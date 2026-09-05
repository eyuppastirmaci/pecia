package dev.eyuppastirmaci.pecia.content;

import dev.eyuppastirmaci.pecia.config.DefaultFileRules;
import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dev.eyuppastirmaci.pecia.content.DocumentType.*;
import static org.junit.jupiter.api.Assertions.*;

class FileTypeDetectorTest {
    private final FileTypeDetector detector = new FileTypeDetector();

    static Stream<Arguments> extensions() {

        return Stream.of(
                             group(PLAIN_TEXT, "txt rst adoc"), group(MARKDOWN, "md markdown"),
                             group(SOURCE_CODE, "java kt kts py pyi js jsx mjs cjs ts tsx mts cts c h cc cpp cxx hh hpp hxx cs go rs rb php swift sh bash zsh ps1 psm1 bat cmd sql css scss sass less"),
                             group(STRUCTURED_TEXT, "json jsonc yaml yml toml ini cfg properties xml")
                     ).flatMap(stream -> stream);
    }

    static Stream<Arguments> basenames() {

        return Stream.concat(group(PLAIN_TEXT, "README LICENSE LICENCE NOTICE CHANGELOG AUTHORS CONTRIBUTING"),
                             group(SOURCE_CODE, "Dockerfile Containerfile Makefile Jenkinsfile"));
    }

    private static Stream<Arguments> group(DocumentType type, String names) {

        return Arrays.stream(names.split(" ")).map(name -> Arguments.of(name, type));
    }

    @ParameterizedTest
    @MethodSource("extensions")
    void classifiesAllExtensionsWithoutReadingFiles(String extension, DocumentType expected) {
        assertEquals(Optional.of(expected), detector.detect(Path.of("file." + extension)));
        Path nested = Path.of("missing/directory/File." + extension.toUpperCase(Locale.ROOT));
        assertEquals(Optional.of(expected), detector.detect(nested));
        assertEquals(expected, detector.typeForCandidate(nested));
    }

    @ParameterizedTest
    @MethodSource("basenames")
    void classifiesOnlyExactSpecialBasenames(String name, DocumentType expected) {
        assertEquals(Optional.of(expected), detector.detect(Path.of(name)));
        assertEquals(Optional.of(expected), detector.detect(Path.of("nested/" + name.toUpperCase(Locale.ROOT))));
        assertTrue(detector.detect(Path.of(name + ".dev")).isEmpty());
    }

    @Test
    void classificationAndDefaultDiscoveryHaveExactlyTheSameCatalog() {
        Set<String> expectedExtensions = extensions().map(args -> (String) args.get()[0])
                                                     .collect(Collectors.toSet());
        Set<String> expectedNames = basenames().map(args -> (String) args.get()[0])
                                              .collect(Collectors.toSet());
        assertEquals(53, expectedExtensions.size());
        assertEquals(11, expectedNames.size());
        assertEquals(expectedExtensions, Set.copyOf(DefaultFileRules.EXTENSIONS));
        assertEquals(expectedNames, Set.copyOf(DefaultFileRules.BASENAMES));
        assertEquals(53, DefaultFileRules.EXTENSIONS.size());
        assertEquals(11, DefaultFileRules.BASENAMES.size());
    }

    @Test
    void finalExtensionWinsOverEarlierSuffixesAndSpecialNamePrefixes() {
        assertEquals(Optional.of(SOURCE_CODE), detector.detect(Path.of("build.gradle.kts")));
        assertEquals(Optional.of(STRUCTURED_TEXT), detector.detect(Path.of("pom.xml")));
        assertEquals(Optional.of(STRUCTURED_TEXT), detector.detect(Path.of("package.json")));
        assertEquals(Optional.of(PLAIN_TEXT), detector.detect(Path.of("CMakeLists.txt")));
        assertEquals(Optional.of(MARKDOWN), detector.detect(Path.of("README.md")));
        assertEquals(Optional.of(STRUCTURED_TEXT), detector.detect(Path.of(".pecia.toml")));
        assertTrue(detector.detect(Path.of("notes.md.backup")).isEmpty());
        assertTrue(detector.detect(Path.of("folder.java/unknown")).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"file.html", "file.htm", "file.svg", "file.vue", "file.svelte", "file.csv", "file.tsv",
            "file.ipynb", "file.log", "file.eml", "file.msg", "file.pdf", "file.doc", "file.docx", "file.xls",
            "file.xlsx", "file.ppt", "file.pptx", "file.odt", "file.ods", "file.odp", "file.rtf", "file.zip",
            "file.png", "file.jpg", "file.mp3", "file.wav", "file.mp4", "file.mkv", "unknown", "file.custom",
            ".md", ".env", "file.", "Dockerfile.dev"})
    void unknownAndDeferredNamesAreUnrecognizedButCanBeTextCandidates(String name) {
        assertTrue(detector.detect(Path.of(name)).isEmpty());
        assertEquals(PLAIN_TEXT, detector.typeForCandidate(Path.of(name)));
    }

    @Test
    void invalidPathsAreRejected() {
        assertThrows(NullPointerException.class, () -> detector.detect(null));

        for (Path path : List.of(Path.of(""), Path.of("."), Path.of(".."), Path.of(".").toAbsolutePath().getRoot())) {
            assertThrows(IllegalArgumentException.class, () -> detector.detect(path));
            assertThrows(IllegalArgumentException.class, () -> detector.typeForCandidate(path));
        }
    }

    @Test
    void usesLocaleIndependentCaseFolding() {
        Locale original = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(Optional.of(PLAIN_TEXT), detector.detect(Path.of("LICENSE")));
            assertEquals(Optional.of(STRUCTURED_TEXT), detector.detect(Path.of("SETTINGS.INI")));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void discoveryControlsAdmissionBeforeCandidateFallback(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("notes.custom"), "future extraction input");
        Files.writeString(root.resolve("guide.MD"), "# Guide");
        PeciaConfig defaults = PeciaConfig.defaults();
        FileWalker normal = new FileWalker(new dev.eyuppastirmaci.pecia.index.GlobFilter(defaults.include(), defaults.exclude()));
        assertEquals(List.of(Path.of("guide.MD")), normal.walk(root));
        FileWalker custom = new FileWalker(new dev.eyuppastirmaci.pecia.index.GlobFilter(List.of("**/*.custom"), defaults.exclude()));
        List<Path> candidates = custom.walk(root);
        assertEquals(List.of(Path.of("notes.custom")), candidates);
        assertTrue(detector.detect(candidates.getFirst()).isEmpty());
        assertEquals(PLAIN_TEXT, detector.typeForCandidate(candidates.getFirst()));
    }
}
