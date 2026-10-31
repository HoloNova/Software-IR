package io.kcg.sir.application.conformance;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.ast.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class FitnessReferenceContractTest {
    private static final Set<String> REMOVED = Set.of("MyMonthHours", "MyMonthAverage", "CoachCheckIns", "CoachMonthVisits");
    private static final Set<String> ANONYMOUS = Set.of("ListPrograms");
    private static final Map<String, String> ROUTES = Map.of(
            "ListPrograms", "/api/programs", "LogWorkout", "/api/check-ins",
            "MyMonthVisits", "/api/my/month/visits", "MyMonthHours", "/api/my/month/hours",
            "MyMonthAverage", "/api/my/month/average", "CoachCheckIns", "/api/coach/check-ins",
            "CoachMonthVisits", "/api/coach/month/visits");

    @Test
    void referenceHasExactlySevenContractRoutesAndRealCredentialIdentity() throws Exception {
        var compiled = compile(ConformanceFixtures.readResource("/valid/fitness-check-in.sir"));
        var capabilities = capabilities(compiled);
        assertEquals(ROUTES.keySet(), capabilities.keySet());
        ROUTES.forEach((name, route) -> assertEquals(route, capabilities.get(name).route(), name));
        for (var capability : capabilities.values()) {
            assertEquals(!ANONYMOUS.contains(capability.javaName()), capability.authenticated(), capability.javaName());
            assertEquals(capability.javaName().equals("LogWorkout") ? SpringBootDeclaration.HttpMethod.POST
                    : SpringBootDeclaration.HttpMethod.GET, capability.httpMethod());
        }
        assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.content().contains("getExpiresAt()")
                && f.relativePath().contains("Credential")), "authentication must consume a real expiring credential");
    }

    @Test
    void baseCompilesAndRemovesOnlyTheFourNewCapabilities() throws Exception {
        String reference = ConformanceFixtures.readResource("/valid/fitness-check-in.sir").replace("\r\n", "\n");
        String base = Files.readString(root().resolve("experiments/slm/drafts/fitness-check-in/base.sir")).replace("\r\n", "\n");
        var expected = new TreeMap<>(declarations(reference));
        REMOVED.forEach(expected::remove);
        assertEquals(expected, declarations(base), "preserve every declaration other than the four removed capabilities");
        var baseCompiled = compile(base);
        assertEquals(Set.of("ListPrograms", "LogWorkout", "MyMonthVisits"), capabilities(baseCompiled).keySet());
        for (String missing : REMOVED) {
            assertFalse(baseCompiled.generatedFiles().stream()
                    .anyMatch(f -> f.relativePath().endsWith("/" + missing + "Controller.java")), missing);
        }
    }

    @Test
    void contractRoutesAndSuccessStatusesMatchTheActualGeneratedControllers() throws Exception {
        var compiled = compile(ConformanceFixtures.readResource("/valid/fitness-check-in.sir"));
        var contract = FitnessAssertions.decode(Files.readString(root().resolve("experiments/slm/drafts/fitness-check-in/contract.json")));
        assertEquals("example.gym", contract.path("namespace").asText());
        assertEquals("fitness.check-in", contract.path("family").asText());
        assertEquals("train", contract.path("split").asText());
        assertEquals(7, contract.path("interfaces").size());
        for (var entry : capabilities(compiled).entrySet()) {
            var capability = entry.getValue();
            var api = contract.path("interfaces").path(entry.getKey());
            assertEquals(capability.httpMethod().name() + " " + capability.route(), api.path("route").asText());
            boolean created = entry.getKey().equals("LogWorkout");
            assertEquals(created ? 201 : 200, api.path("success_status").intValue());
            String controller = compiled.generatedFiles().stream()
                    .filter(f -> f.relativePath().endsWith("/" + capability.controllerName() + ".java"))
                    .findFirst().orElseThrow().content();
            assertEquals(created, controller.contains("@ResponseStatus(HttpStatus.CREATED)"), entry.getKey());
        }
    }

    @Test
    void harnessUsesRealExternalCredentialsAndAnAnonymousReadinessRoute(@org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        var environment = BusinessSliceHarnessEvidenceTest.environment(temp.resolve("work"), temp.resolve("evidence"),
                FitnessCheckInBusinessConformanceIT.FIXTURE.enabledProperty());
        var slice = FitnessCheckInBusinessConformanceIT.createSlice(environment);
        var child = slice.childEnvironment(slice.runtimeUrl());
        var spring = new org.springframework.core.env.StandardEnvironment();
        spring.getPropertySources().remove("systemProperties");
        spring.getPropertySources().replace("systemEnvironment", new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", Map.copyOf(child)));
        assertEquals("external", spring.getProperty("kcg.actor-identity.mode"));
        assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_LOCAL_ID"));
        assertEquals("/api/programs", java.net.URI.create(slice.readinessUrl()).getPath());
        assertEquals("page=1&size=10", java.net.URI.create(slice.readinessUrl()).getRawQuery());
        assertFalse(capabilities(compile(ConformanceFixtures.readResource("/valid/fitness-check-in.sir"))).get("ListPrograms").authenticated());
    }

    @Test
    void ddlDeclaresTheGeneratedColumnsAndForeignKeys() throws Exception {
        String ddl = ConformanceFixtures.readResource("/conformance/mysql/fitness-check-in-ddl.sql").replaceAll("\\s+", " ");
        assertTrue(ddl.contains("hours DECIMAL(4,2) NOT NULL"));
        assertTrue(ddl.contains("checked_in_at DATETIME(6) NOT NULL"));
        assertTrue(ddl.contains("role VARCHAR(6) NOT NULL DEFAULT 'MEMBER'"));
        assertTrue(ddl.contains("CONSTRAINT gym_check_in_member FOREIGN KEY (member_id)"));
        assertTrue(ddl.contains("COLLATE=utf8mb4_bin"));
    }

    static ProjectCompilation.Compiled compile(String text) {
        var id = SourceId.of("fitness-check-in.sir");
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var result = ProjectCompilation.compile(new SourceSnapshot(id, Map.of(id, text.getBytes(StandardCharsets.UTF_8))),
                new SpringBootGenerator()::generate, diagnostics);
        assertTrue(result.isPresent() && diagnostics.stream().noneMatch(ExecutionDiagnostic::isError), diagnostics.toString());
        return result.orElseThrow();
    }

    private static Map<String, SpringBootDeclaration.CapabilityDeclaration> capabilities(ProjectCompilation.Compiled model) {
        var result = new TreeMap<String, SpringBootDeclaration.CapabilityDeclaration>();
        model.loweredModel().declarations().stream()
                .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
                .forEach(c -> assertNull(result.put(c.javaName(), c), "duplicate capability"));
        return Map.copyOf(result);
    }

    private static Map<String, String> declarations(String source) {
        var parsed = SirParser.create().parseProject(new SirSource(SourceId.of("fitness-check-in.sir"), source));
        assertTrue(parsed.isSuccess(), parsed.diagnostics().toString());
        var result = new TreeMap<String, String>();
        for (var declaration : ((AstSourceUnit.Root) parsed.unit().orElseThrow()).declarations()) {
            String name = switch (declaration) {
                case AstEntityDecl x -> x.name().text();
                case AstViewDecl x -> x.name().text();
                case AstInputDecl x -> x.name().text();
                case AstCapabilityDecl x -> x.name().text();
                case AstEnumDecl x -> x.name().text();
                case AstSemanticTypeDecl x -> x.name().text();
                case AstErrorDecl x -> x.name().text();
                default -> throw new AssertionError("unexpected declaration " + declaration);
            };
            var span = declaration.span();
            result.put(name, source.substring(source.offsetByCodePoints(0, span.start().codePointOffset()),
                    source.offsetByCodePoints(0, span.end().codePointOffset())).strip());
        }
        return Map.copyOf(result);
    }

    private static Path root() {
        for (Path path = Path.of("").toAbsolutePath(); path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("experiments/slm/drafts/fitness-check-in/spec.md"))) return path;
        }
        throw new AssertionError("repository root not found");
    }
}
