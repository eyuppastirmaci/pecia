package dev.eyuppastirmaci.pecia.semantic;

import java.nio.file.Path;
import java.util.List;

/** Runs in a separate JVM for {@link EmbedderLoadingTest}, which reads the class initialization log it leaves. */
public final class EmbedderLoadingProbe {

    private EmbedderLoadingProbe() {}

    /** Takes a mode and the tiny model's path, and prints one line describing the outcome. */
    public static void main(String[] args) throws Exception {
        Path model = Path.of(args[1]);
        ModelOutcome outcome = new ModelOutcome(ModelOutcome.Status.CACHED, SemanticModel.MINILM, model);

        switch (args[0]) {
            case "initialize" -> {
                // Everything a run touches before it opens an embedder, including a refused unsupported platform.
                for (Class<?> type : List.of(Embedder.class, EmbeddingException.class, ModelCache.class)) {
                    Class.forName(type.getName(), true, EmbedderLoadingProbe.class.getClassLoader());
                }
                System.out.println(failure(() -> Embedder.open(model, "Mac OS X", "x86_64")));
            }
            case "embed" -> {
                try (Embedder embedder = Embedder.open(outcome)) {
                    System.out.println("EMBEDDED " + embedder.embed(List.of("hello")).length);
                }
            }
            // The second attempt meets the class that failed to initialize and must fail just as clearly.
            case "broken-runtime" ->
                System.out.println(failure(() -> Embedder.open(outcome)) + " " + failure(() -> Embedder.open(outcome)));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static String failure(Opening opening) {
        try {
            opening.open().close();
            return "OPENED";
        } catch (EmbeddingException failure) {
            return failure.reason().name();
        }
    }

    private interface Opening {
        Embedder open() throws EmbeddingException;
    }
}
