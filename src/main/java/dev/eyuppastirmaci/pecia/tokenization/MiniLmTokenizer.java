package dev.eyuppastirmaci.pecia.tokenization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Offline uncased BERT WordPiece tokenizer pinned to all-MiniLM-L6-v2. */
public final class MiniLmTokenizer implements TokenCounter {

    private static final String UNKNOWN_TOKEN = "[UNK]";
    private static final String CLASSIFICATION_TOKEN = "[CLS]";
    private static final String SEPARATOR_TOKEN = "[SEP]";
    private static final Set<String> SPECIAL_TOKENS = Set.of("[UNK]", "[SEP]", "[PAD]", "[CLS]", "[MASK]");
    private static final String VOCABULARY_RESOURCE =
            "/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/vocab.txt";
    private static final int EXPECTED_UNKNOWN_TOKEN_ID = 100;
    private static final int EXPECTED_CLASSIFICATION_TOKEN_ID = 101;
    private static final int EXPECTED_SEPARATOR_TOKEN_ID = 102;
    private static final int MAX_WORD_CODE_POINTS = 100;

    private static final TokenizerIdentity IDENTITY = new TokenizerIdentity(
            "sentence-transformers/all-MiniLM-L6-v2",
            "1110a243fdf4706b3f48f1d95db1a4f5529b4d41",
            "bert-uncased-wordpiece-v1",
            "07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3",
            30_522,
            256,
            2
    );

    private final Map<String, Integer> vocabulary;

    private MiniLmTokenizer(Map<String, Integer> vocabulary) {
        this.vocabulary = Map.copyOf(vocabulary);
    }

    /**
     * Returns the lazily initialized tokenizer backed by the verified bundled vocabulary.
     *
     * @return the shared MiniLM-compatible tokenizer
     * @throws IllegalStateException if the bundled vocabulary is missing, corrupt, or incompatible
     */
    public static MiniLmTokenizer bundled() {

        return Holder.INSTANCE;
    }

    /**
     * Returns the pinned MiniLM tokenizer identity used for compatibility checks.
     *
     * @return the tokenizer identity
     */
    @Override
    public TokenizerIdentity identity() {

        return IDENTITY;
    }

    /**
     * Counts uncased BERT WordPiece content tokens without adding model special tokens.
     *
     * @param text exact text to tokenize, including any rendered heading or context
     * @return the number of content tokens
     * @throws NullPointerException if text is null
     */
    @Override
    public int count(String text) {

        return tokenize(text).size();
    }

    /* Applies BERT basic tokenization before greedily decomposing each token with the pinned vocabulary. */
    List<String> tokenize(String text) {
        Objects.requireNonNull(text, "text");
        List<String> pieces = new ArrayList<>();

        for (String segment : splitSpecialTokens(text)) {

            if (SPECIAL_TOKENS.contains(segment)) {
                pieces.add(segment);

                continue;
            }

            for (String token : basicTokenize(segment)) {
                wordPiece(token, pieces);
            }
        }

        return List.copyOf(pieces);
    }

