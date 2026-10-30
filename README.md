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
- [x] Parallel embedding with bounded memory, and indexing runs that stop cleanly on Ctrl+C.
- [x] Failure isolation: unreadable folders and files that cannot be read or embedded are reported while everything else is indexed, storage failures say what to do, and an interrupted or killed run recovers on the next run.
- [x] Reusable Java core API and runnable JAR with `init`, `index`, and `query` commands.
- [x] JSON output for indexing, queries, and dry runs, with match ranges for highlighting.
- [x] Detailed dry runs, live progress, grouped run summaries, and coloured, readable terminal output.

### Planned for v0.1.0

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
./gradlew referenceTest         # check the real semantic model, through the core API and the CLI JAR
./gradlew :pecia-core:performanceTest  # measure indexing and query speed on a synthetic corpus
./gradlew :pecia-core:pitest           # run mutation testing on chunking, incremental indexing, and ranking
```

On Windows use `gradlew.bat` instead of `./gradlew`.

`build` never downloads anything and uses a tiny test model. The other tasks run only on request:

| Task | Checks | Report |
| --- | --- | --- |
| `referenceTest` | The real model: embeddings, retrieval quality, and the CLI's semantic workflows | `pecia-core/build/reports/retrieval/` |
| `:pecia-core:performanceTest` | Indexing and query speed on a generated corpus; never fails on time | `pecia-core/build/reports/performance/index.md` |
| `:pecia-core:pitest` | Which code mutations the unit tests miss | `pecia-core/build/reports/pitest/` |

Retrieval quality is measured on a labelled corpus (`pecia-core/src/test/resources/retrieval`): lexical search in `test`, semantic and hybrid in `referenceTest`. A metric below its threshold fails the run.

`referenceTest` reads the model from `PECIA_CACHE_DIR` and may write there, so point it at a copy of your cache (see [Model cache](#model-cache)). If no model is cached yet, `java -jar pecia-cli/build/libs/pecia.jar index --semantic <folder>` downloads it (about 90 MB) into the cache that the variable names.

```
export PECIA_CACHE_DIR=/tmp/pecia-reference-cache
mkdir -p "$PECIA_CACHE_DIR"
cp -R ~/Library/Caches/pecia/models "$PECIA_CACHE_DIR"   # macOS path
./gradlew referenceTest
```

A hanging test fails after five minutes with a thread dump in its XML report.

## CLI reference

### Quick start

```
java -jar pecia-cli/build/libs/pecia.jar --help                             # list commands
java -jar pecia-cli/build/libs/pecia.jar --version                          # print version
java -jar pecia-cli/build/libs/pecia.jar init                               # write a default .pecia.toml into the current directory
java -jar pecia-cli/build/libs/pecia.jar index . --dry-run                  # show what indexing would change, writing nothing
java -jar pecia-cli/build/libs/pecia.jar index .                            # build the local lexical index
java -jar pecia-cli/build/libs/pecia.jar index . --semantic                 # also build embeddings (downloads the model once)
java -jar pecia-cli/build/libs/pecia.jar index . --progress=plain           # progress as plain lines, for logs
java -jar pecia-cli/build/libs/pecia.jar query "authentication middleware"  # search in the default auto mode
java -jar pecia-cli/build/libs/pecia.jar query "JWT_SECRET" --mode lexical --root /path/to/project --limit 5
java -jar pecia-cli/build/libs/pecia.jar query "how are refunds paid?" --mode semantic
java -jar pecia-cli/build/libs/pecia.jar query "refund policy" --json          # one JSON document for scripts
```

Run `index` before `query`. Every command accepts `--help` and `--color=auto|always|never`; see [Colour](#colour).

### Commands

| Command | What it does | Main options |
| --- | --- | --- |
| `init` | Writes a default `.pecia.toml` into the current directory, unless one is already there. | — |
| `index [folder]` | Indexes the files under the folder (default: current directory) into the index of the project that contains it, found from the nearest `.pecia.toml` or Git root: it adds new and changed files, removes deleted ones, and skips unchanged ones. With semantic indexing on, it also embeds new chunks and stored chunks that have no embedding yet. | `--dry-run`, `--semantic` / `--no-semantic`, `--progress`, `--json` |
| `query <text>` | Searches the existing index read-only, without rereading source files, and prints the best-matching chunks. Quote text with several words. | `--mode`, `--limit`, `--root`, `--json` |

### `index` options

| Option | Effect |
| --- | --- |
| `--dry-run` | Shows what the run would do without changing the project: it reads the files to compare them with the index, but never writes the index, downloads the model, or loads it. An index of an older format is compared after upgrading a temporary copy, which is then deleted. See [Dry runs](#dry-runs). |
| `--semantic` | Builds embeddings in this run, overriding `semantic.enabled` in `.pecia.toml`. The first semantic run downloads the model (about 90 MB) and says so on stderr. |
| `--no-semantic` | Builds only the lexical index in this run. Embeddings that are already stored stay, except those of changed or deleted files. |
| `--progress=<mode>` | How a run shows its progress on stderr: `auto` (default), `plain`, or `none`; see [Progress](#progress). |
| `--json` | Prints the run's or dry run's outcome as one JSON document instead of the summary; see [JSON output](#json-output). |

Without either flag, `semantic.enabled` in `.pecia.toml` decides; it is `false` by default.

#### Run summary

Each run ends with a summary on stdout:

```
index: /home/me/project/.pecia/index.db
files: 2 indexed, 0 rechunked, 41 unchanged, 1 deleted, 1 rejected, 0 failed (44 found)
chunks: 9 written
model: all-MiniLM-L6-v2 cached
embeddings: 9 made, 312 backfilled, 0 discarded
time: 3.2 s (101 chunks/s)
```

- `files` says what happened to each file found. Rechunked files are indexed files whose content was unchanged but whose chunk settings changed.
- `model` and `embeddings` appear for semantic runs. Discarded embeddings belong to an earlier model; backfilled ones were made for stored chunks that had none.
- `time` is the whole run, with the pace of the chunks written and backfilled.

#### Progress

A run shows how far it has come on stderr, phase by phase: scanning, downloading or loading the model, indexing, removing deleted files, and embedding stored chunks.

| `--progress` | What it shows |
| --- | --- |
| `auto` (default) | One line that updates in place on an interactive terminal, such as `indexing: 1,240 of 18,268 files, 9,812 chunks, 197 chunks/s, about 1 min left`; nothing when output goes to a pipe or a file. |
| `plain` | A line every 5 seconds and when a phase ends, with the time since the run started, such as `[0:12] indexing: 1,240 of 18,268 files`; meant for logs and CI. |
| `none` | Nothing. |

The progress line is cleared before warnings, the summary, or JSON, so it never mixes with them. Dry runs show no progress.

Ctrl+C stops a run before its next file or embedding batch, prints the summary of what it finished, and exits with `130`. Files indexed until then stay in the index, a file in progress keeps its previous version, and no deleted files are removed; the next run continues from there.

#### Dry runs

`--dry-run` compares the files with the index as a run would, then prints a summary and the files the run would change:

```
config: /home/me/project/.pecia.toml
index: /home/me/project/.pecia/index.db (current)
semantic: enabled
files: 1 new, 1 changed, 0 rechunked, 41 unchanged, 1 rejected, 0 removed
chunks: ~9 to write, 312 kept, 0 removed
model: all-MiniLM-L6-v2 cached
embeddings: ~9 to make, 0 to discard

