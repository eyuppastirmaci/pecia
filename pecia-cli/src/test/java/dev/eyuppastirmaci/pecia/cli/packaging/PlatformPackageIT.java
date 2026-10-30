package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import dev.eyuppastirmaci.pecia.cli.JsonDocuments;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The platform package as a user gets it: the archive is extracted into an empty directory and its launcher runs with
 * no Java on {@code PATH}. Only the {@code packageTest} task runs it, after building the package for this platform.
 */
@Tag("package")
class PlatformPackageIT {

    private static final String PLATFORM = System.getProperty("pecia.package.platform");
    private static final boolean WINDOWS = PLATFORM.startsWith("win");
    // Characters outside every Windows ANSI code page, which java.exe would turn into question marks.
    private static final String UNICODE_QUERY = "İstanbul ödeme 漢字 😀";

    @TempDir
    static Path sandbox;

    private static Path home;

    @BeforeAll
    static void extractTheArchive() throws Exception {
        Path archive = Path.of(System.getProperty("pecia.package.archive"));
        Path extracted = Files.createDirectory(sandbox.resolve("extracted"));
        // tar extracts both formats on every supported system, Windows included, and keeps permissions.
        Process tar = new ProcessBuilder("tar", "-xf", archive.toString(), "-C", extracted.toString())
                .inheritIO()
                .start();
        assertTrue(tar.waitFor(120, TimeUnit.SECONDS) && tar.exitValue() == 0, "tar could not extract " + archive);

        try (Stream<Path> roots = Files.list(extracted)) {
            List<Path> only = roots.toList();
            assertEquals(1, only.size(), "One top-level directory: " + only);
            home = only.getFirst();
        }
    }

    @Test
    void versionAndHelpNameTheCommand() throws Exception {
        Run version = run(sandbox, emptyCache("version"), "--version");
        Run help = run(sandbox, emptyCache("help"), "--help");

        assertEquals(0, version.exitCode(), version.err());
        assertEquals(
                "pecia " + System.getProperty("pecia.it.version"), version.out().strip());
        assertEquals(0, help.exitCode(), help.err());
        assertTrue(help.out().startsWith("Usage: pecia "), help.out());
    }

    @Test
    void lexicalSearchWorksOfflineWithAnEmptyCacheAndWritesNothingThere() throws Exception {
        Path project = project("lexical");
        Path cache = emptyCache("lexical");

        assertEquals(0, run(project, cache, "init").exitCode());
        Run index = run(project, cache, "index", "--progress=none");
        Run query = run(project, cache, "query", "refunds", "--json");
        Run semantic = run(project, cache, "query", "refunds", "--mode", "semantic");

        assertEquals(0, index.exitCode(), index.err());
        assertEquals(0, query.exitCode(), query.err());
        assertEquals(
                "refunds.md",
                JsonDocuments.parse(query.out())
                        .getAsJsonArray("hits")
                        .get(0)
                        .getAsJsonObject()
                        .get("path")
                        .getAsString());
        // The launcher names itself in hints instead of java -jar.
        assertEquals(3, semantic.exitCode());
        assertTrue(semantic.err().contains("Run: pecia index --semantic "), semantic.err());
        try (Stream<Path> written = Files.list(cache)) {
            assertEquals(List.of(), written.toList(), "A lexical run never touches the model cache");
        }
    }

    @Test
    void semanticAndHybridSearchRunOnTheBundledRuntime() throws Exception {
        Path project = project("semantic");
        Path cache = cacheWithTheModel("semantic");

        assertEquals(0, run(project, cache, "init").exitCode());
        Run index = run(project, cache, "index", "--semantic", "--json");
        Run hybrid = run(project, cache, "query", "how long until I get my money back", "--mode", "hybrid", "--json");

        assertEquals(0, index.exitCode(), index.err());
        JsonObject embeddings = JsonDocuments.parse(index.out()).getAsJsonObject("embeddings");
        assertEquals("CACHED", embeddings.get("modelSource").getAsString());
        assertEquals(0, hybrid.exitCode(), hybrid.err());
        JsonObject first =
                JsonDocuments.parse(hybrid.out()).getAsJsonArray("hits").get(0).getAsJsonObject();
        assertEquals("refunds.md", first.get("path").getAsString());
        assertEquals(1, first.getAsJsonObject("fusedRanks").get("semantic").getAsInt());
    }

    @Test
    @Tag("download")
    void aSemanticRunDownloadsTheModelOverHttpsWithTheBundledRuntime() throws Exception {
        Path project = project("download");
        Path cache = emptyCache("download");
        assertEquals(0, run(project, cache, "init").exitCode());

        // Proves that the runtime has every module TLS needs; only release checks download, about 90 MB.
        Run index = run(project, cache, "index", "--semantic", "--json");

        assertEquals(0, index.exitCode(), index.err());
        assertEquals(
                "DOWNLOADED",
                JsonDocuments.parse(index.out())
                        .getAsJsonObject("embeddings")
                        .get("modelSource")
                        .getAsString());
    }

    @Test
    void argumentsOutsideTheAnsiCodePageArriveIntact() throws Exception {
        Path project = project("unicode");
        Path cache = emptyCache("unicode");
        assertEquals(0, run(project, cache, "init").exitCode());
        assertEquals(0, run(project, cache, "index", "--progress=none").exitCode());

        Run query = run(project, cache, "query", UNICODE_QUERY, "--mode", "lexical", "--json");

        assertEquals(0, query.exitCode(), query.err());
        assertEquals(
                UNICODE_QUERY, JsonDocuments.parse(query.out()).get("query").getAsString());
    }

