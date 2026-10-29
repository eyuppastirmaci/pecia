package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Processes admitted discovery candidates, borrowing storage owned by the enclosing indexing run.
 */
final class FileIndexer {

    private final FileInspector inspector;
    private final DocumentChunkerFactory chunkers;
    private final ChunkingIdentity identity;

    FileIndexer(ProjectContext context) {
        // The inspector reports an invalid budget with its configuration keys before the chunkers reject it.
        this.inspector = new FileInspector(context);
        PeciaConfig config = context.loadedConfig().config();
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        TokenizerIdentity limits = tokenizer.identity();
        // Chunks leave room for the context line their embedding input starts with, so content is never cut; the
        // constant is inlined, so lexical runs never initialize the semantic class that declares it.
        int maxTokens = Math.min(config.maxTokens(), limits.maxInputTokens() - EmbeddingInput.CONTEXT_TOKENS);
        int overlapTokens = Math.min(config.overlapTokens(), maxTokens - limits.specialTokenCount() - 1);
        this.chunkers = DocumentChunkerFactory.create(tokenizer, maxTokens, overlapTokens);
        this.identity = inspector.identity();
    }

    FileIndexer(
            ProjectContext context,
            DocumentExtractionService extraction,
            DocumentChunkerFactory chunkers,
            ChunkingIdentity identity) {
        this.inspector = new FileInspector(context, extraction, identity);
        this.chunkers = Objects.requireNonNull(chunkers, "chunkers");
        this.identity = identity;
    }

    /**
     * Loads and hashes each candidate, skipping interpretation and writes when stored state matches.
     * Changed files are extracted from those same bytes and atomically replaced with their chunking identity.
     * Extraction failures retain their typed reason; SQL failures remain fatal to the enclosing run.
     * Programming/chunking failures propagate without being disguised as content rejections.
     */
    Result index(Path candidate, SqliteStorage storage) throws ExtractionException, SQLException {
        return switch (prepare(candidate, storage)) {
            case Unchanged unchanged -> unchanged.result();
            case Changed changed -> write(changed, storage);
        };
    }

    /**
     * Inspects a candidate as {@link FileInspector} does and, unless its stored state matches, chunks it without
     * writing, so that its chunks can be embedded before {@link #write(Changed, SqliteStorage, EmbeddingProfile,
     * List)}.
     */
    Prepared prepare(Path candidate, SqliteStorage storage) throws ExtractionException, SQLException {
        return switch (inspector.inspect(candidate, storage)) {
            case FileInspector.Unchanged unchanged -> new Unchanged(new Result(unchanged.file(), 0, true));
            case FileInspector.Readable readable -> {
                Document document = readable.document();

                yield new Changed(document, chunkers.getChunker(document).chunk(document), readable.rechunked());
            }
        };
    }

    /** Replaces a changed file with its chunks and no embeddings. */
    Result write(Changed changed, SqliteStorage storage) throws SQLException {
        StoredFile file = storage.replaceFile(changed.document(), changed.chunks(), identity);

        return new Result(file, changed.chunks().size(), false, changed.rechunked(), 0);
    }

    /** Replaces a changed file with its chunks and their embeddings, one per chunk in order, in one transaction. */
    Result write(Changed changed, SqliteStorage storage, EmbeddingProfile profile, List<float[]> vectors)
            throws SQLException {
        StoredFile file = storage.replaceFile(changed.document(), changed.chunks(), identity, profile, vectors);

        return new Result(file, changed.chunks().size(), false, changed.rechunked(), vectors.size());
    }

    /** A prepared candidate: unchanged, or changed and chunked but not yet written. */
    sealed interface Prepared permits Unchanged, Changed {}

    /** A candidate whose stored file matches its content and chunking. */
    record Unchanged(Result result) implements Prepared {}

    /**
     * A changed candidate's document and chunks, ready to be written.
     *
     * @param rechunked whether only the processing settings changed, as {@link Result} describes
     */
    record Changed(Document document, List<Chunk> chunks, boolean rechunked) implements Prepared {

        /** Returns each chunk's embedding input: its path and heading context, then its content. */
        List<String> embeddingInputs() {
            return chunks.stream()
                    .map(chunk -> EmbeddingInput.of(
                            chunk.sourcePath(), chunk.metadata().headingPath(), chunk.content()))
                    .toList();
        }
    }

    /**
     * Chunk and embedding counts describe writes in this operation; unchanged files always report zero.
     *
     * @param rechunked whether the file's content was unchanged but its chunks were rebuilt, because its stored chunks
     *     were made under other extraction, tokenizer, or chunking settings or have no complete chunking profile
     */
    record Result(StoredFile file, int chunkCount, boolean unchanged, boolean rechunked, int embeddedChunks) {

        Result {
            Objects.requireNonNull(file, "file");

            if (chunkCount < 0 || (unchanged && chunkCount != 0)) {
                throw new IllegalArgumentException("chunkCount must be non-negative and zero for unchanged files");
            }

            if (unchanged && rechunked) {
                throw new IllegalArgumentException("An unchanged file is not rechunked");
            }

            if (embeddedChunks < 0 || embeddedChunks > chunkCount) {
                throw new IllegalArgumentException("embeddedChunks must be between zero and chunkCount");
            }
        }

        Result(StoredFile file, int chunkCount, boolean unchanged) {
            this(file, chunkCount, unchanged, false, 0);
        }
    }
}
