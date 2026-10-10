package dev.eyuppastirmaci.pecia.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader.LoadedConfig;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import dev.eyuppastirmaci.pecia.testing.PropertySeeds;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;

/**
 * Source paths are project-relative, normalized, and never escape the project root. A small model computes the
 * expected outcome from the generated name segments, independently of {@link Path#normalize}.
 *
 * <p>Generated names avoid characters that are invalid on Windows, names ending in a dot or space, and backslashes,
 * which are separators on Windows but filename characters elsewhere; those host differences stay with the
 * example-based tests.
 */
class ContentPathPropertiesTest {

    // A syntactic root: these properties never touch the filesystem.
    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"))
            .toAbsolutePath()
            .resolve("pecia-property-root")
            .normalize();

    @Property(seed = PropertySeeds.FIXED, tries = 1000)
    void acceptsExactlyNonEmptyRelativePathsWithoutDotSegments(@ForAll("rawPaths") RawPath raw) {
        Path path = Path.of(raw.text());
        boolean expected = raw.expectedAccepted();
        Statistics.label("accepted").collect(expected);
        Statistics.label("accepted").coverage(coverage -> coverage.check(true).percentage(p -> p >= 25));
        Statistics.label("accepted").coverage(coverage -> coverage.check(false).percentage(p -> p >= 25));

        if (!expected) {
            assertThrows(IllegalArgumentException.class, () -> ContentPath.requireProjectRelative(path), raw.text());
            return;
        }

        assertEquals(path, ContentPath.requireProjectRelative(path));
        assertEquals(raw.names(), names(path));
        Path resolved = ROOT.resolve(path).normalize();
        assertTrue(resolved.startsWith(ROOT) && !resolved.equals(ROOT), resolved.toString());
    }

    @Property(seed = PropertySeeds.FIXED, tries = 1000)
    void normalizationIsIdempotentAndEscapesOnlyWhenDotDotClimbsAboveTheRoot(@ForAll("rawPaths") RawPath raw) {
        Path path = Path.of(raw.text());
        Path normalized = path.normalize();
        List<String> expected = raw.resolvedNames();
        Statistics.label("escapes").collect(expected == null);
        Statistics.label("escapes").coverage(coverage -> coverage.check(true).count(count -> count > 0));

        assertEquals(normalized, normalized.normalize());

        if (raw.rooted()) {
            assertThrows(IllegalArgumentException.class, () -> ContentPath.requireProjectRelative(normalized));
        } else if (expected == null) {
            assertThrows(IllegalArgumentException.class, () -> ContentPath.requireProjectRelative(normalized));
            assertTrue(!ROOT.resolve(path).normalize().startsWith(ROOT));
        } else if (!expected.isEmpty()) {
            assertEquals(expected, names(ContentPath.requireProjectRelative(normalized)));
            assertTrue(ROOT.resolve(path).normalize().startsWith(ROOT));
        }
    }

    @Property(seed = PropertySeeds.FIXED, tries = 1000)
    void projectContextResolvesChildTargetCandidatesIdempotently(
            @ForAll("acceptedPaths") RawPath candidate, @ForAll("childTargets") List<String> child) {
        Path target = ROOT;

        for (String name : child) {
            target = target.resolve(name);
        }

        ProjectContext childContext = context(target);
        ProjectContext rootContext = context(ROOT);
        Path candidatePath = Path.of(candidate.text());

        Path sourcePath = childContext.sourcePath(candidatePath);
        List<String> expected = new ArrayList<>(child);
        expected.addAll(candidate.names());

        assertEquals(expected, names(sourcePath));
        assertEquals(childContext.absoluteSource(candidatePath), ROOT.resolve(sourcePath));
        assertEquals(sourcePath, rootContext.sourcePath(sourcePath));
        assertEquals(sourcePath, ContentPath.requireProjectRelative(sourcePath));
    }

    @Property(seed = PropertySeeds.FIXED, tries = 1000)
    void projectContextRejectsAbsoluteAndEscapingCandidates(@ForAll("rawPaths") RawPath raw) {
        Path path = Path.of(raw.text());
        ProjectContext context = context(ROOT.resolve("child"));

        if (!raw.expectedAccepted()) {
            assertThrows(IllegalArgumentException.class, () -> context.absoluteSource(path), raw.text());
            assertThrows(IllegalArgumentException.class, () -> context.sourcePath(path), raw.text());
        }
        assertThrows(IllegalArgumentException.class, () -> context.sourcePath(ROOT.resolve("absolute")));
    }

