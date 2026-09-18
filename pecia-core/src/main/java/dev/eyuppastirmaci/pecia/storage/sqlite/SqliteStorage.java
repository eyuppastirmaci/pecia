package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexDecision;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.search.LexicalSearch;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

/** Owns a SQLite index connection and exposes repositories and lexical search for its lifetime. */
public final class SqliteStorage implements AutoCloseable {

    private final Connection connection;

    private SqliteStorage(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a SQLite index belonging to the given existing project directory, creating the current
     * schema or atomically upgrading a compatible older index while preserving stored content.
     *
     * @param databasePath the database file path, resolved against the working directory when
     *     relative
     * @param projectRoot the existing project directory whose canonical URI owns the index
     * @return the initialized storage whose connection is owned by the caller until close
     * @throws IOException if the project directory, database parent, or schema resource cannot be
     *     accessed
     * @throws SQLException if SQLite cannot open, initialize, or validate the database
     * @throws NullPointerException if either path is null
     */
    public static SqliteStorage open(Path databasePath, Path projectRoot) throws IOException, SQLException {
        Path database = databasePath.toAbsolutePath().normalize();
        Path root = projectRoot.toRealPath();

        if (!Files.isDirectory(root)) {
            throw new IOException("Project root must be a directory: " + root);
        }

        Files.createDirectories(database.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        // A file URI preserves special characters instead of interpreting them as JDBC URL options.
        Connection connection =
                JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());

        try {
            SqliteSchemaInitializer.load(connection, root.toUri().toASCIIString())
                    .initialize();

            return new SqliteStorage(connection);
        } catch (IOException | SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }

            throw failure;
        }
    }

    /**
     * Opens and validates an existing index with SQLite's native read-only flag. Never creates parent
     * directories, creates an index, migrates it, or probes FTS through writes. The caller owns the
     * returned storage and must close it.
     *
     * @throws IndexAccessException if the index is missing, needs migration, is incompatible, foreign
     *     or corrupt
     * @throws IOException if paths or bundled schema definitions cannot be read
     * @throws SQLException if SQLite cannot open or read the database
     * @throws NullPointerException if either path is null
     */
    public static SqliteStorage openReadOnly(Path databasePath, Path projectRoot) throws IOException, SQLException {
        Path database = databasePath.toAbsolutePath().normalize();
        Path root = projectRoot.toRealPath();

        if (!Files.isDirectory(root)) {
            throw new IOException("Project root must be a directory: " + root);
        }

        try {
            if (!Files.readAttributes(database, BasicFileAttributes.class).isRegularFile()) {
                throw new IndexAccessException(
                        IndexAccessException.Reason.CORRUPT_INDEX, "Index path is not a regular file: " + database);
            }
        } catch (NoSuchFileException missing) {
            throw new IndexAccessException(
                    IndexAccessException.Reason.NOT_FOUND,
                    "Index does not exist; run index first: " + database,
                    missing);
        }

        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        Connection connection;

        try {
            connection =
                    JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());
        } catch (SQLException failure) {
            int code = failure.getErrorCode() & 0xff;

            if (code == 11 || code == 26) {
                throw new IndexAccessException(
                        IndexAccessException.Reason.CORRUPT_INDEX,
                        "Could not open corrupt index: " + database,
                        failure);
            }

            throw failure;
        }

        try {
            SqliteSchemaInitializer.load(connection, root.toUri().toASCIIString())
                    .validateReadOnly();

            return new SqliteStorage(connection);
        } catch (IOException | SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                failure.addSuppressed(closeFailure);
            }

