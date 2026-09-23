package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plans missing-file cleanup without modifying storage or following symbolic links. */
final class IndexDeletionPlanner {

    private final ProjectContext context;
    private final GlobFilter filter;

    IndexDeletionPlanner(ProjectContext context) {
        var config = context.loadedConfig().config();
        this.context = context;
        this.filter = new GlobFilter(config.include(), config.exclude());
    }

    /**
     * Returns an immutable, path-sorted list of absent manifest entries in the scanned scope.
     * Discovered candidates are retained even if extraction failed. Excluded paths and paths replaced
     * by directories or links are retained. A stored spelling below the target that now exists only with a
     * different case or Unicode normalization is absent. The caller must revalidate before applying this advisory
     * plan because filesystem state can change after detection.
     *
     * @throws IOException if discovery is incomplete, the target is no longer a real directory, or
     *     filesystem access cannot establish whether a path is absent
     */
    List<StoredFile> plan(WalkResult scan, List<StoredFile> storedFiles) throws IOException {
        List<StoredFile> manifest = List.copyOf(storedFiles);

        if (!scan.complete()) {
            throw new IOException("Deletion planning requires a complete scan");
        }

        Set<Path> discovered = new HashSet<>();

        for (Path candidate : scan.files()) {
            discovered.add(context.sourcePath(candidate));
        }

        requireTargetDirectory();
        // Listings are cached per plan so the caller's revalidation observes the filesystem again.
        Map<Path, Set<String>> listings = new HashMap<>();
        List<StoredFile> missing = new ArrayList<>();

        for (StoredFile file : manifest) {
            Path source = context.projectRoot().resolve(file.sourcePath());

            if (!source.startsWith(context.target())
                    || source.equals(context.target())
                    || discovered.contains(file.sourcePath())
                    || context.storageFiles().contains(source)
                    || !filter.matches(file.sourcePath())) {
                continue;
            }

            if (isMissingSource(source, listings)) {
                missing.add(file);
            }
        }

        missing.sort(Comparator.comparing(file -> FileWalker.portablePath(file.sourcePath())));

        return List.copyOf(missing);
    }

    private boolean isMissingSource(Path source, Map<Path, Set<String>> listings) throws IOException {
        GitignoreStack gitignore = new GitignoreStack();
        boolean missingDirectory = false;

        for (Path directory = context.projectRoot(); !directory.equals(source); ) {
            if (FileWalker.isReserved(directory)
                    || filter.excludesDirectory(context.projectRoot().relativize(directory))
                    || gitignore.isIgnored(directory, true)) {
                return false;
            }

            if (!missingDirectory) {
                try {
                    if (!readSpelledAttributes(directory, listings).isDirectory()) {
                        return false;
                    }
                } catch (NoSuchFileException absent) {
                    // Losing the target or its ancestors invalidates the scan, not every record.
                    if (!directory.startsWith(context.target()) || directory.equals(context.target())) {
                        throw absent;
                    }

                    missingDirectory = true;
                }

                if (!missingDirectory) {
                    gitignore.enter(directory);
                }
            }

            directory = directory.resolve(directory.relativize(source).getName(0));
        }

        if (gitignore.isIgnored(source, false)) {
            return false;
        }

        if (missingDirectory) {
            return true;
        }

        try {
            readSpelledAttributes(source, listings);

            return false;
        } catch (NoSuchFileException absent) {
            return true;
        }
    }

    /**
     * Reads attributes only if the stored spelling exists below the target. Case- or normalization-insensitive
     * filesystems otherwise resolve an old spelling to a renamed entry, which would retain a stale duplicate. The
     * target and its ancestors keep the caller's spelling, which discovery also used for the stored paths.
     */
    private BasicFileAttributes readSpelledAttributes(Path path, Map<Path, Set<String>> listings) throws IOException {
        BasicFileAttributes attributes = readAttributes(path);

        if (path.startsWith(context.target())
                && !path.equals(context.target())
                && !listedNames(path.getParent(), listings)
                        .contains(path.getFileName().toString())) {
            throw new NoSuchFileException(path.toString(), null, "Only a differently spelled entry exists");
        }

        return attributes;
    }

    private static Set<String> listedNames(Path directory, Map<Path, Set<String>> listings) throws IOException {
        Set<String> names = listings.get(directory);

        if (names == null) {
            names = new HashSet<>();

            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                for (Path entry : entries) {
                    names.add(entry.getFileName().toString());
                }
            }

            listings.put(directory, names);
        }

        return names;
    }

    private void requireTargetDirectory() throws IOException {
        Path directory = context.target().getRoot();

        for (Path part : context.target()) {
            directory = directory.resolve(part);

            if (!readAttributes(directory).isDirectory()) {
                throw new IOException("Deletion planning requires a real directory: " + directory);
            }
        }
    }

    private BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }
}
