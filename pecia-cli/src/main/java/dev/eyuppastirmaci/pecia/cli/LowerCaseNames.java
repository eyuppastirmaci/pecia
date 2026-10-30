package dev.eyuppastirmaci.pecia.cli;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import picocli.CommandLine;

/** Converts and lists an option's enum values in lower case, as {@code --color=auto} spells them. */
abstract class LowerCaseNames<E extends Enum<E>> implements CommandLine.ITypeConverter<E>, Iterable<String> {

    private final Class<E> type;

    LowerCaseNames(Class<E> type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** Returns the value's spelling on the command line. */
    static String spelling(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public E convert(String value) {
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> spelling(constant).equals(value))
                .findFirst()
                .orElseThrow(() -> new CommandLine.TypeConversionException(
                        "expected one of " + String.join(", ", this) + " but was '" + value + "'"));
    }

    @Override
    public Iterator<String> iterator() {
        return Arrays.stream(type.getEnumConstants())
                .map(LowerCaseNames::spelling)
                .iterator();
    }
}
