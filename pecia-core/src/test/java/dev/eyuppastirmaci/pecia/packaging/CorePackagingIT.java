package dev.eyuppastirmaci.pecia.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorePackagingIT {

    private static final String TOKENIZER_RESOURCES = packageEntry(MiniLmTokenizer.class) + "all-MiniLM-L6-v2/";
    private static final String VOCABULARY_SHA256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3";

    @TempDir
    Path fixture;

    @Test
    void runsTheSharedEngineUsingOnlyTheCoreJarAndRuntimeDependencies() throws Exception {
        runConsumer("verify");
    }

    @Test
    void migratesExistingV1DataUsingOnlyPackagedSqlAndRuntimeDependencies() throws Exception {
        runConsumer("verifyV1Migration");
    }

    @Test
    void searchesUsingOnlyThePublicApiInThePackagedCore() throws Exception {
        runConsumer("verifyLexicalSearch");
    }

    @Test
    void indexesAFolderUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyFolderIndex");
    }

    @Test
    void queriesAnIndexedFolderReadOnlyUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyReadOnlyQuery");
    }

    @Test
    void reportsConfigurationWarningsUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyConfigurationWarnings");
    }

    @Test
    void choosesSemanticIndexingPerRunUsingOnlyThePackagedCore() throws Exception {
        // The consumer embeds with the tiny model, which it finds beside the project rather than inside it.
        try (InputStream model = CorePackagingIT.class.getResourceAsStream("/semantic/tiny/tiny.onnx")) {
            Files.copy(model, fixture.resolve("tiny.onnx"));
        }
        runConsumer("verifySemanticIndexingOptions");
    }

    @Test
    void appliesSearchModesUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifySearchModes");
    }

    @Test
    void persistsIndexingProfilesAtomicallyUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyIndexingProfiles");
    }

    @Test
    void persistsDeterministicChunkIdentitiesUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyChunkIdentities");
    }

    @Test
    void indexesIncrementallyWithDeterministicChunkIdentitiesUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyIncrementalChunkIdentities");
    }

    @Test
    void reindexesMigratedV3ProfilesOnceUsingOnlyThePackagedCore() throws Exception {
        runConsumer("verifyV3IncrementalUpgrade");
    }

    @Test
    void completesLexicalLifecycleAndReopensItInSeparatePackagedCoreProcesses() throws Exception {
        Path sandbox = Files.createDirectory(fixture.resolve("forked core")).toRealPath();
        Path project = Files.createDirectory(fixture.resolve("lexical project")).toRealPath();
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                sandbox);
        OfflineSandbox offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();

        assertEquals(
                "PACKAGED_CORE_OK lexical-lifecycle",
                runner.runOffline("lexical-lifecycle", project, offline)
                        .stdout()
                        .strip());
        assertEquals(
                "PACKAGED_CORE_OK lexical-reopen",
                runner.runOffline("lexical-reopen", project, offline).stdout().strip());
    }

    @Test
    void lexicalRunsUpdateAndSearchAnIndexWithEmbeddingsOfflineWithoutTheSemanticRuntime() throws Exception {
        Path sandbox = Files.createDirectory(fixture.resolve("embedded index")).toRealPath();
        Path project =
                Files.createDirectory(fixture.resolve("embedded index project")).toRealPath();
        try (InputStream model = CorePackagingIT.class.getResourceAsStream("/semantic/tiny/tiny.onnx")) {
            Files.copy(model, fixture.resolve("tiny.onnx"));
        }
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                sandbox);
        OfflineSandbox offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();

        assertEquals(
                "PACKAGED_CORE_OK semantic-index",
                runner.runOfflineEmbedding("semantic-index", project, offline)
                        .stdout()
                        .strip());
        // The second process is lexical: no network attempt, no storage change, and no semantic class initialized.
        assertEquals(
                "PACKAGED_CORE_OK lexical-after-semantic",
                runner.runOffline("lexical-after-semantic", project, offline)
                        .stdout()
                        .strip());
    }

    @Test
    void semanticIndexingFailsOfflineWithoutDownloadingOrTouchingTheIndex() throws Exception {
        Path sandbox = Files.createDirectory(fixture.resolve("offline model")).toRealPath();
        Path project =
                Files.createDirectory(fixture.resolve("offline model project")).toRealPath();
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                sandbox);
        OfflineSandbox offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();
        SemanticModel model = SemanticModel.MINILM;
        Path lockFile = Path.of("models", model.name(), model.revision(), ".lock");

        assertEquals(
                "PACKAGED_CORE_OK semantic-offline",
                runner.runOfflineDeniedDownload(
                                "semantic-offline",
                                project,
                                offline,
                                model.downloadUri().getHost(),
                                lockFile)
                        .stdout()
                        .strip());
    }

    @Test
    void embedsOfflineWithATinyModelUsingOnlyThePackagedCore() throws Exception {
        Path sandbox =
                Files.createDirectory(fixture.resolve("offline embedding")).toRealPath();
        Path project = Files.createDirectory(fixture.resolve("offline embedding project"))
                .toRealPath();
        try (InputStream model = CorePackagingIT.class.getResourceAsStream("/semantic/tiny/tiny.onnx")) {
            Files.copy(model, project.resolve("tiny.onnx"));
        }
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                sandbox);
        OfflineSandbox offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();

        assertEquals(
                "PACKAGED_CORE_OK semantic-embedding",
                runner.runOfflineEmbedding("semantic-embedding", project, offline)
                        .stdout()
                        .strip());
    }

    @Test
    void indexesTheSharedCorpusIdenticallyInSeparatePackagedCoreProcesses() throws Exception {
        Path sandbox =
                Files.createDirectory(fixture.resolve("forked determinism")).toRealPath();
        Path first = Files.createDirectory(fixture.resolve("determinism first")).toRealPath();
        Path second = Files.createDirectories(fixture.resolve("determinism second/nested root"))
                .toRealPath();
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                sandbox);
        OfflineSandbox offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();

        String firstDump = packagedDump(runner, "lexical-determinism", first, offline);
        String secondDump = packagedDump(runner, "lexical-determinism-reversed", second, offline);

        assertEquals(
                LexicalTestCorpus.files().size(),
                firstDump.lines().filter(line -> line.startsWith("file ")).count());
        assertEquals(firstDump, secondDump);
        // The development build must agree with the packaged artifact; it does not replace the packaged runs above.
        assertEquals(
                firstDump,
                developmentDump(
                        Files.createDirectory(fixture.resolve("development")).toRealPath()));
    }

    @Test
    void followsUnchangedRerunsAndCaseOnlyRenamesInAPackagedCoreProcess() throws Exception {
        Path sandbox =
                Files.createDirectory(fixture.resolve("forked incremental")).toRealPath();
        Path project =
                Files.createDirectory(fixture.resolve("incremental project")).toRealPath();
        PackagedCoreRunner runner = PackagedCoreRunner.prepare(
                requiredPath("pecia.it.jar"),
                requiredPath("pecia.it.runtimeDirectory"),
                requiredPath("pecia.it.testClasses"),
                sandbox);
        OfflineSandbox offline = OfflineSandbox.create(Files.createDirectory(sandbox.resolve("offline")));
        offline.verifyNetworkDenied();

        String report = packagedDump(runner, "lexical-incremental", project, offline);

        // The consumer probes the filesystem itself; agreeing here shows which rename branch actually ran.
        assertEquals("CASE_FOLDING " + foldsCase(fixture) + "\n", report);
    }

    private static boolean foldsCase(Path directory) throws IOException {
        Path probe = Files.writeString(directory.resolve("CaseProbe.tmp"), "");

        try {
            return Files.exists(directory.resolve("caseprobe.tmp"));
        } finally {
            Files.delete(probe);
        }
    }

    /* Returns the scenario's standard output before its success marker. */
    private static String packagedDump(PackagedCoreRunner runner, String scenario, Path project, OfflineSandbox offline)
            throws Exception {
        String stdout = runner.runOffline(scenario, project, offline).stdout().replace("\r\n", "\n");
        String marker = "PACKAGED_CORE_OK " + scenario + "\n";
        assertTrue(stdout.endsWith(marker), stdout);
        return stdout.substring(0, stdout.length() - marker.length());
    }

    private static String developmentDump(Path project) throws Exception {
        Files.createDirectory(project.resolve(".git"));
        LexicalTestCorpus.write(project);
        IndexResult result = new IndexService(new PeciaConfigLoader(new PeciaConfigParser())).index(project);
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        return IndexDump.read(result.context().databasePath(), project, LexicalTestCorpus.QUERIES)
                .toText();
    }

    private void runConsumer(String method) throws Exception {
        Path coreJar = requiredPath("pecia.it.jar");
        Path runtimeDirectory = requiredPath("pecia.it.runtimeDirectory");
        List<URL> urls = new ArrayList<>();
        urls.add(coreJar.toUri().toURL());

        try (var dependencies = Files.list(runtimeDirectory)) {
            for (Path dependency : dependencies
                    .filter(path -> path.toString().endsWith(".jar"))
                    .sorted()
                    .toList()) {
                urls.add(dependency.toUri().toURL());
            }
        }

        assertTrue(urls.size() > 1, "The consumer needs the packaged runtime dependencies");
        // Copy only the consumer package and allowlisted support types, excluding other test helpers and resources.
        Path testClasses = requiredPath("pecia.it.testClasses");
        Path consumerDirectory = PackagedCoreRunner.copyConsumerClasses(
                testClasses, PackagedCoreRunner.consumerClasses(testClasses), fixture.resolve("consumer-classes"));
        urls.add(consumerDirectory.toUri().toURL());

        // The platform parent prevents the build's production and test classpaths from satisfying missing
        // JAR contents.
        try (URLClassLoader consumerLoader =
                new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader())) {
            for (String unavailable : List.of(
                    "dev.eyuppastirmaci.pecia.Bootstrap",
                    "picocli.CommandLine",
                    "javafx.application.Application",
                    "org.slf4j.impl.StaticLoggerBinder",
                    "org.junit.jupiter.api.Test",
                    "dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport",
                    "ai.djl.Model")) {
                assertThrows(ClassNotFoundException.class, () -> consumerLoader.loadClass(unavailable), unavailable);
            }

            Class<?> consumer = consumerLoader.loadClass(PackagedCoreRunner.CONSUMER);

            try {
                consumer.getMethod(method, Path.class, Path.class, Path.class)
                        .invoke(
                                null,
                                Files.createDirectory(fixture.resolve("project"))
                                        .toRealPath(),
                                coreJar,
                                runtimeDirectory);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("The isolated packaged-core consumer failed: " + method, failure.getCause());
            }
        }
    }

    @Test
    void preservesTheVocabularyAndRequiredLicenseResources() throws Exception {
        try (JarFile jar = new JarFile(requiredPath("pecia.it.jar").toFile())) {
            assertTrue(
                    jar.stream().noneMatch(entry -> entry.getName().startsWith(packageEntry(OfflineSandbox.class))),
                    "Test-only offline support must not be included in the core distribution");
            byte[] vocabulary = readEntry(jar, TOKENIZER_RESOURCES + "vocab.txt");
            assertEquals(
                    VOCABULARY_SHA256,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(vocabulary)));

            Map<String, String> requiredNotices = Map.of(
                    TOKENIZER_RESOURCES + "NOTICE.txt",
                    "sentence-transformers/all-MiniLM-L6-v2",
                    TOKENIZER_RESOURCES + "LICENSE.txt",
                    "Apache License",
                    "META-INF/licenses/commonmark-LICENSE.txt",
                    "Copyright (c) 2015, Atlassian Pty Ltd",
                    "META-INF/licenses/onnxruntime-LICENSE.txt",
                    "Copyright (c) Microsoft Corporation");

            for (var notice : requiredNotices.entrySet()) {
                String text = new String(readEntry(jar, notice.getKey()), StandardCharsets.UTF_8);
                assertTrue(text.contains(notice.getValue()), notice.getKey());
            }
        }
    }

    @Test
    void producesAPlainCoreLibraryWithoutCliOrBundledDependencies() throws IOException {
        try (JarFile jar = new JarFile(requiredPath("pecia.it.jar").toFile())) {
            assertNotNull(jar.getManifest(), "The core JAR must have a manifest");
            assertNull(jar.getManifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
            assertNotNull(jar.getJarEntry(classEntry(IndexService.class)));
            for (String migration : List.of(
                    "V1__create_initial_schema.sql",
                    "V2__add_chunk_fts.sql",
                    "V3__add_file_indexing_profiles.sql",
                    "V4__add_chunk_identities.sql")) {
                assertTrue(
                        readEntry(jar, "db/migration/" + migration).length > 0,
                        "The core JAR must contain the migration: " + migration);
            }
            List<String> forbiddenPrefixes = List.of(
                    "dev/eyuppastirmaci/pecia/cli/",
                    "dev/eyuppastirmaci/pecia/Bootstrap",
                    "picocli/",
                    "javafx/",
                    "org/commonmark/",
                    "org/eclipse/jgit/",
                    "org/tomlj/",
                    "org/slf4j/",
                    "org/sqlite/");

            try (var entries = jar.stream()) {
                assertFalse(
                        entries.anyMatch(entry -> forbiddenPrefixes.stream().anyMatch(entry.getName()::startsWith)),
                        "The core artifact must contain its own library classes without application" + " dependencies");
            }
        }
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        assertNotNull(value, "Missing build property: " + property);
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.exists(path), "Missing build output: " + path);

        return path;
    }

    private static String classEntry(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    /** Returns the JAR directory entry, with a trailing slash, of the package containing type. */
    private static String packageEntry(Class<?> type) {
        return type.getPackageName().replace('.', '/') + "/";
    }

    private static byte[] readEntry(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        assertNotNull(entry, "Missing packaged resource: " + name);

        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
