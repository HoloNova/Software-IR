package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.feature.SemanticTypes;
import io.kcg.sir.semantic.model.*;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.*;
import io.kcg.sir.lowering.springboot.model.*;
import java.util.*;

/** Independent checks for explicit batch counts at both public model boundaries. */
final class RelatedCountValidation {
    private RelatedCountValidation() {}

    static List<String> core(NormalizedView view, NormalizedViewField field,
            NormalizedViewField.CountRelated count, NormalizedSemanticModel model) {
        List<String> errors=new ArrayList<>();
        var related=model.declarations().stream().filter(NormalizedEntity.class::isInstance).map(NormalizedEntity.class::cast)
            .filter(e->e.id().equals(count.entity())).findFirst().orElse(null);
        var row=model.symbols().byId(count.rowSymbol()).orElse(null);
        boolean owned=model.symbols().symbolsInScope(view.id()).stream().anyMatch(s->s.id().equals(count.rowSymbol()));
        if(!owned || !(row instanceof Symbol.VariableSymbol v && v.type() instanceof RefType ref && ref.entityId().equals(view.sourceEntity())))
            errors.add("related count row must belong to its source view");
        if(!SemanticTypes.kind(field.type()).equals(PrimitiveType.INT64)) errors.add("related count must return Int64");
        var connection=model.symbols().byId(count.connectionField()).orElse(null);
        if(related==null || !related.persistent() || !(connection instanceof Symbol.FieldSymbol f && f.ownerId().equals(related.id())
                && f.type() instanceof RefType ref && ref.entityId().equals(view.sourceEntity()))
                || related.fields().stream().noneMatch(candidate->candidate.id().equals(count.connectionField())&&!candidate.secret()))
            errors.add("related count needs a persistent direct non-secret reference to the source entity");
        if(!SemanticTypes.kind(count.conditions().type()).equals(PrimitiveType.BOOLEAN)) errors.add("related count predicate must be Boolean");
        if(!connection(count.conditions(),count)) errors.add("related count predicate must connect its declared reference to its own source row");
        walk(count.conditions(),count,model,errors);
        return List.copyOf(errors);
    }

    private static boolean pair(NormalizedExpression a, NormalizedExpression b, NormalizedViewField.CountRelated count) {
        return a instanceof NormalizedExpression.NameExpression field && b instanceof NormalizedExpression.NameExpression row
            && field.resolvedSymbol().equals(count.connectionField()) && row.resolvedSymbol().equals(count.rowSymbol());
    }
    private static boolean join(NormalizedExpression e, NormalizedViewField.CountRelated count) {
        return e instanceof NormalizedExpression.BinaryExpression b && b.operator()==AstBinaryOperator.EQ
            && (pair(b.left(),b.right(),count)||pair(b.right(),b.left(),count));
    }
    static NormalizedExpression filter(NormalizedExpression e, NormalizedViewField.CountRelated count) {
        if (join(e,count)) return null;
        if (e instanceof NormalizedExpression.BinaryExpression b && b.operator()==AstBinaryOperator.AND) {
            var left=filter(b.left(),count); var right=filter(b.right(),count);
            if(left==null)return right;
            if(right==null)return left;
            return new NormalizedExpression.BinaryExpression(b.sourceNodeId(),b.span(),b.type(),left,b.operator(),right);
        }
        return e;
    }

