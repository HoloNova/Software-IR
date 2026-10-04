package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import static io.kcg.sir.application.MultiSourceTestSupport.*;
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

class MultiSourceVisibilityTest {
    @TempDir Path temp;

    @Test void everyImportSensitiveReferenceRoleHasARealPositiveAndExactMissingImportRefusal() throws Exception {
        Path source = fixture(temp);
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
        expected.removeAll(EnumSet.of(ReferenceRole.UPDATE_TARGET, ReferenceRole.PERSIST_TARGET));
        assertEquals(expected, examples.keySet(), "every public role is either exercised cross-file or explicitly local-variable-only");
        var before = tree(temp);
        for (var example : examples.entrySet()) {
            var using = example.getValue().site().span().source();
            var owner = owner(model, declarations, example.getValue());
            String literal = "import " + owner.name() + " from \"" + owner.span().source().value() + "\";";
            Path file = source.resolve(using.value()); String original = Files.readString(file);
            assertTrue(original.contains(literal), "actual import line for " + example.getKey());
            Files.writeString(file, original.replace(literal, " ".repeat(literal.length())));
            var baseline = tree(temp);
            var result = assertInstanceOf(ProjectToolchainResult.Failure.class, new ToolchainApplication().executeProject(
                    new ProjectToolchainRequest(source, ENTRY, temp.resolve("negative-" + example.getKey()))));
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
                  entity A persistent { identity id: Int64 generated auto; field b: Ref<B>; }
                  entity B persistent { identity id: Int64 generated auto; field a: Ref<A>; }
                } }
                """;
        Files.writeString(source.resolve("project.sir"), root);
        assertInstanceOf(ProjectToolchainResult.Success.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(source, ENTRY, temp.resolve("mutual"))));
    }

    @Test void crossFileDuplicateNamesAndIdsKeepBothDeclarationLocations() throws Exception {
        Path source = fixture(temp), student = source.resolve("modules/student.sir");
        Files.writeString(student, Files.readString(student).replace("declarations {", "declarations { input GetCourseInput { field id: Int64; }"));
        var parsed = parsedSources(source);
        var semantic = new SirSemanticAnalyzer().analyzeProject(parsed);
        assertFalse(semantic.isSuccess());
        var duplicate = semantic.diagnostics().stream().filter(d -> d.code().value().equals("SIR-SYMBOL-001")).findFirst().orElseThrow();
        assertFalse(duplicate.related().isEmpty());
        assertEquals(Set.of(ENTRY, SourceId.of("modules/student.sir")), Set.of(duplicate.primarySpan().source(), duplicate.related().getFirst().span().source()));
        var before = tree(temp); assertPublicPair(refusal(source, temp.resolve("duplicate-name"))); assertEquals(before, tree(temp));
        Files.writeString(student, """
                sir 0.2 imports {} declarations {
                  entity Student persistent { identity id: Int64 generated auto; field studentNo: String; field name: String; }
                  capability AnotherSearch @id("search-course-enrollments") { output Unit; requires readonly; expose query; workflow { return unit; } }
                }
                """);
        var identity = new SirSemanticAnalyzer().analyzeProject(parsedSources(source));
        assertFalse(identity.isSuccess());
        var repeatedId = identity.diagnostics().stream().filter(d -> d.code().value().equals("SIR-IDENTITY-002")).findFirst().orElseThrow();
        assertEquals(Set.of(ENTRY, SourceId.of("modules/student.sir")), Set.of(repeatedId.primarySpan().source(), repeatedId.related().getFirst().span().source()));
        before = tree(temp); assertPublicPair(refusal(source, temp.resolve("duplicate-id"))); assertEquals(before, tree(temp));
    }

    private static void assertPublicPair(ProjectToolchainResult.Failure failure) {
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
    private static ProjectToolchainResult.Failure refusal(Path source, Path output) {
        var result = assertInstanceOf(ProjectToolchainResult.Failure.class, new ToolchainApplication().executeProject(new ProjectToolchainRequest(source, ENTRY, output)));
        assertEquals(ExecutionStage.SEMANTIC, result.failedStage()); assertEquals(FailureDisposition.NO_CHANGES, result.disposition()); return result;
    }
    private static io.kcg.sir.semantic.model.NormalizedDeclaration owner(NormalizedSemanticModel model,
            Map<SymbolId, io.kcg.sir.semantic.model.NormalizedDeclaration> declarations, ReferenceSiteBinding binding) {
        var symbol = model.symbols().byId(binding.targetSymbol()).orElseThrow();
        var id = switch (symbol) { case Symbol.FieldSymbol f -> f.ownerId(); case Symbol.EnumMemberSymbol e -> e.enumId(); default -> symbol.id(); };
        return declarations.get(id);
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
        Files.writeString(source.resolve("modules/contracts.sir"), "sir 0.2 imports { import Course from \"modules/course.sir\"; } declarations {\n" + snippets + "}");
    }
    private record Mutation(String from, String name, String code) {}
}
