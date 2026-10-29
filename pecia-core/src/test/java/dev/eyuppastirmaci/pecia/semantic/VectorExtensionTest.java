package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteConnectionAccess;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Places the bundled sqlite-vec library in the cache and loads it into storage on the platform running the tests. */
class VectorExtensionTest {

    private static final String OS_NAME = System.getProperty("os.name");
    private static final String OS_ARCH = System.getProperty("os.arch");
    private static final Function<String, InputStream> BUNDLED = VectorExtension.class::getResourceAsStream;

    @TempDir
    Path sandbox;

    @ParameterizedTest
    @CsvSource({
        "Linux, amd64, linux-x64/vec0.so",
        "Linux, aarch64, linux-aarch64/vec0.so",
        "Mac OS X, aarch64, osx-aarch64/vec0.dylib",
        "Windows 11, amd64, win-x64/vec0.dll"
    })
    void everyBundledLibraryIsCopiedIntoTheVersionedCacheDirectory(String osName, String osArch, String file)
            throws Exception {
        Path library = VectorExtension.library(osName, osArch, sandbox, BUNDLED);

        assertEquals(sandbox.resolve("native/sqlite-vec/0.1.9").resolve(file), library);
        assertArrayEquals(bundled(file), Files.readAllBytes(library));
    }

    @Test
    void aVerifiedCopyIsReusedAndADamagedOneReplaced() throws Exception {
        Path library = VectorExtension.library(OS_NAME, OS_ARCH, sandbox, BUNDLED);
        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(library, old);

        assertEquals(library, VectorExtension.library(OS_NAME, OS_ARCH, sandbox, BUNDLED));
        assertEquals(old, Files.getLastModifiedTime(library), "A verified copy is not written again");

        Files.writeString(library, "damaged");
        assertEquals(library, VectorExtension.library(OS_NAME, OS_ARCH, sandbox, BUNDLED));
        assertArrayEquals(
                Files.readAllBytes(VectorExtension.library(OS_NAME, OS_ARCH, freshCache(), BUNDLED)),
                Files.readAllBytes(library));
        try (var leftovers = Files.list(library.getParent())) {
            assertEquals(1, leftovers.count(), "No temporary copy is left behind");
        }
    }

    @ParameterizedTest
    @CsvSource({"Mac OS X, x86_64", "Windows 11, aarch64", "SunOS, sparcv9", "Linux, ppc64le"})
    void platformsWithoutABundledLibraryAreRefused(String osName, String osArch) {
        VectorExtensionException failure = assertThrows(
                VectorExtensionException.class, () -> VectorExtension.library(osName, osArch, sandbox, BUNDLED));

        assertEquals(
                "No sqlite-vec library is bundled for " + osName + " on " + osArch + "; semantic search runs on "
                        + RuntimePlatform.SUPPORTED,
                failure.getMessage());
        assertFalse(Files.exists(sandbox.resolve("native")));
    }

    @Test
    void aMissingOrAlteredBundledLibraryIsRefusedWithoutWritingTheCache() {
        Function<String, InputStream> missing = resource -> null;
        Function<String, InputStream> altered =
                resource -> new ByteArrayInputStream("altered".getBytes(StandardCharsets.UTF_8));

        assertTrue(assertThrows(
                        VectorExtensionException.class,
                        () -> VectorExtension.library(OS_NAME, OS_ARCH, sandbox, missing))
                .getMessage()
                .endsWith("is missing"));
        assertTrue(assertThrows(
                        VectorExtensionException.class,
                        () -> VectorExtension.library(OS_NAME, OS_ARCH, sandbox, altered))
                .getMessage()
                .endsWith("does not match its pinned SHA-256"));
        assertFalse(Files.exists(sandbox.resolve("native")));
    }

    @Test
    void anUnwritableCacheIsReported() throws Exception {
        Path file = Files.writeString(sandbox.resolve("not-a-directory"), "");

        VectorExtensionException failure = assertThrows(
                VectorExtensionException.class, () -> VectorExtension.library(OS_NAME, OS_ARCH, file, BUNDLED));

        assertTrue(failure.getMessage().startsWith("Could not place the sqlite-vec library in "), failure.getMessage());
    }

    @Test
    void readOnlyStorageLoadsTheExtensionAndThenRefusesFurtherLoading() throws Exception {
        Path project = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        Path database = project.resolve("index.db");
        SqliteStorage.open(database, project).close();

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, project)) {
            // Lexical storage cannot reach vector functions or load an extension through SQL.
            assertThrows(SQLException.class, () -> scalar(storage, "SELECT vec_version()"));
            assertNotAuthorized(storage);

            storage.loadVectorExtension(VectorExtension.library(OS_NAME, OS_ARCH, sandbox.resolve("cache"), BUNDLED));

            assertEquals("v0.1.9", scalar(storage, "SELECT vec_version()"));
            assertEquals(
                    "0.0",
                    scalar(storage, "SELECT round(vec_distance_cosine(vec_f32('[1, 0]'), vec_f32('[1, 0]')), 3)"));
            assertNotAuthorized(storage);
        }
    }

    /** A missing file fails too, so only SQLite's authorization error proves that loading is turned off. */
    private static void assertNotAuthorized(SqliteStorage storage) {
        SQLException failure =
                assertThrows(SQLException.class, () -> scalar(storage, "SELECT load_extension('anything')"));

        assertTrue(failure.getMessage().contains("not authorized"), failure.getMessage());
    }

    private Path freshCache() throws IOException {
        return Files.createDirectories(sandbox.resolve("fresh"));
    }

    private static byte[] bundled(String file) throws IOException {
        try (InputStream input = BUNDLED.apply("sqlite-vec/" + file)) {
            return input.readAllBytes();
        }
    }

    /** Runs a one-value query on the storage's own connection. */
    private static String scalar(SqliteStorage storage, String sql) throws SQLException {
        try (var statement = SqliteConnectionAccess.connection(storage).createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
