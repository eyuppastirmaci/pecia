package dev.eyuppastirmaci.pecia.cli;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
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

    /** Returns the spelling {@code --color} takes. */
    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Converts and lists {@code --color} values in lower case. */
    static final class Names implements CommandLine.ITypeConverter<ColorMode>, Iterable<String> {

        @Override
        public ColorMode convert(String value) {
            return Arrays.stream(values())
                    .filter(mode -> mode.value().equals(value))
                    .findFirst()
                    .orElseThrow(() -> new CommandLine.TypeConversionException(
                            "expected one of " + String.join(", ", this) + " but was '" + value + "'"));
        }

        @Override
        public Iterator<String> iterator() {
            return Arrays.stream(values()).map(ColorMode::value).iterator();
        }
    }
}
