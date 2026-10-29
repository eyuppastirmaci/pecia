package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.semantic.DownloadListener;
import java.util.Objects;

/**
 * Choices a caller makes for one indexing run. An explicit choice overrides {@code .pecia.toml}, which in turn
 * overrides the default.
 *
 * @param semanticEnabled whether the run builds embeddings for semantic search; null leaves it to {@code
 *     semantic.enabled}
 * @param downloadListener told before the semantic model is downloaded; null when nobody listens
 * @param progressListener told how far long-running work has come; null when nobody listens
 */
public record IndexOptions(
        Boolean semanticEnabled, DownloadListener downloadListener, IndexProgressListener progressListener) {

    /** Creates options without a progress listener. */
    public IndexOptions(Boolean semanticEnabled, DownloadListener downloadListener) {
        this(semanticEnabled, downloadListener, null);
    }

    /** Returns options that leave every setting to the project configuration. */
    public static IndexOptions configured() {
        return new IndexOptions(null, null);
    }

    /**
     * Returns options that turn semantic indexing on or off for this run, whatever the configuration says.
     *
     * @param enabled whether the run builds embeddings for semantic search
     */
    public static IndexOptions semantic(boolean enabled) {
        return new IndexOptions(enabled, null);
    }

    /**
     * Returns these options with a listener that hears about a model download before it starts.
     *
     * @throws NullPointerException if listener is null
     */
    public IndexOptions withDownloadListener(DownloadListener listener) {
        return new IndexOptions(semanticEnabled, Objects.requireNonNull(listener, "listener"), progressListener);
    }

    /**
     * Returns these options with a listener that hears how far long-running work, such as embedding stored chunks,
     * has come.
     *
     * @throws NullPointerException if listener is null
     */
    public IndexOptions withProgressListener(IndexProgressListener listener) {
        return new IndexOptions(semanticEnabled, downloadListener, Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Returns whether the run builds embeddings: the explicit choice if there is one, otherwise the configuration's.
     */
    boolean resolveSemanticEnabled(PeciaConfig config) {
        return semanticEnabled != null ? semanticEnabled : config.semanticEnabled();
    }
}
