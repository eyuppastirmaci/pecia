package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.checkSearchIdentity;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.execute;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.openDatabase;
import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.verifyArchiveOrigins;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Verifies indexing profiles and deterministic chunk identities through the packaged storage API. */
final class IdentityScenario {

    private IdentityScenario() {}

    /** Verifies persisted profiles, atomic replacement, and legacy compatibility through the packaged API. */
    static void verifyIndexingProfiles(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("profiles.db");
        Path source = Path.of("notes.txt");
        IndexingProfile originalProfile = new IndexingProfile("packaged-tokenizer-v1", 128, 16);
        IndexingProfile replacementProfile = new IndexingProfile("packaged-tokenizer-v2", 64, 8);
        Document original = new Document(
                source,
                DocumentType.PLAIN_TEXT,
                "originalneedle",
                ContentHash.sha256("originalneedle".getBytes(StandardCharsets.UTF_8)));
        Document replacement = new Document(
                source,
                DocumentType.PLAIN_TEXT,
                "replacementneedle",
                ContentHash.sha256("replacementneedle".getBytes(StandardCharsets.UTF_8)));
        List<Chunk> originalChunks = List.of(new Chunk(
                source, DocumentType.PLAIN_TEXT, 0, original.content(), new LineRange(1, 1), ChunkMetadata.empty()));
        List<Chunk> replacementChunks = List.of(new Chunk(
                source, DocumentType.PLAIN_TEXT, 0, replacement.content(), new LineRange(1, 1), ChunkMetadata.empty()));
        StoredFile originalFile;
        List<StoredChunk> persistedChunks;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            originalFile = storage.replaceFile(original, originalChunks, originalProfile);
            persistedChunks = storage.chunks().findByFileId(originalFile.id());
        }
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    storage.files()
                            .findIndexingProfile(originalFile.id())
                            .orElseThrow()
                            .equals(originalProfile),
                    "Packaged profile metadata must survive close and read-only reopen");
            check(
                    storage.chunks().findByFileId(originalFile.id()).equals(persistedChunks),
                    "Profile persistence must preserve the associated chunks");
            List<SearchHit> legacyHits = storage.lexicalSearch().search(new SearchRequest("originalneedle"));
            check(
                    legacyHits.size() == 1 && legacyHits.getFirst().stableId().isEmpty(),
                    "Legacy profile hits must remain searchable without inventing a complete chunking identity");
        }

        try (Connection connection = openDatabase(database)) {
            execute(connection, """
                CREATE TRIGGER fail_profile BEFORE INSERT ON file_indexing_profiles
                BEGIN SELECT RAISE(ABORT, 'injected profile failure'); END
                """);
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            try {
                storage.replaceFile(replacement, replacementChunks, replacementProfile);
                throw new AssertionError("A failed profile write must abort file replacement");
            } catch (SQLException failure) {
                check(
                        failure.getMessage().contains("injected profile failure"),
                        "The packaged failure must come from the profile write");
            }
            check(
                    storage.files().findByPath(source).orElseThrow().equals(originalFile),
                    "Profile failure must roll back the file manifest");
            check(
                    storage.chunks().findByFileId(originalFile.id()).equals(persistedChunks),
                    "Profile failure must roll back chunk replacement");
            check(
                    storage.files()
                            .findIndexingProfile(originalFile.id())
                            .orElseThrow()
                            .equals(originalProfile),
                    "Profile failure must retain the previous profile");
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("originalneedle"))
                                    .size()
                            == 1,
                    "Profile failure must preserve the previous FTS rows");
            check(
                    storage.lexicalSearch()
                            .search(new SearchRequest("replacementneedle"))
                            .isEmpty(),
                    "Profile failure must not publish replacement FTS rows");
            try (Connection connection = openDatabase(database)) {
                execute(connection, "DROP TRIGGER fail_profile");
            }
            StoredFile replaced = storage.replaceFile(replacement, replacementChunks, replacementProfile);
            check(replaced.id() == originalFile.id(), "Profile-aware replacement must retain the file identity");
        }

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    storage.files()
                            .findIndexingProfile(originalFile.id())
                            .orElseThrow()
                            .equals(replacementProfile),
                    "A successful retry must commit the replacement profile");
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("replacementneedle"))
                                    .size()
                            == 1,
                    "A successful retry must commit replacement FTS rows");
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            storage.replaceFile(replacement, replacementChunks);
        }
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    storage.files().findIndexingProfile(originalFile.id()).isEmpty(),
                    "Legacy replacement must clear the profile when chunk compatibility is unknown");
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("replacementneedle"))
                                    .size()
                            == 1,
                    "Legacy replacement must keep the document searchable");
        }
    }

    /** Verifies deterministic identities and atomic profile persistence through the packaged API. */
    static void verifyChunkIdentities(Path root, Path coreJar, Path runtimeDirectory) throws Exception {
        verifyArchiveOrigins(coreJar, runtimeDirectory);
        Path database = root.resolve("chunk-identities.db");
        Path source = Path.of("docs", "İstanbul.md");
        TokenizerCompatibility tokenizer =
                new TokenizerCompatibility("packaged-wordpiece-v1", "b".repeat(64), 30_522, 256, 2);
        ChunkingIdentity originalIdentity =
                new ChunkingIdentity("packaged-extraction-v1", "packaged-chunking-v1", tokenizer, 128, 16);
        ChunkingIdentity replacementIdentity =
                new ChunkingIdentity("packaged-extraction-v1", "packaged-chunking-v2", tokenizer, 64, 8);
        Document original = new Document(
                source,
                DocumentType.MARKDOWN,
                "originalneedle\nsecondneedle",
                ContentHash.sha256("originalneedle\nsecondneedle".getBytes(StandardCharsets.UTF_8)));
        List<Chunk> originalChunks = List.of(
                new Chunk(
                        source,
                        original.type(),
                        0,
                        "originalneedle",
                        new LineRange(1, 1),
                        new ChunkMetadata(List.of("Başlık"), Map.of("custom", "İ😀"))),
                new Chunk(source, original.type(), 1, "secondneedle", new LineRange(2, 2), ChunkMetadata.empty()));
        Document replacement = new Document(
                source,
                original.type(),
                "replacementneedle",
                ContentHash.sha256("replacementneedle".getBytes(StandardCharsets.UTF_8)));
        List<Chunk> replacementChunks = List.of(new Chunk(
                source, replacement.type(), 0, replacement.content(), new LineRange(1, 1), ChunkMetadata.empty()));
        StoredFile originalFile;
        List<StoredChunk> persistedChunks;

        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            originalFile = storage.replaceFile(original, originalChunks, originalIdentity);
            persistedChunks = checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks);
            List<ChunkId> originalIds = persistedChunks.stream()
                    .map(chunk -> chunk.stableId().orElseThrow())
                    .toList();
            storage.replaceFile(original, originalChunks, originalIdentity);
            persistedChunks = checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks);
            check(
                    persistedChunks.stream()
                            .map(chunk -> chunk.stableId().orElseThrow())
                            .toList()
                            .equals(originalIds),
                    "Repeated packaged replacement must preserve deterministic chunk IDs");
        }

        byte[] beforeRead = Files.readAllBytes(database);
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            check(
                    checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks)
                            .equals(persistedChunks),
                    "Full identities, chunks, and metadata must survive read-only reopen");
            checkSearchIdentity(
                    storage.lexicalSearch()
                            .search(new SearchRequest("originalneedle"))
                            .getFirst(),
                    persistedChunks.getFirst(),
                    originalIdentity);
        }
        check(Arrays.equals(beforeRead, Files.readAllBytes(database)), "Reading identities must not modify the index");

        try (Connection connection = openDatabase(database)) {
            execute(connection, """
                CREATE TRIGGER fail_chunking_profile BEFORE INSERT ON file_chunking_profiles
                BEGIN SELECT RAISE(ABORT, 'injected chunking profile failure'); END
                """);
        }
        try (SqliteStorage storage = SqliteStorage.open(database, root)) {
            try {
                storage.replaceFile(replacement, replacementChunks, replacementIdentity);
                throw new AssertionError("A failed full profile write must abort replacement");
            } catch (SQLException failure) {
                check(
                        failure.getMessage().contains("injected chunking profile failure"),
                        "The packaged failure must come from the final profile write");
            }
            check(
                    storage.files().findByPath(source).orElseThrow().equals(originalFile),
                    "Full profile failure must restore the original file manifest");
            check(
                    checkChunkIdentities(storage, originalFile.id(), originalIdentity, originalChunks)
                            .equals(persistedChunks),
                    "Full profile failure must restore chunks, metadata, profile, and deterministic IDs");
            checkSearchIdentity(
                    storage.lexicalSearch()
                            .search(new SearchRequest("originalneedle"))
                            .getFirst(),
                    persistedChunks.getFirst(),
                    originalIdentity);
            check(
                    storage.lexicalSearch()
                                    .search(new SearchRequest("originalneedle"))
                                    .size()
                            == 1,
                    "Full profile failure must preserve committed search data");
            check(
                    storage.lexicalSearch()
                            .search(new SearchRequest("replacementneedle"))
                            .isEmpty(),
                    "Full profile failure must not expose replacement search data");
            try (Connection connection = openDatabase(database)) {
                execute(connection, "DROP TRIGGER fail_chunking_profile");
            }

            StoredFile replaced = storage.replaceFile(replacement, replacementChunks, replacementIdentity);
            check(replaced.id() == originalFile.id(), "Full profile replacement must retain the file identity");
            List<StoredChunk> replacedChunks =
                    checkChunkIdentities(storage, replaced.id(), replacementIdentity, replacementChunks);
            check(
                    !replacedChunks
                            .getFirst()
                            .stableId()
                            .equals(persistedChunks.getFirst().stableId()),
                    "A changed chunking profile must produce new deterministic IDs");

            storage.replaceFile(replacement, replacementChunks);
            check(
                    storage.files().findChunkingIdentity(replaced.id()).isEmpty(),
                    "Unprofiled replacement must remove the full chunking identity");
            check(
                    storage.chunks().findByFileId(replaced.id()).stream()
                            .allMatch(chunk -> chunk.stableId().isEmpty()),
                    "Unprofiled chunks must expose unknown deterministic identities");
            List<SearchHit> unprofiledHits = storage.lexicalSearch().search(new SearchRequest("replacementneedle"));
            check(
                    unprofiledHits.size() == 1
                            && unprofiledHits.getFirst().stableId().isEmpty(),
                    "Unprofiled replacement must retain searchable content with an unknown deterministic ID");

            storage.replaceFile(replacement, replacementChunks, replacementIdentity);
            try (Connection connection = openDatabase(database)) {
                execute(connection, "UPDATE chunks SET content = 'directneedle' WHERE file_id = ?", replaced.id());
            }
            List<SearchHit> invalidatedHits = storage.lexicalSearch().search(new SearchRequest("directneedle"));
            check(
                    invalidatedHits.size() == 1
                            && invalidatedHits.getFirst().stableId().isEmpty(),
                    "Direct chunk mutation must keep refreshed search content while invalidating deterministic IDs");

            Document empty = new Document(source, original.type(), "", ContentHash.sha256(new byte[0]));
            storage.replaceFile(empty, List.of(), replacementIdentity);
            checkChunkIdentities(storage, replaced.id(), replacementIdentity, List.of());
        }
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            checkChunkIdentities(storage, originalFile.id(), replacementIdentity, List.of());
        }
    }

    private static List<StoredChunk> checkChunkIdentities(
            SqliteStorage storage, long fileId, ChunkingIdentity identity, List<Chunk> expectedChunks)
            throws SQLException {
        ChunkingIdentity persisted =
                storage.files().findChunkingIdentity(fileId).orElseThrow();
        check(
                persisted.equals(identity),
                "The complete extraction, chunking, tokenizer, and budget identity must persist");
        check(
                persisted.fingerprint().equals(identity.fingerprint()),
                "The persisted fingerprint must match its identity");
        List<StoredChunk> chunks = storage.chunks().findByFileId(fileId);
        check(
                chunks.stream().map(StoredChunk::chunk).toList().equals(expectedChunks),
                "Identity persistence must preserve all chunk content, locations, and metadata");
        ChunkIdGenerator generator = new ChunkIdGenerator(identity);
        for (StoredChunk chunk : chunks) {
            check(
                    chunk.stableId().orElseThrow().equals(generator.generate(chunk.chunk())),
                    "The packaged storage ID must match the public deterministic generator");
        }
        return chunks;
    }
}
