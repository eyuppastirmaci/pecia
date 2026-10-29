package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.search.FusedRanks;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryNotice;
import dev.eyuppastirmaci.pecia.search.QueryOptions;
import dev.eyuppastirmaci.pecia.search.QueryResult;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.QueryShape;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import dev.eyuppastirmaci.pecia.search.SearchScore;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Searches a project's existing index and prints ranked source locations. */
@Command(
        name = "query",
        description = "Searches the local index lexically (BM25), semantically, or with both (hybrid).",
        mixinStandardHelpOptions = true)
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Plain text to search for (quote multiple words).")
    String text;

    @Option(
            names = "--root",
            defaultValue = ".",
            description = "Project context directory (default: current directory).")
    Path root;

    @Option(names = "--limit", description = "Maximum results (default: ${DEFAULT-VALUE}).")
    int limit = SearchRequest.DEFAULT_LIMIT;

    // Null when absent, so search.mode in .pecia.toml applies.
    @Option(
            names = "--mode",
            converter = ModeNames.class,
            completionCandidates = ModeNames.class,
            description = "Search mode for this query (${COMPLETION-CANDIDATES}), overriding search.mode in"
                    + " .pecia.toml (default: auto). Auto searches in hybrid mode when the index has embeddings and"
                    + " the cached model is usable, and lexically otherwise; queries never download the model.")
    SearchMode mode;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /** The exit code when an explicit semantic or hybrid query lacks a semantic prerequisite. */
    static final int MISSING_SEMANTIC_PREREQUISITE = 3;

    private final QueryService queryService;

    /** Creates a command using the supplied non-null query service. */
    public QueryCommand(QueryService queryService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
    }

    /**
     * Searches the stored index; returns 0 for successful queries, {@link #MISSING_SEMANTIC_PREREQUISITE} when an
     * explicit semantic or hybrid query lacks a semantic prerequisite, and 1 for other core validation or access
     * failures. Configuration warnings are printed to stderr first, then notes on how the query was searched; neither
     * changes the exit code.
     */
    @Override
    public Integer call() {
        try {
            QueryOptions options = mode == null ? QueryOptions.configured() : QueryOptions.mode(mode);
            QueryResult result = queryService.query(root, new SearchRequest(text, limit), options);
            WarningPrinter.print(spec.commandLine().getErr(), result.warnings());
            printNotes(spec.commandLine().getErr(), result);
            List<SearchHit> hits = result.hits();
            PrintWriter out = spec.commandLine().getOut();

            if (hits.isEmpty()) {
                out.println("No results.");
            } else {
                for (SearchHit hit : hits) {
                    printHit(out, hit, result.queryShape());
                }
            }

            return 0;
        } catch (QueryException failure) {
            PrintWriter err = spec.commandLine().getErr();
            WarningPrinter.print(err, failure.warnings());
            boolean causeHelps =
                    switch (failure.reason()) {
                        case READ_FAILED, VECTOR_SEARCH_UNAVAILABLE, EMBEDDER_UNAVAILABLE, EMBEDDING_FAILED -> true;
                        default -> false;
                    };
            String detail = causeHelps
                            && failure.getCause() != null
                            && !failure.getMessage().equals(failure.getCause().getMessage())
                    ? ": " + failure.getCause().getMessage()
                    : "";
            err.println("pecia query: " + failure.reason() + ": " + failure.getMessage() + detail);

            return switch (failure.reason()) {
                case INDEX_NOT_FOUND, MIGRATION_REQUIRED -> {
                    err.println(indexCommand(""));
                    yield 1;
                }
                case EMBEDDINGS_MISSING, EMBEDDINGS_OUTDATED, MODEL_NOT_CACHED -> {
                    // Semantic indexing makes the embeddings and downloads the model; queries never do.
                    err.println(indexCommand(" --semantic"));
                    yield MISSING_SEMANTIC_PREREQUISITE;
                }
                case VECTOR_SEARCH_UNAVAILABLE, EMBEDDER_UNAVAILABLE, EMBEDDING_FAILED -> {
                    err.println("Use --mode lexical to search without embeddings.");
                    yield MISSING_SEMANTIC_PREREQUISITE;
                }
                default -> 1;
            };
        } catch (IllegalArgumentException failure) {
            spec.commandLine().getErr().println("pecia query: " + failure.getMessage());

            return 1;
        }
    }

    private String indexCommand(String options) {
        return "Run: java -jar "
                + shellQuote(launcherJar())
                + " index"
                + options
                + " "
                + shellQuote(root.toAbsolutePath().normalize().toString());
    }

    /**
     * Notes why auto searched lexically, unless the index simply has no embeddings, where lexical search is what auto
     * is expected to do, and anything else about how the query text was used.
     */
    private static void printNotes(PrintWriter err, QueryResult result) {
        result.fallback()
                .filter(fallback -> fallback.reason() != QueryException.Reason.EMBEDDINGS_MISSING)
                .ifPresent(fallback -> err.println("note: searched lexically: " + fallback.message()));

        for (QueryNotice notice : result.notices()) {
            err.println("note: " + notice.message());
        }
    }

    private static void printHit(PrintWriter out, SearchHit hit, Optional<QueryShape> shape) {
        String location = FileWalker.portablePath(hit.sourcePath());

        if (hit.sourceLocation() instanceof LineRange lines) {
            location += ":" + lines.startLine() + "-" + lines.endLine();
        }

        out.println(location);
        out.println("  " + scoreLabel(hit.score().kind()) + ": " + hit.score().value() + fusedRanks(hit, shape));

        if (!hit.metadata().headingPath().isEmpty()) {
            out.println("  heading: " + String.join(" > ", hit.metadata().headingPath()));
        }

        hit.snippet().lines().forEach(line -> out.println("  " + line));
        out.println();
    }

    /** Names the score's kind, since BM25 scores are better when lower and cosine similarities when higher. */
    private static String scoreLabel(SearchScore.Kind kind) {
        return switch (kind) {
            case SQLITE_BM25 -> "BM25";
            case COSINE_SIMILARITY -> "cosine";
            case RECIPROCAL_RANK_FUSION -> "RRF";
        };
    }

    /**
     * Shows which sides ranked a hybrid hit, for example {@code " (lexical #1, semantic #4)"}, and names identifier
     * queries, whose lexical order decides; empty for other hits.
     */
    private static String fusedRanks(SearchHit hit, Optional<QueryShape> shape) {
        if (hit.fusedRanks().isEmpty()) {
            return "";
        }

        FusedRanks ranks = hit.fusedRanks().orElseThrow();
        List<String> sides = new ArrayList<>();
        ranks.lexical().ifPresent(rank -> sides.add("lexical #" + rank));
        ranks.semantic().ifPresent(rank -> sides.add("semantic #" + rank));
        // Natural language is the common case, so only the lexical-first ordering of identifiers is named.
        String note = shape.filter(QueryShape.IDENTIFIER::equals).isPresent() ? "; identifier query" : "";

        return " (" + String.join(", ", sides) + note + ")";
    }

    private static String launcherJar() {
        try {
            Path source = Path.of(QueryCommand.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());

            if (source.toString().endsWith(".jar")) {
                return source.toString();
            }
        } catch (URISyntaxException invalidLocation) {
            // Development launchers may not expose a filesystem JAR; point to the build artifact instead.
        }

        return "pecia-cli/build/libs/pecia.jar";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    /** Converts and lists {@code --mode} values, accepting exactly the spellings configuration uses. */
    static final class ModeNames implements CommandLine.ITypeConverter<SearchMode>, Iterable<String> {

        @Override
        public SearchMode convert(String value) {
            return SearchMode.fromConfigValue(value)
                    .orElseThrow(() -> new CommandLine.TypeConversionException(
                            "expected one of " + String.join(", ", this) + " but was '" + value + "'"));
        }

        @Override
        public Iterator<String> iterator() {
            return SearchMode.configValues().iterator();
        }
    }
}
