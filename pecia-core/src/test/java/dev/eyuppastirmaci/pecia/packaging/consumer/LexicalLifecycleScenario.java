package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ChunkMetadata;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.FtsRow;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/** Runs the lexical index lifecycle and its reopening in separate packaged-core processes. */
final class LexicalLifecycleScenario {
    private static final Path GUIDE = Path.of("docs/guide.md");
    private static final Path CALENDAR = Path.of("docs/calendar.txt");
    private static final Path SOURCE = Path.of("src/Auth.java");
    private static final Path WEAK = Path.of("a-weak.txt");
    private static final Path STRONG = Path.of("z-strong.txt");
    private static final Path EMPTY = Path.of("empty.txt");
    private static final String ORIGINAL_GUIDE = "# Guide\n\nİstanbul café originalneedle 😀\n";
    private static final String UPDATED_GUIDE = "# Guide\n\nİstanbul café replacementneedle 😀\n";
    private static final String SOURCE_TEXT = "String JWT_SECRET;\n";
    private static final String WEAK_TEXT = "ranking filler filler filler\n";
    private static final String STRONG_TEXT = "ranking ranking ranking filler\n";
    private static final String CALENDAR_TEXT = """
        needle january one two
        needle february one two
        needle march one two
        needle april one two
        needle may one two
        needle june one two
        needle july one two
        needle august one two
        needle september one two
        needle october one two
        needle november one two
        needle december one two
        """;
    private static final List<LineRange> INITIAL_RANGES = List.of(new LineRange(1, 12));
    private static final List<LineRange> SMALL_RANGES =
            List.of(new LineRange(1, 3), new LineRange(4, 6), new LineRange(7, 9), new LineRange(10, 12));
    private static final List<LineRange> LARGE_RANGES = List.of(new LineRange(1, 6), new LineRange(7, 12));

    private final Path root;
    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final IndexService index = new IndexService(loader);
    private final QueryService query = new QueryService(loader);

