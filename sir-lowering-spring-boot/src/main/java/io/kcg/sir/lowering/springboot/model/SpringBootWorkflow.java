package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Objects;

public record SpringBootWorkflow(LoweredNodeId id, LoweredOrigin origin, List<SpringBootWorkflow.Variable> variables, List<SpringBootWorkflow.Step> steps) {
   public SpringBootWorkflow {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(origin, "origin");
      variables = List.copyOf(Objects.requireNonNull(variables, "variables"));
      steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
   }

   private static void requireStep(LoweredNodeId id, LoweredOrigin origin) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(origin, "origin");
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   public record Binding(SymbolId fieldSymbol, String targetProperty, SpringExpression value) {
      public Binding {
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
         SpringBootWorkflow.requireText(targetProperty, "targetProperty");
         Objects.requireNonNull(value, "value");
      }
   }

   public record CreateStep(
      LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol, SpringBootWorkflow.Variable result, List<SpringBootWorkflow.Binding> bindings
   ) implements SpringBootWorkflow.Step {
      public CreateStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(result, "result");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
      }
   }

   public record FindStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId entitySymbol,
      SpringExpression predicate,
      SpringBootWorkflow.Variable result,
      SpringBootWorkflow.Variable itemVariable
   ) implements SpringBootWorkflow.Step {
      public FindStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(predicate, "predicate");
         Objects.requireNonNull(result, "result");
         Objects.requireNonNull(itemVariable, "itemVariable");
      }
   }

   public record LoadStep(
      LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol, SpringExpression idExpression, SpringBootWorkflow.Variable result, SymbolId errorSymbol
   ) implements SpringBootWorkflow.Step {
      public LoadStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(idExpression, "idExpression");
         Objects.requireNonNull(result, "result");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }

   public record PersistStep(LoweredNodeId id, LoweredOrigin origin, SymbolId targetVariable, PersistenceAction action) implements SpringBootWorkflow.Step {
      public PersistStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(targetVariable, "targetVariable");
         Objects.requireNonNull(action, "action");
      }
   }

   public record ReturnStep(LoweredNodeId id, LoweredOrigin origin, SpringExpression value) implements SpringBootWorkflow.Step {
      public ReturnStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(value, "value");
      }
   }

   public sealed interface Step
      permits SpringBootWorkflow.ValidateStep,
      SpringBootWorkflow.LoadStep,
      SpringBootWorkflow.FindStep,
      SpringBootWorkflow.CreateStep,
      SpringBootWorkflow.UpdateStep,
      SpringBootWorkflow.PersistStep,
      SpringBootWorkflow.ReturnStep {
      LoweredNodeId id();

      LoweredOrigin origin();
   }

   public record UpdateStep(LoweredNodeId id, LoweredOrigin origin, SymbolId targetVariable, List<SpringBootWorkflow.Binding> bindings)
      implements SpringBootWorkflow.Step {
      public UpdateStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(targetVariable, "targetVariable");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
      }
   }

   public record ValidateStep(LoweredNodeId id, LoweredOrigin origin, SpringExpression condition, SymbolId errorSymbol) implements SpringBootWorkflow.Step {
      public ValidateStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(condition, "condition");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }

   public record Variable(SymbolId symbol, String targetName, LoweredJavaType type) {
      public Variable {
         Objects.requireNonNull(symbol, "symbol");
         SpringBootWorkflow.requireText(targetName, "targetName");
         Objects.requireNonNull(type, "type");
      }
   }
}
