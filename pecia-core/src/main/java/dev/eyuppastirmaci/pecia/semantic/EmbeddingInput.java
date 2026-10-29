package dev.eyuppastirmaci.pecia.semantic;

import dev.eyuppastirmaci.pecia.content.ContentPath;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The text a chunk is embedded from: one line naming where the chunk comes from, its project-relative path and heading
 * path such as {@code bridge/README.md > Intent}, then a blank line and the chunk's content. The context lets short
 * chunks be found by what their file and section are about; search results and lexical search see only the content.
 */
public final class EmbeddingInput {

    /** The embedding profile's name for this input, recorded with every stored embedding. */
    public static final String NAME = "path-heading-content-v1";

    /**
     * Content tokens reserved for the context line. Chunks are kept this much below the model input, so the context
     * never pushes content out; it is a compile-time constant, so lexical chunking can use it without initializing
     * this class.
     */
    public static final int CONTEXT_TOKENS = 32;

    private static final String SEPARATOR = " > ";
    private static final String CUT = "… ";

    private EmbeddingInput() {}

    /**
     * Returns the text to embed for a chunk. A context line longer than {@link #CONTEXT_TOKENS} tokens loses its leading
     * path segments and headings first, and then leading characters, behind a {@code …} mark, so the most specific
     * heading stays.
     *
     * @throws NullPointerException if an argument or a heading is null
     * @throws IllegalArgumentException if the path is not a normalized project-relative path
     */
    public static String of(Path sourcePath, List<String> headingPath, String content) {
        Objects.requireNonNull(content, "content");

        return context(sourcePath, headingPath) + "\n\n" + content;
    }

    /** Returns the context line alone, within {@link #CONTEXT_TOKENS} tokens. */
    static String context(Path sourcePath, List<String> headingPath) {
        List<String> parts = new ArrayList<>();

        for (Path name : ContentPath.requireProjectRelative(sourcePath)) {
            parts.add(name.toString());
        }

        int pathParts = parts.size();
        parts.addAll(List.copyOf(headingPath));
        String full = join(parts, pathParts);
        MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

        if (tokenizer.count(full) <= CONTEXT_TOKENS) {
            return full;
        }

        // Drop whole leading parts while more than one remains, keeping the most specific heading.
        while (parts.size() > 1 && tokenizer.count(CUT + join(parts, pathParts)) > CONTEXT_TOKENS) {
            parts.removeFirst();
            pathParts = Math.max(0, pathParts - 1);
        }

        String shortened = join(parts, pathParts);

        // A single part that is still too long loses leading characters.
        while (tokenizer.count(CUT + shortened) > CONTEXT_TOKENS) {
            shortened = shortened.substring(shortened.offsetByCodePoints(0, 1));
        }

        return CUT + shortened;
    }

    /** Joins path segments with {@code /} and the path and headings with {@code >}. */
    private static String join(List<String> parts, int pathParts) {
        StringBuilder text = new StringBuilder();

        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) {
                text.append(index < pathParts ? "/" : SEPARATOR);
            }

            text.append(parts.get(index));
        }

        return text.toString();
    }
}