    private static boolean connection(NormalizedExpression e,NormalizedViewField.CountRelated count) {
        return join(e,count) || e instanceof NormalizedExpression.BinaryExpression b && b.operator()==AstBinaryOperator.AND
            && (connection(b.left(),count)||connection(b.right(),count));
    }
    private static void walk(NormalizedExpression e,NormalizedViewField.CountRelated count,NormalizedSemanticModel model,List<String> errors) {
        if(join(e,count))return;
        switch(e) {
            case NormalizedExpression.BinaryExpression b -> {
                if(!Set.of(AstBinaryOperator.AND,AstBinaryOperator.OR,AstBinaryOperator.EQ,AstBinaryOperator.NE,
                        AstBinaryOperator.GT,AstBinaryOperator.GE,AstBinaryOperator.LT,AstBinaryOperator.LE,AstBinaryOperator.CONTAINS_LITERAL).contains(b.operator()))
                    errors.add("related count accepts predicate comparisons, not arithmetic");
                if(b.operator()==AstBinaryOperator.CONTAINS_LITERAL && !(b.right() instanceof NormalizedExpression.StringLiteral))
                    errors.add("related count literal matches need a constant string");
                walk(b.left(),count,model,errors);walk(b.right(),count,model,errors);
            }
            case NormalizedExpression.UnaryExpression u -> {
                if(u.operator()!=io.kcg.sir.ast.AstUnaryOperator.NOT) errors.add("related count cannot compute values");
                walk(u.operand(),count,model,errors);
            }
            case NormalizedExpression.NameExpression n -> {
                var symbol=model.symbols().byId(n.resolvedSymbol()).orElse(null);
                if(!(symbol instanceof Symbol.FieldSymbol f && f.ownerId().equals(count.entity())
                        && SemanticTypes.kind(f.type()).equals(SemanticTypes.kind(n.type()))))
                    errors.add("related count cannot read external variables or another entity's fields");
                boolean secret=model.declarations().stream().filter(NormalizedEntity.class::isInstance).map(NormalizedEntity.class::cast)
                    .filter(d->d.id().equals(count.entity())).flatMap(d->d.fields().stream()).anyMatch(f->f.id().equals(n.resolvedSymbol())&&f.secret());
                if(secret)errors.add("related count cannot read a secret field");
            }
            case NormalizedExpression.MemberExpression m -> {
                if(!(model.symbols().byId(m.resolvedMember()).orElse(null) instanceof Symbol.EnumMemberSymbol))
                    errors.add("related count cannot read members of another row");
            }
            case NormalizedExpression.BooleanLiteral n -> {if(!SemanticTypes.kind(n.type()).equals(PrimitiveType.BOOLEAN))errors.add("related count literal type");}
            case NormalizedExpression.IntegerLiteral n -> {if(!Set.of(PrimitiveType.INT32,PrimitiveType.INT64).contains(SemanticTypes.kind(n.type())))errors.add("related count literal type");}
            case NormalizedExpression.DecimalLiteral n -> {if(!SemanticTypes.kind(n.type()).equals(PrimitiveType.DECIMAL))errors.add("related count literal type");}
            case NormalizedExpression.StringLiteral n -> {if(!SemanticTypes.kind(n.type()).equals(PrimitiveType.STRING))errors.add("related count literal type");}
            default -> errors.add("related count cannot contain nested queries, clock or runtime values");
        }
    }

    static boolean contains(SirType type,List<NormalizedDeclaration> declarations,Set<SymbolId> visited) {
        if(type instanceof PageType p)return contains(p.element(),declarations,visited);
        if(type instanceof ListType l)return contains(l.element(),declarations,visited);
        if(type instanceof OptionalType o)return contains(o.element(),declarations,visited);
        if(!(type instanceof DeclaredType d)||!visited.add(d.symbolId()))return false;
        return declarations.stream().filter(NormalizedView.class::isInstance).map(NormalizedView.class::cast).filter(v->v.id().equals(d.symbolId()))
            .flatMap(v->v.fields().stream()).anyMatch(f->f.value() instanceof NormalizedViewField.CountRelated||contains(f.type(),declarations,visited));
    }
    static List<String> coreOutput(NormalizedCapability cap,NormalizedSemanticModel model) {
        if(!contains(cap.outputType(),model.declarations(),new HashSet<>()))return List.of();
        List<String> errors=new ArrayList<>();
        if(!cap.requires().contains(AstRequirementKind.READONLY)||!cap.exposure().equals(io.kcg.sir.ast.AstExposureKind.QUERY))
            errors.add("related count outputs require a readonly query");
        coreBounded(cap.outputType(),model.declarations(),new HashSet<>(),errors);
        return List.copyOf(errors);
    }
    private static void coreBounded(SirType type,List<NormalizedDeclaration> declarations,Set<SymbolId> visited,List<String> errors) {
        if(type instanceof ListType && contains(type,declarations,new HashSet<>())) {errors.add("related count cannot follow unbounded collections");return;}
        if(type instanceof PageType p)coreBounded(p.element(),declarations,visited,errors);
        if(type instanceof OptionalType o)coreBounded(o.element(),declarations,visited,errors);
        if(type instanceof DeclaredType d&&visited.add(d.symbolId())) declarations.stream().filter(NormalizedView.class::isInstance).map(NormalizedView.class::cast)
            .filter(v->v.id().equals(d.symbolId())).flatMap(v->v.fields().stream()).forEach(f->coreBounded(f.type(),declarations,visited,errors));
    }

