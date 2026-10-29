package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContent;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
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

    private final ProjectContext context;
    private final DocumentExtractionService extraction;
    private final DocumentChunkerFactory chunkers;
    private final ChunkingIdentity identity;
    private final FileTypeDetector types = new FileTypeDetector();

    FileIndexer(ProjectContext context) {
        PeciaConfig config = context.loadedConfig().config();
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        TokenizerIdentity limits = tokenizer.identity();
        // Report an invalid budget with its configuration keys before the chunkers reject it with internal names.
        config.requireChunkBudget(limits.maxInputTokens(), limits.specialTokenCount());
        FileContentLoader contentLoader = new FileContentLoader(config.maxFileBytes());
        TextDocumentExtractor extractor = new TextDocumentExtractor();

        this.context = context;
        this.extraction = new DocumentExtractionService(contentLoader, extractor);
        // Chunks leave room for the context line their embedding input starts with, so content is never cut; the
        // constant is inlined, so lexical runs never initialize the semantic class that declares it.
        int maxTokens = Math.min(config.maxTokens(), limits.maxInputTokens() - EmbeddingInput.CONTEXT_TOKENS);
        int overlapTokens = Math.min(config.overlapTokens(), maxTokens - limits.specialTokenCount() - 1);
        this.chunkers = DocumentChunkerFactory.create(tokenizer, maxTokens, overlapTokens);
        this.identity = ChunkingIdentity.from(config, tokenizer.identity());
    }

    FileIndexer(
            ProjectContext context,
            DocumentExtractionService extraction,
            DocumentChunkerFactory chunkers,
            ChunkingIdentity identity) {
        this.context = Objects.requireNonNull(context, "context");
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.chunkers = Objects.requireNonNull(chunkers, "chunkers");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    /**
     * Loads and hashes each candidate, skipping interpretation and writes when stored state matches.
     * Changed files are extracted from those same bytes and atomically replaced with their chunking identity.
     * Extraction failures retain their typed reason; SQL failures remain fatal to the enclosing run.
     * Programming/chunking failures propagate without being disguised as content rejections.
     */
    Result index(Path candidate, SqliteStorage storage) throws ExtractionException, SQLException {
        try {
            return index(candidate, storage, null);
        } catch (EmbeddingException impossible) {
            throw new IllegalStateException("A lexical replacement cannot embed", impossible);
        }
    }

    /**
     * Indexes like {@link #index(Path, SqliteStorage)}; when {@code semantic} is not null, a changed file's chunks are
     * embedded first and stored with their vectors in the same replacement. Unchanged files are not embedded.
     *
     * @throws EmbeddingException if embedding fails, before the file is written
     */
    Result index(Path candidate, SqliteStorage storage, SemanticIndexing semantic)
            throws ExtractionException, SQLException, EmbeddingException {
        Objects.requireNonNull(storage, "storage");
        Path absolute = context.absoluteSource(candidate);

        if (context.storageFiles().contains(absolute)) {
            throw new IllegalArgumentException("Index storage cannot be a source candidate: " + candidate);
        }

        ExtractionRequest request =
                new ExtractionRequest(absolute, context.sourcePath(candidate), types.typeForCandidate(candidate));
        FileContent content = extraction.load(request);
        var unchanged = storage.findUnchangedFile(request, content.contentHash(), identity);

        if (unchanged.isPresent()) {
            return new Result(unchanged.orElseThrow(), 0, true);
        }

        // Same path and bytes but not unchanged: the stored chunks were made under other processing settings. A
        // different
        // document type for the same path can only come from changed detection rules, which are such settings too.
        boolean rechunked = storage.files()
                .findByPath(request.sourcePath())
                .filter(stored -> stored.contentHash().equals(content.contentHash()))
                .isPresent();
        Document document = extraction.extract(request, content);

        if (!document.contentHash().equals(content.contentHash())
                || !document.sourcePath().equals(request.sourcePath())
                || document.type() != request.type()) {
            throw new IllegalArgumentException("Extracted document must preserve the loaded source identity and hash");
        }

        List<Chunk> chunks = chunkers.getChunker(document).chunk(document);

        if (semantic == null) {
            return new Result(storage.replaceFile(document, chunks, identity), chunks.size(), false, rechunked, 0);
        }

        List<float[]> vectors = semantic.embed(chunks);
        StoredFile file = storage.replaceFile(document, chunks, identity, semantic.profile(), vectors);

        return new Result(file, chunks.size(), false, rechunked, vectors.size());
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
