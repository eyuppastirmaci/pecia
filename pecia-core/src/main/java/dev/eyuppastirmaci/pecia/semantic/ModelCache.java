package dev.eyuppastirmaci.pecia.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Keeps the pinned semantic model in the user's cache, downloading and verifying it when it is missing or damaged.
 * A file lock in the model's directory serializes runs across processes; a download goes to a temporary file that is
 * moved into place only after its size and SHA-256 match, so the model's name never holds an unverified file.
 */
public final class ModelCache implements ModelProvider, ModelLookup {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(60);
    private static final String USER_AGENT = "pecia";
    private static final String LOCK_FILE = ".lock";
    private static final String TEMPORARY_PREFIX = ".download-";
    private static final String TEMPORARY_SUFFIX = ".part";
    private static final int BUFFER_SIZE = 64 * 1024;
    // File locks are held for the whole JVM, so threads of one process first wait on a monitor per model file.
    private static final ConcurrentHashMap<Path, Object> MONITORS = new ConcurrentHashMap<>();

    private final SemanticModel model;
    private final Supplier<Path> cacheRoot;
    private final Supplier<HttpClient> client;
    private final Duration stallTimeout;

    /**
     * Creates a cache for all-MiniLM-L6-v2 in the user's cache directory. Nothing is resolved, created, or contacted
     * until {@link #ensure()} or {@link #find()} runs.
     */
    public ModelCache() {
        this(SemanticModel.MINILM, CacheDirectory::resolve, ModelCache::defaultClient, STALL_TIMEOUT);
    }

    ModelCache(SemanticModel model, Supplier<Path> cacheRoot, Supplier<HttpClient> client, Duration stallTimeout) {
        this.model = Objects.requireNonNull(model, "model");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot");
        this.client = Objects.requireNonNull(client, "client");
        this.stallTimeout = Objects.requireNonNull(stallTimeout, "stallTimeout");
    }

    static HttpClient defaultClient() {
        return client(ProxyEnvironment.selector(System.getenv(), System::getProperty));
    }

    static HttpClient client(ProxySelector proxy) {
        // NORMAL follows redirects except from HTTPS to HTTP, so a redirect cannot downgrade the transport.
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(proxy)
                .build();
    }

    /**
     * Returns the verified model file, downloading it when it is missing or does not match its pinned size and
     * SHA-256. Waits while another thread or process works on the same file.
     *
     * @return whether the file was already cached or was downloaded now, and where it is
     * @throws ModelException if the file cannot be made available; no unverified file is left under its name
     * @throws IllegalArgumentException if the cache directory or proxy setting is invalid
     */
    public ModelOutcome ensure() throws ModelException {
        return ensure(DownloadListener.NONE);
    }

    /**
     * Works like {@link #ensure()} and tells the listener before a download starts, and as its bytes arrive when it is
     * a {@link DownloadProgressListener}.
     *
     * @throws NullPointerException if listener is null
     */
    @Override
    public ModelOutcome ensure(DownloadListener listener) throws ModelException {
        Objects.requireNonNull(listener, "listener");
        Path target = model.cachedFile(cacheRoot.get());

        synchronized (MONITORS.computeIfAbsent(target, path -> new Object())) {
            return ensureUnderFileLock(target, listener);
        }
    }

    /**
     * Returns the model file if it is cached and matches its pinned size and SHA-256. Takes no lock and writes
     * nothing: a download moves only a verified file into place, so the model's name never holds a partial one.
     *
     * @throws ModelException if the cache cannot be read
     */
    @Override
    public Optional<ModelOutcome> find() throws ModelException {
        Path target = model.cachedFile(cacheRoot.get());

        try {
            if (Files.isRegularFile(target) && verified(target)) {
                return Optional.of(new ModelOutcome(ModelOutcome.Status.CACHED, model, target));
            }
        } catch (NoSuchFileException deleted) {
            // Another run deleted a damaged copy after the check, before downloading the model again.
        } catch (IOException failure) {
            throw cacheUnavailable(target.getParent(), failure);
        }

        return Optional.empty();
    }

    private ModelOutcome ensureUnderFileLock(Path target, DownloadListener listener) throws ModelException {
        Path directory = target.getParent();

        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw cacheUnavailable(directory, failure);
        }

        try (FileChannel channel =
                FileChannel.open(directory.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = channel.lock();

            try {
                return ensureLocked(target, listener);
            } finally {
                lock.release();
            }
        } catch (FileLockInterruptionException | ClosedByInterruptException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted(interrupted);
        } catch (IOException failure) {
            throw cacheUnavailable(directory, failure);
        }
    }

    private ModelOutcome ensureLocked(Path target, DownloadListener listener) throws IOException, ModelException {
        deleteLeftovers(target.getParent());

        if (Files.isRegularFile(target)) {
            if (verified(target)) {
                return new ModelOutcome(ModelOutcome.Status.CACHED, model, target);
            }

            Files.delete(target);
        }

        listener.downloading(model, target);
        download(target, listener);

        return new ModelOutcome(ModelOutcome.Status.DOWNLOADED, model, target);
    }

