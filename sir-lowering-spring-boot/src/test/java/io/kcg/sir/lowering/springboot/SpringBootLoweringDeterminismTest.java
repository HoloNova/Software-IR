package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SpringBootLoweringDeterminismTest {

    @Test
    void sameNormalizedModelProducesEqualLoweredIrTwice() {
        var normalized = LoweringTestSupport.normalized("valid/campus-market.sir");

        var first = new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering()
                .lower(normalized).model().orElseThrow();
        var second = new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering()
                .lower(normalized).model().orElseThrow();

        assertEquals(first, second);
    }

    @Test
    void defaultLocaleDoesNotChangeNamesIdsOrOrdering() {
        var normalized = LoweringTestSupport.normalized("valid/campus-market.sir");
        SpringBootLoweredModel before = new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering()
                .lower(normalized).model().orElseThrow();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            SpringBootLoweredModel after = new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering()
                    .lower(normalized).model().orElseThrow();
            assertEquals(before, after);
        } finally {
            Locale.setDefault(original);
        }
    }
}
