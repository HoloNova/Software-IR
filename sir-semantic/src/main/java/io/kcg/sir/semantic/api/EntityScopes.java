package io.kcg.sir.semantic.api;

import io.kcg.sir.ast.AstBinaryOperator;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedWorkflow;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SirType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Applies every entity's {@code scope} to the workflows, so that what is implemented never sees a row outside it.
 *
 * <p>The author writes the condition once, on the entity; this turns it into what each workflow would otherwise have
 * to repeat. A find adds it to its predicate, an existence test to its conditions, and a load is followed by a check
 * that fails with the load's own error, because a row outside the scope does not exist for the software. The result
 * is ordinary steps, so nothing after this needs to know scopes exist.
 *
 * <p>Every node this adds gets an id below the step it serves, since the same scope is applied in many places and
 * each place must stay distinguishable downstream.
 */
final class EntityScopes {
   private final SymbolTable symbols;
   private final Map<SymbolId, NormalizedEntity> scoped = new LinkedHashMap<>();
   private final Map<SymbolId, Set<SymbolId>> fieldsOf = new LinkedHashMap<>();

   private EntityScopes(SymbolTable symbols, List<NormalizedDeclaration> declarations) {
      this.symbols = symbols;
      for (NormalizedDeclaration declaration : declarations) {
         if (declaration instanceof NormalizedEntity entity && entity.scope().isPresent()) {
            this.scoped.put(entity.id(), entity);
            Set<SymbolId> fields = new HashSet<>();
            fields.add(entity.identity().id());
            entity.fields().forEach(field -> fields.add(field.id()));
            this.fieldsOf.put(entity.id(), fields);
         }
      }
   }

   static List<NormalizedDeclaration> apply(SymbolTable symbols, List<NormalizedDeclaration> declarations) {
      EntityScopes scopes = new EntityScopes(symbols, declarations);
      if (scopes.scoped.isEmpty()) {
         return declarations;
      }
      return declarations.stream().map(d -> d instanceof NormalizedCapability c ? scopes.capability(c)
          : d instanceof io.kcg.sir.semantic.model.NormalizedView v ? scopes.view(v) : d).toList();
   }

    private io.kcg.sir.semantic.model.NormalizedView view(io.kcg.sir.semantic.model.NormalizedView view) {
       var fields=view.fields().stream().map(field -> {
          if (!(field.value() instanceof io.kcg.sir.semantic.model.NormalizedViewField.CountRelated count)
                || !this.scoped.containsKey(count.entity())) return field;
          String at=field.sourceNodeId().value()+"/count-scope";
          var conditions=and(at+"/and",count.conditions(),this.copy(this.scope(count.entity()),at,Optional.empty(),count.entity()));
          return new io.kcg.sir.semantic.model.NormalizedViewField(field.id(),field.name(),field.span(),field.sourceNodeId(),field.type(),
             new io.kcg.sir.semantic.model.NormalizedViewField.CountRelated(count.rowSymbol(),count.entity(),count.connectionField(),conditions),
             field.directNamedTypeReferenceSiteId());
       }).toList();
       return new io.kcg.sir.semantic.model.NormalizedView(view.id(),view.name(),view.span(),view.sourceNodeId(),view.sourceEntity(),fields);
    }

    private NormalizedCapability capability(NormalizedCapability c) {
      NormalizedWorkflow w=c.workflow();
      return new NormalizedCapability(c.id(),c.name(),c.span(),c.sourceNodeId(),c.actorSymbol(),c.inputSymbol(),c.outputType(),
          c.fails(),c.requires(),c.exposure(),c.method(),c.path(),new NormalizedWorkflow(w.id(),w.span(),w.sourceNodeId(),this.steps(w.steps())));
   }

   private List<NormalizedStep> steps(List<NormalizedStep> input) {
      List<NormalizedStep> result=new ArrayList<>();
      for(NormalizedStep original:input) {
         NormalizedStep rewritten=this.step(original);result.add(rewritten);
         if(rewritten instanceof NormalizedStep.LoadStep load && this.scoped.containsKey(load.entitySymbol())) {
            String base=load.sourceNodeId().value()+"/scope";
            NormalizedExpression condition=this.copy(this.scope(load.entitySymbol()),base,Optional.of(load.resultVariable()),load.entitySymbol());
            result.add(new NormalizedStep.ValidateStep(new AstNodeId(base),condition.span(),condition,load.errorSymbol()));
         }
      }
      return List.copyOf(result);
   }

