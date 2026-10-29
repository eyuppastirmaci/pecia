package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.sql.SQLException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Storage failures are classified by SQLite's primary result code and by index-access reason. */
class StorageFailureTest {

    @ParameterizedTest(name = "SQLite code {0} -> {1}")
    @CsvSource({
        "13, DISK_FULL",
        "5, BUSY",
        // SQLITE_BUSY_SNAPSHOT, an extended code whose low byte is SQLITE_BUSY.
        "517, BUSY",
        "6, BUSY",
        "8, READ_ONLY",
        // SQLITE_READONLY_DBMOVED.
        "1032, READ_ONLY",
        "10, IO_ERROR",
        // SQLITE_IOERR_WRITE.
        "778, IO_ERROR",
        "14, IO_ERROR",
        "11, CORRUPT",
        "26, CORRUPT",
        // SQLITE_CONSTRAINT_TRIGGER, raised by a trigger.
        "1811, OTHER",
        "1, OTHER",
        "0, OTHER"
    })
    void sqliteFailuresAreClassifiedByTheirPrimaryCode(int code, StorageFailure expected) {
        assertEquals(expected, StorageFailure.of(new SQLException("failure", null, code)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "CORRUPT_INDEX, CORRUPT",
        "WRONG_PROJECT, WRONG_PROJECT",
        "INCOMPATIBLE, INCOMPATIBLE",
        "MIGRATION_REQUIRED, INCOMPATIBLE",
        "NOT_FOUND, OTHER"
    })
    void indexAccessFailuresKeepTheirMeaning(IndexAccessException.Reason reason, StorageFailure expected) {
        assertEquals(expected, StorageFailure.of(new IndexAccessException(reason, "failure")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"io", "denied"})
    void filesystemFailuresAreInputOutputErrors(String kind) {
        IOException failure = kind.equals("io") ? new IOException("failure") : new AccessDeniedException(".pecia");

        assertEquals(StorageFailure.IO_ERROR, StorageFailure.of(failure));
    }
}
