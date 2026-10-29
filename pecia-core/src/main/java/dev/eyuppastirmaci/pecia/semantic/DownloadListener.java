package dev.eyuppastirmaci.pecia.semantic;

import java.nio.file.Path;

/** Hears that a model download is about to start. */
@FunctionalInterface
public interface DownloadListener {

    /** A listener that ignores every download. */
    DownloadListener NONE = (model, file) -> {};

    /**
     * Called once, before the request for the model file is sent.
     *
     * @param model the model being downloaded; its size and download URI describe the transfer
     * @param file the cache file the verified download will be published as
     */
    void downloading(SemanticModel model, Path file);
}
