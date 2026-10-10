"""Writes a deterministic, reviewable text dump of a Pecia SQLite index fixture.

The dump lists PRAGMA user_version, every schema object's SQL in name order, and every ordinary table's rows in
primary-key order. FTS5 shadow tables (chunks_fts_*) are omitted: they hold binary index structures derived from
the chunks table, and migration tests verify FTS through queries instead.

Usage: python3 -I dump-fixture.py <fixture.db> > <fixture.sql>
"""
import sqlite3
import sys


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, bytes):
        return "X'" + value.hex() + "'"
    return text_literal(str(value))


def text_literal(text):
    """Quotes printable runs and writes control characters as char(N), so the dump has no raw CR, LF, or tab."""
    parts = []
    run = []
    for character in text:
        if ord(character) < 0x20 or ord(character) == 0x7f:
            if run:
                parts.append("'" + "".join(run).replace("'", "''") + "'")
                run = []
            parts.append("char(%d)" % ord(character))
        else:
            run.append(character)
    if run or not parts:
        parts.append("'" + "".join(run).replace("'", "''") + "'")
    return " || ".join(parts)


def main(path):
    connection = sqlite3.connect("file:" + path + "?mode=ro", uri=True)
    # Write UTF-8 with LF line endings on every platform.
    out = open(sys.stdout.fileno(), "w", encoding="utf-8", newline="\n", closefd=False)
    out.write("-- PRAGMA user_version = %d\n" % connection.execute("PRAGMA user_version").fetchone()[0])
    objects = connection.execute(
        "SELECT type, name, tbl_name, sql FROM sqlite_schema"
        " WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'chunks_fts_%' ORDER BY name").fetchall()
    for kind, name, _table, sql in objects:
        if sql is not None:
            out.write("\n-- %s %s\n%s;\n" % (kind, name, sql))
    for kind, name, _table, sql in objects:
        if kind != "table" or (sql or "").upper().startswith("CREATE VIRTUAL TABLE"):
            continue
        columns = [row[1] for row in connection.execute("PRAGMA table_info(%s)" % name)]
        keys = [row[1] for row in sorted(connection.execute("PRAGMA table_info(%s)" % name), key=lambda r: r[5]) if row[5] > 0]
        order = ", ".join(keys or columns)
        out.write("\n-- rows of %s\n" % name)
        for row in connection.execute("SELECT %s FROM %s ORDER BY %s" % (", ".join(columns), name, order)):
            out.write("INSERT INTO %s(%s) VALUES (%s);\n" % (name, ", ".join(columns), ", ".join(literal(v) for v in row)))
    out.flush()
    connection.close()


if __name__ == "__main__":
    main(sys.argv[1])
