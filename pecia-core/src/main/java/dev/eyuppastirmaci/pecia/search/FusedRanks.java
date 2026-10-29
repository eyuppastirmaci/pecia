package dev.eyuppastirmaci.pecia.search;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * The 1-based ranks a hybrid hit had among the lexical and the semantic candidates. A side's rank is empty when the
 * chunk was not among that side's candidates; at least one side ranked it.
 */
public record FusedRanks(OptionalInt lexical, OptionalInt semantic) {

    /**
     * Validates the ranks.
     *
     * @throws NullPointerException if either rank is null
     * @throws IllegalArgumentException if both ranks are empty or a rank is below 1
     */
    public FusedRanks {
        Objects.requireNonNull(lexical, "lexical");
        Objects.requireNonNull(semantic, "semantic");

        if (lexical.isEmpty() && semantic.isEmpty()) {
            throw new IllegalArgumentException("At least one side must rank the chunk");
        }

        if (lexical.orElse(1) < 1 || semantic.orElse(1) < 1) {
            throw new IllegalArgumentException("Ranks start at 1");
        }
    }
}
