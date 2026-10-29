package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.onnxruntime.OrtEnvironment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Places ONNX Runtime's native libraries in the cache, verified, and falls back when the cache cannot be used. */
class RuntimeLibrariesTest {

    // Linux on x64 has the smallest pinned libraries, so the tests copy them whatever platform runs them.
    private static final String OS_NAME = "Linux";
    private static final String OS_ARCH = "amd64";
    private static final Path DIRECTORY = Path.of("native/onnxruntime/1.28.0/linux-x64");
    private static final List<String> FILES = List.of("libonnxruntime.so", "libonnxruntime4j_jni.so");
    private static final Function<String, InputStream> BUNDLED = RuntimeLibraries.class::getResourceAsStream;

    @TempDir
    Path sandbox;

    @Test
    void thePinnedLibrariesAreExactlyTheRuntimeJarsNativeLibraries() throws Exception {
        Map<String, Set<String>> pinned = new TreeMap<>();
        Map<String, Set<String>> bundled = new TreeMap<>();

        RuntimeLibraries.libraries()
                .forEach((platform, libraries) ->
                        libraries.forEach(library -> pinned.computeIfAbsent(platform, key -> new TreeSet<>())
                                .add(library.file() + " " + library.sha256())));

        try (JarFile jar = new JarFile(Path.of(OrtEnvironment.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toFile())) {
            for (var entry : jar.stream().toList()) {
                String name = entry.getName();

                // Debug symbols sit beside the macOS libraries and are never loaded.
                if (entry.isDirectory() || !name.startsWith("ai/onnxruntime/native/") || name.contains(".dSYM/")) {
                    continue;
                }

                String[] parts =
                        name.substring("ai/onnxruntime/native/".length()).split("/");
                byte[] bytes;

                try (InputStream input = jar.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }

                bundled.computeIfAbsent(parts[0], key -> new TreeSet<>()).add(parts[1] + " " + sha256(bytes));
            }
        }

        assertEquals(bundled, pinned);
    }

    @Test
    void thePinnedVersionIsTheRuntimesVersion() {
        assertEquals(OrtEnvironment.class.getPackage().getImplementationVersion(), RuntimeLibraries.VERSION);
    }

    @Test
    void theLibrariesAreCopiedIntoTheVersionedCacheDirectory() throws Exception {
        Optional<Path> directory = RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, BUNDLED);

        assertEquals(Optional.of(sandbox.resolve(DIRECTORY)), directory);

        for (String file : FILES) {
            assertArrayEquals(
                    bundled(file), Files.readAllBytes(sandbox.resolve(DIRECTORY).resolve(file)), file);
        }

        assertEquals(Set.copyOf(FILES), fileNames(sandbox.resolve(DIRECTORY)), "No temporary file is left behind");
    }

    @Test
    void verifiedCopiesAreReusedAndADamagedOneReplaced() throws Exception {
        RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, BUNDLED);
        Path runtime = sandbox.resolve(DIRECTORY).resolve(FILES.getFirst());
        Path bridge = sandbox.resolve(DIRECTORY).resolve(FILES.getLast());
        FileTime old = FileTime.fromMillis(1_000_000_000_000L);
        Files.setLastModifiedTime(runtime, old);
        Files.writeString(bridge, "damaged");

        Optional<Path> directory = RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, BUNDLED);

        assertEquals(Optional.of(sandbox.resolve(DIRECTORY)), directory);
        assertEquals(old, Files.getLastModifiedTime(runtime), "A verified copy is not written again");
        assertArrayEquals(bundled(FILES.getLast()), Files.readAllBytes(bridge));
    }

    @Test
    void aVerifiedCacheNeedsNoBundledLibrary() throws Exception {
        RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, BUNDLED);

        Optional<Path> directory = RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, resource -> {
            throw new AssertionError("Read " + resource);
        });

        assertEquals(Optional.of(sandbox.resolve(DIRECTORY)), directory);
    }

    @Test
    void aBundledLibraryThatDoesNotMatchItsPinLeavesExtractionToTheRuntime() throws Exception {
        Function<String, InputStream> tampered =
                resource -> new ByteArrayInputStream("not the runtime".getBytes(StandardCharsets.UTF_8));

        assertEquals(Optional.empty(), RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, tampered));
        assertEquals(Set.of(), fileNames(sandbox.resolve(DIRECTORY)), "Neither a copy nor a temporary file remains");
    }

    @Test
    void aMissingBundledLibraryLeavesExtractionToTheRuntime() throws Exception {
        assertEquals(Optional.empty(), RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> sandbox, resource -> null));
        assertEquals(Set.of(), fileNames(sandbox.resolve(DIRECTORY)));
    }

    @Test
    void aCacheThatCannotBeWrittenLeavesExtractionToTheRuntime() throws Exception {
        Path file = Files.writeString(sandbox.resolve("cache"), "a file, not a directory");

        assertEquals(Optional.empty(), RuntimeLibraries.directory(OS_NAME, OS_ARCH, () -> file, BUNDLED));
        assertEquals(
                Optional.empty(),
                RuntimeLibraries.directory(
                        OS_NAME,
                        OS_ARCH,
                        () -> {
                            throw new IllegalArgumentException("PECIA_CACHE_DIR must be an absolute path, got cache");
                        },
                        BUNDLED));
    }

    @Test
    void platformsWithoutPinnedLibrariesNeverTouchTheCache() {
        Optional<Path> directory = RuntimeLibraries.directory(
                "Mac OS X",
                "x86_64",
                () -> {
                    throw new AssertionError("The cache was resolved");
                },
                BUNDLED);

        assertEquals(Optional.empty(), directory);
        assertFalse(Files.exists(sandbox.resolve("native")));
    }

    @Test
    void librariesArePinnedForEveryPlatformTheEmbedderRunsOn() {
        assertEquals(
                Set.of("linux-x64", "linux-aarch64", "osx-aarch64", "win-x64"),
                RuntimeLibraries.libraries().keySet());

        for (String[] platform : new String[][] {
            {"Linux", "amd64"}, {"Linux", "aarch64"}, {"Mac OS X", "aarch64"}, {"Windows 11", "amd64"}
        }) {
            assertTrue(RuntimePlatform.bundled(platform[0], platform[1]), String.join(" ", platform));
        }
    }

    private static byte[] bundled(String file) throws IOException {
        try (InputStream input = BUNDLED.apply("/ai/onnxruntime/native/linux-x64/" + file)) {
            return input.readAllBytes();
        }
    }

    private static Set<String> fileNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Set.of();
        }

        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
