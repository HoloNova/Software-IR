package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Recognizes only the catalogue fixture's fixed read shapes, not arbitrary SQL. */
final class LibraryCountReadBudget {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOTAL = Pattern.compile("select count\\s*\\(\\s*\\*\\s*\\) as total from book(?: where .+)?");
    private static final String COLUMN = "(?:id|isbn|title|author|publisher)";
    private static final Pattern ROWS = Pattern.compile("select " + COLUMN + "(?:\\s*,\\s*" + COLUMN + ")* from book\\b.*");
    private static final Pattern COPIES = Pattern.compile("select book_id as count_key\\s*,\\s*count\\s*\\(\\s*\\*\\s*\\)"
            + " as count_value from copy where .+ group by book_id");
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(?:join|union|into)\\b");
    private static final Pattern SELECT = Pattern.compile("\\bselect\\b");

    private LibraryCountReadBudget() {}
    private enum Kind { TOTAL, ROWS, COPIES, SESSION, UNEXPECTED }
    private record Read(String sql, Kind kind) {}

    static void check(BusinessSliceHarness harness, MysqlRuntimeTrace.Trace trace, boolean page) throws IOException {
        var reads = trace.selectDigests().stream().map(sql -> new Read(sql, classify(sql))).toList();
        var totals = ofKind(reads, Kind.TOTAL);
        var rows = ofKind(reads, Kind.ROWS);
        var copies = ofKind(reads, Kind.COPIES);
        var sessions = ofKind(reads, Kind.SESSION);
        var unexpected = ofKind(reads, Kind.UNEXPECTED);
        String filename = "library-count-" + (page ? "page" : "detail") + "-trace.json";
        Files.createDirectories(harness.evidenceRoot());
        JSON.writerWithDefaultPrettyPrinter().writeValue(harness.evidenceRoot().resolve(filename).toFile(), Map.of(
                "selectCount", trace.selectCount(), "selectDigests", trace.selectDigests(), "rawStatements", trace.rawStatements(),
                "transactions", trace.transactions(), "readOnlyTransactions", trace.readOnlyTransactions(),
                "totalReads", totals, "rowReads", rows, "copyAggregates", copies, "sessionProbes", sessions, "unexpected", unexpected));
        harness.check("catalogue " + (page ? "page: total plus rows plus one child aggregate" : "detail: one row plus one child aggregate"),
                trace.selectCount() == reads.size() && totals.size() == (page ? 1 : 0) && rows.size() == 1
                        && copies.size() == 1 && unexpected.isEmpty(),
                "total=" + totals.size() + " rows=" + rows.size() + " child=" + copies.size() + " sessions=" + sessions.size()
                        + " unexpected=" + unexpected.size() + "; complete evidence=" + filename);
    }

    private static Kind classify(String digest) {
        String sql = digest.replace("`", "").strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).stripTrailing();
        if (sql.contains(";") || FORBIDDEN.matcher(sql).find() || SELECT.matcher(sql).results().count() != 1) return Kind.UNEXPECTED;
        if (GroupedSumReadBudget.isSessionProbe(sql)) return Kind.SESSION;
        if (TOTAL.matcher(sql).matches()) return Kind.TOTAL;
        if (ROWS.matcher(sql).matches()) return Kind.ROWS;
        if (COPIES.matcher(sql).matches()) return Kind.COPIES;
        return Kind.UNEXPECTED;
    }

    private static List<String> ofKind(List<Read> reads, Kind kind) {
        return reads.stream().filter(read -> read.kind() == kind).map(Read::sql).toList();
    }
}
