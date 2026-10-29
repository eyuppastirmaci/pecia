package dev.eyuppastirmaci.pecia.chunking.markdown;

import dev.eyuppastirmaci.pecia.tokenization.TokenCounter;
import java.util.ArrayList;
import java.util.List;

/**
 * Joins tiny Markdown groups, such as a heading with one short line or one item of a list that had to be split, with a
 * neighbouring group under the same parent heading, so no chunk is too small to say anything on its own. A tiny group
 * joins the next group when it can, otherwise the previous one; joined groups never exceed the token budget and never
 * cross into another parent's section.
 */
final class MarkdownGroupMerger {

    /** Groups below this many content tokens are tiny, unless the budget is so small that a fraction of it is less. */
    static final int MIN_CONTENT_TOKENS = 24;

    // With small budgets nearly every group would be tiny, so the threshold never exceeds an eighth of the budget.
    private static final int BUDGET_FRACTION = 8;

    private final TokenCounter tokenCounter;
    private final int contentBudget;
    private final int threshold;

    MarkdownGroupMerger(TokenCounter tokenCounter, int contentBudget) {
        this.tokenCounter = tokenCounter;
        this.contentBudget = contentBudget;
        this.threshold = Math.min(MIN_CONTENT_TOKENS, contentBudget / BUDGET_FRACTION);
    }

    /**
     * Returns the groups with every tiny one joined to a neighbour where the rules allow, in source order; the groups
     * still cover the same text without gaps.
     *
     * @param text the whole Markdown document
     * @param groups contiguous groups in source order
     */
    List<HeadedGroup> merge(String text, List<HeadedGroup> groups) {
        List<HeadedGroup> merged = new ArrayList<>();

        for (HeadedGroup group : groups) {
            if (!merged.isEmpty() && isTiny(text, merged.getLast()) && canJoin(text, merged.getLast(), group)) {
                merged.set(merged.size() - 1, join(merged.getLast(), group));
                continue;
            }

            joinTinyLastBackward(text, merged);
            merged.add(group);
        }

        joinTinyLastBackward(text, merged);

        return List.copyOf(merged);
    }

    /** Joins the last group to the one before it when it is tiny and could not join the group after it. */
    private void joinTinyLastBackward(String text, List<HeadedGroup> merged) {
        int last = merged.size() - 1;

        if (last >= 1 && isTiny(text, merged.get(last)) && canJoin(text, merged.get(last - 1), merged.get(last))) {
            HeadedGroup joined = join(merged.get(last - 1), merged.get(last));
            merged.remove(last);
            merged.set(last - 1, joined);
        }
    }

    private boolean isTiny(String text, HeadedGroup group) {
        return tokenCounter.count(text.substring(group.startOffset(), group.endOffset())) < threshold;
    }

    /**
     * Allows siblings, a section with its first subsection, and parts of one section, when both groups fit the budget
     * together; a group that needs splitting never does, so it is never joined.
     */
    private boolean canJoin(String text, HeadedGroup first, HeadedGroup second) {
        int shared = sharedPrefix(first.headingPath(), second.headingPath()).size();
        int deepest = Math.max(first.headingPath().size(), second.headingPath().size());

        return shared >= deepest - 1
                && tokenCounter.count(text.substring(first.startOffset(), second.endOffset())) <= contentBudget;
    }

    private static HeadedGroup join(HeadedGroup first, HeadedGroup second) {
        return new HeadedGroup(
                first.startOffset(),
                second.endOffset(),
                false,
                sharedPrefix(first.headingPath(), second.headingPath()));
    }

    private static List<String> sharedPrefix(List<String> first, List<String> second) {
        int length = 0;

        while (length < first.size()
                && length < second.size()
                && first.get(length).equals(second.get(length))) {
            length++;
        }

        return first.subList(0, length);
    }

    /** A planned group of source text with the heading path of the section it starts in. */
    record HeadedGroup(int startOffset, int endOffset, boolean requiresSplit, List<String> headingPath) {

        HeadedGroup {
            headingPath = List.copyOf(headingPath);
        }
    }
}
