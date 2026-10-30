package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the cache against a local HTTP server with a test descriptor; nothing here reaches the internet. */
class ModelCacheTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final String MODEL_PATH = "/test/model/resolve/" + REVISION + "/onnx/model.bin";
    private static final byte[] PAYLOAD = payload();

    @TempDir
    Path cacheRoot;

    private final AtomicInteger requests = new AtomicInteger();
    private ExecutorService serverThreads;
    private HttpServer server;
    private volatile HttpHandler behavior = exchange -> respond(exchange, 200, PAYLOAD);
    private SemanticModel model;

    @BeforeEach
    void startServer() throws IOException {
        serverThreads = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            behavior.handle(exchange);
        });
        server.setExecutor(serverThreads);
        server.start();
        model = descriptor(server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    @Test
    void firstUseDownloadsAndPublishesTheVerifiedFile() throws Exception {
        ModelOutcome outcome = cache().ensure();

        assertEquals(ModelOutcome.Status.DOWNLOADED, outcome.status());
        assertEquals(model.cachedFile(cacheRoot), outcome.file());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(outcome.file()));
        assertEquals(1, requests.get());
        assertEquals(List.of(".lock", "model.bin"), entries());
    }

    @Test
    void aDownloadReportsItsBytesUpToTheSizeAndACachedModelReportsNone() throws Exception {
        List<String> heard = new ArrayList<>();
        DownloadProgressListener listener = new DownloadProgressListener() {
            @Override
            public void downloading(SemanticModel downloading, Path file) {
                heard.add("downloading " + file.getFileName());
            }

            @Override
            public void received(long received, long size) {
                heard.add(received + "/" + size);
            }
        };

        cache().ensure(listener);

        assertEquals("downloading model.bin", heard.getFirst());
        List<Long> received = heard.subList(1, heard.size()).stream()
                .map(update -> Long.parseLong(update.substring(0, update.indexOf('/'))))
                .toList();
        assertTrue(received.size() > 1, heard.toString());
        assertEquals((long) PAYLOAD.length, received.getLast());
        assertTrue(heard.subList(1, heard.size()).stream().allMatch(update -> update.endsWith("/" + PAYLOAD.length)));

        for (int index = 1; index < received.size(); index++) {
            assertTrue(received.get(index) > received.get(index - 1), received.toString());
        }

        heard.clear();
        cache().ensure(listener);

        assertEquals(List.of(), heard);
    }

    @Test
    void anInterruptedTransferNeverReportsTheWholeSize() throws Exception {
        behavior = exchange -> {
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            exchange.getResponseBody().write(PAYLOAD, 0, PAYLOAD.length / 2);
            exchange.getResponseBody().flush();
            exchange.close();
        };
        List<Long> received = new ArrayList<>();
        DownloadProgressListener listener = new DownloadProgressListener() {
            @Override
            public void downloading(SemanticModel downloading, Path file) {}

            @Override
            public void received(long bytes, long size) {
                received.add(bytes);
            }
        };

        assertThrows(ModelException.class, () -> cache().ensure(listener));

        assertTrue(received.stream().allMatch(bytes -> bytes <= PAYLOAD.length / 2), received.toString());
    }

    @Test
    void laterUsesVerifyTheCachedFileWithoutARequest() throws Exception {
        cache().ensure();

        ModelOutcome outcome = cache().ensure();

        assertEquals(ModelOutcome.Status.CACHED, outcome.status());
        assertEquals(1, requests.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"flipped", "truncated", "extended"})
    void aDamagedCachedFileIsDownloadedAgain(String damage) throws Exception {
        Path file = cache().ensure().file();
        byte[] bytes = Files.readAllBytes(file);
        switch (damage) {
            case "flipped" -> bytes[bytes.length / 2] ^= 1;
            case "truncated" -> bytes = Arrays.copyOf(bytes, bytes.length - 1);
            default -> bytes = Arrays.copyOf(bytes, bytes.length + 1);
        }
        Files.write(file, bytes);

        ModelOutcome outcome = cache().ensure();

        assertEquals(ModelOutcome.Status.DOWNLOADED, outcome.status());
        assertArrayEquals(PAYLOAD, Files.readAllBytes(file));
        assertEquals(2, requests.get());
    }

    @Test
    void findingReturnsOnlyAVerifiedCachedFileAndNeverDownloadsOrWrites() throws Exception {
        assertEquals(Optional.empty(), cache().find());
        assertFalse(Files.exists(cacheRoot.resolve("models")), "Finding must not create the cache");

        Path file = cache().ensure().file();
        assertEquals(Optional.of(new ModelOutcome(ModelOutcome.Status.CACHED, model, file)), cache().find());

        byte[] damaged = PAYLOAD.clone();
        damaged[0] ^= 1;
        Files.write(file, damaged);
        List<String> before = entries();

        assertEquals(Optional.empty(), cache().find());
        Files.write(file, Arrays.copyOf(PAYLOAD, PAYLOAD.length - 1));
        assertEquals(Optional.empty(), cache().find());
        assertEquals(before, entries());
        assertEquals(1, requests.get());
    }

    @Test
    void findingFailsWhenTheCachedModelCannotBeRead() throws Exception {
        Path file = cache().ensure().file();
        assumeTrue(Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class));
        Files.setPosixFilePermissions(file, Set.of());

        try {
            assumeFalse(Files.isReadable(file), "The permission change has no effect for this user");

            ModelException failure = assertThrows(ModelException.class, () -> cache().find());

            assertEquals(ModelException.Reason.CACHE_UNAVAILABLE, failure.reason());
        } finally {
            Files.setPosixFilePermissions(
                    file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void followsARedirectToAnotherPathLikeTheHostsCdn() throws Exception {
        behavior = exchange -> {
            if (exchange.getRequestURI().getPath().equals(MODEL_PATH)) {
                exchange.getResponseHeaders().add("Location", "/cdn/model.bin");
                respond(exchange, 302, new byte[0]);
            } else {
                respond(exchange, 200, PAYLOAD);
            }
        };

        assertEquals(ModelOutcome.Status.DOWNLOADED, cache().ensure().status());
        assertEquals(2, requests.get());
    }

    @Test
    void httpsProxyCarriesTheDownloadThroughTheProxy() throws Exception {
        // The local server plays the proxy: it receives the absolute URI of a host that does not resolve.
        List<String> seen = new CopyOnWriteArrayList<>();
        behavior = exchange -> {
            seen.add(exchange.getRequestURI().toString());
            respond(exchange, 200, PAYLOAD);
        };
        model = descriptorAt("http://models.example.invalid/");
        Map<String, String> environment =
                Map.of("HTTPS_PROXY", "http://127.0.0.1:" + server.getAddress().getPort());

        ModelOutcome outcome = proxiedCache(environment).ensure();

        assertEquals(ModelOutcome.Status.DOWNLOADED, outcome.status());
        assertEquals(List.of("http://models.example.invalid" + MODEL_PATH), seen);
    }

    @Test
    void noProxyHostsAreReachedDirectly() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Map<String, String> environment =
                Map.of("HTTPS_PROXY", "http://127.0.0.1:" + closedPort, "NO_PROXY", "127.0.0.1");

        assertEquals(
                ModelOutcome.Status.DOWNLOADED,
                proxiedCache(environment).ensure().status());
        assertEquals(1, requests.get());
    }

    @Test
    void sameLengthDifferentContentIsRejectedAndNothingIsPublished() throws Exception {
        byte[] tampered = PAYLOAD.clone();
        tampered[0] ^= 1;
        behavior = exchange -> respond(exchange, 200, tampered);

        assertFailure(ModelException.Reason.CHECKSUM_MISMATCH, "does not match its pinned SHA-256");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void aBodyOfTheWrongLengthIsRejectedAndNothingIsPublished(int difference) throws Exception {
        byte[] wrongLength = Arrays.copyOf(PAYLOAD, PAYLOAD.length + difference);
        behavior = exchange -> respond(exchange, 200, wrongLength);

        assertFailure(ModelException.Reason.CHECKSUM_MISMATCH, "bytes instead of " + PAYLOAD.length);
    }

    @Test
    void aConnectionClosedMidBodyIsATransferFailure() throws Exception {
        behavior = exchange -> {
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            exchange.getResponseBody().write(PAYLOAD, 0, PAYLOAD.length / 2);
            exchange.getResponseBody().flush();
            exchange.close();
        };

        assertFailure(ModelException.Reason.TRANSFER_FAILED, "failed before it completed");
    }

    @Test
    void aStalledBodyFailsWithinTheStallTimeout() throws Exception {
        behavior = exchange -> {
            exchange.sendResponseHeaders(200, PAYLOAD.length);
            exchange.getResponseBody().write(PAYLOAD, 0, PAYLOAD.length / 2);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        long start = System.nanoTime();

        ModelException failure = assertThrows(
                ModelException.class, () -> cache(Duration.ofMillis(300)).ensure());

        assertEquals(ModelException.Reason.TRANSFER_FAILED, failure.reason());
        assertTrue(failure.getMessage().contains("stalled"), failure.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10, "The stall timeout must end the wait");
        assertNothingPublished();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void anUnsuccessfulStatusIsReportedWithItsCode(int status) throws Exception {
        behavior = exchange -> respond(exchange, status, new byte[0]);

        assertFailure(ModelException.Reason.HTTP_STATUS, "answered HTTP " + status);
    }

    @Test
    void anUnreachableHostIsOffline() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        model = descriptor(closedPort);

        assertFailure(ModelException.Reason.OFFLINE, "Could not reach 127.0.0.1 to download test-model");
    }

    @Test
    void aCacheThatCannotHoldDirectoriesIsUnavailable() throws Exception {
        Path file = Files.writeString(cacheRoot.resolve("not-a-directory"), "x");
        ModelCache blocked = new ModelCache(model, () -> file, ModelCache::defaultClient, Duration.ofSeconds(5));

        ModelException failure = assertThrows(ModelException.class, blocked::ensure);

        assertEquals(ModelException.Reason.CACHE_UNAVAILABLE, failure.reason());
        assertEquals(0, requests.get());
    }

    @Test
    void anInterruptedThreadGetsAnInterruptedFailureAndKeepsItsInterrupt() throws Exception {
        Thread.currentThread().interrupt();

        try {
            ModelException failure = assertThrows(ModelException.class, () -> cache().ensure());

            assertEquals(ModelException.Reason.INTERRUPTED, failure.reason());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals(0, requests.get());
    }

    @Test
    void temporaryFilesOfInterruptedRunsAreRemovedUnderTheLock() throws Exception {
        Path directory = Files.createDirectories(model.cachedFile(cacheRoot).getParent());
        Files.writeString(directory.resolve(".download-123.part"), "left behind");

        cache().ensure();

        assertEquals(List.of(".lock", "model.bin"), entries());
    }

    @Test
    void concurrentUsesDownloadOnce() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            Callable<ModelOutcome> use = () -> cache().ensure();
            List<Future<ModelOutcome>> outcomes = callers.invokeAll(List.of(use, use, use, use));

            long downloaded = 0;
            for (Future<ModelOutcome> outcome : outcomes) {
                if (outcome.get().status() == ModelOutcome.Status.DOWNLOADED) {
                    downloaded++;
                }
            }

            assertEquals(1, downloaded);
            assertEquals(1, requests.get());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void creatingTheCacheResolvesAndContactsNothing() {
        new ModelCache(
                model,
                () -> {
                    throw new AssertionError("The cache directory must be resolved only by ensure()");
                },
                () -> {
                    throw new AssertionError("The HTTP client must be created only by ensure()");
                },
                Duration.ofSeconds(5));

        assertEquals(0, requests.get());
    }

    private void assertFailure(ModelException.Reason reason, String messagePart) throws IOException {
        ModelException failure = assertThrows(ModelException.class, () -> cache().ensure());

        assertEquals(reason, failure.reason());
        assertTrue(failure.getMessage().contains(messagePart), failure.getMessage());
        assertNothingPublished();
    }

    private void assertNothingPublished() throws IOException {
        assertFalse(Files.exists(model.cachedFile(cacheRoot)));
        assertEquals(List.of(".lock"), entries());
    }

    private List<String> entries() throws IOException {
        try (Stream<Path> files = Files.list(model.cachedFile(cacheRoot).getParent())) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private ModelCache cache() {
        return cache(Duration.ofSeconds(5));
    }

    private ModelCache cache(Duration stallTimeout) {
        return new ModelCache(model, () -> cacheRoot, ModelCache::defaultClient, stallTimeout);
    }

    private ModelCache proxiedCache(Map<String, String> environment) {
        return new ModelCache(
                model,
                () -> cacheRoot,
                () -> ModelCache.client(ProxyEnvironment.selector(environment, name -> null)),
                Duration.ofSeconds(5));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static SemanticModel descriptor(int port) {
        return descriptorAt("http://127.0.0.1:" + port + "/");
    }

    private static SemanticModel descriptorAt(String host) {
        return new SemanticModel(
                "test-model",
                URI.create(host),
                "test/model",
                REVISION,
                "onnx/model.bin",
                PAYLOAD.length,
                sha256(PAYLOAD));
    }

    private static byte[] payload() {
        byte[] bytes = new byte[300_000];
        new Random(20).nextBytes(bytes);
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
