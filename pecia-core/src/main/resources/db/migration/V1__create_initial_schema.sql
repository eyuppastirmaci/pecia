CREATE TABLE files (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    source_path TEXT NOT NULL COLLATE BINARY UNIQUE CHECK (length(source_path) > 0),
    document_type TEXT NOT NULL CHECK (document_type IN ('PLAIN_TEXT', 'MARKDOWN', 'SOURCE_CODE', 'STRUCTURED_TEXT')),
    content_hash TEXT NOT NULL CHECK (length(content_hash) = 64 AND content_hash NOT GLOB '*[^0-9a-f]*')
);

CREATE TABLE chunks (
    id INTEGER PRIMARY KEY CHECK (id > 0),
    file_id INTEGER NOT NULL REFERENCES files(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL CHECK (chunk_index >= 0),
    content TEXT NOT NULL CHECK (length(content) > 0),
    start_line INTEGER NOT NULL CHECK (start_line >= 1),
    end_line INTEGER NOT NULL CHECK (end_line >= start_line),
    UNIQUE (file_id, chunk_index)
);

CREATE TABLE chunk_headings (
    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    heading TEXT NOT NULL CHECK (length(heading) > 0),
    PRIMARY KEY (chunk_id, position)
);

CREATE TABLE chunk_attributes (
    chunk_id INTEGER NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (length(name) > 0),
    value TEXT NOT NULL,
    PRIMARY KEY (chunk_id, name)
);

CREATE TABLE index_metadata (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    project_root_uri TEXT NOT NULL CHECK (length(project_root_uri) > 0),
    index_format_version INTEGER NOT NULL CHECK (index_format_version > 0)
);