new:
  docs/refunds.md (~3 chunks)
changed:
  src/auth/middleware.ts (~6 chunks)
rejected:
  assets/logo.txt: BINARY_CONTENT: File contains binary control characters: /home/me/project/assets/logo.txt
```

Counts marked `~` are estimated from file sizes; the others are read from the index. The `index` line says whether the index is `current`, does not exist yet (`none yet; would be created`), has an older format (`older format; would be upgraded`), or `cannot be read`, in which case the dry run fails as the run would, with the same advice. The `model` line says whether the model is cached or would be downloaded, with its size. A rejected file or an unreadable folder keeps the run from removing deleted files, so none are listed then. A dry run exits with `0`, even with rejected files, unless the index cannot be read or a folder cannot be scanned.

### `query` options

| Option | Default | Effect |
| --- | --- | --- |
| `--mode <mode>` | `search.mode` in `.pecia.toml`, otherwise `auto` | Chooses the search mode for this query; see the next table. |
| `--limit <n>` | `10` | Maximum number of results, from 1 to 100. |
| `--root <dir>` | `.` | Any directory inside the project; the whole project's index is searched. |
| `--json` | off | Prints the results as one JSON document; see [JSON output](#json-output). |

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
  RRF: 0.0328 (lexical #1, semantic #1)
  heading: Payments > Refunds
  ## Refunds

  Customers get their money back on the original card within five business days.
```

