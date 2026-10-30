package dev.eyuppastirmaci.pecia.cli;

import picocli.CommandLine;

/** When output uses colour, as {@code --color} chooses it. */
enum ColorMode {
    /** On a terminal that shows colour, unless {@code NO_COLOR} asks otherwise. */
    AUTO,
    /** Always, also when output goes to a pipe or a file. */
    ALWAYS,
    /** Never. */
    NEVER;

    /** Returns the mode {@code --color} chose for a command, or {@link #AUTO} when the command has no such option. */
    static ColorMode of(CommandLine.Model.CommandSpec spec) {
        CommandLine.Model.OptionSpec option = spec.findOption("--color");
        ColorMode mode = option == null ? null : option.getValue();

        return mode == null ? AUTO : mode;
    }

    /** Converts and lists {@code --color} values. */
    static final class Names extends LowerCaseNames<ColorMode> {

        Names() {
            super(ColorMode.class);
        }
    }
}
