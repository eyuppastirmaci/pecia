package dev.eyuppastirmaci.pecia.index;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GlobFilter {

    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;
    private final List<PathMatcher> excludedDirectories;

    public GlobFilter(List<String> includeGlobs, List<String> excludeGlobs) {
        this.includeMatchers = compile(includeGlobs);
        this.excludeMatchers = compile(excludeGlobs);

        // Remove subtree suffixes to identify directories that can be skipped before visiting their children.
        this.excludedDirectories = compile(excludeGlobs.stream()
                                                       .filter(glob -> glob.endsWith("/**"))
                                                       .map(glob -> glob.substring(0, glob.length() - 3)).toList());
    }

    /**
     * Decides whether a path passes the include/exclude filters.
     *
     * @param relative path relative to the walked root
     * @return true if the path is included and not excluded
     * @throws NullPointerException if relative is null
     */
    public boolean matches(Path relative) {
        relative = folded(relative);

        if (anyMatch(excludeMatchers, relative)) {

            return false;
        }

        // An empty include list means "include everything".
        return includeMatchers.isEmpty() || anyMatch(includeMatchers, relative);
    }

    /**
     * Reports whether a directory is excluded as an entire subtree.
     *
     * @param relative directory path relative to the project root
     * @return true when an exclude glob rejects the whole directory subtree
     * @throws NullPointerException if relative is null
     */
    public boolean excludesDirectory(Path relative) {

        return anyMatch(excludedDirectories, folded(relative));
    }

    private static Path folded(Path path) {

        return path.getFileSystem().getPath(path.toString().toLowerCase(Locale.ROOT));
    }

    private static boolean anyMatch(List<PathMatcher> matchers, Path relative) {

        for (PathMatcher matcher : matchers) {

            if (matcher.matches(relative)) {

                return true;
            }
        }

        return false;
    }

    /* Compiles case-folded glob patterns and adds root-level variants for recursive patterns. */
    private static List<PathMatcher> compile(List<String> globs) {
        FileSystem fs = FileSystems.getDefault();

        List<PathMatcher> matchers = new ArrayList<>();

        for (String glob : globs) {
            // Matching is case-insensitive on every OS; never change the returned source path.
            glob = glob.toLowerCase(Locale.ROOT);
            matchers.add(fs.getPathMatcher("glob:" + glob));

            // A glob like "**/*.md" never matches a root-level "README.md", so also match the part after "**/".
            if (glob.startsWith("**/")) {
                matchers.add(fs.getPathMatcher("glob:" + glob.substring(3)));
            }
        }

        return matchers;
    }
}
