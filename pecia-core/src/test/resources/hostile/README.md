# Hostile-input corpus

These inputs collect the hostile cases Pecia must handle with a typed outcome instead of a crash, a partial write, or an unclassified error. `cases.toml` lists every case with its input, the SHA-256 of the input's raw bytes, and the expected outcome. `HostileInputCorpusTest` runs each case and fails when a manifest entry has no input file or an input file has no manifest entry.

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

## Maintenance

The repository's `.gitattributes` marks this directory `-text`, and core test resources disable Maven filtering, so inputs keep their exact bytes on every checkout; the checksums detect accidental conversion. Whitespace attributes allow the deliberate whitespace and CR terminators in content inputs. Expected outcomes record reviewed behavior: change one only together with the behavior it describes, never just to make a failing test pass. Add a case by committing its input and a `cases.toml` entry with the input's checksum.
