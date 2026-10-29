package dev.eyuppastirmaci.pecia.semantic;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The embedding model behind semantic search, pinned to one file at one revision with its size and SHA-256. Pecia
 * supports only all-MiniLM-L6-v2, and its descriptor is fixed in code, so nothing at run time can change which bytes
 * Pecia trusts.
 */
public final class SemanticModel {

    // Declared before MINILM, whose construction validates against them.
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    /** all-MiniLM-L6-v2 in ONNX form, at the revision of the bundled tokenizer vocabulary. */
    public static final SemanticModel MINILM = new SemanticModel(
            "all-MiniLM-L6-v2",
            URI.create("https://huggingface.co/"),
            "sentence-transformers/all-MiniLM-L6-v2",
            "1110a243fdf4706b3f48f1d95db1a4f5529b4d41",
            "onnx/model.onnx",
            90_405_214L,
            "6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452");

    private final String name;
    private final URI host;
    private final String repository;
    private final String revision;
    private final String file;
    private final long size;
    private final String sha256;

    /**
     * Creates a descriptor; only Pecia and its tests define models.
     *
     * @param name the model's short name, used as its cache directory name
     * @param host the absolute base URI the repository is served from, ending with a slash
     * @param repository the repository path on the host, such as {@code owner/name}
     * @param revision the pinned 40-character commit
     * @param file the file's path within the repository, with forward slashes
     * @param size the file's length in bytes
     * @param sha256 the file's lowercase hexadecimal SHA-256
     * @throws IllegalArgumentException if a value is malformed
     */
    SemanticModel(String name, URI host, String repository, String revision, String file, long size, String sha256) {
        this.name = require(name, NAME, "name");
        this.host = Objects.requireNonNull(host, "host");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.revision = require(revision, REVISION, "revision");
        this.file = Objects.requireNonNull(file, "file");
        this.size = size;
        this.sha256 = require(sha256, SHA_256, "sha256");

        if (!host.isAbsolute() || !host.getPath().endsWith("/")) {
            throw new IllegalArgumentException("host must be an absolute URI ending with a slash: " + host);
        }

        if (!relativePath(repository) || !relativePath(file)) {
            throw new IllegalArgumentException(
                    "repository and file must be relative paths: " + repository + ", " + file);
        }

        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive, got " + size);
        }
    }

    /** Returns the model's short name, such as {@code all-MiniLM-L6-v2}. */
    public String name() {
        return name;
    }

    /** Returns the repository path on the host, such as {@code sentence-transformers/all-MiniLM-L6-v2}. */
    public String repository() {
        return repository;
    }

    /** Returns the pinned commit. */
    public String revision() {
        return revision;
    }

    /** Returns the file's path within the repository, such as {@code onnx/model.onnx}. */
    public String file() {
        return file;
    }

    /** Returns the file's length in bytes. */
    public long size() {
        return size;
    }

    /** Returns the file's lowercase hexadecimal SHA-256. */
    public String sha256() {
        return sha256;
    }

    /** Returns the URI the pinned file is downloaded from. */
    public URI downloadUri() {
        return host.resolve(repository + "/resolve/" + revision + "/" + file);
    }

    /** Returns where the verified file lives under a cache root: {@code models/<name>/<revision>/<file name>}. */
    Path cachedFile(Path cacheRoot) {
        String fileName = file.substring(file.lastIndexOf('/') + 1);

        return cacheRoot.resolve("models").resolve(name).resolve(revision).resolve(fileName);
    }

    private static String require(String value, Pattern pattern, String field) {
        Objects.requireNonNull(value, field);

        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is malformed: " + value);
        }

        return value;
    }

    private static boolean relativePath(String path) {
        return Arrays.stream(path.split("/", -1))
                .allMatch(segment -> NAME.matcher(segment).matches() && !segment.equals(".") && !segment.equals(".."));
    }
}
