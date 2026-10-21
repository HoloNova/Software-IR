package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** A software defines a feature once and uses it like the language's own: it means what its text means. */
class UserFeatureTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void aUseOfADefinedFeatureMeansTheDeclarationsItsTextExpandsInto() throws IOException {
        String used = resource("/valid/user-feature.sir");
        // The same software written out by hand: the feature's text with the arguments filled in.
        String text = used.substring(used.indexOf("template ```") + "template ```".length(), used.indexOf("```;"));
        String expanded = text.replace("${prefix}", "").replace("${owner}", "Note").replace("${limit}", "3 days");
        String head = used.substring(0, used.indexOf("    feature Tagging {"));
        String byHand = head + expanded + "  }\n}\n";
        assertEquals(files(compile(byHand)), files(compile(used)), "the generated project must be byte-identical");
        assertTrue(files(compile(used)).keySet().stream().anyMatch(f -> f.endsWith("/Tag.java")));
    }

    @Test void theDefaultsApplyAndAPrefixKeepsTwoUsesApart() throws IOException {
        String used = resource("/valid/user-feature.sir");
        String twice = used.replace("      limit: 3 days;\n    }\n  }\n}\n", "      limit: 3 days;\n    }\n  }\n}\n").replace("  }\n}\n",
            "    use Tagging {\n      owner: Note;\n      prefix: Second;\n    }\n  }\n}\n");
        // The language allows each feature to be used once, so the second use is refused with a pointer to the first.
        assertTrue(errors(twice).contains("SIR-FEATURE-005"), errors(twice).toString());
        String defaulted = used.replace("      limit: 3 days;\n", "");
        assertEquals(files(compile(used.replace("limit: 3 days;", "limit: 7 days;"))), files(compile(defaulted)), "a left out argument takes the default");
        assertFalse(files(compile(used)).equals(files(compile(defaulted))), "3 days is not 7 days");
    }

    @Test void aDefinitionIsCheckedWhetherOrNotAnythingUsesIt() throws IOException {
        String used = resource("/valid/user-feature.sir");
        String noUse = used.replace("    use Tagging {\n      owner: Note;\n      limit: 3 days;\n    }\n", "");
        assertEquals(List.of(), errors(noUse), "an unused but valid definition is fine");
        assertTrue(errors(noUse.replace("${owner} by input", "${other} by input")).contains("SIR-FEATURE-006"));
        assertTrue(errors(noUse.replace("parameter limit: duration = 7 days;", "parameter limit: colour = 7 days;")).contains("SIR-FEATURE-006"));
        assertTrue(errors(noUse.replace("parameter limit: duration = 7 days;", "parameter limit: duration = red;")).contains("SIR-FEATURE-006"));
        assertTrue(errors(noUse.replace("parameter prefix", "parameter owner")).contains("SIR-FEATURE-006"));
        assertTrue(errors(noUse.replace("feature Tagging", "feature DualTokenLogin")).contains("SIR-FEATURE-006"));
    }

    @Test void theArgumentsOfAUseAreCheckedAgainstTheDefinition() throws IOException {
        String used = resource("/valid/user-feature.sir");
        assertTrue(errors(used.replace("      owner: Note;\n", "")).contains("SIR-FEATURE-002"));
        assertTrue(errors(used.replace("limit: 3 days;", "limit: 3000 days;\n      colour: red;")).contains("SIR-FEATURE-002"));
        assertTrue(errors(used.replace("limit: 3 days;", "limit: 4000 days;")).contains("SIR-FEATURE-003"));
        assertTrue(errors(used.replace("owner: Note;", "owner: 3 days;")).contains("SIR-FEATURE-003"));
        // What the arguments name is checked by the ordinary analysis of the expansion.
        assertFalse(errors(used.replace("owner: Note;", "owner: Missing;")).isEmpty());
    }

    @Test void anArgumentEditChangesOnlyTheUseAndTheDefinitionIsPickedByItsOwnName() throws IOException {
        String used = resource("/valid/user-feature.sir");
        var snapshot = new SourceSnapshot(ENTRY, Map.of(ENTRY, used.getBytes(StandardCharsets.UTF_8)));
        String name = used.lines().filter(l -> l.startsWith("software ")).findFirst().orElseThrow().split("\\s+")[1];
        var longer = new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, name, SourceEditor.sha256(snapshot), List.of(
            new Command.Update(Selector.root().named("declarations", "Tagging").named("arguments", "limit").slot("value"), new StructValue.Text("5 days")))));
        assertTrue(longer instanceof EditResult.Edited, longer::toString);
        assertEquals(used.replace("limit: 3 days;", "limit: 5 days;"), new String(((EditResult.Edited) longer).sources().bytes(ENTRY), StandardCharsets.UTF_8));
        // The definition is its own element: a parameter's default can change without touching the use.
        var defaults = new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, name, SourceEditor.sha256(snapshot), List.of(
            new Command.Update(Selector.root().named("declarations", "feature Tagging").at("parameters", 2).slot("defaultValue"), new StructValue.Text("14 days")))));
        assertTrue(defaults instanceof EditResult.Edited, defaults::toString);
        assertEquals(used.replace("parameter limit: duration = 7 days;", "parameter limit: duration = 14 days;"),
            new String(((EditResult.Edited) defaults).sources().bytes(ENTRY), StandardCharsets.UTF_8));
    }

    private static Map<String, String> files(ProjectCompilation.Compiled compiled) {
        Map<String, String> files = new TreeMap<>();
        compiled.generatedFiles().forEach(f -> files.put(f.relativePath(), f.content()));
        return files;
    }

    private static ProjectCompilation.Compiled compile(String text) {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isPresent(), diagnostics::toString);
        return compiled.get();
    }

    private static List<String> errors(String text) {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        if (compiled.isPresent()) return List.of();
        return diagnostics.stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).distinct().toList();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = UserFeatureTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
