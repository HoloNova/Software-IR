package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import io.kcg.sir.lowering.springboot.model.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReferenceIdentityLoweringTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void referenceComparisonHasAnExplicitIdentityProjectionAndPhysicalPresenceType(boolean optional) {
        String source = LoweringTestSupport.source("valid/explicit-lock.sir")
                .replace("field room: Ref<Room>;", "field room: Ref<Room>; field backup: Optional<Ref<Room>>;")
                .replace("item.room == room and item.startsAt < input.endsAt and item.endsAt > input.startsAt",
                        optional ? "item.backup == room" : "item.room == room");
        var model = LoweringTestSupport.lowerSourceSuccess(source);
        var cap = model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        var query = cap.workflow().steps().stream().filter(SpringBootWorkflow.ScalarQueryStep.class::isInstance)
                .map(SpringBootWorkflow.ScalarQueryStep.class::cast).findFirst().orElseThrow();
        var comparison = assertInstanceOf(SpringExpression.BinaryExpression.class, query.predicate());
        var identity = assertInstanceOf(SpringExpression.MemberExpression.class, comparison.right());
        assertEquals("id", identity.targetMember());
        assertInstanceOf(LoweredJavaType.EntityReference.class, identity.receiver().type());
        assertEquals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG), identity.type());
        if (optional) {
            assertEquals(new LoweredJavaType.OptionalValue(identity.type()), comparison.left().type());
            var presence = comparison.presentComparison().orElseThrow();
            assertEquals(identity.type(), presence.operandType());
            assertTrue(presence.checkLeft()); assertFalse(presence.checkRight());
        } else assertEquals(identity.type(), comparison.left().type());
        assertTrue(new SpringBootLoweredIrValidator().validate(model).isEmpty());
    }
}
