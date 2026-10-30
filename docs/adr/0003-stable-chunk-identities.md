# 0003. Chunk identities from path, position, and chunking profile

**Status:** accepted for v0.1.0.

## Context

SQLite row IDs change whenever a file is reindexed, so they cannot identify a chunk across runs. Callers of the core API, and later features such as reranking or a desktop interface, need an identity that survives reindexing and is the same on every machine that indexes the same project.

## Decision

- Each chunk's ID is a SHA-256 over a format tag, the project-relative path (slash-separated, case and Unicode spelling preserved), the zero-based chunk position, and the chunking profile's fingerprint, each length-prefixed.
- Content is not an input: editing a chunk keeps its ID as long as its position and the profile stay the same. The file's content hash tracks freshness separately.
- IDs are scoped to a project; the absolute project root is not an input, so moving the project keeps them.
- Chunks written before identities existed, or whose profile is incomplete, have no ID until they are reindexed.

## Consequences

- The API exposes the ID as `StoredChunk.stableId()` and `SearchHit.stableId()`; JSON output includes it.
- Identities are reproducible across machines and runs, and need no stored counter.
- An insertion early in a file shifts the positions, and so the IDs, of every later chunk in that file.
- A chunking profile change (0002) changes every ID, as it should, because the chunks themselves change.

## Alternatives considered

- **Content hashes:** stable under moves within a file, but two equal chunks would collide, and every edit would change the ID.
- **Random IDs stored in the index:** unique, but differ between machines and are lost when the index is rebuilt.
