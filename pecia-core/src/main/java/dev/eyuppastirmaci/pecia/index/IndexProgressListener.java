package dev.eyuppastirmaci.pecia.index;

/** Hears how far long-running indexing work has come; called on the indexing thread, so it should return quickly. */
@FunctionalInterface
public interface IndexProgressListener {

    void progress(IndexProgress progress);
}
