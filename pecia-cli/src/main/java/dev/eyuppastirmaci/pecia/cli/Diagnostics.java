package dev.eyuppastirmaci.pecia.cli;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Prints diagnostics on stderr with a label that says what they are, coloured when the terminal shows colour: failures
 * red, partial outcomes and warnings yellow, notes cyan.
 */
final class Diagnostics {

    private final PrintWriter err;
    private final Terminal terminal;
    private final String command;

    /**
     * Creates diagnostics for one command.
     *
     * @param command the label of the command's own lines, such as {@code pecia index}
     */
    Diagnostics(PrintWriter err, Terminal terminal, String command) {
        this.err = Objects.requireNonNull(err, "err");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.command = Objects.requireNonNull(command, "command");
    }

    /** Prints why the command failed. */
    void failure(String message) {
        err.println(terminal.paint(command + ":", Terminal.Style.BOLD, Terminal.Style.RED) + " " + message);
    }

    /** Prints what the command left out although it finished, such as a partial run. */
    void partial(String message) {
        err.println(terminal.paint(command + ":", Terminal.Style.BOLD, Terminal.Style.YELLOW) + " " + message);
    }

    /** Prints a problem that did not stop the command. */
    void warning(String message) {
        err.println(terminal.paint("warning:", Terminal.Style.YELLOW) + " " + message);
    }

    /** Prints something about how the command did its work. */
    void note(String message) {
        err.println(terminal.paint("note:", Terminal.Style.CYAN) + " " + message);
    }

    /** Prints a line of advice or information without a label. */
    void line(String message) {
        err.println(message);
    }
}
