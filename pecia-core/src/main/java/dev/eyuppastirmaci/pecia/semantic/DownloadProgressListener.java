package dev.eyuppastirmaci.pecia.semantic;

/**
 * Hears that a model download starts and how far it has come. It is a separate interface rather than a default method
 * of {@link DownloadListener}, because an interface with default methods is initialized with every lambda that
 * implements it, and lexical runs, which always carry a download listener, must not initialize semantic classes.
 */
public interface DownloadProgressListener extends DownloadListener {

    /**
     * Called on the downloading thread after each part of the model file arrives, before the whole file is verified;
     * it should return quickly.
     *
     * @param received bytes received so far, at most {@code size}
     * @param size the model file's pinned size in bytes
     */
    void received(long received, long size);
}
