package dev.eyuppastirmaci.pecia.storage.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.MatchRange;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lexical hits say where their snippet matches the query, as SQLite's tokenizer matched it. */
class SqliteMatchRangeTest {

    @TempDir
    Path root;

    private SqliteStorage storage;

    @BeforeEach
    void openStorage() throws Exception {
        storage = SqliteStorage.open(root.resolve("index.db"), root);
    }

    @AfterEach
    void closeStorage() throws Exception {
        storage.close();
    }

    @Test
    void everyOccurrenceIsMatchedWhateverItsCaseButNotLongerWords() throws Exception {
        insert("a.md", "Refunds are paid. A refund takes five days; REFUND requests too.");

        SearchHit hit = only("refund");

        assertEquals(List.of("refund", "REFUND"), matched(hit));
        assertEquals(List.of(new MatchRange(20, 26), new MatchRange(44, 50)), hit.matches());
    }

    @Test
    void diacriticsAndTurkishLettersFoldAsTheTokenizerFoldsThem() throws Exception {
        insert("a.md", "Café owners and cafe guests visit İstanbul, istanbul, and ISTANBUL; Şehir şehir.");

        assertEquals(List.of("Café", "cafe"), matched(only("cafe")));
        assertEquals(List.of("İstanbul", "istanbul", "ISTANBUL", "Şehir", "şehir"), matched(only("istanbul şehir")));
    }

    @Test
    void rangesCountCodePointsOutsideTheBasicPlane() throws Exception {
        insert("a.md", "😀 needle and 😀😀 needle");

        assertEquals(
                List.of(new MatchRange(2, 8), new MatchRange(16, 22)),
                only("needle").matches());
    }

    @Test
    void aPartWithPunctuationMatchesAsOnePhrase() throws Exception {
        insert("a.md", "use foo-bar or foo bar or foo_bar here, but not bar foo");

        assertEquals(List.of("foo-bar", "foo bar", "foo_bar"), matched(only("foo-bar")));
    }

    @Test
    void aSnippetCutAtBothEndsKeepsTheRangeOfItsMatch() throws Exception {
        insert("a.md", "word ".repeat(200) + "needle " + "word ".repeat(200));

        SearchHit hit = only("needle");

        assertTrue(hit.snippet().startsWith("…") && hit.snippet().endsWith("…"), hit.snippet());
        assertEquals(List.of("needle"), matched(hit));
    }

    @Test
    void aMatchPastTheSnippetBoundIsDropped() throws Exception {
        insert("a.md", "averyveryverylongtokenthatisquitelong ".repeat(40) + "needle");

        SearchHit hit = only("needle");

        assertEquals(
                SearchHit.MAX_SNIPPET_CODE_POINTS,
                hit.snippet().codePointCount(0, hit.snippet().length()));
        assertEquals(List.of(), hit.matches());
    }

    @Test
    void aMatchInTheHeadingsOrThePathLeavesTheSnippetWithoutRanges() throws Exception {
        insert("a.md", "Body text without the term.", "Needle heading");
        insert("needle/b.md", "Other body text.");

        List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest("needle"));

        assertEquals(2, hits.size());
        hits.forEach(hit -> assertEquals(List.of(), hit.matches(), hit.snippet()));
    }

    @Test
    void contentHoldingAMarkerCharacterKeepsItsSnippetWithoutRanges() throws Exception {
        insert("a.md", "text with \uFDD0 marker needle");

        SearchHit hit = only("needle");

        assertEquals("text with \uFDD0 marker needle", hit.snippet());
        assertEquals(List.of(), hit.matches());
    }

    private SearchHit only(String query) throws Exception {
        List<SearchHit> hits = storage.lexicalSearch().search(new SearchRequest(query));
        assertEquals(1, hits.size(), hits.toString());
        return hits.getFirst();
    }

    private void insert(String path, String content, String... headings) throws Exception {
        Path source = Path.of(path);
        var file = storage.files()
                .save(source, DocumentType.MARKDOWN, ContentHash.sha256(content.getBytes(StandardCharsets.UTF_8)));
        storage.chunks()
                .insert(
                        file.id(),
                        new Chunk(
                                source,
                                DocumentType.MARKDOWN,
                                0,
                                content,
                                new LineRange(1, 1),
                                new ChunkMetadata(List.of(headings), Map.of())));
    }

    /** Returns the text of each range, cut from the snippet by code points. */
    static List<String> matched(SearchHit hit) {
        int[] codePoints = hit.snippet().codePoints().toArray();

        return hit.matches().stream()
                .map(match -> new String(codePoints, match.start(), match.end() - match.start()))
                .toList();
    }
}
