package dev.eyuppastirmaci.pecia.semantic;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Finds the per-user directory where Pecia caches downloaded files. {@code PECIA_CACHE_DIR} overrides the platform
 * convention: {@code %LOCALAPPDATA%\pecia\cache} on Windows, {@code ~/Library/Caches/pecia} on macOS, and {@code
 * $XDG_CACHE_HOME/pecia} or {@code ~/.cache/pecia} elsewhere.
 */
final class CacheDirectory {

    static final String OVERRIDE = "PECIA_CACHE_DIR";

    private CacheDirectory() {}

    /** Resolves the cache directory from this process's environment and system properties. */
    static Path resolve() {
        return resolve(System.getenv(), System::getProperty);
    }

    /**
     * Resolves the cache directory from the given environment and system properties.
     *
     * @throws IllegalArgumentException if {@code PECIA_CACHE_DIR} is set but is not an absolute path, or the platform
     *     default is needed and {@code user.home} is not an absolute path
     */
    static Path resolve(Map<String, String> environment, Function<String, String> properties) {
        String override = environment.get(OVERRIDE);

        if (override != null && !override.isEmpty()) {
            return absolute(override)
                    .orElseThrow(() ->
                            new IllegalArgumentException(OVERRIDE + " must be an absolute path, got " + override));
        }

        String os = properties.apply("os.name");

        if (os != null && os.startsWith("Windows")) {
            Path localData = absolute(environment.get("LOCALAPPDATA"))
                    .orElseGet(() -> home(properties).resolve("AppData/Local"));

            return localData.resolve("pecia").resolve("cache");
        }

        if (os != null && os.startsWith("Mac")) {
            return home(properties).resolve("Library/Caches/pecia");
        }

        // The XDG Base Directory specification says to ignore relative values.
        return absolute(environment.get("XDG_CACHE_HOME"))
                .orElseGet(() -> home(properties).resolve(".cache"))
                .resolve("pecia");
    }

    /** Returns the value as a normalized absolute path, or empty if it is unset, relative, or not a valid path. */
    private static Optional<Path> absolute(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }

        try {
            Path path = Path.of(value);

            return path.isAbsolute() ? Optional.of(path.normalize()) : Optional.empty();
        } catch (InvalidPathException invalid) {
            return Optional.empty();
        }
    }

    private static Path home(Function<String, String> properties) {
        return absolute(properties.apply("user.home"))
                .orElseThrow(
                        () -> new IllegalArgumentException("user.home must be an absolute path to find the cache"));
    }
}
