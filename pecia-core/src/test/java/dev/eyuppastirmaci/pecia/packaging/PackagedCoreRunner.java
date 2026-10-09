package dev.eyuppastirmaci.pecia.packaging;

import dev.eyuppastirmaci.pecia.packaging.consumer.PackagedCoreConsumer;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/** Runs the standalone consumer with an explicit artifact classpath in a separate JVM. */
final class PackagedCoreRunner {

    static final String CONSUMER = PackagedCoreConsumer.class.getName();
    private static final Path PACKAGE =
            Path.of(PackagedCoreConsumer.class.getPackageName().replace('.', '/'));
    // Standalone test support shared with core tests; each depends only on the JDK, SQLite, and public core APIs.
    static final List<Class<?>> SUPPORT_TYPES = List.of(IndexDump.class, LexicalTestCorpus.class);
    private final Path coreJar;
    private final Path runtimeDirectory;
    private final Path sandbox;
    private final List<Path> classpath;

    private PackagedCoreRunner(Path coreJar, Path runtimeDirectory, Path sandbox, List<Path> classpath) {
        this.coreJar = coreJar;
        this.runtimeDirectory = runtimeDirectory;
        this.sandbox = sandbox;
        this.classpath = List.copyOf(classpath);
    }

    static PackagedCoreRunner prepare(Path coreJar, Path runtimeDirectory, Path testClasses, Path sandbox)
            throws IOException {
        Objects.requireNonNull(coreJar, "coreJar");
        Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
        Objects.requireNonNull(testClasses, "testClasses");
        Objects.requireNonNull(sandbox, "sandbox");
        Path jar = requireJar(coreJar);
        Path runtime = requireDirectory(runtimeDirectory);
        List<Path> consumerClasses = consumerClasses(testClasses);
        Path output = requireDirectory(sandbox);
        List<Path> artifacts = new ArrayList<>(List.of(jar));
        try (var paths = Files.list(runtime)) {
            for (Path dependency : paths.filter(
                            path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted()
                    .toList()) {
                artifacts.add(requireJar(dependency));
            }
        }
        if (artifacts.size() == 1) {
            throw new IOException("No packaged runtime JARs in " + runtime);
        }

        artifacts.add(copyConsumerClasses(testClasses, consumerClasses, output.resolve("consumer-classes")));
        return new PackagedCoreRunner(jar, runtime, output, artifacts);
    }

    /**
     * Lists the standalone consumer classes: every class in the consumer package, plus the allowlisted support types
     * with their nested types. Test classes are excluded so build-only code cannot reach the isolated classpath.
     */
    static List<Path> consumerClasses(Path testClasses) throws IOException {
        Path consumerPackage = requireDirectory(testClasses.resolve(PACKAGE));
        List<Path> classes = new ArrayList<>(classFiles(consumerPackage, name -> true));
        if (!classes.contains(consumerPackage.resolve("PackagedCoreConsumer.class"))) {
            throw new IOException("Missing standalone consumer in " + consumerPackage);
        }
        for (Class<?> support : SUPPORT_TYPES) {
            Path supportPackage = requireDirectory(testClasses.resolve(packagePath(support)));
            String simpleName = support.getSimpleName();
            List<Path> types = classFiles(
                    supportPackage, name -> name.equals(simpleName + ".class") || name.startsWith(simpleName + "$"));
            if (!types.contains(supportPackage.resolve(simpleName + ".class"))) {
                throw new IOException("Missing consumer support type " + support.getName() + " in " + supportPackage);
            }
            classes.addAll(types);
        }
        for (Path type : classes) {
            if (!Files.isRegularFile(type)) {
                throw new IOException("Consumer class is not a regular file: " + type);
            }
        }
        return List.copyOf(classes);
    }

    /** Copies consumer classes, keeping their package paths, into a new directory and returns that classpath root. */
    static Path copyConsumerClasses(Path testClasses, List<Path> classes, Path directory) throws IOException {
        // A fresh directory cannot retain helpers or resources from an earlier run.
        Path isolated = Files.createDirectory(directory);
        for (Path type : classes) {
            Path destination = isolated.resolve(testClasses.relativize(type));
            Files.createDirectories(destination.getParent());
            Files.copy(type, destination);
        }
        return isolated;
    }

    private static List<Path> classFiles(Path directory, Predicate<String> names) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".class")
                                && names.test(name)
                                && !name.matches(".*(?:Test|IT)(?:\\$[^/]+)?\\.class");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static Path packagePath(Class<?> type) {
        return Path.of(type.getPackageName().replace('.', '/'));
    }

