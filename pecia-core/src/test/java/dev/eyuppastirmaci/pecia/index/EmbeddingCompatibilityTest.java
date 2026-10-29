package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingException;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.IndexWriteObserver;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import dev.eyuppastirmaci.pecia.testing.TinyModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A changed embedding profile rebuilds embeddings from the stored chunks; the index never holds two profiles. */
class EmbeddingCompatibilityTest {

    private static final ModelOutcome TINY = TinyModel.cached();
    private static final EmbeddingProfile CURRENT = EmbeddingProfile.minilm();
    // The same model under another pinned revision, as a release that updates the model would pin it.
    private static final EmbeddingProfile NEXT = new EmbeddingProfile(
            CURRENT.modelId(),
            "next-revision",
            CURRENT.modelSha256(),
            CURRENT.dimensions(),
            CURRENT.tokenizerAlgorithm(),
            CURRENT.vocabularySha256(),
            CURRENT.maxInputTokens(),
            CURRENT.pooling(),
            CURRENT.normalization(),
            CURRENT.input());

    @TempDir
    Path sandbox;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private EmbeddingProfile profile = CURRENT;
    private int profileRequests;
    private int failOnCall = -1;
    private int embedCalls;
    private Path root;

    @BeforeEach
    void prepareProject() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        LexicalTestCorpus.write(root);
    }

    @Test
    void aModelOnlyChangeRebuildsEveryEmbeddingFromTheStoredChunks() throws Exception {
        IndexResult first = service().index(root, IndexOptions.semantic(true));
        long chunks = first.embeddedChunks();
        IndexDump before = dump(first);
        IndexWriteObserver observer = IndexWriteObserver.start(first.context().databasePath());
        profile = NEXT;

        IndexResult rebuilt = service().index(root, IndexOptions.semantic(true));

        assertEquals(IndexResult.Status.COMPLETE, rebuilt.status());
        assertEquals(chunks, rebuilt.discardedEmbeddings());
        assertEquals(chunks, rebuilt.backfilledChunks());
        assertEquals(0, rebuilt.indexedFiles());
        assertEquals(0, rebuilt.embeddedChunks());
        assertEquals(new EmbeddingCoverage(chunks, 0, 0), rebuilt.embeddingCoverage());
        assertEquals(0, observer.finish().writeCount(), "No file, chunk, identity, or profile row is written");
        assertEquals(before.withoutEmbeddings(), dump(rebuilt).withoutEmbeddings());
        assertEquals(Set.of(NEXT.fingerprint()), profilesOf(dump(rebuilt)));
        assertEquals(List.of(NEXT.fingerprint()), storedProfiles(rebuilt));
    }

    @Test
    void anInterruptedRebuildLeavesOnlyTheNewProfilesEmbeddings() throws Exception {
        Path project = Files.createDirectories(sandbox.resolve("many")).toRealPath();
        Files.writeString(project.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 6\noverlap_tokens = 0\n");
        Files.writeString(project.resolve("words.txt"), "alpha beta gamma delta ".repeat(70) + "\n");
        IndexResult first = service().index(project, IndexOptions.semantic(true));
        profile = NEXT;
        failOnCall = embedCalls + 2;

        IndexException failure =
                assertThrows(IndexException.class, () -> service().index(project, IndexOptions.semantic(true)));

        assertEquals(IndexResult.Status.EMBEDDING_FAILED, failure.result().status());
        assertEquals(70, failure.result().discardedEmbeddings());
        assertEquals(32, failure.result().backfilledChunks());
        assertNull(failure.result().embeddingCoverage());
        assertEquals(Set.of(NEXT.fingerprint()), profilesOf(dump(first)));
        assertEquals(new EmbeddingCoverage(32, 0, 38), coverage(first, NEXT));

        failOnCall = -1;
        IndexResult resumed = service().index(project, IndexOptions.semantic(true));

        assertEquals(0, resumed.discardedEmbeddings());
        assertEquals(38, resumed.backfilledChunks());
        assertEquals(new EmbeddingCoverage(70, 0, 0), resumed.embeddingCoverage());
    }

    @Test
    void anUnchangedProfileDiscardsNothing() throws Exception {
        IndexResult first = service().index(root, IndexOptions.semantic(true));

        IndexResult again = service().index(root, IndexOptions.semantic(true));

        assertEquals(0, again.discardedEmbeddings());
        assertEquals(new EmbeddingCoverage(first.embeddedChunks(), 0, 0), again.embeddingCoverage());
        assertEquals(dump(first), dump(again));
    }

    @Test
    void lexicalRunsNeitherAskForTheProfileNorDiscardItsEmbeddings() throws Exception {
        IndexResult semantic = service().index(root, IndexOptions.semantic(true));
        IndexDump before = dump(semantic);
        int requests = profileRequests;
        profile = NEXT;

        IndexResult lexical = service().index(root);

        assertEquals(requests, profileRequests);
        assertEquals(0, lexical.discardedEmbeddings());
        assertNull(lexical.embeddingCoverage());
        assertEquals(before, dump(lexical));
        // After an upgrade, a lexical run keeps the earlier profile's embeddings, which the next profile counts as
        // stale.
        assertEquals(new EmbeddingCoverage(0, semantic.embeddedChunks(), 0), coverage(lexical, NEXT));
    }

    @Test
    void coverageCountsChunksALexicalRunLeftWithoutEmbeddings() throws Exception {
        IndexResult semantic = service().index(root, IndexOptions.semantic(true));
        Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Replaced notes about refunds.\nAnd more.\n");
        service().index(root);

        EmbeddingCoverage afterLexical = coverage(semantic, CURRENT);
        IndexResult completed = service().index(root, IndexOptions.semantic(true));

        assertFalse(afterLexical.missing() == 0, afterLexical.toString());
        assertEquals(0, afterLexical.stale());
        assertEquals(afterLexical.missing(), completed.backfilledChunks());
        assertEquals(new EmbeddingCoverage(afterLexical.chunks(), 0, 0), completed.embeddingCoverage());
    }

    @Test
    void invalidCompatibilityCountersAreRejected() throws Exception {
        ProjectContext context = service().index(root).context();
        IndexResult.Status complete = IndexResult.Status.COMPLETE;

        assertEquals(
                3,
                new IndexResult(context, complete, 0, 0, 0, 0, 0, List.of(), TINY, 0, 0, 3, null)
                        .discardedEmbeddings());
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(context, complete, 0, 0, 0, 0, 0, List.of(), TINY, 0, 0, -1, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(context, complete, 0, 0, 0, 0, 0, List.of(), null, 0, 0, 1, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new IndexResult(
                        context, complete, 0, 0, 0, 0, 0, List.of(), null, 0, 0, 0, new EmbeddingCoverage(0, 0, 0)));
    }

    private IndexService service() {
        return new IndexService(loader, EmbeddingCompatibilityTest::openStorage, listener -> TINY, this::open, () -> {
            profileRequests++;
            return profile;
        });
    }

    private static SqliteStorage openStorage(ProjectContext context) throws IOException, SQLException {
        return SqliteStorage.open(context.databasePath(), context.projectRoot());
    }

    private TextEmbedder open(ModelOutcome model) throws EmbeddingException {
        TextEmbedder embedder = TextEmbedder.open(model);

        return new TextEmbedder() {
            @Override
            public float[][] embed(List<String> texts) throws EmbeddingException {
                embedCalls++;

                if (embedCalls == failOnCall) {
                    throw new EmbeddingException(EmbeddingException.Reason.INFERENCE_FAILED, "injected", null);
                }

                return embedder.embed(texts);
            }

            @Override
            public void close() {
                embedder.close();
            }
        };
    }

    private static EmbeddingCoverage coverage(IndexResult result, EmbeddingProfile profile) throws Exception {
        try (SqliteStorage storage = SqliteStorage.openReadOnly(
                result.context().databasePath(), result.context().projectRoot())) {
            return storage.embeddingCoverage(profile);
        }
    }

    private static List<String> storedProfiles(IndexResult result) throws Exception {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + result.context().databasePath());
                var rows = connection.createStatement().executeQuery("SELECT fingerprint FROM embedding_profiles")) {
            List<String> fingerprints = new ArrayList<>();

            while (rows.next()) {
                fingerprints.add(rows.getString(1));
            }

            return fingerprints;
        }
    }

    private static Set<String> profilesOf(IndexDump dump) {
        Set<String> fingerprints = new TreeSet<>();
        dump.embeddings().forEach(entry -> fingerprints.add(entry.profileFingerprint()));
        return fingerprints;
    }

    private static IndexDump dump(IndexResult result) throws Exception {
        return IndexDump.read(
                result.context().databasePath(), result.context().projectRoot(), LexicalTestCorpus.QUERIES);
    }
}
