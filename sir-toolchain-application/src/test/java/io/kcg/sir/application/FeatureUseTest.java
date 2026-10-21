package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.semantic.api.FeatureOrigin;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedFeatureUse;
import io.kcg.sir.semantic.schema.DraftOrder;
import io.kcg.sir.semantic.schema.SlotKind;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.semantic.schema.StructNode;
import io.kcg.sir.semantic.symbol.SymbolId;
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
import io.kcg.sir.change.edit.Command;
import io.kcg.sir.change.edit.CommandBatch;
import io.kcg.sir.change.edit.EditResult;
import io.kcg.sir.change.edit.Selector;
import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.change.edit.SourceEditor;
import io.kcg.sir.semantic.schema.StructValue;
import org.junit.jupiter.api.Test;

/**
 * {@code use DualTokenLogin { ... }} stands for the 117 lines of the dual-token pattern. The decisive claim is that it
 * means exactly what those lines mean: the same software model, and byte for byte the same generated project.
 */
class FeatureUseTest {
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void aUseDeclarationMeansExactlyTheDeclarationsItStandsFor() throws IOException {
        var written = compile(resource("/valid/dual-token-written.sir"));
        var used = compile(resource("/valid/dual-token-feature.sir"));
        assertEquals(authored(written.semanticModel()), authored(used.semanticModel()));
        assertEquals(files(written), files(used), "the generated project must be byte-identical");
    }

    @Test void theModelSaysWhichDeclarationsAFeatureExpandedInto() throws IOException {
        NormalizedSemanticModel model = compile(resource("/valid/dual-token-feature.sir")).semanticModel();
        SymbolId feature = new SymbolId("sir://DualTokenDesk/feature/DualTokenLogin/User");
        NormalizedFeatureUse use = (NormalizedFeatureUse) declaration(model, "DualTokenLogin");
        assertEquals(feature, use.id());
        assertEquals(List.of("account=User", "name=name", "password=passwordHash", "access=15 minutes", "refresh=7 days"),
            use.arguments().stream().map(a -> a.name() + "=" + a.value()).toList());
        assertEquals(java.util.Optional.of(new FeatureOrigin(feature, "AccessToken")), model.expandedFrom(declaration(model, "AccessToken").id()));
        assertEquals(java.util.Optional.of(new FeatureOrigin(feature, "Refresh")), model.expandedFrom(declaration(model, "Refresh").id()));
        assertTrue(model.isWritten(declaration(model, "User")));
        assertTrue(model.isWritten(use));
        assertFalse(model.isWritten(declaration(model, "Login")));
        assertEquals(14, model.declarations().stream().filter(d -> !model.isWritten(d)).count());
        // Every expanded declaration is located at the use declaration.
        assertTrue(model.declarations().stream().filter(d -> !model.isWritten(d)).allMatch(d -> d.span().equals(use.span())));
    }

    @Test void parametersAreCheckedBeforeAnythingIsExpanded() throws IOException {
        String base = resource("/valid/dual-token-feature.sir");
        assertEquals(List.of("SIR-FEATURE-001"), errors(base.replace("use DualTokenLogin {", "use SingleTokenLogin {")));
        assertEquals(List.of("SIR-FEATURE-002"), errors(base.replace("      account: User;\n", "")));
        assertEquals(List.of("SIR-FEATURE-002"), errors(base.replace("      access: 15 minutes;\n", "      access: 15 minutes;\n      colour: blue;\n")));
        assertEquals(List.of("SIR-FEATURE-003"), errors(base.replace("access: 15 minutes;", "access: 2 days;")));
        assertEquals(List.of("SIR-FEATURE-003"), errors(base.replace("account: User;", "account: 15 minutes;")));
        // Durations that were left out take the feature's defaults, which are the ones written here.
        String defaults = base.replace("      access: 15 minutes;\n      refresh: 7 days;\n", "");
        assertEquals(files(compile(base)), files(compile(defaults)));
        // What the arguments name is checked by the ordinary analysis of the expansion, and reported at the use declaration.
        int useLine = (int) base.substring(0, base.indexOf("    use DualTokenLogin")).lines().count() + 1;
        // (The author's own declarations that use the fields the feature adds are then refused where they are written.)
        for (String wrong : List.of(base.replace("password: passwordHash;", "password: name;"), base.replace("name: name;", "name: id;"))) {
            List<ExecutionDiagnostic> refused = refusal(wrong);
            assertTrue(refused.stream().anyMatch(d -> d.code().equals("SIR-SYMBOL-001") && d.sourceSpan().orElseThrow().start().line() == useLine), refused.toString());
        }
        String twice = base.replace("    use DualTokenLogin {", "    use DualTokenLogin { account: User; name: name; password: passwordHash; prefix: Second; }\n    use DualTokenLogin {");
        assertTrue(errors(twice).contains("SIR-FEATURE-005"), errors(twice).toString());
    }

    @Test void anEditChangesTheUseDeclarationAndNeverTheDeclarationsItExpandedInto() throws IOException {
        String base = resource("/valid/dual-token-feature.sir");
        // Changing an argument rewrites only the use declaration; the expansion follows on its own.
        var longer = edit(base, new Command.Update(Selector.root().named("declarations", "DualTokenLogin").named("arguments", "access").slot("value"),
            new StructValue.Text("30 minutes")));
        var edited = (EditResult.Edited) longer;
        String saved = text(edited);
        assertEquals(base.replace("access: 15 minutes;", "access: 30 minutes;"), saved);
        assertTrue(edited.delta().changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.UPDATE && c.after().equals("30 minutes")), edited.delta().toString());

