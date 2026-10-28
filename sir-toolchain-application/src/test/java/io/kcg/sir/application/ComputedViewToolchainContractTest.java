package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.SirSemanticAnalyzer;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComputedViewToolchainContractTest {
    @Test void courseDisplaysRemainingSeatsWithoutAddingStorageFields() throws Exception {
        var compiled = compile(ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("topics/course/project.sir")));
        String view = content(compiled, "CourseView.java");
        assertTrue(view.contains("getRemaining()"), view);
        assertTrue(view.contains("getFull()"), view);
        var entity = compiled.loweredModel().declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast).filter(e -> e.javaName().equals("Course")).findFirst().orElseThrow();
        assertFalse(entity.fields().stream().anyMatch(f -> List.of("remaining", "full").contains(f.javaName())));
    }

    @Test void theActualNestedHttpFixtureGeneratesComputationsAtEveryRowScope() throws Exception {
        var compiled = compile(ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("valid/course-enrollment.sir")));
        String service = content(compiled, "SearchCourseEnrollmentsService.java");
        assertTrue(service.contains("Math.multiplyExact($row.getCapacity(), 2)"), service);
        assertTrue(service.contains(".getStatus()"), service);
        assertTrue(service.contains("EnrollmentStatus.ACTIVE"), service);
        assertTrue(service.contains(".getName()"), service);
        assertEquals(3, occurrences(service, ".selectList("), service);
        assertEquals(1, occurrences(service, ".selectCount("), service);
    }

    @Test void crossFileComputedReferencesRequireImportsAndRenameByTheirBindings() throws Exception {
        var source = multiSource();
        var positive = ProjectTestSupport.model(source);
        var view = positive.declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedView.class::isInstance)
                .map(io.kcg.sir.semantic.model.NormalizedView.class::cast).filter(v -> v.name().equals("CourseDetail")).findFirst().orElseThrow();
        var computed = (io.kcg.sir.semantic.model.NormalizedViewField.Computed) view.fields().stream()
                .filter(f -> f.name().equals("doubledCapacity")).findFirst().orElseThrow().value();
        var range = computed.expression().span();
        var binding = positive.referenceSiteBindings().all().stream().filter(b -> b.site().role() == ReferenceRole.MEMBER_ACCESS)
                .filter(b -> b.site().span().start().codePointOffset() >= range.start().codePointOffset()
                        && b.site().span().end().codePointOffset() <= range.end().codePointOffset())
                .filter(b -> b.site().span().source().equals(source.entry()))
                .filter(b -> positive.symbols().byId(b.targetSymbol()).orElseThrow().name().equals("capacity"))
                .findFirst().orElseThrow();
        var bytes = new LinkedHashMap<SourceId, byte[]>();
        for (var file : source.manifest().files()) bytes.put(file.sourceId(), source.bytes(file.sourceId()));
        String root = new String(bytes.get(source.entry()), StandardCharsets.UTF_8);
        String imported = "import Course from \"modules/course.sir\";";
        bytes.put(source.entry(), root.replace(imported, " ".repeat(imported.length())).getBytes(StandardCharsets.UTF_8));
        var negative = new SirSemanticAnalyzer().analyzeProject(ProjectTestSupport.parse(new SourceSnapshot(source.entry(), bytes)));
        assertFalse(negative.isSuccess());
        assertTrue(negative.diagnostics().stream().anyMatch(d -> d.code().value().equals("SIR-SOURCE-VISIBILITY-001")
                && d.primarySpan().equals(binding.site().span())), negative.diagnostics().toString());
        var result = new SourceEditor().edit(source, new CommandBatch(CommandBatch.PROTOCOL, "CourseAdmin", SourceEditor.sha256(source),
                List.of(new Command.Rename(Selector.root().named("declarations", "Course").named("fields", "capacity"), "limit"))));
        var edited = assertInstanceOf(EditResult.Edited.class, result, () -> String.valueOf(result));
        String updated = new String(edited.sources().bytes(source.entry()), StandardCharsets.UTF_8);
        assertTrue(updated.contains("doubledCapacity: Count = item.limit * 2;"), updated);
        assertDoesNotThrow(() -> compile(edited.sources()));
    }

    private static SourceSnapshot multiSource() throws Exception {
        Map<SourceId, byte[]> files = new LinkedHashMap<>();
        for (String file : ProjectTestSupport.FILES) {
            String text = ProjectTestSupport.resource("valid/multi-course/" + file).replace("\r\n", "\n");
            if (file.equals("project.sir")) text = text.replace("field version: Serial;",
                    "field version: Serial;\n      field doubledCapacity: Count = item.capacity * 2;");
            files.put(SourceId.of(file), text.getBytes(StandardCharsets.UTF_8));
        }
        return new SourceSnapshot(SourceId.of("project.sir"), files);
    }
    private static String content(ProjectCompilation.Compiled compiled, String suffix) {
        return compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + suffix)).findFirst().orElseThrow().content();
    }
    private static ProjectCompilation.Compiled compile(SourceSnapshot source) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var result = ProjectCompilation.compile(source, new SpringBootGenerator()::generate, diagnostics);
        assertTrue(result.isPresent(), diagnostics.toString());
        return result.orElseThrow();
    }
    private static int occurrences(String text, String token) { return (text.length() - text.replace(token, "").length()) / token.length(); }
}
