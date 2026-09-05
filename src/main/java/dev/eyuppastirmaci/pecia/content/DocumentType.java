package dev.eyuppastirmaci.pecia.content;

import java.util.List;

/** Text-processing families; recognition does not validate or parse file contents. */
public enum DocumentType {
    PLAIN_TEXT(List.of("txt", "rst", "adoc"),
            List.of("README", "LICENSE", "LICENCE", "NOTICE", "CHANGELOG", "AUTHORS", "CONTRIBUTING")),
    MARKDOWN(List.of("md", "markdown"), List.of()),
    SOURCE_CODE(List.of("java", "kt", "kts", "py", "pyi", "js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts",
            "c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx", "cs", "go", "rs", "rb", "php", "swift",
            "sh", "bash", "zsh", "ps1", "psm1", "bat", "cmd", "sql", "css", "scss", "sass", "less"),
            List.of("Dockerfile", "Containerfile", "Makefile", "Jenkinsfile")),
    STRUCTURED_TEXT(List.of("json", "jsonc", "yaml", "yml", "toml", "ini", "cfg", "properties", "xml"), List.of());

    private final List<String> extensions;
    private final List<String> basenames;

    DocumentType(List<String> extensions, List<String> basenames) {
        this.extensions = List.copyOf(extensions);
        this.basenames = List.copyOf(basenames);
    }

    /**
     * Returns the filename extensions recognized as this document type.
     *
     * @return the immutable recognized extensions without leading dots
     */
    public List<String> extensions() {

        return extensions;
    }

    /**
     * Returns the extensionless filenames recognized as this document type.
     *
     * @return the immutable recognized basenames
     */
    public List<String> basenames() {

        return basenames;
    }
}
