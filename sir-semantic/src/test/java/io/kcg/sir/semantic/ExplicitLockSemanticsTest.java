package io.kcg.sir.semantic;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExplicitLockSemanticsTest {
    @Test void atomicIdentityLockIsLegal() {
        var result = TestSources.analyze(source());
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }
    @Test void explicitLockAlsoAllowsVersionedRowsWithoutRequiringAWritingStep() {
        var result = TestSources.analyze(source().replace("field name: Text unique;", "field name: Text unique; field version: Serial versioned;"));
        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }
    @ParameterizedTest
    @ValueSource(strings = {"requires readonly;", "", "requires atomic; requires readonly;"})
    void aLockCannotPromiseSafetyWithoutAWritableTransaction(String requirements) {
        var result = TestSources.analyze(source().replace("requires atomic;", requirements));
        assertTrue(TestSources.errorCodes(result).contains("SIR-FLOW-004"), () -> result.diagnostics().toString());
    }
    @Test void lockRequiresIdentityLookup() {
        var result = TestSources.analyze(source().replace("by input.roomId as room", "by name: \"room\" as room"));
        assertTrue(TestSources.errorCodes(result).contains("SIR-FLOW-004"), () -> result.diagnostics().toString());
    }
    @Test void queryTransportCannotTakeAnExplicitLockEvenWithAtomic() {
        var result = TestSources.analyze(source().replace("expose command", "expose query"));
        assertTrue(TestSources.errorCodes(result).contains("SIR-FLOW-004"), () -> result.diagnostics().toString());
    }
    static String source() { return TestSources.resource("valid/explicit-lock.sir"); }
}
