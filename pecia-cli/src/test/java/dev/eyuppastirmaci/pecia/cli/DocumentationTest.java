package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.Bootstrap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Keeps the Markdown documentation true to the repository and the shipped CLI: every local link and heading anchor
 * resolves, every {@code pecia} command line in a code block parses, and every option the docs name exists.
 */
class DocumentationTest {

    private static final Path ROOT = Path.of("..");
    // User documents that name CLI options; CONTRIBUTING.md names Gradle options instead.
    private static final List<String> USER_DOCUMENTS =
            List.of("README.md", "docs/cli.md", "docs/install.md", "docs/library.md");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?ms)^```[^\\n]*\\n(.*?)^```");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6} (.+)$");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern OPTION = Pattern.compile("(?<![\\w-])--(\\[no-])?([a-z][a-z-]*)");

    @Test
    void everyLocalLinkAndHeadingAnchorResolves() throws IOException {
        List<String> broken = new ArrayList<>();

        for (Path document : documents()) {
            String text = FENCED_BLOCK.matcher(read(document)).replaceAll("");
            Matcher link = LINK.matcher(text);

            while (link.find()) {
                String target = link.group(1);

                if (target.matches("[a-z]+:.*")) {
                    continue;
                }

                int hash = target.indexOf('#');
                String path = hash < 0 ? target : target.substring(0, hash);
                Path file = path.isEmpty()
                        ? document
                        : document.getParent().resolve(path).normalize();
                boolean resolves = Files.exists(file)
                        && (hash < 0
                                || Files.isRegularFile(file) && anchors(file).contains(target.substring(hash + 1)));

                if (!resolves) {
                    broken.add(ROOT.relativize(document) + ": " + target);
                }
            }
        }

        assertEquals(List.of(), broken);
    }

    @Test
    void everyDocumentedCommandLineParses() throws IOException {
        List<String> commandLines = new ArrayList<>();

        for (String name : USER_DOCUMENTS) {
            Matcher block = FENCED_BLOCK.matcher(read(ROOT.resolve(name)));

            while (block.find()) {
                block.group(1)
                        .lines()
                        .map(line -> line.replaceFirst("\\s+#.*$", "").strip())
                        // Output such as "pecia index: PARTIAL: …" names the command but is not a command line.
                        .filter(line -> line.matches("pecia( (?![\\w-]+:)\\S.*)?"))
                        .forEach(commandLines::add);
            }
        }

        assertTrue(commandLines.size() >= 10, "Too few documented command lines found: " + commandLines);

        for (String commandLine : commandLines) {
            List<String> words = words(commandLine);
            String[] arguments = words.subList(1, words.size()).toArray(String[]::new);

            try {
                new CommandLine(new PeciaCommand(), new Bootstrap()).parseArgs(arguments);
            } catch (CommandLine.ParameterException rejected) {
                throw new AssertionError(commandLine + ": " + rejected.getMessage(), rejected);
            }
        }
    }

    @Test
    void everyDocumentedOptionExists() throws IOException {
        Set<String> known = knownOptions();
        Set<String> unknown = new TreeSet<>();

        for (String name : USER_DOCUMENTS) {
            for (String usage : optionUsages(read(ROOT.resolve(name)))) {
                Matcher option = OPTION.matcher(usage);

                while (option.find()) {
                    String documented = "--" + option.group(2);

                    if (!known.contains(documented)) {
                        unknown.add(name + ": " + documented);
                    }
                }
            }
        }

        assertEquals(Set.of(), unknown);
    }

    /**
     * The places where a document names Pecia's options: code-block lines and inline code that start with {@code pecia}
     * or with an option. Other tools' commands, such as {@code codesign --check-notarization}, are left out.
     */
    private static List<String> optionUsages(String document) {
        List<String> usages = new ArrayList<>();
        Matcher block = FENCED_BLOCK.matcher(document);

        while (block.find()) {
            block.group(1).lines().filter(DocumentationTest::namesPecia).forEach(usages::add);
        }

        Matcher inline = INLINE_CODE.matcher(FENCED_BLOCK.matcher(document).replaceAll(""));

        while (inline.find()) {
            if (namesPecia(inline.group(1))) {
                usages.add(inline.group(1));
            }
        }

        return usages;
    }

    private static boolean namesPecia(String code) {
        String text = code.strip();
        return text.startsWith("pecia ") || text.startsWith("--");
    }

    /** The repository's Markdown files: those at the root and everything under docs. */
    private static List<Path> documents() throws IOException {
        List<Path> documents = new ArrayList<>();

        try (Stream<Path> root = Files.list(ROOT);
                Stream<Path> docs = Files.walk(ROOT.resolve("docs"))) {
            Stream.concat(root, docs)
                    .filter(path -> path.toString().endsWith(".md") && Files.isRegularFile(path))
                    .sorted()
                    .forEach(documents::add);
        }

        assertTrue(documents.contains(ROOT.resolve("README.md")), "The scan must cover the repository root");
        return documents;
    }

    /** The anchors GitHub gives a document's headings: lower case, punctuation and emoji removed, spaces to hyphens. */
    private static Set<String> anchors(Path document) throws IOException {
        Set<String> anchors = new HashSet<>();
        Matcher heading = HEADING.matcher(FENCED_BLOCK.matcher(read(document)).replaceAll(""));

        while (heading.find()) {
            anchors.add(heading.group(1)
                    .strip()
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}_\\- ]", "")
                    .replace(' ', '-'));
        }

        return anchors;
    }

    /** Every long option of the CLI and its subcommands, with the negated form of negatable ones. */
    private static Set<String> knownOptions() {
        Set<String> known = new HashSet<>();
        List<CommandLine> commands = new ArrayList<>(List.of(new CommandLine(new PeciaCommand(), new Bootstrap())));

        for (int next = 0; next < commands.size(); next++) {
            for (OptionSpec option : commands.get(next).getCommandSpec().options()) {
                for (String name : option.names()) {
                    known.add(name);

                    if (option.negatable() && name.startsWith("--")) {
                        known.add("--no-" + name.substring(2));
                    }
                }
            }

            commands.addAll(commands.get(next).getSubcommands().values());
        }

        return known;
    }

    /** Splits a documented command line into words as a POSIX shell would, for the quoting the docs use. */
    private static List<String> words(String commandLine) {
        List<String> words = new ArrayList<>();
        Matcher word = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(commandLine);

        while (word.find()) {
            words.add(word.group(1) != null ? word.group(1) : word.group(2) != null ? word.group(2) : word.group(3));
        }

        return words;
    }

    private static String read(Path document) throws IOException {
        return Files.readString(document, StandardCharsets.UTF_8);
    }
}
