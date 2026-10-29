package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;

/** A finite retrieval score whose kind defines the ordering direction for the same query. */
public record SearchScore(double value, Kind kind) {
    /**
     * Validates the score value and its interpretation.
     *
     * @throws NullPointerException if kind is null
     * @throws IllegalArgumentException if value is NaN or infinite
     */
    public SearchScore {
        Objects.requireNonNull(kind, "kind");

        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("score must be finite");
        }
    }

    /** Score interpretations currently supported by retrieval. */
    public enum Kind {
        /** Raw SQLite FTS5 bm25() output; numerically lower scores are better. */
        SQLITE_BM25(true),
        /** Cosine similarity between query and chunk embeddings, from -1 to 1; higher scores are better. */
        COSINE_SIMILARITY(false);

        private final boolean lowerIsBetter;

        Kind(boolean lowerIsBetter) {
            this.lowerIsBetter = lowerIsBetter;
        }

        /**
         * Returns the ordering direction within this kind for the same query.
         *
         * @return true when a numerically lower score is a better match
         */
        public boolean lowerIsBetter() {
            return lowerIsBetter;
        }
    }
}
