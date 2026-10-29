package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Ctrl+C stops an indexing run cleanly: a partial summary, exit status 130, and a next run that completes. */
class IndexInterruptTest {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @TempDir
    Path root;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private final Interrupts interrupts = new Interrupts();

    @BeforeEach
    void writeProject() throws Exception {
        for (int file = 0; file < 5; file++) {
            Files.writeString(root.resolve("note" + file + ".md"), "# Note " + file + "\n\nRefund policy text.\n");
        }
    }

    @Test
    void anInterruptedRunPrintsWhatItFinishedAndExitsWith130() throws Exception {
        interrupts.interruptOnRegister = true;

        int exitCode = run(root.toString(), "--no-semantic");

        assertEquals(130, exitCode);
        assertTrue(printed().contains("candidates: 5\n"), printed());
        assertTrue(printed().contains("indexed: 0\n"), printed());
        assertEquals(
                "pecia index: cancelled; the files indexed so far are kept, and the next run continues from there\n",
                err.toString().replace("\r\n", "\n"));
        assertEquals(1, interrupts.closed);

        // The JVM exits as soon as the hook returns, so it must return only after the summary is printed, and at once
        // rather than when its ten-second limit runs out.
        interrupts.hook.join(Duration.ofSeconds(2).toMillis());
        assertFalse(interrupts.hook.isAlive());
        assertEquals(out.toString() + err, interrupts.printedWhenHookReturned);
    }

    @Test
    void theNextRunCompletesTheIndex() throws Exception {
        interrupts.interruptOnRegister = true;
        run(root.toString(), "--no-semantic");
        interrupts.interruptOnRegister = false;
        out.getBuffer().setLength(0);

        assertEquals(0, run(root.toString(), "--no-semantic"));
        assertTrue(printed().contains("indexed: 5\n"), printed());
    }

    @Test
    void aRunThatIsNotInterruptedRemovesItsHookAndNeverRunsIt() {
        assertEquals(0, run(root.toString(), "--no-semantic"));

        assertEquals(1, interrupts.registered);
        assertEquals(1, interrupts.closed);
        assertNull(interrupts.hook);
    }

    @Test
    void aFailedRunAlsoRemovesItsHook() {
        assertEquals(1, run(root.resolve("missing").toString(), "--no-semantic"));

        assertEquals(1, interrupts.registered);
        assertEquals(1, interrupts.closed);
    }

    @Test
    void aDryRunInstallsNoHook() {
        assertEquals(0, run(root.toString(), "--dry-run"));

        assertEquals(0, interrupts.registered);
    }

    /** Returns standard output with Windows line endings normalized. */
    private String printed() {
        return out.toString().replace("\r\n", "\n");
    }

    private int run(String... args) {
        IndexService service = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));
        CommandLine commandLine = new CommandLine(new IndexCommand(service, interrupts));
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));

        return commandLine.execute(args);
    }

    /** Records registrations and, when asked, runs the action at once on its own thread, as Ctrl+C would. */
    private final class Interrupts implements InterruptHandlers {

        private boolean interruptOnRegister;
        private int registered;
        private int closed;
        private Thread hook;
        private volatile String printedWhenHookReturned;

        @Override
        public Registration register(Runnable action) {
            registered++;

            if (interruptOnRegister) {
                hook = Thread.ofPlatform().daemon().start(() -> {
                    action.run();
                    printedWhenHookReturned = out.toString() + err;
                });
                awaitWaiting(hook);
            }

            return () -> closed++;
        }

        /** Waits until the action has cancelled the run and waits for its summary. */
        private static void awaitWaiting(Thread thread) {
            long deadline = System.nanoTime() + PATIENCE.toNanos();

            while (thread.getState() != Thread.State.TIMED_WAITING) {
                assertTrue(System.nanoTime() < deadline, "The hook never waited for the summary");
                Thread.onSpinWait();
            }
        }
    }
}
