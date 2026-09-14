package dev.eyuppastirmaci.pecia.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
        name = "query",
        description = "Searches the index and prints the closest chunks.",
        mixinStandardHelpOptions = true
)
public class QueryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Text to search for.")
    String text;

    /**
     * Searches the index for the given text; not implemented yet.
     *
     * @return 1 until the command is implemented
     */
    @Override
    public Integer call() {
        System.err.println("pecia query: not implemented yet (query: \"" + text + "\")");
        return 1;
    }
}
