package dev.eyuppastirmaci.pecia.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.JDBC;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LexicalQueryCompilerFtsTest {
    private final LexicalQueryCompiler compiler = new LexicalQueryCompiler();
    private Connection connection;

    @BeforeEach
    void createIndex() throws SQLException {
        connection = JDBC.createConnection("jdbc:sqlite::memory:", new Properties());

        try (var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE VIRTUAL TABLE query_fixture USING fts5(
                        content, headings, source_path,
                        tokenize = 'unicode61 remove_diacritics 2', detail = full, columnsize = 1
                    )
                    """);
        }
    }

    @AfterEach
    void closeIndex() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void requiresAllWordsButNotTheirOrderOrAdjacency() throws SQLException {
        insert(1, "authentication middleware");
        insert(2, "middleware handles authentication");
        insert(3, "authentication only");
        insert(4, "middleware only");
        insert(5, "unrelated");

        assertMatches("authentication middleware", 1, 2);
        assertMatches("authentication - middleware", 1, 2);
    }

    @Test
    void searchesAcrossFieldsWithoutTreatingColonsAsColumnFilters() throws SQLException {
        insert(1, "authentication", "middleware", "src/handler.java");
        insert(2, "content needle", "", "");
        insert(3, "needle", "", "");
        insert(4, "other", "content needle", "");

        assertMatches("authentication middleware", 1);
        assertMatches("content:needle", 2, 4);
    }

    @Test
    void preservesIdentifierPhraseBoundariesWithoutPromisingByteExactMatching() throws SQLException {
        insert(1, "JWT_SECRET");
        insert(2, "JWT SECRET");
        insert(3, "SECRET JWT");
        insert(4, "JWT unrelated SECRET");
        insert(5, "UserRepository.findByEmail");
        insert(6, "findByEmail UserRepository");
        insert(7, "ERR_CONNECTION_TIMEOUT");
        insert(8, "ERR CONNECTION then TIMEOUT");

        assertMatches("JWT_SECRET", 1, 2);
        assertMatches("UserRepository.findByEmail", 5);
        assertMatches("ERR_CONNECTION_TIMEOUT", 7);
    }

    @Test
    void doesNotEnablePrefixQueriesOrCamelCaseSplitting() throws SQLException {
        insert(1, "PaymentService");
        insert(2, "PaymentServiceFactory");
        insert(3, "Payment Service");

        assertMatches("PaymentService", 1);
        assertMatches("PaymentService*", 1);
        assertMatches("Payment", 3);
        assertMatches("Service", 3);
    }

    @Test
    void treatsBooleanKeywordsAsRequiredLiteralWords() throws SQLException {
        insert(1, "foo");
        insert(2, "bar");
        insert(3, "foo bar");
        insert(4, "foo OR bar");
        insert(5, "foo AND bar");
        insert(6, "foo NOT bar");

        assertMatches("foo OR bar", 4);
        assertMatches("foo AND bar", 5);
        assertMatches("foo NOT bar", 6);
    }

    @Test
    void treatsNearAndInitialTokenSyntaxAsLiteralText() throws SQLException {
        insert(1, "foo bar");
        insert(2, "NEAR foo bar");
        insert(3, "prefix foo suffix");

        assertMatches("NEAR(foo bar)", 2);
        assertMatches("^foo", 1, 2, 3);
        assertMatches("(foo)", 1, 2, 3);
    }

    @Test
    void escapesQuotesWithoutActivatingFtsOrSqlSyntax() throws SQLException {
        insert(1, "alpha");
        insert(2, "beta");
        insert(3, "alpha OR beta");
        insert(4, "O'Reilly");
        insert(5, "\"; DROP TABLE query_fixture; --");

        assertMatches("alpha\" OR \"beta", 3);
        assertMatches("O'Reilly", 4);
        assertMatches("\"; DROP TABLE query_fixture; --", 5);
        assertMatches("alpha", 1, 3);
    }

    @Test
    void keepsPunctuationInsideOnePartAsATokenizedPhrase() throws SQLException {
        insert(1, "say hello");
        insert(2, "say distant hello");
        insert(3, "hello say");

        assertMatches("say\"hello", 1);
        assertMatches("say-hello", 1);
        assertMatches("say::hello()", 1);
    }

    @Test
    void handlesPathsAsLiteralPhrases() throws SQLException {
        insert(1, "", "", "src/main/java/PaymentService.java");
        insert(2, "", "", "src/main/java/PaymentServiceFactory.java");
        insert(3, "", "", "src/test/java/PaymentService.java");

        assertMatches("src/main/java/PaymentService.java", 1);
    }

    @Test
    void delegatesUnicodeAndTurkishFoldingToTheExistingTokenizer() throws SQLException {
        insert(1, "İSTANBUL çözüm Cafe\u0301");
        insert(2, "ıstanbul çözüm café");
        insert(3, "istanbul only");
        insert(4, "başlık");

        assertMatches("istanbul\u00a0cozum\u0085CAFÉ", 1);
        assertMatches("ıstanbul çözüm cafe", 2);
        assertMatches("İSTANBUL\u202fCafe\u0301", 1);
        assertMatches("başlık", 4);
        assertMatches("baslik");
    }

    @Test
    void handlesPrivateUseAndNonDecimalNumbersInRealFts() throws SQLException {
        insert(1, "\ue000");
        insert(2, "Ⅳ");
        insert(3, "½");

        assertMatches("\ue000", 1);
        assertMatches("Ⅳ", 2);
        assertMatches("½", 3);
    }

    @Test
    void runsBoundarySizedCompiledQueries() throws SQLException {
        insert(1, "needle");
        insert(2, "x".repeat(4096));

        assertMatches(String.join(" ", Collections.nCopies(64, "needle")), 1);
        assertMatches("x".repeat(4096), 2);
    }

    @Test
    void punctuationOnlyDoesNotFallBackToMatchingEveryRow() throws SQLException {
        insert(1, "authentication middleware");

        assertMatches("!!! () \"\"");
        assertMatches("\ud83d\ude80");
        assertMatches("absent");
    }

    private void insert(long id, String content) throws SQLException {
        insert(id, content, "", "");
    }

    private void insert(long id, String content, String headings, String path) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO query_fixture(rowid, content, headings, source_path) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, id);
            statement.setString(2, content);
            statement.setString(3, headings);
            statement.setString(4, path);
            statement.executeUpdate();
        }
    }

    private void assertMatches(String query, long... expectedIds) throws SQLException {
        var expression = compiler.compile(new SearchRequest(query));
        List<Long> actual = new ArrayList<>();

        if (expression.isPresent()) {
            try (var statement = connection.prepareStatement(
                    "SELECT rowid FROM query_fixture WHERE query_fixture MATCH ? ORDER BY rowid")) {
                statement.setString(1, expression.orElseThrow());

                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        actual.add(rows.getLong(1));
                    }
                }
            }
        }

        assertEquals(Arrays.stream(expectedIds).boxed().toList(), actual, query);
    }
}
