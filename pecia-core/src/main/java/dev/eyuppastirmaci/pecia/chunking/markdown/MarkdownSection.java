package dev.eyuppastirmaci.pecia.chunking.markdown;

import java.util.List;

record MarkdownSection(int startOffset, int endOffset, List<String> headingPath) {

    MarkdownSection {
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("Section offsets must describe a non-empty source range");
        }

        headingPath = List.copyOf(headingPath);
    }
}
