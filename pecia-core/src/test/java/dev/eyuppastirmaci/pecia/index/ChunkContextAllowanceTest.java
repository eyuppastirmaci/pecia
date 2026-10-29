package dev.eyuppastirmaci.pecia.index;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Chunks leave room for the context line of their embedding input, so the model never cuts their content. */
class ChunkContextAllowanceTest {

    @TempDir
    Path sandbox;

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();
    private Path root;

    @BeforeEach
    void writeALongFile() throws Exception {
        root = Files.createDirectories(sandbox.resolve("project")).toRealPath();
        Files.writeString(root.resolve("long.txt"), "word ".repeat(3000));
    }

    @Test
    void theDefaultBudgetShrinksByTheContextAllowance() throws Exception {
        List<Integer> sizes = chunkSizes();
        int limit = tokenizer.identity().maxInputTokens() - EmbeddingInput.CONTEXT_TOKENS;

        assertTrue(sizes.stream().allMatch(size -> size <= limit), sizes.toString());
        assertTrue(sizes.stream().anyMatch(size -> size > limit - 8), "Chunks still fill the reduced budget");
    }

    @Test
    void aSmallerConfiguredBudgetAlreadyLeavesRoomAndIsKept() throws Exception {
        Files.writeString(root.resolve(".pecia.toml"), "[chunk]\nmax_tokens = 128\noverlap_tokens = 16\n");

        List<Integer> sizes = chunkSizes();

        assertTrue(sizes.stream().allMatch(size -> size <= 128), sizes.toString());
        assertTrue(sizes.stream().anyMatch(size -> size > 120), "The configured budget is not reduced further");
    }

    /** Indexes the project and returns each stored chunk's model-input size. */
    private List<Integer> chunkSizes() throws Exception {
        IndexResult result = new IndexService(new PeciaConfigLoader(new PeciaConfigParser())).index(root);
        List<Integer> sizes = new ArrayList<>();

        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + result.context().databasePath());
                var rows = connection.createStatement().executeQuery("SELECT content FROM chunks")) {
            while (rows.next()) {
                sizes.add(tokenizer.countModelInput(rows.getString(1)));
            }
        }

        return sizes;
    }
}
