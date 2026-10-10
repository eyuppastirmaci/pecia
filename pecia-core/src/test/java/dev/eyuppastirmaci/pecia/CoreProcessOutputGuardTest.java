package dev.eyuppastirmaci.pecia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.index.IndexService;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeps core free of process-level side effects: callers own presentation and process lifetime, so core production
 * code must not write to standard output or error, print stack traces to them, or terminate the JVM. The scan
 * ignores comments and string, character, and text-block literals.
 */
class CoreProcessOutputGuardTest {

    private static final Pattern FORBIDDEN = Pattern.compile("\\bSystem\\s*\\.\\s*(?:out|err|exit)\\b"
            + "|\\bjava\\s*\\.\\s*lang\\s*\\.\\s*System\\s*\\.\\s*(?:out|err|exit|\\*)"
            + "|\\bRuntime\\s*\\.\\s*getRuntime\\s*\\(\\s*\\)\\s*\\.\\s*(?:exit|halt)\\b"
            + "|\\.\\s*printStackTrace\\s*\\(\\s*\\)");

    @Test
    void coreProductionSourcesNeverWriteToProcessStreamsOrExit() throws Exception {
        Path sources = coreMainSources();
        List<Path> files;

        try (Stream<Path> paths = Files.walk(sources)) {
            files = paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        List<String> violations = new ArrayList<>();

        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);

            for (String violation : violations(source)) {
                violations.add(sources.relativize(file) + ":" + violation);
            }
        }

        assertTrue(
                files.contains(sources.resolve("dev/eyuppastirmaci/pecia/index/IndexService.java")),
                "The scan must cover the core production sources: " + sources);
        assertEquals(List.of(), violations);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "System.out.println(value);",
                "System . err.print(value);",
                "System.exit(1);",
                "java.lang.System.out.println(value);",
                "import static java.lang.System.err;",
                "import static java.lang.System.*;",
                "Runtime.getRuntime().exit(1);",
                "Runtime.getRuntime ( ) . halt(1);",
                "failure.printStackTrace();"
            })
    void flagsEachForbiddenCallWithItsLine(String statement) {
        // The scanner does not parse Java, so the statement can sit on its own line regardless of where it belongs.
        String source = "class Sample {\n    " + statement + "\n}\n";

        assertEquals(List.of("2: " + statement), violations(source));
    }

    @Test
    void ignoresCommentsLiteralsAndPermittedCalls() {
        String source = """
            class Sample {
                // System.out.println(value);
                /* System.exit(1); */
                /** Never calls System.err directly. */
                String text = "System.out.println(\\" quoted \\")";
                String escaped = "\\\\"; String after = "System.exit(0)";
                char quote = '"'; String next = "Runtime.getRuntime().halt(1)";
                String block = \"""
                    System.out.println("inside a text block");
                    \""";
                void report(java.io.PrintWriter writer, Exception failure) {
                    failure.printStackTrace(writer);
                    String systemOut = "out";
                }
            }
            """;

        assertEquals(List.of(), violations(source));
    }

    /* Returns "line: source line" for each forbidden call outside comments and literals. */
    private static List<String> violations(String source) {
        String code = blankCommentsAndLiterals(source);
        String[] codeLines = code.split("\n", -1);
        String[] sourceLines = source.split("\n", -1);
        List<String> violations = new ArrayList<>();

        for (int index = 0; index < codeLines.length; index++) {
            Matcher matcher = FORBIDDEN.matcher(codeLines[index]);

            if (matcher.find()) {
                violations.add((index + 1) + ": " + sourceLines[index].strip());
            }
        }

        return violations;
    }

    /**
     * Replaces comments and string, character, and text-block literals with spaces, keeping line breaks so line
     * numbers still match the original source.
     */
    private static String blankCommentsAndLiterals(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;

        while (index < source.length()) {
            if (source.startsWith("//", index)) {
                index = blankUntil(source, code, index, source.indexOf('\n', index));
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = blankUntil(source, code, index, end < 0 ? -1 : end + 2);
            } else if (source.startsWith("\"\"\"", index)) {
                int end = closingDelimiter(source, index + 3, "\"\"\"");
                index = blankUntil(source, code, index, end < 0 ? -1 : end + 3);
            } else if (source.charAt(index) == '"' || source.charAt(index) == '\'') {
                String quote = String.valueOf(source.charAt(index));
                int end = closingDelimiter(source, index + 1, quote);
                index = blankUntil(source, code, index, end < 0 ? -1 : end + 1);
            } else {
                code.append(source.charAt(index));
                index++;
            }
        }

        return code.toString();
    }

    /* Finds an unescaped delimiter; a backslash escapes exactly the following character. */
    private static int closingDelimiter(String source, int from, String delimiter) {
        for (int index = from; index < source.length(); index++) {
            if (source.charAt(index) == '\\') {
                index++;
            } else if (source.startsWith(delimiter, index)) {
                return index;
            }
        }

        return -1;
    }

    private static int blankUntil(String source, StringBuilder code, int start, int end) {
        int stop = end < 0 ? source.length() : end;

        for (int index = start; index < stop; index++) {
            code.append(source.charAt(index) == '\n' ? '\n' : ' ');
        }

        return stop;
    }

    /* Locates sources from the compiled core classes, so the result does not depend on the working directory. */
    private static Path coreMainSources() throws IOException, URISyntaxException {
        Path classes = Path.of(IndexService.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path sources = classes.getParent().getParent().resolve("src/main/java");
        assertTrue(Files.isDirectory(sources), "Core production sources not found next to " + classes);
        return sources;
    }
}
