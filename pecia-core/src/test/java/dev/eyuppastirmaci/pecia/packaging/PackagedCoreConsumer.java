package dev.eyuppastirmaci.pecia.packaging;

import dev.eyuppastirmaci.pecia.chunking.DocumentChunkerFactory;
import dev.eyuppastirmaci.pecia.chunking.markdown.MarkdownChunker;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentExtractionService;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.FileContentLoader;
import dev.eyuppastirmaci.pecia.content.FileTypeDetector;
import dev.eyuppastirmaci.pecia.content.TextDocumentExtractor;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.commonmark.parser.Parser;
import org.eclipse.jgit.ignore.FastIgnoreRule;
import org.tomlj.Toml;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PackagedCoreConsumer {

    private PackagedCoreConsumer() {
    }

    /**
     * Exercises the packaged core API as a consumer isolated from the build classpath.
     *
     * @param root temporary project directory used for the fixture
     * @param coreJar expected archive providing every production class and bundled resource
     * @param runtimeDirectory directory containing the core's runtime dependency JARs
     * @throws Exception if the fixture, packaged resources, or core API cannot be used
     * @throws AssertionError if the engine behavior or archive provenance is incorrect
     */
    public static void verify(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        String configuration = """
                [index]
                include = ["docs/*.md"]
                exclude = ["docs/excluded.md"]
                [chunk]
                max_tokens = 64
                overlap_tokens = 0
                """;
        Files.writeString(root.resolve(".pecia.toml"), configuration);
        Files.writeString(root.resolve(".gitignore"), "ignored.md\n");
        Path docs = Files.createDirectory(root.resolve("docs"));
        String markdown = "# Installation\nintro\n## Windows\n- one\n- two\n";
        Path source = Files.writeString(docs.resolve("keep.md"), markdown);
        Files.writeString(docs.resolve("ignored.md"), "ignored by JGit");
        Files.writeString(docs.resolve("excluded.md"), "excluded by configuration");
        Files.writeString(docs.resolve("other.txt"), "not included by configuration");

        IndexService service = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
        IndexPreview preview = service.preview(docs);
        check(preview.target().equals(docs), "Preview must preserve the selected directory");
        check(preview.loadedConfig().fromFile(), "TOML configuration was not loaded");
        check(preview.loadedConfig().root().equals(root), "Configuration must be rooted above the target");
        check(preview.loadedConfig().config().maxTokens() == 64, "TOML chunk settings were not applied");
        check(preview.walkResult().complete(), "The fixture scan must complete");
        check(preview.walkResult().files().equals(List.of(Path.of("keep.md"))),
                "Preview must apply include, exclude, and inherited Git ignore rules with target-relative paths");

        DocumentType type = new FileTypeDetector().detect(source).orElseThrow();
        DocumentExtractionService extraction = new DocumentExtractionService(
                new FileContentLoader(preview.loadedConfig().config().maxFileBytes()), new TextDocumentExtractor());
        Document document = extraction.extract(new ExtractionRequest(source, Path.of("docs/keep.md"), type));
        check(document.type() == DocumentType.MARKDOWN, "The source must be detected as Markdown");
        check(document.content().equals(markdown), "UTF-8 extraction must preserve the content");
        check(document.contentHash().equals(ContentHash.sha256(markdown.getBytes(StandardCharsets.UTF_8))),
                "Extraction must retain the raw-byte hash");

        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        check(tokenizer.count("Hello world!") == 3, "Bundled WordPiece tokenization must work offline");
        DocumentChunkerFactory factory = DocumentChunkerFactory.create(tokenizer,
                preview.loadedConfig().config().maxTokens(), preview.loadedConfig().config().overlapTokens());
        check(factory.getChunker(document) instanceof MarkdownChunker, "Markdown must use its dedicated strategy");
        List<Chunk> chunks = factory.getChunker(document).chunk(document);
        check(chunks.stream().map(chunk -> chunk.metadata().headingPath()).toList()
                    .equals(List.of(List.of("Installation"), List.of("Installation", "Windows"))),
                "CommonMark chunking must preserve the heading hierarchy");

        for (Chunk chunk : chunks) {
            check(chunk.sourcePath().equals(Path.of("docs/keep.md")), "Chunks must preserve the source identity");
            check(tokenizer.countModelInput(chunk.content()) <= 64, "Chunks must respect the loaded token budget");
        }

        check(Files.readString(root.resolve(".pecia.toml")).equals(configuration), "The source config must be unchanged");
        check(!Files.exists(root.resolve(".pecia")) && !Files.exists(docs.resolve(".pecia")),
                "The shared engine must not create an index during these operations");
        check(!Files.exists(docs.resolve(".pecia.toml")), "Preview must not create a config in its target");
    }

    /* Checks both code sources and resource URLs so development outputs cannot mask an incomplete distribution. */
    private static void verifyArchiveOrigins(Path coreJar, Path runtimeDirectory) throws Exception {
        for (Class<?> type : List.of(IndexService.class, IndexPreview.class, PeciaConfigLoader.class,
                PeciaConfigParser.class, DocumentExtractionService.class, Document.class, FileContentLoader.class,
                TextDocumentExtractor.class, FileTypeDetector.class, MiniLmTokenizer.class,
                DocumentChunkerFactory.class, MarkdownChunker.class, Chunk.class)) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(Files.isSameFile(coreJar, origin), type.getName() + " must load from the packaged core JAR: " + origin);
        }

        for (Class<?> type : List.of(Parser.class, FastIgnoreRule.class, Toml.class)) {
            Path origin = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            check(origin.toString().endsWith(".jar") && Files.isSameFile(runtimeDirectory, origin.getParent()),
                    type.getName() + " must load from a packaged runtime dependency: " + origin);
        }

        String tokenizerResources = "/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/";

        for (String name : List.of(tokenizerResources + "vocab.txt", tokenizerResources + "NOTICE.txt",
                tokenizerResources + "LICENSE.txt", "/META-INF/licenses/commonmark-LICENSE.txt")) {
            URL resource = MiniLmTokenizer.class.getResource(name);
            check(resource != null && resource.getProtocol().equals("jar"), "Resource must load from a JAR: " + name);
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            connection.setUseCaches(false);
            Path archive = Path.of(connection.getJarFileURL().toURI());
            check(Files.isSameFile(coreJar, archive), "Resource must belong to the core JAR: " + name);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
