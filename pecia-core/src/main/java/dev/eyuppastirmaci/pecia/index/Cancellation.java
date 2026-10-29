package dev.eyuppastirmaci.pecia.index;

/**
 * Asks an indexing run to stop. Any thread may cancel; the run notices before its next file, embedding batch, or
 * backfill page, commits nothing partial, and returns a {@link IndexResult.Status#CANCELLED} result. Cancelling is
 * permanent, so each run needs its own handle.
 */
public final class Cancellation {

    private volatile boolean cancelled;

    /** Asks the run to stop; later calls have no further effect. */
    public void cancel() {
        cancelled = true;
    }

    /** Returns whether {@link #cancel()} was called. */
    public boolean isCancelled() {
        return cancelled;
    }

    /** Throws if the run was cancelled, so work in progress stops before it writes. */
    void check() throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    /** Signals inside a run that it was cancelled; the run catches it and reports what finished. */
    static final class CancelledException extends Exception {

        CancelledException() {
            super("Indexing was cancelled", null, false, false);
        }
    }
}
