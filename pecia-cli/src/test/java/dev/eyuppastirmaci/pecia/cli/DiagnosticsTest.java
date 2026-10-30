package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

/** Each kind of diagnostic has its label, coloured only when the terminal shows colour. */
class DiagnosticsTest {

    private static final String ESCAPE = "\u001b[";

    @Test
    void labelsAreColouredByKind() {
        assertEquals(
                String.join(
                        "\n",
                        ESCAPE + "1m" + ESCAPE + "31mpecia test:" + ESCAPE + "0m stopped",
                        ESCAPE + "1m" + ESCAPE + "33mpecia test:" + ESCAPE + "0m left out",
                        ESCAPE + "33mwarning:" + ESCAPE + "0m careful",
                        ESCAPE + "36mnote:" + ESCAPE + "0m by the way",
                        "advice",
                        ""),
                printAll(new Terminal(true, false, 80)));
    }

    @Test
    void withoutColourTheLabelsArePlain() {
        assertEquals(
                "pecia test: stopped\npecia test: left out\nwarning: careful\nnote: by the way\nadvice\n",
                printAll(Terminal.PLAIN));
    }

    private static String printAll(Terminal terminal) {
        StringWriter err = new StringWriter();

        try (PrintWriter writer = new PrintWriter(err)) {
            Diagnostics diagnostics = new Diagnostics(writer, terminal, "pecia test");
            diagnostics.failure("stopped");
            diagnostics.partial("left out");
            diagnostics.warning("careful");
            diagnostics.note("by the way");
            diagnostics.line("advice");
        }

        return err.toString().replace("\r\n", "\n");
    }
}
