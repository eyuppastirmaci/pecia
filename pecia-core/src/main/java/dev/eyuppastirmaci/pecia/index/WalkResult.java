package dev.eyuppastirmaci.pecia.index;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Issues make a scan incomplete; missing paths must not then be treated as deletions. */
public record WalkResult(List<Path> files, List<Issue> issues) {
    public WalkResult {
        files = List.copyOf(files);
        issues = List.copyOf(issues);
    }

    /**
     * Reports whether traversal completed without recoverable issues.
     *
     * @return true when the issue list is empty
     */
    public boolean complete() {
        return issues.isEmpty();
    }

    /**
     * A path that discovery could not read, so files below it may be missing from the scan. When the path is a
     * {@code .gitignore} file, its directory was skipped, since its ignore rules are unknown.
     *
     * @param cause why the path could not be read
     * @param reason diagnostic message, or null when the filesystem provided none
     */
    public record Issue(Path path, Cause cause, String reason) {

        /** Requires a path and a cause. */
        public Issue {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(cause, "cause");
        }

        /** Classifies a failure to read the path by its exception type. */
        static Issue of(Path path, IOException failure) {
            return new Issue(path, Cause.of(failure), failure.getMessage());
        }
    }

    /** Why discovery could not read a path. */
    public enum Cause {

        /** The operating system denied access. */
        PERMISSION_DENIED,
        /** The path disappeared while discovery was reading it. */
        NOT_FOUND,
        /** Another input or output error. */
        IO_ERROR;

        static Cause of(IOException failure) {
            if (failure instanceof AccessDeniedException) {
                return PERMISSION_DENIED;
            }

            if (failure instanceof NoSuchFileException) {
                return NOT_FOUND;
            }

            return IO_ERROR;
        }
    }
}
