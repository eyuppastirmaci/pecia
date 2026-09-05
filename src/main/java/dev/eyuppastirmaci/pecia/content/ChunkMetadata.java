package dev.eyuppastirmaci.pecia.content;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Optional structural context carried with a chunk. */
public record ChunkMetadata(List<String> headingPath, Map<String, String> attributes) {

    private static final ChunkMetadata EMPTY = new ChunkMetadata(List.of(), Map.of());

    public ChunkMetadata {
        Objects.requireNonNull(headingPath, "headingPath");
        Objects.requireNonNull(attributes, "attributes");

        for (String heading : headingPath) {

            if (heading == null || heading.isBlank()) {

                throw new IllegalArgumentException("headingPath must contain only non-blank headings");
            }
        }

        for (Map.Entry<String, String> attribute : attributes.entrySet()) {

            if (attribute.getKey() == null || attribute.getKey().isBlank()) {

                throw new IllegalArgumentException("attribute names must not be blank");
            }

            if (attribute.getValue() == null) {

                throw new IllegalArgumentException("attribute values must not be null: " + attribute.getKey());
            }
        }

        // Defensive, sorted copies make metadata stable while chunks move through concurrent pipeline stages.
        headingPath = List.copyOf(headingPath);
        attributes = Collections.unmodifiableMap(new TreeMap<>(attributes));
    }

    /**
     * Returns the shared metadata instance containing no structural context.
     *
     * @return the empty chunk metadata
     */
    public static ChunkMetadata empty() {

        return EMPTY;
    }
}
