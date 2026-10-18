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
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedFeatureUse;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.schema.DraftOrder;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
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
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code use Crud { entity: Note; }} is the create, show, list, change and delete of an entity, read from the entity
 * itself. The decisive claim is the same as for every feature: it means exactly the declarations written out in
 * crud-written.sir (a versioned entity with every kind of field, a uuid entity with a scope, an account whose login
 * fields another feature adds, and posts owned by their author), down to the generated project.
 */
class CrudFeatureTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void oneUseIsTheWholeCrudOfItsEntityAsIfWrittenOut() throws IOException {
        var used = compile(resource("/valid/crud-feature.sir"));
        var written = compile(resource("/valid/crud-written.sir"));
        assertEquals(authored(written.semanticModel()), authored(used.semanticModel()));
        assertEquals(files(written), files(used), "the generated project must be byte-identical");
    }

    @Test void eachEntityHasItsOwnUseOfTheFeature() throws IOException {
        NormalizedSemanticModel model = compile(resource("/valid/crud-feature.sir")).semanticModel();
        assertEquals(List.of("sir://CrudDesk/feature/Crud/Item", "sir://CrudDesk/feature/Crud/Note", "sir://CrudDesk/feature/Crud/Post",
                "sir://CrudDesk/feature/Crud/User",
                "sir://CrudDesk/feature/DualTokenLogin/User"),
            model.declarations().stream().filter(NormalizedFeatureUse.class::isInstance).map(d -> d.id().value()).toList());
        var view = model.declarations().stream().filter(d -> d.name().equals("UserView")).findFirst().orElseThrow();
        assertEquals("sir://CrudDesk/feature/Crud/User", model.expandedFrom(view.id()).orElseThrow().feature().value());
        // The account's login name comes from DualTokenLogin; the Crud of the account sees it like the account's own field.
        assertTrue(((NormalizedView) view).fields().stream().anyMatch(f -> f.name().equals("loginName")));
    }

    @Test void aCrudThatCannotBeIsRefusedAtTheUse() throws IOException {
        String base = resource("/valid/crud-feature.sir");
        assertEquals(List.of("SIR-FEATURE-003"), errors(base.replace("use Crud { entity: Item; }", "use Crud { entity: Level; }")));
        assertEquals(List.of("SIR-FEATURE-005"), errors(base.replace("use Crud { entity: Item; }", "use Crud { entity: Note; }")));
        assertEquals(List.of("SIR-FEATURE-002"), errors(base.replace("use Crud { entity: Item; }", "use Crud { }")));
        // The owner must be a field of the entity.
        assertEquals(List.of("SIR-FEATURE-003"), errors(base.replace("owner: author;", "owner: writer;")));
    }

    @Test void anAuthorsFeatureCanGoThroughAnEntitysFieldsToo() throws IOException {
        String summary = """
                feature Summary {
                  parameter entity: name;
                  template ```
                view ${entity}Summary from ${entity} {
                ${for f in entity.fields except secret, versioned}
                  field ${f.name}: ${f.type};
                ${end}
                }
                ```;
                }
                use Summary { entity: Note; }
            """;
        String base = resource("/valid/crud-feature.sir").replace("    use DualTokenLogin", summary + "    use DualTokenLogin");
        var model = compile(base).semanticModel();
        var view = (NormalizedView) model.declarations().stream().filter(d -> d.name().equals("NoteSummary")).findFirst().orElseThrow();
        assertEquals(List.of("title", "body", "level", "owner", "code"), view.fields().stream().map(f -> f.name()).toList());

        // A template is read where it is defined: a loop over something that is not a name parameter, a member a field
        // does not have and a loop left open are refused there, used or not.
        for (String broken : List.of("${for f in other.fields except secret, versioned}", "${for f in entity.fields except hidden}", "${for f in entity.columns}")) {
            assertEquals(List.of("SIR-FEATURE-006"), errors(base.replace("${for f in entity.fields except secret, versioned}", broken)), broken);
        }
        assertEquals(List.of("SIR-FEATURE-006"), errors(base.replace("field ${f.name}: ${f.type};", "field ${f.name}: ${f.size};")));
        assertEquals(List.of("SIR-FEATURE-006"), errors(base.replace("${end}\n", "")));
        // Such a feature expands after the types are known, so it cannot declare one.
        assertEquals(List.of("SIR-FEATURE-004"), errors(base.replace("view ${entity}Summary from", "type ${entity}Label = Name;\nview ${entity}Summary from")));
    }

    @Test void anEditCanAddAnotherUseOfTheSameFeature() throws IOException {
        String base = resource("/valid/crud-feature.sir").replace("    use Crud { entity: Item; }\n", "");
        var use = new StructNode("NormalizedFeatureUse", List.of(
            new StructNode.Slot("name", SlotKind.FACT, new StructValue.Text("Crud")),
            new StructNode.Slot("arguments", SlotKind.FACT, new StructValue.Seq(List.of(new StructValue.Child(new StructNode("NormalizedFeatureArgument", List.of(
                new StructNode.Slot("name", SlotKind.FACT, new StructValue.Text("entity")),
                new StructNode.Slot("value", SlotKind.FACT, new StructValue.Text("Item"))))))))));
        var snapshot = new SourceSnapshot(ENTRY, Map.of(ENTRY, base.getBytes(StandardCharsets.UTF_8)));
        var result = new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, "CrudDesk", SourceEditor.sha256(snapshot),
            List.of(new Command.Add(Selector.root().slot("declarations"), new StructValue.Child(use)))));
        assertTrue(result instanceof EditResult.Edited, result::toString);
        String text = new String(((EditResult.Edited) result).sources().bytes(ENTRY), StandardCharsets.UTF_8);
        assertTrue(text.contains("use Crud {\n      entity: Item;\n    }") || text.contains("use Crud { entity: Item; }"), text);
        assertEquals(files(compile(resource("/valid/crud-feature.sir"))).keySet(), files(compile(text)).keySet());
    }

    /** The model as the author's facts, without identities (step variables are identified by where they are written). */
    private static StructNode authored(NormalizedSemanticModel model) {
        SoftwareSchema schema = SoftwareSchema.normalizedModel();
        StructNode root = SoftwareDraft.of(model.implementable()).root().without(Set.of(SlotKind.IDENTITY));
        return DraftOrder.canonical(root, schema);
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
        assertTrue(compiled.isEmpty(), "expected a refusal");
        return diagnostics.stream().filter(ExecutionDiagnostic::isError).map(ExecutionDiagnostic::code).distinct().toList();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CrudFeatureTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
