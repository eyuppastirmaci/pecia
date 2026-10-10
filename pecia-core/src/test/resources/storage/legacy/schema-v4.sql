-- PRAGMA user_version = 4

-- table chunk_attributes
CREATE TABLE chunk_attributes (
    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(name) > 0),
    value TEXT NOT NULL,
    PRIMARY KEY (chunk_id, name)
);

-- trigger chunk_attributes_chunking_profile_delete
CREATE TRIGGER chunk_attributes_chunking_profile_delete AFTER DELETE ON chunk_attributes
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

-- trigger chunk_attributes_chunking_profile_insert
CREATE TRIGGER chunk_attributes_chunking_profile_insert AFTER INSERT ON chunk_attributes
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

-- trigger chunk_attributes_chunking_profile_update
CREATE TRIGGER chunk_attributes_chunking_profile_update AFTER UPDATE ON chunk_attributes
BEGIN
    DELETE FROM file_chunking_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

-- trigger chunk_attributes_indexing_profile_delete
CREATE TRIGGER chunk_attributes_indexing_profile_delete AFTER DELETE ON chunk_attributes
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

-- trigger chunk_attributes_indexing_profile_insert
CREATE TRIGGER chunk_attributes_indexing_profile_insert AFTER INSERT ON chunk_attributes
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

-- trigger chunk_attributes_indexing_profile_update
CREATE TRIGGER chunk_attributes_indexing_profile_update AFTER UPDATE ON chunk_attributes
BEGIN
    DELETE FROM file_indexing_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

-- table chunk_headings
CREATE TABLE chunk_headings (
    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    heading TEXT NOT NULL CHECK (length(heading) > 0),
    PRIMARY KEY (chunk_id, position)
);

-- trigger chunk_headings_chunking_profile_delete
CREATE TRIGGER chunk_headings_chunking_profile_delete AFTER DELETE ON chunk_headings
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

-- trigger chunk_headings_chunking_profile_insert
CREATE TRIGGER chunk_headings_chunking_profile_insert AFTER INSERT ON chunk_headings
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

-- trigger chunk_headings_chunking_profile_update
CREATE TRIGGER chunk_headings_chunking_profile_update AFTER UPDATE ON chunk_headings
BEGIN
    DELETE FROM file_chunking_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

-- trigger chunk_headings_fts_delete
CREATE TRIGGER chunk_headings_fts_delete AFTER DELETE ON chunk_headings
BEGIN
    UPDATE chunks_fts
    SET headings = COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                             FROM chunk_headings WHERE chunk_id = OLD.chunk_id), '')
    WHERE rowid = OLD.chunk_id;
END;

-- trigger chunk_headings_fts_insert
CREATE TRIGGER chunk_headings_fts_insert AFTER INSERT ON chunk_headings
BEGIN
    UPDATE chunks_fts
    SET headings = COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                             FROM chunk_headings WHERE chunk_id = NEW.chunk_id), '')
    WHERE rowid = NEW.chunk_id;
END;

-- trigger chunk_headings_fts_update
CREATE TRIGGER chunk_headings_fts_update AFTER UPDATE OF chunk_id, position, heading ON chunk_headings
WHEN OLD.chunk_id IS NOT NEW.chunk_id OR OLD.position IS NOT NEW.position OR OLD.heading IS NOT NEW.heading
BEGIN
    UPDATE chunks_fts
    SET headings = COALESCE((SELECT group_concat(heading, ' ' ORDER BY position)
                             FROM chunk_headings WHERE chunk_id = chunks_fts.rowid), '')
    WHERE rowid IN (OLD.chunk_id, NEW.chunk_id);
END;

-- trigger chunk_headings_indexing_profile_delete
CREATE TRIGGER chunk_headings_indexing_profile_delete AFTER DELETE ON chunk_headings
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = OLD.chunk_id);
END;

-- trigger chunk_headings_indexing_profile_insert
CREATE TRIGGER chunk_headings_indexing_profile_insert AFTER INSERT ON chunk_headings
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = (SELECT file_id FROM chunks WHERE id = NEW.chunk_id);
END;

-- trigger chunk_headings_indexing_profile_update
CREATE TRIGGER chunk_headings_indexing_profile_update AFTER UPDATE ON chunk_headings
BEGIN
    DELETE FROM file_indexing_profiles
    WHERE file_id IN (SELECT file_id FROM chunks WHERE id IN (OLD.chunk_id, NEW.chunk_id));
END;

-- table chunk_identities
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

-- index chunk_identities_file
CREATE INDEX chunk_identities_file ON chunk_identities(file_id);

-- table chunks
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL CHECK (length(content) > 0),
    start_line INTEGER NOT NULL CHECK (start_line >= 1),
    end_line INTEGER NOT NULL CHECK (end_line >= start_line),
    UNIQUE (file_id, chunk_index)
);

