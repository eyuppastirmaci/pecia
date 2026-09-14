package dev.eyuppastirmaci.pecia.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeciaConfigLoaderTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

    @Test
    void fallsBackToNearestGitRootWithoutConfig() throws IOException {
        Files.createDirectory(root.resolve(".git"));
        Path nested = Files.createDirectories(root.resolve("src/deep"));
        PeciaConfigLoader.LoadedConfig loaded = loader.load(nested);
        assertFalse(loaded.fromFile());
        assertEquals(root, loaded.root());
    }

    @Test
    void gitWorktreeMarkerFileAlsoDefinesRoot() throws IOException {
        Files.writeString(root.resolve(".git"), "gitdir: elsewhere");
        Path nested = Files.createDirectories(root.resolve("src"));
        assertEquals(root, loader.load(nested).root());
    }

    @Test
    void findsConfigInTheStartDirectory() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 512\n");

        PeciaConfigLoader.LoadedConfig loaded = loader.load(root);

        assertTrue(loaded.fromFile());
        assertEquals(root, loaded.root());
        assertEquals(512, loaded.config().maxTokens());
    }

    @Test
    void findsConfigInAnAncestorDirectory() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 512\n");
        Path nested = Files.createDirectories(root.resolve("a/b"));

        PeciaConfigLoader.LoadedConfig loaded = loader.load(nested);

        assertTrue(loaded.fromFile());
        assertEquals(root, loaded.root());
    }

    @Test
    void nearestConfigWinsOverAnAncestorOne() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 512\n");
        Path nested = Files.createDirectories(root.resolve("sub"));
        Files.writeString(nested.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\n");

        PeciaConfigLoader.LoadedConfig loaded = loader.load(nested);

        assertEquals(nested, loaded.root());
        assertEquals(128, loaded.config().maxTokens());
    }

    @Test
    void malformedConfigFileIsRejected() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk\nmax_tokens =");

        assertThrows(IllegalArgumentException.class, () -> loader.load(root));
    }

    @Test
    void fallsBackToDefaultsWhenNothingIsFound() throws IOException {
        PeciaConfigLoader.LoadedConfig loaded = loader.load(root);

        assertFalse(loaded.fromFile());
        assertEquals(root, loaded.root());
        assertEquals(PeciaConfig.defaults(), loaded.config());
    }
}
