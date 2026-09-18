package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.search.LexicalSearch;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class SqliteStorage implements AutoCloseable {
    private final Connection connection;

    private SqliteStorage(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens a SQLite index belonging to the given existing project directory, creating the current schema
     * or atomically upgrading a compatible version-one index and its stored chunks for FTS5 search.
     *
     * @param databasePath the database file path, resolved against the working directory when relative
     * @param projectRoot the existing project directory whose canonical URI owns the index
     * @return the initialized storage whose connection is owned by the caller until close
     * @throws IOException if the project directory, database parent, or schema resource cannot be accessed
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
        Connection connection = JDBC.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString(), config.toProperties());

        try {
            SqliteSchemaInitializer.initialize(connection, root.toUri().toASCIIString());

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
     * Returns a read-only lexical search service usable for this storage's lifetime.
     * Repeated searches see committed replacements; this service never closes the connection,
     * rebuilds the index or commits the caller's transaction. Serialize access to this storage.
     * Opening storage itself may still create or migrate an index.
     *
     * <p>For an already opened storage, prepare a corpus and query it:
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
     * Atomically saves a document's manifest and replaces all its chunks while preserving an existing file ID.
     *
     * @param document the extracted document carrying the raw-byte hash
     * @param chunks the complete chunk list in contiguous zero-based order, possibly empty
     * @return the saved manifest entry
     * @throws SQLException if persistence fails, with prior data restored when rollback succeeds
     * @throws IllegalArgumentException if chunk identity, order, or location does not match the storage contract
     * @throws NullPointerException if document, chunks, or a chunk is null
     */
    public StoredFile replaceFile(Document document, List<Chunk> chunks) throws SQLException {
        Objects.requireNonNull(document, "document");
        List<Chunk> replacement = List.copyOf(chunks);

        for (int index = 0; index < replacement.size(); index++) {
            Chunk chunk = replacement.get(index);

            if (!chunk.sourcePath().equals(document.sourcePath()) || chunk.documentType() != document.type()
                    || chunk.index() != index || !(chunk.sourceLocation() instanceof LineRange)) {
                throw new IllegalArgumentException("Replacement chunks must match the document and have contiguous indices and line ranges");
            }
        }

        return inScope(() -> {
            StoredFile file = files().save(document.sourcePath(), document.type(), document.contentHash());
            SqliteChunkRepository repository = chunks();
            repository.deleteByFileId(file.id());

            for (Chunk chunk : replacement) {
                repository.insert(file.id(), chunk);
            }

            return file;
        });
    }

    /* Keeps multi-statement repository operations atomic while preserving any caller-owned outer transaction. */
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
