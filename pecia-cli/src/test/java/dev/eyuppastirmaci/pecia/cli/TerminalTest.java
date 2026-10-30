package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

/** Colour follows {@code --color}, the conventional environment variables, and what the terminal can show. */
class TerminalTest {

    private static final String ESCAPE = "\u001b[";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            # case                                  | os       | console | picocli | variables                      | color
            a terminal                              | Mac OS X | true    |         |                                | true
            a pipe or a file                        | Mac OS X | false   |         |                                | false
            NO_COLOR                                | Linux    | true    |         | NO_COLOR=1                     | false
            an empty NO_COLOR counts as unset       | Linux    | true    |         | NO_COLOR=                      | true
            CLICOLOR_FORCE into a pipe              | Linux    | false   |         | CLICOLOR_FORCE=1               | true
            CLICOLOR_FORCE=0 changes nothing        | Linux    | false   |         | CLICOLOR_FORCE=0               | false
            NO_COLOR wins over CLICOLOR_FORCE       | Linux    | true    |         | NO_COLOR=1,CLICOLOR_FORCE=1    | false
            a dumb terminal                         | Linux    | true    |         | TERM=dumb                      | false
            picocli.ansi=false                      | Linux    | true    | false   |                                | false
            picocli.ansi=true into a pipe           | Linux    | false   | true    | NO_COLOR=1                     | true
            picocli.ansi=tty follows the terminal   | Linux    | false   | tty     |                                | false
            the legacy Windows console              | Windows 11 | true  |         |                                | false
            Windows Terminal                        | Windows 11 | true  |         | WT_SESSION=abc                 | true
            ConEmu                                  | Windows 10 | true  |         | ConEmuANSI=ON                  | true
            ConEmu without ANSI                     | Windows 10 | true  |         | ConEmuANSI=OFF                 | false
            ANSICON                                 | Windows 10 | true  |         | ANSICON=1                      | true
            Git Bash                                | Windows 10 | true  |         | TERM=xterm                     | true
            Windows Terminal into a pipe            | Windows 11 | false |         | WT_SESSION=abc                 | false
            """)
    void autoDecidesFromTheEnvironment(
            String description, String os, boolean console, String picocli, String variables, boolean color) {
        Terminal terminal = Terminal.detect(ColorMode.AUTO, environment(os, console, picocli, variables));

        assertEquals(color, terminal.color(), description);
        assertEquals(console, terminal.interactive(), description);
    }

    @Test
    void alwaysAndNeverDecideColourAlone() {
        Terminal.Environment pipeWithoutColour = environment("Linux", false, "false", "NO_COLOR=1");
        Terminal.Environment forcedTerminal = environment("Linux", true, "true", "CLICOLOR_FORCE=1");

        assertTrue(Terminal.detect(ColorMode.ALWAYS, pipeWithoutColour).color());
        assertFalse(Terminal.detect(ColorMode.ALWAYS, pipeWithoutColour).interactive());
        assertFalse(Terminal.detect(ColorMode.NEVER, forcedTerminal).color());
        assertTrue(Terminal.detect(ColorMode.NEVER, forcedTerminal).interactive());
    }

    @ParameterizedTest(name = "COLUMNS={0}")
    @CsvSource(
            nullValues = "unset",
            value = {"120, 120", "' 60 ', 60", "unset, 80", "0, 80", "-5, 80", "wide, 80"})
    void theWidthComesFromColumns(String columns, int width) {
        String variables = columns == null ? "" : "COLUMNS=" + columns;

        assertEquals(
                width,
                Terminal.detect(ColorMode.AUTO, environment("Linux", true, null, variables))
                        .width());
    }

    @Test
    void paintingAddsStylesOnlyWithColour() {
        Terminal coloured = new Terminal(true, true, 80);

        assertEquals(
                ESCAPE + "1m" + ESCAPE + "31mfailed" + ESCAPE + "0m",
                coloured.paint("failed", Terminal.Style.BOLD, Terminal.Style.RED));
        assertEquals("plain", coloured.paint("plain"));
        assertEquals("", coloured.paint("", Terminal.Style.BOLD));
        assertEquals("failed", Terminal.PLAIN.paint("failed", Terminal.Style.BOLD, Terminal.Style.RED));
        assertThrows(IllegalArgumentException.class, () -> new Terminal(false, false, 0));
    }

    @Test
    void theEnvironmentKeepsItsOwnCopyOfTheVariables() {
        Map<String, String> variables = new HashMap<>(Map.of("NO_COLOR", "1"));
        Terminal.Environment environment = new Terminal.Environment(variables, "Linux", null, true);
        variables.clear();

        assertFalse(Terminal.detect(ColorMode.AUTO, environment).color());
        assertThrows(NullPointerException.class, () -> new Terminal.Environment(Map.of(), null, null, true));
    }

    @Test
    void modesHaveLowerCaseSpellingsAndRejectOthers() {
        ColorMode.Names names = new ColorMode.Names();

        assertEquals(List.of("auto", "always", "never"), List.copyOf(iterable(names)));
        assertEquals(ColorMode.ALWAYS, names.convert("always"));
        CommandLine.TypeConversionException invalid =
                assertThrows(CommandLine.TypeConversionException.class, () -> names.convert("ALWAYS"));
        assertEquals("expected one of auto, always, never but was 'ALWAYS'", invalid.getMessage());
    }

    private static List<String> iterable(Iterable<String> values) {
        List<String> list = new ArrayList<>();
        values.forEach(list::add);
        return list;
    }

    /** Builds an environment from comma-separated {@code NAME=value} pairs. */
    static Terminal.Environment environment(String os, boolean console, String picocli, String variables) {
        Map<String, String> parsed = new HashMap<>();

        if (variables != null && !variables.isBlank()) {
            for (String pair : variables.split(",")) {
                int equals = pair.indexOf('=');
                parsed.put(pair.substring(0, equals).strip(), pair.substring(equals + 1));
            }
        }

        return new Terminal.Environment(parsed, os, picocli, console);
    }
}
