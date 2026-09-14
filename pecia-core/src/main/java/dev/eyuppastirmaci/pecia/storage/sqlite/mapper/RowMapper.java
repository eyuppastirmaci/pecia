package dev.eyuppastirmaci.pecia.storage.sqlite.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;

@FunctionalInterface
public interface RowMapper<T> {
    /**
     * Maps the current result row without advancing or closing the cursor.
     *
     * @param row the result positioned on a row
     * @return the mapped value
     * @throws SQLException if the row cannot be read or represented
     */
    T map(ResultSet row) throws SQLException;
}
