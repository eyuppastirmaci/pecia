package dev.eyuppastirmaci.pecia.index;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

public final class GlobFilter {

    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;

    public GlobFilter(List<String> includeGlobs, List<String> excludeGlobs) {
        this.includeMatchers = compile(includeGlobs);
        this.excludeMatchers = compile(excludeGlobs);
    }

    /**
     * Decides whether a path passes the include/exclude filters.
     *
     * @param relative path relative to the walked root
     * @return true if the path is included and not excluded
     */
    public boolean matches(Path relative) {
        if (anyMatch(excludeMatchers, relative)) {
            return false;
        }

        // An empty include list means "include everything".
        return includeMatchers.isEmpty() || anyMatch(includeMatchers, relative);
    }

    private static boolean anyMatch(List<PathMatcher> matchers, Path relative) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative)) {
                return true;
            }
        }

        return false;
    }

    private static List<PathMatcher> compile(List<String> globs) {
        FileSystem fs = FileSystems.getDefault();

        List<PathMatcher> matchers = new ArrayList<>();

        for (String glob : globs) {
            matchers.add(fs.getPathMatcher("glob:" + glob));

            // A glob like "**/*.md" never matches a root-level "README.md", so also match the part after "**/".
            if (glob.startsWith("**/")) {
                matchers.add(fs.getPathMatcher("glob:" + glob.substring(3)));
            }
        }

        return matchers;
    }
}
