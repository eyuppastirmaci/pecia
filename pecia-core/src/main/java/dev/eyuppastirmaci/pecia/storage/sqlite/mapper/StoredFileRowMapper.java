package dev.eyuppastirmaci.pecia.storage.sqlite.mapper;

import dev.eyuppastirmaci.pecia.content.ContentHash;
import dev.eyuppastirmaci.pecia.content.DocumentType;
import dev.eyuppastirmaci.pecia.storage.model.StoredFile;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps SQLite file manifest rows to validated stored files. */
public final class StoredFileRowMapper implements RowMapper<StoredFile> {
    /**
     * Maps the current manifest row into a validated stored file.
     *
     * @param row the result positioned on a files row
     * @return the stored file preserving source identity and raw-byte hash
     * @throws SQLException if columns are missing or values violate the storage contract
     */
    @Override
    public StoredFile map(ResultSet row) throws SQLException {
        try {
            return new StoredFile(
                    row.getLong("id"),
                    SqlitePath.decode(row.getString("source_path")),
                    DocumentType.valueOf(row.getString("document_type")),
                    new ContentHash(row.getString("content_hash")));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored file row", invalid);
        }
    }
}
