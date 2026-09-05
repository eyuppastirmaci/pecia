package dev.eyuppastirmaci.pecia.content;

/** Strategy for interpreting already-loaded file bytes as a document. */
public interface DocumentExtractor {

    /**
     * Reports whether this strategy can interpret the requested document type.
     *
     * @param type document type to test
     * @return true when this strategy supports the type
     * @throws NullPointerException if type is null
     */
    boolean supports(DocumentType type);

    /**
     * Interprets already-loaded bytes as a document.
     *
     * @param request validated extraction context
     * @param content bytes loaded for the requested source file
     * @return the extracted document
     * @throws NullPointerException if request or content is null
     * @throws IllegalArgumentException if request and content refer to different files
     * @throws ExtractionException if the bytes cannot be interpreted as the requested document
     */
    Document extract(ExtractionRequest request, FileContent content) throws ExtractionException;
}