            throw failure;
        }
    }

    Connection connection() {
        return connection;
    }

    /**
     * Returns a file repository using this storage's owned connection.
     *
     * @return the repository usable until this storage is closed
     */
    public SqliteFileRepository files() {
        return new SqliteFileRepository(connection);
    }

    /**
     * Returns a chunk repository using this storage's owned connection.
     *
     * @return the repository usable until this storage is closed
     */
    public SqliteChunkRepository chunks() {
        return new SqliteChunkRepository(this);
    }

    /**
     * Finds a file whose content, type, and known profile match in one database snapshot. The caller
     * must load and validate the current source before this lookup. This method never writes data.
     *
     * @return the matching manifest entry, or empty when the file needs indexing
     * @throws NullPointerException if an argument is null
     * @throws SQLException if reading or validating stored state fails
     */
    public Optional<StoredFile> findUnchangedFile(
            ExtractionRequest request, ContentHash contentHash, IndexingProfile profile) throws SQLException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(profile, "profile");

        return inScope(() -> {
            var previous = files().findByPath(request.sourcePath());

            if (previous.isEmpty()) {
                return Optional.empty();
            }

            StoredFile file = previous.orElseThrow();
            var storedProfile = files().findIndexingProfile(file.id());

            if (storedProfile.isEmpty()) {
                return Optional.empty();
            }

            IndexDecision decision =
                    IndexDecision.evaluate(request, contentHash, profile, file, storedProfile.orElseThrow());

            return decision == IndexDecision.UNCHANGED ? previous : Optional.empty();
        });
    }

    /**
     * Returns a read-only lexical search service usable for this storage's lifetime. Repeated
     * searches see committed replacements; this service never closes the connection, rebuilds the
     * index or commits the caller's transaction. Serialize access to this storage. Opening storage
     * itself may still create or migrate an index.
     *
     * <p>For an already opened storage, prepare a corpus and query it:
     *
     * <pre>{@code
     * var path = Path.of("src/Auth.java");
     * var text = "JWT_SECRET authentication middleware";
     * var document = new Document(path, DocumentType.SOURCE_CODE, text,
     *         ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8)));
     * var chunk = new Chunk(path, document.type(), 0, text,
     *         new LineRange(1, 1), ChunkMetadata.empty());
     * storage.replaceFile(document, List.of(chunk));
     * var hits = storage.lexicalSearch().search(new SearchRequest("JWT_SECRET"));
     * }</pre>
     *
     * @return a search service borrowing the existing connection
     */
    public LexicalSearch lexicalSearch() {
        return new SqliteLexicalSearch(new SqliteLexicalRetriever(this));
    }

    /**
     * Atomically saves a document's manifest and replaces all its chunks while preserving an existing
     * file ID. Clears any previously stored indexing profile because this caller supplies none.
     *
     * @param document the extracted document carrying the raw-byte hash
     * @param chunks the complete chunk list in contiguous zero-based order, possibly empty
     * @return the saved manifest entry
     * @throws SQLException if persistence fails, with prior data restored when rollback succeeds
     * @throws IllegalArgumentException if chunk identity, order, or location does not match the
     *     storage contract
     * @throws NullPointerException if document, chunks, or a chunk is null
     */
    public StoredFile replaceFile(Document document, List<Chunk> chunks) throws SQLException {
        List<Chunk> replacement = validateChunks(document, chunks);

        return inScope(() -> saveReplacement(document, replacement));
    }

    /**
     * Atomically replaces a file and commits the profile used to produce its chunks. The profile is
     * written last; a failure restores the previous manifest, chunks, search entries, and profile.
     *
     * @param profile the tokenizer and chunk settings used for this complete replacement
     * @return the saved manifest entry, preserving an existing file ID
     * @throws SQLException if persistence fails, with prior data restored when rollback succeeds
     * @throws IllegalArgumentException if chunks do not match the document or storage contract
     * @throws NullPointerException if document, chunks, a chunk, or profile is null
     */
    public StoredFile replaceFile(Document document, List<Chunk> chunks, IndexingProfile profile) throws SQLException {
        Objects.requireNonNull(profile, "profile");
        List<Chunk> replacement = validateChunks(document, chunks);

        return inScope(() -> {
            StoredFile file = saveReplacement(document, replacement);
            files().saveIndexingProfile(file.id(), profile);

            return file;
        });
    }

    /**
     * Deletes the supplied manifest snapshots and all dependent rows as one atomic batch. Missing
     * records are ignored; changed records abort the batch so a stale plan cannot remove new content.
     * The caller owns filesystem eligibility checks. An outer transaction remains caller-owned.
     *
     * @return the number of records actually deleted
     * @throws NullPointerException if the list or any entry is null
     * @throws SQLException if a record changed or deletion fails; the entire batch is rolled back
     */
    public int deleteFiles(List<StoredFile> expectedFiles) throws SQLException {
        List<StoredFile> expected = List.copyOf(expectedFiles);

        if (expected.isEmpty()) {
            return 0;
        }

        return inScope(() -> {
            SqliteFileRepository repository = files();
            int deleted = 0;

            for (StoredFile file : expected) {
                var current = repository.findByPath(file.sourcePath());

                if (current.isEmpty()) {
                    continue;
                }

                if (!current.orElseThrow().equals(file)) {
                    throw new SQLException("File changed since deletion planning: " + file.sourcePath());
                }

                if (repository.delete(file.id())) {
                    deleted++;
                }
            }

            return deleted;
        });
    }

    private static List<Chunk> validateChunks(Document document, List<Chunk> chunks) {
        Objects.requireNonNull(document, "document");
        List<Chunk> replacement = List.copyOf(chunks);

        for (int index = 0; index < replacement.size(); index++) {
            Chunk chunk = replacement.get(index);

            if (!chunk.sourcePath().equals(document.sourcePath())
                    || chunk.documentType() != document.type()
                    || chunk.index() != index
                    || !(chunk.sourceLocation() instanceof LineRange)) {
                throw new IllegalArgumentException(
                        "Replacement chunks must match the document and have contiguous indices and line" + " ranges");
            }
        }

        return replacement;
    }

    private StoredFile saveReplacement(Document document, List<Chunk> replacement) throws SQLException {
        StoredFile file = files().save(document.sourcePath(), document.type(), document.contentHash());
        SqliteChunkRepository repository = chunks();
        repository.deleteByFileId(file.id());

        for (Chunk chunk : replacement) {
            repository.insert(file.id(), chunk);
        }

        return file;
    }

    /** Keeps repository operations atomic while preserving any caller-owned outer transaction. */
    <T> T inScope(SqlWork<T> work) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SAVEPOINT pecia_repository");

            try {
                T result = work.run();
                statement.execute("RELEASE pecia_repository");

                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    statement.execute("ROLLBACK TO pecia_repository");
                    statement.execute("RELEASE pecia_repository");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }

                throw failure;
            }
        }
    }

    @FunctionalInterface
    interface SqlWork<T> {
        T run() throws SQLException;
    }

    /**
     * Closes the owned SQLite connection.
     *
     * @throws SQLException if the connection cannot be closed
     */
    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
