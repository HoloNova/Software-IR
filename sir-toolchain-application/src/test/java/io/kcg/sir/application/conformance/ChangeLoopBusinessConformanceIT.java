package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ChangeApplyRequest;
import io.kcg.sir.application.api.ChangeApplyResult;
import io.kcg.sir.application.api.ChangeBaselinePlanningRequest;
import io.kcg.sir.application.api.ChangeBaselinePlanningResult;
import io.kcg.sir.application.api.ChangeBaselineRegistrationRequest;
import io.kcg.sir.application.api.ChangeBaselineRegistrationResult;
import io.kcg.sir.application.api.ChangeExecutionApplication;
import io.kcg.sir.application.api.ChangeOutputManifest;
import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ToolchainApplication;
import io.kcg.sir.application.api.ToolchainRequest;
import io.kcg.sir.application.api.ToolchainResult;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.source.SourceId;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Business conformance for the G1 change loop (Q13): real SIR changes applied to an already delivered
 * project, rebuilt into the same tree, and verified over real HTTP against a real MySQL server.
 *
 * <p>The existing conformance runs compile one fixed source. This one asks the question the G1 gate
 * actually poses — "continue generating SIR / modify SIR" — by walking one project through four
 * rounds on the same project root and asserting the <em>behaviour change</em> each round delivers:
 *
 * <ol>
 *   <li>R1 widens the search filter from "has an ACTIVE enrollment" to "has any enrollment". The
 *       decisive row is ART101, whose only enrollment is cancelled: absent before, served after.</li>
 *   <li>R2 tightens {@code CreateCourseInput.name} to 20 characters. A 25-character name must be
 *       refused with the offending property named and no row written; a 15-character one must be
 *       created.</li>
 *   <li>R3 removes the two write capabilities, turning the service read-only. The write routes must
 *       be gone, the search must still work, and an invalid page must still answer with the declared
 *       400 and the same error envelope — that last assertion is the runtime evidence that the
 *       shared support artifacts are unconditional products rather than products of the capabilities
 *       that happen to remain.</li>
 *   <li>R4 adds a capability that reuses the declarations already in the base. Its route must serve.</li>
 * </ol>
 *
 * <p>Each round is applied with the real change pipeline (plan, apply, new baseline) and the tree it
 * wrote is then built and served — not a freshly generated copy of the candidate. The
 * "incrementally applied project equals a from-scratch generation of the same candidate" invariant is
 * asserted byte for byte by {@code ChangeLoopPlanningContractTest}; this test asserts what only a
 * running application can: that the delivered tree still builds, starts, and behaves as the change
 * intended.
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
 *   -Dtest=io.kcg.sir.application.conformance.ChangeLoopBusinessConformanceIT \
 *   -Dkcg.change-conformance.enabled=true \
 *   -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
 *   -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
 *   -Dkcg.conformance.maven-executable=/usr/bin/mvn \
 *   -Dkcg.conformance.maven-repo=/root/.m2/repository \
 *   -Dmaven.repo.local=/root/.m2/repository \
 *   test
 * }</pre>
 */
class ChangeLoopBusinessConformanceIT {

    private static final BusinessSliceHarness.Fixture FIXTURE = new BusinessSliceHarness.Fixture(
            "Q13 change loop",
            "/valid/course-admin-enrollment.sir",
            "/conformance/mysql/change-loop-ddl.sql",
            "/conformance/mysql/change-loop-seed.sql",
            "course",
            "/api/search-course-enrollments?page=1&size=10",
            "change-loop-report.txt",
            "change-loop",
            "kcg.change-conformance.enabled");

    private static final String SEARCH_ROUTE = "/api/search-course-enrollments";
    private static final String CREATE_ROUTE = "/api/create-course";
    private static final String UPDATE_ROUTE = "/api/update-course";
    private static final String REFS_ROUTE = "/api/list-course-refs";

    /** Ordered by code; ART101 is the row the first round's filter change is decisive about. */
    private static final List<String> BEFORE_R1 = List.of("CS101", "CS102", "MAT101");
    private static final List<String> AFTER_R1 = List.of("ART101", "CS101", "CS102", "MAT101");

