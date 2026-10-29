package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** Validates embedding profiles, chunk vectors, and their invariants against the V5 resource. */
final class SqliteEmbeddingSchema {

    private static final String RESOURCE = "/db/migration/V5__add_chunk_embeddings.sql";
    private static final Set<String> REQUIRED_OBJECTS = Set.of(
            "embedding_profiles",
            "chunk_embeddings",
            "chunk_embeddings_profile",
            "chunk_embeddings_dimensions_insert",
            "chunk_embeddings_dimensions_update",
            "chunks_embedding_update",
            "embedding_profiles_update");

    private final SqliteDeclaredSchema declarations;

    private SqliteEmbeddingSchema(SqliteDeclaredSchema declarations) {
        this.declarations = declarations;
    }

    static SqliteEmbeddingSchema load() throws IOException {
        return new SqliteEmbeddingSchema(SqliteDeclaredSchema.load(RESOURCE, REQUIRED_OBJECTS, "embedding"));
    }

    String script() {
        return declarations.script();
    }

    void validate(Connection connection) throws SQLException {
        declarations.validate(connection);

        try (var statement = connection.createStatement()) {
            statement.execute("""
                SELECT id, model_id, model_revision, model_sha256, dimensions, tokenizer_algorithm,
                       vocabulary_sha256, max_input_tokens, pooling, normalization, input, fingerprint
                FROM main.embedding_profiles LIMIT 0
                """);
            statement.execute("SELECT chunk_id, profile_id, vector FROM main.chunk_embeddings LIMIT 0");
        }
    }
}
