package dev.eyuppastirmaci.pecia;

import dev.eyuppastirmaci.pecia.cli.IndexCommand;
import dev.eyuppastirmaci.pecia.cli.InitCommand;
import dev.eyuppastirmaci.pecia.cli.PeciaCommand;
import dev.eyuppastirmaci.pecia.cli.QueryCommand;
import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import dev.eyuppastirmaci.pecia.config.PeciaConfigLoader;
import dev.eyuppastirmaci.pecia.config.PeciaConfigParser;
import dev.eyuppastirmaci.pecia.index.IndexService;
import dev.eyuppastirmaci.pecia.search.QueryService;
import picocli.CommandLine;

/** Wires core services into CLI commands and provides the executable entry point. */
public final class Bootstrap implements CommandLine.IFactory {

    private final CommandLine.IFactory fallback = CommandLine.defaultFactory();

    private final PeciaConfigFile configFile = new PeciaConfigFile();

    private final PeciaConfigLoader configLoader = new PeciaConfigLoader(new PeciaConfigParser());

    private final IndexService indexService = new IndexService(configLoader);

    private final QueryService queryService = new QueryService(configLoader);

    /**
     * Creates a command instance, injecting wired dependencies where needed.
     *
     * @param commandClass command class picocli asks for
     * @return an instance of the requested class
     * @throws Exception if the fallback factory fails to build the instance
     */
    @Override
    public <K> K create(Class<K> commandClass) throws Exception {
        if (commandClass == InitCommand.class) {
            return commandClass.cast(new InitCommand(configFile));
        }

        if (commandClass == IndexCommand.class) {
            return commandClass.cast(new IndexCommand(indexService));
        }

        if (commandClass == QueryCommand.class) {
            return commandClass.cast(new QueryCommand(queryService));
        }

        // Commands without wired dependencies are built by picocli's default factory.
        return fallback.create(commandClass);
    }

    /**
     * Starts the pecia CLI and exits with the command's exit code.
     *
     * @param args raw command-line arguments
     */
    public static void main(String[] args) {
        Bootstrap bootstrap = new Bootstrap();

        int exitCode = new CommandLine(new PeciaCommand(), bootstrap).execute(args);

        System.exit(exitCode);
    }
}