    static List<String> target(SpringBootDeclaration.ViewDeclaration view,SpringBootDeclaration.ViewField field,
            SpringBootDeclaration.RelatedCountPlan p,SpringBootLoweredModel model) {
        List<String> errors=new ArrayList<>();
        var parent=entity(model,view.sourceEntitySymbol());var child=entity(model,p.targetEntitySymbol());
        var reference=child==null?null:child.fields().stream().filter(f->f.sourceSymbol().equals(p.connectionFieldSymbol())).findFirst().orElse(null);
        if(parent==null || child==null || reference==null || !(reference.type() instanceof LoweredJavaType.EntityReference ref
                && ref.entitySymbol().equals(parent.sourceSymbol())&&ref.identityStorageType().equals(parent.identity().type()))
                || reference.persistenceShape()!=SpringBootDeclaration.PersistenceShape.REFERENCE_ID)
            errors.add("related count must join the declared child foreign key to its parent");
        else {
            String column="`"+reference.columnName().orElse("")+"`";
            if(!p.keyColumn().equals(column)||!p.targetKeyPropertyName().equals(reference.javaName())
                    || !p.sourceKeyPropertyName().equals(parent.identity().javaName())||!p.keyType().equals(parent.identity().type()))
                errors.add("related count key columns, properties and carrier must match the relation");
        }
        if(!field.sourceSymbol().equals(p.viewFieldSymbol())||!field.type().equals(new LoweredJavaType.Scalar(LoweredJavaType.ScalarKind.LONG))
                ||!p.keyAlias().equals("count_key")||!p.countAlias().equals("count_value")
                ||!p.keyProjection().equals(p.keyColumn()+" AS count_key")||!p.countProjection().equals("COUNT(*) AS count_value")
                ||!p.mapperMethod().equals("selectMaps"))errors.add("related count projection and result contract must be exact");
        if(p.maxKeys()!=io.kcg.sir.lowering.springboot.profile.SpringBootQueryPolicy.V0_1.pageMaxSize()||p.queryBudget()!=1
                ||!p.skipEmptySource()||!p.missingGroupIsZero())errors.add("related count requires bounded keys, one read, zero groups and skip-empty");
        if(p.arguments().stream().anyMatch(a->a instanceof SpringExpression.ExistsArgument.Value))errors.add("related count cannot capture runtime arguments");
        if(!sql(p,child))errors.add("related count SQL must only reference its declared columns and bound values: "+p.conditionSql());
        String pkg=model.basePackage()+".persistence";
        if(model.projectArtifacts().stream().filter(ProjectArtifact.RelatedCounts.class::isInstance).map(ProjectArtifact.RelatedCounts.class::cast)
                .filter(a->a.packageName().equals(pkg)&&a.simpleName().equals("RelatedCounts")&&a.path().equals("src/main/java/"+pkg.replace('.','/')+"/RelatedCounts.java")).count()!=1)
            errors.add("related count needs its exact support artifact");
        if(model.projectArtifacts().stream().filter(ProjectArtifact.ApplicationConfig.class::isInstance).map(ProjectArtifact.ApplicationConfig.class::cast)
                .filter(ProjectArtifact.ApplicationConfig::statementScopedQueryCache).count()!=1)errors.add("related count requires STATEMENT cache");
        return List.copyOf(errors);
    }

