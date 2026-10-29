package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContent;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what a run would do with one candidate: loads and hashes its bytes, compares them with the stored index,
 * and extracts its text unless the stored file is unchanged. It never chunks or writes, so a dry run uses it as it
 * is, and it never initializes the tokenizer.
 */
final class FileInspector {

    private final ProjectContext context;
    private final DocumentExtractionService extraction;
    private final ChunkingIdentity identity;
    private final FileTypeDetector types = new FileTypeDetector();

    /**
     * Creates an inspector for the project's configuration.
     *
     * @throws IllegalArgumentException if the chunk budget is invalid, reported with its configuration keys
     */
    FileInspector(ProjectContext context) {
        PeciaConfig config = context.loadedConfig().config();
        TokenizerIdentity tokenizer = TokenizerIdentity.MINILM;
        config.requireChunkBudget(tokenizer.maxInputTokens(), tokenizer.specialTokenCount());

        this.context = context;
        this.extraction = new DocumentExtractionService(
                new FileContentLoader(config.maxFileBytes()), new TextDocumentExtractor());
        this.identity = ChunkingIdentity.from(config, tokenizer);
    }

    FileInspector(ProjectContext context, DocumentExtractionService extraction, ChunkingIdentity identity) {
        this.context = Objects.requireNonNull(context, "context");
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    ChunkingIdentity identity() {
        return identity;
    }

    /**
     * Loads and hashes a candidate and, unless its stored state matches, extracts its text.
     *
     * @throws ExtractionException if the file cannot be read or its content is rejected, with its typed reason
     * @throws SQLException if storage cannot be read
     */
    Inspection inspect(Path candidate, SqliteStorage storage) throws ExtractionException, SQLException {
        Objects.requireNonNull(storage, "storage");
        ExtractionRequest request = request(candidate);
        FileContent content = extraction.load(request);
        Optional<StoredFile> unchanged = storage.findUnchangedFile(request, content.contentHash(), identity);

        if (unchanged.isPresent()) {
            return new Unchanged(unchanged.orElseThrow());
        }

        Optional<StoredFile> stored = storage.files().findByPath(request.sourcePath());
        // Same path and bytes but not unchanged: the stored chunks were made under other processing settings. A
        // different document type for the same path can only come from changed detection rules, which are such
        // settings too.
        Kind kind = stored.isEmpty()
                ? Kind.NEW
                : stored.orElseThrow().contentHash().equals(content.contentHash()) ? Kind.RECHUNKED : Kind.CHANGED;

        return extract(request, content, kind);
    }

    /**
     * Inspects a candidate of a project that has no index yet, so a readable candidate is new.
     *
     * @throws ExtractionException if the file cannot be read or its content is rejected, with its typed reason
     */
    Readable inspectNew(Path candidate) throws ExtractionException {
        ExtractionRequest request = request(candidate);

        return extract(request, extraction.load(request), Kind.NEW);
    }

    private ExtractionRequest request(Path candidate) {
        Path absolute = context.absoluteSource(candidate);

        if (context.storageFiles().contains(absolute)) {
            throw new IllegalArgumentException("Index storage cannot be a source candidate: " + candidate);
        }

        return new ExtractionRequest(absolute, context.sourcePath(candidate), types.typeForCandidate(candidate));
    }

    private Readable extract(ExtractionRequest request, FileContent content, Kind kind) throws ExtractionException {
        Document document = extraction.extract(request, content);

        if (!document.contentHash().equals(content.contentHash())
                || !document.sourcePath().equals(request.sourcePath())
                || document.type() != request.type()) {
            throw new IllegalArgumentException("Extracted document must preserve the loaded source identity and hash");
        }

        return new Readable(document, kind, content.size());
    }

    /** What a run would do with a candidate. */
    sealed interface Inspection permits Unchanged, Readable {}

    /** A candidate whose stored file matches its content and chunking; a run skips it. */
    record Unchanged(StoredFile file) implements Inspection {}

    /**
     * A candidate a run would extract, chunk, and write.
     *
     * @param size the file's size in bytes
     */
    record Readable(Document document, Kind kind, long size) implements Inspection {

        /** Requires a document and a kind. */
        Readable {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(kind, "kind");
        }

        boolean rechunked() {
            return kind == Kind.RECHUNKED;
        }
    }

    /** Why a run would write a candidate. */
    enum Kind {

        /** The index has no file at this path. */
        NEW,
        /** The stored file has other content. */
        CHANGED,
        /** The stored file has the same content but was chunked under other settings. */
        RECHUNKED
    }
}
