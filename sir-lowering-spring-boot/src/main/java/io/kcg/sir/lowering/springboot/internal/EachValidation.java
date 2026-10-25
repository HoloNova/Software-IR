package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.springboot.model.*;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.*;
import java.util.stream.Stream;

/** Independent checks for a constructed bounded iteration plan. No renderer fallback is permitted. */
final class EachValidation {
    private EachValidation() {}

    static int bodyStatements(List<SpringBootWorkflow.Step> body) {
        int count=0;
        for(var step:body) {
            if(step instanceof SpringBootWorkflow.LoadStep || step instanceof SpringBootWorkflow.ScalarQueryStep)count++;
            if(step instanceof SpringBootWorkflow.PersistStep persist) {
                count++;
                boolean relative=body.stream().filter(SpringBootWorkflow.UpdateStep.class::isInstance)
                    .map(SpringBootWorkflow.UpdateStep.class::cast).filter(u -> u.targetVariable().equals(persist.targetVariable()))
                    .anyMatch(u -> u.bindings().stream().anyMatch(b -> b.relative().isPresent()));
                if(relative)count++;
            }
        }
        return count;
    }

    static List<String> validate(SpringBootLoweredModel model, SpringBootDeclaration.CapabilityDeclaration owner,
            SpringBootWorkflow.EachStep each) {
        var errors=new ArrayList<String>();var p=each.plan();
        var entity=model.declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance)
            .map(SpringBootDeclaration.EntityDeclaration.class::cast).filter(e -> e.sourceSymbol().equals(each.entitySymbol())).findFirst().orElse(null);
        if(entity==null)return List.of("each source entity must be declared");
        var columns=Stream.concat(Stream.of(entity.identity().columnName()),entity.fields().stream().flatMap(f -> f.columnName().stream())).toList();
        if(p.limit()<1 || p.limit()>100 || p.fetchLimit()!=p.limit()+1 || !p.mapperMethod().equals("selectList")
                || !p.columns().equals(columns) || p.sourceStatements()!=1 || p.bodyStatements()!=bodyStatements(each.body())
                || p.maximumStatements()!=1+p.limit()*p.bodyStatements())
            errors.add("each needs a complete physical source, one sentinel read and a bounded body budget");
        var keys=each.orderKeys();
        if(keys.isEmpty() || !keys.getLast().fieldSymbol().equals(entity.identity().sourceSymbol())
                || keys.stream().map(SpringBootWorkflow.OrderKey::fieldSymbol).distinct().count()!=keys.size())
            errors.add("each requires unique order keys ending with identity");
        for(var key:keys) {
            if(key.fieldSymbol().equals(entity.identity().sourceSymbol())) {
                if(!key.targetMemberName().equals(entity.identity().javaName()))errors.add("each identity order name mismatch");
            } else {
                var property=entity.fields().stream().filter(f -> f.sourceSymbol().equals(key.fieldSymbol())).findFirst().orElse(null);
                if(property==null || property.columnName().isEmpty() || !property.javaName().equals(key.targetMemberName())
                        || property.type() instanceof LoweredJavaType.OptionalValue)
                    errors.add("each order key must be its own required physical column");
            }
        }
        var rowType=new LoweredJavaType.Declared(LoweredJavaType.DeclaredKind.ENTITY,entity.sourceSymbol(),entity.javaName());
        if(!each.row().type().equals(rowType) || !each.itemVariable().type().equals(rowType)
                || each.row().symbol().equals(each.itemVariable().symbol())
                || !owner.workflow().variables().contains(each.row()) || !owner.workflow().variables().contains(each.itemVariable()))
            errors.add("each needs separate declared query and body row bindings");
        if(owner.kind()!=SpringBootDeclaration.CapabilityKind.COMMAND
                || owner.transactionMode()!=SpringBootDeclaration.TransactionMode.READ_COMMITTED
                || !each.predicate().type().equals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.BOOLEAN))
                || !owner.failures().contains(each.errorSymbol()))
            errors.add("each requires atomic READ_COMMITTED command, Boolean predicate and declared failure");
        if(model.projectArtifacts().stream().filter(ProjectArtifact.ApplicationConfig.class::isInstance)
                .map(ProjectArtifact.ApplicationConfig.class::cast).filter(ProjectArtifact.ApplicationConfig::statementScopedQueryCache).count()!=1)
            errors.add("each requires STATEMENT cache");
        if(!LoopBodies.validTarget(each.row().symbol(),each.body()))
            errors.add("each body may write only its own rows; an outer row only by accumulation");
        return List.copyOf(errors);
    }
}
