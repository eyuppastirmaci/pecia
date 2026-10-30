package dev.eyuppastirmaci.pecia.index;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * How long an indexing run and each of its phases took.
 *
 * @param total from the start of the run to its result
 * @param phases each phase the run ran, in run order; a phase the run never reached is absent
 */
public record IndexTimings(Duration total, Map<IndexProgress.Phase, Duration> phases) {

    /** Timings of a result built without measuring, such as one made by hand. */
    public static final IndexTimings NONE = new IndexTimings(Duration.ZERO, Map.of());

    /**
     * Copies the phases in run order and rejects negative durations.
     *
     * @throws NullPointerException if total, phases, or any key or value is null
     * @throws IllegalArgumentException if a duration is negative
     */
    public IndexTimings {
        Objects.requireNonNull(total, "total");
        Map<IndexProgress.Phase, Duration> ordered = new EnumMap<>(IndexProgress.Phase.class);

        for (Map.Entry<IndexProgress.Phase, Duration> phase : phases.entrySet()) {
            ordered.put(Objects.requireNonNull(phase.getKey(), "phase"), Objects.requireNonNull(phase.getValue()));
        }

        if (total.isNegative() || ordered.values().stream().anyMatch(Duration::isNegative)) {
            throw new IllegalArgumentException("Durations must not be negative");
        }

        phases = Collections.unmodifiableMap(ordered);
    }
}