    /* Normalizes whitespace, Chinese boundaries, case, accents, and punctuation as BERT BasicTokenizer does. */
    private static List<String> basicTokenize(String text) {
        StringBuilder cleaned = new StringBuilder(text.length());

        text.codePoints().forEach(codePoint -> appendCleaned(cleaned, codePoint));

        List<String> tokens = new ArrayList<>();

        for (String token : whitespaceTokens(cleaned.toString())) {
            String normalized = Normalizer.normalize(token.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
            StringBuilder accentsRemoved = new StringBuilder(normalized.length());

            normalized.codePoints()
                      .filter(codePoint -> Character.getType(codePoint) != Character.NON_SPACING_MARK)
                      .forEach(accentsRemoved::appendCodePoint);

            splitPunctuation(accentsRemoved.toString(), tokens);
        }

        return tokens;
    }

    /* Separates literal model special tokens before basic tokenization so they keep their reserved identities. */
    private static List<String> splitSpecialTokens(String text) {
        List<String> segments = new ArrayList<>();
        int cursor = 0;

        while (cursor < text.length()) {
            String nearestToken = null;
            int nearestIndex = text.length();

            for (String specialToken : SPECIAL_TOKENS) {
                int index = text.indexOf(specialToken, cursor);

                if (index >= 0 && index < nearestIndex) {
                    nearestToken = specialToken;
                    nearestIndex = index;
                }
            }

            if (nearestToken == null) {
                segments.add(text.substring(cursor));

                break;
            }

            if (nearestIndex > cursor) {
                segments.add(text.substring(cursor, nearestIndex));
            }

            segments.add(nearestToken);
            cursor = nearestIndex + nearestToken.length();
        }

        return segments;
    }

    private static void appendCleaned(StringBuilder cleaned, int codePoint) {

        if (codePoint == 0 || codePoint == 0xFFFD) {

            return;
        }

        if (isWhitespace(codePoint)) {
            cleaned.append(' ');

            return;
        }

        if (isControl(codePoint)) {

            return;
        }

        if (isChinese(codePoint)) {
            cleaned.append(' ').appendCodePoint(codePoint).append(' ');

            return;
        }

        cleaned.appendCodePoint(codePoint);
    }

    private static List<String> whitespaceTokens(String text) {
        String trimmed = text.trim();

        if (trimmed.isEmpty()) {

            return List.of();
        }

        return List.of(trimmed.split("\\s+"));
    }

    private static void splitPunctuation(String token, List<String> output) {
        StringBuilder current = new StringBuilder(token.length());

        token.codePoints().forEach(codePoint -> {

            if (isPunctuation(codePoint)) {

                if (!current.isEmpty()) {
                    output.add(current.toString());
                    current.setLength(0);
                }

                output.add(Character.toString(codePoint));
            } else {
                current.appendCodePoint(codePoint);
            }
        });

        if (!current.isEmpty()) {
            output.add(current.toString());
        }
    }

    /* Uses longest-match-first WordPiece segmentation and replaces an indivisible token with one unknown token. */
    private void wordPiece(String token, List<String> output) {
        int[] offsets = codePointOffsets(token);

        if (offsets.length - 1 > MAX_WORD_CODE_POINTS) {
            output.add(UNKNOWN_TOKEN);

            return;
        }

        List<String> pieces = new ArrayList<>();
        int start = 0;

        while (start < offsets.length - 1) {
            String matched = null;
            int matchedEnd = offsets.length - 1;

            while (start < matchedEnd) {
                String candidate = token.substring(offsets[start], offsets[matchedEnd]);

                if (start > 0) {
                    candidate = "##" + candidate;
                }

                if (vocabulary.containsKey(candidate)) {
                    matched = candidate;

                    break;
                }

                matchedEnd--;
            }

            if (matched == null) {
                output.add(UNKNOWN_TOKEN);

                return;
            }

            pieces.add(matched);
            start = matchedEnd;
        }

        output.addAll(pieces);
    }

    private static int[] codePointOffsets(String text) {
        int codePoints = text.codePointCount(0, text.length());
        int[] offsets = new int[codePoints + 1];
        int charOffset = 0;

        for (int index = 0; index < codePoints; index++) {
            offsets[index] = charOffset;
            charOffset += Character.charCount(text.codePointAt(charOffset));
        }

        offsets[codePoints] = text.length();

        return offsets;
    }

    private static boolean isWhitespace(int codePoint) {

        return codePoint == ' ' || codePoint == '\t' || codePoint == '\n' || codePoint == '\r'
                || Character.getType(codePoint) == Character.SPACE_SEPARATOR;
    }

    private static boolean isControl(int codePoint) {
        int type = Character.getType(codePoint);

        return type == Character.CONTROL || type == Character.FORMAT;
    }

    private static boolean isPunctuation(int codePoint) {

        if ((codePoint >= 33 && codePoint <= 47) || (codePoint >= 58 && codePoint <= 64)
                || (codePoint >= 91 && codePoint <= 96) || (codePoint >= 123 && codePoint <= 126)) {

            return true;
        }

        int type = Character.getType(codePoint);

        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isChinese(int codePoint) {

        return codePoint >= 0x4E00 && codePoint <= 0x9FFF
                || codePoint >= 0x3400 && codePoint <= 0x4DBF
                || codePoint >= 0x20000 && codePoint <= 0x2A6DF
                || codePoint >= 0x2A700 && codePoint <= 0x2B73F
                || codePoint >= 0x2B740 && codePoint <= 0x2B81F
                || codePoint >= 0x2B820 && codePoint <= 0x2CEAF
                || codePoint >= 0xF900 && codePoint <= 0xFAFF
                || codePoint >= 0x2F800 && codePoint <= 0x2FA1F;
    }

    /* Verifies the exact asset bytes and required token IDs before exposing the vocabulary to callers. */
    private static Map<String, Integer> loadVocabulary() {

        try (InputStream input = MiniLmTokenizer.class.getResourceAsStream(VOCABULARY_RESOURCE)) {

            if (input == null) {

                throw new IllegalStateException("Bundled tokenizer vocabulary is missing: " + VOCABULARY_RESOURCE);
            }

            byte[] bytes = input.readAllBytes();
            String actualHash = sha256(bytes);

            if (!IDENTITY.vocabularySha256().equals(actualHash)) {

                throw new IllegalStateException("Bundled tokenizer vocabulary checksum mismatch: " + actualHash);
            }

            Map<String, Integer> vocabulary = parseVocabulary(bytes);

            if (vocabulary.size() != IDENTITY.vocabularySize()) {

                throw new IllegalStateException("Bundled tokenizer vocabulary size mismatch: " + vocabulary.size());
            }

            requireTokenId(vocabulary, UNKNOWN_TOKEN, EXPECTED_UNKNOWN_TOKEN_ID);
            requireTokenId(vocabulary, CLASSIFICATION_TOKEN, EXPECTED_CLASSIFICATION_TOKEN_ID);
            requireTokenId(vocabulary, SEPARATOR_TOKEN, EXPECTED_SEPARATOR_TOKEN_ID);

            return vocabulary;
        } catch (IOException failure) {

            throw new IllegalStateException("Could not read bundled tokenizer vocabulary", failure);
        }
    }

    private static Map<String, Integer> parseVocabulary(byte[] bytes) throws IOException {
        Map<String, Integer> vocabulary = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String token;
            int tokenId = 0;

            while ((token = reader.readLine()) != null) {

                if (vocabulary.put(token, tokenId) != null) {
                    throw new IllegalStateException("Duplicate token in bundled vocabulary: " + token);
                }

                tokenId++;
            }
        }

        return vocabulary;
    }

    private static void requireTokenId(Map<String, Integer> vocabulary, String token, int expectedId) {
        Integer actualId = vocabulary.get(token);

        if (actualId == null || actualId != expectedId) {

            throw new IllegalStateException(
                    "Bundled tokenizer token ID mismatch for " + token + ": " + actualId);
        }
    }

    private static String sha256(byte[] bytes) {

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {

            throw new IllegalStateException("SHA-256 is unavailable in this Java runtime", impossible);
        }
    }

    private static final class Holder {
        private static final MiniLmTokenizer INSTANCE = new MiniLmTokenizer(loadVocabulary());

        private Holder() { }
    }
}
