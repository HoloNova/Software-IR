package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator;
import org.junit.jupiter.api.Test;

class RelatedQueryParametersTest {
    @Test
    void repeatedExternalValuesHaveStableSeparateBindingsUnderNotAndNullTests() {
        String source = LoweringTestSupport.source("valid/course-enrollment.sir")
            .replace("field size: PageSize;", "field size: PageSize;\n      field chosen: Optional<EnrollmentStatus>;")
            .replace("status == EnrollmentStatus.ACTIVE", "input.chosen is none or (status == input.chosen and not (status != input.chosen))");
        // Keep the connection outside OR, so it still relates every subquery alternative to its root.
        source = source.replace("course == item and input.chosen is none or (status == input.chosen and not (status != input.chosen))",
            "course == item and (input.chosen is none or (status == input.chosen and not (status != input.chosen)))");
        var model = LoweringTestSupport.lowerSourceSuccess(source);
        var capability = model.declarations().stream().filter(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        var query = (io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.FindStep) capability.workflow().steps().getFirst();
        var exists = (io.kcg.sir.lowering.springboot.model.SpringExpression.ExistsPredicate) query.predicate();
        assertEquals(3, exists.arguments().size());
        assertTrue(exists.subquerySql().contains("{0} IS NULL") && exists.subquerySql().contains("{1}") && exists.subquerySql().contains("{2}"), exists.subquerySql());
        assertTrue(exists.subquerySql().contains("COALESCE(") && exists.subquerySql().contains("NOT ("), exists.subquerySql());
        assertEquals(java.util.List.of(), new SpringBootLoweredIrValidator().validate(model));
    }

    @Test
    void correlatedQueryBindsRequestValuesRatherThanRejectingOrInliningThem() {
        String source = LoweringTestSupport.source("valid/course-enrollment.sir")
            .replace("field page: PageNo;", "field chosen: EnrollmentStatus;\n      field page: PageNo;")
            .replace("status == EnrollmentStatus.ACTIVE", "status == input.chosen");
        var model = LoweringTestSupport.lowerSourceSuccess(source);
        assertEquals(java.util.List.of(), new SpringBootLoweredIrValidator().validate(model));
        var capability = model.declarations().stream().filter(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
            .map(io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration.class::cast).findFirst().orElseThrow();
        var find = (io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.FindStep) capability.workflow().steps().getFirst();
        var exists = (io.kcg.sir.lowering.springboot.model.SpringExpression.ExistsPredicate) find.predicate();
        assertEquals("SELECT 1 FROM enrollment enrollment_rel WHERE enrollment_rel.course_id = course.id AND enrollment_rel.status = {0}", exists.subquerySql());
        assertInstanceOf(io.kcg.sir.lowering.springboot.model.SpringExpression.ExistsArgument.Value.class, exists.arguments().getFirst());
    }
}
