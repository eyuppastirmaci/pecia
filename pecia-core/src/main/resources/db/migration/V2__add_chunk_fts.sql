-- The caller owns the transaction, V1 validation and version markers.
-- Keep files/chunks/metadata authoritative; this table is a derived search copy.
CREATE VIRTUAL TABLE chunks_fts USING fts5(
    content,
    headings,
    source_path,
    tokenize = 'unicode61 remove_diacritics 2',
    detail = full,
    columnsize = 1
);

CREATE TRIGGER chunks_fts_insert AFTER INSERT ON chunks
BEGIN
    INSERT INTO chunks_fts(rowid, content, headings, source_path)
    SELECT NEW.id, NEW.content,
           COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                     FROM chunk_headings WHERE chunk_id = NEW.id), ''),
           source_path
    FROM files WHERE id = NEW.file_id;
END;

CREATE TRIGGER chunks_fts_update AFTER UPDATE OF content, file_id ON chunks
WHEN OLD.content IS NOT NEW.content OR OLD.file_id IS NOT NEW.file_id
BEGIN
    UPDATE chunks_fts
    SET content = NEW.content,
        source_path = (SELECT source_path FROM files WHERE id = NEW.file_id)
    WHERE rowid = OLD.id;
END;

CREATE TRIGGER chunks_fts_delete AFTER DELETE ON chunks
BEGIN
    DELETE FROM chunks_fts WHERE rowid = OLD.id;
END;

-- Metadata is inserted after its chunk. Only update existing FTS rows so a
-- cascading heading deletion can never recreate a deleted chunk's search copy.
CREATE TRIGGER chunk_headings_fts_insert AFTER INSERT ON chunk_headings
BEGIN
    UPDATE chunks_fts
    SET headings = COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                             FROM chunk_headings WHERE chunk_id = NEW.chunk_id), '')
    WHERE rowid = NEW.chunk_id;
END;

CREATE TRIGGER chunk_headings_fts_update AFTER UPDATE OF chunk_id, position, heading ON chunk_headings
WHEN OLD.chunk_id IS NOT NEW.chunk_id OR OLD.position IS NOT NEW.position OR OLD.heading IS NOT NEW.heading
BEGIN
    UPDATE chunks_fts
    SET headings = COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                             FROM chunk_headings WHERE chunk_id = chunks_fts.rowid), '')
    WHERE rowid IN (OLD.chunk_id, NEW.chunk_id);
END;

CREATE TRIGGER chunk_headings_fts_delete AFTER DELETE ON chunk_headings
BEGIN
    UPDATE chunks_fts
    SET headings = COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                             FROM chunk_headings WHERE chunk_id = OLD.chunk_id), '')
    WHERE rowid = OLD.chunk_id;
END;

CREATE TRIGGER files_fts_path_update AFTER UPDATE OF source_path ON files
WHEN OLD.source_path IS NOT NEW.source_path
BEGIN
    UPDATE chunks_fts
    SET source_path = NEW.source_path
    WHERE rowid IN (SELECT id FROM chunks WHERE file_id = NEW.id);
END;

-- Triggers cover future writes; existing V1 chunks need this one-time backfill.
INSERT INTO chunks_fts(rowid, content, headings, source_path)
SELECT c.id, c.content,
       COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                 FROM chunk_headings WHERE chunk_id = c.id), ''),
       f.source_path
FROM chunks c JOIN files f ON f.id = c.file_id
ORDER BY c.id;
