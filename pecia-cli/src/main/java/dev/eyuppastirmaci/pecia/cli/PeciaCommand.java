package dev.eyuppastirmaci.pecia.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Root CLI command that groups supported operations and displays usage. */
@Command(
        name = "pecia",
        description = "Indexes documents and source code for offline lexical and semantic search.",
        versionProvider = PeciaCommand.Version.class,
        mixinStandardHelpOptions = true,
        subcommands = {InitCommand.class, IndexCommand.class, QueryCommand.class})
public class PeciaCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    // Inherited, so it may follow the subcommand as well; subcommands read it with ColorMode.of.
    @Option(
            names = "--color",
            paramLabel = "<when>",
            scope = CommandLine.ScopeType.INHERIT,
            converter = ColorMode.Names.class,
            completionCandidates = ColorMode.Names.class,
            description = "When to use colour: ${COMPLETION-CANDIDATES} (default: auto, on terminals that show colour"
                    + " unless NO_COLOR is set).")
    ColorMode color = ColorMode.AUTO;

    /** Prints usage when pecia is run without a subcommand. */
    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    /** Reads the version the build writes into the JAR's manifest; outside a JAR there is none to read. */
    static final class Version implements CommandLine.IVersionProvider {

        @Override
        public String[] getVersion() {
            String version = PeciaCommand.class.getPackage().getImplementationVersion();
            return new String[] {"pecia " + (version == null ? "development" : version)};
        }
    }
}
