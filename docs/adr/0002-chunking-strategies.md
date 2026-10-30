# 0002. Token-budget chunking with one strategy per document family

**Status:** accepted for v0.1.0.

## Context

Search results point to chunks, not whole files, and the embedding model reads at most 256 tokens. Chunks must be small enough to embed whole, large enough to mean something on their own, and cut where a reader would cut: at headings, paragraphs, or code blocks. Supported files range from Markdown to source code in dozens of languages.

## Decision

- Budgets are counted in the model's own tokens, with its bundled WordPiece vocabulary, so a chunk never exceeds what the model reads. The default budget is 256 tokens including special tokens, with up to 32 tokens of overlap between neighbouring chunks; 32 more are kept free for the embedding context line.
- **Markdown** is split along headings and blocks; each chunk keeps its heading path. A tiny section, or a few items of a long split list, joins a neighbour under the same parent heading.
- **Source code** is split at line and indentation boundaries, without language-specific parsers.
- **Plain and structured text** (`.txt`, `.json`, `.yaml`, and similar) are split at paragraphs, then sentences, then words.
- Extraction and chunking carry version strings. Together with the tokenizer and budgets they form a chunking profile; a file whose profile changed is chunked again on the next run.

## Consequences

- Every chunk can be embedded without truncation, and lexical and semantic search share the same units.
- Chunk boundaries are deterministic for the same input and profile, which stable identities (0003) and reproducible indexes rely on.
- Without parsers, a chunk may cut through a long function; line ranges always show where it came from.
- A change to the chunking code requires a version bump, or old and new chunks would be mixed silently.

## Alternatives considered

- **Fixed-size character windows:** simple, but cut through words, headings, and code, and do not match the model's limit.
- **Language parsers such as tree-sitter:** better code boundaries, but native libraries per language and platform; left for later.
