package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Business conformance for the Q11 course-enrollment relation slice: existence filtering over a
 * related table, a nested projection read in batches, and the number of reads each response costs —
 * asserted over real HTTP against a real MySQL server, with the statement count and the literal SQL
 * taken from the server itself.
 *
 * <p>This is <em>not</em> the ADR-017 qualification matrix and it never claims {@code QUALIFIED}; it
 * reports {@code PASSED}, {@code FAILED}, or {@code NOT_RUN} for one business slice. It closes the
 * gap that unit tests, a lowering contract, and a compiling generated project cannot: that the
 * compiled query really selects the roots the declaration describes, once each, and reads their
 * related rows in a bounded number of statements.
 *
 * <h2>What each assertion is decisive about</h2>
 *
 * <p>The seed is built so that a join-based read and an existence-semantics read disagree on every
 * number the page reports: course 1 has two matching enrollments, so a join would serve it twice,
 * raise the total by one, and pull course 3 (whose only enrollment is cancelled) into the page. The
 * statement counts are taken from {@code performance_schema} digests scoped to the owned schema and
 * cross-checked against the general log of the account the application runs as, so the reads per
 * response are measured on the database rather than inferred from the generated source.
 *
 * <h2>Opt-in</h2>
 *
 * <p>Without a complete reference environment the test prints {@code NOT_RUN} and returns before any
 * side effect. Invocation:
 *
 * <pre>{@code
 * source /root/kcg-conformance/env.sh
 * docker start kcg-conformance-mysql
 * mvn -pl sir-toolchain-application \
 *   -Dtest=io.kcg.sir.application.conformance.RelationSliceBusinessConformanceIT \
 *   -Dkcg.relation-conformance.enabled=true \
 *   -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
 *   -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
 *   -Dkcg.conformance.maven-executable=/usr/bin/mvn \
 *   -Dkcg.conformance.maven-repo=/root/.m2/repository \
 *   -Dmaven.repo.local=/root/.m2/repository \
 *   test
 * }</pre>
 */
class RelationSliceBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Q11 course enrollment relation slice",
            "/valid/course-enrollment.sir",
            "/conformance/mysql/course-enrollment-ddl.sql",
            "/conformance/mysql/course-enrollment-seed.sql",
            "course",
            "/api/search-course-enrollments?page=1&size=10",
            "relation-slice-report.txt",
            "relation-slice",
            "kcg.relation-conformance.enabled");

    /** The generated route for {@code capability SearchCourseEnrollments} with {@code expose query}. */
    private static final String SEARCH_ROUTE = "/api/search-course-enrollments";

    /** The four courses whose enrollments include an active one, ordered by code. */
    private static final List<String> MATCHING_CODES = List.of("CS101", "CS102", "MAT101", "ZOO101");

    /** The enrollments the fixture seeds for the course with thirty of them. */
    private static final int BULK_ENROLLMENTS = 30;

    /** The reads a page costs: the count, the page, and one batch read per association. */
    private static final long PAGE_READS = 4L;

    /** The reads an empty page costs: the association reads are skipped when there are no roots. */
    private static final long EMPTY_PAGE_READS = 2L;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void compiledRelationQueryFiltersByExistenceAndReadsAssociationsInBatches() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[RELATION-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[RELATION-CONFORMANCE] The Q11 business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[RELATION-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[RELATION-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[RELATION-CONFORMANCE] The Q11 business slice was NOT verified.");
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
            System.out.println("[RELATION-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[RELATION-CONFORMANCE] The Q11 business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "relation slice business conformance FAILED:\n" + slice.report());
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

        slice.setHttpStatus("READY (200 from " + FIXTURE.readinessPath() + ")");
        slice.reportLine("schemaSource=TEST_FIXTURE_DDL");
        slice.reportLine("productInitializeImplemented=false");
        slice.reportLine("productUpdateImplemented=false");
        try (MysqlObserver observer = slice.openObserver(runtimeUrl);
             MysqlRuntimeTrace trace = MysqlRuntimeTrace.open(
                     environment.controlJdbcUrl(), environment.controlUsername(), environment.controlPassword(),
                     environment.schemaName(), environment.runtimeUsername())) {
            slice.setRowsBefore(slice.rowsNow(observer));
            assertRelationBehaviour(slice, observer, trace);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    /**
     * The business assertions. Each response is a fresh request, its reads are counted from the
     * server's own records, and the rows it answers with are cross-checked against the seed.
     */
    private void assertRelationBehaviour(BusinessSliceHarness slice, MysqlObserver observer, MysqlRuntimeTrace trace)
            throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        // A1: the page reports the roots, once each, and only those with an active enrollment.
        Call first = request(http, trace, base, "?page=1&size=10", slice, "A1 GET search page=1 size=10");
        if (slice.expectStatus("the first page is served", first.response(), 200)) {
            slice.expectField("the total counts matching roots, not matching enrollments",
                    first.response(), "total", Integer.toString(MATCHING_CODES.size()));
            slice.check("the page serves each matching root exactly once",
                    recordCodes(first.response().body()).equals(MATCHING_CODES),
                    "codes=" + recordCodes(first.response().body()));
            slice.check("a course whose only enrollment is cancelled is not a match",
                    !first.response().body().contains("ART101"),
                    "body=" + first.response().body());
            slice.check("a course without enrollments is not a match",
                    !first.response().body().contains("PHY101"),
                    "body=" + first.response().body());
            // The one predicate cannot be satisfied by two different rows: this course has an
            // enrollment (so "has enrollments" holds) and an active enrollment exists in the table
            // (so "some enrollment is active" holds), yet no single row satisfies both.
            slice.check("one existence predicate is not satisfied by two different rows",
                    !first.response().body().contains("ENG101"),
                    "body=" + first.response().body());
        }

        // A2: the nested collection is projected from the related rows, in identity order.
        String firstCourse = recordFor(first.response().body(), "CS101");
        slice.check("the nested collection holds every related row of the root, in identity order",
                firstCourse.contains("\"id\":1") && firstCourse.contains("\"id\":2")
                        && firstCourse.indexOf("\"id\":1") < firstCourse.indexOf("\"id\":2"),
                "CS101=" + firstCourse);
        slice.check("the single related row of each collection entry is read from its own reference",
                firstCourse.contains("\"name\":\"Alice\"") && firstCourse.contains("\"name\":\"Bob\""),
                "CS101=" + firstCourse);

        // A3: an existence filter on the root does not filter the projection: the cancelled
        // enrollment of a matching course is still one of that course's enrollments.
        String secondCourse = recordFor(first.response().body(), "CS102");
        slice.check("a matching root still projects its unrelated-status enrollments",
                secondCourse.contains("\"id\":3") && secondCourse.contains("\"id\":4"),
                "CS102=" + secondCourse);
        slice.check("the projected row carries the status it was stored with",
                secondCourse.contains("\"status\":\"CANCELLED\"") && secondCourse.contains("\"name\":\"Cara\""),
                "CS102=" + secondCourse);

        // A4: thirty related rows cost the same reads as one, and all of them are projected.
        String bulkCourse = recordFor(first.response().body(), "ZOO101");
        slice.check("every related row of a root is projected, however many there are",
                occurrences(bulkCourse, "\"student\":") == BULK_ENROLLMENTS,
                "ZOO101 nested entries=" + occurrences(bulkCourse, "\"student\":")
                        + " expected=" + BULK_ENROLLMENTS);

        // A5: the reads the page costs, measured on the server.
        //
        // The budget counts the statements that touch a fixture table — the reads the query plan
        // issues. A JDBC connection opened inside the window also contributes its own bookkeeping
        // (Connector/J's session initialization and the driver's read-only probe); those are not
        // reads of business data, so they are reported separately and must all be session probes,
        // which is what keeps an unexpected extra statement from hiding behind the filter.
        MysqlRuntimeTrace.Trace page1 = first.trace();
        List<String> page1FixtureReads = fixtureReads(page1);
        slice.reportLine("A1 reads=" + page1.selectCount() + " fixtureReads=" + page1FixtureReads.size());
        slice.reportLine("A1 readDigests=" + page1.selectDigests());
        slice.reportLine("A1 rawStatements=" + page1.rawSelects());
        slice.check("a page with roots costs the count, the page and one read per association",
                page1FixtureReads.size() == PAGE_READS, "fixtureReads=" + page1FixtureReads
                        + " allDigests=" + page1.selectDigests());
        slice.check("every counted statement that does not read a fixture table is a session probe",
                nonFixtureReads(page1).stream().allMatch(RelationSliceBusinessConformanceIT::isSessionProbe),
                "nonFixture=" + nonFixtureReads(page1));
        slice.check("the root filter is a correlated EXISTS over the related table",
                page1.rawSelects().stream().anyMatch(statement -> statement.contains("EXISTS (SELECT 1 FROM enrollment")),
                "rawStatements=" + page1.rawSelects());
        slice.check("each association is read once by the keys of the rows already loaded",
                page1.rawSelects().stream().anyMatch(statement -> statement.contains("FROM enrollment")
                        && statement.contains("course_id IN"))
                        && page1.rawSelects().stream().anyMatch(statement -> statement.contains("FROM student")
                        && statement.contains("id IN")),
                "rawStatements=" + page1.rawSelects());

        // A6: the same page size with a full page still costs the same number of reads, so the
        // related rows are read per page and not per root.
        Call second = request(http, trace, base, "?page=2&size=2", slice, "A6 GET search page=2 size=2");
        if (slice.expectStatus("the second page is served", second.response(), 200)) {
            slice.check("the last page serves the remaining roots",
                    recordCodes(second.response().body()).equals(List.of("MAT101", "ZOO101")),
                    "codes=" + recordCodes(second.response().body()));
        }

        MysqlRuntimeTrace.Trace page2 = second.trace();
        List<String> page2FixtureReads = fixtureReads(page2);
        slice.reportLine("A6 reads=" + page2.selectCount() + " fixtureReads=" + page2FixtureReads.size());
        slice.check("a page of two roots costs one read per association, not one per related row",
                page2FixtureReads.size() == PAGE_READS, "fixtureReads=" + page2FixtureReads
                        + " allDigests=" + page2.selectDigests());
        slice.check("thirty related rows cost no more statements than one",
                page2FixtureReads.size() == PAGE_READS, "fixtureReads=" + page2FixtureReads.size());

        // A7: an out-of-range page answers with an empty page and the real total, and skips the reads
        // it cannot need.
        Call outOfRangeCall = request(http, trace, base, "?page=99&size=10", slice, "A7 GET search page=99 size=10");
        if (slice.expectStatus("an out-of-range page is served as an empty page", outOfRangeCall.response(), 200)) {
            slice.expectField("the empty page still reports the real total", outOfRangeCall.response(), "total",
                    Integer.toString(MATCHING_CODES.size()));
            slice.check("the out-of-range page serves no records",
                    recordCodes(outOfRangeCall.response().body()).isEmpty(),
                    "body=" + outOfRangeCall.response().body());
        }

        MysqlRuntimeTrace.Trace outOfRange = outOfRangeCall.trace();
        slice.reportLine("A7 reads=" + outOfRange.selectCount()
                + " fixtureReads=" + fixtureReads(outOfRange).size());
        slice.check("an empty page costs only its own two reads",
                fixtureReads(outOfRange).size() == EMPTY_PAGE_READS, "fixtureReads=" + fixtureReads(outOfRange)
                        + " allDigests=" + outOfRange.selectDigests());

        // A8: the declared page bounds are the contract, and a rejected request reads nothing.
        Call invalidPageCall = request(http, trace, base, "?page=0&size=10", slice, "A8 GET search page=0 size=10");
        if (slice.expectStatus("a page below the declared bounds is refused with the declared 400",
                invalidPageCall.response(), 400)) {
            slice.expectField("the rejected page carries the declared failure code", invalidPageCall.response(),
                    "code", "InvalidPage");
        }

        slice.reportLine("A8 reads=" + invalidPageCall.trace().selectCount()
                + " fixtureReads=" + fixtureReads(invalidPageCall.trace()).size());
        slice.check("a rejected page issues no read at all",
                fixtureReads(invalidPageCall.trace()).isEmpty(),
                "fixtureReads=" + fixtureReads(invalidPageCall.trace())
                        + " allDigests=" + invalidPageCall.trace().selectDigests());

        // A9: one request is one read-only transaction, so its count, page and batch reads cannot see
        // different states of the data.
        slice.reportLine("A1 transactions=" + page1.transactions() + " readOnly=" + page1.readOnlyTransactions());
        slice.check("every read of one request runs in a single read-only transaction",
                page1.transactions() == 1L && page1.readOnlyTransactions() == 1L,
                "transactions=" + page1.transactions() + " readOnly=" + page1.readOnlyTransactions());

        // A10: the fixture rows themselves are untouched; the slice is read-only.
        slice.check("the relation slice does not write to the fixture tables",
                slice.rowsNow(observer).size() == slice.rowsBeforeSize(),
                "before=" + slice.rowsBeforeSize() + " after=" + slice.rowsNow(observer).size());
        slice.reportLine("finalRowFingerprint=" + observer.queryRows(environment.schemaName(), FIXTURE.table(), "id"));
    }

    /** How many times a fragment appears in a response record. */
    private static int occurrences(String text, String fragment) {
        int count = 0;
        int index = text.indexOf(fragment);
        while (index >= 0) {
            count++;
            index = text.indexOf(fragment, index + fragment.length());
        }

        return count;
    }

    /** Runs one request inside its own measurement window and records the response. */
    private static Call request(
            HttpAssertionClient http, MysqlRuntimeTrace trace, String base, String query,
            BusinessSliceHarness slice, String label) throws Exception {
        trace.start();
        HttpAssertionClient.Response response = http.get(base + SEARCH_ROUTE + query, Map.of());
        MysqlRuntimeTrace.Trace measured = trace.stop();
        slice.record(label, response);
        return new Call(measured, response);
    }

    /** One request with the reads it cost. */
    private record Call(MysqlRuntimeTrace.Trace trace, HttpAssertionClient.Response response) {
    }

    /** The codes of the records a paged response serves, in the order served. */
    private static List<String> recordCodes(String body) {
        List<String> codes = new ArrayList<>();
        for (String record : topLevelRecords(body)) {
            codes.add(BusinessSliceHarness.field(record, "code"));
        }

        return codes;
    }

    /** The served record whose {@code code} is the given one, or an empty string when absent. */
    private static String recordFor(String body, String code) {
        for (String record : topLevelRecords(body)) {
            if (BusinessSliceHarness.field(record, "code").equals(code)) {
                return record;
            }
        }

        return "";
    }

    /**
     * The records of a paged response as raw JSON objects, in the order served.
     *
     * <p>A small hand-rolled reader over the response text, like the harness's own: it cross-checks
     * the serialized body instead of trusting a mapping shared with the application, and it keeps the
     * nested objects of a record intact.
     */
    private static List<String> topLevelRecords(String body) {
        List<String> records = new ArrayList<>();
        int recordsKey = body.indexOf("\"records\"");
        if (recordsKey < 0) {
            return records;
        }

        int index = body.indexOf('[', recordsKey);
        if (index < 0) {
            return records;
        }

        int depth = 0;
        int recordStart = -1;
        for (int position = index; position < body.length(); position++) {
            char current = body.charAt(position);
            if (current == '{') {
                if (depth == 0) {
                    recordStart = position;
                }

                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0 && recordStart >= 0) {
                    records.add(body.substring(recordStart, position + 1));
                    recordStart = -1;
                }
            } else if (current == ']' && depth == 0) {
                break;
            }
        }

        return records;
    }

    /** The counted reads that touch a fixture table: the statements the query plan itself issued. */
    private static List<String> fixtureReads(MysqlRuntimeTrace.Trace trace) {
        return trace.selectDigests().stream()
                .filter(RelationSliceBusinessConformanceIT::mentionsFixtureTable)
                .toList();
    }

    /** The counted statements that are not fixture reads: connection bookkeeping, not business reads. */
    private static List<String> nonFixtureReads(MysqlRuntimeTrace.Trace trace) {
        return trace.selectDigests().stream()
                .filter(digest -> !mentionsFixtureTable(digest))
                .toList();
    }

    /** Connector/J's own session handshake and read-only probe both read server variables. */
    private static boolean isSessionProbe(String digestText) {
        return digestText.contains("@@");
    }

    private static boolean mentionsFixtureTable(String digestText) {
        String normalized = digestText.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("`course`") || normalized.contains("`student`") || normalized.contains("`enrollment`");
    }
}