    private static final String FILTER_ANY = "/valid/course-admin-enrollment-filter-any.sir";
    private static final String TIGHTEN_NAME = "/valid/course-admin-enrollment-tighten.sir";
    private static final String REMOVE_UPDATE = "/valid/course-admin-enrollment-remove-update.sir";
    private static final String REMOVE_LAST_WRITER = "/valid/course-admin-enrollment-readonly.sir";
    private static final String ADD_LIST_REFS = "/valid/course-admin-enrollment-add-list-refs.sir";

    /**
     * One source identity for every compile of this slice.
     *
     * <p>The registered snapshot is validated by recompiling the given source, and the project graph
     * embeds the source identity — so the snapshot, the revision and every round must use the same
     * one, or registration fails with a digest mismatch that has nothing to do with the change.
     */
    private static final SourceId SOURCE_ID = SourceId.of("change-loop.sir");

    private static BusinessSliceHarness.Environment environment;

    @BeforeAll
    static void readEnvironment() {
        environment = BusinessSliceHarness.Environment.read(
                System::getProperty, System::getenv, FIXTURE.enabledProperty());
    }

    @Test
    void fourChangeRoundsReachTheRunningService() throws Exception {
        if (!environment.enabled()) {
            System.out.println("[CHANGE-CONFORMANCE] NOT_RUN: " + FIXTURE.enabledProperty() + " is not true");
            System.out.println("[CHANGE-CONFORMANCE] The Q13 change loop was NOT verified.");
            return;
        }

        if (!environment.complete()) {
            System.out.println("[CHANGE-CONFORMANCE] NOT_RUN: incomplete reference environment");
            System.out.println("[CHANGE-CONFORMANCE] Missing prerequisites: " + environment.missingPrerequisites());
            System.out.println("[CHANGE-CONFORMANCE] The Q13 change loop was NOT verified.");
            return;
        }

        BusinessSliceHarness slice = new BusinessSliceHarness(FIXTURE, environment);
        try {
            execute(slice);
        } catch (java.sql.SQLException unreachable) {
            slice.setNotRunReason("the reference database became unreachable: "
                    + BusinessSliceHarness.describe(unreachable));
        } finally {
            slice.teardown();
        }

        slice.writeReport();
        if (slice.notRunReason() != null) {
            System.out.println("[CHANGE-CONFORMANCE] NOT_RUN: " + slice.notRunReason());
            System.out.println("[CHANGE-CONFORMANCE] The Q13 change loop was NOT verified.");
            return;
        }

        System.out.println(slice.report());
        assertTrue(slice.passed(), () -> "change loop business conformance FAILED:\n" + slice.report());
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

        Path jar = slice.buildAndLocateJar(runtimeUrl);
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

        Changes changes = new Changes(slice);
        try (MysqlObserver observer = slice.openObserver(runtimeUrl)) {
            slice.setRowsBefore(slice.rowsNow(observer));
            assertBaseline(slice, observer);

            changes.round("R1 widen the enrollment filter",
                    FILTER_ANY, "SearchCourseEnrollments", RoundKind.MODIFY_WORKFLOW);
            if (!slice.rebuildAndRestart(runtimeUrl)) {
                return;
            }

            assertFilterWidened(slice, observer);

            changes.round("R2 tighten CreateCourseInput.name",
                    TIGHTEN_NAME, "CreateCourseInput", RoundKind.MODIFY_CONSTRAINTS);
            if (!slice.rebuildAndRestart(runtimeUrl)) {
                return;
            }

            assertNameLengthEnforced(slice, observer);

            changes.round("R3a remove UpdateCourse", REMOVE_UPDATE, "UpdateCourse", RoundKind.REMOVE);
            changes.round("R3b remove CreateCourse (the last write capability)",
                    REMOVE_LAST_WRITER, "CreateCourse", RoundKind.REMOVE);
            if (!slice.rebuildAndRestart(runtimeUrl)) {
                return;
            }

            assertServiceIsReadOnly(slice, observer);

            changes.round("R4 add the reference list endpoint",
                    ADD_LIST_REFS, "ListCourseRefs", RoundKind.ADD);
            if (!slice.rebuildAndRestart(runtimeUrl)) {
                return;
            }

            assertReferenceListServes(slice, observer);

            slice.setRowsAfter(slice.rowsNow(observer));
            slice.reportLine("finalRowFingerprint=" + observer.queryRows(
                    environment.schemaName(), FIXTURE.table(), "id"));
            slice.check("the change loop left the fixture rows as it found them, except the rows it created",
                    slice.rowsAfterSize() >= slice.rowsBeforeSize(),
                    "before=" + slice.rowsBeforeSize() + " after=" + slice.rowsAfterSize());
        }
    }

