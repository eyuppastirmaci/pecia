package dev.eyuppastirmaci.pecia.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

/** Outside a JAR there is no manifest version, so the CLI says it is a development build. */
class PeciaCommandVersionTest {

    @Test
    void classesOutsideAJarReportADevelopmentBuild() {
        assertArrayEquals(new String[] {"pecia development"}, new PeciaCommand.Version().getVersion());
    }
}
