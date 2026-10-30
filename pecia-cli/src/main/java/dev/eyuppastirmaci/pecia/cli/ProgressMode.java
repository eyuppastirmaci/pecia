package dev.eyuppastirmaci.pecia.cli;

/** How {@code index} shows its progress on stderr, as {@code --progress} chooses it. */
enum ProgressMode {
    /** A line that updates in place on an interactive terminal; nothing otherwise. */
    AUTO,
    /** A line when a phase starts and ends, and every few seconds in between, for logs. */
    PLAIN,
    /** Nothing. */
    NONE;

    /** Converts and lists {@code --progress} values. */
    static final class Names extends LowerCaseNames<ProgressMode> {

        Names() {
            super(ProgressMode.class);
        }
    }
}
