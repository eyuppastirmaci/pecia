package dev.eyuppastirmaci.pecia.performance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Writes a reproducible project of Markdown, Java, and plain-text files for performance measurements. The same seed
 * and size always give the same bytes, so runs on different commits or machines index the same text. Words follow a
 * Zipf-like distribution over a generated vocabulary, so some are common and most are rare, as in real projects.
 */
public final class SyntheticCorpus {

    static final int VOCABULARY_SIZE = 4000;
    private static final String[] SYLLABLES = {
        "ka", "lo", "mi", "ra", "te", "su", "no", "vi", "da", "pe", "zu", "ri", "go", "ha", "be", "xo", "an", "el",
        "or", "un"
    };
    private static final int DIRECTORIES = 100;

    private final long seed;
    private final List<String> vocabulary;
    // Cumulative Zipf weights over the vocabulary, for sampling a word by its rank.
    private final double[] cumulative;

    public SyntheticCorpus(long seed) {
        this.seed = seed;
        this.vocabulary = vocabulary(new Random(seed));
        this.cumulative = zipf(VOCABULARY_SIZE);
    }

    /** Returns the vocabulary in rank order, most common first. */
    public List<String> vocabulary() {
        return vocabulary;
    }

    /**
     * Writes {@code files} files below {@code root}, spread over a hundred directories, and returns their paths
     * relative to {@code root} in the order they were written.
     */
    public List<Path> write(Path root, int files) throws IOException {
        Random random = new Random(seed ^ files);
        List<Path> written = new ArrayList<>();

        for (int index = 0; index < files; index++) {
            Path relative = path(index);
            Path file = root.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content(index, random));
            written.add(relative);
        }

        return written;
    }

    /** Rewrites one paragraph of a file the corpus wrote, as an edit would; the same index always gives the same edit. */
    public void edit(Path root, Path relative, int index) throws IOException {
        Random random = new Random(seed * 31 + index);
        Files.writeString(root.resolve(relative), "\n" + paragraph(random) + "\n", StandardOpenOption.APPEND);
    }

    /** Returns a word sampled by its Zipf weight. */
    String word(Random random) {
        double target = random.nextDouble() * cumulative[cumulative.length - 1];
        int low = 0;
        int high = cumulative.length - 1;

        while (low < high) {
            int middle = (low + high) >>> 1;

            if (cumulative[middle] < target) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }

        return vocabulary.get(low);
    }

    private static Path path(int index) {
        String directory = "area" + (index % DIRECTORIES);
        String name = "file" + index;

        return switch (index % 20) {
            // About three in five files are Markdown, one in four Java, the rest plain text.
            case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 -> Path.of(directory, "docs", name + ".md");
            case 12, 13, 14, 15, 16 -> Path.of(directory, "src", capitalized(name) + ".java");
            default -> Path.of(directory, "notes", name + ".txt");
        };
    }

    private String content(int index, Random random) {
        // One to eight paragraphs, so files range from a few hundred bytes to several kilobytes.
        int paragraphs = 1 + random.nextInt(8);
        StringBuilder text = new StringBuilder();

        switch (index % 20) {
            case 12, 13, 14, 15, 16 -> {
                text.append("package area").append(index % DIRECTORIES).append(";\n\n");
                text.append("/** ").append(paragraph(random)).append(" */\n");
                text.append("final class ").append(capitalized("file" + index)).append(" {\n");

                for (int method = 0; method < paragraphs; method++) {
                    String name = word(random) + capitalized(word(random));
                    text.append("\n    /** ").append(sentence(random)).append(" */\n");
                    text.append("    int ")
                            .append(name)
                            .append("(int ")
                            .append(word(random))
                            .append(") {\n");
                    text.append("        return ").append(random.nextInt(1000)).append(";\n    }\n");
                }

                text.append("}\n");
            }
            default -> {
                if (index % 20 < 12) {
                    text.append("# ")
                            .append(capitalized(word(random)))
                            .append(' ')
                            .append(word(random))
                            .append("\n\n");
                }

                for (int paragraph = 0; paragraph < paragraphs; paragraph++) {
                    if (index % 20 < 12 && paragraph > 0 && paragraph % 3 == 0) {
                        text.append("## ").append(capitalized(word(random))).append("\n\n");
                    }

                    text.append(paragraph(random)).append("\n\n");
                }
            }
        }

        return text.toString();
    }

    private String paragraph(Random random) {
        int sentences = 2 + random.nextInt(5);
        StringBuilder paragraph = new StringBuilder();

        for (int sentence = 0; sentence < sentences; sentence++) {
            if (sentence > 0) {
                paragraph.append(' ');
            }

            paragraph.append(sentence(random));
        }

        return paragraph.toString();
    }

    private String sentence(Random random) {
        int words = 6 + random.nextInt(12);
        StringBuilder sentence = new StringBuilder(capitalized(word(random)));

        for (int word = 1; word < words; word++) {
            sentence.append(' ').append(word(random));
        }

        return sentence.append('.').toString();
    }

    private static List<String> vocabulary(Random random) {
        List<String> words = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        while (words.size() < VOCABULARY_SIZE) {
            int syllables = 2 + random.nextInt(3);
            StringBuilder word = new StringBuilder();

            for (int syllable = 0; syllable < syllables; syllable++) {
                word.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
            }

            if (seen.add(word.toString())) {
                words.add(word.toString());
            }
        }

        return List.copyOf(words);
    }

    private static double[] zipf(int size) {
        double[] cumulative = new double[size];
        double total = 0;

        for (int rank = 0; rank < size; rank++) {
            total += 1.0 / (rank + 1);
            cumulative[rank] = total;
        }

        return cumulative;
    }

    private static String capitalized(String word) {
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
