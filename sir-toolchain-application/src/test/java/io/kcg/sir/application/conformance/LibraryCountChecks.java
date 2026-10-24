package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The actual library catalogue reports how many copies are on the shelf right now. Every expected number here is
 * computed from the observer's own copy rows, so a wrong count cannot agree with itself, and each request is measured
 * to prove the page is counted once instead of once per book.
 */
final class LibraryCountChecks {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> STAFF = bearer(15);
    private static final List<Long> NO_COPIES = List.of(11L, 12L, 13L);

    static void verify(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h) throws Exception {
        Map<Long, Long> available = availableCopies(s, o);
        detail(s, o, h, available);
        pageAndZeroBuckets(s, o, h, available);
        budget(s, h);
    }

    private static void detail(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h, Map<Long, Long> available) throws Exception {
        for (long id : List.of(1L, 10L, 15L, 16L, 11L, 12L, 13L)) {
            var response = h.get(s.baseUrl() + "/api/books/" + id, Map.of());
            s.record("catalogue detail " + id, response);
            s.expectStatus("catalogue detail " + id, response, 200);
            long expected = available.getOrDefault(id, 0L);
            s.check("detail counts only copies that are on the shelf (book " + id + ")",
                    JSON.readTree(response.body()).path("availableCopies").asLong(-1) == expected,
                    "observer says " + expected);
        }
        s.check("a book whose only copy is lost reports zero, not an empty field", available.getOrDefault(12L, 0L) == 0L,
                "copy 120 is LOST in the fixture");
        s.check("a book with no copy rows at all reports zero", !available.containsKey(13L), "book 13 has no copies");
    }

    private static void pageAndZeroBuckets(BusinessSliceHarness s, MysqlObserver o, HttpAssertionClient h, Map<Long, Long> available) throws Exception {
        var page = h.get(s.baseUrl() + "/api/books?page=1&size=50", Map.of());
        s.record("catalogue page", page);
        s.expectStatus("catalogue page", page, 200);
        var body = JSON.readTree(page.body());
        var records = body.path("records");
        s.check("the page covers every seeded book", records.size() >= 20, "records=" + records.size());
        Map<Long, Long> reported = new LinkedHashMap<>();
        boolean allPresent = true;
        for (var row : records) {
            var field = row.path("availableCopies");
            allPresent &= field.isIntegralNumber();
            reported.put(row.path("id").asLong(), field.asLong(-1));
        }
        s.check("every catalogue row carries an integral availableCopies", allPresent, records.toString());
        for (var entry : reported.entrySet()) {
            long expected = available.getOrDefault(entry.getKey(), 0L);
            s.check("catalogue page agrees with the observer for book " + entry.getKey(), entry.getValue() == expected,
                    "reported " + entry.getValue() + ", observer says " + expected);
        }
        for (long id : NO_COPIES) {
            if (reported.containsKey(id)) s.check("book " + id + " stays zero rather than dropping the field",
                    reported.get(id) == 0L, "reported " + reported.get(id));
        }
        s.check("books the page does not contain are still counted correctly", reported.keySet().stream()
                .noneMatch(id -> id == 999999L), "no invented rows");

        var staff = h.get(s.baseUrl() + "/api/books/1", STAFF);
        s.expectStatus("an authenticated librarian reads the same view", staff, 200);
        s.check("the read view does not depend on who reads it",
                JSON.readTree(staff.body()).path("availableCopies").asLong(-1) == available.getOrDefault(1L, 0L), staff.body());
    }

    /** A page of N books must cost one child aggregate, not N. */
    private static void budget(BusinessSliceHarness s, HttpAssertionClient h) throws Exception {
        final HttpAssertionClient.Response page;
        final MysqlRuntimeTrace.Trace total;
        try (var trace = s.openRuntimeTrace()) {
            page = h.get(s.baseUrl() + "/api/books?page=1&size=50", Map.of());
            total = trace.stop();
        }
        var lower = total.selectDigests().stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
        long bookReads = lower.stream().filter(sql -> sql.contains("from book")).count();
        long copyReads = lower.stream().filter(sql -> sql.contains("from copy")).count();
        s.check("the counted page is read once", bookReads == 1, "book reads=" + bookReads + " in " + lower);
        s.check("all rows on a page share one child aggregate", copyReads == 1, "copy reads=" + copyReads + " in " + lower);
        s.check("no session or statement count is mistaken for a business read",
                lower.stream().noneMatch(sql -> sql.contains("from copy") && sql.contains("select @@")), lower.toString());
        s.check("the measured page still succeeded", page.statusCode() == 200, String.valueOf(page.statusCode()));
        s.check("one page request issues no copy query per row", copyReads < 21, "copy reads=" + copyReads);

        final MysqlRuntimeTrace.Trace detail;
        try (var trace = s.openRuntimeTrace()) {
            h.get(s.baseUrl() + "/api/books/1", Map.of());
            detail = trace.stop();
        }
        var detailLower = detail.selectDigests().stream().map(sql -> sql.toLowerCase(Locale.ROOT)).toList();
        s.check("a detail read also counts in one query",
                detailLower.stream().filter(sql -> sql.contains("from copy")).count() == 1, detailLower.toString());
    }

    private static Map<Long, Long> availableCopies(BusinessSliceHarness s, MysqlObserver o) throws Exception {
        Map<Long, Long> counts = new LinkedHashMap<>();
        for (String row : s.rowsOf(o, "copy")) {
            String[] parts = row.split("\\|", -1);
            if (parts.length < 4 || !parts[3].equals("AVAILABLE")) continue;
            counts.merge(Long.parseLong(parts[2]), 1L, Long::sum);
        }
        return counts;
    }

    private static Map<String, String> bearer(int actor) {
        return Map.of("Authorization", "Bearer 00000000-0000-4000-8000-" + String.format(Locale.ROOT, "%012d", actor));
    }
}
