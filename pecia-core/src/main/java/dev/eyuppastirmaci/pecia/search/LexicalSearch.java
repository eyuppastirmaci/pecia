package dev.eyuppastirmaci.pecia.search;

import java.util.List;

/**
 * Plain-text lexical search over an existing index, obtained from {@link
 * dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage#lexicalSearch()}. Does not own or close
 * storage. Callers must serialize access to the storage connection; this API adds no concurrent-use
 * guarantee.
 */
public abstract class LexicalSearch {
    private final LexicalQueryCompiler compiler = new LexicalQueryCompiler();

    /** Creates a search service that compiles literal queries before retrieval. */
    protected LexicalSearch() {}

    /**
     * Searches literal query parts with AND semantics and returns immutable BM25-ranked hits. Smaller
     * scores rank first; ties use binary source path, chunk position, then chunk ID. Snippets are
     * plain content excerpts, while locations describe the complete source chunk.
     *
     * @param request validated plain query text and result limit; raw FTS operators are not
     *     interpreted
     * @return up to the requested limit, empty for no match or no searchable query parts
     * @throws NullPointerException if request is null
     * @throws IllegalArgumentException if the query exceeds 64 whitespace-delimited parts
     * @throws SearchException if storage is closed or reading/mapping fails, preserving the cause;
     *     punctuation-only requests return empty without accessing storage
     */
    public final List<SearchHit> search(SearchRequest request) throws SearchException {
        var expression = compiler.compile(request);
        return expression.isEmpty() ? List.of() : retrieve(expression.get(), request.limit());
    }

    /** Reads ranked hits for an already compiled, nonempty literal expression. */
    protected abstract List<SearchHit> retrieve(String matchExpression, int limit) throws SearchException;
}
