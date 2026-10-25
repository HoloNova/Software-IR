package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.semantic.model.*;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.lowering.springboot.model.*;
import java.util.*;
import java.util.stream.Stream;

/** Rechecks block-local lifetimes at both public model boundaries; structural walkers do not enforce scopes. */
final class EachLexicalScopes {
    private EachLexicalScopes() {}

    static List<String> core(NormalizedCapability capability) {
        var all=new HashSet<SymbolId>();
        NormalizedSteps.walk(capability.workflow().steps()).filter(NormalizedStep.EachStep.class::isInstance)
            .map(NormalizedStep.EachStep.class::cast).forEach(e -> {
                all.add(e.rowVariable());all.add(e.itemVariable());
                e.body().forEach(child -> LoopBodies.declaredCore(child).ifPresent(all::add));
            });
        if(all.isEmpty())return List.of();
        var errors=new ArrayList<String>();
        for(var step:capability.workflow().steps()) {
            if(!(step instanceof NormalizedStep.EachStep each)) { reject(coreUses(step),all,Set.of(),errors);continue; }
            reject(coreNames(each.predicate()),all,Set.of(each.itemVariable()),errors);
            var available=new HashSet<SymbolId>();available.add(each.rowVariable());
            for(var child:each.body()) {
                reject(coreUses(child),all,available,errors);
                LoopBodies.declaredCore(child).ifPresent(available::add);
            }
        }
        return List.copyOf(errors);
    }

    static List<String> target(SpringBootDeclaration.CapabilityDeclaration capability) {
        var all=new HashSet<SymbolId>();
        SpringBootWorkflow.walk(capability.workflow().steps()).filter(SpringBootWorkflow.EachStep.class::isInstance)
            .map(SpringBootWorkflow.EachStep.class::cast).forEach(e -> {
                all.add(e.row().symbol());all.add(e.itemVariable().symbol());
                e.body().forEach(child -> LoopBodies.declaredTarget(child).ifPresent(all::add));
            });
        if(all.isEmpty())return List.of();
        var errors=new ArrayList<String>();
        for(var step:capability.workflow().steps()) {
            if(!(step instanceof SpringBootWorkflow.EachStep each)) {reject(targetUses(step),all,Set.of(),errors);continue;}
            reject(targetNames(each.predicate()),all,Set.of(each.itemVariable().symbol()),errors);
            var available=new HashSet<SymbolId>();available.add(each.row().symbol());
            for(var child:each.body()) {
                reject(targetUses(child),all,available,errors);
                LoopBodies.declaredTarget(child).ifPresent(available::add);
            }
        }
        return List.copyOf(errors);
    }

    private static void reject(Stream<SymbolId> reads,Set<SymbolId> local,Set<SymbolId> available,List<String> errors) {
        reads.filter(local::contains).filter(id -> !available.contains(id)).distinct()
            .forEach(id -> errors.add("each lexical variable is used outside its lifetime: "+id));
    }

    private static Stream<SymbolId> coreUses(NormalizedStep step) {
        return switch(step) {
            case NormalizedStep.ValidateStep s -> coreNames(s.condition());
            case NormalizedStep.LoadStep s -> coreNames(s.idExpression());
            case NormalizedStep.ReturnStep s -> coreNames(s.value());
            case NormalizedStep.EachStep s -> coreNames(s.predicate());
            case NormalizedStep.FindStep s -> coreNames(s.predicate());
            case NormalizedStep.SumStep s -> coreNames(s.predicate());
            case NormalizedStep.GroupedSumStep s -> coreNames(s.predicate());
            case NormalizedStep.ScalarQueryStep s -> coreNames(s.predicate());
            case NormalizedStep.MonthStep s -> Stream.concat(coreNames(s.month()),coreNames(s.zone()));
            case NormalizedStep.CreateStep s -> s.bindings().stream().flatMap(b -> coreNames(b.value()));
            case NormalizedStep.UpdateStep s -> Stream.concat(Stream.of(s.targetVariable()),Stream.concat(
                s.bindings().stream().flatMap(b -> coreNames(b.value())),s.guard().stream().flatMap(EachLexicalScopes::coreNames)));
            case NormalizedStep.PersistStep s -> Stream.of(s.targetVariable());
            case NormalizedStep.DeleteStep s -> Stream.of(s.targetVariable());
        };
    }
    private static Stream<SymbolId> coreNames(NormalizedExpression expression) {
        return switch(expression) {
            case NormalizedExpression.NameExpression e -> Stream.of(e.resolvedSymbol());
            case NormalizedExpression.MemberExpression e -> coreNames(e.receiver());
            case NormalizedExpression.UnaryExpression e -> coreNames(e.operand());
            case NormalizedExpression.BinaryExpression e -> Stream.concat(coreNames(e.left()),coreNames(e.right()));
            case NormalizedExpression.TimeShiftExpression e -> coreNames(e.base());
            case NormalizedExpression.PresentExpression e -> coreNames(e.target());
            case NormalizedExpression.ExistsExpression e -> coreNames(e.conditions());
            case NormalizedExpression.BooleanLiteral e -> Stream.empty();
            case NormalizedExpression.IntegerLiteral e -> Stream.empty();
            case NormalizedExpression.DecimalLiteral e -> Stream.empty();
            case NormalizedExpression.StringLiteral e -> Stream.empty();
            case NormalizedExpression.UnitLiteral e -> Stream.empty();
            case NormalizedExpression.NowExpression e -> Stream.empty();
        };
    }

