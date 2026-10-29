package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.search.HybridSearch;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

final class SqliteHybridSearch extends HybridSearch {

    private final SqliteStorage storage;
    private final SqliteLexicalRetriever lexical;
    private final SqliteVectorRetriever semantic;
    private final EmbeddingProfile profile;

    SqliteHybridSearch(
            SqliteStorage storage,
            SqliteLexicalRetriever lexical,
            SqliteVectorRetriever semantic,
            EmbeddingProfile profile) {
        this.storage = storage;
        this.lexical = lexical;
        this.semantic = semantic;
        this.profile = profile;
    }

    @Override
    protected Candidates retrieve(Optional<String> matchExpression, float[] queryEmbedding, int depth)
            throws SearchException {
        if (queryEmbedding.length != profile.dimensions()) {
            throw new IllegalArgumentException(
                    "queryEmbedding must have " + profile.dimensions() + " dimensions, not " + queryEmbedding.length);
        }

        try {
            // The outer scope holds one read transaction, so both sides see the same index state.
            return storage.inScope(() -> new Candidates(
                    matchExpression.isPresent() ? lexical.search(matchExpression.get(), depth) : List.of(),
                    semantic.search(profile.fingerprint(), queryEmbedding, depth)));
        } catch (SQLException failure) {
            throw new SearchException("Could not read hybrid search candidates", failure);
        }
    }
}
