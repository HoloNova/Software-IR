package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import org.junit.jupiter.api.Test;

/** An average is planned as the database's AVG rounded half up to the measured column's scale, never as Java arithmetic. */
class AverageLoweringTest {
    @Test void averagesAreRoundedToTheScaleOfTheMeasuredColumn() {
        var model = LoweringTestSupport.lowerSuccess("valid/decimal-average.sir");
        var steps = model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
            .flatMap(c -> c.workflow().steps().stream()).toList();
        var single = steps.stream().filter(SpringBootWorkflow.SumStep.class::isInstance).map(SpringBootWorkflow.SumStep.class::cast).findFirst().orElseThrow();
        var grouped = steps.stream().filter(SpringBootWorkflow.GroupedSumStep.class::isInstance).map(SpringBootWorkflow.GroupedSumStep.class::cast).findFirst().orElseThrow();

        assertTrue(single.plan().average());
        assertEquals("COALESCE(ROUND(AVG(`amount`), 2), 0)", single.plan().projectionSql());
        assertTrue(grouped.plan().average());
        assertEquals("`category_id` AS g_key,COALESCE(ROUND(AVG(`amount`), 2), 0) AS g_total", grouped.plan().dataProjection());
    }
}
