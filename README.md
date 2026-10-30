# Pecia

Pecia is a local command-line tool that turns a folder of documents and source code into a searchable index. It walks a directory, splits text into coherent chunks, and stores them in a single SQLite file next to your project for offline BM25 search. With semantic indexing enabled, it also embeds each chunk with a small model running in the same process, so queries can match by meaning as well as by words.

## Status

**v0.1.0**, the first release. Pecia searches UTF-8 text documents and source code; later versions are meant to add more formats.

- Project initialization and TOML configuration.
- File discovery with `.gitignore`, include/exclude filters, and `--dry-run`.
- Validated UTF-8 text extraction and SHA-256 content hashing.
- Token-budget chunking for text, Markdown, and source code, using a bundled offline tokenizer.
- SQLite storage with atomic file replacement and synchronized FTS5 indexing.
- Incremental indexing: skip unchanged files and remove deleted files.
- Stable chunk identities and versioned extraction, tokenizer, and chunking compatibility.
- Offline BM25 search with paths, line ranges, scores, optional headings, and snippets.
- Optional semantic indexing with all-MiniLM-L6-v2, downloaded once on first use, verified, and cached.
- Vector storage, resumable embedding backfill, and rebuilds when the embedding model changes.
- Semantic search with sqlite-vec, hybrid BM25 + vector ranking with reciprocal rank fusion, and an `auto` mode.
- Parallel embedding with bounded memory, and indexing runs that stop cleanly on Ctrl+C.
- Failure isolation: unreadable folders and files that cannot be read or embedded are reported while everything else is indexed, storage failures say what to do, and an interrupted or killed run recovers on the next run.
- Reusable Java core API and runnable JAR with `init`, `index`, and `query` commands.
- JSON output for indexing, queries, and dry runs, with match ranges for highlighting.
- Detailed dry runs, live progress, grouped run summaries, and coloured, readable terminal output.
- End-to-end checks of the packaged CLI with the real model, and retrieval-quality metrics gated in CI.
- Packages for macOS, Linux, and Windows with a bundled Java runtime, installed through Homebrew or Scoop.

## Install

Packages bundle their own Java runtime, so no Java installation is needed.

| System | Install |
| --- | --- |
| macOS (Apple silicon), Linux (x64, arm64) | `brew install eyuppastirmaci/pecia/pecia` |
| Windows (x64) | `scoop bucket add pecia https://github.com/eyuppastirmaci/scoop-pecia`, then `scoop install pecia` |

