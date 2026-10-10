package dev.eyuppastirmaci.pecia.config;

import java.util.Arrays;
import java.util.List;

/**
 * Suggests the known key or table closest to an unknown one. A candidate is close when its optimal string alignment
 * distance is at most 2 and less than half the longer name, which catches typos without guessing for unrelated
 * names.
 */
final class KeySuggestion {

    private static final int MAX_DISTANCE = 2;

    private KeySuggestion() {}

    /**
     * Returns the known key closest to an unknown key path, or null. The full dotted path is compared first, which
     * also catches misspelled tables; then the last segment is compared with each known key's name, preferring the
     * same table, which catches keys placed in the wrong table or at the root.
     */
    static String forKey(List<String> path) {
        String byPath = closest(
                String.join(".", path),
                Arrays.stream(ConfigKey.values()).map(ConfigKey::path).toList());

        if (byPath != null) {
            return byPath;
        }

        String leaf = path.getLast();
        String table = path.size() > 1 ? path.get(path.size() - 2) : null;
        ConfigKey best = null;
        int bestRank = Integer.MAX_VALUE;

        for (ConfigKey key : ConfigKey.values()) {
            int distance = distance(leaf, key.leaf());

            if (close(leaf, key.leaf(), distance)) {
                // Distance decides first; the key's own table breaks ties.
                int rank = distance * 2 + (key.table().equals(table) ? 0 : 1);

                if (rank < bestRank) {
                    best = key;
                    bestRank = rank;
                }
            }
        }

        return best == null ? null : best.path();
    }

    /** Returns the known table closest to an unknown table path, or null. */
    static String forTable(List<String> path) {
        return closest(String.join(".", path), ConfigKey.tables());
    }

    private static String closest(String name, List<String> candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (String candidate : candidates) {
            int distance = distance(name, candidate);

            if (close(name, candidate, distance) && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }

        return best;
    }

    private static boolean close(String name, String candidate, int distance) {
        return distance <= MAX_DISTANCE && distance * 2 < Math.max(name.length(), candidate.length());
    }

    /** Counts insertions, deletions, substitutions, and adjacent transpositions (optimal string alignment). */
    static int distance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }

        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);

                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }

        return d[a.length()][b.length()];
    }
}
