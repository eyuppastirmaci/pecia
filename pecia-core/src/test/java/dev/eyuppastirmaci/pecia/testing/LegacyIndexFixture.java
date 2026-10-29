package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.sqlite.JDBC;

/**
 * Prepares a working copy of a committed legacy index fixture (see {@code storage/legacy/README.md}): it verifies the
 * fixture's checksum, recreates the corpus the fixture indexed in an empty project, copies the fixture to the
 * project's default index path, and rewrites the one machine-specific value, the stored project root. Tests use the
 * copy and never open the committed resource.
 */
public final class LegacyIndexFixture {

    /** The committed fixtures' SHA-256 checksums, which the fixture README must list as well. */
    public static final Map<Integer, String> CHECKSUMS = Map.of(
            1, "3cb9fbbbe3305c7f9b82de6bedd168002b91ae628a50addba39a84d84731017b",
            2, "e91d44ba9b6e94175381e165a097fee6dbec6ecc8d8275a498624e38ced8ce89",
            3, "c63c4c702d925ebf7dc65fffefaeff194c27bf4dd80a57547abe8955753662bd",
            4, "6b004fcc4c0a25f54b75b6e39f3a08c7998297295a4c4f9acefe937dedca6120");

    /** The schema versions that have committed fixtures, oldest first. */
    public static final int[] VERSIONS = {1, 2, 3, 4};

    /** The schema version current code writes, which may be newer than the newest fixture. */
    public static final int CURRENT_VERSION = 5;

    private static final String RESOURCE_DIRECTORY = "/storage/legacy/";

    private LegacyIndexFixture() {}

    /**
     * Writes the corpus and a verified copy of the fixture for {@code version} into the empty {@code projectRoot}.
     *
     * @return the copied database, at the project's default index path
     * @throws IllegalStateException if the committed fixture no longer matches its checksum, or its stored content
     *     hashes no longer match the corpus
     */
    public static Path install(int version, Path projectRoot) throws IOException, SQLException {
        Path root = projectRoot.toRealPath();

        try (var children = Files.list(root)) {
            if (children.findAny().isPresent()) {
                throw new IllegalArgumentException("The fixture project must start empty: " + root);
            }
        }

        byte[] fixture = verifiedBytes(version);
        LexicalTestCorpus.write(root);
        Path database = Files.createDirectories(root.resolve(".pecia")).resolve("index.db");
        Files.write(database, fixture);

        try (Connection connection = open(database)) {
            try (var update = connection.prepareStatement("UPDATE index_metadata SET project_root_uri = ?")) {
                update.setString(1, root.toUri().toASCIIString());
                update.executeUpdate();
            }

            requireCorpusHashes(connection, version);
        }

        return database;
    }

    /**
     * Installs the current-version fixture like {@link #install}, then applies one documented damage to the copy, so
     * the copy is rejected for exactly that reason and not because of its stored project root.
     *
     * @return the damaged database, at the project's default index path
     */
    public static Path installDamaged(Damage damage, Path projectRoot) throws IOException, SQLException {
        Path root = projectRoot.toRealPath();
        Path database = install(VERSIONS[VERSIONS.length - 1], root);

        switch (damage) {
            case TRUNCATED -> {
                // Cut inside the third page, after the header but before the schema and data are complete.
                byte[] bytes = Files.readAllBytes(database);
                int pageSize = ((bytes[16] & 0xff) << 8) | (bytes[17] & 0xff);
                Files.write(database, Arrays.copyOf(bytes, pageSize * 2 + pageSize / 2));
            }
            case FOREIGN_PROJECT ->
                execute(
                        database,
                        "UPDATE index_metadata SET project_root_uri = '"
                                + root.resolveSibling(root.getFileName() + "-other")
                                        .toUri()
                                        .toASCIIString()
                                + "'");
            case FUTURE_SCHEMA -> execute(database, "PRAGMA user_version = " + (CURRENT_VERSION + 1));
            case FORMAT_MISMATCH ->
                execute(database, "UPDATE index_metadata SET index_format_version = " + (CURRENT_VERSION + 1));
        }

        return database;
    }

    /** Returns the committed fixture's bytes after checking them against {@link #CHECKSUMS}. */
    public static byte[] verifiedBytes(int version) throws IOException {
        String resource = RESOURCE_DIRECTORY + "schema-v" + version + ".db";
        byte[] bytes;

        try (InputStream input = LegacyIndexFixture.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing legacy index fixture: " + resource);
            }

            bytes = input.readAllBytes();
        }

        requireChecksum(version, bytes);
        return bytes;
    }

    /* Package-visible so tests can prove that a changed fixture is rejected. */
    static void requireChecksum(int version, byte[] bytes) {
        String expected = CHECKSUMS.get(version);
        String actual = ContentHash.sha256(bytes).value();

        if (expected == null || !expected.equals(actual)) {
            throw new IllegalStateException(
                    "Legacy index fixture schema-v" + version + ".db does not match its checksum: " + actual);
        }
    }

    /* The fixture must describe exactly the corpus that tests recreate; otherwise it needs regenerating. */
    private static void requireCorpusHashes(Connection connection, int version) throws SQLException {
        Map<String, String> expected = new TreeMap<>();
        LexicalTestCorpus.files()
                .forEach((path, bytes) -> expected.put(
                        SqlitePath.encode(path), ContentHash.sha256(bytes).value()));
        Map<String, String> stored = new TreeMap<>();

        try (var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT source_path, content_hash FROM files")) {
            while (result.next()) {
                stored.put(result.getString(1), result.getString(2));
            }
        }

        if (!expected.equals(stored)) {
            throw new IllegalStateException("Legacy index fixture schema-v" + version
                    + ".db no longer matches the corpus; regenerate it as its README describes: " + stored);
        }
    }

    private static void execute(Path database, String sql) throws SQLException {
        try (Connection connection = open(database);
                var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static Connection open(Path database) throws SQLException {
        return JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), new Properties());
    }

    /** A single damage applied to a working copy of the current-version fixture. */
    public enum Damage {
        /** The file cut in the middle of its third page. */
        TRUNCATED,
        /** The stored project root names a sibling project. */
        FOREIGN_PROJECT,
        /** The schema version is one above the current version. */
        FUTURE_SCHEMA,
        /** The schema version is current, but the stored index format version is one above it. */
        FORMAT_MISMATCH
    }
}
