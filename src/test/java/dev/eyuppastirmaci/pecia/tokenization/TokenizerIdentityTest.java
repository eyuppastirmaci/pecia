package dev.eyuppastirmaci.pecia.tokenization;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerIdentityTest {

    @Test
    void buildsAStableCompatibilityKeyFromEveryRelevantProperty() {
        TokenizerIdentity identity = MiniLmTokenizer.bundled().identity();

        assertEquals(identity.compatibilityKey(), MiniLmTokenizer.bundled().identity().compatibilityKey());
        assertTrue(identity.compatibilityKey().contains(identity.modelId()));
        assertTrue(identity.compatibilityKey().contains(identity.revision()));
        assertTrue(identity.compatibilityKey().contains(identity.algorithm()));
        assertTrue(identity.compatibilityKey().contains(identity.vocabularySha256()));
        assertTrue(identity.compatibilityKey().endsWith(":30522:256:2"));
    }

    @Test
    void rejectsIncompleteOrInvalidIdentityValues() {
        String hash = "a".repeat(64);

        assertThrows(NullPointerException.class,
                () -> new TokenizerIdentity(null, "revision", "algorithm", hash, 1, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerIdentity("model", " ", "algorithm", hash, 1, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerIdentity("model", "revision", "algorithm", "ABC", 1, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerIdentity("model", "revision", "algorithm", hash, 0, 2, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerIdentity("model", "revision", "algorithm", hash, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TokenizerIdentity("model", "revision", "algorithm", hash, 1, 2, 2));
    }
}
