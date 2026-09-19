package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OfflineEnvironmentTest {

    private static final String INDEX_SERVICE = "dev.eyuppastirmaci.pecia.index.IndexService";
    private static final String SQLITE_NATIVE =
            "sqlite-3.53.4.0-12345678-1234-4321-abcd-123456789abc-libsqlitejdbc.dylib";

    @TempDir
    Path sandbox;

    @Test
    void isolatesJvmAndEnvironmentPathsWithoutChangingHostOrUnrelatedVariables() throws Exception {
        Map<String, String> host = new HashMap<>(System.getenv());
        String userHome = System.getProperty("user.home");
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<String, String> child = new HashMap<>();
        child.put("PATH", "keep-this-path");
        child.put("CUSTOM_SETTING", "keep-this-value");
        child.put("home", "/host-home");
        child.put("XDG_CACHE_HOME", "/host-cache");
        child.put("HF_HOME", "/host-models");

        isolated.configure(child);

        assertEquals(host, System.getenv());
        assertEquals(userHome, System.getProperty("user.home"));
        assertEquals("keep-this-path", child.get("PATH"));
        assertEquals("keep-this-value", child.get("CUSTOM_SETTING"));
        assertFalse(child.containsKey("home"));
        for (String variable : List.of("HOME", "USERPROFILE")) {
            assertEquals(isolated.home().toString(), child.get(variable));
        }
        assertEquals(isolated.home().toString(), child.get("HOMEDRIVE") + child.get("HOMEPATH"));
        for (String variable : List.of(
                "XDG_CONFIG_HOME",
                "XDG_CONFIG_DIRS",
                "XDG_DATA_HOME",
                "XDG_DATA_DIRS",
                "XDG_STATE_HOME",
                "APPDATA",
                "LOCALAPPDATA")) {
            assertTrue(Path.of(child.get(variable)).startsWith(isolated.home()), variable);
        }
        for (String variable : List.of(
                "XDG_CACHE_HOME",
                "HF_HOME",
                "HF_HUB_CACHE",
                "HUGGINGFACE_HUB_CACHE",
                "HF_DATASETS_CACHE",
                "HF_ASSETS_CACHE",
                "TRANSFORMERS_CACHE",
                "PYTORCH_TRANSFORMERS_CACHE",
                "PYTORCH_PRETRAINED_BERT_CACHE",
                "SENTENCE_TRANSFORMERS_HOME",
                "TORCH_HOME",
                "PYTORCH_HOME",
                "DJL_CACHE_DIR",
                "ONNX_HOME",
                "TFHUB_CACHE_DIR")) {
            assertTrue(Path.of(child.get(variable)).startsWith(isolated.cache()), variable);
        }
        for (String variable : List.of("TMPDIR", "TMP", "TEMP")) {
            assertEquals(isolated.temporary().toString(), child.get(variable));
        }
        assertEquals(
                List.of(
                        "-Duser.home=" + isolated.home(),
                        "-Djava.io.tmpdir=" + isolated.temporary(),
                        "-Dorg.sqlite.tmpdir=" + isolated.temporary(),
                        "-Djava.util.prefs.userRoot=" + isolated.temporary().resolve("user-prefs"),
                        "-Djava.util.prefs.systemRoot=" + isolated.temporary().resolve("system-prefs")),
                isolated.jvmArguments());
        assertEquals(3, isolated.snapshot().size());
    }

    @Test
    void removesInheritedJvmInjectionProxyAndModelSettingsRegardlessOfCase() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<String, String> child = new HashMap<>();
        List<String> unsafe = List.of(
                "JAVA_TOOL_OPTIONS",
                "jdk_java_options",
                "_Java_Options",
                "CLASSPATH",
                "http_proxy",
                "HTTPS_PROXY",
                "All_Proxy",
                "no_proxy",
                "FTP_PROXY",
                "HF_ENDPOINT",
                "HF_HUB_OFFLINE",
                "HUGGINGFACE_TOKEN",
                "TRANSFORMERS_OFFLINE",
                "SENTENCE_TRANSFORMERS_CACHE",
                "TORCH_MODEL_ZOO",
                "PYTORCH_CUSTOM_CACHE",
                "DJL_ENGINE",
                "ONNX_CACHE_DIR",
                "TFHUB_MODEL_LOAD_FORMAT");
        unsafe.forEach(variable -> child.put(variable, "inherited"));

        isolated.configure(child);

        unsafe.forEach(variable -> assertFalse(child.containsKey(variable), variable));
    }

    @Test
    void validatesAllRootsBeforeCreatingDirectories() throws Exception {
        Path existing = Files.createDirectory(sandbox.resolve("tmp"));
        Files.writeString(existing.resolve("keep.txt"), "keep");

        assertThrows(IOException.class, () -> OfflineEnvironment.create(sandbox));

        assertFalse(Files.exists(sandbox.resolve("home")));
        assertFalse(Files.exists(sandbox.resolve("cache")));
        assertEquals("keep", Files.readString(existing.resolve("keep.txt")));
    }

    @Test
    void rejectsInvalidRequiredInputsBeforeAnySideEffects() throws Exception {
        assertThrows(NullPointerException.class, () -> OfflineEnvironment.create(null));
        assertThrows(IOException.class, () -> OfflineEnvironment.create(sandbox.resolve("missing")));
        Path file = Files.writeString(sandbox.resolve("file"), "existing");
        assertThrows(IOException.class, () -> OfflineEnvironment.create(file));
        try (var entries = Files.list(sandbox)) {
            assertEquals(List.of(file), entries.toList());
        }
    }

    @Test
    void refusesToReuseExistingStorage() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> before = isolated.snapshot();

        assertThrows(IOException.class, () -> OfflineEnvironment.create(sandbox));

        isolated.assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"home/.unusual/nested/weights.bin", "cache/arbitrary/tensor.data", "tmp/download/model.onnx"})
    void detectsModelsInEveryRootWithoutRelyingOnKnownCacheDirectories(String relativePath) throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> before = isolated.snapshot();
        Path model = sandbox.resolve(relativePath);
        Files.createDirectories(model.getParent());
        Files.writeString(model, "model bytes");

        AssertionError failure = assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));

        assertTrue(failure.getMessage().contains(model.getFileName().toString()));
    }

    @Test
    void detectsSameSizeContentChangesAndDeletedModels() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Path model = Files.writeString(isolated.cache().resolve("model.bin"), "first");
        Map<Path, String> before = isolated.snapshot();
        Files.writeString(model, "other");

        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));

        Files.writeString(model, "first");
        isolated.assertUnchanged(before);
        Files.delete(model);
        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
    }

    @Test
    void recordsDirectoryCreationDeletionAndDistinctRootPaths() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> empty = isolated.snapshot();
        Files.createDirectory(isolated.cache().resolve("empty-model-cache"));
        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(empty));
        Files.writeString(isolated.home().resolve("same.bin"), "home");
        Files.writeString(isolated.cache().resolve("same.bin"), "cache");
        Files.writeString(isolated.temporary().resolve("same.bin"), "tmp");
        Map<Path, String> before = isolated.snapshot();

        assertTrue(before.containsKey(Path.of("home/same.bin")));
        assertTrue(before.containsKey(Path.of("cache/same.bin")));
        assertTrue(before.containsKey(Path.of("tmp/same.bin")));
        Files.delete(isolated.cache().resolve("empty-model-cache"));
        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
    }

    @Test
    void detectsMissingStorageRoot() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> before = isolated.snapshot();
        Files.delete(isolated.home());

        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
    }

    @Test
    void recordsSymlinksWithoutFollowingExternalTargetsAndDetectsRetargeting() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Path external = Files.createDirectory(sandbox.resolve("external"));
        Files.writeString(external.resolve("outside.bin"), "outside");
        Map<Path, String> before = isolated.snapshot();
        Path link = createSymbolicLink(isolated.home().resolve("models"), external);

        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
        Map<Path, String> linked = isolated.snapshot();
        assertEquals("symlink:" + external, linked.get(Path.of("home/models")));
        assertFalse(linked.containsKey(Path.of("home/models/outside.bin")));
        Files.delete(link);
        createSymbolicLink(link, sandbox.resolve("missing"));
        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(linked));
    }

    @ParameterizedTest
    @ValueSource(strings = {"libsqlitejdbc.so", "libsqlitejdbc.dylib", "sqlitejdbc.dll"})
    void ignoresOnlyExpectedNativeSqliteFilesAndLocksInTemporaryRoot(String library) throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> before = isolated.snapshot();
        String name = "sqlite-3.53.4.0-12345678-1234-4321-abcd-123456789abc-" + library;
        Files.writeString(isolated.temporary().resolve(name), "native code");
        Files.writeString(isolated.temporary().resolve(name + ".lck"), "lock");
        Files.writeString(sandbox.resolve("class-init.log"), "test-owned log outside monitored roots");

        isolated.assertUnchanged(before);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "cache/sqlite-3.53.4.0-12345678-1234-4321-abcd-123456789abc-libsqlitejdbc.dylib",
                "tmp/nested/sqlite-3.53.4.0-12345678-1234-4321-abcd-123456789abc-libsqlitejdbc.dylib",
                "tmp/sqlite-3.53.4.0-not-a-uuid-libsqlitejdbc.so",
                "tmp/sqlite-3.53.4.0-12345678-1234-4321-abcd-123456789abc-model.onnx",
                "tmp/sqlite-model.bin"
            })
    void doesNotIgnoreFilesMerelyResemblingSqliteTemporaryArtifacts(String relativePath) throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> before = isolated.snapshot();
        Path artifact = sandbox.resolve(relativePath);
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "unexpected");

        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
    }

    @Test
    void doesNotIgnoreDirectoriesOrSymlinksNamedLikeSqliteNativeFiles() throws Exception {
        OfflineEnvironment isolated = OfflineEnvironment.create(sandbox);
        Map<Path, String> before = isolated.snapshot();
        Path artifact = Files.createDirectory(isolated.temporary().resolve(SQLITE_NATIVE));
        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
        Files.delete(artifact);
        createSymbolicLink(artifact, sandbox.resolve("missing"));

        assertThrows(AssertionError.class, () -> isolated.assertUnchanged(before));
    }

    @Test
    void recognizesJdkInitializationRecordsWithSlashOrDotNames() throws Exception {
        Path log = writeLog("""
            [0.008s][info][class,init] 0 Initializing 'java/lang/Object'(no method) (0x0000009000000e90)
            [0.125s][info][class,init] 425 Initializing 'dev/eyuppastirmaci/pecia/index/IndexService' (0x1234)
            [2026-09-19T10:00:00.000+0000][info][class,init] 426 Initializing 'org.sqlite.JDBC' (0x5678)
            """);

        assertDoesNotThrow(() -> OfflineEnvironment.assertLexicalInitialization(log, INDEX_SERVICE));
        assertTrue(OfflineEnvironment.initialized(log, "dev/eyuppastirmaci/pecia/index/IndexService"));
        assertTrue(OfflineEnvironment.initialized(log, "org/sqlite/JDBC"));
        assertFalse(OfflineEnvironment.initialized(log, "ai.onnxruntime.OrtEnvironment"));
    }

    @Test
    void ignoresClassLoadingVerificationAndOtherMentionsOfSemanticClasses() throws Exception {
        Path log = writeLog("""
            [0.125s][info][class,init] 425 Initializing 'dev/eyuppastirmaci/pecia/index/IndexService' (0x1234)
            [0.126s][info][class,load] ai.onnxruntime.OrtEnvironment source: dependency.jar
            [0.127s][info][class,init] Start class verification for: ai.onnxruntime.OrtEnvironment
            [0.128s][info][class,init] End class verification for: ai.onnxruntime.OrtEnvironment
            Initializing 'ai.onnxruntime.OrtEnvironment'
            [0.129s][info][class,load] 426 Initializing 'ai/onnxruntime/OrtEnvironment' (0x5678)
            """);

        assertDoesNotThrow(() -> OfflineEnvironment.assertLexicalInitialization(log, INDEX_SERVICE));
        assertFalse(OfflineEnvironment.initialized(log, "ai.onnxruntime.OrtEnvironment"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ai/onnxruntime/OrtEnvironment",
                "ai.djl.Model",
                "org/tensorflow/Graph",
                "org.pytorch.Module",
                "com.microsoft.onnxruntime.Session",
                "dev/eyuppastirmaci/pecia/embedding/EmbeddingService",
                "dev.eyuppastirmaci.pecia.embed.Embedder",
                "dev/eyuppastirmaci/pecia/semantic/SemanticSearch"
            })
    void rejectsSemanticInitializationEvenWhenPositiveLexicalRecordExists(String semanticClass) throws Exception {
        Path log = writeLog("[0.1s][info][class,init] 1 Initializing '" + INDEX_SERVICE + "' (0x1)\n"
                + "[0.2s][info][class,init] 2 Initializing '" + semanticClass + "' (0x2)\n");

        AssertionError failure = assertThrows(
                AssertionError.class, () -> OfflineEnvironment.assertLexicalInitialization(log, INDEX_SERVICE));

        assertTrue(failure.getMessage().contains(semanticClass.replace('/', '.')));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n", "[0.1s][info][class,load] dev.eyuppastirmaci.pecia.index.IndexService"})
    void refusesEmptyLogsOrLogsWithoutTheRequiredPositiveInitialization(String contents) throws Exception {
        Path log = writeLog(contents);

        assertThrows(AssertionError.class, () -> OfflineEnvironment.assertLexicalInitialization(log, INDEX_SERVICE));
    }

    @Test
    void rejectsMissingLogAndDoesNotConfusePrefixClassesWithRequiredClass() throws Exception {
        Path missing = sandbox.resolve("missing.log");
        assertThrows(
                AssertionError.class, () -> OfflineEnvironment.assertLexicalInitialization(missing, INDEX_SERVICE));
        assertThrows(AssertionError.class, () -> OfflineEnvironment.initialized(missing, INDEX_SERVICE));
        Path log = writeLog("[0.1s][info][class,init] 1 Initializing '" + INDEX_SERVICE + "$Helper' (0x1)\n");

        assertThrows(AssertionError.class, () -> OfflineEnvironment.assertLexicalInitialization(log, INDEX_SERVICE));
        assertFalse(OfflineEnvironment.initialized(log, INDEX_SERVICE));
    }

    private Path writeLog(String contents) throws IOException {
        return Files.writeString(sandbox.resolve("initialization.log"), contents);
    }

    private static Path createSymbolicLink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unavailable) {
            return Assumptions.abort("Symbolic links unavailable: " + unavailable.getMessage());
        }
    }
}
