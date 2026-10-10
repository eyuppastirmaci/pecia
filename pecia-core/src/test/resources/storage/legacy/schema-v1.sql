-- PRAGMA user_version = 1

-- table chunk_attributes
CREATE TABLE chunk_attributes (
    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(name) > 0),
    value TEXT NOT NULL,
    PRIMARY KEY (chunk_id, name)
);

-- table chunk_headings
CREATE TABLE chunk_headings (
    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    heading TEXT NOT NULL CHECK (length(heading) > 0),
    PRIMARY KEY (chunk_id, position)
);

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

-- table files
CREATE TABLE files (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    source_path TEXT NOT NULL COLLATE BINARY UNIQUE CHECK (length(source_path) > 0),
    document_type TEXT NOT NULL CHECK (document_type IN ('PLAIN_TEXT', 'MARKDOWN', 'SOURCE_CODE', 'STRUCTURED_TEXT')),
    content_hash TEXT NOT NULL CHECK (length(content_hash) = 64 AND content_hash NOT GLOB '*[^0-9a-f]*')
);

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

-- rows of files
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (1, 'config.yaml', 'STRUCTURED_TEXT', 'b0708c6e3ab626dd7c597f9d7e4c64d1afdad3df1075249a4fbb74530ad82760');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (2, 'docs/notes.txt', 'PLAIN_TEXT', '0fad918a3d8c77591e8b3c33b3aee0e05c1ae565b01a1bdd23a7aba5bd6da1d0');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (3, 'docs/Ödeme rehberi.md', 'MARKDOWN', '150fe639c6a7ed6e87b2979b91f1cb0f6083c75b369a162496cd4394e2ee6e0a');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (4, 'empty.txt', 'PLAIN_TEXT', 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (5, 'handbook.txt', 'PLAIN_TEXT', '5c8857e86531f033fa7cd805a75aed7c4a0eaac868cc7dcd90ea3d20398fa121');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (6, 'src/Auth.java', 'SOURCE_CODE', '392e1d0e80b37f663235a3a8710e22fbc02067504e82fc14d22a249a7ba03f6b');
INSERT INTO files(id, source_path, document_type, content_hash) VALUES (7, 'src/scripts/build.sh', 'SOURCE_CODE', '8f97d95c50528bb7a592a5136c7d8908cfed5709951b0b2431676b0efd23899c');

-- rows of index_metadata
INSERT INTO index_metadata(singleton, project_root_uri, index_format_version) VALUES (1, 'file:///private/tmp/pecia-legacy-fixture/v1/project/', 1);
