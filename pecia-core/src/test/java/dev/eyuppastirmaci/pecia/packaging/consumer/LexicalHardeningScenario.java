package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/**
 * Runs the shared lexical corpus through the packaged core: canonical dumps for comparing indexes built by separate
 * processes, and an incremental lifecycle covering write-free reruns and case-only renames.
 */
final class LexicalHardeningScenario {

    private static final int CORPUS_SIZE = LexicalTestCorpus.files().size();
    private static final String[] SIDECAR_SUFFIXES = {"-journal", "-wal", "-shm"};

    private final Path root;
    private final IndexService index = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

    LexicalHardeningScenario(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /** Writes the corpus in declaration or reverse order into an empty project, indexes it, and returns its dump. */
    String indexCorpus(boolean reversed) throws Exception {
        writeCorpus(root, reversed);
        IndexResult result = index(root, CORPUS_SIZE, CORPUS_SIZE, 0);

        IndexDump dump = IndexDump.read(result.context().databasePath(), root, LexicalTestCorpus.QUERIES);
        check(dump.files().size() == CORPUS_SIZE, "The packaged dump must list every corpus file");
        check(
                dump.queries().stream().allMatch(query -> !query.hits().isEmpty()),
                "Every corpus query must match in the packaged index");
        return dump.toText();
    }

    /**
     * Proves a write-free unchanged rerun, then renames by case alone and requires the index to follow each rename
     * without stale records. Where the filesystem folds case, the renamed directory is reindexed through its old
     * spelling as a child target. The final index must equal a fresh index of the same files. Returns a report line
     * naming the filesystem behavior, so the caller can confirm which branch ran.
     */
    String verifyIncrementalLifecycle() throws Exception {
        writeCorpus(root, false);
        Path database = index(root, CORPUS_SIZE, CORPUS_SIZE, 0).context().databasePath();
        boolean foldsCase = foldsCase();

        try (Connection probe = openProbe(database)) {
            long dataVersion = dataVersion(probe);
            String files = databaseFiles(database);
            index(root, CORPUS_SIZE, 0, 0);
            check(dataVersion == dataVersion(probe), "An unchanged packaged rerun committed to the index");
            check(files.equals(databaseFiles(database)), "An unchanged packaged rerun changed the database files");

            Files.writeString(root.resolve(LexicalTestCorpus.NOTES), "Release notes: changed.\n");
            index(root, CORPUS_SIZE, 1, 0);
            check(dataVersion != dataVersion(probe), "The data_version probe must observe a content change");
        }

        renameSpelling(root.resolve(LexicalTestCorpus.NOTES), "Notes.txt");
        index(root, CORPUS_SIZE, 1, 1);
        checkStoredPaths(database, "docs/Notes.txt", "docs/notes.txt");

        renameSpelling(root.resolve("docs"), "Docs");
        int docs = 2;
        // Folding filesystems resolve the old spelling to the renamed directory, the case fixed for child targets.
        index(foldsCase ? root.resolve("docs") : root, foldsCase ? docs : CORPUS_SIZE, docs, docs);
        checkStoredPaths(database, "Docs/Notes.txt", "docs/Notes.txt");
        checkStoredPaths(database, "Docs/Ödeme rehberi.md", "docs/Ödeme rehberi.md");

        if (foldsCase) {
            index(root.resolve("DOCS"), docs, 0, 0);
        }

        Path fresh = Files.createDirectory(root.resolveSibling(root.getFileName() + " fresh"));
        copyProject(root, fresh);
        String freshDump = IndexDump.read(
                        index(fresh, CORPUS_SIZE, CORPUS_SIZE, 0).context().databasePath(),
                        fresh,
                        LexicalTestCorpus.QUERIES)
                .toText();
        check(
                freshDump.equals(IndexDump.read(database, root, LexicalTestCorpus.QUERIES)
                        .toText()),
                "The incremental packaged index must equal a fresh index of the same files");
        return "CASE_FOLDING " + foldsCase + "\n";
    }

    private IndexResult index(Path target, int candidates, int indexed, int deleted) throws Exception {
        IndexResult result = index.index(target);
        check(
                result.status() == IndexResult.Status.COMPLETE
                        && result.candidateCount() == candidates
                        && result.indexedFiles() == indexed
                        && result.unchangedFiles() == candidates - indexed
                        && result.deletedFiles() == deleted,
                "Unexpected packaged index result for " + target + ": " + result);
        return result;
    }

    /* Writes into an empty project; a Git marker pins the project root for child targets and is never indexed. */
    private static void writeCorpus(Path project, boolean reversed) throws Exception {
        try (var children = Files.list(project)) {
            check(children.findAny().isEmpty(), "The packaged corpus project must start empty: " + project);
        }

        Files.createDirectory(project.resolve(".git"));

        if (reversed) {
            LexicalTestCorpus.writeReversed(project);
        } else {
            LexicalTestCorpus.write(project);
        }
    }

    private void checkStoredPaths(Path database, String present, String absent) throws Exception {
        List<String> stored = new ArrayList<>();

        try (SqliteStorage storage = SqliteStorage.openReadOnly(database, root)) {
            storage.files().findAll().forEach(file -> stored.add(SqlitePath.encode(file.sourcePath())));
        }

        check(
                stored.contains(present) && !stored.contains(absent),
                "Expected " + present + " without " + absent + " in " + stored);
        check(stored.size() == CORPUS_SIZE, "Renames must not duplicate records: " + stored);
    }

    private boolean foldsCase() throws Exception {
        Path probe = Files.writeString(root.resolveSibling("CaseProbe.tmp"), "");

        try {
            return Files.exists(root.resolveSibling("caseprobe.tmp"));
        } finally {
            Files.delete(probe);
        }
    }

    /** Renames through a temporary name because a case-only move is a no-op on case-insensitive filesystems. */
    private static void renameSpelling(Path path, String name) throws Exception {
        Path temporary = Files.move(path, path.resolveSibling(name + ".renaming"));
        Files.move(temporary, path.resolveSibling(name));
    }

    /* Copies the source files and the Git marker, but not the index, so the copy is indexed from scratch. */
    private static void copyProject(Path source, Path target) throws Exception {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path relative = source.relativize(path);

                if (relative.toString().isEmpty() || relative.startsWith(".pecia")) {
                    continue;
                }

                Path copy = target.resolve(relative.toString());

                if (Files.isDirectory(path)) {
                    Files.createDirectories(copy);
                } else {
                    Files.copy(path, copy);
                }
            }
        }
    }

    private static Connection openProbe(Path database) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
    }

    private static long dataVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("PRAGMA data_version")) {
            result.next();
            return result.getLong(1);
        }
    }

    /* The database file's SHA-256 with the size of each sidecar file that exists. */
    private static String databaseFiles(Path database) throws Exception {
        Map<String, Long> sidecars = new TreeMap<>();

        for (String suffix : SIDECAR_SUFFIXES) {
            Path sidecar = database.resolveSibling(database.getFileName() + suffix);

            if (Files.exists(sidecar)) {
                sidecars.put(suffix, Files.size(sidecar));
            }
        }

        return ContentHash.sha256(Files.readAllBytes(database)).value() + " " + sidecars;
    }
}
