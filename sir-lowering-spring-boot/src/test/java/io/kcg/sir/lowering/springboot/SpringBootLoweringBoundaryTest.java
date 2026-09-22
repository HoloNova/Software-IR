package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.DiagnosticCode;
import io.kcg.sir.api.DiagnosticSeverity;
import io.kcg.sir.ast.AstMetadata;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedConstraint;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringBootLoweringBoundaryTest {

    @Test
    void rejectsUnsupportedJavaVersionWithStructuredFailure() {
        NormalizedSemanticModel model = LoweringTestSupport.normalized("valid/campus-market.sir");
        AstTarget target = model.target();
        AstTarget unsupported = new AstTarget(
                target.id(), target.span(), target.language(), BigInteger.valueOf(17),
                target.languageVersionSpan(), target.framework(), target.persistence(),
                target.database(), target.build(), target.interfaceKind());

        LoweringAnalysis<SpringBootLoweredModel> result = lower(copy(model, model.metadata(), unsupported,
                model.declarations(), model.diagnostics()));

        assertFailure(result, "SIR-LOWER-TARGET-001");
    }

    @Test
    void rejectsIllegalJavaNamespace() {
        NormalizedSemanticModel model = LoweringTestSupport.normalized("valid/campus-market.sir");
        AstMetadata metadata = model.metadata();
        AstMetadata invalid = new AstMetadata(
                metadata.id(), metadata.span(), metadata.displayName(), metadata.displayNameSpan(),
                "com.example.bad-package", metadata.namespaceSpan());

        LoweringAnalysis<SpringBootLoweredModel> result = lower(copy(model, invalid, model.target(),
                model.declarations(), model.diagnostics()));

        assertFailure(result, "SIR-LOWER-NAME-001");
    }

    @Test
    void rejectsNormalizedModelThatAlreadyContainsAnError() {
        NormalizedSemanticModel model = LoweringTestSupport.normalized("valid/campus-market.sir");
        Diagnostic error = new Diagnostic(
                new DiagnosticCode("SIR-TEST-001"), DiagnosticSeverity.ERROR, "damaged snapshot",
                model.metadata().span(), List.of(), List.of());

        LoweringAnalysis<SpringBootLoweredModel> result = lower(copy(model, model.metadata(), model.target(),
                model.declarations(), List.of(error)));

        assertFailure(result, "SIR-LOWER-INPUT-001");
    }

    @Test
    void rejectsUnknownErrorSymbolWithoutResolvingItsNameAgain() {
        NormalizedSemanticModel model = LoweringTestSupport.normalized("valid/campus-market.sir");
        List<NormalizedDeclaration> declarations = new ArrayList<>(model.declarations());
        for (int index = 0; index < declarations.size(); index++) {
            if (declarations.get(index) instanceof NormalizedCapability capability) {
                declarations.set(index, new NormalizedCapability(
                        capability.id(), capability.name(), capability.span(), capability.sourceNodeId(),
                        capability.actorSymbol(), capability.inputSymbol(), capability.outputType(),
                        List.of(new SymbolId("sir://unknown/error")), capability.requires(), capability.exposure(),
                        capability.workflow()));
            }
        }

        LoweringAnalysis<SpringBootLoweredModel> result = lower(copy(
                model, model.metadata(), model.target(), declarations, model.diagnostics()));

        assertFailure(result, "SIR-LOWER-BINDING-001");
    }

    @Test
    void rejectsConstraintOutsideFrozenTargetSubset() {
        NormalizedSemanticModel model = LoweringTestSupport.normalized("valid/campus-market.sir");
        List<NormalizedDeclaration> declarations = new ArrayList<>(model.declarations());
        for (int index = 0; index < declarations.size(); index++) {
            if (declarations.get(index) instanceof NormalizedEntity entity && entity.name().equals("User")) {
                NormalizedField field = entity.fields().get(0);
                NormalizedConstraint unsupported = new NormalizedConstraint(
                        field.sourceNodeId(), field.span(), "targetSpecificMagic", List.of());
                NormalizedField damagedField = new NormalizedField(
                        field.id(), field.name(), field.span(), field.sourceNodeId(), field.type(),
                        List.of(unsupported));
                declarations.set(index, new NormalizedEntity(
                        entity.id(), entity.name(), entity.span(), entity.sourceNodeId(), entity.persistent(),
                        entity.identity(), List.of(damagedField, entity.fields().get(1)), entity.versionField()));
            }
        }

        LoweringAnalysis<SpringBootLoweredModel> result = lower(copy(
                model, model.metadata(), model.target(), declarations, model.diagnostics()));

        assertFailure(result, "SIR-LOWER-FEATURE-001");
    }

    private LoweringAnalysis<SpringBootLoweredModel> lower(NormalizedSemanticModel model) {
        return new SpringBootTargetLowering().lower(model);
    }

    private NormalizedSemanticModel copy(
            NormalizedSemanticModel model,
            AstMetadata metadata,
            AstTarget target,
            List<NormalizedDeclaration> declarations,
            List<Diagnostic> diagnostics
    ) {
        return new NormalizedSemanticModel(
                model.softwareName(), metadata, target, model.symbols(), model.referenceBindings(),
                model.findItemBindings(), model.expressionTypes(), declarations, diagnostics);
    }

    private void assertFailure(LoweringAnalysis<SpringBootLoweredModel> result, String code) {
        assertFalse(result.isSuccess());
        assertTrue(result.model().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.severity().isError() && d.code().value().equals(code)),
                () -> "missing " + code + " in " + result.diagnostics());
    }
}
