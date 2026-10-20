package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.ProjectTestSupport.*;
import io.kcg.sir.api.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.semantic.symbol.*;
import io.kcg.sir.source.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectVisibilityTest {
    @TempDir Path temp;

    @Test void everyImportSensitiveReferenceRoleHasARealPositiveAndExactMissingImportRefusal() throws Exception {
        Path source = fixture(temp);
        addKeyedLookup(source);
        addGuardedUpdate(source);
        moveContracts(source);
        var positive = generate(source, temp.resolve("positive"));
        var model = model(positive.sources());
        Map<SymbolId, io.kcg.sir.semantic.model.NormalizedDeclaration> declarations = model.declarations().stream()
                .collect(Collectors.toMap(d -> d.id(), d -> d));
        Map<ReferenceRole, ReferenceSiteBinding> examples = new EnumMap<>(ReferenceRole.class);
        for (var binding : model.referenceSiteBindings().all()) {
            var owner = owner(model, declarations, binding);
            if (owner != null && !owner.span().source().equals(binding.site().span().source())) examples.putIfAbsent(binding.site().role(), binding);
        }
        var expected = EnumSet.allOf(ReferenceRole.class);
        // Local by construction: a workflow variable lives in its own workflow, and the credential clause names fields
        // of the entity it sits in (so does a unique key), so none of them can refer to a declaration in another file.
        expected.removeAll(EnumSet.of(ReferenceRole.UPDATE_TARGET, ReferenceRole.PERSIST_TARGET, ReferenceRole.DELETE_TARGET,
                ReferenceRole.CREDENTIAL_SUBJECT_FIELD, ReferenceRole.CREDENTIAL_EXPIRY_FIELD, ReferenceRole.UNIQUE_KEY_FIELD));
        assertEquals(expected, examples.keySet(), "every public role is either exercised cross-file or explicitly local by construction");
        var before = tree(temp);
        for (var example : examples.entrySet()) {
            var using = example.getValue().site().span().source();
            var owner = owner(model, declarations, example.getValue());
            String literal = "import " + owner.name() + " from \"" + owner.span().source().value() + "\";";
            Path file = source.resolve(using.value()); String original = Files.readString(file);
            assertTrue(original.contains(literal), "actual import line for " + example.getKey());
            Files.writeString(file, original.replace(literal, " ".repeat(literal.length())));
            var baseline = tree(temp);
            var result = assertInstanceOf(ToolchainResult.Failure.class, new ToolchainApplication().execute(
                    new ToolchainRequest(source, ENTRY, temp.resolve("negative-" + example.getKey()))));
            assertEquals(ExecutionStage.SEMANTIC, result.failedStage());
            var actual = result.diagnostics().stream().filter(ExecutionDiagnostic::isError).toList();
            var missingSites = model.referenceSiteBindings().all().stream().filter(b -> b.site().span().source().equals(using))
                    .filter(b -> Objects.equals(owner(model, declarations, b), owner)).map(b -> b.site().span()).toList();
            assertEquals(missingSites.size(), actual.size(), "one visibility diagnostic per resolved illegal site, no Type/Validate cascade: " + example.getKey());
            assertTrue(actual.stream().allMatch(d -> d.code().equals("SIR-SOURCE-VISIBILITY-001")), actual.toString());
            assertEquals(new HashSet<>(missingSites), actual.stream().map(d -> d.sourceSpan().orElseThrow()).collect(Collectors.toSet()));
            var related = result.diagnostics().stream().filter(d -> d.severity() == ExecutionSeverity.INFO).toList();
            assertEquals(missingSites.size(), related.size());
            assertTrue(related.stream().allMatch(d -> d.sourceSpan().orElseThrow().equals(owner.span()) && d.message().contains("related to " + using.value())));
            assertEquals(baseline, tree(temp));
            Files.writeString(file, original);
        }
        assertEquals(before, tree(temp));
    }

    @Test void badImportSourceDeclarationAndRepeatedSlotAreNeverSilentlyBoundByGlobalName() throws Exception {
        for (var mutation : List.of(
                new Mutation("modules/missing.sir", "Course", "SIR-SOURCE-IMPORT-001"),
                new Mutation("modules/student.sir", "Course", "SIR-SOURCE-IMPORT-002"),
                new Mutation("modules/course.sir", "MissingCourse", "SIR-SOURCE-IMPORT-002"))) {
            Path directory = Files.createDirectory(temp.resolve(mutation.code + "-" + mutation.name + "-" + mutation.from.hashCode()));
            Path source = fixture(directory), root = source.resolve("project.sir");
            String text = Files.readString(root).replace("import Course from \"modules/course.sir\";",
                    "import " + mutation.name + " from \"" + mutation.from + "\";");
            Files.writeString(root, text); var before = tree(directory);
            var failed = refusal(source, directory.resolve("output"));
            assertEquals(List.of(mutation.code), failed.diagnostics().stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).toList());
            assertEquals(ENTRY, failed.diagnostics().getFirst().sourceSpan().orElseThrow().source());
            assertTrue(failed.diagnostics().getFirst().sourceSpan().orElseThrow().start().line() > 1);
            assertEquals(before, tree(directory));
        }
        Path directory = Files.createDirectory(temp.resolve("duplicate")), source = fixture(directory), root = source.resolve("project.sir");
        Files.writeString(root, Files.readString(root).replace("imports {", "imports { import Course from \"modules/course.sir\";"));
        var before = tree(directory);
        assertEquals(List.of("SIR-SOURCE-IMPORT-003"), refusal(source, directory.resolve("output")).diagnostics().stream()
                .filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).toList());
        assertEquals(before, tree(directory));
    }

    @Test void selfAndFileImportCyclesAreRejectedButSameFileMutualRefsRemainLegal() throws Exception {
        Path source = fixture(temp), course = source.resolve("modules/course.sir");
        String original = Files.readString(course);
        Files.writeString(course, original.replace("imports {}", "imports { import Course from \"modules/course.sir\"; }"));
        var before = tree(temp);
        assertEquals("SIR-SOURCE-IMPORT-004", refusal(source, temp.resolve("self")).diagnostics().getFirst().code()); assertEquals(before, tree(temp));
        Files.writeString(course, original.replace("imports {}", "imports { import Enrollment from \"modules/enrollment.sir\"; }"));
        before = tree(temp);
        var cycle = refusal(source, temp.resolve("cycle"));
        assertEquals(List.of("SIR-SOURCE-IMPORT-005"), cycle.diagnostics().stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).toList());
        assertEquals(SourceId.of("modules/enrollment.sir"), cycle.diagnostics().getFirst().sourceSpan().orElseThrow().source()); assertEquals(before, tree(temp));
        String root = Files.readString(source.resolve("project.sir"));
        root = root.substring(0, root.indexOf("  sources {")) + """
                sources {} imports {} declarations {
                  entity A persistent { identity id: Id generated auto; field b: Ref<B>; }
                  entity B persistent { identity id: Id generated auto; field a: Ref<A>; }
                } }
                """;
        Files.writeString(source.resolve("project.sir"), root);
        assertInstanceOf(ToolchainResult.Success.class, new ToolchainApplication().execute(new ToolchainRequest(source, ENTRY, temp.resolve("mutual"))));
    }

    @Test void crossFileDuplicateNamesAndIdsKeepBothDeclarationLocations() throws Exception {
        Path source = fixture(temp), student = source.resolve("modules/student.sir");
        Files.writeString(student, Files.readString(student).replace("declarations {", "declarations { input GetCourseInput { field id: Id; }"));
        var parsed = parsedSources(source);
        var semantic = new SirSemanticAnalyzer().analyzeProject(parsed);
        assertFalse(semantic.isSuccess());
        var duplicate = semantic.diagnostics().stream().filter(d -> d.code().value().equals("SIR-SYMBOL-001")).findFirst().orElseThrow();
        assertFalse(duplicate.related().isEmpty());
        assertEquals(Set.of(ENTRY, SourceId.of("modules/student.sir")), Set.of(duplicate.primarySpan().source(), duplicate.related().getFirst().span().source()));
        var before = tree(temp); assertPublicPair(refusal(source, temp.resolve("duplicate-name"))); assertEquals(before, tree(temp));
        Files.writeString(student, """
                sir 0.3 imports {} declarations {
                  entity Student persistent { identity id: Id generated auto; field studentNo: Text; field name: Text; }
                  capability AnotherSearch @id("search-course-enrollments") { output Unit; requires readonly; expose query; workflow { return unit; } }
                }
                """);
        var identity = new SirSemanticAnalyzer().analyzeProject(parsedSources(source));
        assertFalse(identity.isSuccess());
        var repeatedId = identity.diagnostics().stream().filter(d -> d.code().value().equals("SIR-IDENTITY-002")).findFirst().orElseThrow();
        assertEquals(Set.of(ENTRY, SourceId.of("modules/student.sir")), Set.of(repeatedId.primarySpan().source(), repeatedId.related().getFirst().span().source()));
        before = tree(temp); assertPublicPair(refusal(source, temp.resolve("duplicate-id"))); assertEquals(before, tree(temp));
    }

    private static void assertPublicPair(ToolchainResult.Failure failure) {
        assertEquals(1, failure.diagnostics().stream().filter(ExecutionDiagnostic::isError).count(), failure.diagnostics().toString());
        assertEquals(1, failure.diagnostics().stream().filter(d -> d.severity() == ExecutionSeverity.INFO).count());
        assertEquals(Set.of(ENTRY, SourceId.of("modules/student.sir")), failure.diagnostics().stream().map(d -> d.sourceSpan().orElseThrow().source()).collect(Collectors.toSet()));
        assertEquals(failure.diagnostics().getFirst().code(), failure.diagnostics().getLast().code());
    }

    private static ProjectSemanticInput parsedSources(Path root) throws Exception {
        Map<SourceId, byte[]> inputs = new LinkedHashMap<>();
        for (String file : FILES) inputs.put(SourceId.of(file), Files.readAllBytes(root.resolve(file)));
        return parse(new SourceSnapshot(ENTRY, inputs));
    }
    private static ToolchainResult.Failure refusal(Path source, Path output) {
        var result = assertInstanceOf(ToolchainResult.Failure.class, new ToolchainApplication().execute(new ToolchainRequest(source, ENTRY, output)));
        assertEquals(ExecutionStage.SEMANTIC, result.failedStage()); return result;
    }
    private static io.kcg.sir.semantic.model.NormalizedDeclaration owner(NormalizedSemanticModel model,
            Map<SymbolId, io.kcg.sir.semantic.model.NormalizedDeclaration> declarations, ReferenceSiteBinding binding) {
        var symbol = model.symbols().byId(binding.targetSymbol()).orElseThrow();
        var id = switch (symbol) { case Symbol.FieldSymbol f -> f.ownerId(); case Symbol.EnumMemberSymbol e -> e.enumId(); default -> symbol.id(); };
        return declarations.get(id);
    }
    /**
     * Gives this test's own copy of the project a load by a unique field of an imported entity, the one
     * reference role the shared fixture has no use for. The fixture stays as it is because other
     * tests count its files and compare its digests.
     */
    private static void addKeyedLookup(Path source) throws Exception {
        Path course = source.resolve("modules/course.sir"), root = source.resolve("project.sir");
        String entity = Files.readString(course), project = Files.readString(root);
        String code = "field code: Code;";
        String input = "input GetCourseInput { field id: Id; }";
        String anchor = "    capability CreateCourse {";
        assertTrue(entity.contains(code) && project.contains(input) && project.contains(anchor), "the fixture changed under this test");
        Files.writeString(course, entity.replace(code, code.substring(0, code.length() - 1) + " unique;"));
        Files.writeString(root, project.replace(input, input + "\n    input FindCourseByCodeInput { field code: Text; }")
                .replace(anchor, "    capability GetCourseByCode {\n"
                        + "      input FindCourseByCodeInput;\n      output CourseDetail;\n      fails CourseNotFound;\n"
                        + "      requires readonly;\n      expose query;\n"
                        + "      workflow {\n        load Course by code: input.code as course else CourseNotFound;\n        return course;\n      }\n    }\n"
                        + anchor));
    }

    /**
     * Gives this test's own copy of the project an update with a guard whose error is declared in another
     * file, the one reference role neither the shared fixture nor the keyed lookup uses.
     */
    private static void addGuardedUpdate(Path source) throws Exception {
        Path root = source.resolve("project.sir");
        String project = Files.readString(root);
        String input = "input GetCourseInput { field id: Id; }";
        String anchor = "    capability CreateCourse {";
        assertTrue(project.contains(input) && project.contains(anchor), "the fixture changed under this test");
        Files.writeString(root, project.replace(input, input + "\n    input RenameStudentInput { field id: Id; field name: Text; }")
                .replace(anchor, "    capability RenameStudent {\n"
                        + "      input RenameStudentInput;\n      output StudentSummary;\n      fails CourseNotFound;\n"
                        + "      requires atomic;\n      expose command;\n"
                        + "      workflow {\n        load Student by input.id as student else CourseNotFound;\n"
                        + "        update student when student.name != input.name else CourseNotFound { name: input.name; }\n"
                        + "        persist student;\n        return student;\n      }\n    }\n"
                        + anchor));
    }

    private static void moveContracts(Path source) throws Exception {
        Path file = source.resolve("project.sir"); String root = Files.readString(file);
        var result = SirParser.create().parseProject(new SirSource(ENTRY, root)); assertTrue(result.isSuccess());
        var unit = (AstSourceUnit.Root) result.unit().orElseThrow();
        var declarations = unit.declarations().stream().filter(d -> d instanceof AstInputDecl || d instanceof AstErrorDecl).toList();
        StringBuilder snippets = new StringBuilder(), imports = new StringBuilder();
        for (var d : declarations) {
            int start = root.offsetByCodePoints(0, d.span().start().codePointOffset()), end = root.offsetByCodePoints(0, d.span().end().codePointOffset());
            snippets.append(root, start, end).append('\n');
            String name = d instanceof AstInputDecl i ? i.name().text() : ((AstErrorDecl)d).name().text();
            imports.append("import ").append(name).append(" from \"modules/contracts.sir\";\n");
        }
        for (int i = declarations.size() - 1; i >= 0; i--) {
            var d = declarations.get(i); int start = root.offsetByCodePoints(0, d.span().start().codePointOffset()), end = root.offsetByCodePoints(0, d.span().end().codePointOffset());
            root = root.substring(0, start) + root.substring(end);
        }
        root = root.replace("sources {", "sources { source \"modules/contracts.sir\";").replace("imports {", "imports {\n" + imports);
        Files.writeString(file, root);
        Files.writeString(source.resolve("modules/contracts.sir"), "sir 0.3 imports { import Course from \"modules/course.sir\"; } declarations {\n" + snippets + "}");
    }
    private record Mutation(String from, String name, String code) {}
}
