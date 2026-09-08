package dev.eyuppastirmaci.pecia.chunking;

record MarkdownBlockGroup(int startOffset, int endOffset, boolean requiresSplit) {

    MarkdownBlockGroup {
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("Block group offsets must describe a non-empty source range");
        }
    }
}
