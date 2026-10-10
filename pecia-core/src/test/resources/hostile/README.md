# Hostile-input corpus

These inputs collect the hostile content, configuration, query, filesystem, and index storage cases Pecia must handle with a typed outcome instead of a crash, a partial write, or an unclassified error. `cases.toml` lists the content, configuration, query, and storage cases with their expected outcomes, and the SHA-256 of each committed input file. `HostileInputCorpusTest` runs them and fails when a manifest entry has no input file or an input file has no manifest entry; `HostileFilesystemTest` generates the filesystem states.

## Content

Each content case indexes one file from `content/` in an otherwise empty project with the default configuration. An `indexed` case expects a `COMPLETE` result with the exact chunk count. A `rejected` case expects a `PARTIAL` result with one file issue carrying the listed `ExtractionException` reason, after a valid earlier version of the same file was indexed; the runner checks that the rejected change leaves those records and their search results unchanged. Indexed cases also check that no stored chunk keeps a byte order mark.

| Case | Input | Outcome |
| --- | --- | --- |
| `empty` | zero bytes | indexed, 0 chunks |
| `whitespace-only` | spaces, a tab, CRLF, LF and a no-break space | indexed, 0 chunks |
| `bom-only` | a UTF-8 byte order mark | indexed, 0 chunks |
| `bom-then-text` | a UTF-8 byte order mark before text | indexed, 1 chunk |
| `malformed-utf8` | a lead byte followed by a non-continuation byte | `INVALID_UTF8` |
| `truncated-utf8` | a multibyte sequence cut off at end of file | `INVALID_UTF8` |
| `overlong-utf8` | an overlong encoding of `/` | `INVALID_UTF8` |
| `encoded-surrogate` | a UTF-16 surrogate encoded in UTF-8 | `INVALID_UTF8` |
| `utf16le-bom`, `utf16be-bom` | UTF-16 text with a byte order mark | `INVALID_UTF8` |
| `utf16le-no-bom`, `utf16be-no-bom` | UTF-16 text without a byte order mark | `BINARY_CONTENT` |
| `nul-byte` | text containing a NUL byte | `BINARY_CONTENT` |
| `control-characters` | text containing a run of C0 control characters | `BINARY_CONTENT` |
| `mixed-line-endings` | CRLF, LF and CR in one file, no final newline | indexed, 1 chunk |
| `long-line` | one 10,000-byte line, far above the token budget | indexed, 9 chunks |

UTF-16 with a byte order mark fails UTF-8 decoding, while UTF-16 without one is rejected earlier because its zero bytes look binary. Pecia supports only UTF-8 text.

### Generated at test time

The size limit is exercised with generated files instead of committed ones, so the limit can stay small and the boundary is explicit: with `index.max_file_bytes = 64`, a 64-byte file is indexed and a 65-byte file is rejected with `TOO_LARGE`.

### Related tests

`TextDocumentExtractorTest` covers BOM removal, empty input, malformed UTF-8, UTF-16 and binary detection at the extractor level; `FileContentLoaderTest` covers the size limit, symbolic links and unreadable files at the loader level. This corpus checks the same inputs end to end through `IndexService`.

## Configuration

Each configuration case uses one file from `config/` as `.pecia.toml` in a project with one text file. An `accepted` case expects the recorded indexing status and exactly the listed warnings, with their code, line, column, and message, so a valid file must produce none. A `rejected` case expects the recorded exception and message fragment at its stage: `load` cases fail while the configuration loads, and `indexing` cases load but fail when indexing validates settings against the tokenizer or resolves the storage path. Either way indexing must fail before creating storage.

