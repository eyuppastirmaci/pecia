package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexServiceTest {

    @TempDir
    Path root;

    private final IndexService service = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

    @Test
    void normalizesTargetAndReturnsDefaultsWithoutWritingFiles() throws IOException {
        Files.writeString(root.resolve("notes.md"), "notes");
        Files.writeString(root.resolve("data.bin"), "data");

        IndexPreview preview = service.preview(root.resolve("unused/.."));

        assertEquals(root.toAbsolutePath().normalize(), preview.target());
        assertFalse(preview.loadedConfig().fromFile());
        assertEquals(root, preview.loadedConfig().root());
        assertEquals(PeciaConfig.defaults(), preview.loadedConfig().config());
        assertEquals(List.of(Path.of("notes.md")), preview.walkResult().files());
        assertTrue(preview.walkResult().complete());
        assertThrows(UnsupportedOperationException.class,
                () -> preview.walkResult().files().add(Path.of("other.md")));
        assertFalse(Files.exists(root.resolve(".pecia.toml")));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void usesProjectRelativeConfigAndIgnoreRulesForAChildTarget() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), """
                [index]
                include = ["docs/*.md"]
                exclude = ["docs/excluded.md"]
                """);
        Files.writeString(root.resolve(".gitignore"), "ignored.md\n");
        Files.writeString(root.resolve("outside.md"), "outside target");
        Path docs = Files.createDirectory(root.resolve("docs"));
        Files.writeString(docs.resolve(".gitignore"), "local.md\n");
        Files.writeString(docs.resolve("keep.md"), "keep");
        Files.writeString(docs.resolve("ignored.md"), "ignored by ancestor");
        Files.writeString(docs.resolve("local.md"), "ignored locally");
        Files.writeString(docs.resolve("excluded.md"), "excluded by config");
        Files.writeString(docs.resolve("Other.java"), "not included by config");

        IndexPreview preview = service.preview(docs);

        assertEquals(docs, preview.target());
        assertTrue(preview.loadedConfig().fromFile());
        assertEquals(root, preview.loadedConfig().root());
        assertEquals(List.of("docs/*.md"), preview.loadedConfig().config().include());
        assertEquals(List.of(Path.of("keep.md")), preview.walkResult().files());
        assertTrue(preview.walkResult().complete());
    }

    @Test
    void usesGitRootAndInheritedIgnoreRulesWithoutConfig() throws IOException {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".gitignore"), "drop.md\n");
        Path docs = Files.createDirectory(root.resolve("docs"));
        Files.writeString(docs.resolve("keep.md"), "keep");
        Files.writeString(docs.resolve("drop.md"), "drop");

        IndexPreview preview = service.preview(docs);

        assertEquals(docs, preview.target());
        assertFalse(preview.loadedConfig().fromFile());
        assertEquals(root, preview.loadedConfig().root());
        assertEquals(PeciaConfig.defaults(), preview.loadedConfig().config());
        assertEquals(List.of(Path.of("keep.md")), preview.walkResult().files());
        assertTrue(preview.walkResult().complete());
    }

    @Test
    void rejectsMissingTargets() {
        assertThrows(IOException.class, () -> service.preview(root.resolve("missing")));
    }

    @Test
    void rejectsFileTargets() throws IOException {
        Path file = Files.writeString(root.resolve("notes.md"), "notes");

        IOException failure = assertThrows(IOException.class, () -> service.preview(file));

        assertTrue(failure.getMessage().contains("Target must be a directory"));
    }

    @Test
    void propagatesMalformedConfig() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index\ninclude =");

        assertThrows(IllegalArgumentException.class, () -> service.preview(root));
    }

    @Test
    void propagatesInvalidGlobPatterns() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[index]\ninclude = [\"[\"]\n");

        assertThrows(IllegalArgumentException.class, () -> service.preview(root));
    }

    @Test
    void returnsUsableFilesAndImmutableIssuesWhenScanIsIncomplete() throws IOException {
        Path invalidIgnore = Files.createDirectories(root.resolve("bad/.gitignore"));
        Files.writeString(root.resolve("good.md"), "good");

        IndexPreview preview = service.preview(root);

        assertEquals(List.of(Path.of("good.md")), preview.walkResult().files());
        assertFalse(preview.walkResult().complete());
        assertEquals(1, preview.walkResult().issues().size());
        assertEquals(invalidIgnore, preview.walkResult().issues().getFirst().path());
        assertFalse(preview.walkResult().issues().getFirst().reason().isBlank());
        assertThrows(UnsupportedOperationException.class, () -> preview.walkResult().issues().clear());
    }

    @Test
    void discoversCandidatesWithoutApplyingContentProcessingSettings() throws IOException {
        String config = """
                [index]
                include = ["**/*.md"]
                max_file_bytes = 1
                [chunk]
                max_tokens = 512
                """;
        Files.writeString(root.resolve(".pecia.toml"), config);
        // The invalid UTF-8 and incompatible processing limits must not affect file discovery.
        byte[] content = {(byte) 0xc3, (byte) 0x28};
        Path candidate = Files.write(root.resolve("candidate.md"), content);

        IndexPreview preview = service.preview(root);

        assertEquals(List.of(Path.of("candidate.md")), preview.walkResult().files());
        assertTrue(preview.walkResult().complete());
        assertEquals(1, preview.loadedConfig().config().maxFileBytes());
        assertEquals(512, preview.loadedConfig().config().maxTokens());
        assertEquals(config, Files.readString(root.resolve(".pecia.toml")));
        assertArrayEquals(content, Files.readAllBytes(candidate));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }
}
