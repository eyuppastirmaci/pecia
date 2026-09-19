package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.storage.sqlite.mapper.ChunkingIdentityRowMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Validates complete chunk identities using the caller's existing database snapshot. */
final class SqliteChunkIdentityReader {

    private final Connection connection;

    SqliteChunkIdentityReader(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    State read(long fileId) throws SQLException {
        return readForFiles(List.of(fileId)).get(fileId);
    }

    Map<Long, State> readForFiles(List<Long> fileIds) throws SQLException {
        List<Long> validatedIds = List.copyOf(fileIds);
        List<Long> requested = List.copyOf(new LinkedHashSet<>(validatedIds));

        for (long fileId : requested) {
            if (fileId <= 0) {
                throw new IllegalArgumentException("fileId must be positive: " + fileId);
            }
        }

        if (requested.isEmpty()) {
            return Map.of();
        }

        Map<Long, Profile> profiles = readProfiles(requested);
        Map<Long, Map<Long, ChunkId>> expected = expectedIdentities(profiles);
        Map<Long, Map<Long, ChunkId>> identities = readIdentities(requested, expected);
        Map<Long, State> result = new HashMap<>();

        for (long fileId : requested) {
            Map<Long, ChunkId> expectedIds = expected.getOrDefault(fileId, Map.of());
            Map<Long, ChunkId> actualIds = identities.getOrDefault(fileId, Map.of());

            if (actualIds.size() != expectedIds.size()) {
                throw new SQLException("Stored chunking profile has incomplete chunk identities");
            }

            result.put(
                    fileId, new State(Optional.ofNullable(profiles.get(fileId)).map(Profile::identity), actualIds));
        }

        return Map.copyOf(result);
    }

    private Map<Long, Map<Long, ChunkId>> readIdentities(List<Long> fileIds, Map<Long, Map<Long, ChunkId>> expected)
            throws SQLException {
        Map<Long, Map<Long, ChunkId>> identities = new HashMap<>();
        String selection = placeholders(fileIds);

        // Include both declared and actual owners so corrupt mappings cannot hide outside the batch.
        try (var query = connection.prepareStatement("""
            SELECT i.chunk_id, i.file_id, i.stable_id, c.file_id AS chunk_file_id
            FROM chunk_identities i LEFT JOIN chunks c ON c.id = i.chunk_id
            WHERE i.file_id IN (%s) OR i.chunk_id IN (SELECT id FROM chunks WHERE file_id IN (%s))
            """.formatted(selection, selection))) {
            bind(query, fileIds, 0);
            bind(query, fileIds, fileIds.size());

            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long chunkId = readInteger(rows, "chunk_id");
                    long fileId = readInteger(rows, "file_id");
                    Map<Long, ChunkId> expectedIds = expected.get(fileId);

                    if (expectedIds == null
                            || readInteger(rows, "chunk_file_id") != fileId
                            || !expectedIds.containsKey(chunkId)) {
                        throw new SQLException("Stored chunk identity has no matching chunk and full profile");
                    }

                    ChunkId stableId;

                    try {
                        stableId = new ChunkId(readText(rows, "stable_id"));
                    } catch (IllegalArgumentException invalid) {
                        throw new SQLException("Invalid stored chunk identity", invalid);
                    }

                    if (!stableId.equals(expectedIds.get(chunkId))) {
                        throw new SQLException("Stored chunk identity does not match its path, index, and profile");
                    }

                    identities
                            .computeIfAbsent(fileId, ignored -> new HashMap<>())
                            .put(chunkId, stableId);
                }
            }
        }

        return identities;
    }

    private Map<Long, Profile> readProfiles(List<Long> fileIds) throws SQLException {
        Map<Long, Profile> profiles = new HashMap<>();

        try (var query = connection.prepareStatement("""
            SELECT p.file_id, p.extraction_version, p.chunking_version, p.tokenizer_algorithm,
                   p.vocabulary_sha256, p.vocabulary_size, p.max_input_tokens, p.special_token_count,
                   p.max_tokens, p.overlap_tokens, p.fingerprint, f.source_path
            FROM file_chunking_profiles p LEFT JOIN files f ON f.id = p.file_id WHERE p.file_id IN (%s)
            """.formatted(placeholders(fileIds)))) {
            bind(query, fileIds, 0);

            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long fileId = readInteger(rows, "file_id");
                    ChunkingIdentity identity = new ChunkingIdentityRowMapper().map(rows);

                    try {
                        Path sourcePath = SqlitePath.decode(readText(rows, "source_path"));
                        // Empty files also need a path that can be represented losslessly in an ID.
                        new ChunkIdGenerator(identity).generate(sourcePath, 0);

                        profiles.put(fileId, new Profile(identity, sourcePath));
                    } catch (IllegalArgumentException invalid) {
                        throw new SQLException("Invalid stored chunk identity source path", invalid);
                    }
                }
            }
        }

        return profiles;
    }

    private Map<Long, Map<Long, ChunkId>> expectedIdentities(Map<Long, Profile> profiles) throws SQLException {
        if (profiles.isEmpty()) {
            return Map.of();
        }

        Map<Long, Map<Long, ChunkId>> expected = new HashMap<>();
        Map<Long, ChunkIdGenerator> generators = new HashMap<>();
        profiles.forEach((fileId, profile) -> {
            expected.put(fileId, new HashMap<>());
            generators.put(fileId, new ChunkIdGenerator(profile.identity()));
        });

        List<Long> fileIds = List.copyOf(profiles.keySet());
        try (var query = connection.prepareStatement("""
            SELECT id, file_id, chunk_index FROM chunks
            WHERE file_id IN (%s) ORDER BY file_id, chunk_index
            """.formatted(placeholders(fileIds)))) {
            bind(query, fileIds, 0);

            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = readInteger(rows, "id");
                    long fileId = readInteger(rows, "file_id");
                    long index = readInteger(rows, "chunk_index");
                    Map<Long, ChunkId> expectedIds = expected.get(fileId);

                    if (id <= 0 || index != expectedIds.size()) {
                        throw new SQLException("Known chunk identities require positive IDs and contiguous indices");
                    }

                    try {
                        expectedIds.put(
                                id,
                                generators
                                        .get(fileId)
                                        .generate(profiles.get(fileId).sourcePath(), Math.toIntExact(index)));
                    } catch (IllegalArgumentException | ArithmeticException invalid) {
                        throw new SQLException("Invalid stored chunk identity inputs", invalid);
                    }
                }
            }
        }

        return expected;
    }

    private static String placeholders(List<Long> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }

    private static void bind(PreparedStatement statement, List<Long> values, int offset) throws SQLException {
        for (int index = 0; index < values.size(); index++) {
            statement.setLong(offset + index + 1, values.get(index));
        }
    }

    private static String readText(ResultSet row, String column) throws SQLException {
        if (row.getObject(column) instanceof String text) {
            return text;
        }

        throw new SQLException("Stored " + column + " must be text");
    }

    private static long readInteger(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);

        if (value instanceof Integer integer) {
            return integer;
        }

        if (value instanceof Long number) {
            return number;
        }

        throw new SQLException("Stored " + column + " must be an integer");
    }

    record State(Optional<ChunkingIdentity> identity, Map<Long, ChunkId> chunkIds) {
        State {
            Objects.requireNonNull(identity, "identity");
            chunkIds = Map.copyOf(chunkIds);
        }
    }

    private record Profile(ChunkingIdentity identity, Path sourcePath) {}
}