| Score line | Meaning |
| --- | --- |
| `BM25: -0.86` | Lexical score from SQLite FTS5; lower is better. |
| `cosine: 0.33` | Semantic similarity from -1 to 1; higher is better. |
| `RRF: 0.0328 (lexical #1, semantic #1)` | Hybrid score; higher is better. The parentheses list the sides that ranked the chunk and its rank on each, and end with `; identifier query` when the lexical order decided. |

- Scores are rounded to three significant digits; JSON keeps their full precision.
- Results go to stdout. Configuration warnings and `note:` lines go to stderr; a note explains, for example, why `auto` searched lexically although the index has embeddings, such as a model that is no longer cached.
- `No results.` is printed when nothing matches.

### Colour

On a terminal that shows colour, results, summaries, and stderr labels are coloured: the path is bold, the line range, score, and heading dim, and the words that matched bold red, as ripgrep shows them. Output that goes to a pipe or a file is plain, and JSON never has colour.

| Setting | Effect |
| --- | --- |
| `--color=auto` (default) | Colour on a terminal that shows it. |
| `--color=always` / `--color=never` | Always or never, whatever the variables below say. |
| `NO_COLOR` (set and not empty) | No colour in `auto` mode, as [no-color.org](https://no-color.org) describes. |
| `CLICOLOR_FORCE` (set and not `0`) | Colour in `auto` mode even into a pipe, unless `NO_COLOR` is set. |

- `--color` may come before or after the command: `pecia --color=never query …` and `pecia query … --color=never` are the same.
- On Windows, `auto` colours Windows Terminal, ConEmu, ANSICON, and shells that set `TERM`, such as Git Bash; the legacy console prints escape codes as text, so it stays plain.
- Java can tell whether the console, stdin and stdout, is a terminal, but not stderr alone. When stdout goes to a pipe, `auto` shows neither colour nor the progress line on stderr; `--color=always` and `--progress=plain` still work.

### JSON output

With `--json`, `index`, `index --dry-run`, and `query` print one JSON document on stdout, also when they fail.

- Stderr and exit codes stay the same as without `--json`.
- Usage errors, such as an unknown option, stay plain text on stderr with exit code `2` and nothing on stdout.

<details>
<summary>Example: a lexical query</summary>

```
$ java -jar pecia-cli/build/libs/pecia.jar query "refund card" --mode lexical --json
{
  "schemaVersion": 1,
  "command": "query",
  "query": "refund card",
  "limit": 10,
  "requestedMode": "lexical",
  "effectiveMode": "lexical",
  "fallback": null,
  "notices": [],
  "queryShape": null,
  "embeddingCoverage": null,
  "warnings": [],
  "hits": [
    {
      "rank": 1,
      "path": "refunds.md",
      "documentType": "MARKDOWN",
      "lines": {
        "start": 1,
        "end": 3
      },
      "chunkIndex": 0,
      "stableId": "34a3a27471462101e7f371f1e4ffda1a4edf31a2a4a9026c042c888700aac3ca",
      "headingPath": [
        "Refunds"
      ],
      "score": {
        "kind": "SQLITE_BM25",
        "value": -2.0E-6
      },
      "fusedRanks": null,
      "snippet": "# Refunds\n\nA refund takes five days. Refunds go to the original card.\n",
      "matches": [
        {
          "start": 13,
          "end": 19
        },
        {
          "start": 64,
          "end": 68
        }
      ]
    }
  ],
  "error": null
}
```

</details>

**Conventions**

- Every document starts with `schemaVersion` (currently `1`) and `command`.
- Every field is always present, in the same order; `null` means it does not apply.
- New fields may appear within a version. A field that changes meaning or is removed raises the version.
- Project paths are relative to the project root and use `/`. `root`, `database`, `config`, and warning sources are absolute.
- Modes are spelled as `--mode` takes them (`"hybrid"`); other codes as stderr prints them (`"PARTIAL"`).
- Scores keep full precision.
- The output is plain ASCII: other characters are written as `\u` escapes, so no console or code page can garble them.

<details>
<summary>Documents: the fields of each document</summary>

| Document | Fields |
| --- | --- |
| `query` | `query`, `limit`, `requestedMode`, `effectiveMode`, `fallback`, `notices`, `queryShape`, `embeddingCoverage`, `warnings`, `hits`, `error` |
| Query hit | `rank`, `path`, `documentType`, `lines`, `chunkIndex`, `stableId`, `headingPath`, `score`, `fusedRanks`, `snippet`, `matches` |
| `index` | `dryRun` (`false`), `root`, `database`, `config`, `status`, `files`, `chunks`, `embeddings`, `durationMillis`, `phases`, `issues`, `scanIssues`, `semanticIssues`, `warnings`, `error` |
| `index --dry-run` | `dryRun` (`true`), `root`, `database`, `config`, `semantic`, `index`, `files`, `chunks`, `embeddings`, `newFiles`, `changedFiles`, `rechunkedFiles`, `rejectedFiles`, `removedFiles`, `scanIssues`, `warnings`, `error` |

- `fallback` is set when `auto` searched lexically.
- `queryShape` and `embeddingCoverage` are set when embeddings were compared, and `fusedRanks` for hybrid hits.
- `embeddings` is set for semantic runs and dry runs.
- A dry run only counts unchanged files; it does not list them.

</details>

<details>
<summary>Nested fields: what each field contains</summary>

| Field | Fields or values |
| --- | --- |
| `lines` | `start`, `end` |
| `score` | `kind`, `value` |
| `fusedRanks` | `lexical`, `semantic`: a rank, or `null` when that side did not rank the hit |
| `embeddingCoverage`, `coverage` | `embedded`, `stale`, `missing` |
| `status` | `COMPLETE`, `PARTIAL`, `CANCELLED`, or a failure such as `FAILED` |
| `index` | `NONE`, `CURRENT`, `UPGRADE`, `UNREADABLE` |
| `files` (run) | `candidates`, `indexed`, `rechunked`, `unchanged`, `deleted`, `rejected`, `failed` |
| `files` (dry run) | `new`, `changed`, `rechunked`, `unchanged`, `rejected`, `removed` |
| `chunks` (run) | `written` |
| `chunks` (dry run) | `toWrite`, `kept`, `removed` |
| `embeddings` (run) | `model`, `modelSource` (`CACHED`, `DOWNLOADED`), `embedded`, `backfilled`, `discarded`, `coverage` |
| `embeddings` (dry run) | `model`, `modelState` (`CACHED`, `DOWNLOAD`, `UNAVAILABLE`), `downloadBytes`, `toEmbed`, `toDiscard` |
| `phases` | milliseconds per phase the run reached, in run order: `SCAN`, `MODEL`, `FILES`, `CLEANUP`, `BACKFILL` |
| `newFiles`, `changedFiles`, `rechunkedFiles`, `removedFiles` | `path`, `chunks` |
| `issues`, `rejectedFiles` | `path`, `reason`, `rejected`, `message` |
| `scanIssues` | `path`, `cause`, `message` |
| `semanticIssues` | `path`, `reason`, `message` |

In dry runs, a chunk count is `{"value": n, "estimated": true}` when it is estimated from file sizes, and `"estimated": false` when it is read from the index.

</details>

**Match ranges**

- `matches` lists where the snippet matches the query, so a program can highlight it.
- Each range has an inclusive `start` and an exclusive `end`, in Unicode code points from the start of the snippet.
- Hits found only by semantic search have no ranges, and neither do lexical hits that match only in their headings or path.
- Ranges are never wrong, at worst missing: a hit whose text contains U+FDD0 or U+FDD1, the markers used to find them, has none.

**Errors**

- `error` is `null` on success. Otherwise it holds a `reason` and the `message` that stderr shows.
- Reasons are the codes stderr prints, such as `INDEX_NOT_FOUND`, `OFFLINE`, or `CORRUPT`. Two more cover failures before a command has a result:
  - `INVALID_INPUT`: rejected input, such as a limit outside 1–100 or an invalid `.pecia.toml`.
  - `IO_ERROR`: a folder that is missing or cannot be read. The same code also names a disk error while writing the index.
- When a failure leaves no result, such as a missing folder, the result fields are `null`.
- A partial run or an incomplete scan is not an error: `status` or `scanIssues` say what was left out, and the exit code is `1`.

### Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success, including a query with no results. |
| `1` | Invalid input or configuration, a missing, outdated, or unreadable index, an indexing failure (for example, the model could not be downloaded), or an `index` run that left something out; see [When something goes wrong](#when-something-goes-wrong). A missing index, or one that needs upgrading, prints the `index` command to run. |
| `2` | Usage error, such as an unknown option or mode. |
| `3` | An explicit `semantic` or `hybrid` query whose semantic prerequisite is missing. The error names the reason and prints the next step: `index --semantic` for missing or outdated embeddings and an uncached model, or `--mode lexical` when vector search or the embedder cannot run here. |
| `130` | `index` was stopped with Ctrl+C; see [`index` options](#index-options). |

### When something goes wrong

A problem with one file or folder never stops `index` from indexing the others. Each problem is printed on stderr with its reason, then one line sums up what was left out, and the command exits with `1`:

```
warning: private: PERMISSION_DENIED: /home/me/project/private (not scanned)
warning: notes/draft.md: INVALID_UTF8: File is not valid UTF-8: /home/me/project/notes/draft.md
pecia index: PARTIAL: 1 file could not be read or were rejected; 1 path could not be scanned; every other file was indexed, and no file was removed from the index
```

| Problem | What happens |
| --- | --- |
| A folder or `.gitignore` cannot be read | The folder is skipped (a folder whose `.gitignore` cannot be read is skipped whole, since its rules are unknown), every other file is indexed, and nothing is deleted, because files that could not be seen are not known to be gone. |
| A file cannot be read or is rejected (too large, not UTF-8, binary) | It keeps its previous version in the index, and nothing is deleted in that run. A file that changes while it is read, as when an editor saves it, is read once more. |
| A file's chunks cannot be embedded | The file is indexed with its new chunks and no embeddings, so lexical search stays current; the next semantic run embeds them. A broken runtime or model, or three failing files in a row, stop the run instead. |
| The index cannot be written | The run stops, keeping every file it finished, and says why and what to do: free disk space (`DISK_FULL`), wait for another indexing process (`BUSY`), make the index writable (`READ_ONLY`), check the disk (`IO_ERROR`), or delete a damaged, foreign, or incompatible index and run again (`CORRUPT`, `WRONG_PROJECT`, `INCOMPATIBLE`). |

If the process is killed outright, for example by a crash or a power loss, the index stays consistent: each file is written in one transaction, and the next run indexes the rest and ends with the same index as an uninterrupted run.

### Configuration

`init` writes `.pecia.toml` with every setting and its default; flags on the command line override it.

| Key | Default | Meaning |
| --- | --- | --- |
| `index.include`, `index.exclude` | supported text formats, common build and dependency folders | Glob patterns, relative to the configuration file; `.gitignore` always applies too. |
| `index.max_file_bytes` | `5242880` (5 MiB) | Larger files are rejected. |
| `chunk.max_tokens`, `chunk.overlap_tokens` | `256`, `32` | Token budget per chunk and the overlap between neighbouring chunks. |
| `semantic.enabled` | `false` | Whether `index` builds embeddings. |
| `embed.concurrency` | `2` | Embedding workers in a semantic run. Together they use about half the processor cores, at most four threads each; more workers embed faster but hold more memory. |
| `search.mode` | `"auto"` | The default `query` mode. |
| `store.path` | `".pecia/index.db"` | Where the index is stored, relative to the configuration file. |

### Model cache

The model is cached per user, so every project shares one copy. `PECIA_CACHE_DIR` (an absolute path) overrides the default location:

| Platform | Default cache directory |
| --- | --- |
| macOS | `~/Library/Caches/pecia` |
| Linux | `$XDG_CACHE_HOME/pecia`, or `~/.cache/pecia` |
| Windows | `%LOCALAPPDATA%\pecia\cache` |

The cache holds the model, downloaded from Hugging Face and checked against its pinned size and SHA-256 before use, a verified copy of the sqlite-vec library, placed on the first semantic query, and verified copies of ONNX Runtime's native libraries (about 40 MB), placed when the model is first used, so that later runs load them in place instead of unpacking them each time. Semantic search runs on Linux (x64 and arm64), macOS on Apple silicon, and Windows (x64); lexical search runs wherever Java 21 does.

## How it works

### 📂 File discovery

Pecia selects files using `.gitignore` rules and the include/exclude patterns in `.pecia.toml`. It skips symbolic links, internal index files, and its own `.pecia.toml` configuration files, which include patterns cannot admit; `--dry-run` previews this selection and what indexing would change.

### ✂️ Extraction and chunking

Supported text files are read with size and UTF-8 validation, then split into chunks within the configured token budget, less 32 tokens kept free for the embedding context line described below (so the default 256-token budget gives chunks of up to 224 tokens). Chunks retain source paths, line ranges, and Markdown heading context where available. A tiny Markdown section, such as a heading with one short line, or a few items of a long list that had to be split, joins a neighbouring section under the same parent heading, so no chunk is too small to say anything on its own.

### 🗃️ Local indexing

File metadata and chunks are stored in SQLite, with FTS5 kept in sync for text search. Each file is replaced atomically, so a failed replacement preserves its previous data while earlier successful files remain indexed. Deleted files are removed only after a run that read every file and folder it found, so a file that could not be read is never mistaken for a deleted one.

Indexing validates and hashes the current source before deciding whether to skip it. Matching content, document type, and complete chunking settings skip extraction and writes, including for empty files. Changes to extraction or chunking versions, tokenizer behavior, or token budgets require reprocessing; model names and revisions alone do not change chunking compatibility.

Each indexed chunk has a deterministic SHA-256 ID derived from its project-relative path, zero-based position, and chunking profile. IDs are project-local and exclude content: an edit at the same position can keep its ID while the separate file hash tracks freshness. The Java core exposes this as `StoredChunk.stableId()` and `SearchHit.stableId()`. Their numeric database IDs remain local row locators. Legacy or invalidated records remain searchable with an empty stable ID until reindexing establishes a complete profile.

### 🧠 Embeddings

Semantic indexing embeds each chunk with [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) through ONNX Runtime on the CPU, producing one 384-dimensional unit vector per chunk. The embedded text starts with one context line, the chunk's path and heading path (for example `bridge/README.md > Intent`), then a blank line and the content, so a chunk is also found by what its file and section are about; search results and lexical search show only the content. Vectors are stored next to the chunk in the same SQLite file. Changed files are embedded as they are indexed, by `embed.concurrency` workers that share one model while files are still being read; files are written in the same order whatever the number of workers, and at most 128 chunks wait to be written, so memory stays bounded however large the project is; stored chunks without an embedding, for example after earlier lexical runs, are embedded from the index in pages that commit on their own, so an interrupted run resumes where it stopped. Each embedding records the model, revision, tokenizer, and input it was made with; when these change, the next semantic run replaces the old embeddings, and chunks are reused when only the model changed.

### 🔎 Search

`query` opens the existing index read-only and never rereads source files. Lexical search uses FTS5 with BM25 ranking. Semantic search embeds the query with the same model and ranks embedded chunks by cosine similarity with [sqlite-vec](https://github.com/asg017/sqlite-vec), loaded only for semantic and hybrid queries. Hybrid search reads both rankings from one consistent snapshot of the index and fuses their ranks, never their raw scores, so neither scale dominates. Results show project-relative paths, line ranges, scores, optional headings, and short text snippets; a chunk found lexically keeps the snippet around its matching words.

## Bundled components

- **Tokenizer vocabulary.** Offline token counting uses the 30,522-entry `vocab.txt` from [all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41), bundled in the JAR (about 226 KiB), pinned and SHA-256-verified. The [tokenizer asset directory](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/) includes `NOTICE.txt` with the source, revision, and checksum, and `LICENSE.txt`.
- **sqlite-vec 0.1.9.** The loadable libraries for the supported platforms are bundled unchanged from the [release](https://github.com/asg017/sqlite-vec/releases/tag/v0.1.9); their [`NOTICE.txt`](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/semantic/sqlite-vec/NOTICE.txt) pins each library's SHA-256.
- **ONNX Runtime 1.28.0**, a runtime dependency of the CLI JAR, runs the model; its native libraries ship inside its JAR.
- **The model itself is not bundled.** The first semantic indexing run downloads it into the user's cache, as described under [Model cache](#model-cache).

## License

Pecia's own code is licensed under [MIT](LICENSE). Bundled third-party components keep their own licenses: the tokenizer vocabulary under [Apache 2.0](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/LICENSE.txt), sqlite-vec under [MIT](pecia-core/src/main/resources/META-INF/licenses/sqlite-vec-LICENSE-MIT.txt) or [Apache 2.0](pecia-core/src/main/resources/META-INF/licenses/sqlite-vec-LICENSE-APACHE.txt), and ONNX Runtime under MIT, whose license and third-party notices are included in the CLI JAR. The all-MiniLM-L6-v2 model, downloaded at runtime, is licensed under Apache 2.0.
