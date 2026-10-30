package dev.eyuppastirmaci.pecia.cli;

import java.io.Console;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What the output can show, decided once per command: whether it may use colour, whether it reaches an interactive
 * terminal that can redraw a line, and how wide that terminal is.
 *
 * @param width columns of the terminal, from {@code COLUMNS} when it is set, otherwise {@value #DEFAULT_WIDTH}
 */
record Terminal(boolean color, boolean interactive, int width) {

    /** The width assumed when the terminal does not say. */
    static final int DEFAULT_WIDTH = 80;

    /** Output without colour to something that is not a terminal. */
    static final Terminal PLAIN = new Terminal(false, false, DEFAULT_WIDTH);

    private static final String RESET = "\u001b[0m";

    /** Rejects a width below one column. */
    Terminal {
        if (width < 1) {
            throw new IllegalArgumentException("width must be positive: " + width);
        }
    }

    /**
     * Decides what the output can show. {@code always} and {@code never} decide colour alone; {@code auto} follows
     * {@code picocli.ansi} when it is {@code true} or {@code false}, then {@code NO_COLOR}, then {@code
     * CLICOLOR_FORCE}, and otherwise uses colour on an interactive terminal unless {@code TERM} is {@code dumb}. On
     * Windows it also needs a terminal known to show colour: Windows Terminal, ConEmu, ANSICON, or a Unix-like shell
     * that sets {@code TERM}, since the legacy console prints escape codes as text.
     */
    static Terminal detect(ColorMode mode, Environment environment) {
        Objects.requireNonNull(mode, "mode");
        boolean interactive = environment.console();
        boolean color =
                switch (mode) {
                    case ALWAYS -> true;
                    case NEVER -> false;
                    case AUTO -> automaticColor(environment, interactive);
                };

        return new Terminal(color, interactive, width(environment.variables().get("COLUMNS")));
    }

    /** Wraps text in the styles when colour is on, and returns it unchanged otherwise. */
    String paint(String text, Style... styles) {
        if (!color || styles.length == 0 || text.isEmpty()) {
            return text;
        }

        StringBuilder painted = new StringBuilder();

        for (Style style : styles) {
            painted.append("\u001b[").append(style.code).append('m');
        }

        return painted.append(text).append(RESET).toString();
    }

    private static boolean automaticColor(Environment environment, boolean interactive) {
        Map<String, String> variables = environment.variables();
        String picocli = environment.picocliAnsi();

        if ("true".equalsIgnoreCase(picocli) || "false".equalsIgnoreCase(picocli)) {
            return Boolean.parseBoolean(picocli);
        }

        // https://no-color.org: present and not empty.
        if (!variables.getOrDefault("NO_COLOR", "").isEmpty()) {
            return false;
        }

        String force = variables.getOrDefault("CLICOLOR_FORCE", "");

        if (!force.isEmpty() && !force.equals("0")) {
            return true;
        }

        if (!interactive || "dumb".equals(variables.get("TERM"))) {
            return false;
        }

        if (environment.osName().toLowerCase(Locale.ROOT).startsWith("windows")) {
            return variables.containsKey("WT_SESSION")
                    || "ON".equalsIgnoreCase(variables.get("ConEmuANSI"))
                    || variables.containsKey("ANSICON")
                    || variables.containsKey("TERM");
        }

        return true;
    }

    private static int width(String columns) {
        try {
            int width = columns == null ? DEFAULT_WIDTH : Integer.parseInt(columns.strip());
            return width > 0 ? width : DEFAULT_WIDTH;
        } catch (NumberFormatException invalid) {
            return DEFAULT_WIDTH;
        }
    }

    /** Text styles, as ANSI select-graphic-rendition codes. */
    enum Style {
        BOLD("1"),
        DIM("2"),
        RED("31"),
        GREEN("32"),
        YELLOW("33"),
        BLUE("34"),
        CYAN("36");

        private final String code;

        Style(String code) {
            this.code = code;
        }
    }

    /**
     * What detection looks at, read once from the process so tests can supply their own.
     *
     * @param variables environment variables
     * @param osName the {@code os.name} system property
     * @param picocliAnsi the {@code picocli.ansi} system property; null when unset
     * @param console whether the process runs on an interactive terminal
     */
    record Environment(Map<String, String> variables, String osName, String picocliAnsi, boolean console) {

        /** Copies the variables and requires the operating system's name. */
        Environment {
            variables = Map.copyOf(variables);
            Objects.requireNonNull(osName, "osName");
        }

        /** Reads the running process's environment. */
        static Environment system() {
            return new Environment(
                    System.getenv(),
                    System.getProperty("os.name", ""),
                    System.getProperty("picocli.ansi"),
                    onTerminal());
        }

        /**
         * Returns whether the process runs on a terminal. Java 22 and later return a console even when output is
         * redirected and say whether it is a terminal; on Java 21 a console exists only when stdin and stdout are
         * terminals.
         */
        private static boolean onTerminal() {
            Console console = System.console();

            if (console == null) {
                return false;
            }

            try {
                return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
            } catch (NoSuchMethodException javaTwentyOne) {
                return true;
            } catch (ReflectiveOperationException unexpected) {
                return false;
            }
        }
    }
}
