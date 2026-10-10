import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.index.GlobFilter;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a schema V1 index with the code of commit 8b56827, which predates IndexService.index: discovery,
 * extraction, chunking, and storage are wired by hand with that commit's default configuration.
 * Usage: java -cp <8b56827 core JAR>:<its runtime JARs> GenerateV1Index.java <project directory>
 */
public class GenerateV1Index {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toRealPath();
        PeciaConfig config = PeciaConfig.defaults();
        List<Path> files = new FileWalker(new GlobFilter(config.include(), config.exclude())).walk(root);
        FileTypeDetector types = new FileTypeDetector();
        DocumentExtractionService extraction = new DocumentExtractionService(
                new FileContentLoader(config.maxFileBytes()), new TextDocumentExtractor());
        DocumentChunkerFactory chunkers =
                DocumentChunkerFactory.create(MiniLmTokenizer.bundled(), config.maxTokens(), config.overlapTokens());

        try (SqliteStorage storage = SqliteStorage.open(root.resolve(config.storePath()), root)) {
            for (Path file : files) {
                Document document =
                        extraction.extract(new ExtractionRequest(root.resolve(file), file, types.typeForCandidate(file)));
                storage.replaceFile(document, chunkers.getChunker(document).chunk(document));
                System.out.println("indexed " + file);
            }
        }
    }
}
