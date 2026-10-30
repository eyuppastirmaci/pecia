package dev.eyuppastirmaci.pecia.cli.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Homebrew formula and Scoop manifest templates name exactly the archives the packages produce, use only the
 * placeholders the release fills in, and stay valid Ruby and JSON once filled.
 */
class ChannelManifestsTest {

    private static final Path TEMPLATES = Path.of("src/package/channels");
    private static final String VERSION = "1.2.3-rc.4";
    private static final Map<String, String> VALUES = values();
    private static final String RELEASES =
            "https://github.com/eyuppastirmaci/pecia/releases/download/v" + VERSION + "/";

    @TempDir
    Path directory;

    @Test
    void templatesUseOnlyTheVersionAndThePackagesChecksums() throws Exception {
        Pattern placeholder = Pattern.compile("\\$\\{([^}]+)}");

        for (String template : List.of("pecia.rb", "pecia.json")) {
            Matcher matcher = placeholder.matcher(Files.readString(TEMPLATES.resolve(template)));

            while (matcher.find()) {
                assertTrue(VALUES.containsKey(matcher.group(1)), template + " uses an unknown " + matcher.group());
            }
        }
    }

    @Test
    void theFormulaNamesEachUnixArchiveWithItsChecksum() throws Exception {
        String formula = render("pecia.rb");

        for (String platform : List.of("osx-aarch64", "linux-x64", "linux-aarch64")) {
            String url = RELEASES + "pecia-" + VERSION + "-" + platform + ".tar.gz";
            int at = formula.indexOf("url \"" + url + "\"");
            assertTrue(at >= 0, "No URL for " + platform);
            // Each URL is followed by its own checksum.
            assertTrue(formula.indexOf("sha256 \"" + sha256(platform) + "\"", at) > at, "No checksum for " + platform);
        }

        // brew audit --strict rejects a version that Homebrew can already read from the URLs.
        assertFalse(formula.contains("version \""));
        assertFalse(formula.contains("${"));
    }

    @Test
    void theFormulaIsValidRuby() throws Exception {
        Path formula = Files.writeString(directory.resolve("pecia.rb"), render("pecia.rb"));
        ProcessBuilder ruby = new ProcessBuilder("ruby", "-c", formula.toString()).redirectErrorStream(true);
        Process process;

        try {
            process = ruby.start();
        } catch (java.io.IOException noRuby) {
            assumeTrue(false, "Ruby is not installed");
            return;
        }

        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void theScoopManifestNamesTheWindowsArchiveAndItsLauncher() throws Exception {
        JsonObject manifest = JsonParser.parseString(render("pecia.json")).getAsJsonObject();
        JsonObject windows = manifest.getAsJsonObject("architecture").getAsJsonObject("64bit");

        assertEquals(VERSION, manifest.get("version").getAsString());
        assertEquals(
                RELEASES + "pecia-" + VERSION + "-win-x64.zip",
                windows.get("url").getAsString());
        assertEquals(sha256("win-x64"), windows.get("hash").getAsString());
        assertEquals("pecia-" + VERSION + "-win-x64", windows.get("extract_dir").getAsString());
        assertEquals("pecia.exe", manifest.get("bin").getAsString());
    }

    /** Fills a template the way the channelManifests task does. */
    private static String render(String template) throws Exception {
        String text = Files.readString(TEMPLATES.resolve(template));

        for (Map.Entry<String, String> value : VALUES.entrySet()) {
            text = text.replace("${" + value.getKey() + "}", value.getValue());
        }

        return text;
    }

    private static Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("version", VERSION);

        for (String platform : List.of("osx-aarch64", "linux-x64", "linux-aarch64", "win-x64")) {
            values.put("sha256_" + platform.replace('-', '_'), sha256(platform));
        }

        return values;
    }

    /** A distinct, valid-looking checksum per platform, so a swapped one is noticed. */
    private static String sha256(String platform) {
        return String.format("%064x", Math.abs(platform.hashCode()));
    }
}
