package dev.eyuppastirmaci.pecia.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobFilterTest {

    @Test
    void customGlobsAreCaseInsensitiveAndDoNotChangePaths() {
        GlobFilter filter = new GlobFilter(List.of("Docs/*.{MD,TXT}"), List.of("**/SECRET.*"));
        assertTrue(filter.matches(Path.of("docs/Guide.md")));
        assertFalse(filter.matches(Path.of("DOCS/secret.TXT")));
    }

    @Test
    void onlyWholeSubtreeExcludesPruneDirectories() {
        GlobFilter filter = new GlobFilter(List.of("**/*.md"), List.of("**/build/**", "docs/*.txt"));
        assertTrue(filter.excludesDirectory(Path.of("BUILD")));
        assertTrue(filter.excludesDirectory(Path.of("sub/build")));
        assertFalse(filter.excludesDirectory(Path.of("docs")));
    }

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
