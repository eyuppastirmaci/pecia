package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Every path is built from a host temporary directory, so the platform rules run the same way on every CI OS. */
class CacheDirectoryTest {

    @TempDir
    Path sandbox;

    @Test
    void overrideWinsOnEveryPlatformAndIsNormalized() {
        Path override = sandbox.resolve("custom");

        for (String os : new String[] {"Linux", "Mac OS X", "Windows 11"}) {
            Map<String, String> environment = Map.of(
                    CacheDirectory.OVERRIDE,
                    override.resolve("nested/..").toString(),
                    "XDG_CACHE_HOME",
                    sandbox.resolve("xdg").toString(),
                    "LOCALAPPDATA",
                    sandbox.resolve("local").toString());

            assertEquals(override, CacheDirectory.resolve(environment, properties(os)), os);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"relative/cache", "cache", "~/cache"})
    void relativeOverrideIsRejectedWithItsName(String value) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CacheDirectory.resolve(Map.of(CacheDirectory.OVERRIDE, value), properties("Linux")));

        assertEquals("PECIA_CACHE_DIR must be an absolute path, got " + value, failure.getMessage());
    }

    @Test
    void emptyOverrideFallsBackToThePlatformConvention() {
        assertEquals(
                home().resolve(".cache/pecia"),
                CacheDirectory.resolve(Map.of(CacheDirectory.OVERRIDE, ""), properties("Linux")));
    }

    @Test
    void linuxUsesAnAbsoluteXdgCacheHome() {
        Path xdg = sandbox.resolve("xdg");

        assertEquals(
                xdg.resolve("pecia"),
                CacheDirectory.resolve(Map.of("XDG_CACHE_HOME", xdg.toString()), properties("Linux")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "relative/xdg"})
    void linuxIgnoresAnEmptyOrRelativeXdgCacheHome(String xdg) {
        assertEquals(
                home().resolve(".cache/pecia"),
                CacheDirectory.resolve(Map.of("XDG_CACHE_HOME", xdg), properties("Linux")));
    }

    @Test
    void linuxAndOtherUnixSystemsDefaultToDotCache() {
        assertEquals(home().resolve(".cache/pecia"), CacheDirectory.resolve(Map.of(), properties("Linux")));
        assertEquals(home().resolve(".cache/pecia"), CacheDirectory.resolve(Map.of(), properties("FreeBSD")));
    }

    @Test
    void macOsUsesLibraryCachesAndIgnoresXdg() {
        Map<String, String> environment =
                Map.of("XDG_CACHE_HOME", sandbox.resolve("xdg").toString());

        assertEquals(
                home().resolve("Library/Caches/pecia"), CacheDirectory.resolve(environment, properties("Mac OS X")));
    }

    @Test
    void windowsUsesLocalAppDataAndIgnoresXdg() {
        Path local = sandbox.resolve("local");
        Map<String, String> environment = Map.of(
                "LOCALAPPDATA",
                local.toString(),
                "XDG_CACHE_HOME",
                sandbox.resolve("xdg").toString());

        assertEquals(local.resolve("pecia/cache"), CacheDirectory.resolve(environment, properties("Windows 11")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "relative/local"})
    void windowsFallsBackToTheUsualLocalAppDataLocation(String localAppData) {
        Map<String, String> environment = new HashMap<>();
        if (!localAppData.isEmpty()) {
            environment.put("LOCALAPPDATA", localAppData);
        }

        assertEquals(
                home().resolve("AppData/Local/pecia/cache"),
                CacheDirectory.resolve(environment, properties("Windows 10")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "relative/home"})
    void platformDefaultsNeedAnAbsoluteHome(String userHome) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CacheDirectory.resolve(Map.of(), name -> switch (name) {
                    case "os.name" -> "Linux";
                    case "user.home" -> userHome;
                    default -> null;
                }));

        assertEquals("user.home must be an absolute path to find the cache", failure.getMessage());
    }

    @Test
    void windowsWithLocalAppDataDoesNotNeedAHome() {
        Path local = sandbox.resolve("local");

        assertEquals(
                local.resolve("pecia/cache"),
                CacheDirectory.resolve(
                        Map.of("LOCALAPPDATA", local.toString()),
                        name -> name.equals("os.name") ? "Windows 11" : null));
    }

    private Path home() {
        return sandbox.resolve("home");
    }

    private Function<String, String> properties(String os) {
        return name -> switch (name) {
            case "os.name" -> os;
            case "user.home" -> home().toString();
            default -> null;
        };
    }
}
