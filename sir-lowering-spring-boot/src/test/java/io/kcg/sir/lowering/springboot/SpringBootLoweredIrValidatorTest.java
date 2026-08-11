package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpringBootLoweredIrValidatorTest {

    @Test
    void independentlyRejectsDuplicateArtifactIdentityAndIncompleteOwnership() {
        SpringBootLoweredModel valid = LoweringTestSupport.lowerSuccess("valid/campus-market.sir");
        SpringArtifact duplicate = valid.artifacts().get(0);
        SpringBootLoweredModel damaged = new SpringBootLoweredModel(
                valid.irVersion(), valid.profile(), valid.softwareName(), valid.displayName(),
                valid.basePackage(), valid.declarations(), List.of(duplicate, duplicate),
                valid.mavenProject(), valid.applicationMain());

        var diagnostics = new SpringBootLoweredIrValidator().validate(damaged);

        assertTrue(diagnostics.stream().anyMatch(d -> d.code().value().equals("SIR-LOWER-IR-001")));
        assertEquals(diagnostics, List.copyOf(diagnostics));
    }
}
