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

class BlogReferenceContractTest {
    private static final Set<String> ADDED = Set.of("Publish", "AttachTag", "PostsByTag", "LikePost");
    private static final Map<String, String> ROUTES = Map.of(
            "CreatePost", "/api/myposts", "Publish", "/api/my/posts/{id}/publish",
            "Feed", "/api/posts", "ViewPost", "/api/posts/{id}",
            "AttachTag", "/api/posts/{postId}/tags", "PostsByTag", "/api/tags/{tagId}/posts",
            "LikePost", "/api/posts/{id}/like");

    @Test
    void croppedReferenceHasExactlySevenContractRoutesAndRealCredentialIdentity() throws Exception {
        var compiled = compile(ConformanceFixtures.readResource("/valid/blog.sir"));
        var capabilities = capabilities(compiled);
        assertEquals(ROUTES.keySet(), capabilities.keySet());
        ROUTES.forEach((name, route) -> assertEquals(route, capabilities.get(name).route(), name));
        for (var capability : capabilities.values()) {
            boolean write = Set.of("CreatePost", "Publish", "AttachTag", "LikePost").contains(capability.javaName());
            assertEquals(write, capability.authenticated(), capability.javaName());
            assertEquals(write ? SpringBootDeclaration.HttpMethod.POST : SpringBootDeclaration.HttpMethod.GET,
                    capability.httpMethod());
        }
        assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.relativePath().endsWith("/Like.java")));
        assertTrue(compiled.generatedFiles().stream().anyMatch(f -> f.content().contains("getExpiresAt()")
                && f.relativePath().contains("Credential")), "authentication must consume a real expiring credential");
        assertFalse(compiled.generatedFiles().stream().anyMatch(f ->
                f.relativePath().endsWith("/Comment.java") || f.relativePath().endsWith("/LoginController.java")));
    }

    @Test
    void baseCompilesAndRemovesOnlyTheFourNewCapabilities() throws Exception {
        String reference = ConformanceFixtures.readResource("/valid/blog.sir").replace("\r\n", "\n");
        String base = Files.readString(root().resolve("experiments/slm/drafts/blog/base.sir")).replace("\r\n", "\n");
        var expected = new TreeMap<>(declarations(reference));
        ADDED.forEach(expected::remove);
        assertEquals(expected, declarations(base), "preserve every declaration other than the four removed capabilities");
        var baseCompiled = compile(base);
        assertEquals(Set.of("CreatePost", "Feed", "ViewPost"), capabilities(baseCompiled).keySet());
        for (String missing : ADDED) {
            assertFalse(baseCompiled.generatedFiles().stream()
                    .anyMatch(f -> f.relativePath().endsWith("/" + missing + "Controller.java")), missing);
        }
    }

    @Test
    void contractRoutesAndSuccessStatusesMatchTheActualGeneratedControllers() throws Exception {
        var compiled = compile(ConformanceFixtures.readResource("/valid/blog.sir"));
        var contract = BlogAssertions.decode(Files.readString(root().resolve("experiments/slm/drafts/blog/contract.json")));
        assertEquals("example.blog", contract.path("namespace").asText());
        assertEquals("content.blog", contract.path("family").asText());
        assertEquals("train", contract.path("split").asText());
        assertEquals(7, contract.path("interfaces").size());
        for (var entry : capabilities(compiled).entrySet()) {
            var capability = entry.getValue();
            var api = contract.path("interfaces").path(entry.getKey());
            assertEquals(capability.httpMethod().name() + " " + capability.route(), api.path("route").asText());
            boolean created = Set.of("CreatePost", "AttachTag", "LikePost").contains(entry.getKey());
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
                BlogBusinessConformanceIT.FIXTURE.enabledProperty());
        var slice = BlogBusinessConformanceIT.createSlice(environment);
        var child = slice.childEnvironment(slice.runtimeUrl());
        var spring = new org.springframework.core.env.StandardEnvironment();
        spring.getPropertySources().remove("systemProperties");
        spring.getPropertySources().replace("systemEnvironment", new org.springframework.core.env.SystemEnvironmentPropertySource("systemEnvironment", Map.copyOf(child)));
        assertEquals("external", spring.getProperty("kcg.actor-identity.mode"));
        assertFalse(child.containsKey("KCG_ACTOR_IDENTITY_LOCAL_ID"));
        assertFalse(child.containsKey("SPRING_PROFILES_ACTIVE"));
        assertFalse(child.containsKey("DEEPSEEK_API_KEY"));
        assertEquals("/api/posts", java.net.URI.create(slice.readinessUrl()).getPath());
        assertEquals("keyword=&page=1&size=10", java.net.URI.create(slice.readinessUrl()).getRawQuery());
        assertFalse(capabilities(compile(ConformanceFixtures.readResource("/valid/blog.sir"))).get("Feed").authenticated());
    }

    @Test
    void ddlDeclaresBothPairUniquenessConstraints() throws Exception {
        String ddl = ConformanceFixtures.readResource("/conformance/mysql/blog-ddl.sql").replaceAll("\\s+", " ");
        assertTrue(ddl.contains("CREATE TABLE `like`"));
        assertTrue(ddl.contains("UNIQUE KEY blog_post_tag (post_id, tag_id)"));
        assertTrue(ddl.contains("UNIQUE KEY blog_post_like (post_id, user_id)"));
        assertTrue(ddl.contains("content LONGTEXT NOT NULL"));
        assertTrue(ddl.contains("COLLATE=utf8mb4_bin"));
    }

    static ProjectCompilation.Compiled compile(String text) {
        var id = SourceId.of("blog.sir");
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
        var parsed = SirParser.create().parseProject(new SirSource(SourceId.of("blog.sir"), source));
        assertTrue(parsed.isSuccess(), parsed.diagnostics().toString());
        var result = new TreeMap<String, String>();
        for (var declaration : ((AstSourceUnit.Root) parsed.unit().orElseThrow()).declarations()) {
            String name = switch (declaration) {
                case AstEntityDecl x -> x.name().text();
                case AstViewDecl x -> x.name().text();
                case AstInputDecl x -> x.name().text();
                case AstCapabilityDecl x -> x.name().text();
                case AstEnumDecl x -> x.name().text();
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
            if (Files.isRegularFile(path.resolve("experiments/slm/drafts/blog/spec.md"))) return path;
        }
        throw new AssertionError("repository root not found");
    }
}
