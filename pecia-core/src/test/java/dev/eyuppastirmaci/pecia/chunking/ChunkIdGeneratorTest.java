package dev.eyuppastirmaci.pecia.chunking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerIdentity;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkIdGeneratorTest {

    private static final String VOCABULARY_HASH = "a".repeat(64);
    private static final TokenizerIdentity TOKENIZER =
            new TokenizerIdentity("test-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2);
    private static final PeciaConfig CONFIG =
            new PeciaConfig(List.of(), List.of(), 4096, 128, 16, 1, ".pecia/index.db");
    private static final ChunkingIdentity IDENTITY = ChunkingIdentity.from(CONFIG, TOKENIZER);
    private static final Path SOURCE_PATH = Path.of("docs", "guide.md");

    private final ChunkIdGenerator generator = new ChunkIdGenerator(IDENTITY);

    @Test
    void usesAStableVersionedCanonicalEncoding() {
        ChunkId id = generator.generate(SOURCE_PATH, 7);

        // Independently generated with Python hashlib/struct: length-prefixed UTF-8 format tag
        // and slash-separated path, a big-endian int32 index, then a length-prefixed fingerprint.
        assertEquals("cca2dd030533efab7d2f3801001420765c9997115e7a90590103f5bf9130d44b", id.value());
    }

    @Test
    void encodesUnicodePathComponentsUsingUtf8ByteLengths() {
        Path path = Path.of("東京", "🧪-ı.txt");

        assertEquals(
                "594052a25b4a98fcafcbc95cc7de7fd34e2b8e0cd99c9d1e2af8da5b626ca1f7",
                generator.generate(path, 17).value());
    }

    @Test
    void acceptsTheEntireNonNegativeIndexRange() {
        assertEquals(
                "e091cced2d992353173662fc7ba6c597e78cea5937159705d2a8a4bb7ae98dfe",
                generator.generate(SOURCE_PATH, 0).value());
        assertEquals(
                "278bbe1a5134781ad089aafed2c553e2e7c112108eff2344b95c817434a0567c",
                generator.generate(SOURCE_PATH, Integer.MAX_VALUE).value());
    }

    @Test
    void generationOrderAndGeneratorInstancesDoNotChangeIds() {
        List<Path> paths = List.of(SOURCE_PATH, Path.of("src", "Main.java"), Path.of("README"));
        List<ChunkId> expected =
                paths.stream().map(path -> generator.generate(path, 3)).toList();
        ChunkIdGenerator freshGenerator = new ChunkIdGenerator(IDENTITY);

        for (int index = paths.size() - 1; index >= 0; index--) {
            assertEquals(expected.get(index), freshGenerator.generate(paths.get(index), 3));
            assertEquals(expected.get(index), generator.generate(paths.get(index), 3));
        }
    }

    @Test
    void pathsIndexesAndChunkingCompatibilityDistinguishIds() {
        ChunkId original = generator.generate(SOURCE_PATH, 7);
        TokenizerCompatibility tokenizer = TokenizerCompatibility.from(TOKENIZER);
        List<ChunkingIdentity> alternatives = List.of(
                new ChunkingIdentity("extraction-v2", IDENTITY.chunkingVersion(), tokenizer, 128, 16),
                new ChunkingIdentity(IDENTITY.extractionVersion(), "chunking-v2", tokenizer, 128, 16),
                new ChunkingIdentity(IDENTITY.extractionVersion(), IDENTITY.chunkingVersion(), tokenizer, 64, 16));

        assertNotEquals(original, generator.generate(Path.of("docs", "other.md"), 7));
        assertNotEquals(original, generator.generate(SOURCE_PATH, 8));
        for (ChunkingIdentity identity : alternatives) {
            assertNotEquals(original, new ChunkIdGenerator(identity).generate(SOURCE_PATH, 7));
        }
    }

    @Test
    void preservesCaseAndSeparatesPathComponents() {
        assertNotEquals(
                generator.generate(Path.of("docs", "guide.md"), 0), generator.generate(Path.of("docs", "Guide.md"), 0));
        assertNotEquals(
                generator.generate(Path.of("docs", "a-b.md"), 0), generator.generate(Path.of("docs-a", "b.md"), 0));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void rejectsNativePathsWhoseInvalidBytesWouldAliasAsUnicodeText() {
        Path first = Path.of(URI.create("file:///invalid-%ff.md")).getFileName();
        Path second = Path.of(URI.create("file:///invalid-%fe.md")).getFileName();

        assertNotEquals(first, second);
        assertEquals(first.toString(), second.toString());
        assertThrows(IllegalArgumentException.class, () -> generator.generate(first, 0));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(second, 0));

        Path actualReplacementCharacter = Path.of("invalid-\uFFFD.md");
        assertEquals(
                generator.generate(actualReplacementCharacter, 0),
                new ChunkIdGenerator(IDENTITY).generate(actualReplacementCharacter, 0));
    }

    @Test
    void projectLocationDoesNotAffectAnEquivalentRelativePath() {
        Path firstRoot = Path.of("first-project").toAbsolutePath();
        Path secondRoot = Path.of("elsewhere", "second-project").toAbsolutePath();
        Path firstRelative = firstRoot.relativize(firstRoot.resolve(SOURCE_PATH));
        Path secondRelative = secondRoot.relativize(secondRoot.resolve(SOURCE_PATH));

        assertEquals(generator.generate(firstRelative, 7), generator.generate(secondRelative, 7));
    }

    @Test
    void identicalContentInDifferentFilesOrPositionsHasDifferentIds() {
        Chunk original = chunk(SOURCE_PATH, 0, "same content");
        Chunk otherFile = chunk(Path.of("docs", "other.md"), 0, "same content");
        Chunk otherPosition = chunk(SOURCE_PATH, 1, "same content");

        assertNotEquals(generator.generate(original), generator.generate(otherFile));
        assertNotEquals(generator.generate(original), generator.generate(otherPosition));
    }

    @Test
    void changingContentAtTheSamePositionPreservesTheId() {
        Chunk before = chunk(SOURCE_PATH, 7, "original content");
        Chunk after = chunk(SOURCE_PATH, 7, "replacement content");

        assertEquals(generator.generate(before), generator.generate(after));
        assertEquals(generator.generate(SOURCE_PATH, 7), generator.generate(after));
    }

    @ParameterizedTest
    @EnumSource(DocumentType.class)
    void documentTypeLocationAndMetadataDoNotAffectTheId(DocumentType type) {
        Chunk chunk = new Chunk(
                SOURCE_PATH,
                type,
                7,
                "content",
                new LineRange(20, 30),
                new ChunkMetadata(List.of("Heading"), Map.of("language", "java")));

        assertEquals(generator.generate(SOURCE_PATH, 7), generator.generate(chunk));
    }

    @Test
    void embeddingModelAndRevisionChangesPreserveIds() {
        List<TokenizerIdentity> modelChanges = List.of(
                new TokenizerIdentity("other-model", "revision-1", "wordpiece", VOCABULARY_HASH, 100, 256, 2),
                new TokenizerIdentity("test-model", "revision-2", "wordpiece", VOCABULARY_HASH, 100, 256, 2));

        for (TokenizerIdentity tokenizer : modelChanges) {
            ChunkIdGenerator otherGenerator = new ChunkIdGenerator(ChunkingIdentity.from(CONFIG, tokenizer));

            assertEquals(generator.generate(SOURCE_PATH, 7), otherGenerator.generate(SOURCE_PATH, 7));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../outside.md", "docs/./guide.md", "docs/../guide.md"})
    void rejectsInvalidOrUnnormalizedRelativePaths(String sourcePath) {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(Path.of(sourcePath), 0));
    }

    @Test
    void rejectsAbsolutePathsAndFilesystemRoots() {
        Path absolutePath = SOURCE_PATH.toAbsolutePath();

        assertThrows(IllegalArgumentException.class, () -> generator.generate(absolutePath, 0));
        assertThrows(IllegalArgumentException.class, () -> generator.generate(absolutePath.getRoot(), 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsNegativeIndexes(int index) {
        assertThrows(IllegalArgumentException.class, () -> generator.generate(SOURCE_PATH, index));
    }

    @Test
    void requiresAnIdentitySourcePathAndChunk() {
        assertThrows(NullPointerException.class, () -> new ChunkIdGenerator(null));
        assertThrows(NullPointerException.class, () -> generator.generate(null, 0));
        assertThrows(NullPointerException.class, () -> generator.generate((Chunk) null));
    }

    private static Chunk chunk(Path path, int index, String content) {
        return new Chunk(path, DocumentType.MARKDOWN, index, content, new LineRange(1, 1), ChunkMetadata.empty());
    }
}
