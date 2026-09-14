package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.config.PeciaConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryScopeTest {
    @TempDir Path root;

    private FileWalker walker() {
        PeciaConfig config = PeciaConfig.defaults();

        return new FileWalker(new GlobFilter(config.include(), config.exclude()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"txt", "rst", "adoc", "md", "markdown", "java", "kt", "kts", "py", "pyi",
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts", "c", "h", "cc", "cpp", "cxx",
            "hh", "hpp", "hxx", "cs", "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh", "ps1",
            "psm1", "bat", "cmd", "sql", "css", "scss", "sass", "less", "json", "jsonc", "yaml", "yml",
            "toml", "ini", "cfg", "properties", "xml"})
    void discoversEveryPlannedExtensionAtRootAndDepth(String extension) throws IOException {
        Path top = write("sample." + extension);
        Path nested = write("src/nested/SAMPLE." + extension.toUpperCase(Locale.ROOT));
        assertEquals(List.of(root.relativize(top), root.relativize(nested)), walker().walk(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"README", "LICENSE", "LICENCE", "NOTICE", "CHANGELOG", "AUTHORS", "CONTRIBUTING",
            "Dockerfile", "Containerfile", "Makefile", "Jenkinsfile"})
    void discoversExactBasenamesButNotSuffixedNames(String name) throws IOException {
        Path top = write(name);
        Path nested = write("nested/" + name.toLowerCase(Locale.ROOT));
        write(name + ".dev");
        assertEquals(2, walker().walk(root).size());
        assertTrue(walker().walk(root).containsAll(List.of(root.relativize(top), root.relativize(nested))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"target", "build", "dist", "out", "node_modules", ".gradle", ".venv", "venv",
            "__pycache__", ".pytest_cache", ".mypy_cache", ".next", ".git", ".pecia"})
    void prunesExcludedDirectoriesAtEveryDepth(String dir) throws IOException {
        write(dir + "/generated.md");
        write("nested/" + dir.toUpperCase(Locale.ROOT) + "/generated.md");
        write("docs/generated.md");
        assertEquals(List.of(Path.of("docs/generated.md")), walker().walk(root));
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.min.js", "style.min.css", "source.map", "package-lock.json", "npm-shrinkwrap.json",
            "yarn.lock", "pnpm-lock.yaml", "bun.lock", "bun.lockb", "composer.lock", "Cargo.lock", "poetry.lock",
            "uv.lock", "Pipfile.lock", "Gemfile.lock", "gradle.lockfile", ".env", ".env.local.json"})
    void exclusionsWinEvenWithAnEmptyInclude(String name) throws IOException {
        write(name);
        write("nested/" + name.toUpperCase(Locale.ROOT));
        write("src/Clock.java");
        PeciaConfig config = PeciaConfig.defaults();
        FileWalker all = new FileWalker(new GlobFilter(List.of(), config.exclude()));
        assertEquals(List.of(Path.of("src/Clock.java")), all.walk(root));
    }

    @Test
    void deferredFormatsAreNotDefaultCandidatesButCustomGlobsCanOptIn() throws IOException {

        for (String ext : List.of("html", "htm", "svg", "vue", "svelte", "csv", "tsv", "ipynb", "log", "eml",
                "msg", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf",
                "zip", "png", "jpg", "mp3", "wav", "mp4", "mkv")) {
            write("sample." + ext);
        }

        write("unknown");
        assertTrue(walker().walk(root).isEmpty());
        assertEquals(List.of(Path.of("sample.csv")),
                new FileWalker(new GlobFilter(List.of("**/*.csv"), List.of())).walk(root));
    }

    private Path write(String name) throws IOException {
        Path file = root.resolve(name);
        Files.createDirectories(file.getParent());

        return Files.writeString(file, "fixture");
    }
}
