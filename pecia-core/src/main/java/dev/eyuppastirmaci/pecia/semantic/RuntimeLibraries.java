package dev.eyuppastirmaci.pecia.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps ONNX Runtime's native libraries for this platform in the user's cache. Without them, every process extracts
 * the runtime's roughly 40 MB of libraries from its JAR into a new temporary directory, which takes most of a semantic
 * query's start-up. The copies are made once, through a temporary file and an atomic move, and verified against their
 * pinned SHA-256 before every use; a damaged copy is replaced. If the cache cannot be used, the runtime extracts its
 * libraries as usual.
 */
final class RuntimeLibraries {

    /** The bundled ONNX Runtime release, whose libraries are pinned below. */
    static final String VERSION = "1.28.0";

    /** The system property from which ONNX Runtime loads its libraries instead of extracting them. */
    static final String NATIVE_PATH = "onnxruntime.native.path";

    private static final String RESOURCES = "/ai/onnxruntime/native/";
    // Pinned to the libraries in the onnxruntime 1.28.0 JAR; RuntimeLibrariesTest checks them against the JAR.
    private static final Map<String, List<Library>> LIBRARIES = Map.of(
            "linux-x64",
            List.of(
                    new Library(
                            "libonnxruntime.so", "1461ef7cc3d9e49982591721683cc3e3a55580aeca9a5254e7aac47b75ee4bab"),
                    new Library(
                            "libonnxruntime4j_jni.so",
                            "ea5e379b2de94c4d909f9ccdf7bafd19eabfee5cd075377e139fe2795ade854c")),
            "linux-aarch64",
            List.of(
                    new Library(
                            "libonnxruntime.so", "f1ec1a08eb99bd6e5401340f0a2b101381bf4694415480291dc13bcaa30f9ec7"),
                    new Library(
                            "libonnxruntime4j_jni.so",
                            "3a245e5e8170b4e96d0e3f3707c489b3aa02b426faa7f09c6ad8b0a689134994")),
            "osx-aarch64",
            List.of(
                    new Library(
                            "libonnxruntime.dylib", "22ca56ee53278fd8ee690e006e0259f9b152c0fc86f49e720fb938a846f75575"),
                    new Library(
                            "libonnxruntime4j_jni.dylib",
                            "298c1b52cd2dfda74481f17be25c07a54bf51bdd2faf4cccde599bc4bcc9c667")),
            "win-x64",
            List.of(
                    new Library("onnxruntime.dll", "8a1e380424243aad62d40c12154d60593fd1c93452849ba0c590a7a2b0a16b54"),
                    new Library(
                            "onnxruntime4j_jni.dll",
                            "ae04d3135e65ce4dc97b6950470e370a26d2796fd0966ff5b90e8fe386156878"),
                    // Loaded from the same directory when present; otherwise the runtime would still extract it.
                    new Library(
                            "onnxruntime_providers_shared.dll",
                            "6e781a8b103731e8a42adb79a22329cdad5a11acff3e5c57e0aaebbb500f17a8")));

    private RuntimeLibraries() {}

    /**
     * Points ONNX Runtime at verified copies of its libraries in the user's cache, unless the process already chose a
     * directory, which includes an earlier call. The runtime reads the property once, when it loads.
     */
    static synchronized void configure(String osName, String osArch) {
        if (System.getProperty(NATIVE_PATH) != null) {
            return;
        }

        directory(osName, osArch, CacheDirectory::resolve, RuntimeLibraries.class::getResourceAsStream)
                .ifPresent(directory -> System.setProperty(NATIVE_PATH, directory.toString()));
    }

    /**
     * Returns the directory that holds verified copies of the platform's libraries, copying any that are missing or
     * damaged; empty if no libraries are pinned for the platform or the cache cannot be used.
     */
    static Optional<Path> directory(
            String osName, String osArch, Supplier<Path> cacheRoot, Function<String, InputStream> resources) {
        Optional<String> platform = RuntimePlatform.platform(osName, osArch).filter(LIBRARIES::containsKey);

        if (platform.isEmpty()) {
            return Optional.empty();
        }

        try {
            Path directory = cacheRoot.get().resolve(cachedDirectory(platform.orElseThrow()));

            for (Library library : LIBRARIES.get(platform.orElseThrow())) {
                place(directory, platform.orElseThrow(), library, resources);
            }

            return Optional.of(directory);
        } catch (IOException | RuntimeException unusable) {
            // An unusable cache only costs the start-up time this class saves, so the runtime extracts as before.
            return Optional.empty();
        }
    }

    /** Returns where the platform's libraries are kept, relative to the cache directory. */
    static Path cachedDirectory(String platform) {
        return Path.of("native", "onnxruntime", VERSION, platform);
    }

    /** Returns the pinned libraries of each platform, by file name and SHA-256. */
    static Map<String, List<Library>> libraries() {
        return LIBRARIES;
    }

    private static void place(Path directory, String platform, Library library, Function<String, InputStream> resources)
            throws IOException {
        Path target = directory.resolve(library.file());

        if (Files.isRegularFile(target) && library.sha256().equals(sha256(target))) {
            return;
        }

        Files.createDirectories(directory);
        // A temporary file in the same directory makes the final move atomic, so another process never loads a
        // partial copy.
        Path temporary = Files.createTempFile(directory, "." + library.file() + "-", ".part");

        try {
            String copied;

            try (InputStream input = resources.apply(RESOURCES + platform + "/" + library.file())) {
                if (input == null) {
                    throw new IOException("The runtime library " + library.file() + " is not bundled");
                }

                copied = copy(input, temporary);
            }

            if (!library.sha256().equals(copied)) {
                throw new IOException("The bundled " + library.file() + " does not match its pinned SHA-256");
            }

            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Copies the stream into the file and returns the SHA-256 of what was copied. */
    private static String copy(InputStream input, Path file) throws IOException {
        MessageDigest digest = newDigest();

        try (DigestInputStream digesting = new DigestInputStream(input, digest);
                OutputStream output = Files.newOutputStream(file)) {
            digesting.transferTo(output);
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();

        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java platform provides SHA-256", impossible);
        }
    }

    /** A pinned library file. */
    record Library(String file, String sha256) {}
}
