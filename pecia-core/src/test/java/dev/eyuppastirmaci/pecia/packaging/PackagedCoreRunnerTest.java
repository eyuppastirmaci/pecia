package dev.eyuppastirmaci.pecia.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackagedCoreRunnerTest {

    private static final Path PACKAGE = Path.of("dev/eyuppastirmaci/pecia/packaging/consumer");

    @TempDir
    Path fixture;

    private Path coreJar;
    private Path runtime;
    private Path testClasses;
    private Path sandbox;
    private Path project;

    @BeforeEach
    void prepareArtifacts() throws IOException {
        fixture = fixture.toRealPath();
        coreJar = writeJar(fixture.resolve("core library.jar"), null);
        runtime = Files.createDirectory(fixture.resolve("runtime jars"));
        writeJar(runtime.resolve("z-dependency.jar"), null);
        writeJar(runtime.resolve("a-dependency.jar"), null);
        Files.writeString(runtime.resolve("ignored.txt"), "not part of the runtime");
        testClasses = Files.createDirectories(fixture.resolve("target/test-classes"));
        Path consumerPackage = Files.createDirectories(testClasses.resolve(PACKAGE));
        Files.write(consumerPackage.resolve("PackagedCoreConsumer.class"), new byte[] {1});
        Files.write(consumerPackage.resolve("PackagedCoreConsumer$Nested.class"), new byte[] {2});
        Files.write(consumerPackage.resolve("PipelineScenario.class"), new byte[] {7});
        Files.write(consumerPackage.resolve("PackagedCoreConsumerTest.class"), new byte[] {4});
        Files.write(consumerPackage.resolve("ScenarioIT$Nested.class"), new byte[] {8});
        Files.write(consumerPackage.getParent().resolve("CorePackagingIT.class"), new byte[] {3});
        Files.writeString(testClasses.resolve("fixture.txt"), "must not leak into the consumer");
        Path bootstrap = Files.createDirectories(testClasses.resolve("dev/eyuppastirmaci/pecia"));
        Files.write(bootstrap.resolve("Bootstrap.class"), new byte[] {5});
        Path junit = Files.createDirectories(testClasses.resolve("org/junit/jupiter/api"));
        Files.write(junit.resolve("Test.class"), new byte[] {6});
        Files.createDirectories(fixture.resolve("target/classes"));
        sandbox = Files.createDirectory(fixture.resolve("runner output"));
        project = Files.createDirectory(fixture.resolve("test project"));
    }

    @Test
    void copiesOnlyConsumerClassesAndBuildsAnExplicitClasspathWithSpaceContainingPaths() throws Exception {
        PackagedCoreRunner runner = prepare();
        List<String> command = runner.command("lexical-lifecycle", project);
        int classpathOption = command.indexOf("-cp");
        Path isolated = sandbox.resolve("consumer-classes");

        assertEquals(
                List.of(
                        coreJar.toString(), runtime.resolve("a-dependency.jar").toString(),
                        runtime.resolve("z-dependency.jar").toString(), isolated.toString()),
                List.of(command.get(classpathOption + 1).split(Pattern.quote(File.pathSeparator))));
        assertEquals(
                List.of(
                        "dev.eyuppastirmaci.pecia.packaging.consumer.PackagedCoreConsumer",
                        "lexical-lifecycle",
                        project.toString(),
                        coreJar.toString(),
                        runtime.toString()),
                command.subList(classpathOption + 2, command.size()));
        assertFalse(command.contains(testClasses.toString()));
        assertFalse(command.contains(fixture.resolve("target/classes").toString()));
        assertTrue(command.contains("-Djava.io.tmpdir="
                + Path.of(System.getProperty("java.io.tmpdir")).toRealPath()));
        try (var files = Files.walk(isolated)) {
            assertEquals(
                    Set.of(
                            PACKAGE.resolve("PackagedCoreConsumer.class"),
                            PACKAGE.resolve("PackagedCoreConsumer$Nested.class"),
                            PACKAGE.resolve("PipelineScenario.class")),
                    files.filter(Files::isRegularFile).map(isolated::relativize).collect(Collectors.toSet()));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-core",
                "directory-core",
                "invalid-jar",
                "missing-runtime",
                "empty-runtime",
                "directory-dependency",
                "missing-consumer",
                "directory-consumer"
            })
    void rejectsMissingOrInvalidArtifactsBeforeCreatingConsumerOutput(String failure) throws Exception {
        switch (failure) {
            case "missing-core" -> Files.delete(coreJar);
            case "directory-core" -> {
                Files.delete(coreJar);
                Files.createDirectory(coreJar);
            }
            case "invalid-jar" -> Files.writeString(coreJar, "not a jar");
            case "missing-runtime" -> runtime = fixture.resolve("missing runtime");
            case "empty-runtime" -> {
                Files.delete(runtime.resolve("a-dependency.jar"));
                Files.delete(runtime.resolve("z-dependency.jar"));
            }
            case "directory-dependency" -> Files.createDirectory(runtime.resolve("broken.jar"));
            case "missing-consumer" -> Files.delete(testClasses.resolve(PACKAGE).resolve("PackagedCoreConsumer.class"));
            case "directory-consumer" -> {
                Path consumer = testClasses.resolve(PACKAGE).resolve("PackagedCoreConsumer.class");
                Files.delete(consumer);
                Files.createDirectory(consumer);
            }
            default -> throw new AssertionError(failure);
        }

        assertThrows(IOException.class, this::prepare);
        try (var output = Files.list(sandbox)) {
            assertEquals(List.of(), output.toList());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectsManifestClasspathThatWouldLoadArtifactsOutsideTheExplicitList(boolean onCore) throws Exception {
        writeJar(onCore ? coreJar : runtime.resolve("a-dependency.jar"), "../target/classes/ junit.jar");

        IOException failure = assertThrows(IOException.class, this::prepare);

        assertTrue(failure.getMessage().contains("implicit classpath"));
        assertFalse(Files.exists(sandbox.resolve("consumer-classes")));
    }

    @Test
    void refusesToReuseAnExistingConsumerDirectory() throws Exception {
        Files.createDirectory(sandbox.resolve("consumer-classes"));
        Files.writeString(sandbox.resolve("consumer-classes/unrelated.txt"), "stale output");

        assertThrows(IOException.class, this::prepare);
        assertEquals("stale output", Files.readString(sandbox.resolve("consumer-classes/unrelated.txt")));
    }

    @Test
    void passesArgumentsLiterallyAndKeepsSuccessfulStderrAvailable() throws Exception {
        compileProbe();
        PackagedCoreRunner.Result result = prepare().run("echo", project);

        assertEquals(
                List.of("echo", project.toString(), coreJar.toString(), runtime.toString()),
                result.stdout().lines().toList());
        assertEquals("probe warning", result.stderr().strip());
    }

    @Test
    void reportsNonzeroExitWithBothOutputStreamsAndLogPaths() throws Exception {
        compileProbe();
        PackagedCoreRunner runner = prepare();

        AssertionError failure = assertThrows(AssertionError.class, () -> runner.run("fail", project));

        assertTrue(failure.getMessage().contains("exited with 7: fail"));
        assertTrue(failure.getMessage().contains("stdout.txt"));
        assertTrue(failure.getMessage().contains("stderr.txt"));
        assertTrue(failure.getMessage().contains("probe stdout"));
        assertTrue(failure.getMessage().contains("probe stderr"));
    }

    @Test
    @Timeout(15)
    void redirectsBothStreamsWithoutDeadlockingOnFullPipes() throws Exception {
        compileProbe();
        PackagedCoreRunner.Result result = prepare().run("noisy", project);

        assertEquals(262_144, result.stdout().length());
        assertEquals(262_144, result.stderr().length());
        assertTrue(result.stdout().chars().allMatch(value -> value == 'o'));
        assertTrue(result.stderr().chars().allMatch(value -> value == 'e'));
    }

    @Test
    @Timeout(15)
    void terminatesAndReapsATimedOutConsumer() throws Exception {
        compileProbe();
        PackagedCoreRunner runner = prepare();

        AssertionError failure =
                assertThrows(AssertionError.class, () -> runner.run("wait", project, Duration.ofSeconds(3)));

        assertTrue(failure.getMessage().contains("timed out: wait"));
        long pid = Long.parseLong(Files.readString(project.resolve("child.pid")));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private PackagedCoreRunner prepare() throws IOException {
        return PackagedCoreRunner.prepare(coreJar, runtime, testClasses, sandbox);
    }

    private void compileProbe() throws IOException {
        Path source = fixture.resolve("PackagedCoreConsumer.java");
        Files.writeString(source, """
            package dev.eyuppastirmaci.pecia.packaging.consumer;
            public final class PackagedCoreConsumer {
                public static void main(String[] args) throws Exception {
                    switch (args[0]) {
                        case "echo" -> {
                            for (String arg : args) {
                                System.out.println(arg);
                            }
                            System.err.println("probe warning");
                        }
                        case "fail" -> {
                            System.out.println("probe stdout");
                            System.err.println("probe stderr");
                            System.exit(7);
                        }
                        case "noisy" -> {
                            System.out.print("o".repeat(262144));
                            System.err.print("e".repeat(262144));
                        }
                        case "wait" -> {
                            java.nio.file.Files.writeString(java.nio.file.Path.of(args[1]).resolve("child.pid"),
                                    Long.toString(ProcessHandle.current().pid()));
                            Thread.sleep(60000);
                        }
                        default -> throw new IllegalArgumentException(args[0]);
                    }
                }
                private static final class Nested {}
            }
            """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Runner tests require JDK 21");
        StringWriter diagnostics = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(
                            diagnostics,
                            manager,
                            null,
                            List.of("--release", "21", "-classpath", coreJar.toString(), "-d", testClasses.toString()),
                            null,
                            manager.getJavaFileObjects(source))
                    .call();
            assertTrue(compiled, diagnostics.toString());
        }
    }

    private static Path writeJar(Path path, String classpath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (classpath != null) {
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classpath);
        }
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            // An empty valid JAR is sufficient for process/classpath mechanics tests.
            output.finish();
        }
        return path;
    }
}