| Case | Input | Outcome |
| --- | --- | --- |
| `malformed-toml`, `duplicate-key` | invalid TOML | rejected on load |
| `invalid-utf8` | malformed UTF-8 bytes | rejected on load |
| `byte-order-mark` | a UTF-8 byte order mark before the first table | accepted |
| `section-as-value` | `chunk = 5` instead of a table | rejected on load |
| `string-for-integer`, `float-for-integer`, `string-for-list`, `non-string-list-item`, `integer-for-string` | values of the wrong type | rejected on load |
| `misspelled-key`, `unknown-section` | a misspelled key and a table Pecia does not define | accepted with a warning; the misspelled key gets a suggestion |
| `root-level-key` | `max_tokens` at the root | accepted with a warning suggesting `chunk.max_tokens` |
| `misspelled-table` | `[chunks]` with two keys | accepted with one warning per key, each suggesting its `chunk` key |
| `empty-unknown-table` | `[chunks]` without keys | accepted with a warning about the table, suggesting `chunk` |
| `nested-unknown-table`, `inline-table-unknown-key` | unknown keys in a nested table and in an inline table | accepted with warnings naming the full key path and its own column |
| `dotted-key-typo` | `max.tokens`, which TOML reads as a nested table | accepted with a warning suggesting `chunk.max_tokens` |
| `unrelated-key` | `embed.threads` | accepted with a warning and no suggestion |
| `several-warnings` | four unknown keys across tables | accepted with four warnings in source order |
| `negative-max-tokens`, `negative-overlap` | negative values | rejected on load |
| `integer-overflow`, `integer-underflow` | integers outside the 32-bit range | rejected on load |
| `max-tokens-256` / `max-tokens-257` | the largest token budget and one above it | accepted / rejected on indexing |
| `max-tokens-3` / `max-tokens-2` | the smallest token budget and one below it | accepted / rejected on indexing |
| `overlap-below-budget` / `overlap-at-budget` | overlap one below and equal to the 32-token content budget | accepted / rejected on indexing |
| `overlap-at-max-tokens` | overlap equal to `max_tokens` | rejected on load |
| `max-file-bytes-1` / `max-file-bytes-0` | the smallest file limit and zero | accepted (`PARTIAL`, the file is too large) / rejected on load |
| `concurrency-1` / `concurrency-0` | the smallest embedding concurrency and zero | accepted / rejected on load |
| `blank-store-path`, `nul-in-store-path` | a blank storage path and one containing NUL | rejected on load |
| `store-path-is-directory` | `store.path = "."` | rejected on indexing with an `IOException` |

Unknown keys never change which settings apply: their values are ignored and reported as `UNKNOWN_CONFIG_KEY` warnings, with a suggestion when a known key is close. Token-budget limits depend on the tokenizer, so they are checked when indexing starts rather than when the configuration loads.

`PeciaConfigParserTest`, `PeciaConfigLoaderTest`, `PeciaConfigTest`, and `UnknownConfigKeysTest` cover the same rules at the unit level.

## Queries

Each query case searches a project whose only file contains `Payment validation runs near the quoted text, or not.` Queries are given literally in the manifest, or generated from `repeat`, `times`, and `separator` when they are too long to read. An `accepted` case expects the exact hit count, no exception, and an unchanged index. A `rejected` case runs against a project without an index and expects an `IllegalArgumentException` with the recorded message, which shows that validation happens before the index is opened.

Pecia searches query text as literal words that must all match, so FTS5 syntax never acts as syntax:

| Cases | Input | Outcome |
| --- | --- | --- |
| `operator-and`, `operator-or`, `operator-not` | `AND`, `OR`, `NOT` | literal words: 0, 0, and 1 hits, depending on whether the project text contains them |
| `near-function` | `NEAR(payment validation)` | literal text, 0 hits |
| `quoted-phrase`, `unbalanced-quote` | balanced and unbalanced quotes | punctuation, 1 hit each |
| `prefix-star` | `pay*` | no prefix match, 0 hits |
| `column-filter`, `column-filter-source-path`, `column-set-filter` | FTS column filters | literal text, 0 hits |
| `initial-token-caret`, `unbalanced-parenthesis` | `^`, an unbalanced `(` | punctuation, 1 hit each |
| `open-parenthesis`, `punctuation-only` | punctuation only | 0 hits |
| `sql-injection` | `payment'; DROP TABLE files; --` | bound as a parameter, 0 hits, index unchanged |
| `mixed-case`, `no-break-space`, `hyphenated` | case and separator variations | 1 hit each |
| `empty`, `whitespace-only`, `nul` | blank text or NUL | rejected |
| `max-code-points` / `over-code-points` | 4096 and 4097 code points | accepted / rejected |
| `max-supplementary-code-points` / `over-supplementary-code-points` | 4096 and 4097 emoji | accepted / rejected; the limit counts code points, not UTF-16 units |
| `max-parts` / `over-parts` | 64 and 65 parts | accepted / rejected |
| `over-punctuation-parts` | 65 punctuation-only parts | rejected; punctuation cannot bypass the part limit |
| `limit-1`, `limit-100` / `limit-0`, `limit-101`, `limit-negative` | result limits | accepted / rejected |