        // An expanded declaration cannot be edited as if it were source text.
        var refused = edit(base, new Command.Update(Selector.root().named("declarations", "BadLogin").slot("httpStatus"), new StructValue.Text("403")));
        var refusal = (EditResult.Refused) refused;
        assertEquals(io.kcg.sir.change.edit.EditResult.Reason.COMMAND_REJECTED, refusal.reason());
        assertTrue(refusal.message().contains("expanded from 'use DualTokenLogin'"), refusal.message());
        var deleteExpanded = (EditResult.Refused) edit(base, new Command.Delete(Selector.root().named("declarations", "Logout")));
        assertTrue(deleteExpanded.message().contains("expanded from"), deleteExpanded.message());

        // The software's own Register writes the fields the feature adds to User, so the feature cannot simply go.
        var stillUsed = (EditResult.Refused) edit(base, new Command.Delete(Selector.root().named("declarations", "DualTokenLogin")));
        assertTrue(stillUsed.details().stream().anyMatch(d -> d.contains("passwordHash")), stillUsed.toString());
        // Where nothing else relies on them, removing the use declaration removes its text and everything it expanded into.
        String standalone = base.replace("      field id: Id;\n      field name: Text;\n", "      field id: Id;\n")
            .replace(base.substring(base.indexOf("    capability Register {"), base.indexOf("    capability PostNote {")), "");
        var removed = (EditResult.Edited) edit(standalone, new Command.Delete(Selector.root().named("declarations", "DualTokenLogin")));
        String withoutLogin = text(removed);
        assertFalse(withoutLogin.contains("use DualTokenLogin"));
        assertTrue(withoutLogin.contains("capability PostNote {"));
        assertTrue(removed.delta().changes().stream().anyMatch(c -> c.kind() == SemanticDelta.Kind.DELETE && c.subject().isPresent()
            && c.subject().get().endsWith("/entity/AccessToken")), removed.delta().toString());

        // Adding a declaration next to a use declaration writes it as ordinary text after the last written declaration.
        var error = new io.kcg.sir.semantic.schema.StructNode("NormalizedError", List.of(
            new io.kcg.sir.semantic.schema.StructNode.Slot("name", SlotKind.FACT, new StructValue.Text("NoteTooLong")),
            new io.kcg.sir.semantic.schema.StructNode.Slot("httpStatus", SlotKind.FACT, new StructValue.Text("400"))));
        var added = (EditResult.Edited) edit(base, new Command.Add(Selector.root().slot("declarations"), new StructValue.Child(error)));
        assertTrue(text(added).contains("    }\n    error NoteTooLong;\n  }\n}"), text(added));
    }

    @Test void aUseDeclarationInAnotherFileIsCheckedLikeDeclarationsWrittenThere() throws IOException {
        String single = resource("/valid/dual-token-feature.sir");
        String use = single.substring(single.indexOf("    use DualTokenLogin {"), single.indexOf("    }\n", single.indexOf("    use DualTokenLogin {")) + 6);
        String root = single.replace(use, "").replace("  sources { }", "  sources {\n    source \"auth.sir\";\n  }");
        SourceId auth = SourceId.of("auth.sir");
        String fragment = "sir 0.3\nimports {\n  import User from \"project.sir\";\n}\ndeclarations {\n" + use + "}\n";
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var split = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, root.getBytes(StandardCharsets.UTF_8),
            auth, fragment.getBytes(StandardCharsets.UTF_8))), model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(split.isPresent(), diagnostics::toString);
        assertEquals(files(compile(single)), files(split.get()));
        // The expansion refers to User from auth.sir, so auth.sir must import it like any declaration written there.
        diagnostics.clear();
        var unimported = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, root.getBytes(StandardCharsets.UTF_8),
            auth, fragment.replace("  import User from \"project.sir\";\n", "").getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(unimported.isEmpty());
        assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals("SIR-SOURCE-VISIBILITY-001")), diagnostics.toString());
    }

    private static EditResult edit(String text, Command command) {
        var snapshot = new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8)));
        return new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, "DualTokenDesk", SourceEditor.sha256(snapshot), List.of(command)));
    }

    private static String text(EditResult.Edited edited) {
        return new String(edited.sources().bytes(ENTRY), StandardCharsets.UTF_8);
    }

    private static NormalizedDeclaration declaration(NormalizedSemanticModel model, String name) {
        return model.declarations().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
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
        return refusal(text).stream().map(ExecutionDiagnostic::code).distinct().toList();
    }

    private static List<ExecutionDiagnostic> refusal(String text) {
        List<ExecutionDiagnostic> diagnostics = new ArrayList<>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(ENTRY, Map.of(ENTRY, text.getBytes(StandardCharsets.UTF_8))),
            model -> new SpringBootGenerator().generate(model), diagnostics);
        assertTrue(compiled.isEmpty(), "expected a refusal");
        return diagnostics.stream().filter(ExecutionDiagnostic::isError).toList();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = FeatureUseTest.class.getResourceAsStream(name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
