# Retrieval-quality set

These files measure how well Pecia ranks the files that answer a query. `corpus/` is a small fictional company repository written for this purpose: documentation, runbooks, incident reviews, meeting notes, Java, Python, and TypeScript sources, configuration files, and a few Turkish documents. `queries.toml` lists labelled queries over it. `RetrievalSet` loads both and rejects labels that break the rules below.

## Queries

Each query has a kind, so results are reported per kind:

| Kind | Meaning |
| --- | --- |
| `identifier` | an exact name or code, such as a class, a constant, or an error code |
| `keyword` | a few words that appear in the answer |
| `paraphrase` | a natural question that shares almost no words with the answer |
| `mixed` | an identifier together with natural words |
| `turkish` | a Turkish query, or an English query whose answer is a Turkish document |

`answers` are the files that answer the query (grade 2); `related` are files a reader would also find useful (grade 1). Relevance is judged per file, so the labels survive changes to chunking. `answer_first_in` names the modes in which an answer must rank first; it is kept for exact identifiers that appear in one file only, where anything else is a regression.

## Rules

- Every labelled path exists in `corpus/`, no path is listed twice in one query, and every query has an answer.
- Every corpus file has a type Pecia indexes and lies outside the directories it skips.
- Every kind has queries, and no two queries share an id or a text.
- Queries and labels were written before any mode was measured. Change a label only when it is wrong, and say why in the commit; never change one to make a number pass.
- The corpus keeps its exact bytes on every platform (`.gitattributes` marks it `-text`). Adding or changing a corpus file changes the measured values, so the thresholds are reviewed in the same change.
