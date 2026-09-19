package dev.eyuppastirmaci.pecia.testing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Isolates child-process user storage and verifies lexical execution leaves no model artifacts. */
public final class OfflineEnvironment {

    private static final Pattern SQLITE_TEMPORARY_FILE =
            Pattern.compile("sqlite-[0-9]+(?:\\.[0-9]+)+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-"
                    + "(?:libsqlitejdbc\\.(?:so|dylib)|sqlitejdbc\\.dll)(?:\\.lck)?");
    private static final Pattern INITIALIZATION =
            Pattern.compile("^(?:\\[[^\\]\\r\\n]*\\])*\\[class,init\\s*\\]\\s+\\d+\\s+Initializing '([^'\\r\\n]+)'.*$");
    private static final List<String> SEMANTIC_PACKAGES = List.of(
            "ai.onnxruntime.",
            "ai.djl.",
            "org.tensorflow.",
            "org.pytorch.",
            "com.microsoft.onnxruntime.",
            "dev.eyuppastirmaci.pecia.embedding.",
            "dev.eyuppastirmaci.pecia.embed.",
            "dev.eyuppastirmaci.pecia.semantic.");
    private static final List<String> MODEL_ENVIRONMENT_PREFIXES = List.of(
            "HF_",
            "HUGGINGFACE_",
            "TRANSFORMERS_",
            "SENTENCE_TRANSFORMERS_",
            "TORCH_",
            "PYTORCH_",
            "DJL_",
            "ONNX_",
            "TFHUB_");

    private final Path sandbox;
    private final Path home;
    private final Path cache;
    private final Path temporary;

    private OfflineEnvironment(Path sandbox) {
        this.sandbox = sandbox;
        home = sandbox.resolve("home");
        cache = sandbox.resolve("cache");
        temporary = sandbox.resolve("tmp");
    }

