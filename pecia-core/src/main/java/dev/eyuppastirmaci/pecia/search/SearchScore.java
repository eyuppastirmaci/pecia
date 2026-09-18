package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;

public record SearchScore(double value, Kind kind) {
    /**
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
        SQLITE_BM25(true);

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
