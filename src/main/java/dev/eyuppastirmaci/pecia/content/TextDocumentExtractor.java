package dev.eyuppastirmaci.pecia.content;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.BINARY_CONTENT;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.INVALID_UTF8;

/** UTF-8 extraction strategy shared by every v0.1 document family. */
public final class TextDocumentExtractor implements DocumentExtractor {

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    /**
     * Reports that the shared UTF-8 strategy supports every v0.1 document family.
     *
     * @param type document type to test
     * @return true for every non-null document type
     * @throws NullPointerException if type is null
     */
    @Override
    public boolean supports(DocumentType type) {
        Objects.requireNonNull(type, "type");

        return true;
    }

    /**
     * Decodes loaded bytes as UTF-8 text and rejects binary-looking content.
     *
     * @param request validated extraction context
     * @param content bytes loaded for the requested source file
     * @return the extracted text document with its source hash
     * @throws NullPointerException if request or content is null
     * @throws IllegalArgumentException if request and content refer to different files
     * @throws ExtractionException if the bytes are invalid UTF-8 or appear to be binary
     */
    @Override
    public Document extract(ExtractionRequest request, FileContent content) throws ExtractionException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(content, "content");

        if (!request.file().equals(content.file())) {

            throw new IllegalArgumentException("request and loaded content must refer to the same file");
        }

        String text = decodeUtf8(content);

        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }

        if (looksBinary(text)) {

            throw new ExtractionException(BINARY_CONTENT, request.file(),
                    "File contains binary control characters: " + request.file());
        }

        return new Document(request.sourcePath(), request.type(), text, content.contentHash());
    }

    /* Configures strict UTF-8 decoding so malformed or unmappable input is rejected instead of replaced. */
    private static String decodeUtf8(FileContent content) throws ExtractionException {

        try {
            return StandardCharsets.UTF_8.newDecoder()
                                         .onMalformedInput(CodingErrorAction.REPORT)
                                         .onUnmappableCharacter(CodingErrorAction.REPORT)
                                         .decode(ByteBuffer.wrap(content.bytes()))
                                         .toString();
        } catch (CharacterCodingException failure) {

            throw new ExtractionException(INVALID_UTF8, content.file(),
                    "File is not valid UTF-8: " + content.file(), failure);
        }
    }

    /* Treats NUL or a meaningful density of disallowed control characters as evidence of binary content. */
    private static boolean looksBinary(String text) {
        int suspiciousControls = 0;

        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);

            if (character == '\0') {

                return true;
            }

            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t' && character != '\f') {
                suspiciousControls++;
            }
        }

        // A few isolated controls may be intentional; repeated controls are a strong binary signal.
        return suspiciousControls >= 4 && suspiciousControls * 100L > text.length();
    }
}
