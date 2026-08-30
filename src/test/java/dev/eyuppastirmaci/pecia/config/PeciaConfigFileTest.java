package dev.eyuppastirmaci.pecia.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeciaConfigFileTest {

    @TempDir
    Path dir;

    private final PeciaConfigFile configFile = new PeciaConfigFile();

    @Test
    void writesDefaultConfigWhenAbsent() throws IOException {
        boolean created = configFile.writeDefault(dir);

        assertTrue(created);
        Path file = dir.resolve(PeciaConfigFile.FILE_NAME);
        assertTrue(Files.exists(file));
        assertEquals(configFile.defaultToml(), Files.readString(file));
    }

    @Test
    void defaultConfigContainsAllSections() {
        String toml = configFile.defaultToml();

        assertTrue(toml.contains("[index]"));
        assertTrue(toml.contains("[chunk]"));
        assertTrue(toml.contains("[embed]"));
        assertTrue(toml.contains("[store]"));
    }

    @Test
    void neverOverwritesAnExistingConfig() throws IOException {
        Path file = dir.resolve(PeciaConfigFile.FILE_NAME);
        Files.writeString(file, "user edited this");

        boolean created = configFile.writeDefault(dir);

        assertFalse(created);
        assertEquals("user edited this", Files.readString(file));
    }
}
