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
   NormalizedStep.ScalarQueryStep,
    NormalizedStep.SumStep,
    NormalizedStep.GroupedSumStep,
   NormalizedStep.CreateStep,
   NormalizedStep.UpdateStep,
   NormalizedStep.PersistStep,
   NormalizedStep.DeleteStep,
   NormalizedStep.ReturnStep {
   AstNodeId sourceNodeId();

   SourceSpan span();

   record SumStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span,
       @Reference SymbolId entitySymbol, @Reference SymbolId fieldSymbol, @Fact NormalizedExpression predicate,
       @Reference SymbolId resultVariable, @Derived SymbolId itemVariable) implements NormalizedStep {
       public SumStep {
          Objects.requireNonNull(sourceNodeId, "sourceNodeId");
          Objects.requireNonNull(span, "span");
          Objects.requireNonNull(entitySymbol, "entitySymbol");
          Objects.requireNonNull(fieldSymbol, "fieldSymbol");
          Objects.requireNonNull(predicate, "predicate");
          Objects.requireNonNull(resultVariable, "resultVariable");
          Objects.requireNonNull(itemVariable, "itemVariable");
       }
    }

    /**
     * One exact total per distinct value of {@code keySymbol}, bounded to one page.
     *
     * <p>The key is a field of the same entity, never an expression: the two statements that read the page (the
     * group count and the page itself) must group by the very same physical column.
     */
    record GroupedSumStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span,
        @Reference SymbolId entitySymbol, @Reference SymbolId fieldSymbol, @Reference SymbolId keySymbol,
        @Fact NormalizedExpression predicate, @Fact PageSpec page,
        @Reference SymbolId resultVariable, @Derived SymbolId itemVariable) implements NormalizedStep {
        public GroupedSumStep {
           Objects.requireNonNull(sourceNodeId, "sourceNodeId");
           Objects.requireNonNull(span, "span");
           Objects.requireNonNull(entitySymbol, "entitySymbol");
           Objects.requireNonNull(fieldSymbol, "fieldSymbol");
           Objects.requireNonNull(keySymbol, "keySymbol");
           Objects.requireNonNull(predicate, "predicate");
           Objects.requireNonNull(page, "page");
           Objects.requireNonNull(resultVariable, "resultVariable");
           Objects.requireNonNull(itemVariable, "itemVariable");
        }
    }

    record ScalarQueryStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span,
      @Fact io.kcg.sir.ast.AstScalarQueryStep.Kind kind, @Reference SymbolId entitySymbol,
      @Fact NormalizedExpression predicate, @Reference SymbolId resultVariable, @Derived SymbolId itemVariable) implements NormalizedStep {
      public ScalarQueryStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(predicate, "predicate");
         Objects.requireNonNull(resultVariable, "resultVariable");
         Objects.requireNonNull(itemVariable, "itemVariable");
      }
   }

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
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId entitySymbol, @Fact NormalizedExpression idExpression, @Reference Optional<SymbolId> keyField, @Reference SymbolId resultVariable, @Reference SymbolId errorSymbol, @Fact boolean forUpdate
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

      public LoadStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId entitySymbol, NormalizedExpression idExpression, Optional<SymbolId> keyField, SymbolId resultVariable, SymbolId errorSymbol) {
          this(sourceNodeId, span, entitySymbol, idExpression, keyField, resultVariable, errorSymbol, false);
       }

      public LoadStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId entitySymbol, NormalizedExpression idExpression, SymbolId resultVariable, SymbolId errorSymbol) {
         this(sourceNodeId, span, entitySymbol, idExpression, Optional.empty(), resultVariable, errorSymbol, false);
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

   /** Removes the stored row of {@code targetVariable}, which an earlier load read. */
   record DeleteStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId targetVariable) implements NormalizedStep {
      public DeleteStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(targetVariable, "targetVariable");
      }
   }

   record ReturnStep(@Source AstNodeId sourceNodeId, @Source SourceSpan span, @Fact NormalizedExpression value) implements NormalizedStep {
      public ReturnStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(value, "value");
      }
   }

   /**
   * Changes a loaded row. {@code guard}, when present, is a condition on the row as it is stored at the
   * moment of the write; {@code guardError} is raised when the stored row no longer meets it.
   */
   record UpdateStep(
      @Source AstNodeId sourceNodeId, @Source SourceSpan span, @Reference SymbolId targetVariable,
      @Fact Optional<NormalizedExpression> guard, @Reference Optional<SymbolId> guardError, @Fact List<NormalizedBinding> bindings
   ) implements NormalizedStep {
      public UpdateStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(targetVariable, "targetVariable");
         Objects.requireNonNull(guard, "guard");
         Objects.requireNonNull(guardError, "guardError");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
      }

      public UpdateStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId targetVariable, List<NormalizedBinding> bindings) {
         this(sourceNodeId, span, targetVariable, Optional.empty(), Optional.empty(), bindings);
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
