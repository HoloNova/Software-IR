package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.semantic.model.NormalizedFeatureUse;
import io.kcg.sir.source.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** G9 consumes ordinary SIR: source edits must preserve the same permissions as generation. */
class CrudAccessContractTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void publicReadsKeepOwnerWritesAndExcludeSystemFields() throws Exception {
        var result = compile(post("read: public; writeExclude: [status, createdAt]; path: \"market/items\";"));
        String get = file(result, "/GetPostService.java"), list = file(result, "/ListPostService.java");
        assertFalse(get.contains("actorId"), get);
        assertFalse(list.contains("Post::getAuthorId"), list);
        String update = file(result, "/UpdatePostService.java");
        assertTrue(update.contains("getAuthorId()"), update);
        assertFalse(update.contains("setStatus("), update);
        assertFalse(update.contains("setCreatedAt("), update);
        assertFalse(file(result, "/UpdatePostInput.java").contains("setStatus("));
        assertTrue(file(result, "/PostView.java").contains("getStatus()"));
        assertTrue(file(result, "/GetPostController.java").contains("/api/market/items"));
    }

    @Test void rolesGuardAllWritesBeforeLoadingTheBusinessRow() throws Exception {
        var result = compile(post("writer: User; writeRole: ADMIN;"));
        for (String name : List.of("Create", "Update", "Delete")) {
            String service = file(result, "/" + name + "PostService.java");
            assertTrue(service.contains("getRole()"), service);
            assertTrue(service.contains("Role.ADMIN"), service);
            assertTrue(service.contains("PostForbidden"), service);
            if (!name.equals("Create")) assertTrue(service.indexOf("PostForbidden") < service.lastIndexOf("selectById("), service);
        }
        assertFalse(file(result, "/GetPostService.java").contains("getRole()"));
        assertTrue(file(result, "/PostForbiddenException.java").contains("HttpStatus.FORBIDDEN"));
    }

    @Test void exclusionsPreserveVersionedPatchAndDefaults() throws Exception {
        String source = post("writeExclude: [status, createdAt];").replace("field text: Text;", "field text: Text; field version: Version;");
        var result = compile(source);
        String dto = file(result, "/UpdatePostInput.java"), service = file(result, "/UpdatePostService.java");
        assertTrue(dto.contains("class Changes"), dto);
        assertFalse(dto.contains("setStatus("), dto);
        assertTrue(service.contains("StalePost"), service);
        assertFalse(service.contains("setStatus("), service);
    }

    @Test void invalidCombinationsNeverBecomeMorePermissiveCrud() throws Exception {
        for (String parameters : List.of("writeRole: ADMIN;", "writer: Owner;", "writer: Owner; writeRole: ADMIN;", "writer: User; roleField: missing; writeRole: ADMIN;",
                "writer: User; roleField: status; writeRole: ADMIN;", "writer: User; writeRole: UNKNOWN;", "read: everybody;",
                "writeExclude: [missing];", "writeExclude: [status, status];", "writeExclude: [text];", "path: \"../posts\";",
                "path: \"a//b\";", "path: \"a/{id}\";", "path: \"/posts\";")) {
            assertFalse(errors(post(parameters)).isEmpty(), parameters);
        }
        assertFalse(errors(post("writeExclude: [text, status, createdAt];")).isEmpty());
    }

    @Test void badListElementsKeepTheirOwnSourceLocations() throws Exception {
        for (var test : List.of(Map.entry("[status, missing]", "missing"), Map.entry("[status, status]", "status"))) {
            String source = post("writeExclude: " + test.getKey() + ";");
            var diagnostic = errors(source).stream().filter(d -> d.code().equals("SIR-FEATURE-003")).findFirst().orElseThrow();
            var span = diagnostic.sourceSpan().orElseThrow();
            assertEquals(test.getValue(), source.substring(span.start().codePointOffset(), span.end().codePointOffset()));
            assertEquals(source.lastIndexOf(test.getValue()), span.start().codePointOffset(), "point at the erroneous list element, not its whole use");
        }
    }

    @Test void namesSelectionsAndTextDefaultsWorkForAuthorsFeatures() throws Exception {
        String extra = """
            feature PublicSummary {
              parameter entity: name;
              parameter hidden: names = [];
              parameter route: text = "summary/rows";
              template ```
            view ${entity}Summary from ${entity} {
              field ${entity.identity.name}: ${entity.identity.type};
            ${for f in entity.fields except secret, hidden}
              field ${f.name}: ${f.type};
            ${end}
            }
            capability Summary${entity} {
              output Unit;
              requires readonly;
              expose query at "/${route}";
              workflow { return unit; }
            }
            ```;
            }
            use PublicSummary { entity: Post; hidden: [status, createdAt]; }
            """;
        var result = compile(base().replace("    use Crud { entity: Note;", extra + "    use Crud { entity: Note;"));
        String dto = file(result, "/PostSummary.java");
        assertTrue(dto.contains("getText()"), dto);
        assertFalse(dto.contains("getStatus()"), dto);
        assertTrue(file(result, "/SummaryPostController.java").contains("/api/summary/rows"));
    }

    @Test void renameUsesFieldAndEnumIdentitiesNotMatchingStrings() throws Exception {
        String source = post("writer: User; writeRole: ADMIN; writeExclude: [status, createdAt]; path: \"status/posts\";");
        var snapshot = ProjectTestSupport.oneFile("project.sir", source);
        var commands = List.<Command>of(
                new Command.Rename(Selector.root().named("declarations", "Post").named("fields", "status"), "state"),
                new Command.Rename(Selector.root().named("declarations", "Role").named("members", "ADMIN"), "EDITOR"));
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(snapshot,
                new CommandBatch(CommandBatch.PROTOCOL, "CrudDesk", SourceEditor.sha256(snapshot), commands)));
        String text = new String(edited.sources().bytes(ENTRY), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("[state, createdAt]"), text);
        assertTrue(text.contains("writeRole: EDITOR;"), text);
        assertTrue(text.contains("\"status/posts\""), text);
        assertTrue(text.contains("field status: Count"), text);
        compile(text);
    }

    @Test void renamingTheDefaultRoleFieldMaterializesItsBindingAtTheUse() throws Exception {
        String source = post("writer: User; writeRole: ADMIN;");
        var snapshot = ProjectTestSupport.oneFile("project.sir", source);
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(snapshot,
                new CommandBatch(CommandBatch.PROTOCOL, "CrudDesk", SourceEditor.sha256(snapshot), List.of(
                        new Command.Rename(Selector.root().named("declarations", "User").named("fields", "role"), "permission")))));
        String text = new String(edited.sources().bytes(ENTRY), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("roleField: permission;"), text);
        assertTrue(file(compile(text), "/UpdatePostService.java").contains("getPermission()"));
    }

    @Test void genericFeatureSelectionsRenameOnlyTheChosenEntity() throws Exception {
        String extra = """
            feature PublicSummary {
              parameter entity: name;
              parameter hidden: names = [];
              template ```
            view ${entity}Summary from ${entity} {
              field ${entity.identity.name}: ${entity.identity.type};
            ${for f in entity.fields except secret, hidden}
              field ${f.name}: ${f.type};
            ${end}
            }
            ```;
            }
            use PublicSummary { entity: Post; hidden: [status]; }
            use PublicSummary { entity: User; hidden: [status]; }
            """;
        String source = base().replace("    use Crud { entity: Note;", extra + "    use Crud { entity: Note;");
        var snapshot = ProjectTestSupport.oneFile("project.sir", source);
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(snapshot,
                new CommandBatch(CommandBatch.PROTOCOL, "CrudDesk", SourceEditor.sha256(snapshot), List.of(
                        new Command.Rename(Selector.root().named("declarations", "Post").named("fields", "status"), "state")))));
        String text = new String(edited.sources().bytes(ENTRY), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("hidden: [state];"), text);
        assertTrue(text.contains("hidden: [status];"), text);
    }

    @Test void sourceEditsRoundTripTextAndListsWithoutEditingExpansions() throws Exception {
        String source = ProjectTestSupport.resource("valid/crud-access.sir").replace("\r\n", "\n");
        source = source.substring(0, source.indexOf("    use Crud {")) + source.substring(source.indexOf("    entity Post"));
        var snapshot = ProjectTestSupport.oneFile("project.sir", source);
        var use = Selector.root().named("declarations", "Crud");
        var field = new io.kcg.sir.semantic.schema.StructNode("NormalizedFeatureArgument", List.of(
                new io.kcg.sir.semantic.schema.StructNode.Slot("name", io.kcg.sir.semantic.schema.SlotKind.FACT, new io.kcg.sir.semantic.schema.StructValue.Text("roleField")),
                new io.kcg.sir.semantic.schema.StructNode.Slot("value", io.kcg.sir.semantic.schema.SlotKind.FACT, new io.kcg.sir.semantic.schema.StructValue.Text("role"))));
        var edited = assertInstanceOf(EditResult.Edited.class, new SourceEditor().edit(snapshot,
                new CommandBatch(CommandBatch.PROTOCOL, "CrudAccess", SourceEditor.sha256(snapshot), List.of(
                        new Command.Update(use.named("arguments", "path").slot("value"), new io.kcg.sir.semantic.schema.StructValue.Text("\"catalog/posts\"")),
                        new Command.Update(use.named("arguments", "writeExclude").slot("value"), new io.kcg.sir.semantic.schema.StructValue.Text("[]")),
                        new Command.Add(use.slot("arguments"), new io.kcg.sir.semantic.schema.StructValue.Child(field))))));
        String text = new String(edited.sources().bytes(ENTRY), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.contains("path: \"catalog/posts\";"), text);
        assertTrue(text.contains("writeExclude: [];"), text);
        assertTrue(text.contains("roleField: role;"), text);
        assertFalse(text.contains("capability UpdatePost"), text);
        assertTrue(file(compile(text), "/GetPostController.java").contains("/api/catalog/posts"));
    }

    @Test void theRealDatabaseFixtureCompilesWithoutAReferenceEnvironment() throws Exception {
        compile(ProjectTestSupport.resource("valid/crud-access.sir"));
    }

    @Test void authoredFeaturesRetainCrLfWhenAnArgumentIsEdited() throws Exception {
        String source = ProjectTestSupport.resource("valid/user-feature.sir").replace("\r\n", "\n").replace("\n", "\r\n");
        var snapshot = ProjectTestSupport.oneFile("project.sir", source);
        String name = source.lines().filter(l -> l.startsWith("software ")).findFirst().orElseThrow().split("\\s+")[1];
        var result = new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, name, SourceEditor.sha256(snapshot), List.of(
                new Command.Update(Selector.root().named("declarations", "Tagging").named("arguments", "limit").slot("value"),
                        new io.kcg.sir.semantic.schema.StructValue.Text("5 days")))));
        assertTrue(result instanceof EditResult.Edited, () -> result + (result instanceof EditResult.Refused r
                ? r.candidate().map(c -> new String(c.bytes(ENTRY), java.nio.charset.StandardCharsets.UTF_8).replace("\r", "\\r")).orElse("") : ""));
        String text = new String(((EditResult.Edited) result).sources().bytes(ENTRY), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(source.replace("limit: 3 days;", "limit: 5 days;"), text);
    }

    private static String post(String parameters) throws Exception {
        return base().replace("use Crud { entity: Post; owner: author; }", "use Crud { entity: Post; owner: author; " + parameters + " }");
    }

    private static String base() throws Exception {
        return ProjectTestSupport.resource("valid/crud-feature.sir").replace("\r\n", "\n")
                .replace("    enum Level", "    enum Role { READER, ADMIN }\n    enum OtherRole { ADMIN, MEMBER }\n    enum Level")
                .replace("      field nick: Optional<Name>;", "      field nick: Optional<Name>; field role: Role default Role.READER; field status: Count default 0;")
                .replace("      field text: Text;", "      field text: Text; field status: Count default 0; field createdAt: Moment default now();")
                .replace("    use Crud { entity: User; }\n", "");
    }

    private static ProjectCompilation.Compiled compile(String source) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(ProjectTestSupport.oneFile("project.sir", source), new SpringBootGenerator()::generate, diagnostics);
        assertTrue(compiled.isPresent(), () -> diagnostics.toString());
        return compiled.orElseThrow();
    }

    private static List<ExecutionDiagnostic> errors(String source) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        ProjectCompilation.compile(ProjectTestSupport.oneFile("project.sir", source), new SpringBootGenerator()::generate, diagnostics);
        return diagnostics.stream().filter(ExecutionDiagnostic::isError).toList();
    }

    private static String file(ProjectCompilation.Compiled result, String suffix) {
        return result.generatedFiles().stream().filter(f -> f.relativePath().endsWith(suffix)).findFirst().orElseThrow().content();
    }
}
