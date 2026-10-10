package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class WarningPrinterTest {

    private static final Path SOURCE = Path.of("").toAbsolutePath().resolve(".pecia.toml");

    @Test
    void formatsTheSourceWithAndWithoutAPosition() {
        Warning located = new Warning(
                Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, new Warning.Position(2, 5), "unknown key chunk.max_token");
        Warning unlocated = new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, null, "unknown key x");

        assertEquals("warning: " + SOURCE + ":2:5: unknown key chunk.max_token", WarningPrinter.format(located));
        assertEquals("warning: " + SOURCE + ": unknown key x", WarningPrinter.format(unlocated));
    }

    @Test
    void printsEachWarningOnItsOwnLineInOrder() {
        StringWriter err = new StringWriter();
        Warning first = new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, new Warning.Position(1, 1), "first");
        Warning second = new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, new Warning.Position(3, 1), "second");

        try (PrintWriter writer = new PrintWriter(err)) {
            WarningPrinter.print(writer, List.of(first, second));
        }

        assertEquals(
                "warning: " + SOURCE + ":1:1: first" + System.lineSeparator() + "warning: " + SOURCE + ":3:1: second"
                        + System.lineSeparator(),
                err.toString());
    }
}
