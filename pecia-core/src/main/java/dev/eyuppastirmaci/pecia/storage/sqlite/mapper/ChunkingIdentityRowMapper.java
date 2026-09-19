package dev.eyuppastirmaci.pecia.storage.sqlite.mapper;

import dev.eyuppastirmaci.pecia.chunking.ChunkingIdentity;
import dev.eyuppastirmaci.pecia.tokenization.TokenizerCompatibility;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps full SQLite chunking profiles and verifies their persisted fingerprints. */
public final class ChunkingIdentityRowMapper implements RowMapper<ChunkingIdentity> {

    /**
     * Reads the complete profile without coercing SQLite values or overflowing token limits.
     *
     * @param row the result positioned on a file chunking-profile row
     * @return the validated identity whose fingerprint matches the stored value
     * @throws SQLException if columns are missing, invalid, or inconsistent with the fingerprint
     */
    @Override
    public ChunkingIdentity map(ResultSet row) throws SQLException {
        try {
            TokenizerCompatibility tokenizer = new TokenizerCompatibility(
                    readText(row, "tokenizer_algorithm"),
                    readText(row, "vocabulary_sha256"),
                    readInteger(row, "vocabulary_size"),
                    readInteger(row, "max_input_tokens"),
                    readInteger(row, "special_token_count"));
            ChunkingIdentity identity = new ChunkingIdentity(
                    readText(row, "extraction_version"),
                    readText(row, "chunking_version"),
                    tokenizer,
                    readInteger(row, "max_tokens"),
                    readInteger(row, "overlap_tokens"));

            if (!identity.fingerprint().equals(readText(row, "fingerprint"))) {
                throw new SQLException("Stored chunking fingerprint does not match its profile");
            }

            return identity;
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            throw new SQLException("Invalid stored chunking profile", invalid);
        }
    }

    private static String readText(ResultSet row, String column) throws SQLException {
        if (row.getObject(column) instanceof String text) {
            return text;
        }

        throw new SQLException("Stored " + column + " must be text");
    }

    private static int readInteger(ResultSet row, String column) throws SQLException {
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
