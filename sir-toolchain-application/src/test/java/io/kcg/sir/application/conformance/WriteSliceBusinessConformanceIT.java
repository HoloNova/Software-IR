package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Business conformance for the Q10 course write slice: creation, reading, an empty change set, a
 * partial change, a stale change, and a concurrent change race — asserted over real HTTP against a
 * real MySQL server, cross-checked through an independent JDBC connection.
 *
 * <p>This is <em>not</em> the ADR-017 qualification matrix and it never claims {@code QUALIFIED}; it
 * reports {@code PASSED}, {@code FAILED}, or {@code NOT_RUN} for one business slice. It closes the gap
 * that unit tests, a compiling generated project, and the change-apply harness cannot: that the
 * compiled workflow actually inserts, loads, merges, guards, and refuses exactly the requests the
 * declarations describe, against a database that enforces its own constraints.
 *
 * <h2>What each assertion is decisive about</h2>
 *
 * <p>The patch payload declares no field constraints, so a rejected blank value can only come from the
 * generated check on the merged candidate; a stale request can only be refused by the version
 * predicate or the version pre-check; and a partial change can only leave the other columns alone if
 * the merge is driven by which changes the request actually carried. Every failing request is followed
 * by a full-row fingerprint, so "refused" and "refused without writing" are separate assertions.
 *
 * <h2>Opt-in</h2>
 *
 * <p>Without a complete reference environment the test prints {@code NOT_RUN} and returns before any
 * side effect. Invocation:
 *
 * <pre>{@code
 * source /root/kcg-conformance/env.sh
 * mvn -pl sir-toolchain-application \
 *   -Dtest=io.kcg.sir.application.conformance.WriteSliceBusinessConformanceIT \
 *   -Dkcg.write-conformance.enabled=true \
 *   -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
 *   -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
 *   -Dkcg.conformance.maven-executable=/usr/bin/mvn \
 *   -Dkcg.conformance.maven-repo=/root/.m2/repository \
 *   -Dmaven.repo.local=/root/.m2/repository \
 *   test
 * }</pre>
 */
class WriteSliceBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Q10 course write slice",
            "/valid/course-admin.sir",
            "/conformance/mysql/course-admin-ddl.sql",
            "/conformance/mysql/course-admin-seed.sql",
            "course",
            "/api/get-course?id=1",
            "write-slice-report.txt",
            "write-slice",
            "kcg.write-conformance.enabled");

    private static final String GET_ROUTE = "/api/get-course";
    private static final String CREATE_ROUTE = "/api/create-course";
    private static final String UPDATE_ROUTE = "/api/update-course";

    private static final long SEEDED_ROWS = 3L;

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void compiledCourseWritesAreCreatedReadMergedGuardedAndRefusedAgainstRealMysql() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[WRITE-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[WRITE-CONFORMANCE] The Q10 business slice was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[WRITE-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[WRITE-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[WRITE-CONFORMANCE] The Q10 business slice was NOT verified.");
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
            System.out.println("[WRITE-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[WRITE-CONFORMANCE] The Q10 business slice was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "write slice business conformance FAILED:\n" + slice.report());
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

        slice.setHttpStatus("READY (200 from " + GET_ROUTE + "?id=1)");
        try (MysqlObserver observer = slice.openObserver(runtimeUrl)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            slice.check("the fixture seed is present in the owned schema",
                    slice.rowsBeforeSize() == SEEDED_ROWS, "seededRowCount=" + slice.rowsBeforeSize());
            assertWriteBehaviour(slice, observer);
            slice.setRowsAfter(slice.rowsNow(observer));
        }
    }

    /**
     * The business assertions. Each one is a fresh independent HTTP request, cross-checked against rows
     * read by an independent connection.
     */
    private void assertWriteBehaviour(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        String base = slice.baseUrl();

        // B1: a creation answers with the declared projection, including the version it started at.
        HttpAssertionClient.Response created = http.postJson(base + CREATE_ROUTE, Map.of(),
                "{\"code\":\"PHY101\",\"name\":\"Intro to Physics\",\"description\":\"Mechanics\",\"capacity\":50}");
        slice.record("B1 POST create-course", created);
        // The two-argument form of the check helper keeps the created identity usable in the
        // assertions that follow without a mutable local.
        String createdId = slice.expectStatus("a creation returns 201", created, 201)
                ? BusinessSliceHarness.field(created.body(), "id")
                : "";
        if (!createdId.isEmpty()) {
            slice.expectFields("the creation answers with the projection it declared", created, Map.of(
                    "id", createdId,
                    "code", "PHY101",
                    "name", "Intro to Physics",
                    "description", "Mechanics",
                    "capacity", "50",
                    "version", "0"));
            slice.check("the creation response is the declared projection, not the entity",
                    created.body().contains("\"version\""),
                    "body=" + created.body());
        }

        // B2: the created row is readable, and its version is the value a new row starts at.
        if (!createdId.isEmpty()) {
            HttpAssertionClient.Response read = http.get(base + GET_ROUTE + "?id=" + createdId, Map.of());
            slice.record("B2 GET get-course (created row)", read);
            if (slice.expectStatus("the created row is readable", read, 200)) {
                slice.expectFields("the created row carries what was sent", read, Map.of(
                        "id", createdId, "code", "PHY101", "name", "Intro to Physics", "version", "0"));
            }

            slice.check("the created row is in the database with version 0",
                    observer.queryRows(environment.schemaName(), FIXTURE.table(), "id").stream()
                            .anyMatch(row -> row.startsWith(createdId + "|PHY101|Intro to Physics|")
                                    && row.endsWith("|0")),
                    "rows=" + observer.queryRows(environment.schemaName(), FIXTURE.table(), "id"));
        }

        // B3: an unknown identity is a declared failure with the declared status.
        HttpAssertionClient.Response missing = http.get(base + GET_ROUTE + "?id=999999", Map.of());
        slice.record("B3 GET get-course (unknown id)", missing);
        if (slice.expectStatus("reading an unknown course returns the declared 404", missing, 404)) {
            slice.expectField("the 404 carries the declared failure code", missing, "code", "CourseNotFound");
        }

        // B4: a partial change moves exactly the addressed column and advances the version.
        HttpAssertionClient.Response capacityOnly = patch(http, base, 1L, 0L, "{\"capacity\":77}");
        slice.record("B4 PATCH capacity only (expectedVersion=0)", capacityOnly);
        if (slice.expectStatus("a partial change returns 200", capacityOnly, 200)) {
            slice.expectFields("the partial change answers with the merged row", capacityOnly, Map.of(
                    "id", "1", "code", "CS101", "name", "Intro to Algorithms",
                    "description", "Asymptotic analysis and recursion", "capacity", "77", "version", "1"));
        }

        slice.expectRow("the partial change wrote the addressed column and nothing else",
                observer, 1L, "1|CS101|Intro to Algorithms|Asymptotic analysis and recursion|77|1");
        slice.expectRow("an untouched row keeps its values",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");

        // B5: an explicit null change clears the field, which an absent change must not do.
        HttpAssertionClient.Response cleared = patch(http, base, 1L, 1L, "{\"description\":null}");
        slice.record("B5 PATCH description=null (expectedVersion=1)", cleared);
        if (slice.expectStatus("clearing a field with an explicit null returns 200", cleared, 200)) {
            slice.expectFields("the cleared field answers as null and the version advanced", cleared, Map.of(
                    "id", "1", "name", "Intro to Algorithms", "version", "2"));
        }

        slice.expectRow("the explicit null change cleared the column and left the rest alone",
                observer, 1L, "1|CS101|Intro to Algorithms|null|77|2");

        // B6: a change that names an absent field leaves it alone, and the same request repeated with
        // the stale version is refused.
        HttpAssertionClient.Response nameOnly = patch(http, base, 1L, 2L, "{\"name\":\"Algorithms I\"}");
        slice.record("B6 PATCH name only (expectedVersion=2)", nameOnly);
        if (slice.expectStatus("a name-only change returns 200", nameOnly, 200)) {
            slice.expectFields("the name-only change kept the empty description", nameOnly,
                    Map.of("name", "Algorithms I", "version", "3"));
        }

        slice.expectRow("the name-only change left the cleared column cleared",
                observer, 1L, "1|CS101|Algorithms I|null|77|3");

        HttpAssertionClient.Response stale = patch(http, base, 1L, 2L, "{\"capacity\":999}");
        slice.record("B6 PATCH stale expectedVersion=2", stale);
        if (slice.expectStatus("a change with a stale version is refused with the declared 409", stale, 409)) {
            slice.expectField("the stale conflict carries the declared failure code", stale, "code", "StaleVersion");
        }

        slice.expectRow("the refused stale change wrote nothing",
                observer, 1L, "1|CS101|Algorithms I|null|77|3");

        // B7: the version compared against is the one the row carries, not the one a new row starts at.
        HttpAssertionClient.Response wrongFreshVersion = patch(http, base, 3L, 0L, "{\"capacity\":5}");
        slice.record("B7 PATCH row 3 with expectedVersion=0 (stored version is 4)", wrongFreshVersion);
        slice.expectStatus("a change that assumes a fresh version is refused", wrongFreshVersion, 409);
        slice.expectRow("the refused request left the seeded row untouched",
                observer, 3L, "3|ART101|Art of Painting|Studio course on colour mixing|15|4");

        HttpAssertionClient.Response storedVersion = patch(http, base, 3L, 4L, "{\"capacity\":5}");
        slice.record("B7 PATCH row 3 with expectedVersion=4", storedVersion);
        if (slice.expectStatus("a change carrying the stored version returns 200", storedVersion, 200)) {
            slice.expectFields("the version advanced from the stored value", storedVersion,
                    Map.of("id", "3", "capacity", "5", "version", "5"));
        }

        slice.expectRow("the accepted change advanced the row",
                observer, 3L, "3|ART101|Art of Painting|Studio course on colour mixing|5|5");

        // B8: the candidate check, not a payload annotation, refuses a blank merged value.
        HttpAssertionClient.Response blankName = patch(http, base, 2L, 0L, "{\"name\":\"   \"}");
        slice.record("B8 PATCH blank name (expectedVersion=0)", blankName);
        if (slice.expectStatus("a blank merged value is refused with 400", blankName, 400)) {
            // The envelope's own code says the request was invalid; the field entry names the property
            // the rejected value came from and the constraint that rejected it.
            slice.expectField("the rejection reports an invalid request", blankName, "code", "INVALID_REQUEST");
            slice.check("the rejection names the request property it came from",
                    blankName.body().contains("\"path\":\"changes.name\""), "body=" + blankName.body());
            slice.check("the rejection carries the constraint code",
                    blankName.body().contains("\"code\":\"notBlank\""), "body=" + blankName.body());
        }

        slice.expectRow("the refused blank value wrote nothing",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");

        // B9: an unknown change property is refused instead of ignored.
        HttpAssertionClient.Response unknownChange = patch(http, base, 2L, 0L, "{\"code\":\"MUTATED\"}");
        slice.record("B9 PATCH undeclared change property", unknownChange);
        if (slice.expectStatus("an undeclared change property is refused with 400", unknownChange, 400)) {
            slice.check("the rejection names the undeclared property",
                    unknownChange.body().contains("changes.code"), "body=" + unknownChange.body());
        }

        slice.expectRow("the refused undeclared change wrote nothing",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");

        // B10: an envelope member that is missing is refused by name.
        HttpAssertionClient.Response withoutVersion = postPatch(http, base,
                "{\"id\":2,\"changes\":{\"capacity\":21}}");
        slice.record("B10 PATCH without expectedVersion", withoutVersion);
        if (slice.expectStatus("a change without expectedVersion is refused with 400", withoutVersion, 400)) {
            slice.check("the rejection names the missing member",
                    withoutVersion.body().contains("expectedVersion"), "body=" + withoutVersion.body());
        }

        HttpAssertionClient.Response withoutIdentity = postPatch(http, base,
                "{\"expectedVersion\":0,\"changes\":{\"capacity\":21}}");
        slice.record("B10 PATCH without id", withoutIdentity);
        if (slice.expectStatus("a change without an identity is refused with 400", withoutIdentity, 400)) {
            slice.check("the rejection names the missing identity",
                    withoutIdentity.body().contains("\"path\":\"id\""), "body=" + withoutIdentity.body());
        }

        slice.expectRow("the refused envelopes wrote nothing",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");

        // B11: an empty change set is a declared failure, not a no-op write.
        HttpAssertionClient.Response emptyChange = patch(http, base, 2L, 0L, "{}");
        slice.record("B11 PATCH with an empty change set", emptyChange);
        if (slice.expectStatus("an empty change set is refused with 400", emptyChange, 400)) {
            slice.expectField("the empty change set carries the declared failure code",
                    emptyChange, "code", "EmptyChange");
        }

        slice.expectRow("the refused empty change set wrote nothing and did not advance the version",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");

        // B12: an unknown property beside the envelope is refused rather than dropped silently.
        HttpAssertionClient.Response unknownEnvelopeMember = postPatch(http, base,
                "{\"id\":2,\"expectedVersion\":0,\"changes\":{\"capacity\":21},\"unexpected\":1}");
        slice.record("B12 PATCH with an unknown envelope property", unknownEnvelopeMember);
        slice.expectStatus("an unknown envelope property is refused with 400", unknownEnvelopeMember, 400);
        slice.expectRow("the refused unknown envelope property wrote nothing",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");

        // B13: a change against an unknown identity is the declared not-found failure.
        HttpAssertionClient.Response missingRow = patch(http, base, 999999L, 0L, "{\"capacity\":21}");
        slice.record("B13 PATCH unknown identity", missingRow);
        if (slice.expectStatus("a change against an unknown course returns the declared 404", missingRow, 404)) {
            slice.expectField("the 404 carries the declared failure code", missingRow, "code", "CourseNotFound");
        }

        // B14: a creation that violates a declared constraint is refused, and creates nothing.
        long rowsBeforeInvalidCreate = observer.countRows(environment.schemaName(), FIXTURE.table());
        HttpAssertionClient.Response invalidCreate = http.postJson(base + CREATE_ROUTE, Map.of(),
                "{\"code\":\"BAD1\",\"name\":\"   \",\"description\":null,\"capacity\":10}");
        slice.record("B14 POST create-course with a blank name", invalidCreate);
        if (slice.expectStatus("a creation with a blank name is refused with 400", invalidCreate, 400)) {
            slice.check("the creation rejection names the offending payload property",
                    invalidCreate.body().contains("\"path\":\"name\""), "body=" + invalidCreate.body());
        }
        slice.check("the refused creation inserted no row",
                observer.countRows(environment.schemaName(), FIXTURE.table()) == rowsBeforeInvalidCreate,
                "rowCount=" + observer.countRows(environment.schemaName(), FIXTURE.table()));

        // B15: the concurrency assertion. Two changes carrying the same expected version race; exactly
        // one may win, and the version advances once.
        slice.expectRow("the racing row starts at the version both requests will carry",
                observer, 2L, "2|CS102|Intro to Patterns|null|20|0");
        List<Integer> raceStatuses = raceTwoChanges(http, base, 2L, 0L);
        slice.check("exactly one of two concurrent changes with the same version is accepted",
                raceStatuses.equals(List.of(200, 409)), "statuses=" + raceStatuses);
        slice.expectRow("the accepted change advanced the version exactly once",
                observer, 2L, "2|CS102|Intro to Patterns|null|21|1");

        // B16: the whole table fingerprint is checked once more, so no assertion above can have passed
        // because some request wrote something it then reported as refused.
        slice.reportLine("finalRowFingerprint=" + observer.queryRows(environment.schemaName(), FIXTURE.table(), "id"));
    }

    private static HttpAssertionClient.Response patch(
            HttpAssertionClient http, String base, long id, long expectedVersion, String changesJson)
            throws Exception {
        return postPatch(http, base, "{\"id\":" + id + ",\"expectedVersion\":" + expectedVersion
                + ",\"changes\":" + changesJson + "}");
    }

    private static HttpAssertionClient.Response postPatch(HttpAssertionClient http, String base, String body)
            throws Exception {
        return http.patchJson(base + UPDATE_ROUTE, Map.of(), body);
    }

    /**
     * Sends two changes with the same expected version as close together as the JVM allows.
     *
     * <p>Both threads wait on one barrier so neither can finish before the other starts; the generated
     * write is a single conditional statement, so the database decides the winner.
     */
    private static List<Integer> raceTwoChanges(
            HttpAssertionClient http, String base, long id, long expectedVersion) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> racers = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                racers.add(() -> {
                    barrier.await();
                    return postPatch(http, base, "{\"id\":" + id + ",\"expectedVersion\":" + expectedVersion
                            + ",\"changes\":{\"capacity\":21}}").statusCode();
                });
            }

            List<Future<Integer>> results = executor.invokeAll(racers);
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }

            statuses.sort(Integer::compareTo);
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }
}
