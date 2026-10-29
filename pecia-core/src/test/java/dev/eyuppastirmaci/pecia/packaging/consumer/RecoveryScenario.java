package dev.eyuppastirmaci.pecia.packaging.consumer;

import static dev.eyuppastirmaci.pecia.packaging.consumer.ConsumerChecks.check;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexOptions;
import dev.eyuppastirmaci.pecia.index.IndexResult;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.nio.file.Path;

/**
 * Indexes a project the packaging test prepared, so that the test can kill the process mid-run and run it again; the
 * semantic run uses the tiny model the test places beside the project.
 */
final class RecoveryScenario {

    private RecoveryScenario() {}

    static void index(Path root, boolean semantic) throws Exception {
        ModelOutcome tiny = new ModelOutcome(
                ModelOutcome.Status.CACHED, SemanticModel.MINILM, root.resolveSibling(EmbeddingScenario.MODEL_FILE));

        IndexResult result = IndexService.withModelProvider(
                        new PeciaConfigLoader(new PeciaConfigParser()), listener -> tiny)
                .index(root, IndexOptions.semantic(semantic));

        check(result.status() == IndexResult.Status.COMPLETE, "Packaged indexing must complete: " + result.status());
    }
}
