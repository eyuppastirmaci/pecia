package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.eyuppastirmaci.pecia.testing.PropertySeeds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Discovery must select exactly the files that a small reference model of the documented rules selects, for
 * generated file trees, include and exclude globs, and nested {@code .gitignore} files.
 *
 * <p>The model covers a documented subset and does not reimplement JGit or {@link java.nio.file.PathMatcher}:
 * <ul>
 *   <li>Globs {@code **}{@code /*.ext}, {@code *.ext}, {@code dir/**}, and literal paths, matched case-insensitively;
 *       exclusions win, an empty include list admits everything, and a leading {@code **}{@code /} also matches at the
 *       root.
 *   <li>Gitignore patterns without inner slashes: names and {@code *.ext} match a path's last name at any depth,
 *       a trailing slash matches directories only, a leading slash anchors to the file's directory, and {@code !}
 *       negates. Within a file the last matching pattern wins; the nearest file with a match decides; an ignored
 *       directory hides its whole subtree.
 * </ul>
 * Other syntax stays with the example-based discovery tests.
 */
class DiscoveryRulesPropertiesTest {

    private static final List<String> DIRECTORIES = List.of("", "docs", "src", "build", "lib", "src/lib", "docs/build");
    private static final List<String> FILE_NAMES =
            List.of("a.md", "b.MD", "notes.txt", "x.log", "keep.log", "Main.java", "README");
    private static final List<String> INCLUDES =
            List.of("**/*.md", "**/*.MD", "*.txt", "src/**", "**/*.java", "docs/*.md", "README", "**/lib/**");
    private static final List<String> EXCLUDES =
            List.of("**/build/**", "*.log", "docs/**", "**/*.TXT", "src/Main.java", "**/README");
    private static final List<String> IGNORE_RULES = List.of(
            "*.log", "!keep.log", "build/", "/README", "notes.txt", "!notes.txt", "lib/", "*.md", "!b.MD", "/docs/");

    @Property(seed = PropertySeeds.FIXED, tries = 300)
    void discoverySelectsExactlyTheFilesTheReferenceModelSelects(@ForAll("scenarios") Scenario scenario)
            throws IOException {
        Path root = Files.createTempDirectory("pecia-discovery-property").toRealPath();

        try {
            scenario.create(root);
            List<String> actual = new ArrayList<>();
            new FileWalker(new GlobFilter(scenario.includes(), scenario.excludes()))
                    .walk(root)
                    .forEach(path -> actual.add(FileWalker.portablePath(path)));

            List<String> expected = scenario.expectedCandidates();
            Statistics.label("ignored by gitignore").collect(scenario.anyIgnored());
            Statistics.label("candidates").collect(expected.isEmpty() ? "none" : "some");
            Statistics.label("ignored by gitignore")
                    .coverage(coverage -> coverage.check(true).percentage(p -> p >= 30));
            Statistics.label("candidates")
                    .coverage(coverage -> coverage.check("some").percentage(p -> p >= 50));

            assertEquals(expected, actual, scenario.toString());
        } finally {
            deleteRecursively(root);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<String> file = Combinators.combine(Arbitraries.of(DIRECTORIES), Arbitraries.of(FILE_NAMES))
                .as((directory, name) -> directory.isEmpty() ? name : directory + "/" + name);
        return Combinators.combine(
                        file.set().ofMinSize(1).ofMaxSize(10),
                        Arbitraries.of(INCLUDES).list().ofMaxSize(3),
                        Arbitraries.of(EXCLUDES).list().ofMaxSize(2),
                        Arbitraries.of(IGNORE_RULES).list().ofMaxSize(4),
                        Arbitraries.of(DIRECTORIES).filter(directory -> !directory.isEmpty()),
                        Arbitraries.of(IGNORE_RULES).list().ofMaxSize(3))
                .as(Scenario::new);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    /**
     * A generated tree with globs and two {@code .gitignore} files: one at the root, one in a nested directory.
     *
     * @param files slash-separated source files to create
     * @param nestedDirectory directory holding the nested {@code .gitignore}; created when it holds no files
     */
    record Scenario(
            Set<String> files,
            List<String> includes,
            List<String> excludes,
            List<String> rootRules,
            String nestedDirectory,
            List<String> nestedRules) {

        void create(Path root) throws IOException {
            for (String file : files) {
                Path path = root.resolve(file);
                Files.createDirectories(path.getParent());
                Files.writeString(path, file);
            }

            writeIgnore(root, rootRules);
            Path nested = Files.createDirectories(root.resolve(nestedDirectory));
            writeIgnore(nested, nestedRules);
        }

        /* Every file the walker can see, including the generated .gitignore files themselves. */
        Set<String> allFiles() {
            Set<String> all = new TreeSet<>(files);

            if (!rootRules.isEmpty()) {
                all.add(".gitignore");
            }
            if (!nestedRules.isEmpty()) {
                all.add(nestedDirectory + "/.gitignore");
            }

            return all;
        }

        List<String> expectedCandidates() {
            List<String> candidates = new ArrayList<>();

            for (String file : allFiles()) {
                if (!hiddenByIgnoredAncestor(file) && !ignored(file, false) && admittedByGlobs(file)) {
                    candidates.add(file);
                }
            }

            return candidates.stream().sorted().toList();
        }

        boolean anyIgnored() {
            return allFiles().stream().anyMatch(file -> hiddenByIgnoredAncestor(file) || ignored(file, false));
        }

        private boolean hiddenByIgnoredAncestor(String file) {
            String[] names = file.split("/");
            StringBuilder directory = new StringBuilder();

            for (int index = 0; index < names.length - 1; index++) {
                directory.append(index == 0 ? "" : "/").append(names[index]);

                if (ignored(directory.toString(), true)) {
                    return true;
                }
            }

            return false;
        }

        /* The nearest .gitignore with a matching pattern decides; a directory's own file applies to its contents. */
        private boolean ignored(String path, boolean directory) {
            if (path.startsWith(nestedDirectory + "/") && !nestedRules.isEmpty()) {
                Boolean nested = lastMatch(nestedRules, path.substring(nestedDirectory.length() + 1), directory);

                if (nested != null) {
                    return nested;
                }
            }

            Boolean atRoot = lastMatch(rootRules, path, directory);
            return atRoot != null && atRoot;
        }

        /* Returns whether the last matching rule ignores the path, or null when no rule matches. */
        private static Boolean lastMatch(List<String> rules, String relative, boolean directory) {
            Boolean result = null;

            for (String rule : rules) {
                boolean negated = rule.startsWith("!");
                String pattern = negated ? rule.substring(1) : rule;
                boolean directoryOnly = pattern.endsWith("/");
                pattern = directoryOnly ? pattern.substring(0, pattern.length() - 1) : pattern;
                boolean anchored = pattern.startsWith("/");
                pattern = anchored ? pattern.substring(1) : pattern;
                String subject = anchored ? relative : relative.substring(relative.lastIndexOf('/') + 1);

                if ((!directoryOnly || directory)
                        && wildcard(pattern).matcher(subject).matches()) {
                    result = !negated;
                }
            }

            return result;
        }

        private boolean admittedByGlobs(String file) {
            String folded = file.toLowerCase(Locale.ROOT);

            if (excludes.stream().anyMatch(glob -> glob(glob).matcher(folded).matches())) {
                return false;
            }

            return includes.isEmpty()
                    || includes.stream()
                            .anyMatch(glob -> glob(glob).matcher(folded).matches());
        }

        /* Translates the documented glob subset: a leading **, a trailing /**, and * within one name. */
        private static Pattern glob(String glob) {
            String folded = glob.toLowerCase(Locale.ROOT);
            String prefix = "";
            String suffix = "";

            if (folded.startsWith("**/")) {
                prefix = "(?:.*/)?";
                folded = folded.substring(3);
            }
            if (folded.endsWith("/**")) {
                suffix = "/.+";
                folded = folded.substring(0, folded.length() - 3);
            }

            return Pattern.compile(prefix + wildcard(folded).pattern() + suffix);
        }

        /* Translates * into any run of characters within one name; everything else is literal. */
        private static Pattern wildcard(String pattern) {
            List<String> parts = new ArrayList<>();

            for (String part : pattern.split("\\*", -1)) {
                parts.add(Pattern.quote(part));
            }

            return Pattern.compile(String.join("[^/]*", parts));
        }

        private static void writeIgnore(Path directory, List<String> rules) throws IOException {
            if (!rules.isEmpty()) {
                Files.writeString(directory.resolve(".gitignore"), String.join("\n", rules) + "\n");
            }
        }
    }
}
