package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the HTTP evidence checks without starting the library or requiring a database. */
class LibraryCountChecksTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Long, Long> AVAILABLE = Map.of(1L, 4L, 15L, 1L);
    @TempDir Path root;

    @Test void theActualSparseSeedIsComparedByRowIdentityNotByItsHighestId() throws Exception {
        var books = seededBooks();
        var harness = harness();
        LibraryCountChecks.checkPageContents(harness, page(books), books, AVAILABLE);
        assertTrue(harness.passed(), harness.report());
    }

    @Test void anActuallyEmptyCatalogueIsAValidCompletePage() {
        var harness = harness();
        LibraryCountChecks.checkPageContents(harness, page(List.of()), List.of(), Map.of());
        assertTrue(harness.passed(), harness.report());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "duplicate", "invented", "wrong-total", "reversed", "missing-count", "wrong-count"})
    void rowsAndCountsCannotSilentlyDivergeFromTheObserver(String damage) throws Exception {
        var books = seededBooks();
        var body = page(books);
        var records = body.withArray("records");
        switch (damage) {
            case "missing" -> records.remove(0);
            case "duplicate" -> records.add(records.get(0).deepCopy());
            case "invented" -> ((ObjectNode) records.get(0)).put("id", 999999L);
            case "wrong-total" -> body.put("total", books.size() + 1);
            case "reversed" -> {
                var rows = new ArrayList<ObjectNode>();
                records.forEach(row -> rows.add((ObjectNode) row.deepCopy()));
                records.removeAll();
                rows.reversed().forEach(records::add);
            }
            case "missing-count" -> ((ObjectNode) records.get(0)).remove("availableCopies");
            case "wrong-count" -> ((ObjectNode) records.get(0)).put("availableCopies", 999L);
            default -> throw new AssertionError(damage);
        }
        var harness = harness();
        LibraryCountChecks.checkPageContents(harness, body, books, AVAILABLE);
        assertFalse(harness.passed(), damage + " must fail independently of a magic row-count threshold");
    }

    /** Read only the identity of each explicit book tuple from the actual isolated seed, not MAX(id). */
    private static List<String> seededBooks() throws Exception {
        String seed = ConformanceFixtures.readResource("/conformance/mysql/library-seed.sql");
        var ids = Pattern.compile("(?is)INSERT INTO book\\s*\\([^;]*?\\)\\s*VALUES\\s*(.*?);")
                .matcher(seed).results().flatMap(insert -> Pattern.compile("\\((\\d+)\\s*,").matcher(insert.group(1)).results())
                .map(row -> Long.parseLong(row.group(1))).sorted(Comparator.naturalOrder()).toList();
        assertFalse(ids.isEmpty(), "the real seed must actually provide catalogue rows");
        return ids.stream().map(id -> id + "|fixture").toList();
    }

    private static ObjectNode page(List<String> bookRows) {
        var body = JSON.createObjectNode().put("total", bookRows.size());
        var rows = body.putArray("records");
        for (String book : bookRows) {
            long id = Long.parseLong(book.split("\\|", -1)[0]);
            rows.addObject().put("id", id).put("availableCopies", AVAILABLE.getOrDefault(id, 0L));
        }
        return body;
    }

    private BusinessSliceHarness harness() {
        return BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"), root.resolve("evidence"));
    }
}
