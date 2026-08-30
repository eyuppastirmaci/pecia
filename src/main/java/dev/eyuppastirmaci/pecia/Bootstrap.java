package dev.eyuppastirmaci.pecia;

import dev.eyuppastirmaci.pecia.cli.InitCommand;
import dev.eyuppastirmaci.pecia.cli.PeciaCommand;
import dev.eyuppastirmaci.pecia.config.PeciaConfigFile;
import picocli.CommandLine;

public final class Bootstrap implements CommandLine.IFactory {

    private final CommandLine.IFactory fallback = CommandLine.defaultFactory();

    private final PeciaConfigFile configFile = new PeciaConfigFile();

    /**
     * Creates a command instance, injecting wired dependencies where needed.
     *
     * @param cls command class picocli asks for
     * @return an instance of the requested class
     * @throws Exception if the fallback factory fails to build the instance
     */
    @Override
    public <K> K create(Class<K> cls) throws Exception {
        if (cls == InitCommand.class) {
            return cls.cast(new InitCommand(configFile));
        }

        // Commands without wired dependencies are built by picocli's default factory.
        return fallback.create(cls);
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
