package dev.eyuppastirmaci.pecia.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The sqlite-vec extension bundled for the platforms semantic search runs on. SQLite loads extensions only from files,
 * so the platform's library is copied once into the user's cache and verified against its pinned SHA-256 before every
 * use; a damaged copy is replaced.
 */
public final class VectorExtension {

    /** The bundled sqlite-vec release. */
    public static final String VERSION = "0.1.9";

    private static final String RESOURCES = "sqlite-vec/";
    // Pinned to the files extracted from the release archives, as NOTICE.txt beside them records.
    private static final Map<String, Library> LIBRARIES = Map.of(
            "linux-x64", new Library("vec0.so", "5923730861b86c707cca5602b5f91092f9e52a46706dbc6e269fd4bb9c4498e8"),
            "linux-aarch64", new Library("vec0.so", "0b84cbd06418ca3040827deddd650539be05be0f657952426b926c8606217437"),
            "osx-aarch64",
                    new Library("vec0.dylib", "193e480c50b59a55977d166f4aaf0e1bc8832d6963516e5950f39e4d2ce0b793"),
            "win-x64", new Library("vec0.dll", "fcf98662a7ad9dce394b96a88f91032047823831b951c76636787c312a6476e6"));

    private VectorExtension() {}

    /**
     * Returns the verified library file for this platform in the user's cache, copying it there when it is missing or
     * damaged.
     *
     * @throws VectorExtensionException if no library is bundled for this platform or the cache cannot be written
     * @throws IllegalArgumentException if the cache directory setting is invalid
     */
    public static Path library() throws VectorExtensionException {
        return library(
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""),
                CacheDirectory.resolve(),
                VectorExtension.class::getResourceAsStream);
    }

    static Path library(String osName, String osArch, Path cacheRoot, Function<String, InputStream> resources)
            throws VectorExtensionException {
        String platform = RuntimePlatform.platform(osName, osArch).orElse("");
        Library library = LIBRARIES.get(platform);

        if (library == null) {
            throw new VectorExtensionException(
                    "No sqlite-vec library is bundled for " + osName + " on " + osArch + "; semantic search runs on "
                            + RuntimePlatform.SUPPORTED,
                    null);
        }

        Path target = cacheRoot.resolve(cachedLibrary(platform, library));

        try {
            if (Files.isRegularFile(target) && library.sha256().equals(sha256(Files.readAllBytes(target)))) {
                return target;
            }

            byte[] bytes;

            try (InputStream input = resources.apply(RESOURCES + platform + "/" + library.file())) {
                if (input == null) {
                    throw new VectorExtensionException("The sqlite-vec library for " + platform + " is missing", null);
                }

                bytes = input.readAllBytes();
            }

            if (!library.sha256().equals(sha256(bytes))) {
                throw new VectorExtensionException(
                        "The bundled sqlite-vec library for " + platform + " does not match its pinned SHA-256", null);
            }

            // A temporary file in the same directory makes the final move atomic, so another process never loads a
            // partial copy.
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".vec0-", ".part");

            try {
                Files.write(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }

            return target;
        } catch (IOException failure) {
            throw new VectorExtensionException(
                    "Could not place the sqlite-vec library in " + target.getParent(), failure);
        }
    }

    /** Returns where the platform's library is kept, relative to the cache directory, or empty if none is bundled. */
    static Optional<Path> cachedLibrary(String osName, String osArch) {
        return RuntimePlatform.platform(osName, osArch)
                .filter(LIBRARIES::containsKey)
                .map(platform -> cachedLibrary(platform, LIBRARIES.get(platform)));
    }

    private static Path cachedLibrary(String platform, Library library) {
        return Path.of("native", "sqlite-vec", VERSION, platform, library.file());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java platform provides SHA-256", impossible);
        }
    }

    private record Library(String file, String sha256) {}
}
