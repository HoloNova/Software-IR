package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.springboot.model.*;
import java.util.ArrayList;
import java.util.List;

/** Validates constructed target plans before any Java/SQL is rendered. */
final class GroupedSumValidation {
    private GroupedSumValidation() {}

    static List<String> validate(SpringBootLoweredModel model, SpringBootDeclaration.CapabilityDeclaration owner,
                                 SpringBootWorkflow.GroupedSumStep query) {
        var errors = new ArrayList<String>();
        var entity = model.declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.EntityDeclaration.class::cast).filter(e -> e.sourceSymbol().equals(query.entitySymbol()))
            .findFirst().orElse(null);
        if (entity == null) return List.of("grouped sum entity must be declared");
        var p = query.plan();
        var amount = entity.fields().stream().filter(f -> f.sourceSymbol().equals(p.fieldSymbol())).findFirst().orElse(null);
        var key = entity.fields().stream().filter(f -> f.sourceSymbol().equals(p.keySymbol())).findFirst().orElse(null);
        var decimal = new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BIG_DECIMAL);
        var amountType = amount == null ? null : amount.type();
        if (amountType instanceof LoweredJavaType.OptionalValue optional) amountType = optional.elementType();
        if (amount == null || !decimal.equals(amountType)
                || amount.persistenceShape() != SpringBootDeclaration.PersistenceShape.SCALAR
                || !amount.columnName().orElse("").equals(p.columnName()))
            errors.add("grouped sum requires its entity's direct Decimal column");
        if (key == null || !(key.type() instanceof LoweredJavaType.EntityReference ref)
                || key.persistenceShape() != SpringBootDeclaration.PersistenceShape.REFERENCE_ID
                || !key.columnName().orElse("").equals(p.keyColumn())
                || !new LoweredJavaType.PageValue(new LoweredJavaType.SumGroupValue(ref)).equals(query.result().type()))
            errors.add("grouped sum key column and result reference must match");
        String k = "`" + p.keyColumn().replace("`", "``") + "`";
        String a = "`" + p.columnName().replace("`", "``") + "`";
        if (!p.countProjection().equals("DISTINCT " + k)
                || !p.dataProjection().equals(k + " AS g_key," + AggregateSql.measurement(a, p.average(), p.scale()) + " AS g_total")
                || !p.countMethod().equals("selectCount") || !p.dataMethod().equals("selectMaps")
                || p.statements() != 2 || !p.rejectWarnings())
            errors.add("grouped sum must plan the same key for distinct count and exact ordered data, with statement warnings");
        if (!(query.itemVariable().type() instanceof LoweredJavaType.Declared row)
                || row.kind() != LoweredJavaType.DeclaredKind.ENTITY || !row.symbolId().equals(query.entitySymbol())
                || !owner.workflow().variables().contains(query.itemVariable()) || !owner.workflow().variables().contains(query.result())
                || !query.result().type().equals(owner.outputType()))
            errors.add("grouped sum row and result must belong to this capability");
        if (owner.kind() != SpringBootDeclaration.CapabilityKind.QUERY
                || owner.httpMethod() != SpringBootDeclaration.HttpMethod.GET
                || owner.transactionMode() != SpringBootDeclaration.TransactionMode.REPEATABLE_READ
                || !query.predicate().type().equals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BOOLEAN)))
            errors.add("grouped sum requires a Boolean filter and one explicit read-only repeatable-read transaction");
        var support = model.projectArtifacts().stream().filter(ProjectArtifact.ExactAggregate.class::isInstance)
            .map(ProjectArtifact.ExactAggregate.class::cast).toList();
        if (support.size() != 1 || !support.getFirst().groupPageType().equals(java.util.Optional.of(model.basePackage()+".api.PageResponse"))
                || !support.getFirst().packageName().equals(model.basePackage()+".persistence")
                || !support.getFirst().simpleName().equals("ExactAggregate")
                || !support.getFirst().path().equals("src/main/java/"+(model.basePackage()+".persistence").replace('.', '/')+"/ExactAggregate.java")
                || model.projectArtifacts().stream().filter(ProjectArtifact.ApplicationConfig.class::isInstance)
                    .map(ProjectArtifact.ApplicationConfig.class::cast).filter(ProjectArtifact.ApplicationConfig::statementScopedQueryCache).count() != 1)
            errors.add("grouped sum requires exact aggregate support and STATEMENT cache");
        return List.copyOf(errors);
    }
}
