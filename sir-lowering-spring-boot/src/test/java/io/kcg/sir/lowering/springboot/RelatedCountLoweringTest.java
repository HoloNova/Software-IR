package io.kcg.sir.lowering.springboot;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import org.junit.jupiter.api.Test;

class RelatedCountLoweringTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"keyColumn","keyType","keyProjection","countProjection","keyAlias","countAlias",
        "mapperMethod","queryBudget","maxKeys","skipEmptySource","missingGroupIsZero","conditionSql","foreignColumn","arguments","cache","support","transaction"})
    void constructedTargetsCannotDropBatchReadPolicies(String damage) throws Exception {
        var model=LoweringTestSupport.lowerSuccess("valid/related-count.sir");
        var view=model.declarations().stream().filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
            .map(SpringBootDeclaration.ViewDeclaration.class::cast).filter(v->v.javaName().equals("CatalogueBook")).findFirst().orElseThrow();
        var field=view.fields().getLast();var plan=((SpringBootDeclaration.CountViewValue)field.value()).plan();
        var changed=switch(damage) {
            case "queryBudget" -> copy(plan,damage,2);case "maxKeys" -> copy(plan,damage,101);
            case "skipEmptySource","missingGroupIsZero" -> copy(plan,damage,false);
            case "keyType" -> copy(plan,damage,new io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar(io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind.INTEGER));
            case "conditionSql" -> copy(plan,damage,"SELECT 1");
            case "foreignColumn" -> copy(plan,"conditionSql","`book`.`id` = {0}");
            case "arguments" -> copy(plan,damage,java.util.List.of());
            case "cache","support","transaction" -> plan;
            default -> copy(plan,damage,"forged");
        };
        var replacement=copy(view,"fields",view.fields().stream().map(f->f==field?copyUnchecked(f,"value",new SpringBootDeclaration.CountViewValue(changed)):f).toList());
        var declarations=model.declarations().stream().map(d->d==view?replacement:d).map(d->damage.equals("transaction")&&d instanceof SpringBootDeclaration.CapabilityDeclaration c
            ?copyUnchecked(c,"transactionMode",SpringBootDeclaration.TransactionMode.READ_ONLY):d).toList();
        var artifacts=model.projectArtifacts().stream().filter(a->!damage.equals("support")||!(a instanceof io.kcg.sir.lowering.springboot.model.ProjectArtifact.RelatedCounts))
            .map(a->damage.equals("cache")&&a instanceof io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationConfig?copyUnchecked(a,"statementScopedQueryCache",false):a).toList();
        var broken=copy(copy(model,"declarations",declarations),"projectArtifacts",artifacts);
        var errors=new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(broken);
        assertTrue(errors.stream().anyMatch(d->d.message().contains("related count")),damage+": "+errors);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"rowSymbol","entity","connectionField","result","predicate"})
    void constructedCoreCannotInventACountContext(String damage) throws Exception {
        var model=LoweringTestSupport.normalized("valid/related-count.sir").implementable();
        var view=model.declarations().stream().filter(io.kcg.sir.semantic.model.NormalizedView.class::isInstance)
            .map(io.kcg.sir.semantic.model.NormalizedView.class::cast).filter(v->v.name().equals("CatalogueBook")).findFirst().orElseThrow();
        var field=view.fields().getLast();var count=(io.kcg.sir.semantic.model.NormalizedViewField.CountRelated)field.value();
        var altered=switch(damage) {
            case "predicate" -> copy(count,"conditions",new io.kcg.sir.semantic.model.NormalizedExpression.BooleanLiteral(count.conditions().sourceNodeId(),count.conditions().span(),io.kcg.sir.semantic.type.PrimitiveType.BOOLEAN,true));
            case "result" -> count;
            default -> copy(count,damage,new io.kcg.sir.semantic.symbol.SymbolId("missing"));
        };
        var bad=damage.equals("result")?copy(field,"type",io.kcg.sir.semantic.type.PrimitiveType.INT32):copy(field,"value",altered);
        var replacement=copy(view,"fields",view.fields().stream().map(f->f==field?bad:f).toList());
        var broken=copy(model,"declarations",model.declarations().stream().map(d->d==view?replacement:d).toList());
        var errors=new io.kcg.sir.lowering.springboot.internal.SpringBootInputValidator(broken).validate();
        assertTrue(errors.stream().anyMatch(d->d.message().contains("related count")),damage+": "+errors);
    }

    private static <T> T copyUnchecked(T record,String field,Object value) {
        try{return copy(record,field,value);}catch(Exception e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") private static <T> T copy(T record,String field,Object value) throws Exception {
        var parts=record.getClass().getRecordComponents();var types=new Class<?>[parts.length];var values=new Object[parts.length];boolean found=false;
        for(int i=0;i<parts.length;i++){types[i]=parts[i].getType();boolean match=parts[i].getName().equals(field);found|=match;values[i]=match?value:parts[i].getAccessor().invoke(record);}
        assertTrue(found,field);return (T)record.getClass().getDeclaredConstructor(types).newInstance(values);
    }

    @Test void aRelatedCountHasOneExplicitBoundedReadPlanNotAFakeColumn() {
        var model=LoweringTestSupport.lowerSuccess("valid/related-count.sir");
        var view=model.declarations().stream().filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
                .map(SpringBootDeclaration.ViewDeclaration.class::cast).filter(v->v.javaName().equals("CatalogueBook")).findFirst().orElseThrow();
        var count=assertInstanceOf(SpringBootDeclaration.CountViewValue.class,view.fields().getLast().value()).plan();
        assertTrue(count.conditionSql().contains("`copy`.`removed`"),count.conditionSql());
        assertTrue(count.conditionSql().contains("`copy`.`status`"),count.conditionSql());
        assertFalse(count.conditionSql().contains("book.id"),count.conditionSql());
        assertEquals(java.util.List.of(new io.kcg.sir.lowering.springboot.model.SpringExpression.ExistsArgument.Text("AVAILABLE"),
            new io.kcg.sir.lowering.springboot.model.SpringExpression.ExistsArgument.Flag(false)),count.arguments());
        assertEquals(100,count.maxKeys());assertTrue(count.skipEmptySource());assertTrue(count.missingGroupIsZero());
        for(String name:new String[]{"GetBook","ListBook","SearchCatalogue"}) {
            var cap=model.declarations().stream().filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
                    .map(SpringBootDeclaration.CapabilityDeclaration.class::cast).filter(c->c.javaName().equals(name)).findFirst().orElseThrow();
            assertEquals(SpringBootDeclaration.TransactionMode.REPEATABLE_READ,cap.transactionMode(),name);
        }
        assertTrue(new io.kcg.sir.lowering.springboot.api.SpringBootLoweredIrValidator().validate(model).isEmpty());
        assertFalse(model.projectArtifacts().stream().anyMatch(a->a.getClass().getSimpleName().equals("ExactAggregate")),
                "a plain related COUNT must not acquire Decimal warning machinery");
    }
}
