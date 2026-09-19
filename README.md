# Pecia

Pecia is a local command-line tool that turns a folder of documents and source code into a searchable vector index. It walks a directory, splits text into coherent chunks, embeds them with a small model running in the same process, and stores everything in a single SQLite file next to your project.

## Status

**Early development.**

### Available now

- [x] Project initialization and TOML configuration.
- [x] File discovery with `.gitignore`, include/exclude filters, and `--dry-run`.
- [x] Validated UTF-8 text extraction and SHA-256 content hashing.
- [x] Token-budget chunking for text, Markdown, and source code, using a bundled offline tokenizer.
- [x] SQLite storage with atomic file replacement and synchronized FTS5 indexing.
- [x] Incremental indexing: skip unchanged files and remove deleted files.
- [x] Stable chunk identities and versioned extraction, tokenizer, and chunking compatibility.
- [x] Offline BM25 search with paths, line ranges, scores, optional headings, and snippets.
- [x] Reusable Java core API and runnable JAR with `init`, `index`, and `query` commands.

### Planned for v0.1.0

- [ ] Embedding compatibility tracking.
- [ ] Optional MiniLM semantic search with explicit model download and caching.
- [ ] Vector storage and resumable embedding backfill.
- [ ] Hybrid BM25 + vector ranking with RRF and search mode selection.
- [ ] Bounded parallel processing, cancellation, and semantic failure recovery.
- [ ] Richer dry runs, progress reporting, and run summaries.
- [ ] JSON output for indexing, queries, and dry runs.
- [ ] Expanded end-to-end and retrieval-quality checks.
- [ ] Automated macOS, Linux, and Windows release packages with installation documentation.

## Building and testing

Requires JDK 21+. The Maven Wrapper is included, so no Maven installation is needed.

The project has two Maven modules: `pecia-core` provides document discovery, extraction, chunking, and token counting; `pecia-cli` provides the terminal commands and depends on `pecia-core`.

Run these commands from the repository root:

```
./mvnw test           # run unit tests in both modules
./mvnw clean package  # run unit tests and build both JARs
./mvnw clean verify   # also run acceptance tests against the packaged JARs
```

On Windows use `.\mvnw.cmd` instead of `./mvnw`.

## Commands

```
java -jar pecia-cli/target/pecia.jar --help                             # list commands
java -jar pecia-cli/target/pecia.jar --version                          # print version
java -jar pecia-cli/target/pecia.jar init                               # write a default .pecia.toml into the current directory
java -jar pecia-cli/target/pecia.jar index . --dry-run                  # list candidate files without writing an index
java -jar pecia-cli/target/pecia.jar index .                            # build the local SQLite lexical index
java -jar pecia-cli/target/pecia.jar query "authentication middleware"  # search the current project's index with BM25
java -jar pecia-cli/target/pecia.jar query "JWT_SECRET" --root /path/to/project --limit 5
```

Run `index` before `query`. The query defaults are `--root .` and `--limit 10`.

## How it works

### 📂 File discovery

Pecia selects files using `.gitignore` rules and the include/exclude patterns in `.pecia.toml`. It skips symbolic links and internal index files; `--dry-run` previews this selection without processing file contents.

### ✂️ Extraction and chunking

Supported text files are read with size and UTF-8 validation, then split into chunks within the configured token budget. Chunks retain source paths, line ranges, and Markdown heading context where available.

### 🗃️ Local indexing

File metadata and chunks are stored in SQLite, with FTS5 kept in sync for text search. Each file is replaced atomically, so a failed replacement preserves its previous data while earlier successful files remain indexed.

Indexing validates and hashes the current source before deciding whether to skip it. Matching content, document type, and complete chunking settings skip extraction and writes, including for empty files. Changes to extraction or chunking versions, tokenizer behavior, or token budgets require reprocessing; model names and revisions alone do not change chunking compatibility.

Each indexed chunk has a deterministic SHA-256 ID derived from its project-relative path, zero-based position, and chunking profile. IDs are project-local and exclude content: an edit at the same position can keep its ID while the separate file hash tracks freshness. The Java core exposes this as `StoredChunk.stableId()` and `SearchHit.stableId()`. Their numeric database IDs remain local row locators. Legacy or invalidated records remain searchable with an empty stable ID until reindexing establishes a complete profile.

### 🔎 Lexical search

`query` opens the existing index read-only and uses FTS5 to find text matches ranked by BM25, without rereading source files. Results show project-relative paths, line ranges, scores, optional headings, and short text snippets.

## Bundled tokenizer assets

Offline token counting uses the 30,522-entry `vocab.txt` from [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41), bundled in the JAR (about 226 KiB). The vocabulary is pinned and SHA-256-verified; token counting requires no model download or network access.

The [tokenizer asset directory](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/) includes `NOTICE.txt` with the source, revision, and checksum, and `LICENSE.txt` with the Apache 2.0 license for the vocabulary.

## License

Pecia's own code is licensed under [MIT](LICENSE). The bundled vocabulary is licensed separately under [Apache 2.0](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/LICENSE.txt).
