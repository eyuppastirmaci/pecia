package dev.eyuppastirmaci.pecia.content;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentHashTest {

    @Test
    void matchesTheKnownSha256Vector() {
        ContentHash hash = ContentHash.sha256("abc".getBytes(StandardCharsets.UTF_8));

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash.value());
    }

    @Test
    void equalBytesProduceEqualHashesAndOneChangedByteDoesNot() {
        byte[] original = "content".getBytes(StandardCharsets.UTF_8);

        assertEquals(ContentHash.sha256(original), ContentHash.sha256(original.clone()));
        assertNotEquals(ContentHash.sha256(original), ContentHash.sha256("contenu".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rawBomAndLineEndingBytesAffectTheHash() {
        byte[] plain = "line one\nline two".getBytes(StandardCharsets.UTF_8);
        byte[] bom = "\uFEFFline one\nline two".getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "line one\r\nline two".getBytes(StandardCharsets.UTF_8);

        assertNotEquals(ContentHash.sha256(plain), ContentHash.sha256(bom));
        assertNotEquals(ContentHash.sha256(plain), ContentHash.sha256(crlf));
    }

    @Test
    void validatesTheCanonicalRepresentation() {
        String valid = "a".repeat(64);

        assertEquals(valid, new ContentHash(valid).value());
        assertThrows(NullPointerException.class, () -> new ContentHash(null));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("a".repeat(63)));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new ContentHash("g".repeat(64)));
    }
}
