package dev.eyuppastirmaci.pecia.testing;

import dev.eyuppastirmaci.pecia.testing.ChunkingArbitraries.Budget;
import dev.eyuppastirmaci.pecia.tokenization.MiniLmTokenizer;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Checks that the chunking generators produce the inputs the chunking properties rely on, so a weak generator
 * cannot make those properties pass vacuously. Coverage thresholds are minimums over each run's samples.
 */
class ChunkingArbitrariesTest {

    @Property(seed = PropertySeeds.FIXED, tries = 1000)
    void budgetsAreValidAndIncludeSmallAndLargeLimits(@ForAll("budgets") Budget budget) {
        int contentBudget =
                budget.maxTokens() - MiniLmTokenizer.bundled().identity().specialTokenCount();

        if (budget.maxTokens() > MiniLmTokenizer.bundled().identity().maxInputTokens()
                || budget.overlapTokens() < 0
                || budget.overlapTokens() >= contentBudget) {
            throw new AssertionError("Invalid budget: " + budget);
        }

        Statistics.label("single content token").collect(contentBudget == 1);
        Statistics.label("full input").collect(budget.maxTokens() == 256);
        Statistics.label("overlap").collect(budget.overlapTokens() > 0);
        Statistics.label("single content token")
                .coverage(coverage -> coverage.check(true).count(count -> count > 0));
        Statistics.label("full input").coverage(coverage -> coverage.check(true).count(count -> count > 0));
        Statistics.label("overlap").coverage(coverage -> coverage.check(true).percentage(p -> p >= 50));
    }

    @Property(seed = PropertySeeds.FIXED)
    void plainTextCoversLineEndingsUnicodeAndWhitespace(@ForAll("plainText") String text) {
        collectCommonFeatures(text);
        Statistics.label("whitespace-only line")
                .collect(text.lines().anyMatch(line -> !line.isEmpty() && line.isBlank()));
        Statistics.label("whitespace-only line")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 20));
    }

    @Property(seed = PropertySeeds.FIXED)
    void markdownCoversHeadingsListsAndFences(@ForAll("markdown") String text) {
        collectCommonFeatures(text);
        Statistics.label("nested heading")
                .collect(text.lines()
                        .anyMatch(
                                line -> line.startsWith("## ") || line.startsWith("### ") || line.startsWith("#### ")));
        Statistics.label("list item").collect(text.contains("- ") || text.contains("1. "));
        Statistics.label("fence").collect(text.contains("```") || text.contains("~~~"));
        Statistics.label("unclosed fence").collect(hasUnclosedFence(text));
        Statistics.label("nested heading")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 20));
        Statistics.label("list item").coverage(coverage -> coverage.check(true).percentage(p -> p >= 30));
        Statistics.label("fence").coverage(coverage -> coverage.check(true).percentage(p -> p >= 30));
        Statistics.label("unclosed fence")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 10));
    }

    @Property(seed = PropertySeeds.FIXED)
    void sourceCodeCoversIndentationAndBlankLineBlocks(@ForAll("sourceCode") String text) {
        collectCommonFeatures(text);
        Statistics.label("indented line")
                .collect(text.lines().anyMatch(line -> line.startsWith(" ") || line.startsWith("\t")));
        Statistics.label("blank-line block").collect(text.matches("(?s).*\\S(\\r\\n|\\r|\\n){2,}.*\\S.*"));
        Statistics.label("indented line")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 50));
        Statistics.label("blank-line block")
                .coverage(coverage -> coverage.check(true).percentage(p -> p >= 30));
    }

    @Provide
    Arbitrary<Budget> budgets() {
        return ChunkingArbitraries.budgets();
    }

    @Provide
    Arbitrary<String> plainText() {
        return ChunkingArbitraries.plainText();
    }

    @Provide
    Arbitrary<String> markdown() {
        return ChunkingArbitraries.markdown();
    }

    @Provide
    Arbitrary<String> sourceCode() {
        return ChunkingArbitraries.sourceCode();
    }

    /* Features every generator must reach: each line-ending style, surrogate pairs, Turkish, and long words. */
    private static void collectCommonFeatures(String text) {
        Statistics.label("CRLF").collect(text.contains("\r\n"));
        Statistics.label("lone CR").collect(text.replace("\r\n", "").contains("\r"));
        Statistics.label("surrogate pair").collect(text.codePoints().anyMatch(Character::isSupplementaryCodePoint));
        Statistics.label("Turkish").collect(text.contains("İ") || text.contains("ı"));
        Statistics.label("long word").collect(text.matches("(?s).*\\S{40,}.*"));
        Statistics.label("empty").collect(text.isEmpty());
        for (String label : new String[] {"CRLF", "lone CR", "surrogate pair", "Turkish", "long word"}) {
            Statistics.label(label).coverage(coverage -> coverage.check(true).percentage(p -> p >= 20));
        }
        Statistics.label("empty").coverage(coverage -> coverage.check(true).count(count -> count > 0));
    }

    /* Counts fence delimiters at line starts; an odd count leaves the last fence open. */
    private static boolean hasUnclosedFence(String text) {
        return text.lines()
                                .filter(line -> line.startsWith("```") || line.startsWith("~~~"))
                                .count()
                        % 2
                == 1;
    }
}
