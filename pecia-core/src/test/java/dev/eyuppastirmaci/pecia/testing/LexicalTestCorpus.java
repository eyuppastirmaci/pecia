package dev.eyuppastirmaci.pecia.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fixed mixed corpus for lexical integration tests: plain text, Markdown with nested headings and a fence,
 * source code with CRLF endings, a BOM, Turkish and other non-ASCII text, an empty file, and a document long
 * enough to produce several overlapping chunks under the default chunking settings. It depends only on the JDK.
 */
public final class LexicalTestCorpus {

    public static final Path GUIDE = Path.of("docs/Ödeme rehberi.md");
    public static final Path NOTES = Path.of("docs/notes.txt");
    public static final Path AUTH = Path.of("src/Auth.java");
    public static final Path SCRIPT = Path.of("src/scripts/build.sh");
    public static final Path CONFIG = Path.of("config.yaml");
    public static final Path EMPTY = Path.of("empty.txt");
    public static final Path HANDBOOK = Path.of("handbook.txt");

    /** Queries covering identifiers, Turkish text, diacritic folding, headings, paths, and the long document. */
    public static final List<String> QUERIES =
            List.of("payment", "ödeme", "istanbul", "JWT_SECRET", "validation", "rehberi", "handbook", "retry");

    private static final byte[] UTF8_BOM = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};

    private LexicalTestCorpus() {}

    /** Writes the corpus below root in declaration order. */
    public static void write(Path root) throws IOException {
        write(root, false);
    }

    /** Writes the same bytes in reverse order, so directory creation and timestamps differ from {@link #write}. */
    public static void writeReversed(Path root) throws IOException {
        write(root, true);
    }

    /** Returns the exact bytes of each corpus file, keyed by project-relative path in declaration order. */
    public static Map<Path, byte[]> files() {
        Map<Path, byte[]> files = new LinkedHashMap<>();
        files.put(GUIDE, utf8("""
            # Ödeme rehberi

            Payment validation for İstanbul şubesi.

            ## Kartlar

            Kart ödemesi doğrulanır.

            ### Retry policy

            ```java
            retry(payment);
            ```

            ## İade

            İade işlemleri ayrı yürütülür.
            """));
        files.put(NOTES, utf8("Release notes: payment fixes and validation cleanup.\n"));
        files.put(AUTH, utf8("class Auth {\r\n    String JWT_SECRET = \"x\";\r\n    void validate() {}\r\n}\r\n"));
        files.put(SCRIPT, withBom("#!/bin/sh\nset -e\necho \"retry build\"\n"));
        files.put(CONFIG, utf8("payment:\n  retries: 3\n  city: Istanbul\n"));
        files.put(EMPTY, new byte[0]);
        files.put(HANDBOOK, utf8(handbook()));
        return files;
    }

    private static void write(Path root, boolean reversed) throws IOException {
        List<Map.Entry<Path, byte[]>> entries = new ArrayList<>(files().entrySet());

        if (reversed) {
            entries = entries.reversed();
        }

        for (var entry : entries) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent() == null ? root : file.getParent());
            Files.write(file, entry.getValue());
        }
    }

    /* Forty short paragraphs with sentence boundaries, well beyond one 256-token chunk. */
    private static String handbook() {
        StringBuilder text = new StringBuilder("Handbook\n\n");

        for (int paragraph = 1; paragraph <= 40; paragraph++) {
            text.append("Section ")
                    .append(paragraph)
                    .append(" covers payment validation step ")
                    .append(paragraph)
                    .append(". Ödeme adımı ")
                    .append(paragraph)
                    .append(" İstanbul ekibince incelenir. A retry follows every failed attempt.\n\n");
        }

        return text.toString();
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withBom(String text) {
        byte[] body = utf8(text);
        byte[] bytes = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, bytes, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, bytes, UTF8_BOM.length, body.length);
        return bytes;
    }
}
