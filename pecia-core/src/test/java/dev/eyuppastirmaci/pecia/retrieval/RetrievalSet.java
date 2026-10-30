package dev.eyuppastirmaci.pecia.retrieval;

import dev.eyuppastirmaci.pecia.config.DefaultFileRules;
import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.retrieval.RetrievalQuery.Kind;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The labelled queries and the corpus they refer to. Loading validates the labels, so a typo fails loudly instead of
 * silently lowering a metric.
 *
 * @param corpus the directory holding the corpus files
 * @param files every corpus file, relative to {@code corpus} with forward slashes, in sorted order
 */
public record RetrievalSet(Path corpus, List<String> files, List<RetrievalQuery> queries) {

    private static final String RESOURCE = "/retrieval";
    private static final Set<String> QUERY_KEYS = Set.of("id", "kind", "text", "answers", "related", "answer_first_in");

    public RetrievalSet {
        files = List.copyOf(files);
        queries = List.copyOf(queries);
    }

    /** Loads the committed set from the test resources. */
    public static RetrievalSet committed() throws IOException {
        URL url = RetrievalSet.class.getResource(RESOURCE);

        if (url == null) {
            throw new IllegalStateException("Missing test resource " + RESOURCE);
        }

        try {
            return load(Path.of(url.toURI()));
        } catch (URISyntaxException invalid) {
            throw new IllegalStateException("Cannot resolve " + url, invalid);
        }
    }

    /**
     * Loads {@code queries.toml} and the {@code corpus} directory below {@code root}.
     *
     * @throws IllegalArgumentException if the file cannot be parsed or a label breaks a rule, naming the query
     */
    public static RetrievalSet load(Path root) throws IOException {
        Path corpus = root.resolve("corpus");
        List<String> files = corpusFiles(corpus);
        TomlParseResult parsed = Toml.parse(root.resolve("queries.toml"));

        if (parsed.hasErrors()) {
            throw new IllegalArgumentException("queries.toml: " + parsed.errors());
        }

        TomlArray tables = parsed.getArrayOrEmpty("query");
        List<RetrievalQuery> queries = new ArrayList<>();

        for (int index = 0; index < tables.size(); index++) {
            queries.add(query(tables.getTable(index), Set.copyOf(files)));
        }

        validateSet(queries);
        return new RetrievalSet(corpus, files, queries);
    }

    private static List<String> corpusFiles(Path corpus) throws IOException {
        try (Stream<Path> paths = Files.walk(corpus)) {
            List<String> files = paths.filter(Files::isRegularFile)
                    .map(path -> corpus.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();

            for (String file : files) {
                requireIndexable(file);
            }

            return files;
        }
    }

    /** Rejects a corpus file that indexing would skip, since its labels could then never be met. */
    private static void requireIndexable(String file) {
        List<String> parts = List.of(file.split("/"));
        String name = parts.getLast();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);

        if (!DefaultFileRules.EXTENSIONS.contains(extension) && !DefaultFileRules.BASENAMES.contains(name)) {
            throw new IllegalArgumentException("Corpus file of an unsupported type: " + file);
        }

        for (String directory : parts.subList(0, parts.size() - 1)) {
            if (DefaultFileRules.EXCLUDED_DIRECTORIES.contains(directory) || directory.startsWith(".")) {
                throw new IllegalArgumentException("Corpus file in a directory indexing skips: " + file);
            }
        }
    }

    private static RetrievalQuery query(TomlTable table, Set<String> files) {
        String id = table.getString("id");

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A query has no id");
        }

        Set<String> unknown = new HashSet<>(table.keySet());
        unknown.removeAll(QUERY_KEYS);
        require(unknown.isEmpty(), id, "unknown keys " + unknown);

        String kindName = table.getString("kind");
        Kind kind = kindName == null ? null : kind(kindName, id);
        require(kind != null, id, "no kind");
        String text = table.getString("text");
        require(text != null && !text.isBlank(), id, "no text");
        List<String> answers = strings(table, "answers", id);
        List<String> related = strings(table, "related", id);
        require(!answers.isEmpty(), id, "no answers");

        for (String path : concat(answers, related)) {
            require(files.contains(path), id, "names a file not in the corpus: " + path);
        }

        require(
                new HashSet<>(concat(answers, related)).size() == answers.size() + related.size(),
                id,
                "repeats a path");
        Set<SearchMode> answerFirstIn = EnumSet.noneOf(SearchMode.class);

        for (String mode : strings(table, "answer_first_in", id)) {
            SearchMode parsed = mode(mode, id);
            require(answerFirstIn.add(parsed), id, "repeats the mode " + mode);
        }

        return new RetrievalQuery(id, kind, text, answers, related, answerFirstIn);
    }

    private static void validateSet(List<RetrievalQuery> queries) {
        Set<String> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);

        for (RetrievalQuery query : queries) {
            require(ids.add(query.id()), query.id(), "repeats an id");
            require(texts.add(query.text()), query.id(), "repeats the text of another query");
            kinds.add(query.kind());
        }

        if (!kinds.equals(EnumSet.allOf(Kind.class))) {
            throw new IllegalArgumentException("Every kind needs queries; present: " + kinds);
        }
    }

    private static Kind kind(String name, String id) {
        for (Kind kind : Kind.values()) {
            if (kind.name().toLowerCase(Locale.ROOT).equals(name)) {
                return kind;
            }
        }

        throw new IllegalArgumentException("Query " + id + ": unknown kind " + name);
    }

    /** Parses a mode that names one way of searching; {@code auto} chooses among them, so it cannot be required. */
    private static SearchMode mode(String name, String id) {
        for (SearchMode mode : List.of(SearchMode.LEXICAL, SearchMode.SEMANTIC, SearchMode.HYBRID)) {
            if (mode.name().toLowerCase(Locale.ROOT).equals(name)) {
                return mode;
            }
        }

        throw new IllegalArgumentException("Query " + id + ": unknown mode " + name);
    }

    private static List<String> strings(TomlTable table, String key, String id) {
        if (!table.contains(key)) {
            return List.of();
        }

        TomlArray array = table.getArray(key);
        require(array != null, id, key + " is not an array");
        List<String> values = new ArrayList<>();

        for (int index = 0; index < array.size(); index++) {
            Object value = array.get(index);
            require(value instanceof String, id, key + " holds a value that is not a string");
            values.add((String) value);
        }

        return values;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static void require(boolean condition, String id, String problem) {
        if (!condition) {
            throw new IllegalArgumentException("Query " + id + ": " + problem);
        }
    }
}
