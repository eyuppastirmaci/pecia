-- Historical profiles lack extraction/chunking provenance. Leave these tables empty on upgrade.
CREATE TABLE file_chunking_profiles (
    file_id INTEGER PRIMARY KEY CHECK (file_id > 0) REFERENCES files(id) ON DELETE CASCADE,
    extraction_version TEXT NOT NULL CHECK (typeof(extraction_version) = 'text'
        AND length(CAST(trim(extraction_version) AS BLOB)) > 0),
    chunking_version TEXT NOT NULL CHECK (typeof(chunking_version) = 'text'
        AND length(CAST(trim(chunking_version) AS BLOB)) > 0),
    tokenizer_algorithm TEXT NOT NULL CHECK (typeof(tokenizer_algorithm) = 'text'
        AND length(CAST(trim(tokenizer_algorithm) AS BLOB)) > 0),
    vocabulary_sha256 TEXT NOT NULL CHECK (typeof(vocabulary_sha256) = 'text' AND length(vocabulary_sha256) = 64
        AND length(CAST(vocabulary_sha256 AS BLOB)) = 64
        AND vocabulary_sha256 NOT GLOB '*[^0-9a-f]*'),
    vocabulary_size INTEGER NOT NULL CHECK (typeof(vocabulary_size) = 'integer'
        AND vocabulary_size > 0 AND vocabulary_size <= 2147483647),
    max_input_tokens INTEGER NOT NULL CHECK (typeof(max_input_tokens) = 'integer'
        AND max_input_tokens > 0 AND max_input_tokens <= 2147483647),
    special_token_count INTEGER NOT NULL CHECK (typeof(special_token_count) = 'integer'
        AND special_token_count >= 0 AND special_token_count < max_input_tokens),
    max_tokens INTEGER NOT NULL CHECK (typeof(max_tokens) = 'integer'
        AND max_tokens > special_token_count AND max_tokens <= max_input_tokens),
    overlap_tokens INTEGER NOT NULL CHECK (typeof(overlap_tokens) = 'integer'
        AND overlap_tokens >= 0 AND overlap_tokens < max_tokens - special_token_count),
    fingerprint TEXT NOT NULL CHECK (typeof(fingerprint) = 'text' AND length(fingerprint) = 64
        AND length(CAST(fingerprint AS BLOB)) = 64
        AND fingerprint NOT GLOB '*[^0-9a-f]*')
);

-- The composite key lets identities enforce that their chunk and profile belong to the same file.
CREATE UNIQUE INDEX chunks_identity_owner ON chunks(id, file_id);

CREATE TABLE chunk_identities (
    chunk_id INTEGER PRIMARY KEY CHECK (chunk_id > 0),
    file_id INTEGER NOT NULL CHECK (file_id > 0),
    stable_id TEXT NOT NULL COLLATE BINARY UNIQUE CHECK (typeof(stable_id) = 'text' AND length(stable_id) = 64
        AND length(CAST(stable_id AS BLOB)) = 64
        AND stable_id NOT GLOB '*[^0-9a-f]*'),
    FOREIGN KEY (chunk_id, file_id) REFERENCES chunks(id, file_id) ON DELETE CASCADE,
    FOREIGN KEY (file_id) REFERENCES file_chunking_profiles(file_id) ON DELETE CASCADE
        DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX chunk_identities_file ON chunk_identities(file_id);

-- Invalidation removes the full profile and cascades to all identities for that file. Identity
-- rows never update chunks, so stamping them cannot recursively invalidate the completed profile.
-- Writers must finish chunks and metadata, stamp identities, then insert the profile before commit.
CREATE TRIGGER files_chunking_profile_update AFTER UPDATE ON files
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id IN (OLD.id, NEW.id);
END;

CREATE TRIGGER chunks_chunking_profile_insert AFTER INSERT ON chunks
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = NEW.file_id;
END;

CREATE TRIGGER chunks_chunking_profile_update AFTER UPDATE ON chunks
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id IN (OLD.file_id, NEW.file_id);
END;

CREATE TRIGGER chunks_chunking_profile_delete AFTER DELETE ON chunks
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = OLD.file_id;
END;

CREATE TRIGGER chunk_headings_chunking_profile_insert AFTER INSERT ON chunk_headings
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

CREATE TRIGGER chunk_headings_chunking_profile_update AFTER UPDATE ON chunk_headings
BEGIN
    DELETE FROM file_chunking_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

CREATE TRIGGER chunk_headings_chunking_profile_delete AFTER DELETE ON chunk_headings
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

CREATE TRIGGER chunk_attributes_chunking_profile_insert AFTER INSERT ON chunk_attributes
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

CREATE TRIGGER chunk_attributes_chunking_profile_update AFTER UPDATE ON chunk_attributes
BEGIN
    DELETE FROM file_chunking_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

CREATE TRIGGER chunk_attributes_chunking_profile_delete AFTER DELETE ON chunk_attributes
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

CREATE TRIGGER file_chunking_profiles_update AFTER UPDATE ON file_chunking_profiles
BEGIN
    DELETE FROM chunk_identities WHERE file_id IN (OLD.file_id, NEW.file_id);
END;
