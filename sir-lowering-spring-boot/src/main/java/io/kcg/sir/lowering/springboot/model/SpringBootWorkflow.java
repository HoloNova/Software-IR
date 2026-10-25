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

   /** Structural audit only; body scope is kept by the actual execution plan. */
   public static java.util.stream.Stream<Step> walk(List<Step> steps) {
       return steps.stream().flatMap(step -> step instanceof EachStep each
           ? java.util.stream.Stream.concat(java.util.stream.Stream.of(step),walk(each.body()))
           : step instanceof RepeatStep repeat ? java.util.stream.Stream.concat(java.util.stream.Stream.of(step),walk(repeat.body()))
           : java.util.stream.Stream.of(step));
   }

   /** The body of a loop step (each or repeat), or empty for any other step. */
   public static List<Step> body(Step step) {
       return step instanceof EachStep each ? each.body() : step instanceof RepeatStep repeat ? repeat.body() : List.of();
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
   *
   * <p>{@code relative}, when present, says the binding changes its own column by an amount
   * ({@code count: row.count + 1}); {@code value} is still the whole expression, which is what the
   * in-memory row holds, while the stored row is changed by {@code amount} in the statement itself.
   */
   public record Binding(
      SymbolId fieldSymbol, String targetProperty, SpringExpression value, java.util.Optional<String> sourcePropertyName,
      java.util.Optional<SpringBootWorkflow.Relative> relative
   ) {
      public Binding {
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
         SpringBootWorkflow.requireText(targetProperty, "targetProperty");
         Objects.requireNonNull(value, "value");
         Objects.requireNonNull(sourcePropertyName, "sourcePropertyName");
         Objects.requireNonNull(relative, "relative");
      }

      public Binding(SymbolId fieldSymbol, String targetProperty, SpringExpression value, java.util.Optional<String> sourcePropertyName) {
         this(fieldSymbol, targetProperty, value, sourcePropertyName, java.util.Optional.empty());
      }

      public Binding(SymbolId fieldSymbol, String targetProperty, SpringExpression value) {
         this(fieldSymbol, targetProperty, value, java.util.Optional.empty(), java.util.Optional.empty());
      }
   }

   /** The amount a column is increased by, or decreased by when {@code subtract}. */
   public record Relative(boolean subtract, SpringExpression amount, java.util.Optional<DecimalShape> decimalShape) {
      public Relative {
         Objects.requireNonNull(amount, "amount");
         Objects.requireNonNull(decimalShape, "decimalShape");
      }
      public Relative(boolean subtract, SpringExpression amount) {
         this(subtract, amount, java.util.Optional.empty());
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

   public record EachPlan(int limit, int fetchLimit, String mapperMethod, List<String> columns,
         int sourceStatements, int bodyStatements, int maximumStatements) {
        public EachPlan { requireText(mapperMethod,"each mapper"); columns=List.copyOf(columns); }
    }
    public record EachStep(LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol,
         SpringExpression predicate, List<OrderKey> orderKeys, List<SpringExpression.StringMatch> stringMatches,
         EachPlan plan, SymbolId errorSymbol, Variable row, Variable itemVariable, List<Step> body) implements Step {
        public EachStep {
            requireStep(id,origin);Objects.requireNonNull(entitySymbol);Objects.requireNonNull(predicate);
            orderKeys=List.copyOf(orderKeys);stringMatches=List.copyOf(stringMatches);Objects.requireNonNull(plan);
            Objects.requireNonNull(errorSymbol);Objects.requireNonNull(row);Objects.requireNonNull(itemVariable);body=List.copyOf(body);
        }
    }

    /**
     * A counted loop: the count is evaluated once, a count above {@code limit} fails with the error before any pass, and
     * the body runs with {@code index} = 0 .. count-1. {@code maximumStatements} is the bound times the body's statements.
     */
    public record RepeatStep(LoweredNodeId id, LoweredOrigin origin, SpringExpression count, int limit,
         SymbolId errorSymbol, Variable index, int bodyStatements, int maximumStatements, List<Step> body) implements Step {
        public RepeatStep {
            requireStep(id,origin);Objects.requireNonNull(count);Objects.requireNonNull(errorSymbol);Objects.requireNonNull(index);
            body=List.copyOf(body);
        }
    }

    public record GroupedSumPlan(SymbolId fieldSymbol, SymbolId keySymbol, String columnName, String keyColumn,
       String countProjection, String dataProjection, String countMethod, String dataMethod,
       int statements, boolean rejectWarnings) {
       public GroupedSumPlan {
          Objects.requireNonNull(fieldSymbol, "fieldSymbol"); Objects.requireNonNull(keySymbol, "keySymbol");
          for (String value : List.of(columnName, keyColumn, countProjection, dataProjection, countMethod, dataMethod))
             requireText(value, "group query plan");
       }
    }

    public record GroupedSumStep(LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol,
       SpringExpression predicate, List<SpringExpression.StringMatch> stringMatches, GroupedSumPlan plan,
       PageSpec page, Variable result, Variable itemVariable) implements Step {
       public GroupedSumStep {
          requireStep(id, origin); Objects.requireNonNull(entitySymbol, "entitySymbol");
          Objects.requireNonNull(predicate, "predicate"); stringMatches=List.copyOf(stringMatches);
          Objects.requireNonNull(plan, "plan"); Objects.requireNonNull(page, "page");
          Objects.requireNonNull(result, "result"); Objects.requireNonNull(itemVariable, "itemVariable");
       }
    }

    public record MonthPlan(String minimumInstant, String maximumInstant, int statements) {
        public MonthPlan { requireText(minimumInstant,"minimumInstant");requireText(maximumInstant,"maximumInstant"); }
     }
     public record MonthStep(LoweredNodeId id, LoweredOrigin origin, SpringExpression month, SpringExpression zone,
         Variable since, Variable until, SymbolId errorSymbol, MonthPlan plan) implements Step {
        public MonthStep {
           requireStep(id,origin);Objects.requireNonNull(month);Objects.requireNonNull(zone);
           Objects.requireNonNull(since);Objects.requireNonNull(until);Objects.requireNonNull(errorSymbol);Objects.requireNonNull(plan);
        }
     }

     public record SumPlan(SymbolId fieldSymbol, String columnName, String projectionSql, String mapperMethod,
                          int statements, boolean emptyAsZero, boolean rejectWarnings) {
       public SumPlan {
          Objects.requireNonNull(fieldSymbol, "fieldSymbol");
          requireText(columnName, "columnName");
          requireText(projectionSql, "projectionSql");
          requireText(mapperMethod, "mapperMethod");
       }
    }

    public record SumStep(LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol,
       SpringExpression predicate, List<SpringExpression.StringMatch> stringMatches, SumPlan plan,
       Variable result, Variable itemVariable) implements Step {
       public SumStep {
          requireStep(id, origin);
          Objects.requireNonNull(entitySymbol, "entitySymbol");
          Objects.requireNonNull(predicate, "predicate");
          stringMatches = List.copyOf(stringMatches);
          Objects.requireNonNull(plan, "plan");
          Objects.requireNonNull(result, "result");
          Objects.requireNonNull(itemVariable, "itemVariable");
       }
    }

    public enum ScalarResult { EXISTS, COUNT }

   /** Physical query choices are made by Lowering, including the one-row existence bound. */
   public record ScalarQueryPlan(ScalarResult kind, String mapperMethod,
      java.util.Optional<String> projectionProperty, java.util.Optional<String> tailSql, int statements) {
      public ScalarQueryPlan {
         Objects.requireNonNull(kind, "kind");
         requireText(mapperMethod, "mapperMethod");
         Objects.requireNonNull(projectionProperty, "projectionProperty");
         Objects.requireNonNull(tailSql, "tailSql");
      }
   }

   public record ScalarQueryStep(LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol,
      SpringExpression predicate, List<SpringExpression.StringMatch> stringMatches, ScalarQueryPlan plan,
      Variable result, Variable itemVariable) implements Step {
      public ScalarQueryStep {
         requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(predicate, "predicate");
         stringMatches = List.copyOf(Objects.requireNonNull(stringMatches, "stringMatches"));
         Objects.requireNonNull(plan, "plan");
         Objects.requireNonNull(result, "result");
         Objects.requireNonNull(itemVariable, "itemVariable");
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

   /**
   * Reads the row of the authenticated actor, first in a workflow that reads more of the actor than its identity
   * ({@code actor.role}). The actor was authenticated, so its row is expected; when it is gone the request is answered
   * as unauthenticated (401), like a failed authentication, and the author declares no error for it.
   */
   public record LoadActorStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId entitySymbol,
      SpringExpression idExpression,
      SpringBootWorkflow.Variable result
   ) implements SpringBootWorkflow.Step {
      public LoadActorStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(idExpression, "idExpression");
         Objects.requireNonNull(result, "result");
      }
   }

   /**
   * Loads one row. {@code idExpression} is the value to look it up by: the identity when
   * {@code keyField} is empty, otherwise the value of the unique entity field {@code keyField} names.
   * A load by a field is never a load for update, because the row lock is taken by identity.
   */
   public record LoadStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId entitySymbol,
      SpringExpression idExpression,
      SpringBootWorkflow.Variable result,
      SymbolId errorSymbol,
      boolean forUpdate,
      java.util.Optional<SymbolId> keyField,
      boolean explicitLock
   ) implements SpringBootWorkflow.Step {
      public LoadStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         Objects.requireNonNull(idExpression, "idExpression");
         Objects.requireNonNull(result, "result");
         Objects.requireNonNull(errorSymbol, "errorSymbol");
         Objects.requireNonNull(keyField, "keyField");
      }

      public LoadStep(
         LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol, SpringExpression idExpression,
         SpringBootWorkflow.Variable result, SymbolId errorSymbol, boolean forUpdate, java.util.Optional<SymbolId> keyField
      ) {
         this(id, origin, entitySymbol, idExpression, result, errorSymbol, forUpdate, keyField, false);
      }

      public LoadStep(
         LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol, SpringExpression idExpression,
         SpringBootWorkflow.Variable result, SymbolId errorSymbol, boolean forUpdate
      ) {
         this(id, origin, entitySymbol, idExpression, result, errorSymbol, forUpdate, java.util.Optional.empty());
      }

      public LoadStep(
         LoweredNodeId id, LoweredOrigin origin, SymbolId entitySymbol, SpringExpression idExpression,
         SpringBootWorkflow.Variable result, SymbolId errorSymbol
      ) {
         this(id, origin, entitySymbol, idExpression, result, errorSymbol, false, java.util.Optional.empty());
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

   /**
   * The value the workflow answers with.
   *
   * <p>{@code associationStatements} is how many batch reads answering costs when the value is one row that the
   * response view nests other views into (the related rows are read after the row, one statement per association). A
   * find already declares its own reads in its budget, so a returned find result and a returned row that needs
   * no association both say zero.
   */
   public record ReturnStep(LoweredNodeId id, LoweredOrigin origin, SpringExpression value, int associationStatements)
      implements SpringBootWorkflow.Step {
      public ReturnStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(value, "value");
         if (associationStatements < 0) {
            throw new IllegalArgumentException("associationStatements must not be negative");
         }
      }

      public ReturnStep(LoweredNodeId id, LoweredOrigin origin, SpringExpression value) {
         this(id, origin, value, 0);
      }
   }

   public sealed interface Step
      permits SpringBootWorkflow.ValidateStep,
      SpringBootWorkflow.LoadStep,
      SpringBootWorkflow.LoadActorStep,
      SpringBootWorkflow.FindStep,
       SpringBootWorkflow.EachStep,
       SpringBootWorkflow.RepeatStep,
      SpringBootWorkflow.ScalarQueryStep,
       SpringBootWorkflow.SumStep,
        SpringBootWorkflow.MonthStep,
       SpringBootWorkflow.GroupedSumStep,
      SpringBootWorkflow.CreateStep,
      SpringBootWorkflow.UpdateStep,
      SpringBootWorkflow.PersistStep,
      SpringBootWorkflow.ReturnStep {
      LoweredNodeId id();

      LoweredOrigin origin();
   }

   /**
   * The condition a stored row must still meet when its update is written, and what is raised when it
   * does not. It is checked inside the one statement that writes the row, never in Java beforehand.
   */
   public record UpdateGuard(SpringExpression predicate, SymbolId failure) {
      public UpdateGuard {
         Objects.requireNonNull(predicate, "predicate");
         Objects.requireNonNull(failure, "failure");
      }
   }

   public record UpdateStep(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId targetVariable,
      List<SpringBootWorkflow.Binding> bindings,
      java.util.Optional<SpringBootWorkflow.ConditionalUpdate> conditional,
      java.util.Optional<SpringBootWorkflow.UpdateGuard> guard
   ) implements SpringBootWorkflow.Step {
      public UpdateStep {
         SpringBootWorkflow.requireStep(id, origin);
         Objects.requireNonNull(targetVariable, "targetVariable");
         bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
         Objects.requireNonNull(conditional, "conditional");
         Objects.requireNonNull(guard, "guard");
      }

      public UpdateStep(
         LoweredNodeId id, LoweredOrigin origin, SymbolId targetVariable, List<SpringBootWorkflow.Binding> bindings,
         java.util.Optional<SpringBootWorkflow.ConditionalUpdate> conditional
      ) {
         this(id, origin, targetVariable, bindings, conditional, java.util.Optional.empty());
      }

      public UpdateStep(LoweredNodeId id, LoweredOrigin origin, SymbolId targetVariable, List<SpringBootWorkflow.Binding> bindings) {
         this(id, origin, targetVariable, bindings, java.util.Optional.empty(), java.util.Optional.empty());
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