    List<String> command(String scenario, Path project) throws IOException {
        if (scenario.isBlank()) {
            throw new IllegalArgumentException("scenario must not be blank");
        }
        Path root = requireDirectory(project);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin");
        Path java = javaBin.resolve(Files.isRegularFile(javaBin.resolve("java.exe")) ? "java.exe" : "java");
        if (!Files.isRegularFile(java)) {
            throw new IOException("Missing Java executable: " + java);
        }
        Path temporary = requireDirectory(Path.of(System.getProperty("java.io.tmpdir")));
        return List.of(
                java.toString(),
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Djava.io.tmpdir=" + temporary,
                "-cp",
                classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)),
                CONSUMER,
                scenario,
                root.toString(),
                coreJar.toString(),
                runtimeDirectory.toString());
    }

    Result run(String scenario, Path project) throws IOException, InterruptedException {
        return run(scenario, project, Duration.ofSeconds(30));
    }

    Result run(String scenario, Path project, Duration timeout) throws IOException, InterruptedException {
        return run(scenario, project, timeout, null);
    }

    private Result run(String scenario, Path project, Duration timeout, OfflineSandbox offline)
            throws IOException, InterruptedException {
        long timeoutMillis = timeout.toMillis();
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeout must be at least one millisecond");
        }
        List<String> command = new ArrayList<>(command(scenario, project));
        OfflineSandbox.Run evidence = offline == null ? null : offline.newRun();
        if (evidence != null) {
            // Override the generic JVM defaults before the explicit artifact classpath.
            command.addAll(command.indexOf("-cp"), evidence.jvmArguments());
        }
        Path output = Files.createTempDirectory(sandbox, "run-");
        Path stdout = output.resolve("stdout.txt");
        Path stderr = output.resolve("stderr.txt");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(project.toFile())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment()
                .keySet()
                .removeAll(List.of("CLASSPATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"));
        if (evidence != null) {
            evidence.configure(builder.environment());
        }
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new AssertionError(diagnostics("Packaged core timed out: " + scenario, stdout, stderr));
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(diagnostics(
                        "Packaged core exited with " + process.exitValue() + ": " + scenario, stdout, stderr));
            }
            String error = Files.readString(stderr);
            if (evidence != null) {
                evidence.verifyLexical(CONSUMER);
                error = OfflineSandbox.applicationStderr(error);
            }
            return new Result(Files.readString(stdout), error);
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    Result runOffline(String scenario, Path project, OfflineSandbox offline) throws IOException, InterruptedException {
        Objects.requireNonNull(offline, "offline");
        return run(scenario, project, Duration.ofSeconds(30), offline);
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            throw new AssertionError("Could not terminate packaged core process " + process.pid());
        }
    }

    private static String diagnostics(String message, Path stdout, Path stderr) throws IOException {
        return message + "\nstdout (" + stdout + "):\n" + Files.readString(stdout) + "\nstderr (" + stderr + "):\n"
                + Files.readString(stderr);
    }

    private static Path requireDirectory(Path path) throws IOException {
        Path directory = path.toRealPath();
        if (!Files.isDirectory(directory)) {
            throw new IOException("Not a directory: " + directory);
        }
        return directory;
    }

    private static Path requireJar(Path path) throws IOException {
        Path jar = path.toRealPath();
        if (!Files.isRegularFile(jar) || !jar.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Not a packaged JAR: " + jar);
        }
        try (JarFile archive = new JarFile(jar.toFile())) {
            var manifest = archive.getManifest();
            String additionalClasspath =
                    manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (additionalClasspath != null && !additionalClasspath.isBlank()) {
                throw new IOException("JAR manifest adds an implicit classpath: " + jar);
            }
        }
        return jar;
    }

    record Result(String stdout, String stderr) {
        Result {
            Objects.requireNonNull(stdout, "stdout");
            Objects.requireNonNull(stderr, "stderr");
        }
    }
}
