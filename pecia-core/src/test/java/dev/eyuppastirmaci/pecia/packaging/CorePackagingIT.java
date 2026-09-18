package dev.eyuppastirmaci.pecia.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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

    private static final String TOKENIZER_RESOURCES = "dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/";
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
        // Copy only the standalone consumer and its nested classes, excluding all test
        // helpers/resources.
        Path consumerDirectory = Files.createDirectories(fixture.resolve("consumer-classes"));
        Path packagePath = Path.of("dev/eyuppastirmaci/pecia/packaging");
        Path destination = Files.createDirectories(consumerDirectory.resolve(packagePath));
        try (var classes = Files.list(requiredPath("pecia.it.testClasses").resolve(packagePath))) {
            for (Path type : classes.filter(
                            path -> path.getFileName().toString().matches("PackagedCoreConsumer(?:\\$[^/]+)?\\.class"))
                    .toList()) {
                Files.copy(type, destination.resolve(type.getFileName()));
            }
        }
        urls.add(consumerDirectory.toUri().toURL());

        // The platform parent prevents Maven's production and test classpaths from satisfying missing
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
                    "ai.onnxruntime.OrtEnvironment",
                    "ai.djl.Model")) {
                assertThrows(ClassNotFoundException.class, () -> consumerLoader.loadClass(unavailable), unavailable);
            }

            Class<?> consumer = consumerLoader.loadClass("dev.eyuppastirmaci.pecia.packaging.PackagedCoreConsumer");

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
                    "Copyright (c) 2015, Atlassian Pty Ltd");

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
            assertNotNull(jar.getJarEntry("dev/eyuppastirmaci/pecia/index/IndexService.class"));
            for (String migration : List.of("V1__create_initial_schema.sql", "V2__add_chunk_fts.sql")) {
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

    private static byte[] readEntry(JarFile jar, String name) throws IOException {
        var entry = jar.getJarEntry(name);
        assertNotNull(entry, "Missing packaged resource: " + name);

        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
