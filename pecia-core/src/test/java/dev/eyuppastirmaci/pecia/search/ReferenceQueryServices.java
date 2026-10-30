package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.semantic.ModelCache;
import dev.eyuppastirmaci.pecia.semantic.VectorExtensionAccess;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.nio.file.Path;

/** Query services for the reference checks, which use the real model but must not write into the cache holding it. */
public final class ReferenceQueryServices {

    private ReferenceQueryServices() {}

    /**
     * Returns a service that finds the model in the cache {@code PECIA_CACHE_DIR} names and places the vector extension
     * in {@code extensionCache} instead.
     */
    public static QueryService withVectorExtensionIn(PeciaConfigLoader loader, Path extensionCache) {
        return new QueryService(
                loader,
                context -> SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot()),
                () -> new ModelCache().find(),
                () -> VectorExtensionAccess.library(extensionCache),
                () -> EmbeddingProfile.minilm());
    }
}