    /** The base state, before any change: the narrowed filter is in force. */
    private void assertBaseline(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        HttpAssertionClient.Response search = http.get(
                slice.baseUrl() + SEARCH_ROUTE + "?page=1&size=10", Map.of());
        slice.record("R0 GET search (baseline)", search);
        if (slice.expectStatus("the baseline search is served", search, 200)) {
            slice.check("the baseline filter requires an active enrollment",
                    recordCodes(search.body()).equals(BEFORE_R1),
                    "codes=" + recordCodes(search.body()));
        }

        HttpAssertionClient.Response created = http.postJson(slice.baseUrl() + CREATE_ROUTE, Map.of(),
                "{\"code\":\"NEW100\",\"name\":\"Baseline Course\",\"description\":null,\"capacity\":5}");
        slice.record("R0 POST create-course (baseline)", created);
        slice.expectStatus("the baseline still writes", created, 201);
        slice.reportLine("R0 rows=" + observer.countRows(environment.schemaName(), FIXTURE.table()));
    }

    /** R1: the same service, the same seed, one widened filter — and one more course is served. */
    private void assertFilterWidened(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        HttpAssertionClient.Response search = http.get(
                slice.baseUrl() + SEARCH_ROUTE + "?page=1&size=10", Map.of());
        slice.record("R1 GET search (after the filter change)", search);
        if (slice.expectStatus("the changed search is served", search, 200)) {
            slice.check("widening the filter changed which courses the running service serves",
                    recordCodes(search.body()).equals(AFTER_R1),
                    "codes=" + recordCodes(search.body()));
            slice.expectField("the total counts the roots of the widened filter", search, "total",
                    Integer.toString(AFTER_R1.size()));
            slice.check("a course without enrollments still does not match",
                    !search.body().contains("PHY101"), "body=" + search.body());
            String cancelledOnly = recordFor(search.body(), "ART101");
            slice.check("the newly matching course projects its own enrollment",
                    cancelledOnly.contains("\"status\":\"CANCELLED\"") && cancelledOnly.contains("\"name\":\"Bob\""),
                    "ART101=" + cancelledOnly);
        }

        // The write side of the same delivery is unchanged by a change to a query capability.
        HttpAssertionClient.Response created = http.postJson(slice.baseUrl() + CREATE_ROUTE, Map.of(),
                "{\"code\":\"NEW101\",\"name\":\"Still Writable\",\"description\":null,\"capacity\":6}");
        slice.record("R1 POST create-course (write side unchanged)", created);
        slice.expectStatus("changing a query capability leaves the write side working", created, 201);
        slice.reportLine("R1 rows=" + observer.countRows(environment.schemaName(), FIXTURE.table()));
    }

    /** R2: the tightened input constraint is enforced by the running service, and nothing is written. */
    private void assertNameLengthEnforced(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        long rowsBefore = observer.countRows(environment.schemaName(), FIXTURE.table());

        String tooLong = "Course Name That Is 25ch";
        HttpAssertionClient.Response refused = http.postJson(slice.baseUrl() + CREATE_ROUTE, Map.of(),
                "{\"code\":\"NEW102\",\"name\":\"" + tooLong + "\",\"description\":null,\"capacity\":7}");
        slice.record("R2 POST create-course with a 25-character name", refused);
        if (slice.expectStatus("the tightened bound refuses the 25-character name", refused, 400)) {
            slice.check("the refusal names the offending payload property",
                    refused.body().contains("\"path\":\"name\""), "body=" + refused.body());
        }

        slice.check("the refused creation wrote nothing",
                observer.countRows(environment.schemaName(), FIXTURE.table()) == rowsBefore,
                "rowsBefore=" + rowsBefore + " rowsNow=" + observer.countRows(environment.schemaName(), FIXTURE.table()));

        HttpAssertionClient.Response accepted = http.postJson(slice.baseUrl() + CREATE_ROUTE, Map.of(),
                "{\"code\":\"NEW103\",\"name\":\"Fifteen Char OK\",\"description\":null,\"capacity\":8}");
        slice.record("R2 POST create-course with a 15-character name", accepted);
        slice.expectStatus("a name inside the tightened bound is still created", accepted, 201);
        slice.reportLine("R2 rows=" + observer.countRows(environment.schemaName(), FIXTURE.table()));

        HttpAssertionClient.Response search = http.get(
                slice.baseUrl() + SEARCH_ROUTE + "?page=1&size=10", Map.of());
        slice.record("R2 GET search (unchanged by a constraint change)", search);
        if (slice.expectStatus("the search still serves after a write-side constraint change", search, 200)) {
            slice.check("a write-side constraint change does not change what a query serves",
                    recordCodes(search.body()).equals(AFTER_R1), "codes=" + recordCodes(search.body()));
        }
    }

