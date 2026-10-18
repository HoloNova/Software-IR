package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.semantic.schema.StructValue;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** A software's own semantic types carry their kind and rules into the generated project, and survive an edit. */
class SemanticTypeDeclarationTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void aDeclaredTypeDecidesTheKindAndTheRulesOfTheFieldsThatUseIt() throws IOException {
        var compiled = compile(Map.of(ENTRY, resource("/valid/semantic-types.sir")));
        String entity = file(compiled, "Account.java");
        assertTrue(entity.contains("Integer rate"), entity);   // Percent is a Count: a 32 bit integer
        assertTrue(entity.contains("String label"), entity);   // Handle is a Name: text
        String input = file(compiled, "RateInput.java");
        assertTrue(input.contains("@DecimalMin(\"0\")") && input.contains("@DecimalMax(\"100\")"), input);   // the rules Percent brings
    }

    @Test void anEditRewritingAFieldDoesNotRepeatWhatItsTypeBrings() throws IOException {
        String text = resource("/valid/semantic-types.sir");
        var snapshot = new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8)));
        var batch = new CommandBatch(CommandBatch.PROTOCOL, softwareName(text), SourceEditor.sha256(snapshot), List.of(
            new Command.Update(Selector.root().named("declarations", "Account").named("fields", "tight").slot("name"), new StructValue.Text("bounded"))));
        var result = new SourceEditor().edit(snapshot, batch);
        assertTrue(result instanceof EditResult.Edited, result::toString);
        var edited = (EditResult.Edited) result;
        String saved = new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8);
        assertEquals(text.replace("field tight: Percent where max(10);", "field bounded: Percent where max(10);"), saved);
    }

    @Test void aTypeDeclaredInOneFileIsKnownInEveryFileWithoutAnImport() throws IOException {
        String single = resource("/valid/semantic-types.sir");
        SourceId more = SourceId.of("types.sir");
        String typeLines = "    type Percent = Count where min(0), max(100);\n";
        String root = single.replace(typeLines, "").replace("  sources { }", "  sources {\n    source \"types.sir\";\n  }");
        String fragment = "sir 0.3\nimports { }\ndeclarations {\n" + typeLines + "}\n";
        var split = compile(Map.of(ENTRY, root, more, fragment));
        assertEquals(compile(Map.of(ENTRY, single)).generatedFiles().stream().map(f -> f.relativePath() + f.content()).sorted().toList(),
            split.generatedFiles().stream().map(f -> f.relativePath() + f.content()).sorted().toList());
    }

    private static String file(ProjectCompilation.Compiled compiled, String name) {
        return compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/" + name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " not among " + compiled.generatedFiles().stream().map(f -> f.relativePath()).toList())).content();
    }

    private static String softwareName(String text) {
        return text.lines().filter(l -> l.startsWith("software ")).findFirst().orElseThrow().split("\\s+")[1];
    }

    private static ProjectCompilation.Compiled compile(Map<SourceId, String> sources) {
        Map<SourceId, byte[]> bytes = new java.util.LinkedHashMap<>();
        sources.forEach((id, text) -> bytes.put(id, text.getBytes(StandardCharsets.UTF_8)));
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, bytes), model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), diagnostics::toString);
        return compiled.get();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SemanticTypeDeclarationTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
