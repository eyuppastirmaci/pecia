package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GoldenChunkerTest {

    private static final String RESOURCE_ROOT = "/chunking/golden/";

    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void matchesReviewedChunksAndRepeatsDeterministically(String scenario) throws IOException {
        TomlTable specification = readToml(scenario + ".expected.toml");
        byte[] input = readResource(scenario + ".input");
        ContentHash hash = ContentHash.sha256(input);
        assertEquals(specification.getString("input_sha256"), hash.value(),
                "Golden input bytes changed: " + scenario);
        Document document = new Document(Path.of(specification.getString("source_path")),
                DocumentType.valueOf(specification.getString("document_type")),
                new String(input, StandardCharsets.UTF_8), hash);
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
        assertEquals(pinnedTokenizer(), tokenizer.identity(), "Golden corpus tokenizer changed");
        int maxTokens = integer(specification, "max_tokens");
        int overlapTokens = integer(specification, "overlap_tokens");
        DocumentChunker chunker = DocumentChunkerFactory.create(tokenizer, maxTokens, overlapTokens)
                                                       .getChunker(document);
        List<Chunk> expected = expectedChunks(specification.getArray("chunks"));

        assertEquals(expected, chunker.chunk(document), "Golden output changed: " + scenario);
        assertEquals(expected, chunker.chunk(document), "Reused chunker changed output: " + scenario);
        DocumentChunker fresh = DocumentChunkerFactory.create(tokenizer, maxTokens, overlapTokens)
                                                     .getChunker(document);
        assertEquals(expected, fresh.chunk(document), "Fresh chunker changed output: " + scenario);
    }

    static Stream<String> goldenCases() throws IOException {
        List<String> scenarios = strings(readToml("corpus.toml").getArray("cases"));
        assertFalse(scenarios.isEmpty(), "The golden corpus must contain scenarios");
        assertEquals(scenarios.size(), scenarios.stream().distinct().count(), "Duplicate golden scenario");

        return scenarios.stream();
    }

    private static TokenizerIdentity pinnedTokenizer() throws IOException {
        TomlTable identity = readToml("corpus.toml").getTable("tokenizer");

        return new TokenizerIdentity(identity.getString("model_id"), identity.getString("revision"),
                identity.getString("algorithm"), identity.getString("vocabulary_sha256"),
                integer(identity, "vocabulary_size"), integer(identity, "max_input_tokens"),
                integer(identity, "special_token_count"));
    }

    /* Builds complete expected records from fixed fixture values without deriving fields from chunker output. */
    private static List<Chunk> expectedChunks(TomlArray rows) {
        assertNotNull(rows, "Missing expected chunks array");
        List<Chunk> chunks = new ArrayList<>();

        for (int index = 0; index < rows.size(); index++) {
            TomlTable row = rows.getTable(index);
            TomlTable attributes = row.getTable("attributes");
            Map<String, String> metadata = new TreeMap<>();

            for (String key : attributes.keySet()) {
                metadata.put(key, attributes.getString(key));
            }

            chunks.add(new Chunk(Path.of(row.getString("source_path")),
                    DocumentType.valueOf(row.getString("document_type")), integer(row, "index"),
                    row.getString("content"), new LineRange(integer(row, "first_line"), integer(row, "last_line")),
                    new ChunkMetadata(strings(row.getArray("heading_path")), metadata)));
        }

        return List.copyOf(chunks);
    }

    private static int integer(TomlTable table, String key) {
        return Math.toIntExact(table.getLong(key));
    }

    private static List<String> strings(TomlArray values) {
        assertNotNull(values, "Missing fixture string array");

        return values.toList().stream().map(String.class::cast).toList();
    }

    private static TomlParseResult readToml(String name) throws IOException {
        TomlParseResult result = Toml.parse(new String(readResource(name), StandardCharsets.UTF_8));
        assertFalse(result.hasErrors(), () -> "Invalid fixture " + name + ": " + result.errors());

        return result;
    }

    private static byte[] readResource(String name) throws IOException {
        try (var input = GoldenChunkerTest.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            assertNotNull(input, "Missing golden resource: " + name);

            return input.readAllBytes();
        }
    }
}
