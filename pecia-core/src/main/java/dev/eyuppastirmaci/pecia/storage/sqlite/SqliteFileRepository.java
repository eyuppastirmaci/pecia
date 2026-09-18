package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.mapper.StoredFileRowMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads and writes file manifest entries using the owning storage's connection. */
public final class SqliteFileRepository {
    private static final String COLUMNS = "id, source_path, document_type, content_hash";
    private final Connection connection;
    private final StoredFileRowMapper mapper = new StoredFileRowMapper();

    SqliteFileRepository(Connection connection) {
        this.connection = connection;
    }

    /** Acquires the write lock and preserves an existing file ID without a read-before-write race. */
    StoredFile save(Path sourcePath, DocumentType type, ContentHash hash) throws SQLException {
        try (var statement = connection.prepareStatement("""
            INSERT INTO files(source_path, document_type, content_hash) VALUES (?, ?, ?)
            ON CONFLICT(source_path) DO UPDATE SET
                document_type = excluded.document_type, content_hash = excluded.content_hash
            RETURNING id, source_path, document_type, content_hash
            """)) {
            statement.setString(1, SqlitePath.encode(sourcePath));
            statement.setString(2, type.name());
            statement.setString(3, hash.value());

            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("File save returned no row");
                }

                return mapper.map(row);
            }
        }
    }

    /**
     * Inserts a new manifest entry without replacing an existing path.
     *
     * @param sourcePath the normalized project-relative source path
     * @param documentType the source processing family
     * @param contentHash the raw-byte SHA-256 digest
     * @return the inserted file with its database-assigned ID
     * @throws SQLException if the path already exists or insertion fails
     * @throws IllegalArgumentException if the source path is invalid
     * @throws NullPointerException if a required value is null
     */
    public StoredFile insert(Path sourcePath, DocumentType documentType, ContentHash contentHash) throws SQLException {
        String path = SqlitePath.encode(sourcePath);
        String type = documentType.name();
        String hash = contentHash.value();

        try (var statement = connection.prepareStatement(
                "INSERT INTO files(source_path, document_type, content_hash) VALUES (?, ?, ?) RETURNING"
                        + " "
                        + COLUMNS)) {
            statement.setString(1, path);
            statement.setString(2, type);
            statement.setString(3, hash);

            try (var row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("File insertion returned no row");
                }

                return mapper.map(row);
            }
        }
    }

    /**
     * Finds a manifest entry by its exact project-relative path.
     *
     * @param sourcePath the normalized project-relative source path
     * @return the matching file or empty when absent
     * @throws SQLException if lookup or mapping fails
     * @throws IllegalArgumentException if the path is invalid
     * @throws NullPointerException if sourcePath is null
     */
    public Optional<StoredFile> findByPath(Path sourcePath) throws SQLException {
        String path = SqlitePath.encode(sourcePath);

        try (var statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM files WHERE source_path = ?")) {
            statement.setString(1, path);

            try (var row = statement.executeQuery()) {
                return row.next() ? Optional.of(mapper.map(row)) : Optional.empty();
            }
        }
    }

    /**
     * Returns the manifest ordered by its stored binary source paths.
     *
     * @return an immutable list of stored files
     * @throws SQLException if reading or mapping fails
     */
    public List<StoredFile> findAll() throws SQLException {
        List<StoredFile> files = new ArrayList<>();

        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT " + COLUMNS + " FROM files ORDER BY source_path COLLATE BINARY")) {
            while (rows.next()) {
                files.add(mapper.map(rows));
            }
        }

        return List.copyOf(files);
    }

    /**
     * Updates a manifest entry by ID within the connection's current transaction.
     *
     * @param file the replacement manifest values whose related chunks the caller must keep
     *     consistent
     * @return true when the ID existed
     * @throws SQLException if the path conflicts or updating fails
     * @throws NullPointerException if file is null
     */
    public boolean update(StoredFile file) throws SQLException {
        String path = SqlitePath.encode(file.sourcePath());

        try (var statement = connection.prepareStatement(
                "UPDATE files SET source_path = ?, document_type = ?, content_hash = ? WHERE id = ?")) {
            statement.setString(1, path);
            statement.setString(2, file.documentType().name());
            statement.setString(3, file.contentHash().value());
            statement.setLong(4, file.id());

            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Deletes a manifest entry and its dependent rows by ID.
     *
     * @param id the positive database file ID
     * @return true when the ID existed
     * @throws SQLException if deletion fails
     * @throws IllegalArgumentException if id is not positive
     */
    public boolean delete(long id) throws SQLException {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive: " + id);
        }

        try (var statement = connection.prepareStatement("DELETE FROM files WHERE id = ?")) {
            statement.setLong(1, id);

            return statement.executeUpdate() == 1;
        }
    }
}
