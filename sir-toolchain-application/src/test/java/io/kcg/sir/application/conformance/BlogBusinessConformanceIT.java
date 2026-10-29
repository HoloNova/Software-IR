package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** One cropped reference or external candidate, one generated application, the same seven-capability oracle. */
class BlogBusinessConformanceIT {
    static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Blog publication, chosen tags and unique likes", "/valid/blog.sir",
            "/conformance/mysql/blog-ddl.sql", "/conformance/mysql/blog-seed.sql", "post",
            "/api/posts?keyword=&page=1&size=10", "blog-slice-report.txt", "blog-slice", "kcg.blog-conformance.enabled");

    static BusinessSliceHarness createSlice(BusinessSliceHarness.Environment environment) {
        var slice = new BusinessSliceHarness(FIXTURE, environment);
        slice.useActorMode("external");
        return slice;
    }

    @Test
    void publicationTagsLikesAndContendedWritesMatchHttpAndIndependentDatabaseState() throws Exception {
        var environment = BusinessSliceHarness.Environment.read(System::getProperty, System::getenv, FIXTURE.enabledProperty());
        if (!environment.enabled() || !environment.complete()) {
            System.out.println("[BLOG-CONFORMANCE] NOT_RUN: " + environment.missingPrerequisites());
            return;
        }
        var slice = createSlice(environment);
        try {
            slice.verify(() -> execute(slice, environment));
        } catch (SQLException error) {
            if (error.getSQLState() == null || !error.getSQLState().startsWith("08")) throw error;
            slice.setNotRunReason("the reference database became unreachable: " + BusinessSliceHarness.describe(error));
        } finally {
            try { slice.teardown(); } finally { slice.writeReport(); }
        }
        System.out.println(slice.report());
        if (slice.notRunReason() == null) assertTrue(slice.passed(), () -> "blog business conformance FAILED:\n" + slice.report());
    }

    private static void execute(BusinessSliceHarness slice, BusinessSliceHarness.Environment environment) throws Exception {
        var runtime = slice.runtimeUrl();
        slice.initializeRedaction(runtime);
        slice.prepareSchemaAndFixtures();
        if (slice.notRunReason() != null || slice.compileAndMaterialize().isEmpty()) return;
        try (var fixture = new BlogFixture(slice, environment)) {
            var initial = fixture.snapshot();
            slice.setRowsBefore(initial.table("post").stream().map(Object::toString).toList());
            slice.reportLine("blogInitialDatabase=" + initial);
            if (!fixture.observabilityAvailable()) return;
            var jar = slice.buildAndLocateJar(runtime);
            if (jar == null || !slice.startAndWaitUntilReady(jar)) return;
            slice.setHttpStatus("READY (200 from " + FIXTURE.readinessPath() + ")");
            var a = new BlogAssertions(slice, fixture);
            a.unchanged("B01 startup readiness GET", initial, fixture.snapshot());
            if (!section(a, "identity and creation", () -> BlogPublicationChecks.identityAndCreation(a))) return;
            if (!section(a, "publication and reading", () -> BlogPublicationChecks.publicationAndReading(a))) return;
            if (!section(a, "search and pagination", () -> BlogPublicationChecks.searchAndPagination(a))) return;
            if (!section(a, "selected tags", () -> BlogTagChecks.run(a))) return;
            if (!section(a, "unique likes and rollback", () -> BlogLikeChecks.run(a))) return;
            BlogConcurrencyChecks.run(a);
            var after = fixture.snapshot();
            slice.setRowsAfter(after.table("post").stream().map(Object::toString).toList());
            slice.reportLine("blogFinalDatabase=" + after);
        }
    }

    private static boolean section(BlogAssertions a, String label, BusinessSliceHarness.Body body) throws Exception {
        if (!a.fixture.reset()) return false;
        a.slice.reportLine("blogSection=" + label);
        body.run();
        return true;
    }
}
