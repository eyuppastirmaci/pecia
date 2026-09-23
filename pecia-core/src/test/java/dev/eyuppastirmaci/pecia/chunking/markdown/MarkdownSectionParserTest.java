package dev.eyuppastirmaci.pecia.chunking.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownSectionParserTest {

    private final MarkdownSectionParser parser = new MarkdownSectionParser();

    @Test
    void tracksNestedHeadingsAndReplacesSiblingAndAncestorPaths() {
        String text = "# Installation\nintro\n## Windows\n### Terminal\n## Linux\n# Usage\n";

        assertPaths(
                text,
                List.of("Installation"),
                List.of("Installation", "Windows"),
                List.of("Installation", "Windows", "Terminal"),
                List.of("Installation", "Linux"),
                List.of("Usage"));
    }

    @Test
    void allowsSkippedLevelsWithoutInventingParents() {
        assertPaths(
                "### First\n###### Deep\n## Second\n##### Child\n",
                List.of("First"),
                List.of("First", "Deep"),
                List.of("Second"),
                List.of("Second", "Child"));
    }

    @Test
    void preservesPreambleAndHeadingFreeContent() {
        assertPaths("Introduction\n\n# Title\nbody", List.of(), List.of("Title"));
        assertPaths("No headings\njust text\n", List.of());
        assertPaths(" \t\r\n", List.of());
        assertEquals(List.of(), parser.parse(""));
    }

    @Test
    void recognizesSetextHeadingsIncludingMultilineTitles() {
        assertPaths("Root\n====\n\nChild\ncontinued\n---\nbody", List.of("Root"), List.of("Root", "Child continued"));
    }

    @Test
    void ignoresMalformedEscapedAndIndentedHeadingMarkers() {
        assertPaths(
                "#no-space\n####### Too deep\n\\# Escaped\n\n    # Code\n\n   ## Valid ##\n",
                List.of(),
                List.of("Valid"));
    }

    @Test
    void ignoresHeadingsInsideFencedCodeIncludingIncompleteFences() {
        for (String fence : List.of("```", "~~~")) {
            assertPaths(
                    "# Root\n" + fence + "\n# Hidden\n" + fence + "\n## Visible\n",
                    List.of("Root"),
                    List.of("Root", "Visible"));
            assertPaths("# Root\n" + fence + "\n# Hidden\n## Still hidden", List.of("Root"));
        }
    }

    @Test
    void shorterOrMismatchedFencesDoNotExposeFakeHeadings() {
        assertPaths(
                "# Root\n````\n```\n# Hidden\n~~~\n## Hidden too\n````\n## Real",
                List.of("Root"),
                List.of("Root", "Real"));
    }

    @Test
    void containerHeadingsDoNotChangeTheDocumentHierarchy() {
        assertPaths(
                "# Root\n\n> ## Quote\n\n- ## Item\n\n  ### Nested item heading\n\n## Real\n",
                List.of("Root"),
                List.of("Root", "Real"));
    }

    @Test
    void ignoresHeadingLikeTextInHtmlBlocks() {
        assertPaths(
                "# Root\n\n<!--\n# Hidden\n-->\n\n<div>\n## Hidden too\n</div>\n\n## Real",
                List.of("Root"),
                List.of("Root", "Real"));
    }

    @Test
    void extractsInlineTextWithoutFormattingOrLinkDestinations() {
        assertPaths(
                "# **Install** [Windows](https://example.com) `CLI` &amp; <b>tools</b>" + " ![logo](image.png)\n",
                List.of("Install Windows CLI & tools logo"));
    }

    @Test
    void emptyHeadingsResetDescendantsWithoutAddingBlankPathEntries() {
        assertPaths(
                "# Root\n## Old\n##\n### New\n#\nbody",
                List.of("Root"),
                List.of("Root", "Old"),
                List.of("Root"),
                List.of("Root", "New"),
                List.of());
    }

    @Test
    void preservesUtf16OffsetsIndentationAndEveryLineEnding() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String preamble = "😀 Önsöz" + newline + newline;
            String root = "  # İçerik" + newline + "body" + newline;
            String child = "   ## cafe\u0301 😀";
            String text = preamble + root + child;
            List<MarkdownSection> sections = parser.parse(text);

            assertEquals(new MarkdownSection(0, preamble.length(), List.of()), sections.get(0));
            assertEquals(
                    new MarkdownSection(preamble.length(), preamble.length() + root.length(), List.of("İçerik")),
                    sections.get(1));
            assertEquals(
                    new MarkdownSection(
                            preamble.length() + root.length(), text.length(), List.of("İçerik", "cafe\u0301 😀")),
                    sections.get(2));
            assertCoverage(text, sections);
        }
    }

    @Test
    void repeatedTitlesRemainDistinctSectionsWithImmutablePaths() {
        List<MarkdownSection> sections = parser.parse("# Same\n# Same\n");

        assertEquals(2, sections.size());
        assertTrue(sections.get(0).startOffset() < sections.get(1).startOffset());
        assertThrows(UnsupportedOperationException.class, () -> sections.clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> sections.getFirst().headingPath().add("other"));
        List<String> original = new ArrayList<>(List.of("Title"));
        MarkdownSection section = new MarkdownSection(0, 1, original);
        original.clear();
        assertEquals(List.of("Title"), section.headingPath());
    }

    @Test
    void rejectsNullInputAndInvalidSectionRanges() {
        assertThrows(NullPointerException.class, () -> parser.parse(null));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSection(-1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSection(1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownSection(2, 1, List.of()));
        assertThrows(NullPointerException.class, () -> new MarkdownSection(0, 1, null));
    }

    @SafeVarargs
    // List.of copies the array and never exposes it, so passing the generic varargs array is safe.
    @SuppressWarnings("varargs")
    private void assertPaths(String text, List<String>... expected) {
        List<MarkdownSection> sections = parser.parse(text);
        assertEquals(
                List.of(expected),
                sections.stream().map(MarkdownSection::headingPath).toList());
        assertEquals(sections, parser.parse(text));
        assertCoverage(text, sections);
    }

    /* Reconstructs the original source to detect gaps, overlaps, normalization, or dropped heading markers. */
    private static void assertCoverage(String text, List<MarkdownSection> sections) {
        StringBuilder reconstructed = new StringBuilder();
        int offset = 0;

        for (MarkdownSection section : sections) {
            assertEquals(offset, section.startOffset());
            assertTrue(section.endOffset() > section.startOffset());
            reconstructed.append(text, section.startOffset(), section.endOffset());
            offset = section.endOffset();
        }

        assertEquals(text.length(), offset);
        assertEquals(text, reconstructed.toString());
    }
}