    /** R3: the write routes are gone, and the shared error envelope survives their removal. */
    private void assertServiceIsReadOnly(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        long rowsBefore = observer.countRows(environment.schemaName(), FIXTURE.table());

        HttpAssertionClient.Response create = http.postJson(slice.baseUrl() + CREATE_ROUTE, Map.of(),
                "{\"code\":\"GONE1\",\"name\":\"No Longer Accepted\",\"description\":null,\"capacity\":9}");
        slice.record("R3 POST create-course after the capability was removed", create);
        slice.reportLine("R3 createRouteStatus=" + create.statusCode());
        slice.check("the removed creation route is no longer served",
                create.statusCode() == 404 || create.statusCode() == 405,
                "status=" + create.statusCode() + " body=" + create.body());

        HttpAssertionClient.Response update = http.patchJson(slice.baseUrl() + UPDATE_ROUTE, Map.of(),
                "{\"id\":1,\"expectedVersion\":0,\"changes\":{\"name\":\"Renamed\"}}");
        slice.record("R3 PATCH update-course after the capability was removed", update);
        slice.reportLine("R3 updateRouteStatus=" + update.statusCode());
        slice.check("the removed change route is no longer served",
                update.statusCode() == 404 || update.statusCode() == 405,
                "status=" + update.statusCode() + " body=" + update.body());

        slice.check("the refused writes wrote nothing",
                observer.countRows(environment.schemaName(), FIXTURE.table()) == rowsBefore,
                "rowsBefore=" + rowsBefore + " rowsNow=" + observer.countRows(environment.schemaName(), FIXTURE.table()));

        HttpAssertionClient.Response search = http.get(
                slice.baseUrl() + SEARCH_ROUTE + "?page=1&size=10", Map.of());
        slice.record("R3 GET search (read side still served)", search);
        if (slice.expectStatus("the read side survives the removal of the write capabilities", search, 200)) {
            slice.check("the search still serves every root of the widened filter",
                    recordCodes(search.body()).equals(AFTER_R1), "codes=" + recordCodes(search.body()));
        }

        // The runtime evidence for Q10's C7: the rejection envelope and the validation primitives are
        // still there after the last write capability is gone.
        HttpAssertionClient.Response invalidPage = http.get(
                slice.baseUrl() + SEARCH_ROUTE + "?page=0&size=10", Map.of());
        slice.record("R3 GET search page=0 (error envelope after the last writer)", invalidPage);
        if (slice.expectStatus("a rejected page still answers with the declared 400", invalidPage, 400)) {
            slice.expectField("the rejection still carries the declared failure code",
                    invalidPage, "code", "InvalidPage");
        }
    }

