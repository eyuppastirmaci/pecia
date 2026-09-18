package dev.eyuppastirmaci.pecia.storage.sqlite.mapper;

import dev.eyuppastirmaci.pecia.index.IndexingProfile;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps SQLite processing-profile rows to validated indexing profiles. */
public final class IndexingProfileRowMapper implements RowMapper<IndexingProfile> {
    /**
     * Maps the current profile row without coercing invalid SQLite value types or overflowing limits.
     *
     * @param row the result positioned on a file indexing-profile row
     * @return the validated tokenizer key and token limits
     * @throws SQLException if columns are missing or values violate the profile contract
     */
    @Override
    public IndexingProfile map(ResultSet row) throws SQLException {
        try {
            if (!(row.getObject("tokenizer_key") instanceof String tokenizerKey)) {
                throw new SQLException("Stored tokenizer key must be text");
            }

            return new IndexingProfile(
                    tokenizerKey, readTokenLimit(row, "max_tokens"), readTokenLimit(row, "overlap_tokens"));
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new SQLException("Invalid stored indexing profile", invalid);
        }
    }

    private static int readTokenLimit(ResultSet row, String column) throws SQLException {
        Object value = row.getObject(column);

        if (value instanceof Integer integer) {
            return integer;
        }

        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }

        throw new SQLException("Stored " + column + " must be an integer");
    }
}
