package dev.eyuppastirmaci.pecia.testing;

/**
 * The seed every jqwik property declares, so local and CI runs generate the same samples. jqwik has no global seed
 * setting. To explore other samples, change this value locally and run the properties; a failure report prints the
 * seed and the shrunk sample, which then becomes a regular regression test.
 */
public final class PropertySeeds {

    /** Used as {@code @Property(seed = PropertySeeds.FIXED)}. */
    public static final String FIXED = "20261010";

    private PropertySeeds() {}
}
