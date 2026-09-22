package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Business conformance for the Q9 single-file query slice: the SIR compiler's own output, built and
 * run against a real MySQL server, asserted over HTTP and independently over JDBC.
 *
 * <p>This is <em>not</em> the ADR-017 qualification matrix. It does not execute the five Apply
 * scenarios and it never claims {@code QUALIFIED}; it reports {@code PASSED}, {@code FAILED}, or
 * {@code NOT_RUN} for one business slice. Its purpose is to close the gap that unit tests, a successful
 * generated-project compilation, and the change-apply harness cannot close: that a compiled course
 * query actually filters, pages, orders, and projects correctly against a real database, over a real
 * HTTP route.
 *
 * <h2>What the harness owns versus what the product owns</h2>
 *
 * <p>The schema, its DDL, and its seed rows are <em>test fixtures</em>. The generated application has no
 * INITIALIZE or UPDATE capability (that is G3), so it cannot create tables or seed data, and this
 * harness never pretends otherwise: the evidence records {@code schemaSource = TEST_FIXTURE_DDL} and
 * {@code productInitializeImplemented = false}. No assertion here proves a product-side schema
 * lifecycle. What the assertions do prove is that the compiled workflow produces SQL that selects
 * exactly the declared rows from a real schema, through real HTTP transport, without mutating them.
 *
 * <h2>Opt-in</h2>
 *
 * <p>The test is opt-in. Without a complete reference environment it prints {@code NOT_RUN} and returns
 * <em>before</em> any side effect: no database is created, no directory is allocated, no process is
 * started. Invocation:
 *
 * <pre>{@code
 * source /root/kcg-conformance/env.sh
 * mvn -pl sir-toolchain-application \
 *   -Dtest=io.kcg.sir.application.conformance.QuerySliceBusinessConformanceIT \
 *   -Dkcg.query-conformance.enabled=true \
 *   -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
 *   -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
 *   -Dkcg.conformance.maven-executable=/usr/bin/mvn \
 *   -Dkcg.conformance.maven-repo=/root/.m2/repository \
 *   -Dmaven.repo.local=/root/.m2/repository \
 *   test
 * }</pre>
 *
 * <p>Credentials come only from the environment whitelist ({@code KCG_CONF_CONTROL_JDBC_URL},
 * {@code KCG_CONF_CONTROL_USERNAME}, {@code KCG_CONF_CONTROL_PASSWORD}, {@code KCG_CONF_RUNTIME_USERNAME},
 * {@code KCG_CONF_RUNTIME_PASSWORD}). The runtime JDBC URL is derived from the control endpoint and the
 * validated schema name, never supplied directly.
 */
class QuerySliceBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Q9 query slice",
            "/valid/course-catalog.sir",
            "/conformance/mysql/course-catalog-ddl.sql",
            "/conformance/mysql/course-catalog-seed.sql",
            "course",
            "/api/search-courses?keyword=Intro&page=1&size=2",
            "query-slice-report.txt",
            "query-slice",
            "kcg.query-conformance.enabled");

    /** The generated route for {@code capability SearchCourses} with {@code expose query}. */
    private static final String SEARCH_ROUTE = "/api/search-courses";

    /** The number of rows the fixture seed inserts. */
    private static final int SEEDED_ROWS = 7;

    /** The number of seeded rows whose name contains the plain keyword "Intro". */
    private static final int INTRO_ROWS = 4;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void compiledCourseQueryPagesFiltersAndProjectsAgainstRealMysql() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[QUERY-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[QUERY-CONFORMANCE] The Q9 business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[QUERY-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[QUERY-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[QUERY-CONFORMANCE] The Q9 business slice was NOT verified.");
            return;
        }

        BusinessSliceHarness slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            execute(slice);
        } catch (java.sql.SQLException unreachable) {
            // The harness's own connection to the reference database is infrastructure, not the
            // product under test: an unreachable server yields no verdict rather than a failure, and
            // the reason is recorded in the evidence.
            slice.setNotRunReason("the reference database became unreachable: "
                    + BusinessSliceHarness.describe(unreachable));
        } finally {
            slice.teardown();
        }

        slice.writeReport();
        if (slice.notRunReason() != null) {
            System.out.println("[QUERY-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[QUERY-CONFORMANCE] The Q9 business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "query slice business conformance FAILED:\n" + slice.report());
    }

    /** Runs the slice end to end. Every failure is recorded; none is swallowed. */
    private void execute(BusinessSliceHarness slice) throws Exception {
        MysqlRuntimeJdbcUrl runtimeUrl = slice.runtimeUrl();
        slice.initializeRedaction(runtimeUrl);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null) {
            return;
        }

        if (slice.compileAndMaterialize().isEmpty()) {
            return;
        }

        java.nio.file.Path jar = slice.buildAndLocateJar(runtimeUrl);
        if (jar == null) {
            return;
        }

        if (!slice.startAndWaitUntilReady(jar)) {
            return;
        }

        slice.setHttpStatus("READY (200 from " + SEARCH_ROUTE + ")");
        try (MysqlObserver observer = slice.openObserver(runtimeUrl)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            slice.check("the fixture seed is present in the owned schema", slice.rowsBeforeSize() == SEEDED_ROWS,
                    "seededRowCount=" + slice.rowsBeforeSize());
            assertQueryBehaviour(slice);
            slice.setRowsAfter(slice.rowsNow(observer));
            slice.check("the read path never mutated the table",
                    slice.rowsBefore().equals(slice.rowsAfter()),
                    "rowsBefore=" + slice.rowsBeforeSize() + ", rowsAfter=" + slice.rowsAfterSize());
        }
    }

    /**
     * The business assertions, each one a fresh independent HTTP request, cross-checked against an
     * independent JDBC connection.
     */
    private void assertQueryBehaviour(BusinessSliceHarness slice) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();

        // A1: the first page of a paged, ordered, projected query.
        HttpAssertionClient.Response page1 =
                http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=Intro&page=1&size=2", Map.of());
        slice.record("A1 page=1 size=2", page1);
        if (slice.expectStatus("page 1 of the keyword search returns 200", page1, 200)) {
            slice.expectField("page 1 reports the total matching rows", page1, "total", "4");
            slice.expectFields("page 1 echoes the requested page and size", page1, Map.of("page", "1", "size", "2"));
            slice.expectRecords("page 1 returns the first two courses in declared order", page1, List.of(
                    "CS101|Intro to Algorithms 100%|30",
                    "CS102|Intro to 9_9 Patterns|20"));
            slice.check("the response is the declared projection, not the entity",
                    !page1.body().contains("\"id\"") && !page1.body().contains("description")
                            && page1.body().contains("\"code\"") && page1.body().contains("\"capacity\""),
                    "body=" + page1.body());
        }

        // A2: the second page must advance, not repeat the first.
        HttpAssertionClient.Response page2 =
                http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=Intro&page=2&size=2", Map.of());
        slice.record("A2 page=2 size=2", page2);
        if (slice.expectStatus("page 2 returns 200", page2, 200)) {
            slice.expectRecords("page 2 returns the following two courses in declared order", page2, List.of(
                    "CS103|Intro to Databases|40",
                    "CS104|Intro to Compilers|45"));
            slice.expectField("page 2 keeps the unpaged total", page2, "total", "4");
        }

        // A3: a page past the end is an empty page, not an error and not page 1 again.
        HttpAssertionClient.Response page3 =
                http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=Intro&page=3&size=2", Map.of());
        slice.record("A3 page=3 size=2", page3);
        if (slice.expectStatus("a page beyond the data returns 200", page3, 200)) {
            slice.expectRecords("the page beyond the data carries no records", page3, List.of());
            slice.expectField("the page beyond the data still reports the total", page3, "total", "4");
        }

        // A4: omitting the page parameters uses the fixed defaults rather than an unbounded read.
        HttpAssertionClient.Response defaulted = http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=Intro", Map.of());
        slice.record("A4 keyword only (page and size omitted)", defaulted);
        if (slice.expectStatus("the search without page parameters returns 200", defaulted, 200)) {
            slice.expectFields("the omitted page and size fall back to the fixed defaults", defaulted,
                    Map.of("page", "1", "size", "20"));
            slice.check("every matching row fits on the default page",
                    BusinessSliceHarness.records(defaulted.body()).size() == INTRO_ROWS, "records=" + BusinessSliceHarness.records(defaulted.body()));
        }

        // A5: the literal-match sensitivity probe. Unescaped, '%' would match every row.
        HttpAssertionClient.Response percent = http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=%25", Map.of());
        slice.record("A5 keyword=%25", percent);
        if (slice.expectStatus("a literal '%' keyword returns 200", percent, 200)) {
            slice.expectRecords("a literal '%' matches only the courses containing '%', not every row", percent, List.of(
                    "ART101|Art of 100% Painting|15",
                    "CS101|Intro to Algorithms 100%|30"));
            slice.expectField("the '%' search total counts only literal matches", percent, "total", "2");
        }

        // A6: the underscore probe. Unescaped, '_' would also match MATH202's '919'.
        HttpAssertionClient.Response underscore = http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=9_9", Map.of());
        slice.record("A6 keyword=9_9", underscore);
        if (slice.expectStatus("a keyword containing '_' returns 200", underscore, 200)) {
            slice.expectRecords("a literal '_' matches only '9_9' and not '919'", underscore, List.of(
                    "CS102|Intro to 9_9 Patterns|20",
                    "MATH201|Math 9_9 Advanced|35"));
        }

        // A7: the reported total is checked against rows read by an independent connection.
        int databaseMatches = 0;
        for (String row : slice.rowsBefore()) {
            if (row.contains("Intro")) {
                databaseMatches++;
            }
        }
        slice.check("the independently counted database matches agree with the reported totals",
                databaseMatches == INTRO_ROWS, "databaseMatches=" + databaseMatches);

        // A8: invalid paging is rejected with 400 and returns no rows at all.
        for (String invalid : List.of("page=0&size=2", "page=10001&size=2", "page=1&size=0", "page=1&size=101")) {
            HttpAssertionClient.Response rejected =
                    http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=Intro&" + invalid, Map.of());
            slice.record("A8 invalid paging (" + invalid + ")", rejected);
            slice.expectStatus("invalid paging (" + invalid + ") returns 400", rejected, 400);
            slice.check("the rejected request (" + invalid + ") returns no rows",
                    !rejected.body().contains("CS101") && !rejected.body().contains("\"records\""),
                    "body=" + rejected.body());
        }

        // A9: the generated input validation still guards the query input.
        HttpAssertionClient.Response blankKeyword = http.get(slice.baseUrl() + SEARCH_ROUTE + "?keyword=%20%20", Map.of());
        slice.record("A9 blank keyword", blankKeyword);
        slice.expectStatus("a blank keyword is rejected with 400", blankKeyword, 400);
        HttpAssertionClient.Response missingKeyword = http.get(slice.baseUrl() + SEARCH_ROUTE, Map.of());
        slice.record("A9 missing keyword", missingKeyword);
        slice.expectStatus("a missing keyword is rejected with 400", missingKeyword, 400);
    }

}
