package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.JDBC;

class LegacyIndexFixtureTest {

    @TempDir
    Path project;

    @Test
    void readmeListsEveryCommittedChecksum() throws IOException {
        String readme;

        try (InputStream input = LegacyIndexFixtureTest.class.getResourceAsStream("/storage/legacy/README.md")) {
            readme = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        for (int version : LegacyIndexFixture.VERSIONS) {
            String row = "| `schema-v" + version + ".db` |";
            int start = readme.indexOf(row);
            assertTrue(start >= 0, "README must list " + row);
            String line = readme.substring(start, readme.indexOf('\n', start));
            assertTrue(line.contains(LegacyIndexFixture.CHECKSUMS.get(version)), line);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void changedFixtureBytesAreRejected(int version) throws IOException {
        byte[] changed = LegacyIndexFixture.verifiedBytes(version).clone();
        changed[changed.length / 2] ^= 1;

        assertThrows(IllegalStateException.class, () -> LegacyIndexFixture.requireChecksum(version, changed));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void installedCopyBelongsToItsProjectAndMatchesTheCorpus(int version) throws Exception {
        Path root = project.toRealPath();
        byte[] committed = LegacyIndexFixture.verifiedBytes(version);

        Path database = LegacyIndexFixture.install(version, root);

        assertEquals(root.resolve(".pecia/index.db"), database);
        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement()) {
            try (var result =
                    statement.executeQuery("SELECT project_root_uri, index_format_version FROM index_metadata")) {
                assertTrue(result.next());
                assertEquals(root.toUri().toASCIIString(), result.getString(1));
                assertEquals(version, result.getInt(2));
            }
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                assertEquals(version, result.getInt(1));
            }
        }
        for (var file : LexicalTestCorpus.files().entrySet()) {
            assertArrayEquals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())));
        }
        assertArrayEquals(
                committed, LegacyIndexFixture.verifiedBytes(version), "The committed fixture must not change");
    }

    @Test
    void installRejectsAProjectThatIsNotEmpty() throws IOException {
        Files.writeString(project.resolve("existing.txt"), "existing");

        assertThrows(IllegalArgumentException.class, () -> LegacyIndexFixture.install(1, project));
    }
}
