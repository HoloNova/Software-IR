package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Lightweight contracts only: no database, generated-project build or application process. */
class BoundedProblemReferenceTest {
    static final List<String> PROBLEMS = List.of("maintenance-work-order", "staffing-shift-roster");
    private static final Map<String, Set<String>> REMOVED = Map.of(
            "maintenance-work-order", Set.of("EditWorkOrder", "ListWorkOrders", "GenerateInspections", "CloseOverdueInspections"),
            "staffing-shift-roster", Set.of("GenerateWeeklyShifts", "AssignEmployee", "ShiftHeadcount"));

    @Test
    void runtimeReferencesAreExactlyTheApprovedAnswersAndBothBasesStillGenerate() throws Exception {
        for (String problem : PROBLEMS) {
            String reference = normalized(ConformanceFixtures.readResource("/valid/" + problem + ".sir"));
            assertEquals(normalized(Files.readString(draft(problem).resolve("reference.sir"))), reference, problem);
            var expected = new TreeMap<>(capabilities(compile(reference)));
            REMOVED.get(problem).forEach(expected::remove);
            var base = capabilities(compile(Files.readString(draft(problem).resolve("base.sir"))));
            assertEquals(expected.keySet(), base.keySet(), problem);
        }
    }

    @Test
    void generatedRoutesAuthenticationAndCreationStatusesMatchEachApprovedContract() throws Exception {
        for (String problem : PROBLEMS) {
            var compiled = compile(ConformanceFixtures.readResource("/valid/" + problem + ".sir"));
            var contract = new ObjectMapper().readTree(draft(problem).resolve("contract.json").toFile());
            var capabilities = capabilities(compiled);
            assertEquals(contract.path("interfaces").size(), capabilities.size());
            for (var entry : capabilities.entrySet()) {
                var capability = entry.getValue();
                var api = contract.path("interfaces").path(entry.getKey());
                assertEquals(api.path("route").asText(), capability.httpMethod().name() + " " + capability.route());
                assertEquals("Bearer".equals(api.path("authentication").asText()), capability.authenticated());
                String controller = compiled.generatedFiles().stream()
                        .filter(f -> f.relativePath().endsWith("/" + capability.controllerName() + ".java"))
                        .findFirst().orElseThrow().content();
                assertEquals(api.path("success_status").asInt() == 201,
                        controller.contains("@ResponseStatus(HttpStatus.CREATED)"), entry.getKey());
            }
            assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().contains("Credential")
                    && f.content().contains("getExpiresAt()")), "real expiring database credentials");
        }
    }

    @Test
    void ddlAndSeedAreTheApprovedSqlBlocksNotNewBusinessRules() throws Exception {
        for (String problem : PROBLEMS) {
            String spec = normalized(Files.readString(draft(problem).resolve("spec.md")));
            var sql = Pattern.compile("(?ms)^```sql\\n(.*?)^```").matcher(spec);
            assertTrue(sql.find());
            assertEquals(sql.group(1).strip(), ConformanceFixtures.readResource("/conformance/mysql/" + problem + "-ddl.sql").strip());
            assertTrue(sql.find());
            assertEquals(sql.group(1).strip(), ConformanceFixtures.readResource("/conformance/mysql/" + problem + "-seed.sql").strip());
            assertFalse(sql.find());
        }
    }

    @Test
    void databasesDoNotMaskTheBoundedLoopAndAuthorizationObligations() throws Exception {
        String maintenance = ConformanceFixtures.readResource("/conformance/mysql/maintenance-work-order-ddl.sql");
        String staffing = ConformanceFixtures.readResource("/conformance/mysql/staffing-shift-roster-ddl.sql");
        assertTrue(maintenance.contains("assignee BIGINT NULL"));
        assertTrue(maintenance.contains("version BIGINT NOT NULL"));
        assertFalse(maintenance.contains("assignee_id"));
        assertTrue(staffing.contains("UNIQUE KEY roster_shift_slot (store_id,day,band)"));
        assertTrue(staffing.contains("UNIQUE KEY roster_employee_slot (employee_id,shift_id)"));
        for (String ddl : List.of(maintenance, staffing)) {
            assertFalse(ddl.toUpperCase(java.util.Locale.ROOT).contains("CHECK ("));
            assertFalse(ddl.toUpperCase(java.util.Locale.ROOT).contains("TRIGGER"));
            assertTrue(ddl.contains("COLLATE=utf8mb4_bin"));
        }
    }

    @Test
    void oracleProjectionKeysAndPhysicalColumnsAreBoundToTheApprovedContracts() throws Exception {
        var projections = Map.of("maintenance-work-order", Map.of("FacilityView", MaintenanceWorkOrderChecks.FACILITY,
                        "WorkOrderView", MaintenanceWorkOrderChecks.WORK, "InspectionView", MaintenanceWorkOrderChecks.INSPECTION),
                "staffing-shift-roster", Map.of("StoreView", StaffingRosterChecks.STORE, "EmployeeView", StaffingRosterChecks.EMPLOYEE,
                        "ShiftView", StaffingRosterChecks.SHIFT, "AssignmentView", StaffingRosterChecks.ASSIGNMENT));
        for (String problem : PROBLEMS) {
            var contract = new ObjectMapper().readTree(draft(problem).resolve("contract.json").toFile());
            for (var entry : projections.get(problem).entrySet()) {
                var keys = new java.util.HashSet<String>();
                contract.path("view_fields").path(entry.getKey()).forEach(node -> keys.add(node.textValue()));
                assertEquals(keys, entry.getValue().columns().keySet(), entry.getKey());
                var columns = new java.util.HashSet<String>();
                contract.path("storage").path(entry.getValue().table()).forEach(node -> columns.add(node.textValue()));
                assertTrue(columns.containsAll(entry.getValue().columns().values()), entry.getKey());
            }
        }
    }

    @Test
    void bothHarnessesUseExternalCredentialsAndTheDeclaredAnonymousReadiness(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        for (var fixture : List.of(MaintenanceWorkOrderBusinessConformanceIT.FIXTURE, StaffingShiftRosterBusinessConformanceIT.FIXTURE)) {
            var environment = BusinessSliceHarnessEvidenceTest.environment(temp.resolve(fixture.runTokenPrefix()).resolve("work"),
                    temp.resolve(fixture.runTokenPrefix()).resolve("evidence"), fixture.enabledProperty());
            var slice = BoundedProblemConformance.createSlice(fixture, environment);
            var child = slice.childEnvironment(slice.runtimeUrl());
            var spring = new org.springframework.core.env.StandardEnvironment();
            spring.getPropertySources().remove("systemProperties");
            spring.getPropertySources().replace("systemEnvironment",
                    new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", Map.copyOf(child)));
            assertEquals("external", spring.getProperty("kcg.actor-identity.mode"));
            assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_LOCAL_ID"));
            assertTrue(slice.readinessUrl().endsWith(fixture.readinessPath()));
        }
    }

    static ProjectCompilation.Compiled compile(String source) {
        var id = SourceId.of("reference.sir");
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(id, Map.of(id, source.getBytes(StandardCharsets.UTF_8))),
                new SpringBootGenerator()::generate, diagnostics);
        assertTrue(compiled.isPresent() && diagnostics.isEmpty(), diagnostics.toString());
        return compiled.orElseThrow();
    }

    private static Map<String, SpringBootDeclaration.CapabilityDeclaration> capabilities(ProjectCompilation.Compiled compiled) {
        var result = new TreeMap<String, SpringBootDeclaration.CapabilityDeclaration>();
        compiled.loweredModel().declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).forEach(c -> assertNull(result.put(c.javaName(), c)));
        return Map.copyOf(result);
    }

    static Path draft(String problem) {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            Path draft = path.resolve("experiments/slm/drafts/" + problem);
            if (Files.isRegularFile(draft.resolve("spec.md"))) return draft;
        }
        throw new AssertionError("approved problem directory not found: " + problem);
    }

    private static String normalized(String text) { return text.replace("\r\n", "\n"); }
}