    /** Removes temporary files of interrupted runs; holding the lock guarantees that no other run is writing one. */
    private static void deleteLeftovers(Path directory) throws IOException {
        try (DirectoryStream<Path> leftovers =
                Files.newDirectoryStream(directory, TEMPORARY_PREFIX + "*" + TEMPORARY_SUFFIX)) {
            for (Path leftover : leftovers) {
                Files.deleteIfExists(leftover);
            }
        }
    }

    private boolean verified(Path file) throws IOException {
        if (Files.size(file) != model.size()) {
            return false;
        }

        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                digest.update(buffer, 0, read);
            }
        }

        return HexFormat.of().formatHex(digest.digest()).equals(model.sha256());
    }

    private void download(Path target, DownloadListener listener) throws IOException, ModelException {
        Path temporary = Files.createTempFile(target.getParent(), TEMPORARY_PREFIX, TEMPORARY_SUFFIX);

        try {
            fetchInto(temporary, listener);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next run deletes leftovers under the lock, and the original failure matters more.
            }
        }
    }

    private void fetchInto(Path temporary, DownloadListener listener) throws ModelException {
        HttpRequest request = HttpRequest.newBuilder(model.downloadUri())
                // Bounds the wait for response headers; reading the body has its own stall timeout.
                .timeout(stallTimeout)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response;

        try {
            response = client.get().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (ConnectException | HttpConnectTimeoutException | SecurityException unreachable) {
            // Unknown hosts also arrive as ConnectException, and a security policy denies name resolution itself.
            throw new ModelException(
                    ModelException.Reason.OFFLINE,
                    "Could not reach " + host() + " to download " + model.name(),
                    unreachable);
        } catch (IOException failure) {
            throw transferFailed(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted(interrupted);
        }

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new ModelException(
                        ModelException.Reason.HTTP_STATUS,
                        host() + " answered HTTP " + response.statusCode() + " for " + model.name(),
                        null);
            }

            copyVerified(body, temporary, listener);
        } catch (IOException closeFailure) {
            throw transferFailed(closeFailure);
        }
    }

    private void copyVerified(InputStream body, Path temporary, DownloadListener listener) throws ModelException {
        MessageDigest digest = sha256();
        long total = 0;
        ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "pecia-model-download");
            thread.setDaemon(true);
            return thread;
        });

        // Reads run on the reader thread, so an IOException caught here comes from the cache file.
        try (OutputStream output = Files.newOutputStream(temporary)) {
            byte[] buffer = new byte[BUFFER_SIZE];

            for (int read; (read = readWithin(reader, body, buffer)) != -1; ) {
                total += read;

                if (total > model.size()) {
                    throw sizeMismatch("more than " + model.size());
                }

                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
                if (listener instanceof DownloadProgressListener progress) {
                    progress.received(total, model.size());
                }
            }
        } catch (IOException failure) {
            throw cacheUnavailable(temporary.getParent(), failure);
        } finally {
            reader.shutdownNow();
        }

        if (total != model.size()) {
            throw sizeMismatch(Long.toString(total));
        }

        if (!HexFormat.of().formatHex(digest.digest()).equals(model.sha256())) {
            throw new ModelException(
                    ModelException.Reason.CHECKSUM_MISMATCH,
                    "The downloaded " + model.name() + " does not match its pinned SHA-256",
                    null);
        }
    }

    /** Reads once, failing when no byte arrives within the stall timeout; HttpClient has no read timeout. */
    private int readWithin(ExecutorService reader, InputStream body, byte[] buffer) throws ModelException {
        Future<Integer> read = reader.submit(() -> body.read(buffer));

        try {
            return read.get(stallTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stalled) {
            read.cancel(true);
            throw new ModelException(
                    ModelException.Reason.TRANSFER_FAILED,
                    "The download of " + model.name() + " from " + host() + " stalled for " + stallTimeout.toSeconds()
                            + " seconds",
                    stalled);
        } catch (ExecutionException failed) {
            throw transferFailed(failed.getCause());
        } catch (InterruptedException interrupted) {
            read.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted(interrupted);
        }
    }

    private String host() {
        return model.downloadUri().getHost();
    }

    private ModelException transferFailed(Throwable cause) {
        return new ModelException(
                ModelException.Reason.TRANSFER_FAILED,
                "The download of " + model.name() + " from " + host() + " failed before it completed",
                cause);
    }

    private ModelException sizeMismatch(String actual) {
        return new ModelException(
                ModelException.Reason.CHECKSUM_MISMATCH,
                "The downloaded " + model.name() + " has " + actual + " bytes instead of " + model.size(),
                null);
    }

    private ModelException interrupted(Throwable cause) {
        return new ModelException(
                ModelException.Reason.INTERRUPTED, "Making " + model.name() + " available was interrupted", cause);
    }

    private static ModelException cacheUnavailable(Path directory, Throwable cause) {
        return new ModelException(
                ModelException.Reason.CACHE_UNAVAILABLE, "Could not use the model cache at " + directory, cause);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform provides SHA-256", e);
        }
    }
}
