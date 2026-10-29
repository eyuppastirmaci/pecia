package dev.eyuppastirmaci.pecia.storage.sqlite;

import dev.eyuppastirmaci.pecia.chunking.ChunkIdGenerator;
import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.content.Chunk;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.Document;
import dev.eyuppastirmaci.pecia.content.ExtractionRequest;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.index.IndexDecision;
import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import dev.eyuppastirmaci.pecia.search.HybridSearch;
import dev.eyuppastirmaci.pecia.search.LexicalSearch;
import dev.eyuppastirmaci.pecia.search.SemanticSearch;
import dev.eyuppastirmaci.pecia.semantic.EmbeddingProfile;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import dev.eyuppastirmaci.pecia.storage.model.StoredChunk;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.model.UnembeddedChunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.sqlite.JDBC;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

/** Owns a SQLite index connection and exposes repositories and lexical search for its lifetime. */
public final class SqliteStorage implements AutoCloseable {

    private final Connection connection;
    private boolean vectorExtensionLoaded;

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
     * Loads the sqlite-vec extension from a verified library file into this storage's connection, so its vector
     * functions can be used, and turns extension loading off again so no SQL can load another one. Lexical storage
     * never calls this.
     *
     * @param library the platform's library, as {@code VectorExtension.library()} returns it
     * @throws SQLException if SQLite cannot load the library
     * @throws NullPointerException if library is null
     */
    public void loadVectorExtension(Path library) throws SQLException {
        String path = library.toAbsolutePath().toString();
        var database = connection.unwrap(SQLiteConnection.class).getDatabase();
        database.enable_load_extension(true);

        try (var load = connection.prepareStatement("SELECT load_extension(?)")) {
            load.setString(1, path);
            load.executeQuery().close();
        } finally {
            database.enable_load_extension(false);
        }

        vectorExtensionLoaded = true;
    }

    /**
     * Returns nearest-neighbor search over this storage's embeddings of the given profile, borrowing the existing
     * connection. Only that profile's embeddings are compared with a query.
     *
     * @throws IllegalStateException if the vector extension has not been loaded with {@link #loadVectorExtension}
     * @throws NullPointerException if profile is null
     */
    public SemanticSearch semanticSearch(EmbeddingProfile profile) {
        Objects.requireNonNull(profile, "profile");
        requireVectorExtension();

        return new SqliteSemanticSearch(new SqliteVectorRetriever(this), profile);
    }

    /**
     * Returns hybrid search over this storage's lexical index and its embeddings of the given profile, borrowing the
     * existing connection. Both sides' candidates are read in one snapshot.
     *
     * @throws IllegalStateException if the vector extension has not been loaded with {@link #loadVectorExtension}
     * @throws NullPointerException if profile is null
     */
    public HybridSearch hybridSearch(EmbeddingProfile profile) {
        Objects.requireNonNull(profile, "profile");
        requireVectorExtension();

        return new SqliteHybridSearch(this, new SqliteLexicalRetriever(this), new SqliteVectorRetriever(this), profile);
    }

    private void requireVectorExtension() {
        if (!vectorExtensionLoaded) {
            throw new IllegalStateException("Semantic search needs the vector extension; load it first");
        }
    }

