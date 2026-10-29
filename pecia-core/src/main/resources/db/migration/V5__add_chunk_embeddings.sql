-- Ordinary tables keep vectors readable, writable, and deletable without any SQLite extension, so lexical runs never
-- depend on one. Vectors of different profiles are never comparable.
CREATE TABLE embedding_profiles (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    model_id TEXT NOT NULL CHECK (typeof(model_id) = 'text' AND length(CAST(trim(model_id) AS BLOB)) > 0),
    model_revision TEXT NOT NULL CHECK (typeof(model_revision) = 'text'
        AND length(CAST(trim(model_revision) AS BLOB)) > 0),
    model_sha256 TEXT NOT NULL CHECK (typeof(model_sha256) = 'text' AND length(model_sha256) = 64
        AND length(CAST(model_sha256 AS BLOB)) = 64
        AND model_sha256 NOT GLOB '*[^0-9a-f]*'),
    dimensions INTEGER NOT NULL CHECK (typeof(dimensions) = 'integer' AND dimensions > 0 AND dimensions <= 65536),
    tokenizer_algorithm TEXT NOT NULL CHECK (typeof(tokenizer_algorithm) = 'text'
        AND length(CAST(trim(tokenizer_algorithm) AS BLOB)) > 0),
    vocabulary_sha256 TEXT NOT NULL CHECK (typeof(vocabulary_sha256) = 'text' AND length(vocabulary_sha256) = 64
        AND length(CAST(vocabulary_sha256 AS BLOB)) = 64
        AND vocabulary_sha256 NOT GLOB '*[^0-9a-f]*'),
    max_input_tokens INTEGER NOT NULL CHECK (typeof(max_input_tokens) = 'integer'
        AND max_input_tokens > 0 AND max_input_tokens <= 2147483647),
    pooling TEXT NOT NULL CHECK (typeof(pooling) = 'text' AND length(CAST(trim(pooling) AS BLOB)) > 0),
    normalization TEXT NOT NULL CHECK (typeof(normalization) = 'text'
        AND length(CAST(trim(normalization) AS BLOB)) > 0),
    input TEXT NOT NULL CHECK (typeof(input) = 'text' AND length(CAST(trim(input) AS BLOB)) > 0),
    fingerprint TEXT NOT NULL COLLATE BINARY UNIQUE CHECK (typeof(fingerprint) = 'text' AND length(fingerprint) = 64
        AND length(CAST(fingerprint AS BLOB)) = 64
        AND fingerprint NOT GLOB '*[^0-9a-f]*')
);

-- One vector per chunk, as little-endian float32 values. Replacing or deleting a file deletes its chunks, which
-- cascades here, so no stale vector outlives its chunk.
CREATE TABLE chunk_embeddings (
    chunk_id INTEGER PRIMARY KEY CHECK (chunk_id > 0) REFERENCES chunks(id) ON DELETE CASCADE,
    profile_id INTEGER NOT NULL CHECK (profile_id > 0) REFERENCES embedding_profiles(id),
    vector BLOB NOT NULL CHECK (typeof(vector) = 'blob' AND length(vector) > 0 AND length(vector) % 4 = 0)
);

CREATE INDEX chunk_embeddings_profile ON chunk_embeddings(profile_id);

-- A vector must have exactly its profile's dimensions; an unknown profile is left to the foreign key.
CREATE TRIGGER chunk_embeddings_dimensions_insert BEFORE INSERT ON chunk_embeddings
WHEN length(NEW.vector) != 4 * (SELECT dimensions FROM embedding_profiles WHERE id = NEW.profile_id)
BEGIN
    SELECT RAISE(ABORT, 'chunk embedding length does not match its profile dimensions');
END;

CREATE TRIGGER chunk_embeddings_dimensions_update BEFORE UPDATE ON chunk_embeddings
WHEN length(NEW.vector) != 4 * (SELECT dimensions FROM embedding_profiles WHERE id = NEW.profile_id)
BEGIN
    SELECT RAISE(ABORT, 'chunk embedding length does not match its profile dimensions');
END;

-- Writers replace chunks rather than update them; an update in place must not keep a vector of the old text.
CREATE TRIGGER chunks_embedding_update AFTER UPDATE ON chunks
BEGIN
    DELETE FROM chunk_embeddings WHERE chunk_id IN (OLD.id, NEW.id);
END;

-- A profile's vectors were made under its exact settings, so a stored profile never changes.
CREATE TRIGGER embedding_profiles_update BEFORE UPDATE ON embedding_profiles
BEGIN
    SELECT RAISE(ABORT, 'embedding profiles are immutable');
END;
