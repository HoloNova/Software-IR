package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.api.LoweringAnalysis;
import io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import org.junit.jupiter.api.Test;

class ActorIdentityBoundaryTest {

    @Test
    void actorNonIdentityMemberAccessFailsDuringLowering() {
        LoweringAnalysis<SpringBootLoweredModel> result = new SpringBootTargetLowering().lower(
                LoweringTestSupport.normalized("invalid/actor-non-identity.sir"));

        assertFalse(result.isSuccess(), "actor.name must not reach the Generator");
        assertEquals(1, result.diagnostics().size(), result.diagnostics().toString());
        var diagnostic = result.diagnostics().get(0);
        assertEquals("SIR-LOWER-FEATURE-001", diagnostic.code().value());
        assertTrue(diagnostic.message().contains("actor"), diagnostic.message());
        assertTrue(diagnostic.message().contains("identity"), diagnostic.message());
        assertEquals(28, diagnostic.primarySpan().start().line());
        assertEquals(16, diagnostic.primarySpan().start().column());
    }
}
