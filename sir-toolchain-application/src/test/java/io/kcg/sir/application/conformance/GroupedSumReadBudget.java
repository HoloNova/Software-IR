package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Classifies this fixed fixture's server digests; deliberately not a general SQL parser. */
final class GroupedSumReadBudget {
    private static final String TRACE_FILE = "grouped-sum-query-trace.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String IDENTIFIER = "[a-z_][a-z0-9_]*";
    private static final Pattern SESSION_ITEM = Pattern.compile(
            "@@(?:(?:session|global|local)\\s*\\.\\s*)?(" + IDENTIFIER + ")(?:\\s+as\\s+" + IDENTIFIER + ")?");
    private static final Set<String> DRIVER_VARIABLES = Set.of(
            "transaction_read_only", "transaction_isolation", "auto_increment_increment",
            "character_set_client", "character_set_connection", "character_set_results", "character_set_server",
            "collation_server", "collation_connection", "init_connect", "interactive_timeout", "license",
            "lower_case_table_names", "max_allowed_packet", "net_write_timeout", "performance_schema",
            "sql_mode", "system_time_zone", "time_zone", "wait_timeout");
    private static final Pattern COUNT = Pattern.compile(
            "^select\\s+count\\s*\\(\\s*distinct(?:row)?\\s+category_id\\s*\\)\\s+as\\s+total\\s+from\\s+summary_entry\\b.*$");
    private static final Pattern DATA = Pattern.compile(
            "^select\\s+category_id\\s+as\\s+g_key\\s*,\\s*coalesce\\s*\\(\\s*sum\\s*\\(\\s*amount\\s*\\)"
            + "\\s*,\\s*(?:\\?|0)\\s*\\)\\s+as\\s+g_total\\s+from\\s+summary_entry\\b.*"
            + "\\bgroup\\s+by\\s+category_id\\s+order\\s+by\\s+category_id\\s+asc"
            + "\\s+limit\\s+(?:\\?|[0-9]+)(?:\\s+offset\\s+(?:\\?|[0-9]+))?$");
    private static final Pattern COUNT_PAGE_CLAUSE = Pattern.compile("\\b(?:group\\s+by|order\\s+by|limit|offset)\\b");
    private static final Pattern UNION = Pattern.compile("\\bunion\\b");
    private GroupedSumReadBudget() {}

    private enum Kind { COUNT, DATA, SESSION, UNEXPECTED }
    private record Read(String sql, Kind kind) {}

    static void check(BusinessSliceHarness harness, MysqlRuntimeTrace.Trace trace) throws IOException {
        var reads = trace.selectDigests().stream().map(sql -> new Read(sql, classify(sql))).toList();
        var counts = ofKind(reads, Kind.COUNT);
        var pages = ofKind(reads, Kind.DATA);
        var sessions = ofKind(reads, Kind.SESSION);
        var unexpected = ofKind(reads, Kind.UNEXPECTED);
        saveTrace(harness, trace, counts, pages, sessions, unexpected);
        harness.check("group page executes exactly count plus current page",
                trace.selectCount() == reads.size() && counts.size() == 1 && pages.size() == 1 && unexpected.isEmpty(),
                "allSelects=" + trace.selectCount() + " captured=" + reads.size() + " count=" + counts.size()
                + " page=" + pages.size() + " sessionProbes=" + sessions.size() + " unexpected=" + unexpected.size()
                + "; complete evidence=" + TRACE_FILE);
    }

    private static Kind classify(String digest) {
        String sql = digest.replace("`", "").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).stripTrailing();
        if (sql.contains(";") || UNION.matcher(sql).find()) return Kind.UNEXPECTED;
        if (isSessionProbe(sql)) return Kind.SESSION;
        if (COUNT.matcher(sql).matches() && !COUNT_PAGE_CLAUSE.matcher(sql).find()) return Kind.COUNT;
        if (DATA.matcher(sql).matches()) return Kind.DATA;
        return Kind.UNEXPECTED;
    }

    /** Full projection must consist only of known @@ variables, never a table, subquery or expression. */
    private static boolean isSessionProbe(String sql) {
        if (!sql.startsWith("select ")) return false;
        return List.of(sql.substring("select ".length()).split(",", -1)).stream().allMatch(item -> {
            var match = SESSION_ITEM.matcher(item.strip());
            return match.matches() && DRIVER_VARIABLES.contains(match.group(1));
        });
    }

    private static List<String> ofKind(List<Read> reads, Kind kind) {
        return reads.stream().filter(read -> read.kind() == kind).map(Read::sql).toList();
    }

    private static void saveTrace(BusinessSliceHarness harness, MysqlRuntimeTrace.Trace trace,
                                  List<String> counts, List<String> pages, List<String> sessions,
                                  List<String> unexpected) throws IOException {
        Files.createDirectories(harness.evidenceRoot());
        JSON.writerWithDefaultPrettyPrinter().writeValue(harness.evidenceRoot().resolve(TRACE_FILE).toFile(), Map.of(
                "selectCount", trace.selectCount(), "selectDigests", trace.selectDigests(),
                "rawStatements", trace.rawStatements(), "transactions", trace.transactions(),
                "readOnlyTransactions", trace.readOnlyTransactions(), "countReads", counts,
                "pageReads", pages, "sessionProbes", sessions, "unexpectedReads", unexpected));
        harness.reportLine("G8 grouped SQL budget: full server digests, raw statements and classification in " + TRACE_FILE);
    }
}
