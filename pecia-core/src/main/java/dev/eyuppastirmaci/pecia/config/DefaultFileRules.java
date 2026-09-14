package dev.eyuppastirmaci.pecia.config;

import dev.eyuppastirmaci.pecia.content.DocumentType;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Candidate discovery rules, not a declaration that content extraction is implemented. */
public final class DefaultFileRules {
    public static final List<String> EXTENSIONS = Arrays.stream(DocumentType.values())
                                                        .flatMap(type -> type.extensions().stream()).toList();
    public static final List<String> BASENAMES = Arrays.stream(DocumentType.values())
                                                       .flatMap(type -> type.basenames().stream()).toList();
    public static final List<String> EXCLUDED_DIRECTORIES = List.of(
            "target", "build", "dist", "out", "node_modules", ".gradle", ".venv", "venv",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".next");
    public static final List<String> EXCLUDED_FILES = List.of(
            "*.min.js", "*.min.css", "*.map", "package-lock.json", "npm-shrinkwrap.json",
            "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb", "composer.lock", "Cargo.lock",
            "poetry.lock", "uv.lock", "Pipfile.lock", "Gemfile.lock", "gradle.lockfile",
            ".env", ".env.*");

    private DefaultFileRules() { }

    /**
     * Builds the default globs that admit supported document names.
     *
     * @return the immutable default include globs
     */
    public static List<String> includes() {

        return Stream.concat(EXTENSIONS.stream().map(ext -> "**/*." + ext),
                             BASENAMES.stream().map(name -> "**/" + name)).toList();
    }

    /**
     * Builds the default globs that reject generated, dependency, and sensitive files.
     *
     * @return the immutable default exclude globs
     */
    public static List<String> excludes() {

        return Stream.concat(EXCLUDED_DIRECTORIES.stream().map(dir -> "**/" + dir + "/**"),
                             EXCLUDED_FILES.stream().map(name -> "**/" + name)).toList();
    }
}
