package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputedViewLoweringTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing_row", "wrong_row_type", "foreign_variable", "foreign_field", "wrong_property", "wrong_member_type", "wrong_output", "nonlocal", "wrong_literal_type", "unit_result", "wrong_unary", "wrong_boolean", "wrong_comparison"})
    void malformedPlansCannotBorrowAnotherContextOrInventAColumn(String mutation) {
        var model = LoweringTestSupport.lowerSuccess("valid/computed-views.sir");
        var view = model.declarations().stream().filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ViewDeclaration.class::cast).filter(v -> v.javaName().equals("CourseView")).findFirst().orElseThrow();
        var original = view.fields().get(2);
        var binary = (io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryExpression)
                ((SpringBootDeclaration.ComputedViewValue) original.value()).expression();
        var member = (io.kcg.sir.lowering.springboot.model.SpringExpression.MemberExpression) binary.left();
        var name = (io.kcg.sir.lowering.springboot.model.SpringExpression.NameExpression) member.receiver();
        var scalar = new io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar(
                io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind.BOOLEAN);
        var foreign = model.declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast).filter(e -> e.javaName().equals("Lesson")).findFirst().orElseThrow();
        var wrong = new io.kcg.sir.lowering.springboot.model.SpringExpression.NameExpression(name.id(), name.origin(), name.type(),
                mutation.equals("foreign_variable") ? foreign.sourceSymbol() : name.resolvedSymbol(), name.targetName());
        var changedMember = new io.kcg.sir.lowering.springboot.model.SpringExpression.MemberExpression(member.id(), member.origin(),
                mutation.equals("wrong_member_type") ? scalar : member.type(), wrong,
                mutation.equals("foreign_field") ? foreign.fields().getLast().sourceSymbol() : member.resolvedMember(),
                mutation.equals("wrong_property") ? "enrolled" : member.targetMember());
        if (mutation.equals("unit_result")) scalar = new io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar(
                io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind.VOID);
        io.kcg.sir.lowering.springboot.model.SpringExpression expression = mutation.equals("wrong_literal_type") || mutation.equals("unit_result")
                ? new io.kcg.sir.lowering.springboot.model.SpringExpression.StringLiteral(binary.id(), binary.origin(),
                    mutation.equals("unit_result") ? scalar : binary.type(), "not a number")
                : mutation.equals("wrong_unary")
                ? new io.kcg.sir.lowering.springboot.model.SpringExpression.UnaryExpression(binary.id(), binary.origin(), binary.type(),
                    io.kcg.sir.lowering.springboot.model.SpringExpression.UnaryOperator.NOT, changedMember)
                : mutation.equals("nonlocal")
                ? new io.kcg.sir.lowering.springboot.model.SpringExpression.NowExpression(binary.id(), binary.origin(), binary.type())
                : new io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryExpression(binary.id(), binary.origin(), binary.type(),
                    changedMember, mutation.equals("wrong_boolean") ? io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryOperator.AND
                        : mutation.equals("wrong_comparison") ? io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryOperator.EQ : binary.operator(),
                    binary.right(), binary.presentComparison());
        var changed = new SpringBootDeclaration.ViewField(original.id(), original.origin(), original.sourceSymbol(), original.javaName(),
                mutation.equals("wrong_output") || mutation.equals("unit_result") ? scalar : original.type(), new SpringBootDeclaration.ComputedViewValue(expression));
        var row = mutation.equals("missing_row") ? java.util.Optional.<io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable>empty()
                : mutation.equals("wrong_row_type") ? java.util.Optional.of(new io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable(
                    view.sourceRow().orElseThrow().symbol(), "item", scalar)) : view.sourceRow();
        var replacement = new SpringBootDeclaration.ViewDeclaration(view.id(), view.origin(), view.sourceSymbol(), view.javaName(),
                view.sourceEntitySymbol(), view.sourceEntityJavaName(), view.fields().stream().map(f -> f == original ? changed : f).toList(), row);
        var declarations = model.declarations().stream().<SpringBootDeclaration>map(d -> d == view ? replacement : d).toList();
        var damaged = new io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel(model.irVersion(), model.profile(), model.softwareName(),
                model.displayName(), model.basePackage(), declarations, model.artifacts(), model.mavenProject(), model.applicationMain(), model.projectArtifacts());
        var diagnostics = new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(damaged);
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("computed")), diagnostics.toString());
    }

    @Test void constructedCoreModelCannotBorrowTheRowOfAnotherView() {
        var model = LoweringTestSupport.normalized("valid/computed-views.sir");
        var views = model.declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedView.class::isInstance)
                .map(io.kcg.sir.semantic.model.NormalizedView.class::cast).toList();
        var view = views.stream().filter(v -> v.name().equals("CourseView")).findFirst().orElseThrow();
        var other = views.stream().filter(v -> v.name().equals("CourseTree")).findFirst().orElseThrow();
        var field = view.fields().get(2);
        var computed = (io.kcg.sir.semantic.model.NormalizedViewField.Computed) field.value();
        var foreignRow = ((io.kcg.sir.semantic.model.NormalizedViewField.Computed) other.fields().getFirst().value()).rowSymbol();
        var bad = new io.kcg.sir.semantic.model.NormalizedViewField(field.id(), field.name(), field.span(), field.sourceNodeId(), field.type(),
                new io.kcg.sir.semantic.model.NormalizedViewField.Computed(foreignRow, computed.expression()), field.directNamedTypeReferenceSiteId());
        var replacement = new io.kcg.sir.semantic.model.NormalizedView(view.id(), view.name(), view.span(), view.sourceNodeId(),
                view.sourceEntity(), view.fields().stream().map(f -> f == field ? bad : f).toList());
        var declarations = model.declarations().stream().<io.kcg.sir.semantic.model.NormalizedDeclaration>map(d -> d == view ? replacement : d).toList();
        var damaged = new io.kcg.sir.semantic.api.NormalizedSemanticModel(model.softwareName(), model.metadata(), model.target(), model.symbols(),
                model.referenceBindings(), model.findItemBindings(), model.expressionTypes(), declarations, model.diagnostics(), model.referenceSiteBindings(), model.expansions());
        var diagnostics = new io.kcg.sir.lowering.springboot.api.SpringBootTargetLowering().lower(damaged).diagnostics();
        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("computed projection row")), diagnostics.toString());
    }

    @Test void rowComputationsAreLoweredWithoutInventingStorageOrQueries() {
        var model = LoweringTestSupport.lowerSourceSuccess(LoweringTestSupport.source("valid/computed-views.sir"));
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(model).isEmpty());
        var entity = model.declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
                .map(SpringBootDeclaration.EntityDeclaration.class::cast).filter(e -> e.javaName().equals("Course")).findFirst().orElseThrow();
        assertEquals(List.of("capacity", "enrolled", "price", "note", "starts"), entity.fields().stream().map(f -> f.javaName()).toList());
    }
}
