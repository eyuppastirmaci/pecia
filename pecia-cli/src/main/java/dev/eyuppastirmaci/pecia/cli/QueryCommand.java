package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.search.QueryException;
import dev.eyuppastirmaci.pecia.search.QueryService;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.search.SearchRequest;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Searches a project's existing lexical index and prints ranked source locations. */
@Command(
        name = "query",
        description = "Searches the local index using BM25 lexical ranking.",
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

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    private final QueryService queryService;

    /** Creates a command using the supplied non-null query service. */
    public QueryCommand(QueryService queryService) {
        this.queryService = Objects.requireNonNull(queryService, "queryService");
    }

    /**
     * Searches the stored index; returns 0 for successful queries and 1 for core validation/access
     * failures.
     */
    @Override
    public Integer call() {
        try {
            List<SearchHit> hits = queryService.search(root, new SearchRequest(text, limit));
            PrintWriter out = spec.commandLine().getOut();

            if (hits.isEmpty()) {
                out.println("No results.");
            } else {
                for (SearchHit hit : hits) {
                    printHit(out, hit);
                }
            }

            return 0;
        } catch (QueryException failure) {
            PrintWriter err = spec.commandLine().getErr();
            String detail = failure.reason() == QueryException.Reason.READ_FAILED && failure.getCause() != null
                    ? ": " + failure.getCause().getMessage()
                    : "";
            err.println("pecia query: " + failure.reason() + ": " + failure.getMessage() + detail);

            if (failure.reason() == QueryException.Reason.INDEX_NOT_FOUND
                    || failure.reason() == QueryException.Reason.MIGRATION_REQUIRED) {
                err.println("Run: java -jar "
                        + shellQuote(launcherJar())
                        + " index "
                        + shellQuote(root.toAbsolutePath().normalize().toString()));
            }

            return 1;
        } catch (IllegalArgumentException failure) {
            spec.commandLine().getErr().println("pecia query: " + failure.getMessage());

            return 1;
        }
    }

    private static void printHit(PrintWriter out, SearchHit hit) {
        String location = FileWalker.portablePath(hit.sourcePath());

        if (hit.sourceLocation() instanceof LineRange lines) {
            location += ":" + lines.startLine() + "-" + lines.endLine();
        }

        out.println(location);
        out.println("  BM25: " + hit.score().value());

        if (!hit.metadata().headingPath().isEmpty()) {
            out.println("  heading: " + String.join(" > ", hit.metadata().headingPath()));
        }

        hit.snippet().lines().forEach(line -> out.println("  " + line));
        out.println();
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

        return "pecia-cli/target/pecia.jar";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
