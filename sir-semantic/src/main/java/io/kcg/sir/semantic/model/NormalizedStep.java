package io.kcg.sir.semantic.model;

import io.kcg.sir.semantic.schema.Derived;
import io.kcg.sir.semantic.schema.Fact;
import io.kcg.sir.semantic.schema.Reference;
import io.kcg.sir.semantic.schema.Source;
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
   record OrderKey(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId fieldSymbol, @Fact boolean descending) {
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
   record PageSpec(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId pageField, @Reference SymbolId sizeField, @Reference SymbolId errorSymbol) {
      public PageSpec {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(pageField, "pageField");
         Objects.requireNonNull(sizeField, "sizeField");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }

   record CreateStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId entitySymbol, @Reference SymbolId resultVariable, @Fact List<NormalizedBinding> bindings)
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
      @Source AstNodeId sourceNodeId,
      @Source SourceSpan span,
      @Reference SymbolId entitySymbol,
      @Fact NormalizedExpression predicate,
      @Fact List<OrderKey> orderKeys,
      @Fact Optional<PageSpec> page,
      @Reference SymbolId resultVariable,
      @Derived SymbolId itemVariable
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

   /**
   * Loads one row. {@code idExpression} is the value the row is looked up by: its identity when
   * {@code keyField} is empty, otherwise the value of the unique field {@code keyField} names.
   */
   record LoadStep(
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId entitySymbol, @Fact NormalizedExpression idExpression, @Reference Optional<SymbolId> keyField, @Reference SymbolId resultVariable, @Reference SymbolId errorSymbol
   ) implements NormalizedStep {
      public LoadStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(idExpression, "idExpression");
         Objects.requireNonNull(keyField, "keyField");
         Objects.requireNonNull(resultVariable, "resultVariable");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }

      public LoadStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId entitySymbol, NormalizedExpression idExpression, SymbolId resultVariable, SymbolId errorSymbol) {
         this(sourceNodeId, span, entitySymbol, idExpression, Optional.empty(), resultVariable, errorSymbol);
      }
   }

   record PersistStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId targetVariable, @Reference Optional<SymbolId> failure) implements NormalizedStep {
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

   record ReturnStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Fact NormalizedExpression value) implements NormalizedStep {
      public ReturnStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(value, "value");
      }
   }

   record UpdateStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId targetVariable, @Fact List<NormalizedBinding> bindings) implements NormalizedStep {
      public UpdateStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(targetVariable, "targetVariable");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
      }
   }

   record ValidateStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Fact NormalizedExpression condition, @Reference SymbolId errorSymbol) implements NormalizedStep {
      public ValidateStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(condition, "condition");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }
}
