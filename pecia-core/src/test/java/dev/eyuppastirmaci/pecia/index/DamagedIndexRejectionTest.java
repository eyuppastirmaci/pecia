package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.LegacyIndexFixture;
import dev.eyuppastirmaci.pecia.testing.LegacyIndexFixture.Damage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Damaged and mismatched copies of the current-version fixture must be rejected with their classified reason by every
 * entry point that opens an index, and rejection must leave the copy's bytes unchanged.
 */
class DamagedIndexRejectionTest {

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "TRUNCATED, CORRUPT_INDEX, CORRUPT_INDEX",
        "FOREIGN_PROJECT, WRONG_PROJECT, WRONG_PROJECT",
        "FUTURE_SCHEMA, INCOMPATIBLE, INCOMPATIBLE_INDEX",
        "FORMAT_MISMATCH, INCOMPATIBLE, INCOMPATIBLE_INDEX"
    })
    void everyEntryPointRejectsTheIndexWithoutChangingIt(
            Damage damage, IndexAccessException.Reason storageReason, QueryException.Reason queryReason)
            throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("project")).toRealPath();
        Path database = LegacyIndexFixture.installDamaged(damage, root);
        byte[] damaged = Files.readAllBytes(database);

        IndexAccessException writable = assertThrows(
                IndexAccessException.class,
                () -> SqliteStorage.open(database, root).close());
        assertEquals(storageReason, writable.reason(), writable.getMessage());

        IndexAccessException readOnly = assertThrows(
                IndexAccessException.class,
                () -> SqliteStorage.openReadOnly(database, root).close());
        assertEquals(storageReason, readOnly.reason(), readOnly.getMessage());

        IndexException indexing = assertThrows(IndexException.class, () -> new IndexService(loader).index(root));
        assertEquals(IndexResult.Status.FAILED, indexing.result().status());
        assertEquals(0, indexing.result().indexedFiles());
        IndexAccessException cause = assertInstanceOf(IndexAccessException.class, indexing.getCause());
        assertEquals(storageReason, cause.reason(), cause.getMessage());

        QueryException query = assertThrows(
                QueryException.class, () -> new QueryService(loader).search(root, new SearchRequest("payment")));
        assertEquals(queryReason, query.reason(), query.getMessage());

        assertArrayEquals(damaged, Files.readAllBytes(database), "Rejection must not change the index");
        try (var siblings = Files.list(database.getParent())) {
            assertEquals(1, siblings.count(), "Rejection must not leave journal or write-ahead-log files");
        }
    }
}