    @Test
    void thePackageCarriesItsLicencesAndOnlyThisPlatformsNativeLibraries() throws Exception {
        assertTrue(Files.isRegularFile(home.resolve("LICENSE")));
        try (Stream<Path> licences = Files.list(home.resolve("licenses"))) {
            assertEquals(
                    List.of(
                            "all-MiniLM-L6-v2-LICENSE.txt",
                            "all-MiniLM-L6-v2-NOTICE.txt",
                            "commonmark-LICENSE.txt",
                            "onnxruntime-LICENSE.txt",
                            "sqlite-jdbc-LICENSE.txt",
                            "sqlite-vec-LICENSE-APACHE.txt",
                            "sqlite-vec-LICENSE-MIT.txt"),
                    licences.map(path -> path.getFileName().toString()).sorted().toList());
        }
        assertTrue(Files.isRegularFile(home.resolve("runtime/legal/java.base/LICENSE")), "The runtime's notices");

        List<String> natives = new ArrayList<>();
        try (JarFile jar = new JarFile(
                home.resolve(WINDOWS ? "app/pecia.jar" : "lib/pecia.jar").toFile())) {
            jar.stream()
                    .map(entry -> entry.getName())
                    .filter(name -> name.matches(".*\\.(so|dylib|dll)"))
                    .forEach(natives::add);
        }

        assertFalse(natives.isEmpty());
        for (String library : natives) {
            boolean ownPlatform = library.contains("/" + PLATFORM + "/")
                    || library.startsWith("org/sqlite/native/" + sqliteDirectory());
            assertTrue(
                    ownPlatform || library.startsWith("org/sqlite/native/Linux-Musl/") && PLATFORM.startsWith("linux"),
                    library + " belongs to another platform");
        }
    }

    /** Runs the packaged launcher in {@code directory} with no Java on PATH and {@code cache} as the model cache. */
    private static Run run(Path directory, Path cache, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(home.resolve(WINDOWS ? "pecia.exe" : "bin/pecia").toString());
        command.addAll(List.of(arguments));
        Path out = Files.createTempFile(sandbox, "out", ".txt");
        Path err = Files.createTempFile(sandbox, "err", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile());
        Map<String, String> environment = builder.environment();
        withoutJava(environment);
        environment.put("PECIA_CACHE_DIR", cache.toString());
        Process process = builder.start();
        process.getOutputStream().close();

        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(
                    "pecia " + String.join(" ", arguments) + " did not finish: " + Files.readString(err));
        }

        return new Run(
                process.exitValue(),
                Files.readString(out, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                Files.readString(err, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    /** Removes every way the launcher could find another Java, so only the bundled runtime can run. */
    private static void withoutJava(Map<String, String> environment) {
        for (String variable : List.of(
                "JAVA_HOME", "JDK_HOME", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) {
            environment.remove(variable);
        }

        Map<String, String> copy = new HashMap<>(environment);
        String pathKey = copy.keySet().stream()
                .filter(key -> key.equalsIgnoreCase("PATH"))
                .findFirst()
                .orElse("PATH");
        String systemRoot = copy.getOrDefault("SystemRoot", "C:\\Windows");
        environment.put(pathKey, WINDOWS ? systemRoot + "\\System32;" + systemRoot : "/usr/bin:/bin");
    }

    private static Path project(String name) throws IOException {
        Path project = Files.createDirectories(sandbox.resolve("projects").resolve(name));
        Files.writeString(
                project.resolve("refunds.md"),
                "# Refunds\n\nCustomers get their money back on the original card within five business days.\n");
        Files.writeString(project.resolve("istanbul.md"), "# " + UNICODE_QUERY + "\n\nOffice notes.\n");
        return project;
    }

    private static Path emptyCache(String name) throws IOException {
        return Files.createDirectories(sandbox.resolve("caches").resolve(name));
    }

    /** Copies the model from the cache that PECIA_CACHE_DIR names, as referenceTest does, so that cache is never written. */
    private static Path cacheWithTheModel(String name) throws IOException {
        String source = System.getenv("PECIA_CACHE_DIR");
        assertNotNull(source, "The semantic package check needs PECIA_CACHE_DIR set to a cache that holds the model");
        Path models = Path.of(source, "models");
        assertTrue(Files.isDirectory(models), "No model in " + models);
        Path cache = emptyCache(name);

        try (Stream<Path> paths = Files.walk(models)) {
            for (Path path : paths.toList()) {
                Path copy =
                        cache.resolve("models").resolve(models.relativize(path).toString());

                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else if (!path.getFileName().toString().equals(".lock")) {
                    Files.copy(path, copy);
                }
            }
        }

        return cache;
    }

    private static String sqliteDirectory() {
        String cpu = PLATFORM.endsWith("x64") ? "x86_64" : "aarch64";
        String system =
                switch (PLATFORM.substring(0, PLATFORM.indexOf('-')).toLowerCase(Locale.ROOT)) {
                    case "osx" -> "Mac";
                    case "win" -> "Windows";
                    default -> "Linux";
                };
        return system + "/" + cpu + "/";
    }

    private record Run(int exitCode, String out, String err) {}
}
