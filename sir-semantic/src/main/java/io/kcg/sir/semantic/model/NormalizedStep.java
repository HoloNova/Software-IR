package io.kcg.sir.semantic.model;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;

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
      AstNodeId sourceNodeId, SourceSpan span, SymbolId entitySymbol, NormalizedExpression predicate, SymbolId resultVariable, SymbolId itemVariable
   ) implements NormalizedStep {
      public FindStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(predicate, "predicate");
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

   record PersistStep(AstNodeId sourceNodeId, SourceSpan span, SymbolId targetVariable) implements NormalizedStep {
      public PersistStep {
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         Objects.requireNonNull(targetVariable, "targetVariable");
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
