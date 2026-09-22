package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface NormalizedStep
   permits NormalizedStep.ValidateStep,
   NormalizedStep.LoadStep,
   NormalizedStep.FindStep,
   NormalizedStep.CreateStep,
   NormalizedStep.UpdateStep,
   NormalizedStep.PersistStep,
   NormalizedStep.ReturnStep {
   AstNodeId sourceNodeId();

   SourceSpan span();

   /** One key of a {@code find} step's declared {@code order by} clause, in authored order. */
   record OrderKey(AstNodeId sourceNodeId, SourceSpan span, SymbolId fieldSymbol, boolean descending) {
      public OrderKey {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
      }
   }

   /**
   * The declared pagination sources and the error raised for an illegal page or size.
   *
   * <p>Defaults and legal bounds are Lowering decisions, so they are absent here on purpose.
   */
   record PageSpec(AstNodeId sourceNodeId, SourceSpan span, SymbolId pageField, SymbolId sizeField, SymbolId errorSymbol) {
      public PageSpec {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(pageField, "pageField");
         Objects.requireNonNull(sizeField, "sizeField");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }

   record CreateStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId entitySymbol, SymbolId resultVariable, List<NormalizedBinding> bindings)
      implements NormalizedStep {
      public CreateStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(resultVariable, "resultVariable");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
      }
   }

   record FindStep(
      AstNodeId sourceNodeId,
      SourceSpan span,
      SymbolId entitySymbol,
      NormalizedExpression predicate,
      List<OrderKey> orderKeys,
      Optional<PageSpec> page,
      SymbolId resultVariable,
      SymbolId itemVariable
   ) implements NormalizedStep {
      public FindStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(predicate, "predicate");
         orderKeys = List.copyOf(Objects.requireNonNull(orderKeys, "orderKeys"));
         Objects.requireNonNull(page, "page");
         Objects.requireNonNull(resultVariable, "resultVariable");
         Objects.requireNonNull(itemVariable, "itemVariable");
      }
   }

   record LoadStep(
      AstNodeId sourceNodeId, SourceSpan span, SymbolId entitySymbol, NormalizedExpression idExpression, SymbolId resultVariable, SymbolId errorSymbol
   ) implements NormalizedStep {
      public LoadStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(idExpression, "idExpression");
         Objects.requireNonNull(resultVariable, "resultVariable");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }

   record PersistStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId targetVariable, Optional<SymbolId> failure) implements NormalizedStep {
      public PersistStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(targetVariable, "targetVariable");
         Objects.requireNonNull(failure, "failure");
      }

      public PersistStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId targetVariable) {
         this(sourceNodeId, span, targetVariable, Optional.empty());
      }
   }

   record ReturnStep(AstNodeId sourceNodeId, SourceSpan span, NormalizedExpression value) implements NormalizedStep {
      public ReturnStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(value, "value");
      }
   }

   record UpdateStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId targetVariable, List<NormalizedBinding> bindings) implements NormalizedStep {
      public UpdateStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(targetVariable, "targetVariable");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
      }
   }

   record ValidateStep(AstNodeId sourceNodeId, SourceSpan span, NormalizedExpression condition, SymbolId errorSymbol) implements NormalizedStep {
      public ValidateStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(condition, "condition");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }
}
