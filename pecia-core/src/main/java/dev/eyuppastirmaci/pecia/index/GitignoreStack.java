package dev.eyuppastirmaci.pecia.index;

import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.ignore.IgnoreNode.MatchResult;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;

public final class GitignoreStack {

    private final Deque<GitignoreScope> scopes = new ArrayDeque<>();

    /**
     * Enters a directory, bringing its .gitignore into scope if it has one.
     *
     * @param dir directory being entered
     * @throws NullPointerException if dir is null
     * @throws IOException if the directory's .gitignore cannot be read
     */
    public void enter(Path dir) throws IOException {
        Path gitignore = dir.resolve(".gitignore");

        BasicFileAttributes attributes;

        try {
            attributes = Files.readAttributes(gitignore, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {

            return;
        }

        if (!attributes.isRegularFile()) {
            throw new IOException(".gitignore must be a regular file (links are not followed): " + gitignore);
        }

        IgnoreNode node = new IgnoreNode();

        try (InputStream in = Files.newInputStream(gitignore)) {
            node.parse(in);
        }

        scopes.push(new GitignoreScope(dir, node));
    }

    /**
     * Leaves a directory, dropping its .gitignore scope if one was entered.
     *
     * @param dir directory being left
     */
    public void leave(Path dir) {

        if (!scopes.isEmpty() && scopes.peek().dir().equals(dir)) {
            scopes.pop();
        }
    }

    /**
     * Decides whether a path is ignored by the .gitignore files currently in scope.
     *
     * @param path path under the most recently entered directory
     * @param isDirectory whether the path is a directory
     * @return true if a rule in scope ignores the path
     * @throws NullPointerException if path is null
     */
    public boolean isIgnored(Path path, boolean isDirectory) {

        // The nearest .gitignore wins, so ask the deepest scope first and stop at the first definitive answer.
        for (GitignoreScope scope : scopes) {
            String relative = toGitPath(scope.dir().relativize(path));

            MatchResult result = scope.node().isIgnored(relative, isDirectory);

            if (result == MatchResult.IGNORED) {

                return true;
            }

            if (result == MatchResult.NOT_IGNORED) {

                return false;
            }
        }

        return false;
    }

    private static String toGitPath(Path relative) {

        // Gitignore rules always use forward slashes, even on Windows.
        return relative.toString().replace('\\', '/');
    }

    private record GitignoreScope(Path dir, IgnoreNode node) {
    }
}
