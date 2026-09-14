package dev.eyuppastirmaci.pecia.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "pecia",
        description = "Turns a folder of documents and source code into a searchable local vector index.",
        version = "pecia 0.1.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        subcommands = {
                InitCommand.class,
                IndexCommand.class,
                QueryCommand.class
        }
)
public class PeciaCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /**
     * Prints usage when pecia is run without a subcommand.
     */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}
