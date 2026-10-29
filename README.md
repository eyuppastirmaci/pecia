# Pecia

Pecia is a local command-line tool that turns a folder of documents and source code into a searchable index. It walks a directory, splits text into coherent chunks, and stores them in a single SQLite file next to your project for offline BM25 search. With semantic indexing enabled, it also embeds each chunk with a small model running in the same process, so queries can match by meaning as well as by words.

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
- [x] Optional semantic indexing with all-MiniLM-L6-v2, downloaded once on first use, verified, and cached.
- [x] Vector storage, resumable embedding backfill, and rebuilds when the embedding model changes.
- [x] Semantic search with sqlite-vec, hybrid BM25 + vector ranking with reciprocal rank fusion, and an `auto` mode.
- [x] Reusable Java core API and runnable JAR with `init`, `index`, and `query` commands.

### Planned for v0.1.0

- [ ] Bounded parallel processing, cancellation, and semantic failure recovery.
- [ ] Richer dry runs, progress reporting, and run summaries.
- [ ] JSON output for indexing, queries, and dry runs.
- [ ] Expanded end-to-end and retrieval-quality checks.
- [ ] Automated macOS, Linux, and Windows release packages with installation documentation.

## Building and testing

Building needs JDK 17 or newer to run Gradle; the build itself compiles and tests on JDK 21, which Gradle downloads if it is not installed. The Gradle Wrapper is included, so no Gradle installation is needed. The CLI JAR runs on Java 21+.

The project has two Gradle modules: `pecia-core` provides discovery, extraction, chunking, storage, embeddings, and search behind a Java API; `pecia-cli` provides the terminal commands and depends on `pecia-core`.

Run these commands from the repository root:

```
./gradlew test                  # run unit tests in both modules
./gradlew :pecia-cli:shadowJar  # build the CLI JAR without running tests
./gradlew build                 # run all tests and checks, including acceptance tests against the packaged JARs
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## CLI reference

### Quick start

```
java -jar pecia-cli/build/libs/pecia.jar --help                             # list commands
java -jar pecia-cli/build/libs/pecia.jar --version                          # print version
java -jar pecia-cli/build/libs/pecia.jar init                               # write a default .pecia.toml into the current directory
java -jar pecia-cli/build/libs/pecia.jar index . --dry-run                  # list candidate files without writing an index
java -jar pecia-cli/build/libs/pecia.jar index .                            # build the local lexical index
java -jar pecia-cli/build/libs/pecia.jar index . --semantic                 # also build embeddings (downloads the model once)
java -jar pecia-cli/build/libs/pecia.jar query "authentication middleware"  # search in the default auto mode
java -jar pecia-cli/build/libs/pecia.jar query "JWT_SECRET" --mode lexical --root /path/to/project --limit 5
java -jar pecia-cli/build/libs/pecia.jar query "how are refunds paid?" --mode semantic
```

Run `index` before `query`. Every command accepts `--help`.

### Commands

| Command | What it does | Main options |
| --- | --- | --- |
| `init` | Writes a default `.pecia.toml` into the current directory, unless one is already there. | — |
| `index [folder]` | Indexes the files under the folder (default: current directory) into the index of the project that contains it, found from the nearest `.pecia.toml` or Git root: it adds new and changed files, removes deleted ones, and skips unchanged ones. With semantic indexing on, it also embeds new chunks and stored chunks that have no embedding yet. | `--dry-run`, `--semantic` / `--no-semantic` |
| `query <text>` | Searches the existing index read-only, without rereading source files, and prints the best-matching chunks. Quote text with several words. | `--mode`, `--limit`, `--root` |

### `index` options

| Option | Effect |
| --- | --- |
| `--dry-run` | Lists the files that would be indexed and whether semantic indexing would run, without reading their contents, writing an index, or downloading anything. |
| `--semantic` | Builds embeddings in this run, overriding `semantic.enabled` in `.pecia.toml`. The first semantic run downloads the model (about 90 MB) and says so on stderr. |
| `--no-semantic` | Builds only the lexical index in this run. Embeddings that are already stored stay, except those of changed or deleted files. |

Without either flag, `semantic.enabled` in `.pecia.toml` decides; it is `false` by default. The summary counts candidate, indexed, rechunked, unchanged, and deleted files, written chunks, and rejected and failed files; semantic runs add the model, discarded embeddings of an earlier model, and the chunks they embedded and backfilled.

### `query` options

| Option | Default | Effect |
| --- | --- | --- |
| `--mode <mode>` | `search.mode` in `.pecia.toml`, otherwise `auto` | Chooses the search mode for this query; see the next table. |
| `--limit <n>` | `10` | Maximum number of results, from 1 to 100. |
| `--root <dir>` | `.` | Any directory inside the project; the whole project's index is searched. |

### Search modes

| Mode | Ranks chunks by | Needs | Good for |
| --- | --- | --- | --- |
| `lexical` | BM25 over the words of the query; every word must match. | A lexical index. | Exact identifiers, error codes, file names, and phrases. |
| `semantic` | Cosine similarity between the embedded query and each chunk's embedding. | Embeddings of the current model, the cached model, and a supported platform. | Questions and paraphrases that share few words with the answer. |
| `hybrid` | Reciprocal rank fusion of the lexical and semantic rankings: `1/(60 + lexical rank) + 1/(60 + semantic rank)`, over each side's first 100 candidates. Identifier-like text (up to three words, one of them looking like code: `maxAttempts`, `BUFFER_THRESHOLD`, `com.example.retry`, `src/App.java`) keeps the lexical order, and chunks only semantic search found follow. | The same as `semantic`. | Mixed queries; chunks without an embedding can still come from the lexical side. |
| `auto` (default) | `hybrid` when the index has embeddings and everything semantic search needs is available, otherwise `lexical`. | Nothing. | Everyday use: a lexical-only project stays exactly as fast as plain lexical search. |

Queries never download the model. Lexical queries accept at most 64 words; a hybrid query with more words ranks by the semantic side alone, and only the first 254 tokens of a semantic or hybrid query are embedded. In both cases the query still succeeds and a note on stderr says so.

### Output

Each result shows the project-relative path and line range, the score with its kind, the heading path when there is one, and a snippet:

```
docs/payments.md:3-5
  RRF: 0.03278688524590164 (lexical #1, semantic #1)
  heading: Payments > Refunds
  ## Refunds

  Customers get their money back on the original card within five business days.
```

| Score line | Meaning |
| --- | --- |
| `BM25: -0.86` | Lexical score from SQLite FTS5; lower is better. |
| `cosine: 0.33` | Semantic similarity from -1 to 1; higher is better. |
| `RRF: 0.0328 (lexical #1, semantic #1)` | Hybrid score; higher is better. The parentheses list the sides that ranked the chunk and its rank on each, and end with `; identifier query` when the lexical order decided. |

Results go to stdout, so scripts can read them. Configuration warnings and `note:` lines go to stderr; a note explains, for example, why `auto` searched lexically although the index has embeddings, such as a model that is no longer cached. `No results.` is printed when nothing matches.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success, including a query with no results. |
| `1` | Invalid input or configuration, a missing, outdated, or unreadable index, or an indexing failure (for example, the model could not be downloaded). A missing index, or one that needs upgrading, prints the `index` command to run. |
| `2` | Usage error, such as an unknown option or mode. |
| `3` | An explicit `semantic` or `hybrid` query whose semantic prerequisite is missing. The error names the reason and prints the next step: `index --semantic` for missing or outdated embeddings and an uncached model, or `--mode lexical` when vector search or the embedder cannot run here. |

### Configuration

`init` writes `.pecia.toml` with every setting and its default; flags on the command line override it.

| Key | Default | Meaning |
| --- | --- | --- |
| `index.include`, `index.exclude` | supported text formats, common build and dependency folders | Glob patterns, relative to the configuration file; `.gitignore` always applies too. |
| `index.max_file_bytes` | `5242880` (5 MiB) | Larger files are rejected. |
| `chunk.max_tokens`, `chunk.overlap_tokens` | `256`, `32` | Token budget per chunk and the overlap between neighbouring chunks. |
| `semantic.enabled` | `false` | Whether `index` builds embeddings. |
| `search.mode` | `"auto"` | The default `query` mode. |
| `store.path` | `".pecia/index.db"` | Where the index is stored, relative to the configuration file. |

### Model cache

The model is cached per user, so every project shares one copy. `PECIA_CACHE_DIR` (an absolute path) overrides the default location:

| Platform | Default cache directory |
| --- | --- |
| macOS | `~/Library/Caches/pecia` |
| Linux | `$XDG_CACHE_HOME/pecia`, or `~/.cache/pecia` |
| Windows | `%LOCALAPPDATA%\pecia\cache` |

The cache holds the model, downloaded from Hugging Face and checked against its pinned size and SHA-256 before use, and a verified copy of the sqlite-vec library, placed on the first semantic query. Semantic search runs on Linux (x64 and arm64), macOS on Apple silicon, and Windows (x64); lexical search runs wherever Java 21 does.

## How it works

### 📂 File discovery

Pecia selects files using `.gitignore` rules and the include/exclude patterns in `.pecia.toml`. It skips symbolic links, internal index files, and its own `.pecia.toml` configuration files, which include patterns cannot admit; `--dry-run` previews this selection without processing file contents.

### ✂️ Extraction and chunking

Supported text files are read with size and UTF-8 validation, then split into chunks within the configured token budget. Chunks retain source paths, line ranges, and Markdown heading context where available.

### 🗃️ Local indexing

File metadata and chunks are stored in SQLite, with FTS5 kept in sync for text search. Each file is replaced atomically, so a failed replacement preserves its previous data while earlier successful files remain indexed.

Indexing validates and hashes the current source before deciding whether to skip it. Matching content, document type, and complete chunking settings skip extraction and writes, including for empty files. Changes to extraction or chunking versions, tokenizer behavior, or token budgets require reprocessing; model names and revisions alone do not change chunking compatibility.

Each indexed chunk has a deterministic SHA-256 ID derived from its project-relative path, zero-based position, and chunking profile. IDs are project-local and exclude content: an edit at the same position can keep its ID while the separate file hash tracks freshness. The Java core exposes this as `StoredChunk.stableId()` and `SearchHit.stableId()`. Their numeric database IDs remain local row locators. Legacy or invalidated records remain searchable with an empty stable ID until reindexing establishes a complete profile.

### 🧠 Embeddings

Semantic indexing embeds each chunk's content with [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) through ONNX Runtime on the CPU, producing one 384-dimensional unit vector per chunk, stored next to the chunk in the same SQLite file. Changed files are embedded as they are indexed; stored chunks without an embedding, for example after earlier lexical runs, are embedded from the index in pages that commit on their own, so an interrupted run resumes where it stopped. Each embedding records the model, revision, tokenizer, and input it was made with; when these change, the next semantic run replaces the old embeddings, and chunks are reused when only the model changed.

### 🔎 Search

`query` opens the existing index read-only and never rereads source files. Lexical search uses FTS5 with BM25 ranking. Semantic search embeds the query with the same model and ranks embedded chunks by cosine similarity with [sqlite-vec](https://github.com/asg017/sqlite-vec), loaded only for semantic and hybrid queries. Hybrid search reads both rankings from one consistent snapshot of the index and fuses their ranks, never their raw scores, so neither scale dominates. Results show project-relative paths, line ranges, scores, optional headings, and short text snippets; a chunk found lexically keeps the snippet around its matching words.

## Bundled components

- **Tokenizer vocabulary.** Offline token counting uses the 30,522-entry `vocab.txt` from [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41), bundled in the JAR (about 226 KiB), pinned and SHA-256-verified. The [tokenizer asset directory](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/) includes `NOTICE.txt` with the source, revision, and checksum, and `LICENSE.txt`.
- **sqlite-vec 0.1.9.** The loadable libraries for the supported platforms are bundled unchanged from the [release](https://github.com/asg017/sqlite-vec/releases/tag/v0.1.9); their [`NOTICE.txt`](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/semantic/sqlite-vec/NOTICE.txt) pins each library's SHA-256.
- **ONNX Runtime 1.28.0**, a runtime dependency of the CLI JAR, runs the model; its native libraries ship inside its JAR.
- **The model itself is not bundled.** The first semantic indexing run downloads it into the user's cache, as described under [Model cache](#model-cache).

## License

Pecia's own code is licensed under [MIT](LICENSE). Bundled third-party components keep their own licenses: the tokenizer vocabulary under [Apache 2.0](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/LICENSE.txt), sqlite-vec under [MIT](pecia-core/src/main/resources/META-INF/licenses/sqlite-vec-LICENSE-MIT.txt) or [Apache 2.0](pecia-core/src/main/resources/META-INF/licenses/sqlite-vec-LICENSE-APACHE.txt), and ONNX Runtime under MIT, whose license and third-party notices are included in the CLI JAR. The all-MiniLM-L6-v2 model, downloaded at runtime, is licensed under Apache 2.0.
