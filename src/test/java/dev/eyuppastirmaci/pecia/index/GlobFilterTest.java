package dev.eyuppastirmaci.pecia.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobFilterTest {

    @Test
    void recursiveGlobMatchesAtEveryDepthIncludingRoot() {
        GlobFilter filter = new GlobFilter(List.of("**/*.md"), List.of());

        assertTrue(filter.matches(Path.of("README.md")));
        assertTrue(filter.matches(Path.of("docs/guide.md")));
        assertFalse(filter.matches(Path.of("notes.txt")));
    }

    @Test
    void excludeWinsOverInclude() {
        GlobFilter filter = new GlobFilter(List.of("**/*.md"), List.of("**/target/**"));

        assertFalse(filter.matches(Path.of("target/generated.md")));
        assertFalse(filter.matches(Path.of("sub/target/generated.md")));
        assertTrue(filter.matches(Path.of("docs/keep.md")));
    }

    @Test
    void emptyIncludeListMatchesEverything() {
        GlobFilter filter = new GlobFilter(List.of(), List.of());

        assertTrue(filter.matches(Path.of("anything.bin")));
        assertTrue(filter.matches(Path.of("deep/nested/file")));
    }
}
