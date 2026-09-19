package dev.eyuppastirmaci.pecia.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentPathTest {

    @Test
    void encodesPathElementsWithPortableSeparators() {
        Path path = Path.of("docs", "getting-started", "guide.md");

        assertSame(path, ContentPath.requireProjectRelative(path));
        assertEquals("docs/getting-started/guide.md", ContentPath.encode(path));
        assertEquals("guide.md", ContentPath.encode(Path.of("guide.md")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Guide.md", "guide.md", "İstanbul.md", "é.md", "e\u0301.md", "📚 guide.md"})
    void preservesCaseAndUnicodeSpellingOfPathElements(String name) {
        Path path = Path.of("docs", name);

        assertEquals("docs/" + path.getFileName(), ContentPath.encode(path));
    }

    @Test
    void keepsCaseDistinct() {
        assertNotEquals(ContentPath.encode(Path.of("Guide.md")), ContentPath.encode(Path.of("guide.md")));
    }

    @Test
    void respectsTheHostsMeaningOfBackslashes() {
        Path backslashPath = Path.of("docs\\guide.md");
        String encoded = ContentPath.encode(backslashPath);

        if (File.separatorChar == '\\') {
            assertEquals("docs/guide.md", encoded);
        } else {
            assertEquals("docs\\guide.md", encoded);
            assertNotEquals(ContentPath.encode(Path.of("docs", "guide.md")), encoded);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../outside.md", "docs/../notes.md", "./notes.md", "docs/./notes.md"})
    void rejectsInvalidPathsWithoutNormalizingThem(String value) {
        assertThrows(IllegalArgumentException.class, () -> ContentPath.requireProjectRelative(Path.of(value)));
        assertThrows(IllegalArgumentException.class, () -> ContentPath.encode(Path.of(value)));
    }

    @Test
    void rejectsAbsolutePaths() {
        Path path = Path.of("notes.md").toAbsolutePath();

        assertThrows(IllegalArgumentException.class, () -> ContentPath.requireProjectRelative(path));
        assertThrows(IllegalArgumentException.class, () -> ContentPath.encode(path));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsDriveRelativeAndRootRelativeWindowsPaths() {
        for (Path path : List.of(Path.of("C:notes.md"), Path.of("\\notes.md"))) {
            assertFalse(path.isAbsolute());
            assertNotNull(path.getRoot());
            assertThrows(IllegalArgumentException.class, () -> ContentPath.requireProjectRelative(path));
            assertThrows(IllegalArgumentException.class, () -> ContentPath.encode(path));
        }
    }

    @Test
    void rejectsNullPaths() {
        assertThrows(NullPointerException.class, () -> ContentPath.requireProjectRelative(null));
        assertThrows(NullPointerException.class, () -> ContentPath.encode(null));
    }
}
