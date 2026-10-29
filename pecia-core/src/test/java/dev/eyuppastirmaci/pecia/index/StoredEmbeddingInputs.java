package dev.eyuppastirmaci.pecia.index;

import dev.eyuppastirmaci.pecia.semantic.EmbeddingInput;
import dev.eyuppastirmaci.pecia.storage.sqlite.SqlitePath;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads every stored chunk's embedding input, built from its stored path, headings, and content. */
final class StoredEmbeddingInputs {

    private StoredEmbeddingInputs() {}

    /** Maps each chunk's ID, as text, to its embedding input. */
    static Map<String, String> byChunkId(Path database) throws SQLException {
        return read(database, true);
    }

    /** Maps each chunk's content to its embedding input; contents must be distinct. */
    static Map<String, String> byContent(Path database) throws SQLException {
        return read(database, false);
    }

    private static Map<String, String> read(Path database, boolean byId) throws SQLException {
        Map<String, String> inputs = new LinkedHashMap<>();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var chunks = connection.createStatement().executeQuery("""
                        SELECT c.id, f.source_path, c.content FROM chunks c JOIN files f ON f.id = c.file_id
                        ORDER BY c.id""");
                var headings = connection.prepareStatement(
                        "SELECT heading FROM chunk_headings WHERE chunk_id = ? ORDER BY position")) {
            while (chunks.next()) {
                List<String> headingPath = new ArrayList<>();
                headings.setLong(1, chunks.getLong(1));

                try (var rows = headings.executeQuery()) {
                    while (rows.next()) {
                        headingPath.add(rows.getString(1));
                    }
                }

                String content = chunks.getString(3);
                String input = EmbeddingInput.of(SqlitePath.decode(chunks.getString(2)), headingPath, content);
                inputs.put(byId ? chunks.getString(1) : content, input);
            }
        }

        return inputs;
    }
}
