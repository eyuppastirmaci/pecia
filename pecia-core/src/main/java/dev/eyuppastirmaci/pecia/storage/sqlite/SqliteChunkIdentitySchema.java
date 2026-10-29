package dev.eyuppastirmaci.pecia.storage.sqlite;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** Validates full chunking profiles, identity ownership, and invalidation against the V4 resource. */
final class SqliteChunkIdentitySchema {

    private static final String RESOURCE = "/db/migration/V4__add_chunk_identities.sql";
    private static final Set<String> REQUIRED_OBJECTS = Set.of(
            "file_chunking_profiles",
            "chunks_identity_owner",
            "chunk_identities",
            "chunk_identities_file",
            "files_chunking_profile_update",
            "chunks_chunking_profile_insert",
            "chunks_chunking_profile_update",
            "chunks_chunking_profile_delete",
            "chunk_headings_chunking_profile_insert",
            "chunk_headings_chunking_profile_update",
            "chunk_headings_chunking_profile_delete",
            "chunk_attributes_chunking_profile_insert",
            "chunk_attributes_chunking_profile_update",
            "chunk_attributes_chunking_profile_delete",
            "file_chunking_profiles_update");

    private final SqliteDeclaredSchema declarations;

    private SqliteChunkIdentitySchema(SqliteDeclaredSchema declarations) {
        this.declarations = declarations;
    }

    static SqliteChunkIdentitySchema load() throws IOException {
        return new SqliteChunkIdentitySchema(SqliteDeclaredSchema.load(RESOURCE, REQUIRED_OBJECTS, "chunk-identity"));
    }

    String script() {
        return declarations.script();
    }

    void validate(Connection connection) throws SQLException {
        declarations.validate(connection);

        try (var statement = connection.createStatement()) {
            statement.execute("""
                SELECT file_id, extraction_version, chunking_version, tokenizer_algorithm,
                       vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count,
                       max_tokens, overlap_tokens, fingerprint FROM main.file_chunking_profiles LIMIT 0
                """);
            statement.execute("SELECT chunk_id, file_id, stable_id FROM main.chunk_identities LIMIT 0");
        }
    }
}