    LexicalLifecycleScenario(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    void runLifecycle() throws Exception {
        try (var children = Files.list(root)) {
            check(children.findAny().isEmpty(), "The lexical fixture must start in an empty project");
        }
        Files.createDirectory(root.resolve("docs"));
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve(GUIDE), ORIGINAL_GUIDE);
        Files.writeString(root.resolve(CALENDAR), CALENDAR_TEXT);
        Files.writeString(root.resolve(SOURCE), SOURCE_TEXT);
        Files.writeString(root.resolve(WEAK), WEAK_TEXT);
        Files.writeString(root.resolve(STRONG), STRONG_TEXT);
        Files.writeString(root.resolve(EMPTY), "");
        writeConfig(64);

        checkCounts(index.index(root), 6, 6, 0, 0, 5);
        Map<Path, LexicalFile> original = checkState(64, INITIAL_RANGES, true);
        checkGuide("originalneedle", ORIGINAL_GUIDE, original.get(GUIDE));
        checkUnchanged(original);
        checkGuide("originalneedle", ORIGINAL_GUIDE, original.get(GUIDE));

        Files.writeString(root.resolve(GUIDE), UPDATED_GUIDE);
        checkCounts(index.index(root), 6, 1, 5, 0, 1);
        Map<Path, LexicalFile> edited = checkState(64, INITIAL_RANGES, true);
        checkGuide("replacementneedle", UPDATED_GUIDE, edited.get(GUIDE));
        check(search("originalneedle").isEmpty(), "Editing must remove the previous unique search term");
        check(
                original.get(GUIDE).file().id() == edited.get(GUIDE).file().id()
                        && !original.get(GUIDE)
                                .file()
                                .contentHash()
                                .equals(edited.get(GUIDE).file().contentHash())
                        && stableIds(original.get(GUIDE)).equals(stableIds(edited.get(GUIDE))),
                "Editing must refresh the hash while preserving path/profile identity");
        for (Path path : Set.of(CALENDAR, SOURCE, WEAK, STRONG, EMPTY)) {
            check(original.get(path).equals(edited.get(path)), "Editing must preserve untouched files: " + path);
        }

        Files.delete(root.resolve(GUIDE));
        checkCounts(index.index(root), 5, 0, 5, 1, 0);
        Map<Path, LexicalFile> deleted = checkState(64, INITIAL_RANGES, false);
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database(), root)) {
            check(storage.files().findByPath(GUIDE).isEmpty(), "Deletion must remove the manifest");
            check(
                    storage.chunks().findByFileId(edited.get(GUIDE).file().id()).isEmpty(),
                    "Deletion must remove every chunk belonging to the deleted file");
        }
        for (Path path : deleted.keySet()) {
            check(edited.get(path).equals(deleted.get(path)), "Deletion must preserve untouched files: " + path);
        }
        checkUnchanged(deleted);

        writeConfig(14);
        checkCounts(index.index(root), 5, 5, 0, 0, 7);
        Map<Path, LexicalFile> small = checkState(14, SMALL_RANGES, false);
        checkRechunked(deleted.get(CALENDAR), small.get(CALENDAR));
        checkUnchanged(small);

        writeConfig(26);
        checkCounts(index.index(root), 5, 5, 0, 0, 5);
        Map<Path, LexicalFile> large = checkState(26, LARGE_RANGES, false);
        checkRechunked(small.get(CALENDAR), large.get(CALENDAR));
        checkUnchanged(large);
        // The second process compares physical rows and stable IDs with this committed state.
        Files.writeString(root.resolve(".packaged-lexical-state"), signature(large));
    }

    void verifyReopened() throws Exception {
        String persisted = Files.readString(root.resolve(".packaged-lexical-state"));
        byte[] databaseBefore = Files.readAllBytes(database());
        Map<Path, LexicalFile> reopened = checkState(26, LARGE_RANGES, false);
        check(persisted.equals(signature(reopened)), "A new JVM must reopen the exact committed rows and identities");
        checkUnchanged(reopened);
        check(
                Arrays.equals(databaseBefore, Files.readAllBytes(database())),
                "Read-only queries and the unchanged pass in a new JVM must preserve the database bytes");
    }

    private void writeConfig(int maxTokens) throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), """
            [index]
            include = ['docs/*.md', 'docs/*.txt', 'src/*.java', 'a-weak.txt', 'z-strong.txt', 'empty.txt']
            [chunk]
            max_tokens = %d
            overlap_tokens = 0
            """.formatted(maxTokens));
    }

    private Map<Path, LexicalFile> checkState(int maxTokens, List<LineRange> calendarRanges, boolean guidePresent)
            throws Exception {
        Map<Path, LexicalFile> state = snapshot();
        Set<Path> paths = guidePresent
                ? Set.of(GUIDE, CALENDAR, SOURCE, WEAK, STRONG, EMPTY)
                : Set.of(CALENDAR, SOURCE, WEAK, STRONG, EMPTY);
        check(state.keySet().equals(paths), "The manifest must contain exactly the current fixture files");
        for (var entry : state.entrySet()) {
            LexicalFile file = entry.getValue();
            check(
                    file.file()
                            .contentHash()
                            .equals(ContentHash.sha256(Files.readAllBytes(root.resolve(entry.getKey())))),
                    "The manifest hash must match current raw source bytes: " + entry.getKey());
            check(
                    file.identity().maxTokens() == maxTokens && file.identity().overlapTokens() == 0,
                    "Every file must retain the current chunking profile, including empty files");
            if (!entry.getKey().equals(CALENDAR)) {
                check(file.chunks().size() == (entry.getKey().equals(EMPTY) ? 0 : 1), "Unexpected fixture chunk count");
            }
        }
        checkCalendar(state.get(CALENDAR), calendarRanges);
        checkSourceAndRanking(state);
        if (!guidePresent) {
            check(!Files.exists(root.resolve(GUIDE)), "Reopening must not restore the deleted source file");
            for (String term : List.of("originalneedle", "replacementneedle", "İstanbul café", "Guide")) {
                check(search(term).isEmpty(), "Deleted guide terms must not survive in query results: " + term);
            }
        }
        checkFtsRows(state);
        return state;
    }

    private void checkFtsRows(Map<Path, LexicalFile> state) throws Exception {
        SQLiteConfig readOnly = new SQLiteConfig();
        readOnly.setReadOnly(true);
        try (Connection connection =
                JDBC.createConnection("jdbc:sqlite:" + database().toUri().toASCIIString(), readOnly.toProperties())) {
            List<FtsRow> actual = new ArrayList<>();
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT rowid, content, headings, source_path FROM chunks_fts ORDER BY rowid")) {
                while (rows.next()) {
                    actual.add(new FtsRow(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                }
            }
            List<FtsRow> expected = state.values().stream()
                    .flatMap(file -> file.chunks().stream())
                    .sorted(Comparator.comparingLong(StoredChunk::id))
                    .map(stored -> new FtsRow(
                            stored.id(),
                            stored.chunk().content(),
                            String.join(" ", stored.chunk().metadata().headingPath()),
                            stored.chunk().sourcePath().toString().replace('\\', '/')))
                    .toList();
            check(actual.equals(expected), "Raw FTS rows must contain exactly the current chunk rows and fields");
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'needle' ORDER BY rowid")) {
                List<Long> matches = new ArrayList<>();
                while (rows.next()) {
                    matches.add(rows.getLong(1));
                }
                check(
                        matches.equals(state.get(CALENDAR).chunks().stream()
                                .map(StoredChunk::id)
                                .sorted()
                                .toList()),
                        "Direct FTS MATCH must expose only the current calendar chunk rows");
            }
        }
    }

    private void checkCalendar(LexicalFile file, List<LineRange> ranges) throws Exception {
        check(Files.readString(root.resolve(CALENDAR)).equals(CALENDAR_TEXT), "Rechunking must preserve source bytes");
        check(
                file.chunks().stream()
                        .map(chunk -> chunk.chunk().sourceLocation())
                        .toList()
                        .equals(ranges),
                "The token budget must produce the exact expected calendar boundaries");
        List<String> lines = CALENDAR_TEXT.lines().toList();
        List<SearchHit> hits = search("needle").stream()
                .sorted(Comparator.comparingInt(SearchHit::chunkIndex))
                .toList();
        check(hits.size() == ranges.size(), "Rechunking must leave only the current calendar matches");
        for (int position = 0; position < ranges.size(); position++) {
            LineRange range = ranges.get(position);
            String expected = lineSlice(lines, range.startLine() - 1, range.endLine());
            Chunk chunk = file.chunks().get(position).chunk();
            check(chunk.content().equals(expected), "Calendar chunks must contain exact source slices");
            check(
                    chunk.metadata()
                            .equals(new ChunkMetadata(
                                    List.of(),
                                    Map.of(
                                            "startOffset",
                                                    Integer.toString(lineSlice(lines, 0, range.startLine() - 1)
                                                            .length()),
                                            "endOffset",
                                                    Integer.toString(lineSlice(lines, 0, range.endLine())
                                                            .length())))),
                    "Calendar chunk offsets must follow the new boundaries");
            checkHit(hits.get(position), file, position);
            check(
                    hits.get(position).snippet().contains("needle"),
                    "The calendar preview must include the matched term");
        }
        for (int line = 0; line < lines.size(); line++) {
            String month = lines.get(line).split(" ")[1];
            List<SearchHit> matches = search(month);
            check(matches.size() == 1, "Each month must match exactly one current chunk: " + month);
            LineRange actual = (LineRange) matches.getFirst().sourceLocation();
            check(actual.startLine() <= line + 1 && actual.endLine() >= line + 1, "Month must retain its source range");
            checkHit(matches.getFirst(), file, matches.getFirst().chunkIndex());
            check(
                    matches.getFirst().snippet().contains(month),
                    "The preview must include its matching month: " + month);
        }
    }

    private void checkGuide(String term, String content, LexicalFile file) throws Exception {
        for (String expression : List.of(term, "Guide", "İstanbul café")) {
            List<SearchHit> hits = search(expression);
            check(hits.size() == 1, "Guide terms and Unicode must resolve to one current chunk: " + expression);
            SearchHit hit = hits.getFirst();
            checkHit(hit, file, 0);
            check(
                    hit.sourcePath().equals(GUIDE)
                            && hit.documentType() == DocumentType.MARKDOWN
                            && hit.sourceLocation().equals(new LineRange(1, 3))
                            && hit.metadata().headingPath().equals(List.of("Guide"))
                            && hit.snippet().equals(content),
                    "The query API must preserve guide path, type, lines, heading, Unicode, and current content");
        }
    }

    private void checkSourceAndRanking(Map<Path, LexicalFile> state) throws Exception {
        List<SearchHit> code = search("JWT_SECRET");
        check(code.size() == 1, "The source identifier must retrieve one stored chunk");
        checkHit(code.getFirst(), state.get(SOURCE), 0);
        check(
                code.getFirst().sourcePath().equals(SOURCE)
                        && code.getFirst().documentType() == DocumentType.SOURCE_CODE
                        && code.getFirst().sourceLocation().equals(new LineRange(1, 1))
                        && code.getFirst().snippet().equals(SOURCE_TEXT),
                "Source identifier queries must preserve source metadata and content");
        List<SearchHit> ranked = search("ranking");
        check(
                ranked.stream().map(SearchHit::sourcePath).toList().equals(List.of(STRONG, WEAK)),
                "BM25 frequency ranking must win over the opposite alphabetical path order");
        check(ranked.getFirst().score().value() < ranked.getLast().score().value(), "BM25 ranking must be strict");
        check(
                query.search(root, new SearchRequest("ranking", 1)).equals(List.of(ranked.getFirst())),
                "The query limit must preserve the strongest ranked match");
        for (SearchHit hit : ranked) {
            checkHit(hit, state.get(hit.sourcePath()), 0);
        }
    }

    private void checkUnchanged(Map<Path, LexicalFile> before) throws Exception {
        List<SearchHit> calendar = search("needle");
        List<SearchHit> ranked = search("ranking");
        checkCounts(index.index(root), before.size(), 0, before.size(), 0, 0);
        check(snapshot().equals(before), "Unchanged indexing must preserve manifest, profile, rows, and stable IDs");
        check(search("needle").equals(calendar), "Unchanged indexing must preserve complete calendar hits");
        check(search("ranking").equals(ranked), "Unchanged indexing must preserve scores, ordering, and hit metadata");
    }

    private Map<Path, LexicalFile> snapshot() throws Exception {
        Map<Path, LexicalFile> state = new TreeMap<>();
        try (SqliteStorage storage = SqliteStorage.openReadOnly(database(), root)) {
            for (StoredFile file : storage.files().findAll()) {
                check(
                        storage.files().findIndexingProfile(file.id()).isEmpty(),
                        "Current indexing must use full profiles");
                state.put(
                        file.sourcePath(),
                        new LexicalFile(
                                file,
                                storage.files().findChunkingIdentity(file.id()).orElseThrow(),
                                storage.chunks().findByFileId(file.id())));
            }
        }
        return state;
    }

    private List<SearchHit> search(String text) throws Exception {
        return query.search(root, new SearchRequest(text, 20));
    }

    private Path database() {
        return root.resolve(".pecia/index.db");
    }

    private static void checkCounts(
            IndexResult result, int candidates, int indexed, int unchanged, int deleted, int chunks) {
        check(
                result.status() == IndexResult.Status.COMPLETE
                        && result.issues().isEmpty()
                        && result.candidateCount() == candidates
                        && result.indexedFiles() == indexed
                        && result.unchangedFiles() == unchanged
                        && result.deletedFiles() == deleted
                        && result.writtenChunks() == chunks,
                "Unexpected packaged lifecycle indexing counters: " + result);
    }

    private static void checkHit(SearchHit hit, LexicalFile file, int position) {
        StoredChunk stored = file.chunks().get(position);
        Chunk chunk = stored.chunk();
        check(
                hit.chunkId() == stored.id()
                        && hit.stableId().isPresent()
                        && hit.stableId().equals(stored.stableId())
                        && hit.chunkIndex() == position
                        && hit.sourcePath().equals(file.file().sourcePath())
                        && hit.documentType() == file.file().documentType()
                        && hit.sourceLocation().equals(chunk.sourceLocation())
                        && hit.metadata().equals(chunk.metadata())
                        && hit.score().kind() == SearchScore.Kind.SQLITE_BM25
                        && Double.isFinite(hit.score().value()),
                "A query hit must retain current persisted content, metadata, row ID, stable ID, and finite BM25"
                        + " score: " + hit + "; stored=" + stored);
        if (chunk.sourcePath().equals(CALENDAR) && chunk.sourceLocation().equals(new LineRange(1, 12))) {
            // The initial 48-token chunk exceeds the bounded preview; later slices fit in full.
            String snippet = hit.snippet();
            check(
                    snippet.startsWith("…") || snippet.endsWith("…"),
                    "A shortened calendar preview must show truncation");
            String excerpt = snippet.replaceAll("^…|…$", "");
            check(
                    !excerpt.isBlank()
                            && chunk.content().contains(excerpt)
                            && snippet.codePointCount(0, snippet.length()) <= SearchHit.MAX_SNIPPET_CODE_POINTS,
                    "A shortened calendar preview must remain a bounded contiguous source excerpt");
        } else {
            check(
                    hit.snippet().equals(chunk.content()),
                    "Short chunk previews must preserve the complete current content");
        }
    }

    private static void checkRechunked(LexicalFile before, LexicalFile after) {
        check(before.file().equals(after.file()), "Rechunking must preserve the source hash and manifest identity");
        check(!before.identity().equals(after.identity()), "The changed token budget must invalidate the old profile");
        check(before.chunks().size() != after.chunks().size(), "The chunk count must actually change");
        check(
                stableIds(before).stream().noneMatch(stableIds(after)::contains),
                "Rechunking must replace all stable IDs from the previous profile");
    }

    private static List<ChunkId> stableIds(LexicalFile file) {
        return file.chunks().stream()
                .map(chunk -> chunk.stableId().orElseThrow())
                .toList();
    }

    private static String signature(Map<Path, LexicalFile> state) {
        StringBuilder signature = new StringBuilder();
        for (var entry : state.entrySet()) {
            LexicalFile file = entry.getValue();
            signature
                    .append(entry.getKey())
                    .append('|')
                    .append(file.file().id())
                    .append('|')
                    .append(file.file().contentHash().value())
                    .append('|')
                    .append(file.identity().fingerprint());
            for (StoredChunk chunk : file.chunks()) {
                signature
                        .append('|')
                        .append(chunk.id())
                        .append(':')
                        .append(chunk.stableId().orElseThrow().value());
            }
            signature.append('\n');
        }
        return signature.toString();
    }

    private static String lineSlice(List<String> lines, int start, int end) {
        return start == end ? "" : String.join("\n", lines.subList(start, end)) + "\n";
    }

    private record LexicalFile(StoredFile file, ChunkingIdentity identity, List<StoredChunk> chunks) {
        private LexicalFile {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(identity, "identity");
            chunks = List.copyOf(chunks);
        }
    }
}