    @Property(seed = PropertySeeds.FIXED, tries = 1000)
    void sqliteSpellingRoundTripsAndKeepsEveryName(
            @ForAll("acceptedPaths") RawPath first, @ForAll("acceptedPaths") RawPath second) {
        Path path = Path.of(first.text());
        String encoded = SqlitePath.encode(path);

        assertEquals(String.join("/", first.names()), encoded);
        assertEquals(encoded, ContentPath.encode(path));
        assertEquals(first.names(), names(SqlitePath.decode(encoded)));

        if (!first.names().equals(second.names())) {
            assertNotEquals(encoded, SqlitePath.encode(Path.of(second.text())));
        }
    }

    @Provide
    Arbitrary<RawPath> rawPaths() {
        Arbitrary<String> segment = Arbitraries.frequencyOf(
                Tuple.of(6, names()),
                Tuple.of(1, Arbitraries.just(".")),
                Tuple.of(1, Arbitraries.just("..")),
                Tuple.of(1, Arbitraries.just("")));
        return Combinators.combine(
                        segment.list().ofMinSize(0).ofMaxSize(6),
                        Arbitraries.of(true, false).list().ofSize(7),
                        Arbitraries.frequency(Tuple.of(1, true), Tuple.of(9, false)),
                        Arbitraries.of(true, false))
                .as(RawPath::new);
    }

    @Provide
    Arbitrary<RawPath> acceptedPaths() {
        return rawPaths().filter(RawPath::expectedAccepted);
    }

    @Provide
    Arbitrary<List<String>> childTargets() {
        return names().list().ofMaxSize(2);
    }

    private static Arbitrary<String> names() {
        return Arbitraries.of(
                "docs",
                "Docs",
                "DOCS",
                "notes.txt",
                "src",
                "Main.java",
                ".hidden",
                "..foo",
                ".pecia.toml",
                "a.b.c",
                "Ödeme",
                Normalizer.normalize("Ödeme", Normalizer.Form.NFD),
                "İstanbul",
                "ığüşöç",
                "漢字",
                "😀 emoji",
                "with space");
    }

    private static ProjectContext context(Path target) {
        return new ProjectContext(
                target, new LoadedConfig(PeciaConfig.defaults(), ROOT, false), ROOT.resolve(".pecia/index.db"));
    }

    /* Compares exact name spellings: Path equality ignores case on Windows. */
    private static List<String> names(Path path) {
        List<String> names = new ArrayList<>();
        path.forEach(name -> names.add(name.toString()));
        return names;
    }

    /**
     * A generated path: name segments joined by single or doubled slashes, optionally rooted or ending in a slash.
     *
     * @param doubled for each gap between segments, whether the separator is doubled
     */
    record RawPath(List<String> segments, List<Boolean> doubled, boolean absolute, boolean trailingSlash) {

        String text() {
            StringBuilder text = new StringBuilder(absolute ? "/" : "");

            for (int index = 0; index < segments.size(); index++) {
                if (index > 0) {
                    text.append(doubled.get(index) ? "//" : "/");
                }
                text.append(segments.get(index));
            }

            return text.append(trailingSlash && !segments.isEmpty() ? "/" : "").toString();
        }

        /* A leading empty segment also roots the path, because the text then starts with a separator. */
        boolean rooted() {
            return text().startsWith("/");
        }

        /* Empty segments vanish when the path is parsed, as repeated or trailing separators do. */
        List<String> names() {
            return segments.stream().filter(segment -> !segment.isEmpty()).toList();
        }

        boolean expectedAccepted() {
            List<String> names = names();
            return !rooted()
                    && !names.isEmpty()
                    && names.stream().noneMatch(name -> name.equals(".") || name.equals(".."));
        }

        /* Resolves dot segments with a stack; null means the path climbs above the root. */
        List<String> resolvedNames() {
            Deque<String> stack = new ArrayDeque<>();

            for (String name : names()) {
                if (name.equals("..")) {
                    if (stack.isEmpty()) {
                        return null;
                    }
                    stack.removeLast();
                } else if (!name.equals(".")) {
                    stack.addLast(name);
                }
            }

            return List.copyOf(stack);
        }
    }
}
