package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import dev.eyuppastirmaci.pecia.testing.IndexDump;
import dev.eyuppastirmaci.pecia.testing.LexicalTestCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LexicalDeterminismTest {

    @TempDir
    Path sandbox;

    private final IndexService indexService = new IndexService(new PeciaConfigLoader(new PeciaConfigParser()));

    @Test
    void freshIndexesUnderDifferentRootsAndWriteOrdersProduceIdenticalDumps() throws Exception {
        Path first = Files.createDirectories(sandbox.resolve("first")).toRealPath();
        Path second = Files.createDirectories(sandbox.resolve("second root/with spaces"))
                .toRealPath();
        LexicalTestCorpus.write(first);
        LexicalTestCorpus.writeReversed(second);

        IndexDump firstDump = indexAndDump(first);
        IndexDump secondDump = indexAndDump(second);

        assertCorpusIsRepresented(firstDump);
        assertEquals(firstDump, secondDump);
    }

    @Test
    void rebuildingFromScratchAndRerunningReproduceTheSameDump() throws Exception {
        Path root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        LexicalTestCorpus.write(root);
        IndexDump original = indexAndDump(root);

        IndexDump rerun = indexAndDump(root);
        deleteRecursively(root.resolve(".pecia"));
        IndexDump rebuilt = indexAndDump(root);

        assertEquals(original, rerun);
        assertEquals(original, rebuilt);
    }

    /* Guards the corpus itself, so equal dumps cannot come from an index that skipped the interesting inputs. */
    private static void assertCorpusIsRepresented(IndexDump dump) {
        assertEquals(
                LexicalTestCorpus.files().keySet().stream()
                        .map(SqlitePath::encode)
                        .sorted()
                        .toList(),
                dump.files().stream().map(IndexDump.FileEntry::sourcePath).toList());
        assertEquals(
                ContentHash.sha256(LexicalTestCorpus.files().get(LexicalTestCorpus.SCRIPT))
                        .value(),
                file(dump, LexicalTestCorpus.SCRIPT).contentHash());
        assertTrue(chunks(dump, LexicalTestCorpus.EMPTY).isEmpty());
        assertFalse(chunks(dump, LexicalTestCorpus.SCRIPT).getFirst().content().startsWith("﻿"));
        assertTrue(chunks(dump, LexicalTestCorpus.AUTH).getFirst().content().contains("\r\n"));
        assertTrue(chunks(dump, LexicalTestCorpus.GUIDE).stream()
                .anyMatch(chunk -> chunk.headings().size() == 3));
        assertTrue(dump.chunks().stream().allMatch(chunk -> chunk.stableId() != null));
        assertEquals(dump.chunks().size(), dump.ftsRows().size());
        assertTrue(dump.queries().stream().allMatch(query -> !query.hits().isEmpty()));

        List<IndexDump.ChunkEntry> handbook = chunks(dump, LexicalTestCorpus.HANDBOOK);
        assertTrue(handbook.size() >= 3, "The long document must produce several chunks: " + handbook.size());
        assertTrue(
                overlaps(handbook.get(0), handbook.get(1)),
                "Default overlap must repeat source text between adjacent chunks");
    }

    private static boolean overlaps(IndexDump.ChunkEntry previous, IndexDump.ChunkEntry next) {
        return Integer.parseInt(next.attributes().get("startOffset"))
                < Integer.parseInt(previous.attributes().get("endOffset"));
    }

    private static IndexDump.FileEntry file(IndexDump dump, Path sourcePath) {
        String encoded = SqlitePath.encode(sourcePath);
        return dump.files().stream()
                .filter(file -> file.sourcePath().equals(encoded))
                .findFirst()
                .orElseThrow();
    }

    private static List<IndexDump.ChunkEntry> chunks(IndexDump dump, Path sourcePath) {
        String encoded = SqlitePath.encode(sourcePath);
        return dump.chunks().stream()
                .filter(chunk -> chunk.sourcePath().equals(encoded))
                .toList();
    }

    private IndexDump indexAndDump(Path root) throws Exception {
        IndexResult result = indexService.index(root);
        assertEquals(IndexResult.Status.COMPLETE, result.status());
        return IndexDump.read(result.context().databasePath(), root, LexicalTestCorpus.QUERIES);
    }

    private static void deleteRecursively(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
