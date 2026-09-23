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

   /**
   * One authored value assignment: the entity member it targets, the target's property name, the
   * value, and — when the value is a plain reference to an input member — the request property the
   * client sent, which is what a field error must point at.
   */
   public record Binding(SymbolId fieldSymbol, String targetProperty, SpringExpression value, java.util.Optional<String> sourcePropertyName) {
      public Binding {
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
         SpringBootWorkflow.requireText(targetProperty, "targetProperty");
         Objects.requireNonNull(value, "value");
         Objects.requireNonNull(sourcePropertyName, "sourcePropertyName");
      }

      public Binding(SymbolId fieldSymbol, String targetProperty, SpringExpression value) {
         this(fieldSymbol, targetProperty, value, java.util.Optional.empty());
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
      List<SpringBootWorkflow.OrderKey> orderKeys,
      java.util.Optional<SpringBootWorkflow.PageSpec> page,
      List<SpringExpression.StringMatch> stringMatches,
      SpringBootWorkflow.StatementBudget statementBudget,
      SpringBootWorkflow.Variable result,
      SpringBootWorkflow.Variable itemVariable
   ) implements SpringBootWorkflow.Step {
      public FindStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(predicate, "predicate");
         orderKeys = List.copyOf(Objects.requireNonNull(orderKeys, "orderKeys"));
         Objects.requireNonNull(page, "page");
         stringMatches = List.copyOf(Objects.requireNonNull(stringMatches, "stringMatches"));
         Objects.requireNonNull(statementBudget, "statementBudget");
         Objects.requireNonNull(result, "result");
         Objects.requireNonNull(itemVariable, "itemVariable");
      }
   }

   /**
   * The number of SQL statements one find promises to issue.
   *
   * <p>{@link #pageStatements()} covers the reads the page itself needs — the count and the page
   * query, or a single query when the result is not paged — and {@link #associationStatements()}
   * one batch read per association the response projection nests. An empty page still costs the
   * page reads and skips the association reads, which is what {@link #emptyPageStatements()}
   * states. The numbers live in the IR because the promise is part of the target's contract, not an
   * implementation detail a renderer may recompute.
   */
   public record StatementBudget(int pageStatements, int associationStatements) {
      public StatementBudget {
         if (pageStatements < 1) {
            throw new IllegalArgumentException("pageStatements must be positive");
         }

         if (associationStatements < 0) {
            throw new IllegalArgumentException("associationStatements must not be negative");
         }
      }

      public int total() {
         return this.pageStatements + this.associationStatements;
      }

      public int emptyPageStatements() {
         return this.pageStatements;
      }
   }

   /** One declared {@code order by} key, in authored order. */
   public record OrderKey(SymbolId fieldSymbol, String targetMemberName, boolean descending) {
      public OrderKey {
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
         SpringBootWorkflow.requireText(targetMemberName, "targetMemberName");
      }
   }

   /**
   * A declared pagination clause: where page and size come from, the legal bounds, and the error to
   * raise when the request violates them.
   */
   public record PageSpec(
      SymbolId pageFieldSymbol,
      SymbolId sizeFieldSymbol,
      String pagePropertyName,
      String sizePropertyName,
      int defaultSize,
      int maxSize,
      int maxPageNumber,
      SymbolId errorSymbol
   ) {
      public PageSpec {
         Objects.requireNonNull(pageFieldSymbol, "pageFieldSymbol");
         Objects.requireNonNull(sizeFieldSymbol, "sizeFieldSymbol");
         SpringBootWorkflow.requireText(pagePropertyName, "pagePropertyName");
         SpringBootWorkflow.requireText(sizePropertyName, "sizePropertyName");
         if (defaultSize < 1) {
         throw new IllegalArgumentException("defaultSize must be positive");
         }

         if (maxSize < defaultSize) {
         throw new IllegalArgumentException("maxSize must not be smaller than defaultSize");
         }

         if (maxPageNumber < 1) {
         throw new IllegalArgumentException("maxPageNumber must be positive");
         }

         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }
   }

   public record LoadStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId entitySymbol,
      SpringExpression idExpression,
      SpringBootWorkflow.Variable result,
      SymbolId errorSymbol,
      boolean forUpdate
   ) implements SpringBootWorkflow.Step {
      public LoadStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(idExpression, "idExpression");
         Objects.requireNonNull(result, "result");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
      }

      public LoadStep(
         LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol, SpringExpression idExpression,
         SpringBootWorkflow.Variable result, SymbolId errorSymbol
      ) {
         this(id, origin, entitySymbol, idExpression, result, errorSymbol, false);
      }
   }

   public record PersistStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId targetVariable,
      PersistenceAction action,
      java.util.Optional<SymbolId> failure,
      java.util.Optional<ConditionalUpdate> conditional
   ) implements SpringBootWorkflow.Step {
      public PersistStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(targetVariable, "targetVariable");
         Objects.requireNonNull(action, "action");
         Objects.requireNonNull(failure, "failure");
         Objects.requireNonNull(conditional, "conditional");
      }

      public PersistStep(LoweredNodeId id, LoweredOrigin origin, SymbolId targetVariable, PersistenceAction action) {
         this(id, origin, targetVariable, action, java.util.Optional.empty(), java.util.Optional.empty());
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

   public record UpdateStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId targetVariable,
      List<SpringBootWorkflow.Binding> bindings,
      java.util.Optional<SpringBootWorkflow.ConditionalUpdate> conditional
   ) implements SpringBootWorkflow.Step {
      public UpdateStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(targetVariable, "targetVariable");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
         Objects.requireNonNull(conditional, "conditional");
      }

      public UpdateStep(LoweredNodeId id, LoweredOrigin origin, SymbolId targetVariable, List<SpringBootWorkflow.Binding> bindings) {
         this(id, origin, targetVariable, bindings, java.util.Optional.empty());
      }
   }

   /**
   * The conditional update a versioned entity's change lowers to.
   *
   * <p>Every change column is written from the merged candidate, and the row is matched by identity
   * and expected version; the version column is incremented by the target, never by the request.
   */
   public record ConditionalUpdate(
      SymbolId entitySymbol,
      String tableName,
      SymbolId identityFieldSymbol,
      String identityPropertyName,
      String identityColumnName,
      SymbolId versionFieldSymbol,
      String versionPropertyName,
      String versionColumnName,
      long versionIncrement,
      java.util.Optional<SymbolId> failure,
      List<SpringBootWorkflow.ColumnAssignment> assignments
   ) {
      public ConditionalUpdate {
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         SpringBootWorkflow.requireText(tableName, "tableName");
         Objects.requireNonNull(identityFieldSymbol, "identityFieldSymbol");
         SpringBootWorkflow.requireText(identityPropertyName, "identityPropertyName");
         SpringBootWorkflow.requireText(identityColumnName, "identityColumnName");
         Objects.requireNonNull(versionFieldSymbol, "versionFieldSymbol");
         SpringBootWorkflow.requireText(versionPropertyName, "versionPropertyName");
         SpringBootWorkflow.requireText(versionColumnName, "versionColumnName");
         if (versionIncrement < 1) {
         throw new IllegalArgumentException("versionIncrement must be positive");
         }

         Objects.requireNonNull(failure, "failure");
         assignments = List.copyOf(Objects.requireNonNull(assignments, "assignments"));
         if (assignments.isEmpty()) {
         throw new IllegalArgumentException("a conditional update must assign at least one column");
         }
      }
   }

   /** One {@code SET} column of a conditional update, with the request property that can supply it. */
   public record ColumnAssignment(
      SymbolId entityFieldSymbol,
      String entityPropertyName,
      String entityColumnName,
      String payloadPropertyName
   ) {
      public ColumnAssignment {
         Objects.requireNonNull(entityFieldSymbol, "entityFieldSymbol");
         SpringBootWorkflow.requireText(entityPropertyName, "entityPropertyName");
         SpringBootWorkflow.requireText(entityColumnName, "entityColumnName");
         SpringBootWorkflow.requireText(payloadPropertyName, "payloadPropertyName");
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
