package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteLexicalRetriever.Candidate;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Ranks chunks by the cosine distance sqlite-vec computes between their embeddings and a query embedding. */
final class SqliteVectorRetriever {

    private final SqliteStorage storage;
    private final Connection connection;

    SqliteVectorRetriever(SqliteStorage storage) {
        this.storage = storage;
        this.connection = storage.connection();
    }

    /**
     * Reads the nearest chunks with embeddings of the profile, their metadata, and their deterministic IDs in one
     * snapshot. Only that profile's embeddings are compared, so embeddings of another model are never mixed in.
     */
    List<SearchHit> search(String profileFingerprint, float[] queryEmbedding, int limit) throws SQLException {
        return storage.inScope(() -> SqliteLexicalRetriever.hits(
                connection,
                new SqliteChunkMetadataReader(connection)::readForChunks,
                readCandidates(profileFingerprint, queryEmbedding, limit),
                SearchScore.Kind.COSINE_SIMILARITY));
    }

    private List<Candidate> readCandidates(String profileFingerprint, float[] queryEmbedding, int limit)
            throws SQLException {
        List<Candidate> candidates = new ArrayList<>();

        try (var statement = connection.prepareStatement("""
            SELECT c.id AS chunk_id, c.file_id, c.chunk_index, f.source_path,
                   vec_distance_cosine(e.vector, ?) AS distance,
                   f.document_type, c.content, c.start_line, c.end_line
            FROM chunk_embeddings e
            JOIN embedding_profiles p ON p.id = e.profile_id
            JOIN chunks c ON c.id = e.chunk_id
            JOIN files f ON f.id = c.file_id
            WHERE p.fingerprint = ?
            ORDER BY distance ASC, f.source_path COLLATE BINARY ASC, c.chunk_index ASC, c.id ASC
            LIMIT ?
            """)) {
            statement.setBytes(1, SqliteEmbeddingRepository.encode(queryEmbedding));
            statement.setString(2, profileFingerprint);
            statement.setInt(3, limit);

            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String content = rows.getString("content");
                    // Rounding can put the distance of identical vectors a hair outside [0, 2].
                    double similarity = Math.max(-1, Math.min(1, 1 - rows.getDouble("distance")));
                    candidates.add(new Candidate(
                            rows.getLong("chunk_id"),
                            rows.getLong("file_id"),
                            rows.getInt("chunk_index"),
                            rows.getString("source_path"),
                            similarity,
                            rows.getString("document_type"),
                            content,
                            rows.getInt("start_line"),
                            rows.getInt("end_line"),
                            // The shared hit builder bounds the snippet; semantic matches have no ranges.
                            new HighlightedSnippet(content, List.of())));
                }
            }
        }

        return List.copyOf(candidates);
    }
}
