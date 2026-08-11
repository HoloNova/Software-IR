package io.kcg.sir.lowering.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LoweringContractTest {

    @Test
    void successRejectsErrorDiagnostics() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoweringAnalysis.Success<>(new TestModel(), List.of(error())));
    }

    @Test
    void failureRequiresAnErrorDiagnostic() {
        LoweringDiagnostic warning = new LoweringDiagnostic(
                new LoweringDiagnosticCode("SIR-LOWER-TEST-001"),
                LoweringSeverity.WARNING,
                "warning",
                span(),
                Optional.empty(),
                Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> new LoweringAnalysis.Failure<TestModel>(List.of(warning)));
    }

    @Test
    void failureIsImmutableAndDoesNotExposeModel() {
        LoweringAnalysis<TestModel> result = new LoweringAnalysis.Failure<>(List.of(error()));

        assertFalse(result.isSuccess());
        assertTrue(result.hasErrors());
        assertTrue(result.model().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.diagnostics().add(error()));
    }

    @Test
    void loweredOriginRetainsStableSourceIdentity() {
        LoweredOrigin origin = new LoweredOrigin(
                Optional.of(new SymbolId("sir://Test/entity/User")),
                new AstNodeId("sir-ast://test/entity/User"),
                span());

        assertEquals("sir://Test/entity/User", origin.ownerSymbol().orElseThrow().value());
        assertEquals("sir-ast://test/entity/User", origin.sourceNodeId().value());
    }

    private LoweringDiagnostic error() {
        return new LoweringDiagnostic(
                new LoweringDiagnosticCode("SIR-LOWER-TEST-001"),
                LoweringSeverity.ERROR,
                "failure",
                span(),
                Optional.empty(),
                Optional.empty());
    }

    private SourceSpan span() {
        SourcePosition position = new SourcePosition(0, 1, 1);
        return new SourceSpan(SourceId.of("test.sir"), position, position);
    }

    private record TestModel() implements LoweredModel {
        @Override
        public LoweredIrVersion irVersion() {
            return LoweredIrVersion.V0_1;
        }

        @Override
        public String targetId() {
            return "test";
        }

        @Override
        public String softwareName() {
            return "Test";
        }
    }
}
