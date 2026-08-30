package dev.eyuppastirmaci.pecia.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitignoreStackTest {

    @TempDir
    Path root;

    private final GitignoreStack stack = new GitignoreStack();

    @Test
    void ignoresPathsMatchedByARuleInScope() throws IOException {
        gitignore(root, "secret.md\n");

        stack.enter(root);

        assertTrue(stack.isIgnored(root.resolve("secret.md"), false));
        assertFalse(stack.isIgnored(root.resolve("kept.md"), false));
    }

    @Test
    void negationRuleWinsOverEarlierRule() throws IOException {
        gitignore(root, "*.md\n!keep.md\n");

        stack.enter(root);

        assertFalse(stack.isIgnored(root.resolve("keep.md"), false));
        assertTrue(stack.isIgnored(root.resolve("drop.md"), false));
    }

    @Test
    void nearestGitignoreWinsOverOuterOne() throws IOException {
        Path sub = Files.createDirectories(root.resolve("sub"));
        gitignore(root, "*.md\n");
        gitignore(sub, "!keep.md\n");

        stack.enter(root);
        stack.enter(sub);

        assertFalse(stack.isIgnored(sub.resolve("keep.md"), false));
        assertTrue(stack.isIgnored(sub.resolve("other.md"), false));
    }

    @Test
    void leavingADirectoryDropsItsRules() throws IOException {
        gitignore(root, "secret.md\n");

        stack.enter(root);
        stack.leave(root);

        assertFalse(stack.isIgnored(root.resolve("secret.md"), false));
    }

    @Test
    void enteringADirectoryWithoutGitignoreAddsNoScope() throws IOException {
        stack.enter(root);

        assertFalse(stack.isIgnored(root.resolve("anything.md"), false));
    }

    private void gitignore(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve(".gitignore"), content);
    }
}
