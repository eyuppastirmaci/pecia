package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * A canonical snapshot of an index, comparable between indexes built under different project roots.
 *
 * <p>The dump excludes surrogate keys ({@code files.id}, {@code chunks.id}), FTS rowids, and the absolute project
 * root, so two indexes are equal exactly when their manifest, chunks, FTS rows, embeddings, and ranked results
 * agree. FTS rows
 * are keyed by their chunk's path and position, which also covers chunks without a stable ID. It depends only on
 * the JDK, the SQLite driver, and public core APIs, so isolated packaged-core consumers can use it as well.
 */
public record IndexDump(
        int formatVersion,
        List<FileEntry> files,
        List<ChunkEntry> chunks,
        List<FtsEntry> ftsRows,
        List<EmbeddingEntry> embeddings,
        List<QueryEntry> queries) {

    public IndexDump {
        files = List.copyOf(files);
        chunks = List.copyOf(chunks);
        ftsRows = List.copyOf(ftsRows);
        embeddings = List.copyOf(embeddings);
        queries = List.copyOf(queries);
    }

    /**
     * Reads the stored state in one read transaction, then runs each query through the public lexical search API
     * with the maximum result limit. Callers must not index concurrently, because queries use a second connection.
     */
    public static IndexDump read(Path database, Path projectRoot, List<String> queries)
            throws IOException, SQLException, SearchException {
        IndexDump stored;

        try (Connection connection = openReadOnly(database)) {
            connection.setAutoCommit(false);
            stored = new IndexDump(
                    formatVersion(connection),
                    files(connection),
                    chunks(connection),
                    ftsRows(connection),
                    embeddings(connection),
                    List.of());
            connection.rollback();
        }

        List<QueryEntry> results = new ArrayList<>();

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, projectRoot)) {
            for (String query : queries) {
                List<HitEntry> hits = new ArrayList<>();

                for (SearchHit hit :
                        storage.lexicalSearch().search(new SearchRequest(query, SearchRequest.MAX_LIMIT))) {
                    hits.add(HitEntry.of(hit));
                }

                results.add(new QueryEntry(query, hits));
            }
        }

        return new IndexDump(
                stored.formatVersion(),
                stored.files(),
                stored.chunks(),
                stored.ftsRows(),
                stored.embeddings(),
                results);
    }

    /** Returns this dump without its embeddings, to compare the lexical parts of two indexes. */
    public IndexDump withoutEmbeddings() {
        return new IndexDump(formatVersion, files, chunks, ftsRows, List.of(), queries);
    }

    /**
     * Serializes the dump with one entry per line, escaping backslashes and line breaks, so dumps produced by
     * separate processes can be exchanged as text and compared exactly.
     */
    public String toText() {
        StringBuilder text = new StringBuilder("format " + formatVersion + "\n");
        files.forEach(file -> appendLine(text, "file", file));
        chunks.forEach(chunk -> appendLine(text, "chunk", chunk));
        ftsRows.forEach(row -> appendLine(text, "fts", row));
        embeddings.forEach(embedding -> appendLine(text, "embedding", embedding));

        for (QueryEntry query : queries) {
            appendLine(text, "query", query.query());
            query.hits().forEach(hit -> appendLine(text, "hit", hit));
        }

        return text.toString();
    }

    private static void appendLine(StringBuilder text, String kind, Object value) {
        String escaped =
                String.valueOf(value).replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
        text.append(kind).append(' ').append(escaped).append('\n');
    }

    private static Connection openReadOnly(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(5000);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }

    private static int formatVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT index_format_version FROM index_metadata")) {
            if (!result.next()) {
                throw new SQLException("Index metadata is missing");
            }

            return result.getInt(1);
        }
    }

    private static List<FileEntry> files(Connection connection) throws SQLException {
        List<FileEntry> files = new ArrayList<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                    SELECT f.source_path, f.document_type, f.content_hash,
                           ip.tokenizer_key, ip.max_tokens, ip.overlap_tokens, cp.fingerprint
                    FROM files f
                    LEFT JOIN file_indexing_profiles ip ON ip.file_id = f.id
                    LEFT JOIN file_chunking_profiles cp ON cp.file_id = f.id
                    ORDER BY f.source_path
                    """)) {
            while (result.next()) {
                files.add(new FileEntry(
                        result.getString(1),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4),
                        nullableInt(result, 5),
                        nullableInt(result, 6),
                        result.getString(7)));
            }
        }

        return files;
    }

    private static List<ChunkEntry> chunks(Connection connection) throws SQLException {
        Map<Long, List<String>> headings = headings(connection);
        Map<Long, Map<String, String>> attributes = attributes(connection);
        List<ChunkEntry> chunks = new ArrayList<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                    SELECT c.id, f.source_path, c.chunk_index, ci.stable_id, c.content, c.start_line, c.end_line
                    FROM chunks c
                    JOIN files f ON f.id = c.file_id
                    LEFT JOIN chunk_identities ci ON ci.chunk_id = c.id
                    ORDER BY f.source_path, c.chunk_index
                    """)) {
            while (result.next()) {
                long id = result.getLong(1);
                chunks.add(new ChunkEntry(
                        result.getString(2),
                        result.getInt(3),
                        result.getString(4),
                        result.getString(5),
                        result.getInt(6),
                        result.getInt(7),
                        headings.getOrDefault(id, List.of()),
                        attributes.getOrDefault(id, Map.of())));
            }
        }

        return chunks;
    }

    private static Map<Long, List<String>> headings(Connection connection) throws SQLException {
        Map<Long, List<String>> headings = new HashMap<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT chunk_id, heading FROM chunk_headings ORDER BY chunk_id, position")) {
            while (result.next()) {
                headings.computeIfAbsent(result.getLong(1), id -> new ArrayList<>())
                        .add(result.getString(2));
            }
        }

        return headings;
    }

    private static Map<Long, Map<String, String>> attributes(Connection connection) throws SQLException {
        Map<Long, Map<String, String>> attributes = new HashMap<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT chunk_id, name, value FROM chunk_attributes ORDER BY chunk_id, name")) {
            while (result.next()) {
                attributes
                        .computeIfAbsent(result.getLong(1), id -> new LinkedHashMap<>())
                        .put(result.getString(2), result.getString(3));
            }
        }

        return attributes;
    }

    private static List<FtsEntry> ftsRows(Connection connection) throws SQLException {
        List<FtsEntry> rows = new ArrayList<>();

        // Orphaned FTS rows have no chunk; they sort last and keep null locators so they still alter the dump.
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("""
                    SELECT f.source_path, c.chunk_index, fts.content, fts.headings, fts.source_path
                    FROM chunks_fts fts
                    LEFT JOIN chunks c ON c.id = fts.rowid
                    LEFT JOIN files f ON f.id = c.file_id
                    ORDER BY f.source_path IS NULL, f.source_path, c.chunk_index, fts.content
                    """)) {
            while (result.next()) {
                rows.add(new FtsEntry(
                        result.getString(1),
                        nullableInt(result, 2),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5)));
            }
        }

        return rows;
    }

    /** Reads vectors, keyed by chunk path and position; indexes from before embeddings have none. */
    private static List<EmbeddingEntry> embeddings(Connection connection) throws SQLException {
        List<EmbeddingEntry> embeddings = new ArrayList<>();

        try (var statement = connection.createStatement()) {
            try (var table = statement.executeQuery(
                    "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name = 'chunk_embeddings'")) {
                if (!table.next() || table.getInt(1) == 0) {
                    return embeddings;
                }
            }

            try (var result = statement.executeQuery("""
                    SELECT f.source_path, c.chunk_index, p.fingerprint, e.vector
                    FROM chunk_embeddings e
                    JOIN chunks c ON c.id = e.chunk_id
                    JOIN files f ON f.id = c.file_id
                    JOIN embedding_profiles p ON p.id = e.profile_id
                    ORDER BY f.source_path, c.chunk_index
                    """)) {
                while (result.next()) {
                    embeddings.add(new EmbeddingEntry(
                            result.getString(1),
                            result.getInt(2),
                            result.getString(3),
                            HexFormat.of().formatHex(sha256(result.getBytes(4)))));
                }
            }
        }

        return embeddings;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }

    private static Integer nullableInt(ResultSet result, int column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    /**
     * A stored file and its processing profiles.
     *
     * @param sourcePath the stored slash-separated project-relative path
     * @param tokenizerKey the legacy indexing profile, null with {@code maxTokens} and {@code overlapTokens} when
     *     absent
     * @param chunkingFingerprint the chunking identity fingerprint, or null when absent
     */
    public record FileEntry(
            String sourcePath,
            String documentType,
            String contentHash,
            String tokenizerKey,
            Integer maxTokens,
            Integer overlapTokens,
            String chunkingFingerprint) {}

    /**
     * A stored chunk in path and position order.
     *
     * @param stableId the deterministic chunk ID, or null for legacy or invalidated state
     * @param attributes metadata attributes in name order, including the UTF-16 source offsets
     */
    public record ChunkEntry(
            String sourcePath,
            int chunkIndex,
            String stableId,
            String content,
            int startLine,
            int endLine,
            List<String> headings,
            Map<String, String> attributes) {

        public ChunkEntry {
            headings = List.copyOf(headings);
            // Preserve name order: Map.copyOf would discard it and make assertion output unstable.
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    /**
     * An FTS row located by its chunk.
     *
     * @param chunkPath the owning chunk's path, or null with {@code chunkIndex} for an orphaned row
     * @param sourcePath the path indexed in the FTS {@code source_path} column
     */
    public record FtsEntry(String chunkPath, Integer chunkIndex, String content, String headings, String sourcePath) {}

    /**
     * A stored vector located by its chunk.
     *
     * @param profileFingerprint the fingerprint of the profile the vector was made with
     * @param vectorSha256 the SHA-256 of the vector's stored bytes
     */
    public record EmbeddingEntry(String sourcePath, int chunkIndex, String profileFingerprint, String vectorSha256) {}

    /** Ranked results for one query, in rank order. */
    public record QueryEntry(String query, List<HitEntry> hits) {

        public QueryEntry {
            hits = List.copyOf(hits);
        }
    }

    /**
     * One ranked hit without its surrogate row locator.
     *
     * @param stableId the deterministic chunk ID, or null when the hit has none
     * @param location the hit's source location in its {@code toString} form
     */
    public record HitEntry(String sourcePath, int chunkIndex, String stableId, String location, double score) {

        static HitEntry of(SearchHit hit) {
            return new HitEntry(
                    SqlitePath.encode(hit.sourcePath()),
                    hit.chunkIndex(),
                    hit.stableId().map(ChunkId::value).orElse(null),
                    hit.sourceLocation().toString(),
                    hit.score().value());
        }
    }
}
