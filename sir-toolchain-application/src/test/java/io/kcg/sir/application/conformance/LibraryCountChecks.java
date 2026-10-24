package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

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
        checkPageContents(s, JSON.readTree(page.body()), s.rowsOf(o, "book"), available);
        var staff = h.get(s.baseUrl() + "/api/books/1", STAFF);
        s.expectStatus("an authenticated librarian reads the same view", staff, 200);
        s.check("the read view does not depend on who reads it",
                JSON.readTree(staff.body()).path("availableCopies").asLong(-1) == available.getOrDefault(1L, 0L), staff.body());
    }

    static void checkPageContents(BusinessSliceHarness s, JsonNode body, List<String> bookRows, Map<Long, Long> available) {
        var records = body.path("records");
        var expectedIds = bookRows.stream().map(row -> Long.parseLong(row.split("\\|", -1)[0])).sorted().toList();
        var actualIds = java.util.stream.StreamSupport.stream(records.spliterator(), false)
                .map(row -> row.path("id").asLong(-1)).toList();
        s.check("the complete page has exactly the observer's book identities in order",
                records.isArray() && actualIds.equals(expectedIds), "expected=" + expectedIds + ", actual=" + actualIds);
        var total = body.path("total");
        s.check("the catalogue total equals the observer's row count, not its highest identity",
                total.isIntegralNumber() && total.canConvertToLong() && total.asLong() == expectedIds.size(), body.toString());
        Map<Long, Long> reported = new LinkedHashMap<>();
        boolean allPresent = true;
        for (var row : records) {
            var field = row.path("availableCopies");
            allPresent &= field.isIntegralNumber() && field.canConvertToLong()
                    && row.path("id").isIntegralNumber() && row.path("id").canConvertToLong();
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
    }

    /** A page of N books must cost one child aggregate, not N. */
    private static void budget(BusinessSliceHarness s, HttpAssertionClient h) throws Exception {
        final HttpAssertionClient.Response page;
        final MysqlRuntimeTrace.Trace total;
        try (var trace = s.openRuntimeTrace()) {
            page = h.get(s.baseUrl() + "/api/books?page=1&size=50", Map.of());
            total = trace.stop();
        }
        checkPageBudget(s, total);
        s.check("the measured page still succeeded", page.statusCode() == 200, String.valueOf(page.statusCode()));

        final MysqlRuntimeTrace.Trace detail;
        final HttpAssertionClient.Response response;
        try (var trace = s.openRuntimeTrace()) {
            response = h.get(s.baseUrl() + "/api/books/1", Map.of());
            detail = trace.stop();
        }
        s.expectStatus("the measured detail still succeeded", response, 200);
        LibraryCountReadBudget.check(s, detail, false);
    }

    static void checkPageBudget(BusinessSliceHarness s, MysqlRuntimeTrace.Trace total) throws Exception {
        LibraryCountReadBudget.check(s, total, true);
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
