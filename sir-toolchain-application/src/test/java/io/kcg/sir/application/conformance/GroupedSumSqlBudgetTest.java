package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the actual Money IT budget check, without a database or application. */
class GroupedSumSqlBudgetTest {
    private static final String COUNT = "SELECT COUNT ( DISTINCT `category_id` ) AS `total` FROM `summary_entry` "
            + "WHERE ( `owner` = ? AND EXISTS ( SELECT ? FROM `summary_category` WHERE `id` = `summary_entry`.`category_id` ) )";
    private static final String PAGE = "SELECT `category_id` AS `g_key` , COALESCE ( SUM ( `amount` ) , ? ) AS `g_total` "
            + "FROM `summary_entry` WHERE ( `owner` = ? ) GROUP BY `category_id` ORDER BY `category_id` ASC LIMIT ? OFFSET ?";
    private static final String READ_ONLY = "SELECT @@SESSION . `transaction_read_only`";
    // Connector/J initialization projection present in #167, not a read of application data.
    private static final String DRIVER_INIT = """
            SELECT @@SESSION . `auto_increment_increment` AS `auto_increment_increment`,
              @@`character_set_client` AS `character_set_client`, @@`character_set_connection` AS `character_set_connection`,
              @@`character_set_results` AS `character_set_results`, @@`character_set_server` AS `character_set_server`,
              @@`collation_server` AS `collation_server`, @@`collation_connection` AS `collation_connection`,
              @@`init_connect` AS `init_connect`, @@`interactive_timeout` AS `interactive_timeout`, @@`license` AS `license`,
              @@`lower_case_table_names` AS `lower_case_table_names`, @@`max_allowed_packet` AS `max_allowed_packet`,
              @@`net_write_timeout` AS `net_write_timeout`, @@`performance_schema` AS `performance_schema`,
              @@`sql_mode` AS `sql_mode`, @@`system_time_zone` AS `system_time_zone`, @@`time_zone` AS `time_zone`,
              @@`transaction_isolation` AS `transaction_isolation`, @@`wait_timeout` AS `wait_timeout`
            """;
    private static final String TRACE_FILE = "grouped-sum-query-trace.json";
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 5, 12})
    void driverSessionProbesDoNotConsumeTheTwoBusinessReadBudget(int probes) throws Exception {
        var digests = Stream.concat(Stream.of(COUNT, PAGE), Collections.nCopies(probes, DRIVER_INIT).stream()).toList();
        var harness = harness();

        GroupedSumChecks.checkQueryBudget(harness, trace(digests));

        assertTrue(harness.passed(), harness::report);
    }

    @Test void bothObservedDriverProbeFormsAndIsolationReadAreAcceptedTogether() throws Exception {
        var harness = harness();
        var observed = trace(List.of(READ_ONLY, READ_ONLY, DRIVER_INIT, DRIVER_INIT,
                "select @@session.transaction_isolation", COUNT, PAGE));

        GroupedSumChecks.checkQueryBudget(harness, observed);

        assertTrue(harness.passed(), harness::report);
    }

    @Test void exactServerDigestsCapturedFromCi167MeetTheBudget() throws Exception {
        // Run 39921191961, 74bd393: preserve MySQL's DISTINCTROW normalization, not only authored SQL.
        try (var input = getClass().getResourceAsStream("/conformance/mysql/ci-167-grouped-read-digests.json")) {
            assertNotNull(input);
            var digests = new ObjectMapper().readTree(input).findValuesAsText("sql");
            assertEquals(7, digests.size());
            var harness = harness();

            GroupedSumChecks.checkQueryBudget(harness, trace(digests));

            assertTrue(harness.passed(), harness::report);
            var evidence = new ObjectMapper().readTree(harness.evidenceRoot().resolve(TRACE_FILE).toFile());
            assertEquals(1, evidence.path("countReads").size());
            assertEquals(1, evidence.path("pageReads").size());
            assertEquals(5, evidence.path("sessionProbes").size());
            assertEquals(0, evidence.path("unexpectedReads").size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReads")
    void missingRolesExtraBusinessReadsAndUnknownStatementsCannotPass(String label, List<String> digests) throws Exception {
        var harness = harness();

        GroupedSumChecks.checkQueryBudget(harness, trace(digests));

        assertFalse(harness.passed(), () -> label + "\n" + harness.report());
    }

    private static Stream<Arguments> invalidReads() {
        return Stream.of(
                Arguments.of("no reads", List.of()),
                Arguments.of("count without page", List.of(COUNT)),
                Arguments.of("page without count", List.of(PAGE)),
                Arguments.of("two counts", List.of(COUNT, COUNT)),
                Arguments.of("two pages", List.of(PAGE, PAGE)),
                Arguments.of("only session probes", List.of(READ_ONLY, DRIVER_INIT)),
                Arguments.of("N+1 root read", List.of(COUNT, PAGE, "SELECT `id` FROM `summary_entry`")),
                Arguments.of("extra unrelated table read", List.of(COUNT, PAGE, "SELECT `balance` FROM `wallet`")),
                Arguments.of("extra count", List.of(COUNT, PAGE, COUNT)),
                Arguments.of("extra page", List.of(COUNT, PAGE, PAGE)),
                Arguments.of("unknown table-free SELECT", List.of(COUNT, PAGE, "SELECT ?")),
                Arguments.of("unknown system variable", List.of(COUNT, PAGE, "SELECT @@unknown_session_probe")),
                Arguments.of("session projection with table", List.of(COUNT, PAGE, "SELECT @@session.transaction_read_only FROM wallet")),
                Arguments.of("session projection with subquery", List.of(COUNT, PAGE, "SELECT @@session.transaction_read_only, (SELECT id FROM wallet)")),
                Arguments.of("session projection with UNION", List.of(COUNT, PAGE, "SELECT @@session.transaction_read_only UNION SELECT id FROM wallet")),
                Arguments.of("variable-looking string literal", List.of(COUNT, PAGE, "SELECT '@@session.transaction_read_only'")),
                Arguments.of("row count instead of group count", List.of(COUNT.replace("DISTINCT `category_id`", "*"), PAGE)),
                Arguments.of("wrong grouping key", List.of(COUNT, PAGE.replace("GROUP BY `category_id`", "GROUP BY `owner`"))),
                Arguments.of("unbounded group data", List.of(COUNT, PAGE.replace(" LIMIT ? OFFSET ?", ""))),
                Arguments.of("wrong root disguised as a literal", List.of(COUNT, "SELECT 'summary_entry' FROM wallet")));
    }

    @Test void aDigestCounterWithoutMatchingCapturedReadsCannotBeAccepted() throws Exception {
        var harness = harness();
        var observed = new MysqlRuntimeTrace.Trace(7, List.of(COUNT, PAGE), List.of(COUNT, PAGE), 1, 1);

        GroupedSumChecks.checkQueryBudget(harness, observed);

        assertFalse(harness.passed(), harness::report);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completeEvidenceIsKeptEvenWhenTheBudgetFails(boolean valid) throws Exception {
        String longCount = COUNT + " AND (" + "? = ? AND ".repeat(600) + "? = ?)";
        var digests = valid ? List.of(longCount, PAGE) : List.of(longCount, PAGE, "SELECT id FROM wallet");
        String raw = "/* mysql-connector-j */ SELECT 'quoted \\\"value\\\"\n" + "x".repeat(10_000) + "' /* evidence-tail */";
        var observed = new MysqlRuntimeTrace.Trace(digests.size(), digests,
                List.of("SET autocommit=0", raw, "COMMIT"), 1, 1);
        var harness = harness();
        assertFalse(Files.exists(harness.evidenceRoot()));

        GroupedSumChecks.checkQueryBudget(harness, observed);

        assertEquals(valid, harness.passed(), harness::report);
        var evidence = harness.evidenceRoot().resolve(TRACE_FILE);
        assertTrue(Files.isRegularFile(evidence), "full trace must be saved before either verdict");
        var json = new ObjectMapper().readTree(evidence.toFile());
        assertEquals(observed.selectCount(), json.path("selectCount").longValue());
        assertEquals(longCount, json.path("selectDigests").get(0).asText());
        assertEquals(raw, json.path("rawStatements").get(1).asText());
        assertEquals(1, json.path("transactions").longValue());
        assertEquals(1, json.path("readOnlyTransactions").longValue());
        assertTrue(harness.report().contains(TRACE_FILE), harness::report);
    }

    @Test void evidenceWriteErrorsFailTheActualHarnessInsteadOfDroppingTheTrace() throws Exception {
        var harness = harness();
        Files.createDirectories(harness.evidenceRoot().getParent());
        Files.writeString(harness.evidenceRoot(), "a file cannot be the evidence directory");

        assertThrows(java.io.IOException.class,
                () -> harness.verify(() -> GroupedSumChecks.checkQueryBudget(harness, trace(List.of(COUNT, PAGE)))));

        assertFalse(harness.passed(), harness::report);
        assertTrue(harness.report().contains("unexpected error before the run completed"), harness::report);
    }

    private BusinessSliceHarness harness() {
        return BusinessSliceHarnessEvidenceTest.harness(root.resolve("work"), root.resolve("evidence"));
    }

    private static MysqlRuntimeTrace.Trace trace(List<String> digests) {
        return new MysqlRuntimeTrace.Trace(digests.size(), digests, digests, 1, 1);
    }
}
