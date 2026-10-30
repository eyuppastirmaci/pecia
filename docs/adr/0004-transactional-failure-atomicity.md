# 0004. One transaction per file, and deletions only after complete runs

**Status:** accepted for v0.1.0.

## Context

Indexing can be interrupted at any moment: by Ctrl+C, a crash, a full disk, or a power loss. A file can be unreadable, change while it is read, or fail to embed. The index must never end up with half of a file, and a file must never disappear from the index because Pecia failed to read it.

## Decision

- Each file is replaced in one SQLite transaction: its manifest entry, chunks, headings, identities, profile, and FTS5 rows (0001) commit together or not at all. A failure keeps the file's previous version.
- Files that finished stay committed when a later file or the storage fails; the run reports what it completed and why it stopped.
- A file that disappeared is removed only after a run that found and read every candidate without problems. A run with unreadable folders or rejected files deletes nothing.
- Embeddings for stored chunks are written in pages that commit on their own, so an interrupted semantic run resumes where it stopped.
- A file that changes while it is read is read once more; if it keeps changing, it is reported, not indexed half-way.
- Storage failures are classified (`DISK_FULL`, `BUSY`, `READ_ONLY`, `IO_ERROR`, `CORRUPT`, `WRONG_PROJECT`, `INCOMPATIBLE`) so the CLI can say what to do.

## Consequences

- After any interruption, the next run produces the same index as an uninterrupted one; the packaged tests check this with SIGTERM and with killing the process outright.
- A partial run is not an error for the index, only for the exit code: problems are reported, and everything else is current.
- Stale entries for deleted files can stay until a clean run, so search may briefly list a file that no longer exists.
- One transaction per file costs throughput on very large projects compared with batching many files per transaction.

## Alternatives considered

- **One transaction for the whole run:** all or nothing, but a late failure would discard hours of work, and readers would see no progress.
- **Writes without transactions, with repair afterwards:** faster, but every reader would have to tolerate inconsistent states.
