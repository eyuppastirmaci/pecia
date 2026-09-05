package dev.eyuppastirmaci.pecia.content;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Pure filename classification. Does not read files, apply discovery filters, or validate encoding. */
public final class FileTypeDetector {
    private static final Map<String, DocumentType> EXTENSIONS =
            Arrays.stream(DocumentType.values())
                  .flatMap(type -> type.extensions().stream()
                                                    .map(name -> Map.entry(name.toLowerCase(Locale.ROOT), type)))
                  .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private static final Map<String, DocumentType> BASENAMES =
            Arrays.stream(DocumentType.values())
                  .flatMap(type -> type.basenames().stream()
                                                   .map(name -> Map.entry(name.toLowerCase(Locale.ROOT), type)))
                  .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    /**
     * Classifies a filename by exact basename first and final extension second without accessing the filesystem.
     *
     * @param path path whose filename is classified
     * @return the recognized document type, or empty when the filename is unsupported
     * @throws NullPointerException if the path is null
     * @throws IllegalArgumentException if the path has no meaningful filename
     */
    public Optional<DocumentType> detect(Path path) {
        Objects.requireNonNull(path, "path");
        Path filename = path.getFileName();

        if (filename == null || filename.toString().isEmpty()
                || filename.toString().equals(".") || filename.toString().equals("..")) {
            throw new IllegalArgumentException("A file name is required: " + path);
        }

        String name = filename.toString().toLowerCase(Locale.ROOT);
        DocumentType exact = BASENAMES.get(name);

        if (exact != null) {

            return Optional.of(exact);
        }

        int dot = name.lastIndexOf('.');

        // Require text on both sides of the final dot so bare dotfiles and trailing dots have no extension.
        return dot > 0 && dot < name.length() - 1
                ? Optional.ofNullable(EXTENSIONS.get(name.substring(dot + 1))) : Optional.empty();
    }

    /**
     * Classifies an admitted discovery candidate, routing unknown names through plain-text extraction.
     *
     * @param path candidate path whose filename is classified
     * @return the recognized type, or plain text when the filename is unknown
     * @throws NullPointerException if the path is null
     * @throws IllegalArgumentException if the path has no meaningful filename
     */
    public DocumentType typeForCandidate(Path path) {

        return detect(path).orElse(DocumentType.PLAIN_TEXT);
    }
}
