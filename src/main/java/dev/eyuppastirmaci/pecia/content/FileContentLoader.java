package dev.eyuppastirmaci.pecia.content;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;

import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.NOT_REGULAR_FILE;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.READ_FAILED;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.TOO_LARGE;
import static dev.eyuppastirmaci.pecia.content.ExtractionException.Reason.FILE_CHANGED;

/** Reads bounded file bytes without interpreting their format. */
public final class FileContentLoader {

    private final int maxFileBytes;

    public FileContentLoader(int maxFileBytes) {

        if (maxFileBytes <= 0) {

            throw new IllegalArgumentException("maxFileBytes must be positive: " + maxFileBytes);
        }

        this.maxFileBytes = maxFileBytes;
    }

    /**
     * Reads one stable regular file without exceeding the configured byte limit.
     *
     * @param file absolute normalized path to read
     * @return the immutable loaded file bytes and their content hash
     * @throws NullPointerException if file is null
     * @throws IllegalArgumentException if file is not absolute and normalized
     * @throws ExtractionException if the file is unsuitable, too large, unreadable, or changes during reading
     */
    public FileContent load(Path file) throws ExtractionException {
        requireAbsoluteNormalized(file);
        rejectSymbolicLinks(file);

        BasicFileAttributes beforeAttributes;

        try {
            beforeAttributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {

            throw new ExtractionException(READ_FAILED, file, "Could not read file metadata: " + file, failure);
        }

        if (!beforeAttributes.isRegularFile()) {

            throw new ExtractionException(NOT_REGULAR_FILE, file, "Expected a regular file: " + file);
        }

        if (beforeAttributes.size() > maxFileBytes) {

            throw tooLarge(file);
        }

        byte[] bytes;

        try (InputStream input = Files.newInputStream(file)) {
            bytes = input.readNBytes(maxFileBytes);

            // Probe once after the bounded read so file growth cannot bypass the configured limit.
            if (input.read() != -1) {

                throw tooLarge(file);
            }

        } catch (ExtractionException failure) {

            throw failure;
        } catch (IOException failure) {

            throw new ExtractionException(READ_FAILED, file, "Could not read file: " + file, failure);
        }

        BasicFileAttributes afterAttributes;

        try {
            afterAttributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {

            throw new ExtractionException(FILE_CHANGED, file,
                    "File changed or disappeared while it was being read: " + file, failure);
        }

        FileState before = FileState.from(beforeAttributes);
        FileState after = FileState.from(afterAttributes);

        if (!afterAttributes.isRegularFile() || changedDuringRead(before, after, bytes.length)) {

            throw new ExtractionException(FILE_CHANGED, file, "File changed while it was being read: " + file);
        }

        return new FileContent(file, bytes);
    }

    /* Rejects links in every component so an apparently regular path cannot escape through an ancestor link. */
    private void rejectSymbolicLinks(Path file) throws ExtractionException {

        for (Path part = file; part != null; part = part.getParent()) {

            if (Files.isSymbolicLink(part)) {

                throw new ExtractionException(NOT_REGULAR_FILE, file,
                        "Symbolic-link paths are not supported: " + part);
            }
        }
    }

    private ExtractionException tooLarge(Path file) {

        return new ExtractionException(TOO_LARGE, file,
                "File exceeds the " + maxFileBytes + " byte limit: " + file);
    }

    private static void requireAbsoluteNormalized(Path file) {

        if (file == null) {

            throw new NullPointerException("file");
        }

        if (!file.isAbsolute() || !file.normalize().equals(file)) {

            throw new IllegalArgumentException("file must be an absolute, normalized path: " + file);
        }
    }

    /* Compares identity and metadata snapshots with the observed byte count to detect unstable reads. */
    static boolean changedDuringRead(FileState before, FileState after, int bytesRead) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        return !before.equals(after) || bytesRead != before.size();
    }

    record FileState(long size, FileTime lastModifiedTime, Object fileKey) {

        FileState {
            Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        }

        static FileState from(BasicFileAttributes attributes) {

            return new FileState(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }
}