[Installing Pecia](docs/install.md) covers archives from [Releases](https://github.com/eyuppastirmaci/pecia/releases), upgrades, security prompts, and running the JAR with Java 21 or newer. Intel Macs have no package, because ONNX Runtime does not support them.

## Usage

### Quick start

```
pecia --help                             # list commands
pecia --version                          # print version
pecia init                               # write a default .pecia.toml into the current directory
pecia index . --dry-run                  # show what indexing would change, writing nothing
pecia index .                            # build the local lexical index
pecia index . --semantic                 # also build embeddings (downloads the model once)
pecia index . --progress=plain           # progress as plain lines, for logs
pecia query "authentication middleware"  # search in the default auto mode
pecia query "JWT_SECRET" --mode lexical --root /path/to/project --limit 5
pecia query "how are refunds paid?" --mode semantic
pecia query "refund policy" --json       # one JSON document for scripts
```

Run `index` before `query`. From a source build, use `java -jar pecia-cli/build/libs/pecia.jar` in place of `pecia`. Every command accepts `--help` and `--color=auto|always|never`; see [Colour](docs/cli.md#colour).

### Commands

| Command | What it does | Main options |
| --- | --- | --- |
| `init` | Writes a default `.pecia.toml` into the current directory, unless one is already there. | — |
| `index [folder]` | Indexes the files under the folder (default: current directory) into the index of the project that contains it, found from the nearest `.pecia.toml` or Git root: it adds new and changed files, removes deleted ones, and skips unchanged ones. With semantic indexing on, it also embeds new chunks and stored chunks that have no embedding yet. | `--dry-run`, `--semantic` / `--no-semantic`, `--progress`, `--json` |
| `query <text>` | Searches the existing index read-only, without rereading source files, and prints the best-matching chunks. Quote text with several words. | `--mode`, `--limit`, `--root`, `--json` |

The [CLI reference](docs/cli.md) covers every option, the output formats, exit codes, failures, configuration, and the model cache. To use Pecia from Java instead, see [Using pecia-core from Java](docs/library.md).

## Supported files

Pecia indexes UTF-8 text files of up to 5 MiB (`index.max_file_bytes`):

| Kind | Files |
| --- | --- |
| Markdown | `.md`, `.markdown` |
| Plain text | `.txt`, `.rst`, `.adoc`, and files named `README`, `LICENSE`, `LICENCE`, `NOTICE`, `CHANGELOG`, `AUTHORS`, or `CONTRIBUTING` |
| Source code | `.java`, `.kt`, `.kts`, `.py`, `.pyi`, `.js`, `.jsx`, `.mjs`, `.cjs`, `.ts`, `.tsx`, `.mts`, `.cts`, `.c`, `.h`, `.cc`, `.cpp`, `.cxx`, `.hh`, `.hpp`, `.hxx`, `.cs`, `.go`, `.rs`, `.rb`, `.php`, `.swift`, `.sh`, `.bash`, `.zsh`, `.ps1`, `.psm1`, `.bat`, `.cmd`, `.sql`, `.css`, `.scss`, `.sass`, `.less`, and files named `Dockerfile`, `Containerfile`, `Makefile`, or `Jenkinsfile` |
| Structured text | `.json`, `.jsonc`, `.yaml`, `.yml`, `.toml`, `.ini`, `.cfg`, `.properties`, `.xml` |

By default it skips build and dependency folders (such as `build` and `node_modules`), minified files, lock files, and `.env` files; `.pecia.toml` lists these rules and can change them (see [Configuration](docs/cli.md#configuration)). Files that are not valid UTF-8 or hold binary data are reported and left out.

## Privacy and offline use

Pecia runs entirely on your computer. It sends no telemetry and never uploads your files, your index, or your queries.

Its only network access is the first semantic indexing run, which downloads the model from Hugging Face and checks its pinned size and SHA-256. Lexical indexing and every query work offline. Behind a proxy, set `https_proxy`; see [Model cache](docs/cli.md#model-cache).

The index, `.pecia/index.db` in your project, holds copies of the indexed text, so keep it as private as the files themselves. Deleting `.pecia/` removes it.

## Disk usage

| What | Where | Size |
| --- | --- | --- |
| Package | where Homebrew, Scoop, or you installed it | about 57 MB |
| Index | `.pecia/index.db` in each project | grows with the indexed text: Pecia's own 577 source and documentation files (7,119 chunks) take 15 MB, or 29 MB with embeddings |
| Model | the [model cache](docs/cli.md#model-cache), shared by all projects | about 86 MB, after the first semantic run |
| ONNX Runtime and sqlite-vec | the model cache | about 38 MB, after the first semantic run |

## Known limitations

- Only the UTF-8 text formats above are indexed; PDF, Office documents, images, audio, and video are not.
- The semantic model was trained mainly on English text, so it matches other languages, Turkish included, less well by meaning. Lexical search works in any language.
- A lexical query may have at most 64 space-separated words; a longer one is refused, and a longer hybrid query is ranked by its semantic side alone. A semantic query embeds only its first 256 tokens. Pecia notes when a query is cut or a side is skipped.
- The index changes only when `index` runs; Pecia does not watch files.
- Intel Macs have no package, and semantic search runs only where ONNX Runtime does: Linux x64 and arm64, macOS on Apple silicon, and Windows x64.
- Packages are not signed; see [security prompts](docs/install.md#macos-security-prompts). On Windows, `java -jar` loses query characters outside the system code page, and command hints use PowerShell quoting; see [Installing Pecia](docs/install.md).

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
- **The model itself is not bundled.** The first semantic indexing run downloads it into the user's cache, as described under [Model cache](docs/cli.md#model-cache).

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) explains how to build Pecia, run its tests and checks, and publish a release. The [architecture decision records](docs/adr/README.md) explain the core design choices.

## License

Pecia's own code is licensed under [MIT](LICENSE). Bundled third-party components keep their own licenses: the tokenizer vocabulary under [Apache 2.0](pecia-core/src/main/resources/dev/eyuppastirmaci/pecia/tokenization/all-MiniLM-L6-v2/LICENSE.txt), sqlite-vec under [MIT](pecia-core/src/main/resources/META-INF/licenses/sqlite-vec-LICENSE-MIT.txt) or [Apache 2.0](pecia-core/src/main/resources/META-INF/licenses/sqlite-vec-LICENSE-APACHE.txt), and ONNX Runtime under MIT, whose license and third-party notices are included in the CLI JAR. The all-MiniLM-L6-v2 model, downloaded at runtime, is licensed under Apache 2.0.
