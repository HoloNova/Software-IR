package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.SirValidationApplication;
import io.kcg.sir.application.api.SirValidationRequest;
import io.kcg.sir.application.api.ValidationStopAfter;
import io.kcg.sir.application.internal.ValidationHashes;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Limited pilot: the same independent Q11 requirement rejects a static-valid wrong workflow. */
class SlmGeneratedProjectEvaluationIT {
    private static final String ENABLED = "kcg.slm-evaluation.enabled";
    private static final String GOOD = "/valid/course-enrollment.sir";
    private static final String BAD = "/conformance/slm/course-enrollment-missing-status.sir";
    private static final String ROUTE = "/api/search-course-enrollments";
    private static final List<String> EXPECTED = List.of("CS101", "CS102", "MAT101", "ZOO101");

    @Test void correctAndStaticValidWrongSampleAreJudgedByTheSameOriginalRequirement() throws Exception {
        var env = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, ENABLED);
        if (!env.enabled() || !env.complete()) {
            System.out.println("[SLM-EVALUATION] NOT_RUN: " + env.missingPrerequisites());
            return;
        }
        String good = ConformanceFixtures.readResource(GOOD);
        String bad = ConformanceFixtures.readResource(BAD);
        assertEquals(good.replace("course == item and status == EnrollmentStatus.ACTIVE", "course == item"), bad,
                "the controlled negative must only remove the status filter, not change the golden requirement");
        String treeSha = System.getProperty("kcg.slm-evaluation.source-tree-sha256", "UNBOUND");
        assertTrue(treeSha.matches("[0-9a-f]{64}"), "run evidence must bind the current dirty source tree");
        assertFalse(ConformanceJvmLimits.arguments("maven").isEmpty(), "pilot must cap generated Maven JVM");
        assertFalse(ConformanceJvmLimits.arguments("application").isEmpty(), "pilot must cap application JVM");
        for (var sample : List.of(new Sample("correct", GOOD, true), new Sample("missing-status", BAD, false))) {
            evaluate(sample, env, treeSha);
        }
    }

    private void evaluate(Sample sample, BusinessSliceHarness.Environment env, String treeSha) throws Exception {
        var fixture = new BusinessSliceHarness.Fixture("Q23 SLM evaluation " + sample.id(), sample.resource(),
                "/conformance/mysql/course-enrollment-ddl.sql", "/conformance/mysql/course-enrollment-seed.sql",
                "course", ROUTE + "?page=1&size=10", "slm-evaluation-report.txt", "slm-" + sample.id(), ENABLED);
        var slice = new BusinessSliceHarness(fixture, env);
        var stat = new SirValidationApplication().check(new SirValidationRequest(sample.id(), ConformanceFixtures.readResource(sample.resource())));
        slice.reportLine("sampleId=" + sample.id());
        slice.reportLine("sampleOrigin=CONTROLLED_EXISTING_Q11_FIXTURE_NOT_SLM_CORPUS");
        slice.reportLine("toolchainBaseSha=" + System.getProperty("kcg.slm-evaluation.base-sha", "UNBOUND"));
        slice.reportLine("toolchainSourceTreeSha256=" + treeSha + " evidenceScope=LOCAL_DIRTY_TREE_NOT_SAME_SHA_CI");
        slice.reportLine("requirementRevision=Q11_ACTIVE_ENROLLMENT_GOLDEN_V1");
        slice.reportLine("sourceSha256=" + stat.sourceSha256() + " generatedDigest=" + stat.digest());
        slice.reportLine("static=" + (stat.ok() ? "PASSED" : "FAILED") + " stage=" + stat.stage());
        String build = "NOT_RUN", start = "NOT_RUN", business = "NOT_RUN";
        try {
            assertTrue(stat.ok() && stat.stage() == ValidationStopAfter.GENERATION, "both pilot samples must statically pass: " + stat);
            var runtime = slice.runtimeUrl();
            slice.initializeRedaction(runtime);
            slice.prepareSchemaAndFixtures();
            if (slice.notRunReason() != null) return;
            var files = slice.compileAndMaterialize();
            assertFalse(files.isEmpty());
            assertEquals(stat.fileCount(), files.size());
            assertEquals(stat.digest(), ValidationHashes.generated(files), "the runtime tree must be the checker-approved output");
            for (var file : files) assertArrayEquals(file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(slice.projectRoot().resolve(file.relativePath())));
            var jar = slice.buildAndLocateJar(runtime);
            build = jar == null ? "FAILED" : "PASSED";
            assertNotNull(jar, () -> "BUILD failed:\n" + slice.report());
            boolean ready = slice.startAndWaitUntilReady(jar);
            start = ready ? "PASSED" : "FAILED";
            assertTrue(ready, () -> "START failed:\n" + slice.report());
            slice.setHttpStatus("READY (real HTTP 200)");
            try (var observer = slice.openObserver(runtime)) {
                var before = rows(observer, env);
                slice.setRowsBefore(before.get("course"));
                var http = new HttpAssertionClient();
                var page = http.get(slice.baseUrl() + ROUTE + "?page=1&size=10", Map.of());
                slice.record("original-requirement page1", page);
                boolean rootRule = page.statusCode() == 200 && BusinessSliceHarness.field(page.body(), "total").equals("4")
                        && codes(page.body()).equals(EXPECTED);
                slice.check("original requirement: exactly roots with at least one ACTIVE enrollment", rootRule,
                        "total=" + BusinessSliceHarness.field(page.body(), "total") + " codes=" + codes(page.body()));
                slice.reportLine("actualCourseCodes=" + codes(page.body()));
                slice.reportLine("businessErrorCode=" + (rootRule ? "NONE" : "BUSINESS_ACTIVE_ENROLLMENT_FILTER_MISMATCH"));
                assertEquals(200, page.statusCode());
                boolean projection = page.body().contains("\"enrollments\"") && page.body().contains("\"student\"")
                        && page.body().contains("\"name\":\"Alice\"") && page.body().contains("\"name\":\"Bob\"")
                        && page.body().contains("\"status\":\"CANCELLED\"");
                slice.check("nested projection retains students and cancelled enrollments of matching roots", projection, "projection=" + projection);
                assertTrue(projection);
                assertFalse(page.body().contains("PHY101"), "even the missing-status candidate must exclude no-enrollment roots");
                boolean secondPage;
                var second = http.get(slice.baseUrl() + ROUTE + "?page=2&size=2", Map.of());
                slice.record("original-requirement page2", second);
                secondPage = second.statusCode() == 200 && codes(second.body()).equals(List.of("MAT101", "ZOO101"));
                slice.check("original requirement: second page is MAT101/ZOO101 in code order", secondPage, "codes=" + codes(second.body()));
                var invalid = http.get(slice.baseUrl() + ROUTE + "?page=0&size=10", Map.of());
                slice.record("invalid pagination", invalid);
                boolean rejected = invalid.statusCode() == 400 && BusinessSliceHarness.field(invalid.body(), "code").equals("InvalidPage");
                slice.check("invalid pagination retains the declared 400 contract", rejected, "status=" + invalid.statusCode());
                assertTrue(rejected);
                var after = rows(observer, env);
                slice.setRowsAfter(after.get("course"));
                slice.check("independent JDBC: all three fixture tables unchanged", before.equals(after), "rows=" + before.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().size()).toList());
                assertEquals(before, after);
                slice.reportLine("databaseFingerprintBefore=" + ValidationHashes.source(before.toString()));
                slice.reportLine("databaseFingerprintAfter=" + ValidationHashes.source(after.toString()));
                boolean correct = rootRule && secondPage && projection && rejected && before.equals(after);
                business = correct ? "PASSED" : "FAILED";
                assertEquals(sample.expectedBusinessPass(), correct,
                        "the oracle must accept the positive and reject the wrong static-valid candidate");
                if (!sample.expectedBusinessPass()) {
                    assertTrue(codes(page.body()).containsAll(List.of("ART101", "ENG101")), "negative must expose the concrete cancelled-only leak");
                }
                slice.reportLine("pilotOracleSensitivity=PASSED expectedBusiness=" + (sample.expectedBusinessPass() ? "PASSED" : "FAILED"));
            }
        } catch (SQLException infrastructure) {
            slice.setNotRunReason("reference database unavailable: " + BusinessSliceHarness.describe(infrastructure));
        } finally {
            slice.teardown();
            slice.reportLine("build=" + build + " start=" + start + " business=" + business);
            slice.reportLine("mavenJvmLimits=" + ConformanceJvmLimits.arguments("maven"));
            slice.reportLine("applicationJvmLimits=" + ConformanceJvmLimits.arguments("application"));
            slice.writeReport();
            System.out.println(slice.report());
            assertTrue(slice.notRunReason() == null, "evaluation did NOT_RUN: " + slice.notRunReason());
            assertTrue(slice.report().contains("PASS the generated application process exited"), "application cleanup not proved");
            assertTrue(slice.report().contains("PASS the owned schema was dropped and its absence proved"), "schema cleanup not proved");
        }
    }

    private static Map<String, List<String>> rows(MysqlObserver observer, BusinessSliceHarness.Environment env) throws SQLException {
        var result = new TreeMap<String, List<String>>();
        for (String table : List.of("course", "enrollment", "student")) result.put(table, observer.queryRows(env.schemaName(), table, "id"));
        return result;
    }
    private static List<String> codes(String body) {
        return Pattern.compile("\\\"code\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body).results().map(m -> m.group(1)).toList();
    }
    private record Sample(String id, String resource, boolean expectedBusinessPass) {}
}