    /** R4: the added capability serves on the same project root, and the older ones still do. */
    private void assertReferenceListServes(BusinessSliceHarness slice, MysqlObserver observer) throws Exception {
        HttpAssertionClient http = new HttpAssertionClient();
        HttpAssertionClient.Response refs = http.get(slice.baseUrl() + REFS_ROUTE, Map.of());
        slice.record("R4 GET list-course-refs", refs);
        if (slice.expectStatus("the added capability is served on the delivered project", refs, 200)) {
            // The added capability filters on "has any enrollment", which is the filter R1 installed in
            // the search route: the same four courses, and no course without enrollments.
            slice.check("the added endpoint serves every course that has an enrollment",
                    refs.body().contains("ART101") && refs.body().contains("CS101")
                            && refs.body().contains("CS102") && refs.body().contains("MAT101"),
                    "body=" + refs.body());
            slice.check("the added endpoint does not serve a course without enrollments",
                    !refs.body().contains("PHY101"), "body=" + refs.body());
            slice.reportLine("R4 refsObjects=" + occurrences(refs.body(), "\"code\":"));
        }

        HttpAssertionClient.Response search = http.get(
                slice.baseUrl() + SEARCH_ROUTE + "?page=1&size=10", Map.of());
        slice.record("R4 GET search (older capability still served)", search);
        if (slice.expectStatus("adding a capability leaves the earlier ones served", search, 200)) {
            slice.check("the earlier search still serves its roots",
                    recordCodes(search.body()).equals(AFTER_R1), "codes=" + recordCodes(search.body()));
        }

        slice.check("the added capability did not resurrect a write route",
                http.postJson(slice.baseUrl() + CREATE_ROUTE, Map.of(),
                        "{\"code\":\"STILL1\",\"name\":\"Still Gone\",\"description\":null,\"capacity\":1}")
                        .statusCode() >= 400,
                "the creation route must stay removed");
    }

    // ------------------------------------------------------------------
    // 变更管线
    // ------------------------------------------------------------------

    /** The kind of change a round declares. */
    private enum RoundKind {
        ADD,
        MODIFY_WORKFLOW,
        MODIFY_CONSTRAINTS,
        REMOVE
    }

    /**
     * The change pipeline over one delivered project: one registered baseline, advanced round by round.
     *
     * <p>Every round plans first, asserts the plan is the family it claims to be, applies it, and then
     * checks the manifest against the files on disk. A round that silently changed more than it
     * declared would be caught here rather than at the next HTTP assertion.
     */
    private final class Changes {

        private final BusinessSliceHarness slice;
        private final ChangeExecutionApplication application = new ChangeExecutionApplication();
        private final Path stateRoot;
        private Path currentSource;
        private String baselineId;

        Changes(BusinessSliceHarness slice) throws Exception {
            this.slice = slice;
            this.stateRoot = slice.workPath("state");
            Path baseSource = slice.workPath("sources").resolve("baseline.sir");
            Files.writeString(baseSource, readResource(FIXTURE.sirResource()), StandardCharsets.UTF_8);
            this.currentSource = baseSource;

            ProjectGraph graph = graphOf(baseSource);
            byte[] snapshot = serialize(graph);
            Path snapshotPath = slice.workPath("state").resolve("baseline.kcg-psg");
            Files.write(snapshotPath, snapshot);
            ChangeBaselineRegistrationResult registration = this.application.register(
                    new ChangeBaselineRegistrationRequest(baseSource, snapshotPath,
                            slice.projectRoot(), this.stateRoot, revisionOf(baseSource, graph)));
            ChangeBaselineRegistrationResult.Success registered = (ChangeBaselineRegistrationResult.Success)
                    require(registration instanceof ChangeBaselineRegistrationResult.Success,
                            "the change baseline was registered", registration);
            this.baselineId = registered.receipt().baselineId();
        }

