package dev.eyuppastirmaci.pecia.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WarningTest {

    private static final Path SOURCE = Path.of("").toAbsolutePath().resolve(".pecia.toml");

    @Test
    void keepsItsFieldsWithOrWithoutAPosition() {
        Warning.Position position = new Warning.Position(2, 5);
        Warning located = new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, position, "unknown key x");
        Warning unlocated = new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, null, "unknown key x");

        assertEquals(Warning.Code.UNKNOWN_CONFIG_KEY, located.code());
        assertEquals(SOURCE, located.source());
        assertEquals(position, located.position());
        assertEquals("unknown key x", located.message());
        assertNull(unlocated.position());
    }

    @Test
    void rejectsMissingFields() {
        assertThrows(NullPointerException.class, () -> new Warning(null, SOURCE, null, "message"));
        assertThrows(
                NullPointerException.class, () -> new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, null, null, "message"));
        assertThrows(
                NullPointerException.class, () -> new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, null, null));
    }

    @Test
    void rejectsRelativeOrUnnormalizedSources() {
        Path unnormalized = SOURCE.resolveSibling("nested/../.pecia.toml");

        assertThrows(
                IllegalArgumentException.class,
                () -> new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, Path.of(".pecia.toml"), null, "message"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, unnormalized, null, "message"));
    }

    @Test
    void rejectsBlankMessages() {
        assertThrows(
                IllegalArgumentException.class, () -> new Warning(Warning.Code.UNKNOWN_CONFIG_KEY, SOURCE, null, " "));
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "1, 0", "-1, 5"})
    void positionsAreOneBased(int line, int column) {
        assertThrows(IllegalArgumentException.class, () -> new Warning.Position(line, column));
    }
}
