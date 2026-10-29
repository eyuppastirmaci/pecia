package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import java.io.IOException;
import java.sql.SQLException;

/** Why the index could not be read or written, so callers can say what to do about it. */
public enum StorageFailure {

    /** The disk or the database reached its size limit. */
    DISK_FULL,
    /** Another process holds the index, for example another indexing run, and did not release it in time. */
    BUSY,
    /** The index or its directory cannot be written. */
    READ_ONLY,
    /** The operating system reported an input or output error. */
    IO_ERROR,
    /** The index file is damaged or is not an index. */
    CORRUPT,
    /** The index belongs to another project directory. */
    WRONG_PROJECT,
    /** The index was written by an unsupported version of Pecia. */
    INCOMPATIBLE,
    /** Any other failure; the message has the detail. */
    OTHER;

    // SQLite primary result codes, from sqlite3.h.
    private static final int SQLITE_BUSY = 5;
    private static final int SQLITE_LOCKED = 6;
    private static final int SQLITE_READONLY = 8;
    private static final int SQLITE_IOERR = 10;
    private static final int SQLITE_CORRUPT = 11;
    private static final int SQLITE_FULL = 13;
    private static final int SQLITE_CANTOPEN = 14;
    private static final int SQLITE_NOTADB = 26;

    /** Classifies a storage failure by its type and, for SQLite, its primary result code. */
    static StorageFailure of(Exception failure) {
        if (failure instanceof IndexAccessException access) {
            return switch (access.reason()) {
                case CORRUPT_INDEX -> CORRUPT;
                case WRONG_PROJECT -> WRONG_PROJECT;
                case INCOMPATIBLE, MIGRATION_REQUIRED -> INCOMPATIBLE;
                case NOT_FOUND -> OTHER;
            };
        }

        if (failure instanceof SQLException sql) {
            // Extended result codes keep the primary code in their low byte.
            return switch (sql.getErrorCode() & 0xff) {
                case SQLITE_FULL -> DISK_FULL;
                case SQLITE_BUSY, SQLITE_LOCKED -> BUSY;
                case SQLITE_READONLY -> READ_ONLY;
                case SQLITE_IOERR, SQLITE_CANTOPEN -> IO_ERROR;
                case SQLITE_CORRUPT, SQLITE_NOTADB -> CORRUPT;
                default -> OTHER;
            };
        }

        return failure instanceof IOException ? IO_ERROR : OTHER;
    }
}
