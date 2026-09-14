# Golden chunker corpus

These inputs represent extracted UTF-8 document text, not an extraction pipeline fixture. Each `.input` file has a fixed `.expected.toml` partner. TOML uses the project's existing parser dependency. `corpus.toml` lists the scenarios and pins the complete tokenizer identity; each expectation fixes the source path, document type, token/overlap settings, and input SHA-256.

Every expected chunk specifies its complete content, index, source path/type, inclusive one-based line range, heading path, and all metadata attributes. Offsets are zero-based UTF-16 positions; `endOffset` is exclusive. Escaped `\r\n` and `\n` in TOML preserve exact content without relying on the expectation file's own line endings.

| Group | Cases | Reviewed behavior |
| --- | ---: | --- |
| Text | 7 | Empty/whitespace produce no chunks; Turkish, emoji, and combining marks remain unchanged; LF/CRLF inputs split after two fitting lines; an oversized paragraph uses four content tokens with two-token overlap; paragraph boundaries precede later word boundaries. |
| Source | 8 | Empty/whitespace and Unicode preservation; LF/CRLF inputs split after three fitting lines; an oversized line uses word overlap; full-line overlap reuses the third line; dedent preference ends the first chunk before `end`. |
| Markdown | 8 | Empty/whitespace and Unicode headings; nested headings split into Root and Root/Child contexts under LF/CRLF; oversized paragraph overlap stays within its leaf; fitting fences remain intact; oversized fences split without inventing or losing delimiters. |

## Review and maintenance

The expected slices and line ranges were authored from the boundary rules and literal inputs, independently of chunker output. Fixture preparation checks established that every expected content string matches its declared source slice. The first golden test run matched all 23 authored scenarios without changing their expected outputs to match actual results.

The short English number words each occupy one content token in the pinned tokenizer. A six-token model budget leaves four content tokens after the two special tokens, giving offsets 0–19, 8–28, and 19–39 for the shared oversized-overlap input. Source line-overlap uses a five-token model budget and reuses the `three` line. Line terminators belong to the line they terminate; an absent final newline must not create a phantom line. Emoji count as two UTF-16 code units, while the combining accent in `é` remains a separate code unit. Markdown heading context stays in metadata rather than being prepended to content.

Tests read expectations without writing or refreshing them. To change the corpus, review the input, tokenizer/settings, boundary decisions, complete chunk records, and checksum together. Never replace expectations automatically just because a test failed. Register new cases in `corpus.toml` and preserve paired files.

The repository's `.gitattributes` marks this directory `-text`, and core test resources disable Maven filtering. Input-only whitespace attributes allow deliberate blank lines, space/tab combinations, and CRLF in Git whitespace checks while retaining readable diffs. Preserve the raw input bytes: empty files are zero bytes; CRLF cases use actual CRLF; several cases deliberately omit a final newline. Input checksums detect accidental conversion after checkout or resource copying. Existing invariant, randomized, and packaged-JAR tests remain complementary to these golden tests.
