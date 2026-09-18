-- Legacy files have no profile row: their tokenizer and chunk budgets are unknown.
CREATE TABLE file_indexing_profiles (
    file_id INTEGER PRIMARY KEY CHECK (file_id > 0) REFERENCES files(id) ON DELETE CASCADE,
    tokenizer_key TEXT NOT NULL CHECK (length(trim(tokenizer_key)) > 0),
    max_tokens INTEGER NOT NULL CHECK (typeof(max_tokens) = 'integer' AND max_tokens > 0 AND max_tokens <= 2147483647),
    overlap_tokens INTEGER NOT NULL CHECK (typeof(overlap_tokens) = 'integer' AND overlap_tokens >= 0
        AND overlap_tokens < max_tokens)
);

-- Replacement writes the profile last, after all file, chunk, and metadata mutations.
CREATE TRIGGER files_indexing_profile_update AFTER UPDATE ON files
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id IN (OLD.id, NEW.id);
END;

CREATE TRIGGER chunks_indexing_profile_insert AFTER INSERT ON chunks
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = NEW.file_id;
END;

CREATE TRIGGER chunks_indexing_profile_update AFTER UPDATE ON chunks
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id IN (OLD.file_id, NEW.file_id);
END;

CREATE TRIGGER chunks_indexing_profile_delete AFTER DELETE ON chunks
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = OLD.file_id;
END;

CREATE TRIGGER chunk_headings_indexing_profile_insert AFTER INSERT ON chunk_headings
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

CREATE TRIGGER chunk_headings_indexing_profile_update AFTER UPDATE ON chunk_headings
BEGIN
    DELETE FROM file_indexing_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

CREATE TRIGGER chunk_headings_indexing_profile_delete AFTER DELETE ON chunk_headings
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

CREATE TRIGGER chunk_attributes_indexing_profile_insert AFTER INSERT ON chunk_attributes
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

CREATE TRIGGER chunk_attributes_indexing_profile_update AFTER UPDATE ON chunk_attributes
BEGIN
    DELETE FROM file_indexing_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

CREATE TRIGGER chunk_attributes_indexing_profile_delete AFTER DELETE ON chunk_attributes
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;
