package dev.eyuppastirmaci.pecia.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.testing.ChunkingArbitraries;
import dev.eyuppastirmaci.pecia.testing.ChunkingArbitraries.Budget;
import dev.eyuppastirmaci.pecia.testing.PropertySeeds;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Chunking and chunk identities must be deterministic: the same input and settings yield the same chunks and IDs,
 * IDs are unique within a document and depend only on path, position, and the chunking identity, and changing any
 * identity component changes the fingerprint and every ID.
 */
class ChunkIdentityPropertiesTest {

    private static final MiniLmTokenizer TOKENIZER = MiniLmTokenizer.bundled();
    private static final ChunkingIdentity BUILT_IN =
            ChunkingIdentity.from(PeciaConfig.defaults(), TOKENIZER.identity());

    @Property(seed = PropertySeeds.FIXED, tries = 500)
    void reusedAndFreshChunkersProduceIdenticalChunks(
            @ForAll("documents") Document document, @ForAll("budgets") Budget budget) {
        DocumentChunker reused = chunker(document, budget);
        List<Chunk> first = reused.chunk(document);

        assertEquals(first, reused.chunk(document));
        assertEquals(first, chunker(document, budget).chunk(document));
    }

    @Property(seed = PropertySeeds.FIXED, tries = 500)
    void chunkIdsAreReproducibleUniqueAndPositionBased(
            @ForAll("documents") Document document, @ForAll("budgets") Budget budget) {
        List<Chunk> chunks = chunker(document, budget).chunk(document);
        ChunkingIdentity identity = identity(budget);
        List<ChunkId> ids =
                chunks.stream().map(new ChunkIdGenerator(identity)::generate).toList();

        assertEquals(
                ids,
                chunks.stream().map(new ChunkIdGenerator(identity)::generate).toList());
        assertEquals(ids.size(), new HashSet<>(ids).size(), "Chunk IDs must be unique within a document");

        for (int index = 0; index < chunks.size(); index++) {
            assertEquals(ids.get(index), new ChunkIdGenerator(identity).generate(document.sourcePath(), index));
        }
    }

    @Property(seed = PropertySeeds.FIXED, tries = 500)
    void changingAnyIdentityComponentChangesTheFingerprintAndIds(
            @ForAll("budgets") Budget budget,
            @ForAll("budgets") Budget otherBudget,
            @ForAll("components") Component component,
            @ForAll("versionSuffixes") String suffix) {
        ChunkingIdentity original = identity(budget);
        ChunkingIdentity changed =
                switch (component) {
                    case EXTRACTION_VERSION ->
                        new ChunkingIdentity(
                                original.extractionVersion() + suffix,
                                original.chunkingVersion(),
                                original.tokenizer(),
                                original.maxTokens(),
                                original.overlapTokens());
                    case CHUNKING_VERSION ->
                        new ChunkingIdentity(
                                original.extractionVersion(),
                                original.chunkingVersion() + suffix,
                                original.tokenizer(),
                                original.maxTokens(),
                                original.overlapTokens());
                    case TOKENIZER_VOCABULARY ->
                        new ChunkingIdentity(
                                original.extractionVersion(),
                                original.chunkingVersion(),
                                otherVocabulary(original.tokenizer()),
                                original.maxTokens(),
                                original.overlapTokens());
                    case BUDGET -> {
                        Assume.that(!otherBudget.equals(budget));
                        yield identity(otherBudget);
                    }
                };
        Path path = Path.of("docs/sample.md");

        assertEquals(original.fingerprint(), identity(budget).fingerprint());
        assertNotEquals(original.fingerprint(), changed.fingerprint());
        assertNotEquals(
                new ChunkIdGenerator(original).generate(path, 0), new ChunkIdGenerator(changed).generate(path, 0));
    }

    @Provide
    Arbitrary<Document> documents() {
        return ChunkingArbitraries.documents();
    }

    @Provide
    Arbitrary<Budget> budgets() {
        return ChunkingArbitraries.budgets();
    }

    @Provide
    Arbitrary<Component> components() {
        return Arbitraries.of(Component.class);
    }

    @Provide
    Arbitrary<String> versionSuffixes() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8)
                .map(text -> "-" + text);
    }

    private static DocumentChunker chunker(Document document, Budget budget) {
        return DocumentChunkerFactory.create(TOKENIZER, budget.maxTokens(), budget.overlapTokens())
                .getChunker(document.type());
    }

    /* The built-in identity for a budget, as ChunkingIdentity.from derives it from a configuration. */
    private static ChunkingIdentity identity(Budget budget) {
        return new ChunkingIdentity(
                BUILT_IN.extractionVersion(),
                BUILT_IN.chunkingVersion(),
                BUILT_IN.tokenizer(),
                budget.maxTokens(),
                budget.overlapTokens());
    }

    private static TokenizerCompatibility otherVocabulary(TokenizerCompatibility tokenizer) {
        String digest = tokenizer.vocabularySha256();
        String other = (digest.charAt(0) == '0' ? "1" : "0") + digest.substring(1);
        return new TokenizerCompatibility(
                tokenizer.algorithm(),
                other,
                tokenizer.vocabularySize(),
                tokenizer.maxInputTokens(),
                tokenizer.specialTokenCount());
    }

    /** A chunking identity component that must affect the fingerprint. */
    enum Component {
        EXTRACTION_VERSION,
        CHUNKING_VERSION,
        TOKENIZER_VOCABULARY,
        BUDGET
    }
}
