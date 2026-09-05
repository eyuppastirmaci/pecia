package dev.eyuppastirmaci.pecia.content;

import java.util.Objects;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.UNSUPPORTED_TYPE;

/** Coordinates file loading with the configured extraction strategy. */
public final class DocumentExtractionService {

    private final FileContentLoader contentLoader;
    private final DocumentExtractor extractor;

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
     * @throws ExtractionException if the type is unsupported or the source cannot be loaded or interpreted
     */
    public Document extract(ExtractionRequest request) throws ExtractionException {
        Objects.requireNonNull(request, "request");

        if (!extractor.supports(request.type())) {

            throw new ExtractionException(UNSUPPORTED_TYPE, request.file(),
                    "No extractor supports document type " + request.type() + ": " + request.file());
        }

        FileContent content = contentLoader.load(request.file());

        return extractor.extract(request, content);
    }
}
