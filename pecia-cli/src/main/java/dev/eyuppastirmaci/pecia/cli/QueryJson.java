package dev.eyuppastirmaci.pecia.cli;

import dev.eyuppastirmaci.pecia.config.SearchMode;
import dev.eyuppastirmaci.pecia.content.ChunkId;
import dev.eyuppastirmaci.pecia.content.LineRange;
import dev.eyuppastirmaci.pecia.diagnostic.Warning;
import dev.eyuppastirmaci.pecia.index.FileWalker;
import dev.eyuppastirmaci.pecia.search.FusedRanks;
import dev.eyuppastirmaci.pecia.search.MatchRange;
import dev.eyuppastirmaci.pecia.search.QueryNotice;
import dev.eyuppastirmaci.pecia.search.QueryResult;
import dev.eyuppastirmaci.pecia.search.SearchHit;
import dev.eyuppastirmaci.pecia.storage.model.EmbeddingCoverage;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/** Builds the JSON document of a query: its modes, notes, and ranked hits, or why it failed. */
final class QueryJson {

    private QueryJson() {}

    /** Describes a successful query. */
    static Json.JsonObject result(String query, int limit, QueryResult result) {
        List<Object> hits = new ArrayList<>();

        for (SearchHit hit : result.hits()) {
            hits.add(hit(hits.size() + 1, hit));
        }

        return document(query, limit)
                .put("requestedMode", mode(result.requestedMode()))
                .put("effectiveMode", mode(result.effectiveMode()))
                .put(
                        "fallback",
                        result.fallback()
                                .map(fallback -> new Json.JsonObject()
                                        .put("reason", fallback.reason().name())
                                        .put("message", fallback.message()))
                                .orElse(null))
                .put("notices", result.notices().stream().map(QueryJson::notice).toList())
                .put("queryShape", result.queryShape().map(Enum::name).orElse(null))
                .put("embeddingCoverage", coverage(result.embeddingCoverage()))
                .put("warnings", WarningPrinter.json(result.warnings()))
                .put("hits", hits)
                .put("error", null);
    }

    /** Describes a query that failed; nothing about its modes or hits is known. */
    static Json.JsonObject failure(String query, int limit, List<Warning> warnings, String reason, String message) {
        return document(query, limit)
                .put("requestedMode", null)
                .put("effectiveMode", null)
                .put("fallback", null)
                .put("notices", List.of())
                .put("queryShape", null)
                .put("embeddingCoverage", null)
                .put("warnings", WarningPrinter.json(warnings))
                .put("hits", null)
                .put("error", new Json.JsonObject().put("reason", reason).put("message", message));
    }

    private static Json.JsonObject document(String query, int limit) {
        return new Json.JsonObject()
                .put("schemaVersion", Json.SCHEMA_VERSION)
                .put("command", "query")
                .put("query", query)
                .put("limit", limit);
    }

    private static Json.JsonObject hit(int rank, SearchHit hit) {
        Json.JsonObject lines = hit.sourceLocation() instanceof LineRange range
                ? new Json.JsonObject().put("start", range.startLine()).put("end", range.endLine())
                : null;

        return new Json.JsonObject()
                .put("rank", rank)
                .put("path", FileWalker.portablePath(hit.sourcePath()))
                .put("documentType", hit.documentType().name())
                .put("lines", lines)
                .put("chunkIndex", hit.chunkIndex())
                .put("stableId", hit.stableId().map(ChunkId::value).orElse(null))
                .put("headingPath", hit.metadata().headingPath())
                .put(
                        "score",
                        new Json.JsonObject()
                                .put("kind", hit.score().kind().name())
                                .put("value", hit.score().value()))
                .put("fusedRanks", hit.fusedRanks().map(QueryJson::fusedRanks).orElse(null))
                .put("snippet", hit.snippet())
                .put("matches", hit.matches().stream().map(QueryJson::match).toList());
    }

    private static Json.JsonObject fusedRanks(FusedRanks ranks) {
        return new Json.JsonObject().put("lexical", rank(ranks.lexical())).put("semantic", rank(ranks.semantic()));
    }

    private static Integer rank(OptionalInt rank) {
        return rank.isPresent() ? rank.getAsInt() : null;
    }

    private static Json.JsonObject match(MatchRange match) {
        return new Json.JsonObject().put("start", match.start()).put("end", match.end());
    }

    private static Json.JsonObject notice(QueryNotice notice) {
        return new Json.JsonObject().put("code", notice.code().name()).put("message", notice.message());
    }

    private static Json.JsonObject coverage(EmbeddingCoverage coverage) {
        if (coverage == null) {
            return null;
        }

        return new Json.JsonObject()
                .put("embedded", coverage.embedded())
                .put("stale", coverage.stale())
                .put("missing", coverage.missing());
    }

    /** Spells a mode as {@code --mode} and {@code .pecia.toml} do. */
    private static String mode(SearchMode mode) {
        return mode.configValue();
    }
}
