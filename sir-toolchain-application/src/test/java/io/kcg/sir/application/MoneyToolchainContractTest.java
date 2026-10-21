package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.change.edit.*;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class MoneyToolchainContractTest {
    private static final SourceId ENTRY = SourceId.of("project.sir"), TYPES = SourceId.of("types.sir");

    @Test void realHttpFixtureHasAValidExplicitLoweredPlanAndDeterministicOutput() throws Exception {
        var snapshot = ProjectTestSupport.oneFile("project.sir", ProjectTestSupport.resource("valid/money.sir"));
        var compiled = compile(snapshot);
        assertTrue(new SpringBootLoweredIrValidator().validate(compiled.loweredModel()).isEmpty());
        assertEquals(compiled.generatedFiles(), compile(snapshot).generatedFiles());
        String patch = compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/PatchWalletController.java"))
                .findFirst().orElseThrow().content();
        assertTrue(patch.contains("@PatchMapping"), patch);
        String create = compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/CreateWalletController.java"))
                .findFirst().orElseThrow().content();
        assertTrue(create.contains("HttpStatus.CREATED"), create);
    }

    @Test void projectWidePrecisionAliasSurvivesPreciseRenameAndReRead() throws Exception {
        var snapshot = multiFile();
        compile(snapshot);
        var result = new SourceEditor().edit(snapshot, new CommandBatch(CommandBatch.PROTOCOL, "MoneyLedger",
                SourceEditor.sha256(snapshot), List.of(new Command.Rename(Selector.root().named("declarations", "UnitPrice"), "Price"))));
        var edited = assertInstanceOf(EditResult.Edited.class, result);
        String root = text(edited.sources(), ENTRY), types = text(edited.sources(), TYPES);
        assertTrue(root.contains("import VersionedWallet from \"types.sir\";"), root);
        assertTrue(root.contains("field amount: Price;"), root);
        assertTrue(types.contains("type Price = Money where precision(21, 4);"), types);
        var compiled = compile(edited.sources());
        String dto = compiled.generatedFiles().stream().filter(f -> f.relativePath().endsWith("/Amount.java"))
                .findFirst().orElseThrow().content();
        assertTrue(dto.contains("@ExactDecimal(precision = 21, scale = 4)"), dto);
    }

    @Test void precisionDoesNotBypassEntityCrossFileImports() throws Exception {
        var snapshot = multiFile();
        String root = text(snapshot, ENTRY).replace("import VersionedWallet from \"types.sir\";", "");
        var withoutImport = new SourceSnapshot(ENTRY, Map.of(ENTRY, root.getBytes(StandardCharsets.UTF_8), TYPES, snapshot.bytes(TYPES)));
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        assertTrue(ProjectCompilation.compile(withoutImport, new SpringBootGenerator()::generate, diagnostics).isEmpty());
        assertTrue(diagnostics.stream().anyMatch(d -> d.isError() && d.sourceSpan().isPresent()), diagnostics.toString());
    }

    private static SourceSnapshot multiFile() throws Exception {
        String source = ProjectTestSupport.resource("valid/money.sir").replace("\r\n", "\n");
        String entity = source.substring(source.indexOf("    entity VersionedWallet"), source.indexOf("    input WalletPatch"));
        String root = source.replace(entity, "")
                .replace("sources { }", "sources { source \"types.sir\"; }")
                .replace("imports { }", "imports { import VersionedWallet from \"types.sir\"; }")
                .replace("    type UnitPrice = Money where precision(21, 4);\n", "");
        // Semantic types are project-wide; ordinary entities still require an import.
        String types = "sir 0.3\nimports {}\ndeclarations { type UnitPrice = Money where precision(21, 4);\n" + entity + "}\n";
        return new SourceSnapshot(ENTRY, Map.of(ENTRY, root.getBytes(StandardCharsets.UTF_8), TYPES, types.getBytes(StandardCharsets.UTF_8)));
    }

    private static String text(SourceSnapshot snapshot, SourceId file) { return new String(snapshot.bytes(file), StandardCharsets.UTF_8); }

    private static ProjectCompilation.Compiled compile(SourceSnapshot snapshot) {
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(snapshot, new SpringBootGenerator()::generate, diagnostics);
        assertTrue(compiled.isPresent(), diagnostics.toString());
        return compiled.orElseThrow();
    }
}
