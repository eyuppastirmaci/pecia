package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.UnembeddedChunk;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Writes embedding profiles and chunk vectors using the owning storage's connection. */
final class SqliteEmbeddingRepository {

    private final Connection connection;

    SqliteEmbeddingRepository(Connection connection) {
        this.connection = connection;
    }

    /** Returns the ID of the stored profile with this fingerprint, inserting the profile when it is new. */
    long profileId(EmbeddingProfile profile) throws SQLException {
        String fingerprint = profile.fingerprint();

        try (var query = connection.prepareStatement("SELECT id FROM embedding_profiles WHERE fingerprint = ?")) {
            query.setString(1, fingerprint);

            try (var result = query.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }

        try (var insert = connection.prepareStatement("""
                INSERT INTO embedding_profiles(model_id, model_revision, model_sha256, dimensions,
                    tokenizer_algorithm, vocabulary_sha256, max_input_tokens, pooling, normalization, input,
                    fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, profile.modelId());
            insert.setString(2, profile.modelRevision());
            insert.setString(3, profile.modelSha256());
            insert.setInt(4, profile.dimensions());
            insert.setString(5, profile.tokenizerAlgorithm());
            insert.setString(6, profile.vocabularySha256());
            insert.setInt(7, profile.maxInputTokens());
            insert.setString(8, profile.pooling());
            insert.setString(9, profile.normalization());
            insert.setString(10, profile.input());
            insert.setString(11, fingerprint);
            insert.executeUpdate();

            try (var keys = insert.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Embedding profile insertion returned no ID");
                }

                return keys.getLong(1);
            }
        }
    }

    /** Stores one vector per chunk, in the same order; the caller has validated their dimensions. */
    void saveVectors(List<StoredChunk> chunks, long profileId, List<float[]> vectors) throws SQLException {
        try (var insert = connection.prepareStatement(
                "INSERT INTO chunk_embeddings(chunk_id, profile_id, vector) VALUES (?, ?, ?)")) {
            for (int index = 0; index < chunks.size(); index++) {
                insert.setLong(1, chunks.get(index).id());
                insert.setLong(2, profileId);
                insert.setBytes(3, encode(vectors.get(index)));

                if (insert.executeUpdate() != 1) {
                    throw new SQLException("Chunk embedding insertion returned no row");
                }
            }
        }
    }

    /**
     * Fails if the index holds embeddings of a profile other than the one with this fingerprint, so vectors of two
     * profiles are never stored together.
     */
    void requireOnlyProfile(String fingerprint) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM chunk_embeddings
                WHERE profile_id IN (SELECT id FROM embedding_profiles WHERE fingerprint != ?) LIMIT 1
                """)) {
            query.setString(1, fingerprint);

            try (var result = query.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException(
                            "The index holds embeddings of another profile; discard them before storing new ones");
                }
            }
        }
    }

    /** Deletes embeddings of every other profile and the profile rows they leave unused, returning the count. */
    long discardOtherProfiles(String fingerprint) throws SQLException {
        long discarded;

        try (var vectors = connection.prepareStatement("""
                DELETE FROM chunk_embeddings
                WHERE profile_id IN (SELECT id FROM embedding_profiles WHERE fingerprint != ?)
                """)) {
            vectors.setString(1, fingerprint);
            discarded = vectors.executeUpdate();
        }

        try (var profiles = connection.prepareStatement("DELETE FROM embedding_profiles WHERE fingerprint != ?")) {
            profiles.setString(1, fingerprint);
            profiles.executeUpdate();
        }

        return discarded;
    }

    /** Counts stored chunks by whether they have an embedding of this profile, of another, or none. */
    EmbeddingCoverage coverage(String fingerprint) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT
                    count(*) FILTER (WHERE p.fingerprint = ?),
                    count(*) FILTER (WHERE p.fingerprint != ?),
                    count(*) FILTER (WHERE e.chunk_id IS NULL)
                FROM chunks c
                LEFT JOIN chunk_embeddings e ON e.chunk_id = c.id
                LEFT JOIN embedding_profiles p ON p.id = e.profile_id
                """)) {
            query.setString(1, fingerprint);
            query.setString(2, fingerprint);

            try (var result = query.executeQuery()) {
                result.next();

                return new EmbeddingCoverage(result.getLong(1), result.getLong(2), result.getLong(3));
            }
        }
    }

    /** Returns the file's chunks that have no embedding, in position order. */
    List<UnembeddedChunk> findUnembedded(long fileId) throws SQLException {
        List<UnembeddedChunk> chunks = new ArrayList<>();

        try (var query = connection.prepareStatement("""
                SELECT c.id, c.content FROM chunks c
                WHERE c.file_id = ? AND NOT EXISTS (SELECT 1 FROM chunk_embeddings e WHERE e.chunk_id = c.id)
                ORDER BY c.chunk_index
                """)) {
            query.setLong(1, fileId);

            try (var result = query.executeQuery()) {
                while (result.next()) {
                    chunks.add(new UnembeddedChunk(result.getLong(1), result.getString(2)));
                }
            }
        }

        return chunks;
    }

    /**
     * Stores one vector per chunk, in the same order, for each chunk that still has the same content and no vector;
     * the caller has validated the vectors' dimensions.
     *
     * @return how many vectors were stored
     */
    int saveIfUnchanged(List<UnembeddedChunk> chunks, long profileId, List<float[]> vectors) throws SQLException {
        int stored = 0;

        try (var insert = connection.prepareStatement("""
                INSERT INTO chunk_embeddings(chunk_id, profile_id, vector)
                SELECT ?, ?, ? WHERE EXISTS (SELECT 1 FROM chunks WHERE id = ? AND content = ?)
                    AND NOT EXISTS (SELECT 1 FROM chunk_embeddings WHERE chunk_id = ?)
                """)) {
            for (int index = 0; index < chunks.size(); index++) {
                UnembeddedChunk chunk = chunks.get(index);
                insert.setLong(1, chunk.id());
                insert.setLong(2, profileId);
                insert.setBytes(3, encode(vectors.get(index)));
                insert.setLong(4, chunk.id());
                insert.setString(5, chunk.content());
                insert.setLong(6, chunk.id());
                stored += insert.executeUpdate();
            }
        }

        return stored;
    }

    /** Encodes a vector as little-endian float32 values, the layout sqlite-vec reads. */
    static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);

        for (float value : vector) {
            buffer.putFloat(value);
        }

        return buffer.array();
    }

    /** Decodes little-endian float32 values. */
    static float[] decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];

        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }

        return vector;
    }
}
