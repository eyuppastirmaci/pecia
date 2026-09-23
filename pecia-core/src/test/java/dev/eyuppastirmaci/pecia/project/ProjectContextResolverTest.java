package dev.eyuppastirmaci.pecia.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexPreview;
import dev.eyuppastirmaci.pecia.index.IndexService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectContextResolverTest {

    @TempDir
    Path root;

    private final PeciaConfigLoader loader = new PeciaConfigLoader(new PeciaConfigParser());
    private final ProjectContextResolver resolver = new ProjectContextResolver(loader);

    @BeforeEach
    void useCanonicalRoot() throws IOException {
        // Resolved contexts use on-disk spellings; Windows runners may expose temp directories by short names.
        root = root.toRealPath();
    }

    @Test
    void defaultsUseTheTargetAndDoNotCreateAnything() throws IOException {
        Path target = Files.createDirectory(root.resolve("project"));
        ProjectContext context = resolver.resolve(target.resolve("unused/.."));

        assertEquals(target, context.target());
        assertEquals(target, context.projectRoot());
        assertFalse(context.loadedConfig().fromFile());
        assertEquals(target.resolve(".pecia/index.db"), context.databasePath());

        try (Stream<Path> files = Files.list(target)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void childAndRootResolveTheSameConfigAndStoreWithoutUsingWorkingDirectory() throws IOException {
        config("cache/../indexes/search.db");
        Path child = Files.createDirectories(root.resolve("src/ödeme modülü"));
        ProjectContext fromRoot = resolver.resolve(root);
        ProjectContext context = resolver.resolve(child);

        assertEquals(root, context.projectRoot());
        assertEquals(root.resolve("indexes/search.db"), context.databasePath());
        assertEquals(fromRoot.databasePath(), context.databasePath());
        assertEquals(fromRoot.loadedConfig(), context.loadedConfig());
        assertEquals(child.resolve("Kullanıcı.java"), context.absoluteSource(Path.of("Kullanıcı.java")));
        assertEquals(Path.of("src/ödeme modülü/Kullanıcı.java"), context.sourcePath(Path.of("Kullanıcı.java")));
        assertFalse(Files.exists(root.resolve("indexes")));
    }

    @Test
    void gitRootSuppliesDefaultsAndNearestConfigOverridesIt() throws IOException {
        Files.createDirectory(root.resolve(".git"));
        Path child = Files.createDirectories(root.resolve("nested/src"));

        assertEquals(root, resolver.resolve(child).projectRoot());
        assertEquals(root.resolve(".pecia/index.db"), resolver.resolve(child).databasePath());

        config("outer.db");
        Files.writeString(root.resolve("nested/.pecia.toml"), "[store]\npath = 'inner.db'\n");
        ProjectContext context = resolver.resolve(child);

        assertEquals(root.resolve("nested"), context.projectRoot());
        assertEquals(root.resolve("nested/inner.db"), context.databasePath());
        assertEquals(root.resolve("outer.db"), resolver.resolve(root).databasePath());
    }

    @Test
    void supportsAbsoluteStoreOutsideProjectWithoutCreatingItsParent() throws IOException {
        Path project = Files.createDirectory(root.resolve("project"));
        Path database = root.resolve("external/index.db");
        Files.writeString(project.resolve(".pecia.toml"), "[store]\npath = '" + database + "'\n");
        ProjectContext context = resolver.resolve(project);

        assertEquals(database, context.databasePath());
        assertEquals(project, context.projectRoot());
        assertFalse(Files.exists(database.getParent()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "../escape.java", "sub/../file.java"})
    void rejectsInvalidDiscoveryCandidates(String value) throws IOException {
        ProjectContext context = resolver.resolve(root);

        assertThrows(IllegalArgumentException.class, () -> context.absoluteSource(Path.of(value)));
        assertThrows(IllegalArgumentException.class, () -> context.sourcePath(Path.of(value)));
    }

    @Test
    void rejectsAbsoluteAndNullCandidatesAndInvalidContextPaths() throws IOException {
        ProjectContext context = resolver.resolve(root);

        assertThrows(IllegalArgumentException.class, () -> context.sourcePath(root.resolve("x.java")));
        assertThrows(NullPointerException.class, () -> context.absoluteSource(null));
        assertThrows(NullPointerException.class, () -> new ProjectContextResolver(null));
        assertThrows(NullPointerException.class, () -> resolver.resolve(null));
        assertThrows(NullPointerException.class, () -> new ProjectContext(root, null, context.databasePath()));
        assertThrows(
                NullPointerException.class,
                () -> new ProjectContext(null, context.loadedConfig(), context.databasePath()));
        assertThrows(NullPointerException.class, () -> new ProjectContext(root, context.loadedConfig(), null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectContext(root.getParent(), context.loadedConfig(), context.databasePath()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectContext(Path.of("relative"), context.loadedConfig(), context.databasePath()));
    }

    @Test
    void rejectsMissingOrFileTargetsAndInvalidStoreWithoutWriting() throws IOException {
        assertThrows(IOException.class, () -> resolver.resolve(root.resolve("missing")));

        Path file = Files.writeString(root.resolve("file.txt"), "unchanged");

        assertThrows(IOException.class, () -> resolver.resolve(file));

        config(".");

        assertThrows(IOException.class, () -> resolver.resolve(root));
        assertEquals("unchanged", Files.readString(file));
        assertFalse(Files.exists(root.resolve(".pecia")));
    }

    @Test
    void excludesCustomDatabaseAndSidecarsButKeepsSimilarNamesAndTargetRelativePresentation() throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), """
            [index]
            include = ["**/*"]
            exclude = []
            [store]
            path = "data/index.txt"
            """);
        Path target = Files.createDirectory(root.resolve("data"));

        for (String name : List.of(
                "index.txt", "index.txt-wal", "index.txt-shm", "index.txt-journal", "index.txt.backup", "keep.md")) {
            Files.writeString(target.resolve(name), name);
        }

        ProjectContext context = resolver.resolve(target);

        assertEquals(
                Set.of(
                        target.resolve("index.txt"),
                        target.resolve("index.txt-wal"),
                        target.resolve("index.txt-shm"),
                        target.resolve("index.txt-journal")),
                context.storageFiles());

        assertThrows(
                UnsupportedOperationException.class,
                () -> context.storageFiles().clear());

        IndexPreview preview = new IndexService(loader).preview(target);

        assertEquals(
                List.of(Path.of("index.txt.backup"), Path.of("keep.md")),
                preview.walkResult().files());
        assertTrue(preview.walkResult().complete());

        for (Path path : context.storageFiles()) {
            assertEquals(path.getFileName().toString(), Files.readString(path));
        }
    }

    @Test
    void canonicalizesStoreAliasesButRejectsLinkedTargets() throws IOException {
        Path real = Files.createDirectory(root.resolve("data"));
        Path alias = root.resolve("alias");

        try {
            Files.createSymbolicLink(alias, real);
        } catch (UnsupportedOperationException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + unavailable.getMessage());
        }

        Files.writeString(real.resolve("index.txt"), "database");
        Files.writeString(real.resolve("index.txt-wal"), "wal");
        config("alias/index.txt");
        ProjectContext context = resolver.resolve(root);

        assertEquals(real.resolve("index.txt"), context.databasePath());
        assertFalse(new IndexService(loader).preview(root).walkResult().files().contains(Path.of("data/index.txt")));
        assertThrows(IOException.class, () -> resolver.resolve(alias));
    }

    @ParameterizedTest
    @CsvSource({"Src/Docs, src/docs", "Src/Ödeme, Src/Ödeme"})
    void childTargetsTakeTheirOnDiskSpelling(String onDisk, String typed) throws IOException {
        config(".pecia/index.db");
        Path child = Files.createDirectories(root.resolve(onDisk));
        Path alias = root.resolve(typed);
        assumeTrue(Files.isDirectory(alias), "Filesystem distinguishes these spellings");

        ProjectContext context = resolver.resolve(alias);

        assertEquals(child, context.target());
        assertEquals(root, context.projectRoot());
        assertEquals(Path.of(onDisk, "a.txt"), context.sourcePath(Path.of("a.txt")));
    }

    private void config(String store) throws IOException {
        Files.writeString(root.resolve(".pecia.toml"), "[store]\npath = '" + store + "'\n");
    }
}
