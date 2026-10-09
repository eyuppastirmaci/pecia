package dev.eyuppastirmaci.pecia.cli.packaging;

import static dev.eyuppastirmaci.pecia.cli.packaging.PackagedCliAssertions.assertSuccess;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.cli.packaging.PackagedCli.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedCliContractIT {

    private static final String TOKENIZER_PATH = "dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/";
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
                    "dev.eyuppastirmaci.pecia.Bootstrap",
                    jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getJarEntry("dev/eyuppastirmaci/pecia/index/IndexService.class"));
            assertNotNull(jar.getJarEntry("dev/eyuppastirmaci/pecia/search/QueryService.class"));
            assertNotNull(jar.getJarEntry("picocli/CommandLine.class"));
            assertTrue(
                    jar.stream().noneMatch(entry -> entry.getName().startsWith("dev/eyuppastirmaci/pecia/testing/")),
                    "Test-only offline support must not be included in the CLI distribution");
            assertTrue(
                    jar.stream()
                            .map(entry -> entry.getName())
                            .noneMatch(name -> name.startsWith("ai/onnxruntime/")
                                    || name.startsWith("ai/djl/")
                                    || name.startsWith("org/tensorflow/")
                                    || name.startsWith("org/pytorch/")
                                    || name.endsWith(".onnx")
                                    || name.endsWith(".safetensors")
                                    || name.endsWith("pytorch_model.bin")),
                    "The lexical JAR must not bundle a semantic runtime or model");
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

    private static byte[] resource(JarFile jar, String path) throws IOException {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, "Missing packaged resource: " + path);

        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }
}
