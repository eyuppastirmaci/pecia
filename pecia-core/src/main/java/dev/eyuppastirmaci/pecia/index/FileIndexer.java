package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.ExtractionException;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
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
    private final FileTypeDetector types = new FileTypeDetector();

    FileIndexer(ProjectContext context) {
        PeciaConfig config = context.loadedConfig().config();
        FileContentLoader contentLoader = new FileContentLoader(config.maxFileBytes());
        TextDocumentExtractor extractor = new TextDocumentExtractor();
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

        this.context = context;
        this.extraction = new DocumentExtractionService(contentLoader, extractor);
        this.chunkers = DocumentChunkerFactory.create(tokenizer, config.maxTokens(), config.overlapTokens());
    }

    FileIndexer(ProjectContext context, DocumentExtractionService extraction, DocumentChunkerFactory chunkers) {
        this.context = Objects.requireNonNull(context, "context");
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.chunkers = Objects.requireNonNull(chunkers, "chunkers");
    }

    /**
     * Extracts and chunks completely before atomically replacing the file's manifest and chunks.
     * Extraction failures retain their typed reason; SQL failures remain fatal to the enclosing run.
     * Programming/chunking failures propagate without being disguised as content rejections.
     */
    Result index(Path candidate, SqliteStorage storage) throws ExtractionException, SQLException {
        Objects.requireNonNull(storage, "storage");
        Path absolute = context.absoluteSource(candidate);

        if (context.storageFiles().contains(absolute)) {
            throw new IllegalArgumentException("Index storage cannot be a source candidate: " + candidate);
        }

        ExtractionRequest request =
                new ExtractionRequest(absolute, context.sourcePath(candidate), types.typeForCandidate(candidate));
        Document document = extraction.extract(request);
        List<Chunk> chunks = chunkers.getChunker(document).chunk(document);
        StoredFile file = storage.replaceFile(document, chunks);

        return new Result(file, chunks.size());
    }

    record Result(StoredFile file, int chunkCount) {

        Result {
            Objects.requireNonNull(file, "file");

            if (chunkCount < 0) {
                throw new IllegalArgumentException("chunkCount must not be negative");
            }
        }
    }
}
