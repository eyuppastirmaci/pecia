# CLI reference

Every `pecia` command, option, and output format. The [README](../README.md#usage) has the quick start and the command list.

## `index` options

| Option | Effect |
| --- | --- |
| `--dry-run` | Shows what the run would do without changing the project: it reads the files to compare them with the index, but never writes the index, downloads the model, or loads it. An index of an older format is compared after upgrading a temporary copy, which is then deleted. See [Dry runs](#dry-runs). |
| `--semantic` | Builds embeddings in this run, overriding `semantic.enabled` in `.pecia.toml`. The first semantic run downloads the model (about 90 MB) and says so on stderr. |
| `--no-semantic` | Builds only the lexical index in this run. Embeddings that are already stored stay, except those of changed or deleted files. |
| `--progress=<mode>` | How a run shows its progress on stderr: `auto` (default), `plain`, or `none`; see [Progress](#progress). |
| `--json` | Prints the run's or dry run's outcome as one JSON document instead of the summary; see [JSON output](#json-output). |

Without either flag, `semantic.enabled` in `.pecia.toml` decides; it is `false` by default.

### Run summary

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

### Progress

A run shows how far it has come on stderr, phase by phase: scanning, downloading or loading the model, indexing, removing deleted files, and embedding stored chunks.

| `--progress` | What it shows |
| --- | --- |
| `auto` (default) | One line that updates in place on an interactive terminal, such as `indexing: 1,240 of 18,268 files, 9,812 chunks, 197 chunks/s, about 1 min left`; nothing when output goes to a pipe or a file. |
| `plain` | A line every 5 seconds and when a phase ends, with the time since the run started, such as `[0:12] indexing: 1,240 of 18,268 files`; meant for logs and CI. |
| `none` | Nothing. |

The progress line is cleared before warnings, the summary, or JSON, so it never mixes with them. Dry runs show no progress.

Ctrl+C stops a run before its next file or embedding batch, prints the summary of what it finished, and exits with `130`. Files indexed until then stay in the index, a file in progress keeps its previous version, and no deleted files are removed; the next run continues from there.

### Dry runs

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

## `query` options

| Option | Default | Effect |
| --- | --- | --- |
| `--mode <mode>` | `search.mode` in `.pecia.toml`, otherwise `auto` | Chooses the search mode for this query; see the next table. |
| `--limit <n>` | `10` | Maximum number of results, from 1 to 100. |
| `--root <dir>` | `.` | Any directory inside the project; the whole project's index is searched. |
| `--json` | off | Prints the results as one JSON document; see [JSON output](#json-output). |

## Search modes

| Mode | Ranks chunks by | Needs | Good for |
| --- | --- | --- | --- |
| `lexical` | BM25 over the words of the query; every word must match. | A lexical index. | Exact identifiers, error codes, file names, and phrases. |
| `semantic` | Cosine similarity between the embedded query and each chunk's embedding. | Embeddings of the current model, the cached model, and a supported platform. | Questions and paraphrases that share few words with the answer. |
| `hybrid` | Reciprocal rank fusion of the lexical and semantic rankings: `1/(60 + lexical rank) + 1/(60 + semantic rank)`, over each side's first 100 candidates. Identifier-like text (up to three words, one of them looking like code: `maxAttempts`, `BUFFER_THRESHOLD`, `com.example.retry`, `src/App.java`) keeps the lexical order, and chunks only semantic search found follow. | The same as `semantic`. | Mixed queries; chunks without an embedding can still come from the lexical side. |
| `auto` (default) | `hybrid` when the index has embeddings and everything semantic search needs is available, otherwise `lexical`. | Nothing. | Everyday use: a lexical-only project stays exactly as fast as plain lexical search. |

Queries never download the model. Lexical queries accept at most 64 words; a hybrid query with more words ranks by the semantic side alone, and only the first 254 tokens of a semantic or hybrid query are embedded. In both cases the query still succeeds and a note on stderr says so.

## Output

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

## Colour

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

## JSON output

With `--json`, `index`, `index --dry-run`, and `query` print one JSON document on stdout, also when they fail.

- Stderr and exit codes stay the same as without `--json`.
- Usage errors, such as an unknown option, stay plain text on stderr with exit code `2` and nothing on stdout.

<details>
<summary>Example: a lexical query</summary>

```
$ pecia query "refund card" --mode lexical --json
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

## Exit codes

| Code | Meaning |
| --- | --- |
| `0` | Success, including a query with no results. |
| `1` | Invalid input or configuration, a missing, outdated, or unreadable index, an indexing failure (for example, the model could not be downloaded), or an `index` run that left something out; see [When something goes wrong](#when-something-goes-wrong). A missing index, or one that needs upgrading, prints the `index` command to run. |
| `2` | Usage error, such as an unknown option or mode. |
| `3` | An explicit `semantic` or `hybrid` query whose semantic prerequisite is missing. The error names the reason and prints the next step: `index --semantic` for missing or outdated embeddings and an uncached model, or `--mode lexical` when vector search or the embedder cannot run here. |
| `130` | `index` was stopped with Ctrl+C; see [`index` options](#index-options). |

## When something goes wrong

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

## Configuration

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

## Model cache

The model is cached per user, so every project shares one copy. `PECIA_CACHE_DIR` (an absolute path) overrides the default location:

| Platform | Default cache directory |
| --- | --- |
| macOS | `~/Library/Caches/pecia` |
| Linux | `$XDG_CACHE_HOME/pecia`, or `~/.cache/pecia` |
| Windows | `%LOCALAPPDATA%\pecia\cache` |

The cache holds the model, downloaded from Hugging Face and checked against its pinned size and SHA-256 before use, a verified copy of the sqlite-vec library, placed on the first semantic query, and verified copies of ONNX Runtime's native libraries (about 40 MB), placed when the model is first used, so that later runs load them in place instead of unpacking them each time. Semantic search runs on Linux (x64 and arm64), macOS on Apple silicon, and Windows (x64); lexical search runs wherever Java 21 does.
