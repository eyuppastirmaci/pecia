package dev.eyuppastirmaci.pecia.storage.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.JDBC;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqliteFtsTokenizerTest {
    private Connection connection;

    @BeforeEach
    void createTemporaryIndex() throws SQLException {
        assertEquals("unicode61 remove_diacritics 2", SqliteFtsSupport.TOKENIZER);
        connection = JDBC.createConnection("jdbc:sqlite::memory:", new Properties());

        try (var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE VIRTUAL TABLE temp.tokenizer_fixture USING fts5(body, tokenize = '"
                    + SqliteFtsSupport.TOKENIZER + "', detail = full, columnsize = 1)");
        }
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void foldsEnglishCaseButDoesNotStemOrMatchPartialWords() throws SQLException {
        insert(1, "PAYMENT payment");
        insert(2, "payments");
        insert(3, "repayment");

        assertMatches("payment", 1);
        assertMatches("PAYMENT", 1);
        assertMatches("payments", 2);
        assertMatches("repayment", 3);
        assertMatches("pay");
        assertMatches("ayment");
    }

    @Test
    void foldsTurkishDiacriticsWithoutTurkishSpecificDotlessICaseRules() throws SQLException {
        insert(1, "I İ i");
        insert(2, "ı");
        insert(3, "İSTANBUL ÇÖZÜM GÜNEŞ");
        insert(4, "Başlık");

        // Unicode folding maps ASCII I to i; dot removal also maps İ to i, but ı stays distinct.
        assertMatches("i", 1);
        assertMatches("I", 1);
        assertMatches("İ", 1);
        assertMatches("ı", 2);
        assertMatches("istanbul", 3);
        assertMatches("ıstanbul");
        assertMatches("cozum", 3);
        assertMatches("gunes", 3);
        assertMatches("başlık", 4);
        assertMatches("baslık", 4);
        assertMatches("baslik");
        assertMatches("BASLIK");
    }

    @Test
    void removesComposedCombiningAndMultipleLatinDiacritics() throws SQLException {
        insert(1, "Café naïve façade");
        insert(2, "Cafe\u0301");
        insert(3, "bộ");

        assertMatches("cafe", 1, 2);
        assertMatches("CAFÉ", 1, 2);
        assertMatches("Cafe\u0301", 1, 2);
        assertMatches("naive", 1);
        assertMatches("facade", 1);
        // The circumflex and dot below on ộ require remove_diacritics=2, not its default value of 1.
        assertMatches("bo", 3);
        assertMatches("bộ", 3);
        assertMatches("caf");
        assertMatches("naivety");
    }

    @Test
    void treatsPunctuationAsTokenBoundaries() throws SQLException {
        insert(1, "error-handler::retry()");
        insert(2, "error handler retry");
        insert(3, "errorhandler retry");

        assertMatches("error", 1, 2);
        assertMatches("handler", 1, 2);
        assertMatches("retry", 1, 2, 3);
        // These are authored FTS phrases, not a conversion of arbitrary user query text.
        assertMatches("\"error-handler\"", 1, 2);
        assertMatches("\"handler::retry()\"", 1, 2);
        assertMatches("errorhandler", 3);
        assertMatches("handlerretry");
        assertMatches("\"retry handler\"");
    }

    @Test
    void splitsSnakeCaseIntoAdjacentTokens() throws SQLException {
        insert(1, "load_config");
        insert(2, "load config");
        insert(3, "loadconfig");
        insert(4, "load configurations");

        assertMatches("load", 1, 2, 4);
        assertMatches("config", 1, 2);
        assertMatches("\"load_config\"", 1, 2);
        assertMatches("\"load config\"", 1, 2);
        assertMatches("loadconfig", 3);
        assertMatches("configurations", 4);
        assertMatches("conf");
        assertMatches("\"config load\"");
    }

    @Test
    void keepsCamelCaseAsOneCaseInsensitiveToken() throws SQLException {
        insert(1, "loadConfig");
        insert(2, "LOADCONFIG");
        insert(3, "load config");

        assertMatches("loadConfig", 1, 2);
        assertMatches("loadconfig", 1, 2);
        assertMatches("LOADCONFIG", 1, 2);
        assertMatches("load", 3);
        assertMatches("config", 3);
        assertMatches("\"load config\"", 3);
        assertMatches("loadcon");
    }

    @Test
    void splitsPathSeparatorsExtensionsAndFilenamePunctuation() throws SQLException {
        insert(1, "src/main/java/PaymentService.java");
        insert(2, "docs/setup-guide.md");
        insert(3, "docs/setup_guide.md");

        assertMatches("src", 1);
        assertMatches("java", 1);
        assertMatches("paymentservice", 1);
        assertMatches("\"PaymentService.java\"", 1);
        assertMatches("\"src/main/java/PaymentService.java\"", 1);
        assertMatches("docs", 2, 3);
        assertMatches("setup", 2, 3);
        assertMatches("guide", 2, 3);
        assertMatches("md", 2, 3);
        assertMatches("\"docs/setup-guide.md\"", 2, 3);
        assertMatches("payment");
        assertMatches("service");
        assertMatches("doc");
        assertMatches("\"main paymentservice\"");
    }

    private void insert(long rowId, String body) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO temp.tokenizer_fixture(rowid, body) VALUES (?, ?)")) {
            statement.setLong(1, rowId);
            statement.setString(2, body);
            statement.executeUpdate();
        }
    }

    private void assertMatches(String expression, long... expectedIds) throws SQLException {
        List<Long> actual = new ArrayList<>();

        try (var statement = connection.prepareStatement(
                "SELECT rowid FROM temp.tokenizer_fixture WHERE tokenizer_fixture MATCH ? ORDER BY rowid")) {
            statement.setString(1, expression);

            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    actual.add(rows.getLong(1));
                }
            }
        }

        assertEquals(Arrays.stream(expectedIds).boxed().toList(), actual, "FTS MATCH expression: " + expression);
    }
}
