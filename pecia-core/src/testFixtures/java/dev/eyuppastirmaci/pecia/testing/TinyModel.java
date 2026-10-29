package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.semantic.ModelOutcome;
import dev.eyuppastirmaci.pecia.semantic.SemanticModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tiny ONNX models that {@code semantic/tiny/generate_tiny_models.py} writes, for semantic runs that must neither
 * download nor load the real model.
 */
public final class TinyModel {

    private static final Map<String, Path> EXTRACTED = new ConcurrentHashMap<>();

    private TinyModel() {}

    /**
     * Returns a file holding a tiny model by name, such as {@code tiny} or {@code non-finite}. ONNX Runtime reads models
     * from files, so a model packaged in a JAR is copied to a temporary file once per JVM.
     */
    public static Path path(String name) {
        return EXTRACTED.computeIfAbsent(name, TinyModel::locate);
    }

    /** Returns the valid tiny model as if the model cache had found it. */
    public static ModelOutcome cached() {
        return cached("tiny");
    }

    /** Returns a tiny model by name as if the model cache had found it. */
    public static ModelOutcome cached(String name) {
        return new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, path(name));
    }

    private static Path locate(String name) {
        String resource = "/semantic/tiny/" + name + ".onnx";
        URL url = TinyModel.class.getResource(resource);

        if (url == null) {
            throw new IllegalArgumentException("No tiny model named " + name);
        }

        try {
            if (url.getProtocol().equals("file")) {
                return Path.of(url.toURI());
            }

            Path copy = Files.createTempFile("pecia-" + name + "-", ".onnx");
            copy.toFile().deleteOnExit();

            try (InputStream input = url.openStream()) {
                Files.copy(input, copy, StandardCopyOption.REPLACE_EXISTING);
            }

            return copy;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException("Resource URLs are valid URIs", impossible);
        }
    }
}
