package dev.eyuppastirmaci.pecia.chunking.markdown;

import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static java.util.Objects.requireNonNull;

final class MarkdownSectionParser {

    private final Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

    /* Partitions the unchanged source at document-level headings, carrying the active hierarchy into each section. */
    List<MarkdownSection> parse(String text) {
        requireNonNull(text, "text");
        Node document = parser.parse(text);
        List<MarkdownSection> sections = new ArrayList<>();
        String[] titles = new String[6];
        List<String> headingPath = List.of();
        int start = 0;

        // Only direct children define document sections; headings inside lists and quotes remain local to those blocks.
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            if (!(block instanceof Heading heading)) {
                continue;
            }

            var firstSpan = heading.getSourceSpans().getFirst();
            int headingStart = firstSpan.getInputIndex() - firstSpan.getColumnIndex();

            if (headingStart > start) {
                sections.add(new MarkdownSection(start, headingStart, headingPath));
            }

            int level = heading.getLevel();
            titles[level - 1] = titleOf(heading);

            for (int index = level; index < titles.length; index++) {
                titles[index] = null;
            }

            List<String> path = new ArrayList<>();

            for (String title : titles) {
                if (title != null && !title.isBlank()) {
                    path.add(title);
                }
            }

            headingPath = List.copyOf(path);
            start = headingStart;
        }

        if (start < text.length()) {
            sections.add(new MarkdownSection(start, text.length(), headingPath));
        }

        return List.copyOf(sections);
    }

    /* Collects visible inline text without link destinations or HTML tags using iterative traversal to avoid deep recursion. */
    private static String titleOf(Heading heading) {
        StringBuilder title = new StringBuilder();
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(heading);

        while (!pending.isEmpty()) {
            Node node = pending.pop();

            if (node instanceof Text text) {
                title.append(text.getLiteral());
            } else if (node instanceof Code code) {
                title.append(code.getLiteral());
            } else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                title.append(' ');
            }

            for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                pending.push(child);
            }
        }

        return title.toString().strip();
    }
}
