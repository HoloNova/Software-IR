package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class LibraryCountReadBudgetTest {
    private static final String COUNT = "SELECT COUNT ( * ) AS total FROM `book`";
    private static final String PAGE = "SELECT id, isbn, title, author, publisher FROM `book` ORDER BY id ASC LIMIT ? OFFSET ?";
    private static final String COPIES = "SELECT `book_id` AS count_key, COUNT ( * ) AS count_value FROM `copy`"
            + " WHERE ( `book_id` IN (...) AND status = ? ) GROUP BY `book_id`";

    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aCountedPageRequiresRootTotalPlusRowsPlusOneChildAggregate(boolean quoted) throws Exception {
        var reads = List.of(COUNT, PAGE, COPIES, "SELECT @@SESSION.transaction_read_only");
        if (!quoted) reads = reads.stream().map(sql -> sql.replace("`", "")).toList();
        var harness = harness();
        LibraryCountChecks.checkPageBudget(harness, new MysqlRuntimeTrace.Trace(reads.size(), reads, reads, 1, 1));
        assertTrue(harness.passed(), harness.report());
    }

    @ParameterizedTest
    @MethodSource("invalidReads")
    void missingDuplicatedOrUnexpectedBusinessReadsCannotPass(List<String> reads) throws Exception {
        var harness = harness();
        LibraryCountChecks.checkPageBudget(harness, new MysqlRuntimeTrace.Trace(reads.size(), reads, reads, 1, 1));
        assertFalse(harness.passed(), reads.toString());
    }

    static Stream<List<String>> invalidReads() {
        return Stream.of(
                List.of(PAGE, COPIES),
                List.of(COUNT, COPIES),
                List.of(COUNT, PAGE),
                List.of(COUNT, COUNT, COPIES),
                List.of(PAGE, PAGE, COPIES),
                List.of(COUNT, PAGE, COPIES, COPIES),
                List.of(COUNT, PAGE, COPIES, "SELECT * FROM loan"),
                List.of(COUNT, PAGE, "SELECT * FROM copy WHERE book_id = ?"),
                List.of(COUNT, PAGE, COPIES, "SELECT @@unknown_variable"));
    }

    @Test void actualMappedSqlFixtureAlsoMeetsTheBudget() throws Exception {
        var sql = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                ConformanceFixtures.readResource("/conformance/mysql/library-count-reads.json"));
        var reads = List.of(sql.path("rootTotal").asText(), sql.path("rootPage").asText(), sql.path("children").asText());
        var harness = harness();
        LibraryCountChecks.checkPageBudget(harness, new MysqlRuntimeTrace.Trace(reads.size(), reads, reads, 1, 1));
        assertTrue(harness.passed(), harness.report());
    }

    @Test void fullTraceEvidenceIsWrittenInAColdDirectory() throws Exception {
        var reads = List.of(COUNT, PAGE, COPIES);
        var harness = harness();
        assertFalse(java.nio.file.Files.exists(harness.evidenceRoot()));
        LibraryCountChecks.checkPageBudget(harness, new MysqlRuntimeTrace.Trace(3, reads, reads, 1, 1));
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                harness.evidenceRoot().resolve("library-count-page-trace.json").toFile());
        assertEquals(3, evidence.path("selectCount").asInt());
        assertEquals(3, evidence.path("rawStatements").size());
        assertEquals(COPIES, evidence.path("copyAggregates").get(0).asText());
        assertEquals(0, evidence.path("unexpected").size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void detailRequiresExactlyOneRootAndOneChildQuery(boolean extraRead) throws Exception {
        var reads = extraRead ? List.of(PAGE, COPIES, COPIES) : List.of(PAGE, COPIES);
        var harness = harness();
        LibraryCountReadBudget.check(harness, new MysqlRuntimeTrace.Trace(reads.size(), reads, reads, 1, 1), false);
        assertEquals(!extraRead, harness.passed(), harness.report());
    }

    @Test void incompleteDigestCaptureCannotClaimTheBudgetWasMet() throws Exception {
        var reads = List.of(COUNT, PAGE, COPIES);
        var harness = harness();
        LibraryCountChecks.checkPageBudget(harness, new MysqlRuntimeTrace.Trace(4, reads, reads, 1, 1));
        assertFalse(harness.passed());
    }

    private BusinessSliceHarness harness() {
        return BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"), root.resolve("evidence"));
    }
}
