# 0001. One SQLite file per project, with FTS5 and BM25 for lexical search

**Status:** accepted for v0.1.0.

## Context

Pecia runs offline on a developer's machine, without a server or a background process. Its index must survive crashes, be easy to delete, and stay readable by later versions. Lexical search must handle code identifiers and natural-language text in many languages, and semantic search later needs vectors stored beside the same chunks.

## Decision

- Each project has one SQLite database, `.pecia/index.db` by default, holding files, chunks, headings, profiles, and embeddings.
- An FTS5 table indexes each chunk's content, heading path, and source path with the `unicode61` tokenizer and `remove_diacritics 2`, so case and accents do not matter.
- Triggers keep the FTS5 table in step with the chunk tables inside the same transactions; the chunk tables are the source of truth and FTS5 is a derived copy.
- Queries are ranked with FTS5's `bm25()` with equal column weights. Each whitespace-separated part of the query becomes a quoted phrase, and all phrases must match, so user text never reaches FTS5 syntax.
- Versioned migrations upgrade older indexes in place; an index that a newer Pecia wrote, or one of another project, is refused.

## Consequences

- Lexical search needs no model, no network, and no native code beyond SQLite's.
- One file is simple to back up, delete, or inspect with `sqlite3`.
- One writer at a time: a second indexing process stops with `BUSY` instead of waiting for the first to finish.
- `unicode61` splits words at punctuation and does not stem, so `refunds` does not match `refund`; semantic search covers such paraphrases.
- Column weights for headings and paths, and splitting identifiers such as `PaymentService` into words, are left for later versions.

## Alternatives considered

- **Lucene or Tantivy:** stronger analyzers, but a separate index format, a larger dependency, and a second store beside the vectors.
- **A search server such as Elasticsearch:** contradicts the offline, single-binary goal.
