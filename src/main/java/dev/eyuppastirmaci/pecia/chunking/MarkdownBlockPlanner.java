package dev.eyuppastirmaci.pecia.chunking;

import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Heading;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static java.util.Objects.requireNonNull;

final class MarkdownBlockPlanner {

    private final Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private final TokenCounter tokenCounter;
    private final int contentBudget;

    MarkdownBlockPlanner(TokenCounter tokenCounter, int maxTokens) {
        this.tokenCounter = requireNonNull(tokenCounter, "tokenCounter");
        var identity = requireNonNull(tokenCounter.identity(), "tokenizer identity");

        if (maxTokens <= identity.specialTokenCount() || maxTokens > identity.maxInputTokens()) {
            throw new IllegalArgumentException("maxTokens must be greater than " + identity.specialTokenCount()
                    + " and at most " + identity.maxInputTokens() + ", including special tokens");
        }

        contentBudget = maxTokens - identity.specialTokenCount();
    }

    /* Packs intact top-level blocks within the token budget and flags oversized single blocks for later splitting. */
    List<MarkdownBlockGroup> plan(String text) {
        return plan(text, false);
    }

    /* Refines oversized containers at item and child-block boundaries while leaving oversized leaves for text fallback. */
    List<MarkdownBlockGroup> planWithContainerSplitting(String text) {
        return plan(text, true);
    }

    /* Counts exact source slices while optionally refining containers without reparsing fragments outside their original context. */
    private List<MarkdownBlockGroup> plan(String text, boolean splitContainers) {
        requireNonNull(text, "text");

        if (TextBoundaries.skipWhitespace(text, 0) == text.length()) {
            return List.of();
        }

        Node document = parser.parse(text);
        List<Node> blocks = contentChildren(text, document);
        List<MarkdownBlockGroup> groups = new ArrayList<>();
        int groupStart = 0;
        int blockStart = 0;

        // Whole top-level blocks are preferred; only oversized containers are eligible for structural refinement.
        for (int index = 0; index < blocks.size(); index++) {
            Node block = blocks.get(index);
            int blockEnd = index + 1 == blocks.size() ? text.length() : lineStart(blocks.get(index + 1));
            boolean oversized = exceedsBudget(text, blockStart, blockEnd);

            if (groupStart < blockStart
                    && (block instanceof Heading || oversized || exceedsBudget(text, groupStart, blockEnd))) {
                groups.add(new MarkdownBlockGroup(groupStart, blockStart, false));
                groupStart = blockStart;
            }

            if (oversized) {
                if (splitContainers) {
                    splitContainer(text, block, blockStart, blockEnd, groups);
                } else {
                    groups.add(new MarkdownBlockGroup(blockStart, blockEnd, true));
                }

                groupStart = blockEnd;
            }

            blockStart = blockEnd;
        }

        if (groupStart < text.length()) {
            groups.add(new MarkdownBlockGroup(groupStart, text.length(), exceedsBudget(text, groupStart, text.length())));
        }

        return List.copyOf(groups);
    }

    /* Walks oversized containers iteratively so fitting nested blocks stay intact and original list or quote markers are retained. */
    private void splitContainer(String text, Node block, int start, int end, List<MarkdownBlockGroup> groups) {
        Deque<SourceBlock> pending = new ArrayDeque<>();
        pending.push(new SourceBlock(block, start, end));

        while (!pending.isEmpty()) {
            SourceBlock current = pending.pop();
            boolean oversized = exceedsBudget(text, current.start(), current.end());
            Node node = current.node();
            boolean container = node instanceof ListBlock || node instanceof ListItem || node instanceof BlockQuote;

            if (!oversized || !container) {
                groups.add(new MarkdownBlockGroup(current.start(), current.end(), oversized));

                continue;
            }

            List<Node> children = contentChildren(text, node);

            if (children.isEmpty()) {
                groups.add(new MarkdownBlockGroup(current.start(), current.end(), true));

                continue;
            }

            // Reverse insertion preserves source order while each first child inherits its container's original prefix.
            for (int index = children.size() - 1; index >= 0; index--) {
                Node child = children.get(index);
                int childStart = index == 0 ? current.start() : lineStart(child);
                int childEnd = index + 1 == children.size() ? current.end() : lineStart(children.get(index + 1));
                pending.push(new SourceBlock(child, childStart, childEnd));
            }
        }
    }

    /* Omits whitespace-only parser nodes from split boundaries without removing their characters from the emitted source ranges. */
    private static List<Node> contentChildren(String text, Node parent) {
        List<Node> children = new ArrayList<>();

        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            var lastSpan = child.getSourceSpans().getLast();
            int end = lastSpan.getInputIndex() + lastSpan.getLength();
            String source = text.substring(lineStart(child), end);

            if (TextBoundaries.skipWhitespace(source, 0) < source.length()) {
                children.add(child);
            }
        }

        return children;
    }

    private boolean exceedsBudget(String text, int start, int end) {
        return tokenCounter.count(text.substring(start, end)) > contentBudget;
    }

    /* Includes the next block's indentation while leaving inter-block whitespace attached to the preceding block. */
    private static int lineStart(Node block) {
        var span = block.getSourceSpans().getFirst();

        return span.getInputIndex() - span.getColumnIndex();
    }

    private record SourceBlock(Node node, int start, int end) {
    }
}
