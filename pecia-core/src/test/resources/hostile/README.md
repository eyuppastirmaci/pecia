# Hostile-input corpus

These inputs collect the hostile content, configuration, and query cases Pecia must handle with a typed outcome instead of a crash, a partial write, or an unclassified error. `cases.toml` lists every case with its input, the SHA-256 of the input's raw bytes, and the expected outcome. `HostileInputCorpusTest` runs each case and fails when a manifest entry has no input file or an input file has no manifest entry.

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
| `nul` | text containing a NUL byte | `BINARY_CONTENT` |
| `control-characters` | text containing a run of C0 control characters | `BINARY_CONTENT` |
| `mixed-line-endings` | CRLF, LF and CR in one file, no final newline | indexed, 1 chunk |
| `long-line` | one 10,000-byte line, far above the token budget | indexed, 9 chunks |

UTF-16 with a byte order mark fails UTF-8 decoding, while UTF-16 without one is rejected earlier because its zero bytes look binary. Pecia supports only UTF-8 text.

### Generated at test time

The size limit is exercised with generated files instead of committed ones, so the limit can stay small and the boundary is explicit: with `index.max_file_bytes = 64`, a 64-byte file is indexed and a 65-byte file is rejected with `TOO_LARGE`.

### Related tests

`TextDocumentExtractorTest` covers BOM removal, empty input, malformed UTF-8, UTF-16 and binary detection at the extractor level; `FileContentLoaderTest` covers the size limit, symbolic links and unreadable files at the loader level. This corpus checks the same inputs end to end through `IndexService`.

## Configuration

Each configuration case uses one file from `config/` as `.pecia.toml` in a project with one text file. An `accepted` case expects the recorded indexing status. A `rejected` case expects the recorded exception and message fragment at its stage: `load` cases fail while the configuration loads, and `indexing` cases load but fail when indexing validates settings against the tokenizer or resolves the storage path. Either way indexing must fail before creating storage.

| Case | Input | Outcome |
| --- | --- | --- |
| `malformed-toml`, `duplicate-key` | invalid TOML | rejected on load |
| `invalid-utf8` | malformed UTF-8 bytes | rejected on load |
| `byte-order-mark` | a UTF-8 byte order mark before the first table | accepted |
| `section-as-value` | `chunk = 5` instead of a table | rejected on load |
| `string-for-integer`, `float-for-integer`, `string-for-list`, `non-string-list-item`, `integer-for-string` | values of the wrong type | rejected on load |
| `misspelled-key`, `unknown-section` | keys and tables Pecia does not define | accepted; ignored for now |
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

Unknown keys and tables are ignored today, so a typo silently falls back to the default. Their cases carry a `note` in the manifest and change when Pecia reports unknown keys. Token-budget limits depend on the tokenizer, so they are checked when indexing starts rather than when the configuration loads.

`PeciaConfigParserTest`, `PeciaConfigLoaderTest`, and `PeciaConfigTest` cover the same rules at the unit level.

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

## Maintenance

The repository's `.gitattributes` marks this directory `-text`, and core test resources disable Maven filtering, so inputs keep their exact bytes on every checkout; the checksums detect accidental conversion. Whitespace attributes allow the deliberate whitespace and CR terminators in content inputs. Expected outcomes record reviewed behavior: change one only together with the behavior it describes, never just to make a failing test pass. Add a case by committing its input and a `cases.toml` entry with the input's checksum.
