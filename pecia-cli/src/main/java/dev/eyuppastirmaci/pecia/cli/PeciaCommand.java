package dev.eyuppastirmaci.pecia.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Root CLI command that groups supported operations and displays usage. */
@Command(
        name = "pecia",
        description = "Indexes documents and source code for offline lexical search.",
        version = "pecia 0.1.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        subcommands = {InitCommand.class, IndexCommand.class, QueryCommand.class})
public class PeciaCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /** Prints usage when pecia is run without a subcommand. */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
