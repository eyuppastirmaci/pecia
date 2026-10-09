package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * Observes which files and chunks an indexing run writes, retains, or removes.
 *
 * <p>Row IDs alone cannot tell this: {@code chunks.id} has no {@code AUTOINCREMENT}, so SQLite may reuse deleted
 * row IDs, and replacements update {@code files.id} in place. Instead, {@link #start} installs persistent audit
 * triggers in the test database that log every row image written to the per-file tables into a test-owned table.
 * Storage validates its own schema objects by name, so these extra objects do not affect indexing or queries.
 * Writes to derived state ({@code chunks_fts}) and index-wide metadata are not logged.
 */
public final class IndexWriteObserver {

    private static final String LOG_TABLE = "pecia_test_write_log";
    private static final String TRIGGER_PREFIX = "pecia_test_audit_";

    /* Each audited table with the log columns its row images provide; R is replaced by OLD or NEW. */
    private static final Map<String, String> AUDITED_COLUMNS = Map.of(
            "files", "R.id, NULL, R.source_path, NULL",
            "chunks", "R.file_id, R.id, NULL, R.chunk_index",
            "chunk_headings", "NULL, R.chunk_id, NULL, NULL",
            "chunk_attributes", "NULL, R.chunk_id, NULL, NULL",
            "chunk_identities", "R.file_id, R.chunk_id, NULL, NULL",
            "file_indexing_profiles", "R.file_id, NULL, NULL, NULL",
            "file_chunking_profiles", "R.file_id, NULL, NULL, NULL");

    private final Path database;
    private final State before;

    private IndexWriteObserver(Path database, State before) {
        this.database = database;
        this.before = before;
    }

    /** Installs the audit triggers if needed, clears earlier log entries, and records the current rows. */
    public static IndexWriteObserver start(Path database) throws SQLException {
        try (Connection connection = open(database)) {
            connection.setAutoCommit(false);
            install(connection);

            try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM " + LOG_TABLE);
            }

            State before = State.read(connection);
            connection.commit();
            return new IndexWriteObserver(database, before);
        }
    }

    /** Classifies files and chunks by comparing the logged writes with the rows before and after the run. */
    public Writes finish() throws SQLException {
        try (Connection connection = open(database)) {
            connection.setAutoCommit(false);
            List<LogEntry> log = LogEntry.readAll(connection);
            State after = State.read(connection);
            connection.rollback();
            return classify(log, after);
        }
    }

    private Writes classify(List<LogEntry> log, State after) {
        // Replay the log in order so reused row IDs resolve to the file or chunk they named at that moment.
        Map<Long, String> filePaths = new HashMap<>(before.filePaths());
        Map<Long, ChunkRow> chunkRows = new HashMap<>(before.chunkRows());
        Set<String> touchedPaths = new HashSet<>();
        Set<ChunkKey> touchedChunks = new HashSet<>();

        for (LogEntry entry : log) {
            if (entry.table().equals("files")) {
                filePaths.put(entry.fileId(), entry.sourcePath());
                touchedPaths.add(entry.sourcePath());
                continue;
            }

            if (entry.table().equals("chunks")) {
                chunkRows.put(entry.chunkId(), new ChunkRow(entry.fileId(), entry.chunkIndex()));
            }

            long fileId;

            if (entry.chunkId() != null) {
                ChunkRow chunk = require(chunkRows, entry.chunkId(), entry);
                fileId = chunk.fileId();
                touchedChunks.add(new ChunkKey(decode(require(filePaths, fileId, entry)), chunk.index()));
            } else {
                fileId = entry.fileId();
            }

            touchedPaths.add(require(filePaths, fileId, entry));
        }

        Set<Path> touchedFiles = new HashSet<>();
        touchedPaths.forEach(path -> touchedFiles.add(decode(path)));

        return new Writes(
                log.size(),
                written(before.files(), after.files(), touchedFiles),
                retained(before.files(), after.files(), touchedFiles),
                removed(before.files(), after.files()),
                written(before.chunks(), after.chunks(), touchedChunks),
                retained(before.chunks(), after.chunks(), touchedChunks),
                removed(before.chunks(), after.chunks()));
    }

    /* Present afterwards and either logged or new; a key that only moved, such as a renamed path, is new. */
    private static <T extends Comparable<T>> SortedSet<T> written(Set<T> before, Set<T> after, Set<T> touched) {
        TreeSet<T> written = new TreeSet<>();

        for (T key : after) {
            if (touched.contains(key) || !before.contains(key)) {
                written.add(key);
            }
        }

        return Collections.unmodifiableSortedSet(written);
    }

    private static <T extends Comparable<T>> SortedSet<T> retained(Set<T> before, Set<T> after, Set<T> touched) {
        TreeSet<T> retained = new TreeSet<>(before);
        retained.retainAll(after);
        retained.removeAll(touched);
        return Collections.unmodifiableSortedSet(retained);
    }

    private static <T extends Comparable<T>> SortedSet<T> removed(Set<T> before, Set<T> after) {
        TreeSet<T> removed = new TreeSet<>(before);
        removed.removeAll(after);
        return Collections.unmodifiableSortedSet(removed);
    }

    private static <V> V require(Map<Long, V> rows, Long id, LogEntry entry) {
        V value = id == null ? null : rows.get(id);

        if (value == null) {
            throw new IllegalStateException("Audit entry refers to an unknown row: " + entry);
        }

        return value;
    }

    private static Path decode(String sourcePath) {
        return SqlitePath.decode(sourcePath);
    }

    private static void install(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + LOG_TABLE + """
                     (
                        seq INTEGER PRIMARY KEY,
                        table_name TEXT NOT NULL,
                        image TEXT NOT NULL,
                        file_id INTEGER,
                        chunk_id INTEGER,
                        source_path TEXT,
                        chunk_index INTEGER
                    )""");

            for (var table : AUDITED_COLUMNS.entrySet()) {
                createTrigger(statement, table.getKey(), "INSERT", List.of("NEW"), table.getValue());
                createTrigger(statement, table.getKey(), "UPDATE", List.of("OLD", "NEW"), table.getValue());
                createTrigger(statement, table.getKey(), "DELETE", List.of("OLD"), table.getValue());
            }
        }
    }

    private static void createTrigger(
            Statement statement, String table, String operation, List<String> images, String columns)
            throws SQLException {
        StringBuilder body = new StringBuilder();

        for (String image : images) {
            body.append("INSERT INTO ")
                    .append(LOG_TABLE)
                    .append("(table_name, image, file_id, chunk_id, source_path, chunk_index) VALUES ('")
                    .append(table)
                    .append("', '")
                    .append(image)
                    .append("', ")
                    .append(columns.replace("R.", image + "."))
                    .append(");\n");
        }

        statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS " + TRIGGER_PREFIX + table + "_"
                + operation.toLowerCase(Locale.ROOT) + " AFTER " + operation + " ON " + table + "\nBEGIN\n"
                + body + "END");
    }

    private static Connection open(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(5000);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }

    /**
     * Files and chunks classified by one observed run. Written entries were logged or are new; retained entries
     * existed before and after without any logged write; removed entries no longer exist.
     *
     * @param writeCount the number of logged row images, zero only when no audited table was written
     */
    public record Writes(
            int writeCount,
            SortedSet<Path> writtenFiles,
            SortedSet<Path> retainedFiles,
            SortedSet<Path> removedFiles,
            SortedSet<ChunkKey> writtenChunks,
            SortedSet<ChunkKey> retainedChunks,
            SortedSet<ChunkKey> removedChunks) {}

    /** A chunk located by its project-relative source path and zero-based position. */
    public record ChunkKey(Path sourcePath, int chunkIndex) implements Comparable<ChunkKey> {

        @Override
        public int compareTo(ChunkKey other) {
            int path = sourcePath.compareTo(other.sourcePath);
            return path != 0 ? path : Integer.compare(chunkIndex, other.chunkIndex);
        }
    }

    private record ChunkRow(long fileId, int index) {}

    private record LogEntry(
            String table, String image, Long fileId, Long chunkId, String sourcePath, Integer chunkIndex) {

        static List<LogEntry> readAll(Connection connection) throws SQLException {
            List<LogEntry> entries = new ArrayList<>();

            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT table_name, image, file_id, chunk_id, source_path,"
                            + " chunk_index FROM " + LOG_TABLE + " ORDER BY seq")) {
                while (result.next()) {
                    entries.add(new LogEntry(
                            result.getString(1),
                            result.getString(2),
                            nullableLong(result, 3),
                            nullableLong(result, 4),
                            result.getString(5),
                            nullableInt(result, 6)));
                }
            }

            return entries;
        }

        private static Long nullableLong(ResultSet result, int column) throws SQLException {
            long value = result.getLong(column);
            return result.wasNull() ? null : value;
        }

        private static Integer nullableInt(ResultSet result, int column) throws SQLException {
            int value = result.getInt(column);
            return result.wasNull() ? null : value;
        }
    }

    private record State(Map<Long, String> filePaths, Map<Long, ChunkRow> chunkRows) {

        static State read(Connection connection) throws SQLException {
            Map<Long, String> files = new HashMap<>();
            Map<Long, ChunkRow> chunks = new HashMap<>();

            try (var statement = connection.createStatement()) {
                try (var result = statement.executeQuery("SELECT id, source_path FROM files")) {
                    while (result.next()) {
                        files.put(result.getLong(1), result.getString(2));
                    }
                }

                try (var result = statement.executeQuery("SELECT id, file_id, chunk_index FROM chunks")) {
                    while (result.next()) {
                        chunks.put(result.getLong(1), new ChunkRow(result.getLong(2), result.getInt(3)));
                    }
                }
            }

            return new State(Map.copyOf(files), Map.copyOf(chunks));
        }

        Set<Path> files() {
            Set<Path> paths = new HashSet<>();
            filePaths.values().forEach(path -> paths.add(decode(path)));
            return paths;
        }

        Set<ChunkKey> chunks() {
            Set<ChunkKey> keys = new HashSet<>();
            chunkRows.values().forEach(row -> keys.add(new ChunkKey(decode(filePaths.get(row.fileId())), row.index())));
            return keys;
        }
    }
}
