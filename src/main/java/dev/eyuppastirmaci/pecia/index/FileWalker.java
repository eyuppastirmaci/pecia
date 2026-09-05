package dev.eyuppastirmaci.pecia.index;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Locale;

public final class FileWalker {

    private static final Set<String> ALWAYS_SKIPPED_DIRS = Set.of(".git", ".pecia");

    private final GlobFilter filter;

    public FileWalker(GlobFilter filter) {
        this.filter = filter;
    }

    /**
     * Scans a root and fails when any part of the traversal is incomplete.
     *
     * @param root directory to scan and use as the project root
     * @return matching paths relative to root
     * @throws NullPointerException if root is null
     * @throws IOException if root is invalid, traversal fails, or the scan is incomplete
     */
    public List<Path> walk(Path root) throws IOException {
        WalkResult result = scan(root, root);

        if (!result.complete()) {
            throw new IOException("Incomplete scan: " + result.issues());
        }

        return result.files();
    }

    /**
     * Scans a target with project-relative filters without following symbolic links.
     *
     * @param target directory whose descendants are scanned
     * @param projectRoot project directory against which ignore and glob rules are evaluated
     * @return target-relative matching paths and recoverable traversal issues
     * @throws NullPointerException if target or projectRoot is null
     * @throws IOException if either path is invalid or traversal cannot start
     */
    public WalkResult scan(Path target, Path projectRoot) throws IOException {
        Path normalizedRoot = target.toAbsolutePath().normalize();
        Path project = projectRoot.toAbsolutePath().normalize();

        if (!normalizedRoot.startsWith(project)) {
            throw new IOException("Target must be inside project root: " + normalizedRoot);
        }

        validateDirectory(normalizedRoot);
        List<Path> files = new ArrayList<>();
        List<WalkResult.Issue> issues = new ArrayList<>();
        GitignoreStack gitignore = new GitignoreStack();

        // Load inherited rules and check each ancestor: a child cannot revive an ignored directory.
        for (Path dir = project; !dir.equals(normalizedRoot); ) {
            validateDirectory(dir);

            if (isReserved(dir) || filter.excludesDirectory(project.relativize(dir))
                    || gitignore.isIgnored(dir, true)) {

                return new WalkResult(files, issues);
            }

            try {
                gitignore.enter(dir);
            } catch (IOException failure) {
                issues.add(new WalkResult.Issue(dir.resolve(".gitignore"), failure.getMessage()));

                return new WalkResult(files, issues);
            }

            // Advance one path component so each ancestor's ignore rules are loaded in order.
            dir = dir.resolve(dir.relativize(normalizedRoot).getName(0));
        }

        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {

                if (isReserved(dir) || filter.excludesDirectory(project.relativize(dir))
                        || gitignore.isIgnored(dir, true)) {

                    return FileVisitResult.SKIP_SUBTREE;
                }

                try {
                    gitignore.enter(dir);
                } catch (IOException failure) {
                    issues.add(new WalkResult.Issue(dir.resolve(".gitignore"), failure.getMessage()));

                    return FileVisitResult.SKIP_SUBTREE;
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {

                if (!attrs.isRegularFile()) {

                    return FileVisitResult.CONTINUE;
                }

                if (gitignore.isIgnored(file, false)) {

                    return FileVisitResult.CONTINUE;
                }

                Path relative = normalizedRoot.relativize(file);

                if (filter.matches(project.relativize(file))) {
                    files.add(relative);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {

                if (file.equals(normalizedRoot)) {
                    throw exc;
                }

                issues.add(new WalkResult.Issue(file, exc.getMessage()));

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                gitignore.leave(dir);

                if (exc != null) {
                    issues.add(new WalkResult.Issue(dir, exc.getMessage()));
                }

                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(FileWalker::portablePath));
        issues.sort(Comparator.comparing(issue -> portablePath(issue.path())));

        return new WalkResult(files, issues);
    }

    private static boolean isReserved(Path path) {

        return path.getFileName() != null
                && ALWAYS_SKIPPED_DIRS.contains(path.getFileName().toString().toLowerCase(Locale.ROOT));
    }

    /**
     * Converts a path to a host-independent forward-slash representation.
     *
     * @param path path to render
     * @return path components joined with forward slashes
     * @throws NullPointerException if path is null
     */
    public static String portablePath(Path path) {

        // Join individual path components so display and sorting do not depend on the host separator.
        return String.join("/", java.util.stream.StreamSupport.stream(path.spliterator(), false)
                                                              .map(Path::toString).toList());
    }

    /* Verifies that a traversal root is a real directory reached without any symbolic-link component. */
    private static void validateDirectory(Path path) throws IOException {

        // Reject links anywhere in the supplied path, including an intermediate linked directory.
        for (Path part = path; part != null; part = part.getParent()) {

            if (Files.isSymbolicLink(part)) {
                throw new IOException("Symbolic-link targets are not supported: " + part);
            }
        }

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

        if (!attrs.isDirectory()) {
            throw new IOException("Target must be a directory: " + path);
        }
    }
}