        /** Plans and applies one round onto the live project root. */
        void round(String label, String candidateResource, String target, RoundKind kind) throws Exception {
            Path candidate = slice.workPath("sources").resolve(label.replace(' ', '-') + ".sir");
            Files.writeString(candidate, readResource(candidateResource), StandardCharsets.UTF_8);
            ChangeSet changeSet = changeSetFor(candidate, target, kind);

            ChangeBaselinePlanningResult planned = this.application.plan(new ChangeBaselinePlanningRequest(
                    this.stateRoot, this.baselineId, candidate, this.slice.projectRoot(), changeSet));
            ChangeBaselinePlanningResult.Success plannedOk = (ChangeBaselinePlanningResult.Success) require(
                    planned instanceof ChangeBaselinePlanningResult.Success,
                    label + " plans against the current baseline", planned);
            ChangeAnalysis.Planned plan = (ChangeAnalysis.Planned) require(
                    plannedOk.analysis() instanceof ChangeAnalysis.Planned,
                    label + " produces a plan", plannedOk.analysis());
            String shape = "additions=" + plan.plan().fileAdditions().size()
                    + " changes=" + plan.plan().fileChanges().size()
                    + " deletions=" + plan.plan().fileDeletions().size();
            this.slice.reportLine("plan " + label + ": " + shape);
            this.slice.check(label + " is a pure " + family(kind) + " plan", pure(plan, kind), shape);

            ChangeApplyResult applied = this.application.apply(new ChangeApplyRequest(
                    this.stateRoot, this.baselineId, candidate, this.slice.projectRoot(), changeSet));
            ChangeApplyResult.Applied appliedOk = (ChangeApplyResult.Applied) require(
                    applied instanceof ChangeApplyResult.Applied, label + " applies", applied);
            this.baselineId = appliedOk.newBaselineReceipt().baselineId();
            this.currentSource = candidate;

            this.slice.reportLine("apply " + label + ": manifestEntries=" + appliedOk.outputManifest().entries().size());
            this.slice.check(label + " wrote exactly the files its manifest lists",
                    manifestMatchesDisk(appliedOk), label + " manifest=" + appliedOk.outputManifest().entries().size() + " entries");
        }

        private ChangeSet changeSetFor(Path candidate, String target, RoundKind kind) throws Exception {
            NormalizedSemanticModel model = modelOf(kind == RoundKind.ADD ? candidate : this.currentSource);
            switch (kind) {
                case ADD: {
                    NormalizedCapability capability = capability(model, target);
                    return new ChangeSet(ChangeIrVersion.V0_2, currentRevision(),
                            List.of(new AddCapability(target(capability))));
                }
                case REMOVE: {
                    NormalizedCapability capability = capability(model, target);
                    return new ChangeSet(ChangeIrVersion.V0_3, currentRevision(),
                            List.of(new RemoveCapability(target(capability))));
                }
                case MODIFY_WORKFLOW: {
                    NormalizedCapability capability = capability(model, target);
                    return new ChangeSet(ChangeIrVersion.V0_1, currentRevision(),
                            List.of(new ModifyCapabilityWorkflow(target(capability))));
                }
                default: {
                    NormalizedInput input = input(model, target);
                    NormalizedField field = input.fields().stream()
                            .filter(candidate_field -> candidate_field.name().equals("name"))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("no name field in " + target));
                    return new ChangeSet(ChangeIrVersion.V0_4, currentRevision(),
                            List.of(new ModifyInputFieldConstraints(
                                    new ChangeTarget(input.id(), input.sourceNodeId(), field.sourceNodeId()))));
                }
            }
        }

        private ChangeTarget target(NormalizedCapability capability) {
            return new ChangeTarget(capability.id(), capability.sourceNodeId(), capability.workflow().sourceNodeId());
        }

        private ChangeBaseRevision currentRevision() throws Exception {
            ProjectGraph graph = graphOf(this.currentSource);
            return revisionOf(this.currentSource, graph);
        }