-- trigger chunks_chunking_profile_delete
CREATE TRIGGER chunks_chunking_profile_delete AFTER DELETE ON chunks
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = OLD.file_id;
END;

-- trigger chunks_chunking_profile_insert
CREATE TRIGGER chunks_chunking_profile_insert AFTER INSERT ON chunks
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id = NEW.file_id;
END;

-- trigger chunks_chunking_profile_update
CREATE TRIGGER chunks_chunking_profile_update AFTER UPDATE ON chunks
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id IN (OLD.file_id, NEW.file_id);
END;

-- table chunks_fts
CREATE VIRTUAL TABLE chunks_fts USING fts5(
    content,
    headings,
    source_path,
    tokenize = 'unicode61 remove_diacritics 2',
    detail = full,
    columnsize = 1
);

-- index chunks_identity_owner
CREATE UNIQUE INDEX chunks_identity_owner ON chunks(id, file_id);

-- trigger chunks_indexing_profile_delete
CREATE TRIGGER chunks_indexing_profile_delete AFTER DELETE ON chunks
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = OLD.file_id;
END;

-- trigger chunks_indexing_profile_insert
CREATE TRIGGER chunks_indexing_profile_insert AFTER INSERT ON chunks
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id = NEW.file_id;
END;

-- trigger chunks_indexing_profile_update
CREATE TRIGGER chunks_indexing_profile_update AFTER UPDATE ON chunks
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id IN (OLD.file_id, NEW.file_id);
END;

-- table file_chunking_profiles
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

-- trigger file_chunking_profiles_update
CREATE TRIGGER file_chunking_profiles_update AFTER UPDATE ON file_chunking_profiles
BEGIN
    DELETE FROM chunk_identities WHERE file_id IN (OLD.file_id, NEW.file_id);
END;

-- table file_indexing_profiles
CREATE TABLE file_indexing_profiles (
    file_id INTEGER PRIMARY KEY CHECK (file_id > 0) REFERENCES files(id) ON DELETE CASCADE,
    tokenizer_key TEXT NOT NULL CHECK (length(trim(tokenizer_key)) > 0),
    max_tokens INTEGER NOT NULL CHECK (typeof(max_tokens) = 'integer' AND max_tokens > 0 AND max_tokens <= 2147483647),
    overlap_tokens INTEGER NOT NULL CHECK (typeof(overlap_tokens) = 'integer' AND overlap_tokens >= 0
        AND overlap_tokens < max_tokens)
);

-- table files
CREATE TABLE files (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    source_path TEXT NOT NULL COLLATE BINARY UNIQUE CHECK (length(source_path) > 0),
    document_type TEXT NOT NULL CHECK (document_type IN ('PLAIN_TEXT', 'MARKDOWN', 'SOURCE_CODE', 'STRUCTURED_TEXT')),
    content_hash TEXT NOT NULL CHECK (length(content_hash) = 64 AND content_hash NOT GLOB '*[^0-9a-f]*')
);

-- trigger files_chunking_profile_update
CREATE TRIGGER files_chunking_profile_update AFTER UPDATE ON files
BEGIN
    DELETE FROM file_chunking_profiles WHERE file_id IN (OLD.id, NEW.id);
END;

-- trigger files_fts_path_update
CREATE TRIGGER files_fts_path_update AFTER UPDATE OF source_path ON files
WHEN OLD.source_path IS NOT NEW.source_path
BEGIN
    UPDATE chunks_fts
    SET source_path = NEW.source_path
    WHERE rowid IN (SELECT id FROM chunks WHERE file_id = NEW.id);
END;

-- trigger files_indexing_profile_update
CREATE TRIGGER files_indexing_profile_update AFTER UPDATE ON files
BEGIN
    DELETE FROM file_indexing_profiles WHERE file_id IN (OLD.id, NEW.id);
END;

-- table index_metadata
CREATE TABLE index_metadata (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    project_root_uri TEXT NOT NULL CHECK (length(project_root_uri) > 0),
    index_format_version INTEGER NOT NULL CHECK (index_format_version > 0)
);

-- rows of chunk_attributes
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (1, 'endOffset', '39');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (1, 'startOffset', '0');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (2, 'endOffset', '53');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (2, 'startOffset', '0');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (3, 'endOffset', '58');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (3, 'startOffset', '0');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (4, 'endOffset', '96');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (4, 'startOffset', '58');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (5, 'endOffset', '143');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (5, 'startOffset', '96');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (6, 'endOffset', '183');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (6, 'startOffset', '143');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (7, 'endOffset', '1018');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (7, 'startOffset', '0');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (8, 'endOffset', '1918');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (8, 'startOffset', '892');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (9, 'endOffset', '2821');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (9, 'startOffset', '1789');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (10, 'endOffset', '3724');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (10, 'startOffset', '2692');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (11, 'endOffset', '4627');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (11, 'startOffset', '3595');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (12, 'endOffset', '5143');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (12, 'startOffset', '4498');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (13, 'endOffset', '71');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (13, 'startOffset', '0');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (14, 'endOffset', '36');
INSERT INTO chunk_attributes(chunk_id, name, value) VALUES (14, 'startOffset', '0');