    private static Stream<SymbolId> targetUses(SpringBootWorkflow.Step step) {
        return switch(step) {
            case SpringBootWorkflow.ValidateStep s -> targetNames(s.condition());
            case SpringBootWorkflow.LoadStep s -> targetNames(s.idExpression());
            case SpringBootWorkflow.LoadActorStep s -> targetNames(s.idExpression());
            case SpringBootWorkflow.ReturnStep s -> targetNames(s.value());
            case SpringBootWorkflow.EachStep s -> targetNames(s.predicate());
            case SpringBootWorkflow.FindStep s -> targetNames(s.predicate());
            case SpringBootWorkflow.SumStep s -> targetNames(s.predicate());
            case SpringBootWorkflow.GroupedSumStep s -> targetNames(s.predicate());
            case SpringBootWorkflow.ScalarQueryStep s -> targetNames(s.predicate());
            case SpringBootWorkflow.MonthStep s -> Stream.concat(targetNames(s.month()),targetNames(s.zone()));
            case SpringBootWorkflow.CreateStep s -> s.bindings().stream().flatMap(b -> targetNames(b.value()));
            case SpringBootWorkflow.UpdateStep s -> Stream.concat(Stream.of(s.targetVariable()),Stream.concat(
                s.bindings().stream().flatMap(b -> Stream.concat(targetNames(b.value()),b.relative().stream().flatMap(r -> targetNames(r.amount())))),
                s.guard().stream().flatMap(g -> targetNames(g.predicate()))));
            case SpringBootWorkflow.PersistStep s -> Stream.of(s.targetVariable());
        };
    }
    private static Stream<SymbolId> targetNames(SpringExpression expression) {
        return switch(expression) {
            case SpringExpression.NameExpression e -> Stream.of(e.resolvedSymbol());
            case SpringExpression.MemberExpression e -> targetNames(e.receiver());
            case SpringExpression.UnaryExpression e -> targetNames(e.operand());
            case SpringExpression.BinaryExpression e -> Stream.concat(targetNames(e.left()),targetNames(e.right()));
            case SpringExpression.TimeShiftExpression e -> targetNames(e.base());
            case SpringExpression.DigestExpression e -> targetNames(e.operand());
            case SpringExpression.IntegralToDecimal e -> targetNames(e.operand());
            case SpringExpression.OptionalPayloadValue e -> targetNames(e.payloadMember());
            case SpringExpression.ExistsPredicate e -> e.arguments().stream().filter(SpringExpression.ExistsArgument.Value.class::isInstance)
                .map(SpringExpression.ExistsArgument.Value.class::cast).flatMap(v -> targetNames(v.expression()));
            case SpringExpression.BooleanLiteral e -> Stream.empty();
            case SpringExpression.IntegerLiteral e -> Stream.empty();
            case SpringExpression.DecimalLiteral e -> Stream.empty();
            case SpringExpression.StringLiteral e -> Stream.empty();
            case SpringExpression.UnitLiteral e -> Stream.empty();
            case SpringExpression.NowExpression e -> Stream.empty();
            case SpringExpression.PayloadPresence e -> Stream.empty();
        };
    }
}
