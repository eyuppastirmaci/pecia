package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the isolated packaged-core consumer. Every class in this package is copied onto the
 * consumer classpath, so the package must contain only consumer code.
 */
public final class PackagedCoreConsumer {

    private PackagedCoreConsumer() {}

    /** Runs a lexical acceptance scenario using only the packaged engine and runtime dependencies. */
    public static void main(String[] args) throws Exception {
        if (args == null
                || args.length != 4
                || Arrays.stream(args).anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Expected scenario, project directory, core JAR, and runtime directory");
        }
        String scenario = args[0];
        if (!scenario.equals("lexical-lifecycle") && !scenario.equals("lexical-reopen")) {
            throw new IllegalArgumentException("Unknown packaged-core scenario: " + scenario);
        }
        Path root = Path.of(args[1]).toRealPath();
        Path coreJar = Path.of(args[2]).toRealPath();
        Path runtimeDirectory = Path.of(args[3]).toRealPath();
        if (!Files.isDirectory(root) || !Files.isRegularFile(coreJar) || !Files.isDirectory(runtimeDirectory)) {
            throw new IllegalArgumentException(
                    "Expected an existing project directory, core JAR, and runtime directory");
        }
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        for (String unavailable : List.of(
                "dev.eyuppastirmaci.pecia.Bootstrap",
                "picocli.CommandLine",
                "org.junit.jupiter.api.Test",
                "dev.eyuppastirmaci.pecia.testing.OfflineSandbox",
                "dev.eyuppastirmaci.pecia.testing.OfflineEnvironment",
                "dev.eyuppastirmaci.pecia.packaging.CorePackagingIT",
                "dev.eyuppastirmaci.pecia.storage.sqlite.SqliteFtsTestSupport",
                "ai.onnxruntime.OrtEnvironment",
                "ai.djl.Model")) {
            try {
                Class.forName(unavailable, false, PackagedCoreConsumer.class.getClassLoader());
                throw new AssertionError("The standalone consumer must not load " + unavailable);
            } catch (ClassNotFoundException expected) {
                // These dependencies must be absent, not merely unused by this invocation.
            }
        }
        LexicalLifecycleScenario lexical = new LexicalLifecycleScenario(root);
        if (scenario.equals("lexical-lifecycle")) {
            lexical.runLifecycle();
        } else {
            lexical.verifyReopened();
        }
        System.out.println("PACKAGED_CORE_OK " + scenario);
    }

    /** Runs {@link PipelineScenario#verify}. */
    public static void verify(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        PipelineScenario.verify(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link MigrationScenario#verifyV1Migration}. */
    public static void verifyV1Migration(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        MigrationScenario.verifyV1Migration(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link IdentityScenario#verifyIndexingProfiles}. */
    public static void verifyIndexingProfiles(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        IdentityScenario.verifyIndexingProfiles(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link IdentityScenario#verifyChunkIdentities}. */
    public static void verifyChunkIdentities(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        IdentityScenario.verifyChunkIdentities(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link IndexingScenario#verifyIncrementalChunkIdentities}. */
    public static void verifyIncrementalChunkIdentities(Path root, Path coreJar, Path runtimeDirectory)
            throws Exception {
        IndexingScenario.verifyIncrementalChunkIdentities(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link MigrationScenario#verifyV3IncrementalUpgrade}. */
    public static void verifyV3IncrementalUpgrade(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        MigrationScenario.verifyV3IncrementalUpgrade(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link IndexingScenario#verifyFolderIndex}. */
    public static void verifyFolderIndex(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        IndexingScenario.verifyFolderIndex(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link RetrievalScenario#verifyReadOnlyQuery}. */
    public static void verifyReadOnlyQuery(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        RetrievalScenario.verifyReadOnlyQuery(root, coreJar, runtimeDirectory);
    }

    /** Runs {@link RetrievalScenario#verifyLexicalSearch}. */
    public static void verifyLexicalSearch(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        RetrievalScenario.verifyLexicalSearch(root, coreJar, runtimeDirectory);
    }
}
