package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import java.util.Objects;

/**
 * Choices a caller makes for one query. An explicit choice overrides {@code .pecia.toml}, which in turn overrides
 * the default.
 *
 * @param mode the search mode for this query; null leaves it to {@code search.mode}
 */
public record QueryOptions(SearchMode mode) {

    /** Returns options that leave every setting to the project configuration. */
    public static QueryOptions configured() {
        return new QueryOptions(null);
    }

    /**
     * Returns options that search in the given mode, whatever the configuration says.
     *
     * @param mode the search mode for this query
     * @throws NullPointerException if mode is null; {@link #configured()} leaves the mode to the configuration
     */
    public static QueryOptions mode(SearchMode mode) {
        return new QueryOptions(Objects.requireNonNull(mode, "mode"));
    }

    /** Returns the query's mode: the explicit choice if there is one, otherwise the configuration's. */
    SearchMode resolveMode(PeciaConfig config) {
        return mode != null ? mode : config.searchMode();
    }
}
