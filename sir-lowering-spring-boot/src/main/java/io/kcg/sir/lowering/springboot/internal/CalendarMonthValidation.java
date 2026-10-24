package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.springboot.model.*;
import java.util.ArrayList;
import java.util.List;

/** The physical DATETIME(6) endpoints and local-output obligations of a calendar conversion. */
final class CalendarMonthValidation {
    static final String MINIMUM = "1000-01-01T00:00:00Z";
    static final String MAXIMUM = "9999-12-31T23:59:59.999999Z";
    private CalendarMonthValidation() {}

    static List<String> validate(SpringBootLoweredModel model, SpringBootDeclaration.CapabilityDeclaration owner,
                                 SpringBootWorkflow.MonthStep step) {
        var errors=new ArrayList<String>();
        if (!text(step.month().type()) || !text(step.zone().type())) errors.add("month arguments must be nullable text");
        var instant=new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.INSTANT);
        if (!instant.equals(step.since().type()) || !instant.equals(step.until().type())
            || step.since().symbol().equals(step.until().symbol())
            || !owner.workflow().variables().contains(step.since()) || !owner.workflow().variables().contains(step.until()))
            errors.add("month must declare two distinct owned Moment variables");
        if (owner.kind()!=SpringBootDeclaration.CapabilityKind.QUERY
            || owner.transactionMode()!=SpringBootDeclaration.TransactionMode.REPEATABLE_READ
            || !owner.failures().contains(step.errorSymbol())) errors.add("month requires a read-only snapshot and a declared failure");
        if (!step.plan().minimumInstant().equals(MINIMUM) || !step.plan().maximumInstant().equals(MAXIMUM)
            || step.plan().statements()!=0) errors.add("month DATETIME bounds and zero-SQL plan must be canonical");
        if (model.projectArtifacts().stream().filter(ProjectArtifact.ValidationSupport.class::isInstance)
            .map(ProjectArtifact.ValidationSupport.class::cast).noneMatch(s -> s.constraintCodes().contains("timeZone") && s.utcInstantBinding()))
            errors.add("month requires region time-zone validation support");
        return List.copyOf(errors);
    }
    static boolean text(LoweredJavaType type) {
        if(type instanceof LoweredJavaType.OptionalValue optional)type=optional.elementType();
        return type instanceof LoweredJavaType.Scalar scalar && scalar.kind()==LoweredJavaType.ScalarKind.STRING;
    }
}
