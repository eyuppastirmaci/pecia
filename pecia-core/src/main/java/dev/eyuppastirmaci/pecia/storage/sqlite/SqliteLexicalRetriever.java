package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads and enriches ranked lexical hits on the storage-owned connection. */
final class SqliteLexicalRetriever {
    private final SqliteStorage storage;
    private final Connection connection;
    private final MetadataReader metadataReader;

    SqliteLexicalRetriever(SqliteStorage storage) {
        this(storage, new SqliteChunkMetadataReader(storage.connection())::readForChunks);
    }

    // Keeps snapshot coordination testable while the default reader still uses the real storage
    // connection.
    SqliteLexicalRetriever(SqliteStorage storage, MetadataReader metadataReader) {
        connection = storage.connection();
        this.storage = storage;
        this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    }

    /**
     * Executes an already compiled literal MATCH expression with equal field weights. Raw SQLite BM25
     * scores sort ascending; binary paths and chunk positions break ties before LIMIT.
     *
     * @param matchExpression the non-blank compiler output, never unprocessed user input
     * @param limit the maximum number of candidates, between 1 and 100 inclusive
     * @return immutable candidates in retrieval order, empty when nothing matches
     * @throws NullPointerException if matchExpression is null
     * @throws IllegalArgumentException if the expression is blank or limit is outside its bounds
     * @throws SQLException if preparing, reading or closing the query fails
     */
    List<Candidate> retrieve(String matchExpression, int limit) throws SQLException {
        validateRequest(matchExpression, limit);
        return readCandidates(matchExpression, limit);
    }

    /**
     * Reads candidates, metadata, and validated deterministic IDs from one snapshot without taking
     * ownership of the connection.
     */
    List<SearchHit> search(String matchExpression, int limit) throws SQLException {
        validateRequest(matchExpression, limit);

        return storage.inScope(() ->
                hits(connection, metadataReader, readCandidates(matchExpression, limit), SearchScore.Kind.SQLITE_BM25));
    }

    /**
     * Turns candidates into hits with their metadata and validated deterministic IDs; the caller reads candidates and
     * calls this in one snapshot.
     */
    static List<SearchHit> hits(
            Connection connection, MetadataReader metadataReader, List<Candidate> candidates, SearchScore.Kind kind)
            throws SQLException {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Long, ChunkMetadata> metadata =
                metadataReader.read(candidates.stream().map(Candidate::chunkId).toList());
        List<Long> fileIds = candidates.stream().map(Candidate::fileId).toList();
        var identities = new SqliteChunkIdentityReader(connection).readForFiles(fileIds);
        List<SearchHit> hits = new ArrayList<>(candidates.size());

        for (Candidate candidate : candidates) {
            var fileIdentity = identities.get(candidate.fileId());
            Optional<ChunkId> stableId =
                    Optional.ofNullable(fileIdentity.chunkIds().get(candidate.chunkId()));
            hits.add(toHit(
                    candidate, metadata.getOrDefault(candidate.chunkId(), ChunkMetadata.empty()), stableId, kind));
        }

        return List.copyOf(hits);
    }

    private static void validateRequest(String matchExpression, int limit) {
        if (matchExpression.isBlank()) {
            throw new IllegalArgumentException("matchExpression must not be blank");
        }

        if (limit < 1 || limit > SearchRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + SearchRequest.MAX_LIMIT);
        }
    }

    private List<Candidate> readCandidates(String matchExpression, int limit) throws SQLException {
        List<Candidate> candidates = new ArrayList<>();

        try (var statement = connection.prepareStatement("""
            SELECT c.id AS chunk_id, c.file_id, c.chunk_index, f.source_path,
                   bm25(chunks_fts, 1.0, 1.0, 1.0) AS bm25_score,
                   f.document_type, c.content, c.start_line, c.end_line,
                   snippet(chunks_fts, 0, '', '', '…', 32) AS content_snippet
            FROM chunks_fts
            JOIN chunks c ON c.id = chunks_fts.rowid
            JOIN files f ON f.id = c.file_id
            WHERE chunks_fts MATCH ?
            ORDER BY bm25_score ASC, f.source_path COLLATE BINARY ASC, c.chunk_index ASC, c.id ASC
            LIMIT ?
            """)) {
            statement.setString(1, matchExpression);
            statement.setInt(2, limit);

            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    long fileId = rows.getLong("file_id");

                    if (fileId <= 0) {
                        throw new SQLException("Invalid stored lexical file ID: " + fileId);
                    }

                    candidates.add(new Candidate(
                            rows.getLong("chunk_id"),
                            fileId,
                            rows.getInt("chunk_index"),
                            rows.getString("source_path"),
                            rows.getDouble("bm25_score"),
                            rows.getString("document_type"),
                            rows.getString("content"),
                            rows.getInt("start_line"),
                            rows.getInt("end_line"),
                            rows.getString("content_snippet")));
                }
            }
        }

        return List.copyOf(candidates);
    }

    private static SearchHit toHit(
            Candidate candidate, ChunkMetadata metadata, Optional<ChunkId> stableId, SearchScore.Kind kind)
            throws SQLException {
        try {
            Chunk chunk = new Chunk(
                    SqlitePath.decode(candidate.sourcePath()),
                    DocumentType.valueOf(candidate.documentType()),
                    candidate.chunkIndex(),
                    candidate.content(),
                    new LineRange(candidate.startLine(), candidate.endLine()),
                    metadata);
            StoredChunk stored = new StoredChunk(candidate.chunkId(), candidate.fileId(), chunk, stableId);

            return new SearchHit(
                    stored.id(),
                    chunk.index(),
                    chunk.sourcePath(),
                    chunk.documentType(),
                    chunk.sourceLocation(),
                    chunk.metadata(),
                    new SearchScore(candidate.score(), kind),
                    boundedSnippet(candidate.snippet()),
                    stored.stableId());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored search result", invalid);
        }
    }

    /** Caps a preview at {@link SearchHit#MAX_SNIPPET_CODE_POINTS}, marking a cut with an ellipsis. */
    private static String boundedSnippet(String snippet) {
        if (snippet.codePointCount(0, snippet.length()) <= SearchHit.MAX_SNIPPET_CODE_POINTS) {
            return snippet;
        }

        int end = snippet.offsetByCodePoints(0, SearchHit.MAX_SNIPPET_CODE_POINTS - 1);
        return snippet.substring(0, end) + "…";
    }

    @FunctionalInterface
    interface MetadataReader {
        Map<Long, ChunkMetadata> read(List<Long> chunkIds) throws SQLException;
    }

    /**
     * A ranked chunk read from one search query.
     *
     * @param score the raw BM25 score of a lexical match, or the cosine similarity of a vector match
     */
    record Candidate(
            long chunkId,
            long fileId,
            int chunkIndex,
            String sourcePath,
            double score,
            String documentType,
            String content,
            int startLine,
            int endLine,
            String snippet) {}
}
