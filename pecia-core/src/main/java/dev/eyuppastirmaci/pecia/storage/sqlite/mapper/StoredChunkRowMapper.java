package dev.eyuppastirmaci.pecia.storage.sqlite.mapper;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public final class StoredChunkRowMapper implements RowMapper<StoredChunk> {
    private final Map<Long, ChunkMetadata> metadata;

    public StoredChunkRowMapper(Map<Long, ChunkMetadata> metadata) {
        this.metadata = Map.copyOf(metadata);
    }

    /**
     * Maps the current joined chunk row using metadata loaded in the same snapshot.
     *
     * @param row the current chunk row joined with its file identity
     * @return the validated chunk with its database identities
     * @throws SQLException if row values or preloaded metadata are invalid
     */
    @Override
    public StoredChunk map(ResultSet row) throws SQLException {
        try {
            long id = row.getLong("id");
            Chunk chunk = new Chunk(SqlitePath.decode(row.getString("source_path")),
                    DocumentType.valueOf(row.getString("document_type")), row.getInt("chunk_index"),
                    row.getString("content"), new LineRange(row.getInt("start_line"), row.getInt("end_line")),
                    metadata.getOrDefault(id, ChunkMetadata.empty()));

            return new StoredChunk(id, row.getLong("file_id"), chunk);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored chunk row", invalid);
        }
    }
}
