package dev.eyuppastirmaci.pecia.retrieval;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One labelled query of the retrieval set. Paths are relative to the corpus and use forward slashes.
 *
 * @param answers files that answer the query (grade 2), never empty
 * @param related files that are related but do not answer it (grade 1), disjoint from {@code answers}
 * @param answerFirstIn modes in which an answer must rank first
 */
public record RetrievalQuery(
        String id, Kind kind, String text, List<String> answers, List<String> related, Set<SearchMode> answerFirstIn) {

    public RetrievalQuery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        answers = List.copyOf(answers);
        related = List.copyOf(related);
        answerFirstIn = Set.copyOf(answerFirstIn);
    }

    /** Returns the grade of a file for this query: 2 for an answer, 1 for a related file, and 0 otherwise. */
    public int grade(String path) {
        if (answers.contains(path)) {
            return 2;
        }

        return related.contains(path) ? 1 : 0;
    }

    /** The kinds of query the set measures separately. */
    public enum Kind {
        /** Exact names and codes. */
        IDENTIFIER,
        /** A few words that appear in the answer. */
        KEYWORD,
        /** A natural question that shares almost no words with the answer. */
        PARAPHRASE,
        /** An identifier together with natural words. */
        MIXED,
        /** A Turkish query, or an English query whose answer is a Turkish document. */
        TURKISH
    }
}