    /** Creates fresh storage roots inside an existing directory, refusing to reuse any root. */
    public static OfflineEnvironment create(Path sandbox) throws IOException {
        Path realSandbox = sandbox.toRealPath();
        if (!Files.isDirectory(realSandbox)) {
            throw new IOException("Offline sandbox is not a directory: " + sandbox);
        }
        OfflineEnvironment isolated = new OfflineEnvironment(realSandbox);
        for (Path root : isolated.roots()) {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Offline storage already exists: " + root);
            }
        }
        for (Path root : isolated.roots()) {
            Files.createDirectory(root);
        }
        return isolated;
    }

    public Path home() {
        return home;
    }

    public Path cache() {
        return cache;
    }

    public Path temporary() {
        return temporary;
    }

    /** Sanitizes the supplied child environment without modifying the parent process environment. */
    public void configure(Map<String, String> environment) {
        Map<String, String> redirects = redirects();
        environment.keySet().removeIf(name -> {
            String upper = name.toUpperCase(Locale.ROOT);
            return redirects.containsKey(upper)
                    || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")
                            .contains(upper)
                    || upper.endsWith("_PROXY")
                    || MODEL_ENVIRONMENT_PREFIXES.stream().anyMatch(upper::startsWith);
        });
        environment.putAll(redirects);
    }

    /** Returns literal JVM arguments; callers must pass them as individual process arguments. */
    public List<String> jvmArguments() {
        return List.of(
                "-Duser.home=" + home,
                "-Djava.io.tmpdir=" + temporary,
                "-Dorg.sqlite.tmpdir=" + temporary,
                "-Djava.util.prefs.userRoot=" + temporary.resolve("user-prefs"),
                "-Djava.util.prefs.systemRoot=" + temporary.resolve("system-prefs"));
    }

    /**
     * Captures every storage entry, without following symlinks. Only SQLite native extraction files
     * directly inside the temporary root are ignored. Keys include the root name.
     */
    public Map<Path, String> snapshot() throws IOException {
        Map<Path, String> contents = new TreeMap<>();
        for (Path root : roots()) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            try (var paths = Files.walk(root)) {
                for (Path path : paths.toList()) {
                    BasicFileAttributes attributes =
                            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isRegularFile()
                            && temporary.equals(path.getParent())
                            && SQLITE_TEMPORARY_FILE
                                    .matcher(path.getFileName().toString())
                                    .matches()) {
                        continue;
                    }
                    String value;
                    if (attributes.isSymbolicLink()) {
                        value = "symlink:" + Files.readSymbolicLink(path);
                    } else if (attributes.isDirectory()) {
                        value = "directory";
                    } else if (attributes.isRegularFile()) {
                        value = "sha256:" + digest(path);
                    } else {
                        value = "other";
                    }
                    contents.put(sandbox.relativize(path), value);
                }
            }
        }
        return Map.copyOf(contents);
    }

    /** Fails if any monitored storage entry has been added, changed, or deleted. */
    public void assertUnchanged(Map<Path, String> before) throws IOException {
        Objects.requireNonNull(before, "before");
        Map<Path, String> after = snapshot();
        if (!before.equals(after)) {
            Set<Path> changed = new TreeSet<>(before.keySet());
            changed.addAll(after.keySet());
            changed.removeIf(path -> Objects.equals(before.get(path), after.get(path)));
            throw new AssertionError("Offline home/cache/tmp changed: " + changed);
        }
    }

    /** Requires a positive initialization record and rejects semantic runtime initialization. */
    public static void assertLexicalInitialization(Path log, String requiredClass) throws IOException {
        String required = normalizedClass(requiredClass);
        Set<String> initialized = initializedClasses(log);
        if (!initialized.contains(required)) {
            throw new AssertionError("Missing initialization of " + required + " in " + log);
        }
        Set<String> semantic = new TreeSet<>();
        for (String className : initialized) {
            if (SEMANTIC_PACKAGES.stream().anyMatch(className::startsWith)) {
                semantic.add(className);
            }
        }
        if (!semantic.isEmpty()) {
            throw new AssertionError("Semantic runtime initialized: " + semantic + " in " + log);
        }
    }

    /** Checks actual initialization records, excluding class loading and verification messages. */
    public static boolean initialized(Path log, String className) throws IOException {
        String normalized = normalizedClass(className);
        return initializedClasses(log).contains(normalized);
    }

    private List<Path> roots() {
        return List.of(home, cache, temporary);
    }

    private Map<String, String> redirects() {
        Map<String, String> values = new HashMap<>();
        values.put("HOME", home.toString());
        values.put("USERPROFILE", home.toString());
        String root = home.getRoot().toString();
        String drive = root.length() >= 2 && root.charAt(1) == ':' ? root.substring(0, 2) : "";
        values.put("HOMEDRIVE", drive);
        values.put("HOMEPATH", home.toString().substring(drive.length()));
        values.put("APPDATA", home.resolve("appdata/roaming").toString());
        values.put("LOCALAPPDATA", home.resolve("appdata/local").toString());
        values.put("XDG_CONFIG_HOME", home.resolve("config").toString());
        values.put("XDG_CONFIG_DIRS", home.resolve("config").toString());
        values.put("XDG_DATA_HOME", home.resolve("data").toString());
        values.put("XDG_DATA_DIRS", home.resolve("data").toString());
        values.put("XDG_STATE_HOME", home.resolve("state").toString());
        values.put("XDG_CACHE_HOME", cache.resolve("xdg").toString());
        values.put("XDG_RUNTIME_DIR", temporary.resolve("runtime").toString());
        for (String variable : List.of("TMPDIR", "TMP", "TEMP")) {
            values.put(variable, temporary.toString());
        }
        Map<String, String> modelCaches = Map.ofEntries(
                Map.entry("HF_HOME", "huggingface"),
                Map.entry("HF_HUB_CACHE", "huggingface/hub"),
                Map.entry("HUGGINGFACE_HUB_CACHE", "huggingface/hub"),
                Map.entry("HF_DATASETS_CACHE", "huggingface/datasets"),
                Map.entry("HF_ASSETS_CACHE", "huggingface/assets"),
                Map.entry("TRANSFORMERS_CACHE", "transformers"),
                Map.entry("PYTORCH_TRANSFORMERS_CACHE", "transformers"),
                Map.entry("PYTORCH_PRETRAINED_BERT_CACHE", "transformers"),
                Map.entry("SENTENCE_TRANSFORMERS_HOME", "sentence-transformers"),
                Map.entry("TORCH_HOME", "torch"),
                Map.entry("PYTORCH_HOME", "torch"),
                Map.entry("DJL_CACHE_DIR", "djl"),
                Map.entry("ONNX_HOME", "onnx"),
                Map.entry("TFHUB_CACHE_DIR", "tensorflow-hub"));
        modelCaches.forEach((variable, location) ->
                values.put(variable, cache.resolve(location).toString()));
        return values;
    }

    private static String digest(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Required SHA-256 algorithm is unavailable", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Set<String> initializedClasses(Path log) throws IOException {
        Objects.requireNonNull(log, "log");
        if (!Files.isRegularFile(log)) {
            throw new AssertionError("Missing class initialization log: " + log);
        }
        String contents = Files.readString(log);
        if (contents.isBlank()) {
            throw new AssertionError("Empty class initialization log: " + log);
        }
        Set<String> initialized = new HashSet<>();
        for (String line : contents.lines().toList()) {
            Matcher matcher = INITIALIZATION.matcher(line);
            if (matcher.matches()) {
                initialized.add(normalizedClass(matcher.group(1)));
            }
        }
        return Set.copyOf(initialized);
    }

    private static String normalizedClass(String className) {
        if (className.isBlank()) {
            throw new IllegalArgumentException("Class name must not be blank");
        }
        return className.replace('/', '.');
    }
}
