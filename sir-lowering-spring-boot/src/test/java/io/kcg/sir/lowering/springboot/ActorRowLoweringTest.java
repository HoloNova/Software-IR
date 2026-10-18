package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import org.junit.jupiter.api.Test;

/**
 * A workflow that reads a field of the actor other than its identity ({@code actor.role}) reads the actor's row first,
 * once; one that only uses the actor as a reference does not.
 */
class ActorRowLoweringTest {
    private static final SpringBootLoweredModel MODEL = LoweringTestSupport.lowerSuccess("valid/actor-role.sir");

    @Test
    void readingTheActorsRoleReadsTheActorsRowFirstAndOnce() {
        for (String name : java.util.List.of("RemoveGoods", "RenameGoods")) {
            var steps = capability(name).workflow().steps();
            var load = assertInstanceOf(SpringBootWorkflow.LoadActorStep.class, steps.getFirst(), name);
            assertEquals(1, steps.stream().filter(SpringBootWorkflow.LoadActorStep.class::isInstance).count(), name);
            assertEquals("actor", load.result().targetName());
            assertTrue(capability(name).workflow().variables().contains(load.result()), name);
        }
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(MODEL).isEmpty());
    }

    @Test
    void usingTheActorOnlyAsAReferenceReadsNoRow() {
        assertTrue(capability("PostGoods").workflow().steps().stream().noneMatch(SpringBootWorkflow.LoadActorStep.class::isInstance));
    }

    private static SpringBootDeclaration.CapabilityDeclaration capability(String name) {
        return MODEL.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c -> c.javaName().equals(name)).findFirst().orElseThrow();
    }
}
