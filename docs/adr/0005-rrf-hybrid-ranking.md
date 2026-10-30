# 0005. Hybrid ranking with reciprocal rank fusion, weighted by query shape

**Status:** accepted for v0.1.0.

## Context

Lexical search finds exact words and identifiers; semantic search finds paraphrases. Hybrid search should get both. Their scores cannot be added: BM25 scores are unbounded and lower is better in SQLite, while cosine similarity lies between −1 and 1 and higher is better, and both scales shift from query to query.

## Decision

- Hybrid search reads both rankings from one consistent snapshot of the index and fuses only their ranks, with reciprocal rank fusion: a chunk scores `wl / (60 + lexicalRank) + ws / (60 + semanticRank)`, where a side that did not rank it adds nothing. The constant 60 comes from the original RRF paper.
- Each side contributes its top 100 candidates whatever the requested limit, so a smaller limit always returns a prefix of a larger one.
- The weights depend on the query's shape. Natural-language text weighs both sides equally. Text of at most three words, one of which looks like code (`camelCase`, `snake_case`, `HTTP2`, `a.b`, `a/b`, `a::b`), is identifier-like: its semantic weight is so small that lexical order decides, and semantic ranks only order chunks that lexical search did not find.
- Ties break by source path, chunk position, then chunk ID, so results are deterministic.
- A fused hit keeps the lexical snippet and match ranges when lexical search found it.

## Consequences

- No score normalisation or per-corpus tuning is needed, and a missing side, such as the lexical side of a query with too many words, leaves the formula unchanged.
- JSON output and the terminal show each hit's two ranks, so a ranking can be explained.
- The fused score says nothing absolute about relevance; there is no "no confident results" cutoff yet.
- The shape rules are heuristics; the retrieval-quality tests measure them on a labelled query set.

## Alternatives considered

- **Normalised score blending:** needs per-query normalisation that breaks when one side returns few results.
- **A cross-encoder reranker:** more accurate, but a second model and much slower queries; left for later versions.