    private static boolean sql(SpringBootDeclaration.RelatedCountPlan p,SpringBootDeclaration.EntityDeclaration child) {
        if(child==null)return false;
        Set<String> identifiers=new HashSet<>();identifiers.add(child.tableName());identifiers.add(child.identity().columnName());
        child.fields().forEach(f->f.columnName().ifPresent(identifiers::add));
        var names=java.util.regex.Pattern.compile("`([^`]+)`").matcher(p.conditionSql());
        while(names.find())if(!identifiers.contains(names.group(1)))return false;
        var placeholders=java.util.regex.Pattern.compile("\\{([0-9]+)}").matcher(p.conditionSql());Set<Integer> positions=new HashSet<>();
        while(placeholders.find())try {positions.add(Integer.parseInt(placeholders.group(1)));}catch(NumberFormatException e){return false;}
        if(!positions.equals(java.util.stream.IntStream.range(0,p.arguments().size()).boxed().collect(java.util.stream.Collectors.toSet())))return false;
        String rest=p.conditionSql().replace("'\\\\'", "").replaceAll("`[^`]+`|\\{[0-9]+}", "")
            .replaceAll("(?i)\\b(AND|OR|NOT|COALESCE|FALSE|TRUE|LIKE|ESCAPE|IS|NULL)\\b", "");
        return rest.matches("[\\s().,<>=!]*");
    }
    private static SpringBootDeclaration.EntityDeclaration entity(SpringBootLoweredModel model,SymbolId id) {
        return model.declarations().stream().filter(SpringBootDeclaration.EntityDeclaration.class::isInstance).map(SpringBootDeclaration.EntityDeclaration.class::cast)
            .filter(e->e.sourceSymbol().equals(id)).findFirst().orElse(null);
    }

    static boolean targetContains(LoweredJavaType type,List<SpringBootDeclaration> declarations,Set<SymbolId> visited) {
        if(type instanceof LoweredJavaType.PageValue p)return targetContains(p.elementType(),declarations,visited);
        if(type instanceof LoweredJavaType.ListValue l)return targetContains(l.elementType(),declarations,visited);
        if(type instanceof LoweredJavaType.OptionalValue o)return targetContains(o.elementType(),declarations,visited);
        if(!(type instanceof LoweredJavaType.Declared d)||!visited.add(d.symbolId()))return false;
        return declarations.stream().filter(SpringBootDeclaration.ViewDeclaration.class::isInstance).map(SpringBootDeclaration.ViewDeclaration.class::cast)
            .filter(v->v.sourceSymbol().equals(d.symbolId())).flatMap(v->v.fields().stream())
            .anyMatch(f->f.value() instanceof SpringBootDeclaration.CountViewValue||targetContains(f.type(),declarations,visited));
    }
    static List<String> targetOutput(SpringBootDeclaration.CapabilityDeclaration cap,SpringBootLoweredModel model) {
        if(!targetContains(cap.outputType(),model.declarations(),new HashSet<>()))return List.of();
        List<String> errors=new ArrayList<>();
        if(cap.kind()!=SpringBootDeclaration.CapabilityKind.QUERY||cap.transactionMode()!=SpringBootDeclaration.TransactionMode.REPEATABLE_READ)
            errors.add("related count requires an explicit readonly REPEATABLE_READ snapshot");
        targetBounded(cap.outputType(),model.declarations(),new HashSet<>(),errors);
        return List.copyOf(errors);
    }
    private static void targetBounded(LoweredJavaType type,List<SpringBootDeclaration> declarations,Set<SymbolId> visited,List<String> errors) {
        if(type instanceof LoweredJavaType.ListValue && targetContains(type,declarations,new HashSet<>())) {errors.add("related count cannot follow unbounded collections");return;}
        if(type instanceof LoweredJavaType.PageValue p)targetBounded(p.elementType(),declarations,visited,errors);
        if(type instanceof LoweredJavaType.OptionalValue o)targetBounded(o.elementType(),declarations,visited,errors);
        if(type instanceof LoweredJavaType.Declared d&&visited.add(d.symbolId()))declarations.stream().filter(SpringBootDeclaration.ViewDeclaration.class::isInstance)
            .map(SpringBootDeclaration.ViewDeclaration.class::cast).filter(v->v.sourceSymbol().equals(d.symbolId())).flatMap(v->v.fields().stream())
            .forEach(f->targetBounded(f.type(),declarations,visited,errors));
    }
}