-- rows of chunk_headings
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (3, 0, 'Ödeme rehberi');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (4, 0, 'Ödeme rehberi');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (4, 1, 'Kartlar');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (5, 0, 'Ödeme rehberi');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (5, 1, 'Kartlar');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (5, 2, 'Retry policy');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (6, 0, 'Ödeme rehberi');
INSERT INTO chunk_headings(chunk_id, position, heading) VALUES (6, 1, 'İade');

-- rows of chunk_identities
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (1, 1, '3d1cd91a0e406df69d8a384c648a28235d51f898e2b8ffbae39200b824951e42');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (2, 2, '9434163267f9afac3b4b51f3282a019b4387772d4357e3f1c229fcd525b2090a');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (3, 3, 'c820d67117ed5ac7072dcdb42f2cebf3d3461a8b6f4f61b96674fe959ddf8064');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (4, 3, '1998d0be4fa1f02bc04b21e763eccdcb37e5cf6bf2aa21926b3617fefe5bc497');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (5, 3, '751f2d6a4350c6bac03228fa444062aa8f5ef00aed9d9c329123c0c04092787c');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (6, 3, 'bd22a96f2a8ca47dce955ba546a9a600f0d3ef77c5481716aef401c8c3d6b3ee');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (7, 5, '3130e136503beb35335de92c4e7ceae8db3bae349134e6fafc97d5591935ebea');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (8, 5, '4fd96affd5c56c68d04e1c015838dd1676d059f4231f2df39d0e7933d7d25ef4');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (9, 5, 'da592b9820ac56cba202b162f02d7cb5d8007faeb76ba398dbf844eebcb802a9');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (10, 5, '53b34086d457685f6a121a654a1bfcd21b5f84d76fd7b12ccb792bdc864a7fc7');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (11, 5, 'ba9a60c986ed4a4809c69170e89e1a31bd022f0cbbe3ae9234085eacd0765d12');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (12, 5, '3c8b25379c836581577a2766545f9a2798fedad9e2badae37e7ea302ecae5d43');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (13, 6, '2ad978a91ad897a51a747037b7dae420f9fb8fd610f915e2b96104ebeb164591');
INSERT INTO chunk_identities(chunk_id, file_id, stable_id) VALUES (14, 7, '74ca10bf11edfcf658b52d8a15a2ba83bd1dacf204b6b101037fa642c47f5cc8');

