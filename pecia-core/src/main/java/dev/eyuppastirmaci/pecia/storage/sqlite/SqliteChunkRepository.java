package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.mapper.StoredChunkRowMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class SqliteChunkRepository {
    private final SqliteStorage storage;
    private final Connection connection;

    SqliteChunkRepository(SqliteStorage storage) {
        this.storage = storage;
        this.connection = storage.connection();
    }

    /**
     * Atomically inserts a chunk and its metadata for a matching manifest entry.
     *
     * @param fileId the positive manifest ID
     * @param chunk the chunk whose path and type must match that file
     * @return the chunk with its assigned database ID
     * @throws SQLException if the file does not match, the position exists, or writing fails
     * @throws IllegalArgumentException if the ID or source location is unsupported
     * @throws NullPointerException if chunk is null
     */
    public StoredChunk insert(long fileId, Chunk chunk) throws SQLException {
        requireId(fileId);

        if (!(chunk.sourceLocation() instanceof LineRange lines)) {
            throw new IllegalArgumentException("Stored chunks must have a line range");
        }

        return storage.inScope(() -> {
            long id;

            try (var insert = connection.prepareStatement("""
                    INSERT INTO chunks(file_id, chunk_index, content, start_line, end_line)
                    SELECT id, ?, ?, ?, ? FROM files WHERE id = ? AND source_path = ? AND document_type = ?
                    RETURNING id
                    """)) {
                insert.setInt(1, chunk.index());
                insert.setString(2, chunk.content());
                insert.setInt(3, lines.startLine());
                insert.setInt(4, lines.endLine());
                insert.setLong(5, fileId);
                insert.setString(6, SqlitePath.encode(chunk.sourcePath()));
                insert.setString(7, chunk.documentType().name());

                try (var row = insert.executeQuery()) {
                    if (!row.next()) {
                        throw new SQLException("Chunk source identity does not match file ID: " + fileId);
                    }

                    id = row.getLong(1);
                }
            }

            writeMetadata(id, chunk.metadata());

            return new StoredChunk(id, fileId, chunk);
        });
    }

    /**
     * Reads a file's chunks and metadata in index order from one consistent snapshot.
     *
     * @param fileId the positive manifest ID
     * @return an immutable ordered list, empty for a missing file or a file without chunks
     * @throws SQLException if reading or mapping fails
     * @throws IllegalArgumentException if fileId is not positive
     */
    public List<StoredChunk> findByFileId(long fileId) throws SQLException {
        requireId(fileId);

        return storage.inScope(() -> {
            var mapper = new StoredChunkRowMapper(readMetadata(fileId));
            List<StoredChunk> chunks = new ArrayList<>();

            try (var query = connection.prepareStatement("""
                    SELECT c.id, c.file_id, c.chunk_index, c.content, c.start_line, c.end_line,
                           f.source_path, f.document_type
                    FROM chunks c JOIN files f ON f.id = c.file_id WHERE c.file_id = ? ORDER BY c.chunk_index
                    """)) {
                query.setLong(1, fileId);

                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        chunks.add(mapper.map(rows));
                    }
                }
            }

            return List.copyOf(chunks);
        });
    }

    /**
     * Deletes all chunks and dependent metadata for one file while retaining its manifest entry.
     *
     * @param fileId the positive manifest ID
     * @return the number of deleted chunks
     * @throws SQLException if deletion fails
     * @throws IllegalArgumentException if fileId is not positive
     */
    public int deleteByFileId(long fileId) throws SQLException {
        requireId(fileId);

        try (var statement = connection.prepareStatement("DELETE FROM chunks WHERE file_id = ?")) {
            statement.setLong(1, fileId);

            return statement.executeUpdate();
        }
    }

    private void writeMetadata(long id, ChunkMetadata metadata) throws SQLException {
        try (var heading = connection.prepareStatement("INSERT INTO chunk_headings VALUES (?, ?, ?)");
             var attribute = connection.prepareStatement("INSERT INTO chunk_attributes VALUES (?, ?, ?)")) {
            for (int position = 0; position < metadata.headingPath().size(); position++) {
                heading.setLong(1, id);
                heading.setInt(2, position);
                heading.setString(3, metadata.headingPath().get(position));
                heading.executeUpdate();
            }

            for (var entry : metadata.attributes().entrySet()) {
                attribute.setLong(1, id);
                attribute.setString(2, entry.getKey());
                attribute.setString(3, entry.getValue());
                attribute.executeUpdate();
            }
        }
    }

    /* Loads metadata with two file-scoped queries instead of issuing queries for individual chunk rows. */
    private Map<Long, ChunkMetadata> readMetadata(long fileId) throws SQLException {
        Map<Long, List<String>> headings = new HashMap<>();
        Map<Long, Map<String, String>> attributes = new HashMap<>();

        try (var query = connection.prepareStatement("""
                SELECT h.chunk_id, h.position, h.heading FROM chunk_headings h
                JOIN chunks c ON c.id = h.chunk_id WHERE c.file_id = ? ORDER BY h.chunk_id, h.position
                """)) {
            query.setLong(1, fileId);

            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    var path = headings.computeIfAbsent(rows.getLong(1), ignored -> new ArrayList<>());

                    if (rows.getInt(2) != path.size()) {
                        throw new SQLException("Non-contiguous stored heading positions");
                    }

                    path.add(rows.getString(3));
                }
            }
        }

        try (var query = connection.prepareStatement("""
                SELECT a.chunk_id, a.name, a.value FROM chunk_attributes a
                JOIN chunks c ON c.id = a.chunk_id WHERE c.file_id = ?
                """)) {
            query.setLong(1, fileId);

            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    attributes.computeIfAbsent(rows.getLong(1), ignored -> new HashMap<>()).put(rows.getString(2), rows.getString(3));
                }
            }
        }

        var ids = new HashSet<>(headings.keySet());
        ids.addAll(attributes.keySet());
        Map<Long, ChunkMetadata> result = new HashMap<>();

        try {
            for (long id : ids) {
                result.put(id, new ChunkMetadata(headings.getOrDefault(id, List.of()), attributes.getOrDefault(id, Map.of())));
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored chunk metadata", invalid);
        }

        return result;
    }

    private static void requireId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("fileId must be positive: " + id);
        }
    }
}