   private NormalizedStep step(NormalizedStep step) {
      String at = step.sourceNodeId().value();
      return switch (step) {
          case NormalizedStep.MonthStep s -> new NormalizedStep.MonthStep(s.sourceNodeId(),s.span(),
              this.expression(s.month(),at),this.expression(s.zone(),at),s.sinceVariable(),s.untilVariable(),s.errorSymbol());
          case NormalizedStep.SumStep s -> {
            NormalizedExpression predicate = this.expression(s.predicate(), at);
            if (this.scoped.containsKey(s.entitySymbol())) {
               String base = at + "/scope";
               NormalizedExpression scope = this.copy(this.scope(s.entitySymbol()), base, Optional.of(s.itemVariable()), s.entitySymbol());
               predicate = and(base + "/and", predicate, scope);
            }
            yield new NormalizedStep.SumStep(s.sourceNodeId(), s.span(), s.entitySymbol(), s.fieldSymbol(), predicate, s.resultVariable(), s.itemVariable());
         }
                   case NormalizedStep.EachStep s -> {
             NormalizedExpression predicate=this.expression(s.predicate(),at);
             if(this.scoped.containsKey(s.entitySymbol())) {
                String base=at+"/scope";
                predicate=and(base+"/and",predicate,this.copy(this.scope(s.entitySymbol()),base,Optional.of(s.itemVariable()),s.entitySymbol()));
             }
             yield new NormalizedStep.EachStep(s.sourceNodeId(),s.span(),s.entitySymbol(),predicate,s.orderKeys(),s.limit(),
                 s.errorSymbol(),s.rowVariable(),s.itemVariable(),this.steps(s.body()));
          }
case NormalizedStep.GroupedSumStep s -> {
            NormalizedExpression predicate = this.expression(s.predicate(), at);
            if (this.scoped.containsKey(s.entitySymbol())) {
               String base = at + "/scope";
               NormalizedExpression scope = this.copy(this.scope(s.entitySymbol()), base, Optional.of(s.itemVariable()), s.entitySymbol());
               predicate = and(base + "/and", predicate, scope);
            }
            yield new NormalizedStep.GroupedSumStep(s.sourceNodeId(), s.span(), s.entitySymbol(), s.fieldSymbol(),
               s.keySymbol(), predicate, s.page(), s.resultVariable(), s.itemVariable());
         }
         case NormalizedStep.ScalarQueryStep s -> {
            NormalizedExpression predicate = this.expression(s.predicate(), at);
            if (this.scoped.containsKey(s.entitySymbol())) {
               String base = at + "/scope";
               NormalizedExpression scope = this.copy(this.scope(s.entitySymbol()), base, Optional.of(s.itemVariable()), s.entitySymbol());
               predicate = and(base + "/and", predicate, scope);
            }
            yield new NormalizedStep.ScalarQueryStep(s.sourceNodeId(), s.span(), s.kind(), s.entitySymbol(), predicate, s.resultVariable(), s.itemVariable());
         }
         case NormalizedStep.FindStep s -> {
            NormalizedExpression predicate = this.expression(s.predicate(), at);
            if (this.scoped.containsKey(s.entitySymbol())) {
               String base = at + "/scope";
               NormalizedExpression scope = this.copy(this.scope(s.entitySymbol()), base, Optional.of(s.itemVariable()), s.entitySymbol());
               predicate = predicate instanceof NormalizedExpression.UnitLiteral ? scope : and(base + "/and", predicate, scope);
            }
            yield new NormalizedStep.FindStep(s.sourceNodeId(), s.span(), s.entitySymbol(), predicate, s.orderKeys(), s.page(), s.resultVariable(), s.itemVariable());
         }
         case NormalizedStep.ValidateStep s -> new NormalizedStep.ValidateStep(s.sourceNodeId(), s.span(), this.expression(s.condition(), at), s.errorSymbol());
         case NormalizedStep.LoadStep s -> new NormalizedStep.LoadStep(s.sourceNodeId(), s.span(), s.entitySymbol(), this.expression(s.idExpression(), at),
            s.keyField(), s.resultVariable(), s.errorSymbol(), s.forUpdate());
         case NormalizedStep.CreateStep s -> new NormalizedStep.CreateStep(s.sourceNodeId(), s.span(), s.entitySymbol(), s.resultVariable(), this.bindings(s.bindings(), at));
         case NormalizedStep.UpdateStep s -> new NormalizedStep.UpdateStep(s.sourceNodeId(), s.span(), s.targetVariable(),
            s.guard().map(g -> this.expression(g, at)), s.guardError(), this.bindings(s.bindings(), at));
         case NormalizedStep.ReturnStep s -> new NormalizedStep.ReturnStep(s.sourceNodeId(), s.span(), this.expression(s.value(), at));
         case NormalizedStep.PersistStep s -> s;
         case NormalizedStep.DeleteStep s -> s;
      };
   }

