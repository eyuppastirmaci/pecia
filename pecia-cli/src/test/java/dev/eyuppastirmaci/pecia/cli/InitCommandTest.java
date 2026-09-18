package dev.eyuppastirmaci.pecia.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class InitCommandTest {

    @Test
    void rejectsMissingConfigFileDuringConstruction() {
        assertThrows(NullPointerException.class, () -> new InitCommand(null));
    }
}
