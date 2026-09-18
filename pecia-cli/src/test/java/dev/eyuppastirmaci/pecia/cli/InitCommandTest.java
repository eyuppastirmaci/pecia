package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InitCommandTest {

    @Test
    void rejectsMissingConfigFileDuringConstruction() {
        assertThrows(NullPointerException.class, () -> new InitCommand(null));
    }
}