    /**
     * Returns a file repository using this storage's owned connection.
     *
     * @return the repository usable until this storage is closed
     */
    public SqliteFileRepository files() {
        return new SqliteFileRepository(this);
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
     * Finds a file whose content, type, and complete chunking identity match in one database snapshot.
     * The stored fingerprint and every deterministic chunk ID are validated before returning a match.
     * Legacy profiles and invalidated processing state require reindexing. The caller must load and
     * validate the current source before this lookup; this method never writes data.
     *
     * @return the matching manifest entry, or empty when the file needs indexing
     * @throws NullPointerException if an argument is null
     * @throws SQLException if reading fails or stored identities are inconsistent
     */
    public Optional<StoredFile> findUnchangedFile(
            ExtractionRequest request, ContentHash contentHash, ChunkingIdentity identity) throws SQLException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(identity, "identity");

        return inScope(() -> {
            var previous = files().findByPath(request.sourcePath());

            if (previous.isEmpty()) {
                return Optional.empty();
            }

            StoredFile file = previous.orElseThrow();
            var storedIdentity = files().findChunkingIdentity(file.id());

            if (storedIdentity.isEmpty()) {
                return Optional.empty();
            }

            IndexDecision decision =
                    IndexDecision.evaluate(request, contentHash, identity, file, storedIdentity.orElseThrow());

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
     * file ID. Clears processing profiles and deterministic IDs because this caller supplies none.
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

        return inScope(() -> saveReplacement(document, replacement).file());
    }

    /**
     * Atomically replaces a file and commits the profile used to produce its chunks. The profile is
     * written last; a failure restores the previous manifest, chunks, search entries, and profile.
     * This legacy profile does not establish deterministic chunk IDs.
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
            StoredFile file = saveReplacement(document, replacement).file();
            files().saveIndexingProfile(file.id(), profile);

            return file;
        });
    }

    /**
     * Atomically replaces a file, deriving project-local IDs from its path, chunk positions, and
     * complete chunking identity. All inputs and IDs are validated before writing. Chunk metadata is
     * saved before IDs, and the profile is written last so incomplete replacements never look current.
     * Content changes at the same position retain an ID when the identity is unchanged.
     *
     * @param identity the extraction, tokenizer, and chunking compatibility used for this replacement
     * @return the saved manifest entry, preserving an existing file ID
     * @throws SQLException if persistence fails, with previous content and identities restored when
     *     rollback succeeds; an outer transaction remains caller-owned
     * @throws IllegalArgumentException if chunks do not match the document or storage contract, or
     *     the source path cannot be represented losslessly as Unicode
     * @throws NullPointerException if document, chunks, a chunk, or identity is null
     */
    public StoredFile replaceFile(Document document, List<Chunk> chunks, ChunkingIdentity identity)
            throws SQLException {
        Objects.requireNonNull(identity, "identity");

        return replaceIdentified(document, validateChunks(document, chunks), identity, null, List.of());
    }

    /**
     * Atomically replaces a file like {@link #replaceFile(Document, List, ChunkingIdentity)} and stores one embedding
     * per chunk under the given profile, in the same transaction, so the file's chunks never appear without their
     * vectors. The profile's stored row is reused when its fingerprint is already present.
     *
     * @param profile how the vectors were made
     * @param vectors one vector per chunk, in chunk order, each with the profile's dimensions and only finite values
     * @return the saved manifest entry, preserving an existing file ID
     * @throws SQLException if persistence fails, with previous content, identities, and vectors restored when
     *     rollback succeeds; an outer transaction remains caller-owned
     * @throws IllegalArgumentException if chunks do not match the document or storage contract, or the vectors do not
     *     match the chunks and profile
     * @throws NullPointerException if any argument, chunk, or vector is null
     */
    public StoredFile replaceFile(
            Document document,
            List<Chunk> chunks,
            ChunkingIdentity identity,
            EmbeddingProfile profile,
            List<float[]> vectors)
            throws SQLException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(profile, "profile");
        List<Chunk> replacement = validateChunks(document, chunks);

        return replaceIdentified(
                document, replacement, identity, profile, validateVectors(replacement.size(), profile, vectors));
    }

    /** Writes chunks, IDs, optional vectors, and finally the chunking profile; {@code profile} is null without vectors. */
    private StoredFile replaceIdentified(
            Document document,
            List<Chunk> replacement,
            ChunkingIdentity identity,
            EmbeddingProfile profile,
            List<float[]> vectors)
            throws SQLException {
        ChunkIdGenerator generator = new ChunkIdGenerator(identity);

        // Empty files still need a path that can participate in deterministic identities.
        if (replacement.isEmpty()) {
            generator.generate(document.sourcePath(), 0);
        }

        List<ChunkId> chunkIds = replacement.stream().map(generator::generate).toList();

        return inScope(() -> {
            if (profile != null) {
                new SqliteEmbeddingRepository(connection).requireOnlyProfile(profile.fingerprint());
            }

            SavedReplacement saved = saveReplacement(document, replacement);
            chunks().saveIdentities(saved.chunks(), chunkIds);

            if (profile != null) {
                SqliteEmbeddingRepository embeddings = new SqliteEmbeddingRepository(connection);
                embeddings.saveVectors(saved.chunks(), embeddings.profileId(profile), vectors);
            }

            files().saveChunkingIdentity(saved.file().id(), identity);

            return saved.file();
        });
    }

    /**
     * Returns a file's stored chunks that have no embedding, in position order, with the content an embedding would be
     * made from. This method never writes data.
     *
     * @param fileId the positive manifest ID; an unknown file has no chunks
     * @throws IllegalArgumentException if fileId is not positive
     * @throws SQLException if reading fails
     */
    public List<UnembeddedChunk> findUnembeddedChunks(long fileId) throws SQLException {
        if (fileId <= 0) {
            throw new IllegalArgumentException("fileId must be positive: " + fileId);
        }

        return new SqliteEmbeddingRepository(connection).findUnembedded(fileId);
    }

    /**
     * Stores embeddings for stored chunks atomically under the given profile. A vector is stored only for a chunk that
     * still exists with the content it was made from and still has no embedding, so a chunk changed or deleted
     * meanwhile, or embedded by another run, never receives a vector for other content; such chunks are skipped.
     *
     * @param chunks the chunks the vectors were made from, as returned by {@link #findUnembeddedChunks(long)}
     * @param vectors one vector per chunk, in chunk order, each with the profile's dimensions and only finite values
     * @return how many vectors were stored; the others were skipped
     * @throws SQLException if persistence fails, with no vector of this call stored when rollback succeeds; an outer
     *     transaction remains caller-owned
     * @throws IllegalArgumentException if the vectors do not match the chunks and profile
     * @throws NullPointerException if any argument, chunk, or vector is null
     */
    public int storeEmbeddings(EmbeddingProfile profile, List<UnembeddedChunk> chunks, List<float[]> vectors)
            throws SQLException {
        Objects.requireNonNull(profile, "profile");
        List<UnembeddedChunk> targets = List.copyOf(chunks);
        List<float[]> embeddings = validateVectors(targets.size(), profile, vectors);

        if (targets.isEmpty()) {
            return 0;
        }

        return inScope(() -> {
            SqliteEmbeddingRepository repository = new SqliteEmbeddingRepository(connection);
            repository.requireOnlyProfile(profile.fingerprint());

            return repository.saveIfUnchanged(targets, repository.profileId(profile), embeddings);
        });
    }

    /**
     * Deletes every embedding of a profile other than the given one, and the profile rows left unused, as one atomic
     * operation. Chunks and full-text rows are not touched, so the deleted embeddings can be made again from storage.
     *
     * @return how many embeddings were deleted
     * @throws SQLException if deletion fails, with nothing deleted when rollback succeeds; an outer transaction remains
     *     caller-owned
     * @throws NullPointerException if profile is null
     */
    public long discardIncompatibleEmbeddings(EmbeddingProfile profile) throws SQLException {
        String fingerprint = profile.fingerprint();

        return inScope(() -> new SqliteEmbeddingRepository(connection).discardOtherProfiles(fingerprint));
    }

    /**
     * Counts stored chunks by whether they have an embedding of the given profile, of another profile, or none, in one
     * read. This method never writes data.
     *
     * @throws SQLException if reading fails
     * @throws NullPointerException if profile is null
     */
    public EmbeddingCoverage embeddingCoverage(EmbeddingProfile profile) throws SQLException {
        return new SqliteEmbeddingRepository(connection).coverage(profile.fingerprint());
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

    private static List<float[]> validateVectors(int chunks, EmbeddingProfile profile, List<float[]> vectors) {
        List<float[]> embeddings = List.copyOf(vectors);

        if (embeddings.size() != chunks) {
            throw new IllegalArgumentException(
                    "Expected one vector per chunk: " + chunks + " chunks, " + embeddings.size() + " vectors");
        }

        for (float[] vector : embeddings) {
            if (vector.length != profile.dimensions()) {
                throw new IllegalArgumentException(
                        "Vectors must have " + profile.dimensions() + " dimensions, not " + vector.length);
            }

            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException("Vectors must hold only finite values");
                }
            }
        }

        return embeddings;
    }

    private SavedReplacement saveReplacement(Document document, List<Chunk> replacement) throws SQLException {
        StoredFile file = files().save(document.sourcePath(), document.type(), document.contentHash());
        SqliteChunkRepository repository = chunks();
        repository.deleteByFileId(file.id());
        List<StoredChunk> saved = new ArrayList<>();

        for (Chunk chunk : replacement) {
            saved.add(repository.insert(file.id(), chunk));
        }

        return new SavedReplacement(file, List.copyOf(saved));
    }

    private record SavedReplacement(StoredFile file, List<StoredChunk> chunks) {}

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
