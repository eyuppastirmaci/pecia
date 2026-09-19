package dev.eyuppastirmaci.pecia.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkIdTest {

    private static final String VALUE = "0123456789abcdef".repeat(4);

    @Test
    void preservesCanonicalValuesAndUsesValueEquality() {
        ChunkId id = new ChunkId(VALUE);
        ChunkId sameId = new ChunkId(VALUE);

        assertEquals(VALUE, id.value());
        assertEquals(id, sameId);
        assertEquals(id.hashCode(), sameId.hashCode());
        assertNotEquals(id, new ChunkId("0".repeat(64)));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> new ChunkId(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0123456789abcdef", "\n"})
    void rejectsMissingOrIncompleteValues(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ChunkId(value));
    }

    @Test
    void rejectsIncorrectLengthsWithoutTrimming() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkId("a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ChunkId("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> new ChunkId(" " + VALUE));
        assertThrows(IllegalArgumentException.class, () -> new ChunkId(VALUE + "\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "F", "g", "-", " ", "é", "０", "\n", "\uD800"})
    void rejectsNonCanonicalCharacters(String character) {
        assertThrows(IllegalArgumentException.class, () -> new ChunkId("0".repeat(63) + character));
    }
}