`SearchRequestTest`, `LexicalQueryCompilerTest`, and `QueryServiceTest` cover the same rules at the unit level.

## Filesystem states

`HostileFilesystemTest` generates these states at test time, because Git cannot carry them portably and some depend on the host. Each runs through `IndexService` in a fresh project.

| Case | Outcome | Skipped when |
| --- | --- | --- |
| Portable unusual names: `..foo.txt`, a leading space, brackets, braces, `#`, `%`, `;`, accented and Turkish letters, an emoji | indexed; the stored path and search hit keep the exact name | the file system cannot create the name |
| Names Windows cannot create: a trailing space, `CON.txt`, `NUL.txt`, `:`, `\`, `*`, `?`, a line break | indexed; the stored path and search hit keep the exact name | on Windows, or the file system cannot create the name |
| An extensionless file named `NUL` | not a candidate | on Windows |
| A directory link to the project root, a self-referencing link, and a link to a file outside the project | not followed; only the real file is indexed | symbolic links cannot be created |
| An unreadable file | `PARTIAL` with `READ_FAILED`; other files are indexed | POSIX permissions are unavailable, or the account bypasses them |
| An unreadable directory | `INCOMPLETE_SCAN` naming the directory; storage is not created | POSIX permissions are unavailable, or the account bypasses them |
| A file deleted after discovery | `PARTIAL` with `READ_FAILED`; its records are retained and cleanup is skipped | never |
| A file replaced by a directory after discovery | `PARTIAL` with `NOT_REGULAR_FILE` | never |
| A file changed after discovery | indexed with its new content | never |

The after-discovery cases change the file when indexing opens storage, after discovery and before any file is read. The scan issue for an unreadable directory currently names only the path; that case changes when scan issues carry a typed cause. A file that changes while it is being read is rejected with `FILE_CHANGED`; that race cannot be timed reliably end to end, so `FileContentLoaderTest` covers its snapshot comparison directly. `FileWalkerEdgeTest`, `FileIndexerTest`, and `FileIndexerIncrementalTest` cover the same states at the unit level.

## Index storage

Each storage case places a database at the project's default index path, queries it, then indexes it. Fixture cases derive the copy from the current-version legacy fixture through `LegacyIndexFixture.installDamaged`; the other sources are generated by the test. A query must never change the database, and a rejected database must keep its exact bytes, with no journal or write-ahead-log files beside it.

| Case | Query | Indexing |
| --- | --- | --- |
| `truncated` | `CORRUPT_INDEX` | `FAILED`, `CORRUPT_INDEX` |
| `foreign-project` | `WRONG_PROJECT` | `FAILED`, `WRONG_PROJECT` |
| `future-schema`, `format-mismatch` | `INCOMPATIBLE_INDEX` | `FAILED`, `INCOMPATIBLE` |
| `text-file`, `header-only` (not a SQLite database) | `CORRUPT_INDEX` | `FAILED`, `CORRUPT_INDEX` |
| `empty-file`, `empty-database` (nothing created yet) | `INDEX_NOT_FOUND` | initializes the index: `COMPLETE` |
| `foreign-database` (another application's unversioned database) | `INCOMPATIBLE_INDEX` | `FAILED`, `INCOMPATIBLE` |
| `directory` | `READ_FAILED` | rejected with an `IOException` before storage opens |

A separate case holds an exclusive lock from another connection while indexing and querying run in parallel. After the 5-second SQLite busy timeout, indexing fails with `FAILED` and querying with `READ_FAILED`, both caused by `SQLITE_BUSY`; the index is unchanged, and indexing succeeds once the lock is released. It uses a real lock and the production timeout, so it adds about five seconds.

`DamagedIndexRejectionTest`, `SqliteReadOnlyStorageTest`, and `SqliteWritableValidationTest` cover the same classifications at the storage level.

## Maintenance

The repository's `.gitattributes` marks this directory `-text`, and core test resources disable Maven filtering, so inputs keep their exact bytes on every checkout; the checksums detect accidental conversion. Whitespace attributes allow the deliberate whitespace and CR terminators in content inputs. Expected outcomes record reviewed behavior: change one only together with the behavior it describes, never just to make a failing test pass. Add a case by committing its input and a `cases.toml` entry with the input's checksum. Committed input names must be valid on Windows: no reserved device names such as `NUL` or `CON`, even with an extension, and no `<>:"|?*` characters; generate such names at test time instead.
