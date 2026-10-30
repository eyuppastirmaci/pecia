package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Parses the CLI's JSON output with a parser independent of the CLI's writer. */
public final class JsonDocuments {

    private JsonDocuments() {}

    /**
     * Parses stdout that must hold exactly one strict JSON object and a final newline.
     *
     * @throws AssertionError if the text is not exactly that
     */
    public static JsonObject parse(String stdout) {
        String text = stdout.replace("\r\n", "\n");
        assertTrue(text.endsWith("}\n"), () -> "One JSON object and a newline: " + stdout);

        return parseValue(text).getAsJsonObject();
    }

    /** Parses one strict JSON value that must take up the whole text. */
    public static JsonElement parseValue(String text) {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            assertEquals(JsonToken.END_DOCUMENT, reader.peek(), "Nothing may follow the document");
            return value;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Returns the field names of an object in document order. */
    public static List<String> fields(JsonObject object) {
        return new ArrayList<>(object.keySet());
    }
}
