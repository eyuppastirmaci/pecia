package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SemanticSearch;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import java.sql.SQLException;
import java.util.List;

final class SqliteSemanticSearch extends SemanticSearch {

    private final SqliteVectorRetriever retriever;
    private final EmbeddingProfile profile;

    SqliteSemanticSearch(SqliteVectorRetriever retriever, EmbeddingProfile profile) {
        this.retriever = retriever;
        this.profile = profile;
    }

    @Override
    protected List<SearchHit> retrieve(float[] queryEmbedding, int limit) throws SearchException {
        if (queryEmbedding.length != profile.dimensions()) {
            throw new IllegalArgumentException(
                    "queryEmbedding must have " + profile.dimensions() + " dimensions, not " + queryEmbedding.length);
        }

        try {
            return retriever.search(profile.fingerprint(), queryEmbedding, limit);
        } catch (SQLException failure) {
            throw new SearchException("Could not read semantic search results", failure);
        }
    }
}
