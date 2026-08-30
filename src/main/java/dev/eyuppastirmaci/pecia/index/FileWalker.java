package dev.eyuppastirmaci.pecia.index;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class FileWalker {

    private static final Set<String> ALWAYS_SKIPPED_DIRS = Set.of(".git", ".pecia");

    private final GlobFilter filter;

    public FileWalker(GlobFilter filter) {
        this.filter = filter;
    }

    /**
     * Walks the folder and collects the files worth indexing.
     *
     * @param root folder to walk
     * @return matching files as sorted paths relative to the root
     * @throws IOException if the walk cannot start or a .gitignore cannot be read
     */
    public List<Path> walk(Path root) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();

        List<Path> files = new ArrayList<>();

        GitignoreStack gitignore = new GitignoreStack();

        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(normalizedRoot)) {
                    if (ALWAYS_SKIPPED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (gitignore.isIgnored(dir, true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                gitignore.enter(dir);

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

                if (filter.matches(relative)) {
                    files.add(relative);
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // One unreadable file must not kill the walk.
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                gitignore.leave(dir);

                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(Path::toString));

        return files;
    }
}
