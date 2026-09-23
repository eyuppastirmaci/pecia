package dev.eyuppastirmaci.pecia.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OfflineSandboxTest {

    private static final String MANAGER_WARNING = "WARNING: A command line option has enabled the Security Manager\n";
    private static final String REMOVAL_WARNING =
            "WARNING: The Security Manager is deprecated and will be removed in a future release\n";
    private static final String STARTUP_WARNINGS = MANAGER_WARNING + REMOVAL_WARNING;
    private static final String GUARD_PACKAGE = "dev/eyuppastirmaci/pecia/testing/";

    @TempDir
    Path sandbox;

    @Test
    void guardJarContainsOnlyStandaloneNetworkGuardAndProbeClasses() throws Exception {
        OfflineSandbox offline = OfflineSandbox.create(sandbox);

        try (JarFile jar = new JarFile(offline.guardJar().toFile())) {
            Set<String> entries = jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(entry -> entry.getName())
                    .collect(Collectors.toSet());
            assertEquals(
                    Set.of(
                            GUARD_PACKAGE + "NetworkGuard.class",
                            GUARD_PACKAGE + "NetworkGuard$NetworkDeniedException.class",
                            GUARD_PACKAGE + "NetworkProbe.class"),
                    entries.stream().filter(name -> name.endsWith(".class")).collect(Collectors.toSet()));
            assertTrue(
                    entries.stream().allMatch(name -> name.endsWith(".class") || name.equals("META-INF/MANIFEST.MF")));
            assertFalse(entries.stream()
                    .anyMatch(name -> name.contains("OfflineSandbox") || name.startsWith("org/junit/")));
        }
    }

    @Test
    void createRejectsMissingRootWithoutCreatingIt() {
        Path missing = sandbox.resolve("missing");

        assertThrows(IOException.class, () -> OfflineSandbox.create(missing));

        assertFalse(Files.exists(missing));
    }

    @Test
    void applicationStderrRemovesOnlyTheExpectedStartupWarnings() {
        String applicationOutput = "application diagnostic\nWARNING: application warning\n";

        assertEquals(applicationOutput, OfflineSandbox.applicationStderr(STARTUP_WARNINGS + applicationOutput));
    }

    @Test
    void applicationStderrNormalizesWindowsLineEndingsAndKeepsOtherOutput() {
        String raw = ("before startup\n" + STARTUP_WARNINGS + "after startup\n").replace("\n", "\r\n");

        assertEquals("before startup\nafter startup\n", OfflineSandbox.applicationStderr(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ordinary diagnostic\n", "WARNING: Security Manager enabled\n"})
    void applicationStderrRejectsMissingOrUnrecognizedStartupWarnings(String raw) {
        assertThrows(AssertionError.class, () -> OfflineSandbox.applicationStderr(raw));
    }

    @Test
    void applicationStderrRequiresBothWarnings() {
        assertThrows(AssertionError.class, () -> OfflineSandbox.applicationStderr(MANAGER_WARNING));
        assertThrows(AssertionError.class, () -> OfflineSandbox.applicationStderr(REMOVAL_WARNING));
    }

    @Test
    void applicationStderrRejectsDuplicateWarnings() {
        assertThrows(AssertionError.class, () -> OfflineSandbox.applicationStderr(STARTUP_WARNINGS + MANAGER_WARNING));
        assertThrows(AssertionError.class, () -> OfflineSandbox.applicationStderr(STARTUP_WARNINGS + REMOVAL_WARNING));
    }

    @Test
    void noNetworkAssertionAcceptsAnActivatedGuardWithoutAttempts() throws Exception {
        OfflineSandbox.Run run = OfflineSandbox.create(sandbox).newRun();
        Files.writeString(run.auditLog(), "READY\n");

        run.assertNoNetworkAttempts();
    }

    @Test
    void noNetworkAssertionRejectsMissingAuditInsteadOfAssumingNoAttempts() throws Exception {
        OfflineSandbox.Run run = OfflineSandbox.create(sandbox).newRun();
        Files.deleteIfExists(run.auditLog());

        assertThrows(AssertionError.class, run::assertNoNetworkAttempts);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "DENY connect 127.0.0.1:9\n",
                "READY\nDENY connect 127.0.0.1:9\n",
                "READY\nREADY\n",
                "READY\n\n"
            })
    void noNetworkAssertionRejectsEmptyOrUnexpectedAuditEvents(String audit) throws Exception {
        OfflineSandbox.Run run = OfflineSandbox.create(sandbox).newRun();
        Files.writeString(run.auditLog(), audit);

        assertThrows(AssertionError.class, run::assertNoNetworkAttempts);
    }

    @Test
    void controlProcessesProveRealNetworkDenial() throws Exception {
        OfflineSandbox offline = OfflineSandbox.create(sandbox);

        offline.verifyNetworkDenied();
    }

    @ParameterizedTest
    @CsvSource({
        "1, java.net.ConnectException: Connection refused",
        "124, Network control timed out",
        "1, java.lang.SecurityException: Access denied"
    })
    void networkControlRejectsOrdinaryConnectionFailuresAndUnrelatedSecurityRestrictions(
            int exitCode, String diagnostic) {
        assertThrows(
                AssertionError.class,
                () -> OfflineSandbox.validateNetworkControl(
                        "connect", exitCode, "", STARTUP_WARNINGS + diagnostic + "\n", List.of("READY")));
    }

    @Test
    void networkControlRejectsSuccessMarkerAccompaniedByApplicationErrors() {
        assertThrows(
                AssertionError.class,
                () -> OfflineSandbox.validateNetworkControl(
                        "connect",
                        0,
                        "NETWORK_DENIED connect\n",
                        STARTUP_WARNINGS + "java.net.ConnectException: Connection refused\n",
                        List.of("READY", "DENY CONNECT 127.0.0.1:9")));
    }

    @Test
    void networkControlRequiresAnActualRecordedDenialAlongsideSuccessOutput() {
        assertThrows(
                AssertionError.class,
                () -> OfflineSandbox.validateNetworkControl(
                        "connect", 0, "NETWORK_DENIED connect\n", STARTUP_WARNINGS, List.of("READY")));
    }

    @Test
    void networkControlAcceptsMatchingSuccessfulProbeAndGuardAudit() {
        OfflineSandbox.validateNetworkControl(
                "connect",
                0,
                "NETWORK_DENIED connect\n",
                STARTUP_WARNINGS,
                List.of("READY", "DENY CONNECT 127.0.0.1:9"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect", "dns", "listen"})
    void probeRejectsAnUnguardedJvmBeforeTryingNetworkAccess(String mode) {
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> NetworkProbe.main(new String[] {mode}));

        assertEquals("The acceptance-test network guard is not installed", failure.getMessage());
    }

    @Test
    void lexicalVerificationCombinesActiveGuardClassInitializationAndUnchangedCache() throws Exception {
        OfflineSandbox.Run run = OfflineSandbox.create(sandbox).newRun();
        Files.writeString(run.auditLog(), "READY\n");
        Files.writeString(
                run.initializationLog(),
                "[0.123s][info][class,init] 1 Initializing 'dev/eyuppastirmaci/pecia/Bootstrap' (0x00000000)\n"
                        + "[0.124s][info][class,init] 2 Initializing 'dev/eyuppastirmaci/pecia/testing/NetworkGuard'\n");

        run.verifyLexical("dev/eyuppastirmaci/pecia/Bootstrap");
    }
}