-- rows of chunks
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (1, 1, 0, 'payment:' || char(10) || '  retries: 3' || char(10) || '  city: Istanbul' || char(10), 1, 3);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (2, 2, 0, 'Release notes: payment fixes and validation cleanup.' || char(10), 1, 1);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (3, 3, 0, '# Ödeme rehberi' || char(10) || char(10) || 'Payment validation for İstanbul şubesi.' || char(10) || char(10), 1, 4);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (4, 3, 1, '## Kartlar' || char(10) || char(10) || 'Kart ödemesi doğrulanır.' || char(10) || char(10), 5, 8);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (5, 3, 2, '### Retry policy' || char(10) || char(10) || '```java' || char(10) || 'retry(payment);' || char(10) || '```' || char(10) || char(10), 9, 14);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (6, 3, 3, '## İade' || char(10) || char(10) || 'İade işlemleri ayrı yürütülür.' || char(10), 15, 17);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (7, 5, 0, 'Handbook' || char(10) || char(10) || 'Section 1 covers payment validation step 1. Ödeme adımı 1 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 2 covers payment validation step 2. Ödeme adımı 2 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 3 covers payment validation step 3. Ödeme adımı 3 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 4 covers payment validation step 4. Ödeme adımı 4 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 5 covers payment validation step 5. Ödeme adımı 5 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 6 covers payment validation step 6. Ödeme adımı 6 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 7 covers payment validation step 7. Ödeme adımı 7 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 8 covers payment validation step 8. Ödeme adımı 8 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10), 1, 18);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (8, 5, 1, 'Section 8 covers payment validation step 8. Ödeme adımı 8 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 9 covers payment validation step 9. Ödeme adımı 9 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 10 covers payment validation step 10. Ödeme adımı 10 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 11 covers payment validation step 11. Ödeme adımı 11 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 12 covers payment validation step 12. Ödeme adımı 12 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 13 covers payment validation step 13. Ödeme adımı 13 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 14 covers payment validation step 14. Ödeme adımı 14 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 15 covers payment validation step 15. Ödeme adımı 15 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10), 17, 32);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (9, 5, 2, 'Section 15 covers payment validation step 15. Ödeme adımı 15 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 16 covers payment validation step 16. Ödeme adımı 16 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 17 covers payment validation step 17. Ödeme adımı 17 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 18 covers payment validation step 18. Ödeme adımı 18 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 19 covers payment validation step 19. Ödeme adımı 19 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 20 covers payment validation step 20. Ödeme adımı 20 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 21 covers payment validation step 21. Ödeme adımı 21 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 22 covers payment validation step 22. Ödeme adımı 22 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10), 31, 46);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (10, 5, 3, 'Section 22 covers payment validation step 22. Ödeme adımı 22 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 23 covers payment validation step 23. Ödeme adımı 23 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 24 covers payment validation step 24. Ödeme adımı 24 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 25 covers payment validation step 25. Ödeme adımı 25 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 26 covers payment validation step 26. Ödeme adımı 26 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 27 covers payment validation step 27. Ödeme adımı 27 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 28 covers payment validation step 28. Ödeme adımı 28 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 29 covers payment validation step 29. Ödeme adımı 29 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10), 45, 60);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (11, 5, 4, 'Section 29 covers payment validation step 29. Ödeme adımı 29 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 30 covers payment validation step 30. Ödeme adımı 30 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 31 covers payment validation step 31. Ödeme adımı 31 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 32 covers payment validation step 32. Ödeme adımı 32 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 33 covers payment validation step 33. Ödeme adımı 33 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 34 covers payment validation step 34. Ödeme adımı 34 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 35 covers payment validation step 35. Ödeme adımı 35 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 36 covers payment validation step 36. Ödeme adımı 36 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10), 59, 74);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (12, 5, 5, 'Section 36 covers payment validation step 36. Ödeme adımı 36 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 37 covers payment validation step 37. Ödeme adımı 37 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 38 covers payment validation step 38. Ödeme adımı 38 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 39 covers payment validation step 39. Ödeme adımı 39 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10) || 'Section 40 covers payment validation step 40. Ödeme adımı 40 İstanbul ekibince incelenir. A retry follows every failed attempt.' || char(10) || char(10), 73, 82);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (13, 6, 0, 'class Auth {' || char(13) || char(10) || '    String JWT_SECRET = "x";' || char(13) || char(10) || '    void validate() {}' || char(13) || char(10) || '}' || char(13) || char(10), 1, 4);
INSERT INTO chunks(id, file_id, chunk_index, content, start_line, end_line) VALUES (14, 7, 0, '#!/bin/sh' || char(10) || 'set -e' || char(10) || 'echo "retry build"' || char(10), 1, 3);

-- rows of file_chunking_profiles
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (1, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (2, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (3, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (4, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (5, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (6, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');
INSERT INTO file_chunking_profiles(file_id, extraction_version, chunking_version, tokenizer_algorithm, vocabulary_sha256, vocabulary_size, max_input_tokens, special_token_count, max_tokens, overlap_tokens, fingerprint) VALUES (7, 'pecia-utf8-extraction-v1', 'pecia-built-in-chunking-v1', 'bert-uncased-wordpiece-v1', '07eced375cec144d27c900241f3e339478dec958f92fddbc551f295c992038a3', 30522, 256, 2, 256, 32, '1453da1868ae3d018f0676d2da31b79b81b5e1a469f1b2daccc661ea276934b7');

-- rows of file_indexing_profiles

-- rows of files
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (1, 'config.yaml', 'STRUCTURED_TEXT', 'b0708c6e3ab626dd7c597f9d7e4c64d1afdad3df1075249a4fbb74530ad82760');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (2, 'docs/notes.txt', 'PLAIN_TEXT', '0fad918a3d8c77591e8b3c33b3aee0e05c1ae565b01a1bdd23a7aba5bd6da1d0');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (3, 'docs/Ödeme rehberi.md', 'MARKDOWN', '150fe639c6a7ed6e87b2979b91f1cb0f6083c75b369a162496cd4394e2ee6e0a');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (4, 'empty.txt', 'PLAIN_TEXT', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (5, 'handbook.txt', 'PLAIN_TEXT', '5c8857e86531f033fa7cd805a75aed7c4a0eaac868cc7dcd90ea3d20398fa121');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (6, 'src/Auth.java', 'SOURCE_CODE', '392e1d0e80b37f663235a3a8710e22fbc02067504e82fc14d22a249a7ba03f6b');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (7, 'src/scripts/build.sh', 'SOURCE_CODE', '8f97d95c50528bb7a592a5136c7d8908cfed5709951b0b2431676b0efd23899c');

-- rows of index_metadata
INSERT INTO index_metadata(singleton, project_root_uri, index_format_version) VALUES (1, 'file:///private/tmp/pecia-legacy-fixture/v4/project/', 4);
