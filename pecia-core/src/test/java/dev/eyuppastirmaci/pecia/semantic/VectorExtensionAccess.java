package dev.eyuppastirmaci.pecia.semantic;

import java.nio.file.Path;

/** Places the bundled vector extension in a test's own cache directory instead of the user's. */
public final class VectorExtensionAccess {

    private VectorExtensionAccess() {}

    public static Path library(Path cacheRoot) throws VectorExtensionException {
        return VectorExtension.library(
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                cacheRoot,
                VectorExtension.class::getResourceAsStream);
    }

    /** Returns where the current platform's library is kept, relative to Pecia's cache directory. */
    public static Path cachedLibrary() {
        return VectorExtension.cachedLibrary(System.getProperty("os.name"), System.getProperty("os.arch"))
                .orElseThrow(() -> new IllegalStateException("No sqlite-vec library is bundled for this platform"));
    }
}
