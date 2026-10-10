package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

/** Opening an existing index for writes classifies validation failures the same way a read-only open does. */
class SqliteWritableValidationTest {

    @TempDir
    Path root;

    private Path database;

    @BeforeEach
    void createIndex() throws Exception {
        root = root.toRealPath();
        database = root.resolve(".pecia/index.db");
        SqliteStorage.open(database, root).close();
    }

    @Test
    void futureSchemaVersionIsIncompatible() throws Exception {
        int current;

        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("PRAGMA user_version")) {
                current = result.getInt(1);
            }
            statement.execute("PRAGMA user_version = " + (current + 1));
        }

        byte[] before = Files.readAllBytes(database);
        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.open(database, root));

        assertEquals(IndexAccessException.Reason.INCOMPATIBLE, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void foreignUnversionedDatabaseIsIncompatible() throws Exception {
        Files.delete(database);
        try (var connection =
                        JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE notes(text TEXT)");
        }

        byte[] before = Files.readAllBytes(database);
        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.open(database, root));

        assertEquals(IndexAccessException.Reason.INCOMPATIBLE, failure.reason());
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    @Test
    void truncatedIndexIsCorrupt() throws Exception {
        byte[] bytes = Files.readAllBytes(database);
        int pageSize = ((bytes[16] & 0xff) << 8) | (bytes[17] & 0xff);
        byte[] truncated = Arrays.copyOf(bytes, pageSize * 2 + pageSize / 2);
        Files.write(database, truncated);

        IndexAccessException failure =
                assertThrows(IndexAccessException.class, () -> SqliteStorage.open(database, root));

        assertEquals(IndexAccessException.Reason.CORRUPT_INDEX, failure.reason());
        assertArrayEquals(truncated, Files.readAllBytes(database));
    }
}
