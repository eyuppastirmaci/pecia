package dev.eyuppastirmaci.pecia.tokenization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MiniLmTokenizerTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void loadsOneVerifiedSharedBundledVocabulary() {
        TokenizerIdentity identity = tokenizer.identity();

        assertSame(tokenizer, MiniLmTokenizer.bundled());
        assertEquals("sentence-transformers/all-MiniLM-L6-v2", identity.modelId());
        assertEquals("1110a243fdf4706b3f48f1d95db1a4f5529b4d41", identity.revision());
        assertEquals("bert-uncased-wordpiece-v2", identity.algorithm());
        assertEquals("07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3", identity.vocabularySha256());
        assertEquals(30_522, identity.vocabularySize());
        assertEquals(256, identity.maxInputTokens());
        assertEquals(2, identity.specialTokenCount());
    }

    @Test
    void matchesPinnedReferenceWordPieceFixtures() {
        assertEquals(List.of("hello", ",", "world", "!"), tokenizer.tokenize("Hello, world!"));
        assertEquals(List.of("una", "##ffa", "##ble"), tokenizer.tokenize("unaffable"));
        assertEquals(List.of("payment", "##val", "##ida", "##tion"), tokenizer.tokenize("paymentValidation"));
    }

    @Test
    void matchesPinnedTurkishAndMarkdownReferenceFixtures() {
        assertEquals(
                List.of("istanbul", "'", "da", "ode", "##me", "dog", "##ru", "##lam", "##a"),
                tokenizer.tokenize("İstanbul'da ödeme doğrulama"));
        assertEquals(
                List.of("#", "bas", "##l", "##ı", "##k", "ice", "##rik", "bu", "##rada", "."),
                tokenizer.tokenize("# Başlık\n\nİçerik burada."));
    }

    @Test
    void matchesPinnedUnicodeAndUnknownTokenReferenceFixtures() {
        assertEquals(List.of("[UNK]", "[UNK]", "世", "[UNK]"), tokenizer.tokenize("你好世界"));
        assertEquals(List.of("em", "##oj", "##i", "[UNK]", "test"), tokenizer.tokenize("emoji 😀 test"));
        assertEquals(List.of("[UNK]"), tokenizer.tokenize("a".repeat(101)));
    }

    @Test
    void classifiesCharactersAsHuggingFacesFastTokenizerDoes() {
        // Private-use and format characters vanish, while unassigned code points stay part of their word.
        assertEquals(List.of("ab"), tokenizer.tokenize("a\ue000b"));
        assertEquals(List.of("ab"), tokenizer.tokenize("a" + Character.toString(0xF0000) + "b"));
        assertEquals(List.of("ab"), tokenizer.tokenize("a\u200bb"));
        assertEquals(List.of("[UNK]"), tokenizer.tokenize("a\u0378b"));
        assertEquals(List.of("a", "b", "c"), tokenizer.tokenize("a\u2028b\u2029c"));
    }

    @Test
    void splitsTheCjkExtensionBlockWhereHuggingFaceStartsIt() {
        assertEquals(List.of("[UNK]"), tokenizer.tokenize("ab" + Character.toString(0x2B91F) + "cd"));
        assertEquals(List.of("ab", "[UNK]", "cd"), tokenizer.tokenize("ab" + Character.toString(0x2B920) + "cd"));
        assertEquals(List.of("ab", "[UNK]", "cd"), tokenizer.tokenize("ab" + Character.toString(0x2CEAF) + "cd"));
    }

    @Test
    void preservesSpecialTokensAndWhitespaceBoundaries() {
        assertEquals(List.of("[CLS]", "hello", "[SEP]"), tokenizer.tokenize("[CLS]hello[SEP]"));
        assertEquals(List.of("hello", "world"), tokenizer.tokenize("hello\nworld"));
        assertEquals(List.of("hello", "world"), tokenizer.tokenize("hello\tworld"));
    }

    @Test
    void distinguishesContentCountsFromCompleteModelInputCounts() {
        assertEquals(0, tokenizer.count(""));
        assertEquals(2, tokenizer.countModelInput(""));
        assertEquals(4, tokenizer.count("Hello, world!"));
        assertEquals(6, tokenizer.countModelInput("Hello, world!"));
    }

    @Test
    void countsRenderedHeadingContextAsPartOfTheExactModelInput() {
        String contentOnly = "İçerik burada.";
        String withHeadingContext = "# Başlık\n\n" + contentOnly;

        assertEquals(7, tokenizer.countModelInput(contentOnly));
        assertEquals(12, tokenizer.countModelInput(withHeadingContext));
    }

    @Test
    void enforcesThePinnedModelLimitIncludingSpecialTokens() {
        String exactlyFull = "hello ".repeat(254);
        String tooLarge = "hello ".repeat(255);

        assertEquals(256, tokenizer.countModelInput(exactlyFull));
        assertTrue(tokenizer.fitsModelInput(exactlyFull));
        assertEquals(257, tokenizer.countModelInput(tooLarge));
        assertFalse(tokenizer.fitsModelInput(tooLarge));
    }

    @Test
    void rejectsNullText() {
        assertThrows(NullPointerException.class, () -> tokenizer.count(null));
        assertThrows(NullPointerException.class, () -> tokenizer.countModelInput(null));
        assertThrows(NullPointerException.class, () -> tokenizer.fitsModelInput(null));
    }
}
