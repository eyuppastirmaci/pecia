package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.search.LexicalSearch;
import dev.eyuppastirmaci.pecia.search.SearchException;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

final class SqliteLexicalSearch extends LexicalSearch {
    private final SqliteLexicalRetriever retriever;

    SqliteLexicalSearch(SqliteLexicalRetriever retriever) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
    }

    @Override
    protected List<SearchHit> retrieve(String matchExpression, int limit) throws SearchException {
        try {
            return retriever.search(matchExpression, limit);
        } catch (SQLException failure) {
            throw new SearchException("Could not read lexical search results", failure);
        }
    }
}
