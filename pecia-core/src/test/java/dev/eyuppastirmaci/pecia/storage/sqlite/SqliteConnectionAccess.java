package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.sql.Connection;

/** Gives tests outside this package the connection a storage owns, to run checks on it. */
public final class SqliteConnectionAccess {

    private SqliteConnectionAccess() {}

    public static Connection connection(SqliteStorage storage) {
        return storage.connection();
    }
}
