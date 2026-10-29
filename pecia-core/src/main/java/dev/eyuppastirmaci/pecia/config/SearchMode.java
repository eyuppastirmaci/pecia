package dev.eyuppastirmaci.pecia.config;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How a query ranks results. Configuration files and command-line frontends spell each mode by its lowercase
 * {@linkplain #configValue() configuration value}.
 */
public enum SearchMode {
    /** BM25 ranking over the full-text index. */
    LEXICAL("lexical"),
    /** Nearest-neighbor ranking over embeddings. */
    SEMANTIC("semantic"),
    /** Lexical and semantic rankings combined. */
    HYBRID("hybrid"),
    /** Hybrid ranking when embeddings are ready, otherwise lexical. */
    AUTO("auto");

    private final String configValue;

    SearchMode(String configValue) {
        this.configValue = configValue;
    }

    /** Returns the mode's spelling in configuration, such as {@code hybrid}. */
    public String configValue() {
        return configValue;
    }

    /**
     * Finds the mode spelled exactly as given; the lookup is case-sensitive and does not trim whitespace.
     *
     * @param value a configuration value such as {@code hybrid}
     * @return the matching mode, or empty if no mode has this spelling
     * @throws NullPointerException if value is null
     */
    public static Optional<SearchMode> fromConfigValue(String value) {
        // Without the check a null value would simply match nothing.
        Objects.requireNonNull(value, "value");

        return Arrays.stream(values())
                .filter(mode -> mode.configValue.equals(value))
                .findFirst();
    }

    /** Returns every mode's configuration value in declaration order, for messages and help. */
    public static List<String> configValues() {
        return Arrays.stream(values()).map(SearchMode::configValue).toList();
    }
}
