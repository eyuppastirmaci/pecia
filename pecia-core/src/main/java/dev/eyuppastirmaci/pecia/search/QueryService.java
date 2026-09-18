package dev.eyuppastirmaci.pecia.search;

import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.project.ProjectContext;
import dev.eyuppastirmaci.pecia.project.ProjectContextResolver;
import dev.eyuppastirmaci.pecia.storage.sqlite.IndexAccessException;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqliteStorage;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Resolves project configuration and queries an existing index without scanning source files. */
public final class QueryService {

    private final ProjectContextResolver contextResolver;
    private final StorageOpener storageOpener;

    /** Creates a service that resolves configuration and opens existing indexes read-only. */
    public QueryService(PeciaConfigLoader configLoader) {
        this(configLoader, context -> SqliteStorage.openReadOnly(context.databasePath(), context.projectRoot()));
    }

    QueryService(PeciaConfigLoader configLoader, StorageOpener storageOpener) {
        contextResolver = new ProjectContextResolver(configLoader);
        this.storageOpener = Objects.requireNonNull(storageOpener, "storageOpener");
    }

    /**
     * Resolves the project from a starting directory and searches its complete existing index. The
     * starting directory does not filter hits to a subtree. No source content is reread. Owns and
     * closes the read-only connection on success and failure; never creates or upgrades an index.
     * Even punctuation-only queries require a valid existing index, then return an empty list.
     *
     * @param startDirectory project context starting directory, usually the caller's working
     *     directory
     * @param request the same validated plain-text request accepted by LexicalSearch
     * @return immutable ranked hits; no matches is a successful empty result
     * @throws QueryException if context/index access or retrieval fails, preserving the cause
     * @throws IllegalArgumentException if query/configuration/path validation fails
     * @throws NullPointerException if a required argument is null
     */
    public List<SearchHit> search(Path startDirectory, SearchRequest request) throws QueryException {
        // Apply the existing compiler's limits before any filesystem or database access.
        new LexicalQueryCompiler().compile(request);

        try {
            ProjectContext context = contextResolver.resolve(startDirectory);

            try (SqliteStorage storage = storageOpener.open(context)) {
                return storage.lexicalSearch().search(request);
            }
        } catch (IndexAccessException failure) {
            QueryException.Reason reason =
                    switch (failure.reason()) {
                        case NOT_FOUND -> QueryException.Reason.INDEX_NOT_FOUND;
                        case MIGRATION_REQUIRED -> QueryException.Reason.MIGRATION_REQUIRED;
                        case INCOMPATIBLE -> QueryException.Reason.INCOMPATIBLE_INDEX;
                        case WRONG_PROJECT -> QueryException.Reason.WRONG_PROJECT;
                        case CORRUPT_INDEX -> QueryException.Reason.CORRUPT_INDEX;
                    };

            throw new QueryException(reason, failure.getMessage(), failure);
        } catch (IOException | SQLException | SearchException failure) {
            throw new QueryException(QueryException.Reason.READ_FAILED, "Could not query project index", failure);
        }
    }

    @FunctionalInterface
    interface StorageOpener {

        SqliteStorage open(ProjectContext context) throws IOException, SQLException;
    }
}