   private List<NormalizedBinding> bindings(List<NormalizedBinding> bindings, String at) {
      return bindings.stream().map(b -> new NormalizedBinding(b.fieldName(), b.fieldSymbol(), this.expression(b.value(), at))).toList();
   }

   /** {@code value} with the scope of every existence test inside it added to that test's conditions. */
   private NormalizedExpression expression(NormalizedExpression value, String at) {
      return switch (value) {
         case NormalizedExpression.ExistsExpression e -> {
            NormalizedExpression conditions = this.expression(e.conditions(), at);
            if (this.scoped.containsKey(e.entity())) {
               String base = at + "/" + e.sourceNodeId().value() + "/scope";
               conditions = and(base + "/and", conditions, this.copy(this.scope(e.entity()), base, Optional.empty(), e.entity()));
            }
            yield new NormalizedExpression.ExistsExpression(e.sourceNodeId(), e.span(), e.type(), e.entity(), e.connectionField(), conditions);
         }
         case NormalizedExpression.BinaryExpression e -> new NormalizedExpression.BinaryExpression(
            e.sourceNodeId(), e.span(), e.type(), this.expression(e.left(), at), e.operator(), this.expression(e.right(), at));
         case NormalizedExpression.UnaryExpression e -> new NormalizedExpression.UnaryExpression(
            e.sourceNodeId(), e.span(), e.type(), e.operator(), this.expression(e.operand(), at));
         default -> value;
      };
   }

   private NormalizedExpression scope(SymbolId entity) {
      return this.scoped.get(entity).scope().orElseThrow();
   }

   /**
    * The scope under ids below {@code base}. With a row variable, a bare field name becomes that row's field, as the
    * scope reads inside a find or after a load; without one it stays bare, as an existence test's conditions read.
    */
   private NormalizedExpression copy(NormalizedExpression value, String base, Optional<SymbolId> row, SymbolId entity) {
      AstNodeId id = new AstNodeId(base);
      return switch (value) {
         case NormalizedExpression.NameExpression e when row.isPresent() && this.fieldsOf.get(entity).contains(e.resolvedSymbol()) -> {
            Symbol variable = this.symbols.byId(row.get()).orElseThrow();
            SirType rowType = ((Symbol.VariableSymbol) variable).type();
            yield new NormalizedExpression.MemberExpression(id, e.span(), e.type(),
               new NormalizedExpression.NameExpression(new AstNodeId(base + "/row"), e.span(), rowType, variable.name(), row.get()), e.name(), e.resolvedSymbol());
         }
         case NormalizedExpression.NameExpression e -> new NormalizedExpression.NameExpression(id, e.span(), e.type(), e.name(), e.resolvedSymbol());
         case NormalizedExpression.MemberExpression e -> new NormalizedExpression.MemberExpression(id, e.span(), e.type(),
            this.copy(e.receiver(), base + "/receiver", row, entity), e.member(), e.resolvedMember());
         case NormalizedExpression.BinaryExpression e -> new NormalizedExpression.BinaryExpression(id, e.span(), e.type(),
            this.copy(e.left(), base + "/left", row, entity), e.operator(), this.copy(e.right(), base + "/right", row, entity));
         case NormalizedExpression.UnaryExpression e -> new NormalizedExpression.UnaryExpression(id, e.span(), e.type(), e.operator(),
            this.copy(e.operand(), base + "/operand", row, entity));
         case NormalizedExpression.TimeShiftExpression e -> new NormalizedExpression.TimeShiftExpression(id, e.span(), e.type(),
            this.copy(e.base(), base + "/base", row, entity), e.direction(), e.amount(), e.unit());
         case NormalizedExpression.BooleanLiteral e -> new NormalizedExpression.BooleanLiteral(id, e.span(), e.type(), e.value());
         case NormalizedExpression.IntegerLiteral e -> new NormalizedExpression.IntegerLiteral(id, e.span(), e.type(), e.value());
         case NormalizedExpression.DecimalLiteral e -> new NormalizedExpression.DecimalLiteral(id, e.span(), e.type(), e.value());
         case NormalizedExpression.StringLiteral e -> new NormalizedExpression.StringLiteral(id, e.span(), e.type(), e.value());
         case NormalizedExpression.NowExpression e -> new NormalizedExpression.NowExpression(id, e.span(), e.type());
         default -> throw new IllegalStateException("not allowed in a scope: " + value);
      };
   }

   private static NormalizedExpression and(String id, NormalizedExpression left, NormalizedExpression right) {
      return new NormalizedExpression.BinaryExpression(new AstNodeId(id), left.span(), PrimitiveType.BOOLEAN, left, AstBinaryOperator.AND, right);
   }
}
