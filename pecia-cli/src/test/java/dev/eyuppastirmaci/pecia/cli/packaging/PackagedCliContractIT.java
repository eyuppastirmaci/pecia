package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.Bootstrap;
import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.testing.OfflineSandbox;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliContractIT {

    private static final String TOKENIZER_PATH = packageEntry(MiniLmTokenizer.class) + "all-MiniLM-L6-v2/";
    private static final String VOCABULARY_SHA256 = "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3";

    @TempDir
    Path sandbox;

    private PackagedCli cli;
    private Path project;

    @BeforeEach
    void prepareProject() throws IOException {
        cli = PackagedCli.create(sandbox);
        project = cli.project();
    }

    @Test
    void helpPreservesTheCommandContract() throws Exception {
        Result result = cli.run(project, "--help");

        assertSuccess(result);
        assertEquals("""
            Usage: pecia [-hV] [COMMAND]
            Indexes documents and source code for offline lexical search.
              -h, --help      Show this help message and exit.
              -V, --version   Print version information and exit.
            Commands:
              init   Writes a .pecia.toml config file at the project root.
              index  Builds a local lexical index from a folder of text files.
              query  Searches the local index using BM25 lexical ranking.
            """, result.out());
    }

    @Test
    void versionPreservesTheCommandContract() throws Exception {
        Result result = cli.run(project, "--version");

        assertSuccess(result);
        assertEquals("pecia 0.1.0-SNAPSHOT\n", result.out());
    }

    @Test
    void executableJarIncludesCoreVocabularyAndLicenses() throws Exception {
        try (JarFile jar = new JarFile(cli.executableJar().toFile())) {
            assertEquals(
                    Bootstrap.class.getName(),
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getJarEntry(classEntry(IndexService.class)));
            assertNotNull(jar.getJarEntry(classEntry(QueryService.class)));
            assertNotNull(jar.getJarEntry("picocli/CommandLine.class"));
            assertTrue(
                    jar.stream().noneMatch(entry -> entry.getName().startsWith(packageEntry(OfflineSandbox.class))),
                    "Test-only offline support must not be included in the CLI distribution");
            assertTrue(
                    jar.stream()
                            .map(entry -> entry.getName())
                            .noneMatch(name -> name.startsWith("ai/djl/")
                                    || name.startsWith("org/tensorflow/")
                                    || name.startsWith("org/pytorch/")
                                    || name.endsWith(".onnx")
                                    || name.endsWith(".safetensors")
                                    || name.endsWith("pytorch_model.bin")),
                    "The CLI JAR must not bundle another semantic runtime or any model");
            byte[] vocabulary = resource(jar, TOKENIZER_PATH + "vocab.txt");
            assertEquals(
                    VOCABULARY_SHA256,
                    HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(vocabulary)));
            String notice = new String(resource(jar, TOKENIZER_PATH + "NOTICE.txt"), StandardCharsets.UTF_8);
            assertTrue(notice.contains("sentence-transformers/all-MiniLM-L6-v2"));
            assertTrue(notice.contains("1110a243fdf4706b3f48f1d95db1a4f5529b4d41"));
            assertTrue(notice.contains(VOCABULARY_SHA256));
            String tokenizerLicense = new String(resource(jar, TOKENIZER_PATH + "LICENSE.txt"), StandardCharsets.UTF_8);
            assertTrue(tokenizerLicense.contains("Apache License"));
            assertTrue(tokenizerLicense.contains("Version 2.0, January 2004"));
            String commonmarkLicense =
                    new String(resource(jar, "META-INF/licenses/commonmark-LICENSE.txt"), StandardCharsets.UTF_8);
            assertTrue(commonmarkLicense.contains("Copyright (c) 2015, Atlassian Pty Ltd"));
            assertTrue(commonmarkLicense.contains("Redistribution and use in source and binary forms"));
        }
    }

    @Test
    void executableJarCarriesTheSemanticRuntimeWithoutDebugSymbols() throws Exception {
        try (JarFile jar = new JarFile(cli.executableJar().toFile())) {
            assertNotNull(jar.getJarEntry("ai/onnxruntime/OrtEnvironment.class"));

            for (String library : List.of(
                    "linux-x64/libonnxruntime.so",
                    "linux-x64/libonnxruntime4j_jni.so",
                    "linux-aarch64/libonnxruntime.so",
                    "linux-aarch64/libonnxruntime4j_jni.so",
                    "osx-aarch64/libonnxruntime.dylib",
                    "osx-aarch64/libonnxruntime4j_jni.dylib",
                    "win-x64/onnxruntime.dll",
                    "win-x64/onnxruntime4j_jni.dll",
                    "win-x64/onnxruntime_providers_shared.dll")) {
                assertNotNull(jar.getJarEntry("ai/onnxruntime/native/" + library), library);
            }

            assertTrue(
                    jar.stream().noneMatch(entry -> entry.getName().contains(".dSYM/")),
                    "The CLI JAR must not carry the runtime's macOS debug symbols");

            // Runtime releases from 1.29.0 embed Microsoft's telemetry uploader and its endpoint in these libraries.
            for (var entry : jar.stream()
                    .filter(entry -> entry.getName().startsWith("ai/onnxruntime/native/") && !entry.isDirectory())
                    .toList()) {
                String binary;

                try (var input = jar.getInputStream(entry)) {
                    binary = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
                }

                for (String marker : List.of("events.data.microsoft.com", "OneCollector", "ORT_DISABLE_TELEMETRY")) {
                    assertFalse(binary.contains(marker), entry.getName() + " contains " + marker);
                }
            }

            String license =
                    new String(resource(jar, "META-INF/licenses/onnxruntime-LICENSE.txt"), StandardCharsets.UTF_8);
            assertTrue(license.contains("Copyright (c) Microsoft Corporation"));
            assertTrue(license.contains("Permission is hereby granted, free of charge"));
            String notices = new String(resource(jar, "ThirdPartyNotices.txt"), StandardCharsets.UTF_8);
            assertTrue(notices.startsWith("THIRD PARTY SOFTWARE NOTICES AND INFORMATION"));
        }
    }

    @Test
    void executableJarCarriesTheVectorExtensionForEachSemanticPlatformUnchanged() throws Exception {
        String directory = "dev/eyuppastirmaci/pecia/semantic/sqlite-vec/";

        try (JarFile jar = new JarFile(cli.executableJar().toFile())) {
            String notice = new String(resource(jar, directory + "NOTICE.txt"), StandardCharsets.UTF_8);
            List<String> libraries =
                    List.of("linux-x64/vec0.so", "linux-aarch64/vec0.so", "osx-aarch64/vec0.dylib", "win-x64/vec0.dll");
            List<String> expected = new ArrayList<>(List.of("NOTICE.txt"));
            expected.addAll(libraries);

            assertEquals(
                    expected.stream().sorted().toList(),
                    jar.stream()
                            .filter(entry -> entry.getName().startsWith(directory) && !entry.isDirectory())
                            .map(entry -> entry.getName().substring(directory.length()))
                            .sorted()
                            .toList());

            // The notice pins each library's SHA-256, so the JAR must carry the release's bytes unchanged.
            for (String library : libraries) {
                String sha256 = HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(resource(jar, directory + library)));

                assertTrue(
                        notice.lines().anyMatch(line -> line.startsWith(library + " ") && line.endsWith(sha256)),
                        library);
            }

            assertTrue(new String(resource(jar, "META-INF/licenses/sqlite-vec-LICENSE-MIT.txt"), StandardCharsets.UTF_8)
                    .contains("Copyright (c) 2024 Alex Garcia"));
            assertTrue(
                    new String(resource(jar, "META-INF/licenses/sqlite-vec-LICENSE-APACHE.txt"), StandardCharsets.UTF_8)
                            .contains("Apache License"));
        }
    }

    private static String classEntry(Class<?> type) {
        return type.getName().replace('.', '/') + ".class";
    }

    /** Returns the JAR directory entry, with a trailing slash, of the package containing type. */
    private static String packageEntry(Class<?> type) {
        return type.getPackageName().replace('.', '/') + "/";
    }

    private static byte[] resource(JarFile jar, String path) throws IOException {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, "Missing packaged resource: " + path);

        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
