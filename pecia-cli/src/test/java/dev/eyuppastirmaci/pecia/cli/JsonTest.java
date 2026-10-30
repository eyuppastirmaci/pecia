package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The JSON writer produces strict JSON that an independent parser reads back unchanged. */
class JsonTest {

    @Test
    void writesNestedValuesIndentedByTwoSpaces() {
        Json.JsonObject document = new Json.JsonObject()
                .put("text", "a")
                .put("count", 2)
                .put("total", 3L)
                .put("ok", true)
                .put("missing", null)
                .put("empty", List.of())
                .put("none", new Json.JsonObject())
                .put("items", List.of(1, new Json.JsonObject().put("b", false)));

        assertEquals("""
                {
                  "text": "a",
                  "count": 2,
                  "total": 3,
                  "ok": true,
                  "missing": null,
                  "empty": [],
                  "none": {},
                  "items": [
                    1,
                    {
                      "b": false
                    }
                  ]
                }""", Json.write(document));
    }

    @Test
    void everyCharacterSurvivesTheRoundTrip() {
        StringBuilder text = new StringBuilder("quote \" backslash \\ slash / ");

        for (char control = 0; control < 0x20; control++) {
            text.append(control);
        }

        text.append("\u007f İstanbul 😀 \u2028 \u2029 end");

        String written = Json.write(text.toString());

        assertEquals(text.toString(), JsonDocuments.parseValue(written).getAsString());
        // Raw control characters and separators never reach the output.
        assertEquals(-1, indexOfAny(written, 0x2028, 0x2029));
        assertEquals(List.of(), written.chars().filter(c -> c < 0x20).boxed().toList());
        assertEquals("\"\\u0000\\u001f\\b\\t\\n\\f\\r\"", Json.write("\u0000\u001f\b\t\n\f\r"));
    }

    @Test
    void unpairedSurrogatesAreEscapedAndPairsAreKept() {
        assertEquals("\"\\ud83d x\"", Json.write("\ud83d x"));
        assertEquals("\"x \\ude00\"", Json.write("x \ude00"));
        assertEquals("\"\\ude00\\ud83d\"", Json.write("\ude00\ud83d"));
        assertEquals("\"😀\"", Json.write("😀"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            doubles = {
                0.25,
                -1.5714285714285712E-6,
                0.03278688524590164,
                -0.0,
                Double.MIN_VALUE,
                Double.MAX_VALUE,
                1.0E21,
                123456789.0
            })
    void doublesKeepFullPrecision(double value) {
        String written = Json.write(value);

        assertEquals(
                Double.doubleToLongBits(value),
                Double.doubleToLongBits(JsonDocuments.parseValue(written).getAsDouble()));
    }

    @Test
    void valuesJsonCannotHoldAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Json.write(1.5f));
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Json.JsonObject().put("a", 1).put("a", 2));
    }

    private static int indexOfAny(String text, int... characters) {
        return Arrays.stream(characters)
                .map(text::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(-1);
    }
}