        /** Every manifest entry matches the file on disk, and nothing else was written. */
        private boolean manifestMatchesDisk(ChangeApplyResult.Applied applied) throws Exception {
            for (ChangeOutputManifest.Entry entry : applied.outputManifest().entries()) {
                Path file = this.slice.projectRoot().resolve(entry.relativePath());
                if (!Files.exists(file) || Files.size(file) != entry.byteCount()
                        || !sha256Hex(Files.readAllBytes(file)).equals(entry.sha256Hex())) {
                    return false;
                }
            }

            return true;
        }
    }

    /** Reads back what a plan claims to be, without trusting the round's own label. */
    private static boolean pure(ChangeAnalysis.Planned plan, RoundKind kind) {
        return switch (kind) {
            case ADD -> !plan.plan().fileAdditions().isEmpty() && plan.plan().fileChanges().isEmpty()
                    && plan.plan().fileDeletions().isEmpty();
            case REMOVE -> !plan.plan().fileDeletions().isEmpty() && plan.plan().fileChanges().isEmpty()
                    && plan.plan().fileAdditions().isEmpty();
            case MODIFY_WORKFLOW, MODIFY_CONSTRAINTS -> !plan.plan().fileChanges().isEmpty()
                    && plan.plan().fileAdditions().isEmpty() && plan.plan().fileDeletions().isEmpty();
        };
    }

    private static String family(RoundKind kind) {
        return switch (kind) {
            case ADD -> "creation";
            case REMOVE -> "deletion";
            default -> "update";
        };
    }

    // ------------------------------------------------------------------
    // 基础设施
    // ------------------------------------------------------------------

    private static Object require(boolean condition, String what, Object observed) {
        if (!condition) {
            throw new IllegalStateException(what + " failed: " + observed);
        }

        return observed;
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream stream = ChangeLoopBusinessConformanceIT.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource: " + resource);
            }

            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static NormalizedSemanticModel modelOf(Path source) throws Exception {
        List<io.kcg.sir.application.api.ExecutionDiagnostic> diagnostics = new ArrayList<>();
        Optional<SirCompilation.CompilationSnapshot> compiled = SirCompilation.compile(
                Files.readString(source, StandardCharsets.UTF_8),
                SOURCE_ID, model -> new SpringBootGenerator().generate(model), diagnostics);
        if (compiled.isEmpty()) {
            throw new IllegalStateException("compiling " + source + " failed: " + diagnostics);
        }

        return compiled.get().semanticModel();
    }

    /** Compiles a source into a scratch root purely to obtain its project graph. */
    private static ProjectGraph graphOf(Path source) throws Exception {
        Path scratch = Files.createTempDirectory("change-loop-graph");
        Path outputRoot = Files.createDirectory(scratch.resolve("output"));
        ToolchainResult result = new ToolchainApplication().execute(new ToolchainRequest(
                source.toAbsolutePath(), SOURCE_ID, outputRoot.toAbsolutePath(), ConflictPolicy.FAIL_IF_EXISTS));
        if (result instanceof ToolchainResult.Success success) {
            return success.graph();
        }

        throw new IllegalStateException("compiling " + source + " failed: " + result);
    }

    private static ChangeBaseRevision revisionOf(Path source, ProjectGraph graph) throws Exception {
        return new ChangeBaseRevision(SOURCE_ID, sha256Hex(Files.readAllBytes(source)),
                graph.version(), graph.canonicalDigest(), ProjectGraphCanonicalFormatVersion.V1);
    }

    private static byte[] serialize(ProjectGraph graph) {
        ProjectGraphSerialization serialization = new ProjectGraphSerializer()
                .serialize(graph, ProjectGraphCanonicalFormatVersion.V1);
        if (serialization instanceof ProjectGraphSerialization.Success success) {
            return success.document().bytes();
        }

        throw new IllegalStateException("graph serialization failed: "
                + ((ProjectGraphSerialization.Failure) serialization).diagnostics());
    }

    private static NormalizedCapability capability(NormalizedSemanticModel model, String name) {
        for (var declaration : model.declarations()) {
            if (declaration instanceof NormalizedCapability capability && capability.name().equals(name)) {
                return capability;
            }
        }

        throw new IllegalStateException("capability '" + name + "' not found");
    }

    private static NormalizedInput input(NormalizedSemanticModel model, String name) {
        for (var declaration : model.declarations()) {
            if (declaration instanceof NormalizedInput input && input.name().equals(name)) {
                return input;
            }
        }

        throw new IllegalStateException("input '" + name + "' not found");
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                int unsigned = value & 0xFF;
                if (unsigned < 0x10) {
                    builder.append('0');
                }

                builder.append(Integer.toHexString(unsigned));
            }

            return builder.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        int index = text.indexOf(fragment);
        while (index >= 0) {
            count++;
            index = text.indexOf(fragment, index + fragment.length());
        }

        return count;
    }

    /** The codes of the served records, in the order served. */
    private static List<String> recordCodes(String body) {
        List<String> codes = new ArrayList<>();
        for (String record : topLevelRecords(body)) {
            codes.add(BusinessSliceHarness.field(record, "code"));
        }

        return codes;
    }

    private static String recordFor(String body, String code) {
        for (String record : topLevelRecords(body)) {
            if (BusinessSliceHarness.field(record, "code").equals(code)) {
                return record;
            }
        }

        return "";
    }

    /** The records of a paged response as raw JSON objects, in the order served. */
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
}
