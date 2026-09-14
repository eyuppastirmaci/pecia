package dev.eyuppastirmaci.pecia.index;

import java.nio.file.Path;
import java.util.List;

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

    public record Issue(Path path, String reason) { }
}
