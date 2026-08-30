package dev.eyuppastirmaci.pecia.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileWalkerTest {

    @TempDir
    Path root;

    @Test
    void walksRecursivelyAndReturnsSortedRelativePaths() throws IOException {
        write("b.md");
        write("a/two.md");
        write("a/one.md");

        List<Path> result = new FileWalker(new GlobFilter(List.of(), List.of())).walk(root);

        assertEquals(paths("a/one.md", "a/two.md", "b.md"), result);
    }

    @Test
    void appliesIncludeGlobsIncludingRootLevelFiles() throws IOException {
        write("README.md");
        write("notes.txt");
        write("src/Main.java");

        List<Path> result = new FileWalker(new GlobFilter(List.of("**/*.md"), List.of())).walk(root);

        assertEquals(paths("README.md"), result);
    }

    @Test
    void appliesExcludeGlobsIncludingRootLevelDirs() throws IOException {
        write("target/generated.md");
        write("docs/keep.md");

        List<Path> result = new FileWalker(new GlobFilter(List.of(), List.of("**/target/**"))).walk(root);

        assertEquals(paths("docs/keep.md"), result);
    }

    @Test
    void honorsRootGitignore() throws IOException {
        write(".gitignore", "ignored/\nsecret.md\n");
        write("ignored/inside.md");
        write("secret.md");
        write("kept.md");

        List<Path> result = new FileWalker(new GlobFilter(List.of("**/*.md"), List.of())).walk(root);

        assertEquals(paths("kept.md"), result);
    }

    @Test
    void honorsNestedGitignoreOnlyBelowItsDirectory() throws IOException {
        write("sub/.gitignore", "local.md\n");
        write("sub/local.md");
        write("sub/kept.md");
        write("local.md");

        List<Path> result = new FileWalker(new GlobFilter(List.of("**/*.md"), List.of())).walk(root);

        assertEquals(paths("local.md", "sub/kept.md"), result);
    }

    @Test
    void honorsNegationRules() throws IOException {
        write(".gitignore", "*.md\n!keep.md\n");
        write("keep.md");
        write("drop.md");

        List<Path> result = new FileWalker(new GlobFilter(List.of("**/*.md"), List.of())).walk(root);

        assertEquals(paths("keep.md"), result);
    }

    @Test
    void alwaysSkipsGitAndPeciaDirectories() throws IOException {
        write(".git/objects/blob.md");
        write(".pecia/index.md");
        write("kept.md");

        List<Path> result = new FileWalker(new GlobFilter(List.of("**/*.md"), List.of())).walk(root);

        assertEquals(paths("kept.md"), result);
    }

    private void write(String relative) throws IOException {
        write(relative, "content of " + relative);
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);

        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<Path> paths(String... relatives) {
        return java.util.Arrays.stream(relatives).map(Path::of).toList();
    }
}
