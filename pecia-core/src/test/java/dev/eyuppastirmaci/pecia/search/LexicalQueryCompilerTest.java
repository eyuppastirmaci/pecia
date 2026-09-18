package dev.eyuppastirmaci.pecia.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LexicalQueryCompilerTest {
    private final LexicalQueryCompiler compiler = new LexicalQueryCompiler();

    @Test
    void combinesWordsAsIndependentLiteralPhrases() {
        assertExpression("authentication middleware", "\"authentication\" AND \"middleware\"");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "JWT_SECRET",
                "UserRepository.findByEmail",
                "ERR_CONNECTION_TIMEOUT",
                "PaymentService",
                "src/main/java/PaymentService.java",
                "error-handler::retry()"
            })
    void preservesPunctuationInsideEachPhrase(String query) {
        assertExpression(query, "\"" + query + "\"");
    }

    @Test
    void escapesEmbeddedQuotesAndLeavesApostrophesLiteral() {
        assertExpression("say\"hello O'Reilly \"quoted\"", "\"say\"\"hello\" AND \"O'Reilly\" AND \"\"\"quoted\"\"\"");
    }

    @Test
    void doesNotExposeBooleanPrefixColumnOrNearSyntax() {
        assertExpression(
                "foo OR bar AND NOT baz* content:qux NEAR(x)",
                "\"foo\" AND \"OR\" AND \"bar\" AND \"AND\" AND \"NOT\" AND \"baz*\" AND \"content:qux\""
                        + " AND \"NEAR(x)\"");
    }

    @Test
    void sharesRequestWhitespaceRulesIncludingNonBreakingSpaces() {
        String separators = " \t\r\n\u0085\u00a0\u2003\u2007\u2028\u2029\u202f\u3000";

        for (int separator : separators.codePoints().toArray()) {
            String space = new String(Character.toChars(separator));
            assertExpression(space + "alpha" + space + space + "beta" + space, "\"alpha\" AND \"beta\"");
        }
    }

    @Test
    void preservesCaseDiacriticsAndCombiningMarksWithoutNormalization() {
        String query = "İSTANBUL ı Cafe\u0301";
        SearchRequest request = new SearchRequest(query);

        assertEquals(Optional.of("\"İSTANBUL\" AND \"ı\" AND \"Cafe\u0301\""), compiler.compile(request));
        assertEquals(query, request.query());
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "Ⅳ", "½", "\ue000", "\udb80\udc00", "\ud801\udc00", "東京"})
    void keepsLettersAllNumberCategoriesAndPrivateUseCodePoints(String query) {
        assertExpression(query, "\"" + query + "\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"!!!", "()", "\"\"", "_ . - * : + ^", "\ud83d\ude80", "\u0301", "\u200b"})
    void returnsEmptyWhenNoSearchableCharacterRemains(String query) {
        assertEquals(Optional.empty(), compiler.compile(new SearchRequest(query)));
    }

    @Test
    void removesStandalonePunctuationButNotPunctuationAttachedToText() {
        assertExpression("( foo - bar* )", "\"foo\" AND \"bar*\"");
    }

    @Test
    void acceptsExactlySixtyFourRawParts() {
        String query = String.join(" ", Collections.nCopies(64, "word"));
        String expected = String.join(" AND ", Collections.nCopies(64, "\"word\""));

        assertExpression(query, expected);
        assertEquals(
                Optional.empty(), compiler.compile(new SearchRequest(String.join(" ", Collections.nCopies(64, "!")))));
    }

    @Test
    void rejectsTheSixtyFifthPartBeforeFilteringOrDeduplication() {
        for (String part : new String[] {"word", "!"}) {
            SearchRequest request = new SearchRequest(String.join(" ", Collections.nCopies(65, part)));
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(request));
        }

        SearchRequest mixed = new SearchRequest("word " + String.join("\u00a0", Collections.nCopies(64, "!")));
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(mixed));
    }

    @Test
    void acceptsTheMaximumRequestLengthWithoutTruncation() {
        String text = "\ud801\udc00".repeat(4096);
        assertExpression(text, "\"" + text + "\"");
    }

    @Test
    void isReusableAfterFailureAndEmptyCompilation() {
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(new SearchRequest("word ".repeat(65))));
        assertEquals(Optional.empty(), compiler.compile(new SearchRequest("!!!")));
        assertExpression("next", "\"next\"");
    }

    @Test
    void rejectsNullRequest() {
        assertThrows(NullPointerException.class, () -> compiler.compile(null));
    }

    private void assertExpression(String query, String expected) {
        assertEquals(Optional.of(expected), compiler.compile(new SearchRequest(query)));
    }
}
