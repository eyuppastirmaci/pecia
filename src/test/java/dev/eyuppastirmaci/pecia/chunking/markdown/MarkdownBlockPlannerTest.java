package dev.eyuppastirmaci.pecia.chunking.markdown;

import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownBlockPlannerTest {

    private final MiniLmTokenizer tokenizer = MiniLmTokenizer.bundled();

    @Test
    void combinesAdjacentWholeBlocksWhenTheyFit() {
        String text = "# Title\n\nhello world\n\n- one\n- two\n\n```\ncode\n```\n";
        List<MarkdownBlockGroup> groups = verify(text, 256);

        assertEquals(List.of(new MarkdownBlockGroup(0, text.length(), false)), groups);
    }

    @Test
    void movesAWholeListToTheNextGroupWhenTheRemainingBudgetIsTooSmall() {
        String prefix = "hello world\n\n";
        String list = "- one\n- two\n";
        String text = prefix + list;
        int limit = tokenizer.countModelInput(list);
        List<MarkdownBlockGroup> groups = verify(text, limit);

        assertEquals(List.of(prefix, list), slices(text, groups));
        assertTrue(groups.stream().noneMatch(MarkdownBlockGroup::requiresSplit));
    }

    @Test
    void preservesOrderedNestedAndLooseListsAsSingleUnits() {
        for (String list : List.of("1. one\n2. two\n", "- one\n  - nested\n  - child\n- two\n",
                "- first paragraph\n\n  second paragraph\n\n- next item\n")) {
            String prefix = "intro\n\n";
            String text = prefix + list;
            List<MarkdownBlockGroup> groups = verify(text, tokenizer.countModelInput(list));

            assertEquals(List.of(prefix, list), slices(text, groups));
            assertFalse(groups.getLast().requiresSplit());
        }
    }

    @Test
    void preservesFencesWithTheirMarkersInfoStringsAndBlankLines() {
        for (String fence : List.of("```", "~~~", "````")) {
            String prefix = "intro\n\n";
            String code = fence + "java\n\nint value = 1;\n\n" + fence + "\n";
            String text = prefix + code;
            List<MarkdownBlockGroup> groups = verify(text, tokenizer.countModelInput(code));

            assertEquals(List.of(prefix, code), slices(text, groups));
            assertFalse(groups.getLast().requiresSplit());
        }
    }

    @Test
    void incompleteFencesConsumeTheRemainderWithoutInventingAClosingFence() {
        for (String fence : List.of("```", "~~~")) {
            String prefix = "intro\n\n";
            String code = fence + "python\n# not a heading\n\n- not a list\nprint(1)";
            String text = prefix + code;
            List<MarkdownBlockGroup> groups = verify(text, tokenizer.countModelInput(code));

            assertEquals(List.of(prefix, code), slices(text, groups));
            assertFalse(groups.getLast().requiresSplit());
        }
    }

    @Test
    void mismatchedAndShortFencesDoNotCreateFalseBlockBoundaries() {
        String code = "````\n```\n# hidden\n~~~\n- hidden\n````\n";
        String text = "intro\n\n" + code;
        List<MarkdownBlockGroup> groups = verify(text, tokenizer.countModelInput(code));

        assertEquals(List.of("intro\n\n", code), slices(text, groups));
        assertFalse(groups.getLast().requiresSplit());
    }

    @Test
    void preservesContainersWithEmbeddedFences() {
        for (String container : List.of("- item\n\n  ```java\n  code\n  ```\n\n- next\n",
                "> quoted\n>\n> ```\n> code\n> ```\n")) {
            String text = "intro\n\n" + container;
            List<MarkdownBlockGroup> groups = verify(text, tokenizer.countModelInput(container));

            assertEquals(List.of("intro\n\n", container), slices(text, groups));
            assertFalse(groups.getLast().requiresSplit());
        }
    }

    @Test
    void startsANewGroupAtEachDocumentHeading() {
        String preamble = "intro\n\n";
        String first = "# Root\nbody\n\n";
        String child = "## Child\n- item\n\n";
        String next = "Next\n====\nbody";
        String text = preamble + first + child + next;

        assertEquals(List.of(preamble, first, child, next), slices(text, verify(text, 256)));
    }

    @Test
    void doesNotTreatContainerHeadingsAsDocumentSections() {
        String text = "# Root\n\n> ## Quoted\n\n- ## Item\n\n```\n# Code\n```\n";

        assertEquals(List.of(text), slices(text, verify(text, 256)));
    }

    @Test
    void isolatesOversizedListsFencesAndParagraphsForLaterSplitting() {
        for (String oversized : List.of("- hello world\n".repeat(100) + "\n",
                "```\n" + "code\n".repeat(100) + "```\n\n", "word ".repeat(100) + "\n\n",
                "    code\n".repeat(100) + "\n")) {
            String prefix = "before\n\n";
            String suffix = "after";
            String text = prefix + oversized + suffix;
            List<MarkdownBlockGroup> groups = verify(text, 16);

            assertEquals(List.of(prefix, oversized, suffix), slices(text, groups));
            assertEquals(List.of(false, true, false), groups.stream().map(MarkdownBlockGroup::requiresSplit).toList());
        }
    }

    @Test
    void keepsConsecutiveOversizedBlocksSeparate() {
        String list = "- hello\n".repeat(100) + "\n";
        String code = "```\n" + "code\n".repeat(100) + "```";
        String text = list + code;
        List<MarkdownBlockGroup> groups = verify(text, 8);

        assertEquals(List.of(list, code), slices(text, groups));
        assertTrue(groups.stream().allMatch(MarkdownBlockGroup::requiresSplit));
    }

    @Test
    void countsSpecialTokensAndMarksAOneTokenOverflow() {
        String list = "- hello\n- world\n";
        int exactLimit = tokenizer.countModelInput(list);

        assertEquals(List.of(new MarkdownBlockGroup(0, list.length(), false)), verify(list, exactLimit));
        assertEquals(List.of(new MarkdownBlockGroup(0, list.length(), true)), verify(list, exactLimit - 1));
    }

    @Test
    void preservesUnicodeIndentationAndAllSourceWhitespace() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String prefix = " \t" + newline + "😀 Önsöz" + newline + newline;
            String code = "  ~~~text" + newline + "İçerik cafe\u0301 😀" + newline + "  ~~~" + newline + newline;
            String suffix = "- son" + newline + " \t";
            String text = prefix + code + suffix;
            List<MarkdownBlockGroup> groups = verify(text, tokenizer.countModelInput(code));

            assertEquals(List.of(prefix, code, suffix), slices(text, groups));
            assertEquals(prefix.length(), groups.get(1).startOffset());
            assertEquals(prefix.length() + code.length(), groups.get(1).endOffset());
        }
    }

    @Test
    void retainsLinkDefinitionsAndHtmlThatDoNotRenderAsVisibleText() {
        String text = "[link]: https://example.com\n\n<!-- hidden -->\n\n- [label][link]\n";

        assertEquals(List.of(text), slices(text, verify(text, 256)));
        verify(text, 4);
    }

    @Test
    void handlesNonMonotonicAndZeroTokenContentWithoutDroppingIt() {
        String text = "a".repeat(101);

        assertEquals(List.of(new MarkdownBlockGroup(0, text.length(), false)), verify(text, 3));
        verify("\u200b\n\n".repeat(1000) + "hello", 3);
    }

    @Test
    void returnsNoGroupsForEmptyOrWhitespaceOnlyInput() {
        MarkdownBlockPlanner planner = new MarkdownBlockPlanner(tokenizer, 32);

        for (String text : List.of("", " \t\r\n", "\u00a0\u2003")) {
            assertEquals(List.of(), planner.plan(text));
        }
    }

    @Test
    void rejectsInvalidInputsAndReturnsImmutableResults() {
        assertThrows(NullPointerException.class, () -> new MarkdownBlockPlanner(null, 256));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownBlockPlanner(tokenizer, 2));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownBlockPlanner(tokenizer, 257));
        assertThrows(NullPointerException.class, () -> new MarkdownBlockPlanner(tokenizer, 256).plan(null));
        assertThrows(UnsupportedOperationException.class, () -> verify("hello", 256).clear());
        assertThrows(IllegalArgumentException.class, () -> new MarkdownBlockGroup(-1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownBlockGroup(1, 1, false));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownBlockGroup(2, 1, false));
    }

    @Test
    void maintainsCoverageAndBudgetsAcrossSeededMixedBlocks() {
        Random random = new Random(92);
        String[] blocks = {"# Title\n\n", "hello world\n\n", "- one\n- two\n\n",
                "1. item\n2. item\n\n", "```\n# code\n\ncode\n```\n\n", "~~~\ncode\n~~~\n\n",
                "> quoted\n\n", "[ref]: https://example.com\n\n", "<!--\n# hidden\n-->\n\n",
                "😀 İçerik\r\n\r\n", "- hello\n".repeat(100) + "\n"};

        for (int example = 0; example < 60; example++) {
            StringBuilder text = new StringBuilder();

            for (int index = 0; index < 20; index++) {
                text.append(blocks[random.nextInt(blocks.length)]);
            }

            verify(text.toString(), 3 + random.nextInt(50));
        }
    }

    private static List<String> slices(String text, List<MarkdownBlockGroup> groups) {
        return groups.stream()
                     .map(group -> text.substring(group.startOffset(), group.endOffset())).toList();
    }

    /* Reconstructs the exact source and verifies every normal group fits while every flagged group exceeds the budget. */
    private List<MarkdownBlockGroup> verify(String text, int limit) {
        MarkdownBlockPlanner planner = new MarkdownBlockPlanner(tokenizer, limit);
        List<MarkdownBlockGroup> groups = planner.plan(text);
        assertEquals(groups, planner.plan(text));
        StringBuilder reconstructed = new StringBuilder();
        int offset = 0;

        for (MarkdownBlockGroup group : groups) {
            assertEquals(offset, group.startOffset());
            assertTrue(group.endOffset() > group.startOffset());
            String slice = text.substring(group.startOffset(), group.endOffset());
            assertEquals(tokenizer.countModelInput(slice) > limit, group.requiresSplit());
            reconstructed.append(slice);
            offset = group.endOffset();
        }

        assertEquals(text.length(), offset);
        assertEquals(text, reconstructed.toString());

        return groups;
    }
}
