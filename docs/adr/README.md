# Architecture decision records

Short records of the decisions that shape Pecia's core, each with its context, the decision, and its consequences.

| Record | Decision |
| --- | --- |
| [0001](0001-sqlite-fts5-bm25.md) | One SQLite file per project, with FTS5 and BM25 for lexical search |
| [0002](0002-chunking-strategies.md) | Token-budget chunking with one strategy per document family |
| [0003](0003-stable-chunk-identities.md) | Chunk identities from path, position, and chunking profile |
| [0004](0004-transactional-failure-atomicity.md) | One transaction per file, and deletions only after complete runs |
| [0005](0005-rrf-hybrid-ranking.md) | Hybrid ranking with reciprocal rank fusion, weighted by query shape |
