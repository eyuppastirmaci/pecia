package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads ordered headings and attributes in two queries within the caller's snapshot. */
final class SqliteChunkMetadataReader {
    private final Connection connection;

    SqliteChunkMetadataReader(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    Map<Long, ChunkMetadata> readForFile(long fileId) throws SQLException {
        return read("SELECT id FROM chunks WHERE file_id = ?", List.of(fileId));
    }

    Map<Long, ChunkMetadata> readForChunks(List<Long> chunkIds) throws SQLException {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }

        return read(String.join(",", Collections.nCopies(chunkIds.size(), "?")), chunkIds);
    }

    private Map<Long, ChunkMetadata> read(String selection, List<Long> parameters) throws SQLException {
        Map<Long, List<String>> headings = new HashMap<>();
        Map<Long, Map<String, String>> attributes = new HashMap<>();

        // Only fixed SQL and generated placeholders form the selection; IDs are always bound
        // parameters.
        try (var query = connection.prepareStatement("""
            SELECT chunk_id, position, heading FROM chunk_headings
            WHERE chunk_id IN (%s) ORDER BY chunk_id, position
            """.formatted(selection))) {
            bind(query, parameters);

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
            SELECT chunk_id, name, value FROM chunk_attributes WHERE chunk_id IN (%s)
            """.formatted(selection))) {
            bind(query, parameters);

            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    attributes
                            .computeIfAbsent(rows.getLong(1), ignored -> new HashMap<>())
                            .put(rows.getString(2), rows.getString(3));
                }
            }
        }

        var ids = new HashSet<>(headings.keySet());
        ids.addAll(attributes.keySet());
        Map<Long, ChunkMetadata> result = new HashMap<>();

        try {
            for (long id : ids) {
                result.put(
                        id,
                        new ChunkMetadata(headings.getOrDefault(id, List.of()), attributes.getOrDefault(id, Map.of())));
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored chunk metadata", invalid);
        }

        return Map.copyOf(result);
    }

    private static void bind(PreparedStatement statement, List<Long> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setLong(index + 1, values.get(index));
        }
    }
}
