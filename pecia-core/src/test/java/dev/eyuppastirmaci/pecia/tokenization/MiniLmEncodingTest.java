package dev.eyuppastirmaci.pecia.tokenization;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** Compares encoding with the token ids Hugging Face's tokenizer produced for the pinned model. */
class MiniLmEncodingTest {

    private static final String REFERENCE = "/semantic/reference/minilm-token-ids.toml";

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    static Stream<ReferenceCase> referenceCases() throws IOException {
        TomlParseResult reference = reference();
        List<ReferenceCase> cases = new ArrayList<>();
        TomlArray rows = reference.getArrayOrEmpty("cases");

        for (int index = 0; index < rows.size(); index++) {
            TomlTable row = rows.getTable(index);
            TomlArray ids = row.getArrayOrEmpty("ids");
            int[] expected = new int[ids.size()];

            for (int id = 0; id < expected.length; id++) {
                expected[id] = Math.toIntExact(ids.getLong(id));
            }

            cases.add(new ReferenceCase(row.getString("name"), row.getString("text"), expected));
        }

        return cases.stream();
    }

    @Test
    void referenceDescribesThePinnedModelAndLimit() throws IOException {
        TomlParseResult reference = reference();
        TokenizerIdentity identity = tokenizer.identity();

        assertEquals(identity.modelId(), reference.getString("repository"));
        assertEquals(identity.revision(), reference.getString("revision"));
        assertEquals(identity.maxInputTokens(), reference.getLong("maxLength"));
        assertFalse(reference.getArrayOrEmpty("cases").isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    void encodingMatchesTheReferenceIds(ReferenceCase reference) {
        assertArrayEquals(reference.ids(), tokenizer.encode(reference.text()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("referenceCases")
    void encodingWrapsTheCountedTokensInSpecialTokensUntilTheLimit(ReferenceCase reference) {
        int[] ids = tokenizer.encode(reference.text());

        assertEquals(Math.min(tokenizer.count(reference.text()) + 2, 256), ids.length);
        assertEquals(101, ids[0]);
        assertEquals(102, ids[ids.length - 1]);
    }

    @Test
    void longInputIsCutToTheLimitWithTheSeparatorLast() {
        int[] ids = tokenizer.encode("word ".repeat(600));

        assertEquals(256, ids.length);
        assertEquals(102, ids[255]);
        assertEquals(tokenizer.encode("word")[1], ids[254]);
    }

    @Test
    void nullIsRejected() {
        assertThrows(NullPointerException.class, () -> tokenizer.encode(null));
    }

    private static TomlParseResult reference() throws IOException {
        try (InputStream input = MiniLmEncodingTest.class.getResourceAsStream(REFERENCE)) {
            TomlParseResult parsed = Toml.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(parsed.hasErrors(), () -> parsed.errors().toString());

            return parsed;
        }
    }

    record ReferenceCase(String name, String text, int[] ids) {
        @Override
        public String toString() {
            return name;
        }
    }
}
