package dev.eyuppastirmaci.pecia.content;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.UNSUPPORTED_TYPE;

import java.util.Objects;

/** Coordinates file loading with the configured extraction strategy. */
public final class DocumentExtractionService {

    private final FileContentLoader contentLoader;
    private final DocumentExtractor extractor;

    /** Creates an extraction pipeline using the supplied non-null loader and strategy. */
    public DocumentExtractionService(FileContentLoader contentLoader, DocumentExtractor extractor) {
        this.contentLoader = Objects.requireNonNull(contentLoader, "contentLoader");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
    }

    /**
     * Loads and extracts the source described by the request with the configured strategy.
     *
     * @param request validated extraction context
     * @return the extracted document
     * @throws NullPointerException if request is null
     * @throws ExtractionException if the type is unsupported or the source cannot be loaded or
     *     interpreted
     */
    public Document extract(ExtractionRequest request) throws ExtractionException {
        FileContent content = load(request);

        return extractor.extract(request, content);
    }

    /**
     * Checks strategy support and loads stable, bounded bytes for hashing before interpretation.
     *
     * @throws NullPointerException if request is null
     * @throws ExtractionException if the type is unsupported or the source cannot be loaded
     */
    public FileContent load(ExtractionRequest request) throws ExtractionException {
        requireSupported(request);

        return contentLoader.load(request.file());
    }

    /**
     * Extracts an already loaded snapshot without reading the source again. Use {@link #load(ExtractionRequest)} to
     * enforce the configured file size and stable-read checks before calling this overload.
     *
     * @throws NullPointerException if request or content is null
     * @throws IllegalArgumentException if request and content refer to different files
     * @throws ExtractionException if the type is unsupported or the bytes cannot be interpreted
     */
    public Document extract(ExtractionRequest request, FileContent content) throws ExtractionException {
        if (!request.file().equals(content.file())) {
            throw new IllegalArgumentException("request and loaded content must refer to the same file");
        }

        requireSupported(request);

        return extractor.extract(request, content);
    }

    private void requireSupported(ExtractionRequest request) throws ExtractionException {
        if (!extractor.supports(request.type())) {
            throw new ExtractionException(
                    UNSUPPORTED_TYPE,
                    request.file(),
                    "No extractor supports document type " + request.type() + ": " + request.file());
        }
    }
}
