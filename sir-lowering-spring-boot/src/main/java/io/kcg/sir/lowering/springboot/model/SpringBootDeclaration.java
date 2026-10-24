package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface SpringBootDeclaration
   permits SpringBootDeclaration.EnumDeclaration,
   SpringBootDeclaration.EntityDeclaration,
   SpringBootDeclaration.InputDeclaration,
   SpringBootDeclaration.ViewDeclaration,
   SpringBootDeclaration.ErrorDeclaration,
   SpringBootDeclaration.CapabilityDeclaration {
   LoweredNodeId id();

   LoweredOrigin origin();

   SymbolId sourceSymbol();

   String javaName();

   private static void requireDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName) {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(sourceSymbol, "sourceSymbol");
      requireText(javaName, "javaName");
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   record CapabilityDeclaration(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      String serviceName,
      String controllerName,
      String methodName,
      SpringBootDeclaration.HttpMethod httpMethod,
      String route,
      SpringBootDeclaration.CapabilityKind kind,
      SpringBootDeclaration.TransactionMode transactionMode,
      boolean authenticated,
      Optional<SpringBootWorkflow.Variable> actor,
      Optional<SpringBootWorkflow.Variable> input,
      Optional<ActorBinding> actorBinding,
      TransportPlan transportPlan,
      LoweredJavaType outputType,
      List<SymbolId> failures,
      SpringBootWorkflow workflow,
       List<RequiredArithmeticInput> arithmeticInputs
   ) implements SpringBootDeclaration {
      public CapabilityDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName,
            String serviceName, String controllerName, String methodName, HttpMethod httpMethod, String route,
            CapabilityKind kind, TransactionMode transactionMode, boolean authenticated,
            Optional<SpringBootWorkflow.Variable> actor, Optional<SpringBootWorkflow.Variable> input,
            Optional<ActorBinding> actorBinding, TransportPlan transportPlan, LoweredJavaType outputType,
            List<SymbolId> failures, SpringBootWorkflow workflow) {
         this(id, origin, sourceSymbol, javaName, serviceName, controllerName, methodName, httpMethod, route,
            kind, transactionMode, authenticated, actor, input, actorBinding, transportPlan, outputType,
            failures, workflow, List.of());
      }

      public CapabilityDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         SpringBootDeclaration.requireText(serviceName, "serviceName");
         SpringBootDeclaration.requireText(controllerName, "controllerName");
         SpringBootDeclaration.requireText(methodName, "methodName");
         Objects.requireNonNull(httpMethod, "httpMethod");
         SpringBootDeclaration.requireText(route, "route");
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(transactionMode, "transactionMode");
         Objects.requireNonNull(actor, "actor");
         Objects.requireNonNull(input, "input");
         Objects.requireNonNull(actorBinding, "actorBinding");
         Objects.requireNonNull(transportPlan, "transportPlan");
         Objects.requireNonNull(outputType, "outputType");
         failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
         Objects.requireNonNull(workflow, "workflow");
         arithmeticInputs = List.copyOf(Objects.requireNonNull(arithmeticInputs, "arithmeticInputs"));
      }
   }

   /** A formally bound input field required by this capability's numeric operations. */
   record RequiredArithmeticInput(LoweredNodeId id, LoweredOrigin origin, SymbolId inputSymbol,
         SymbolId fieldSymbol, String propertyName, String fieldPath) {
      public RequiredArithmeticInput {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(inputSymbol, "inputSymbol");
         Objects.requireNonNull(fieldSymbol, "fieldSymbol");
         requireText(propertyName, "propertyName");
         requireText(fieldPath, "fieldPath");
      }
   }

   enum CapabilityKind {
      COMMAND,
      QUERY;
   }

   record Constraint(LoweredNodeId id, LoweredOrigin origin, SpringBootDeclaration.ConstraintKind kind, List<String> arguments,
         Optional<DecimalShape> decimalShape) {
      public Constraint {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(kind, "kind");
         arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
         Objects.requireNonNull(decimalShape, "decimalShape");
         if ((kind == ConstraintKind.PRECISION) != decimalShape.isPresent())
            throw new IllegalArgumentException("only precision constraints carry an explicit DecimalShape");
         if (decimalShape.isPresent() && !arguments.isEmpty())
            throw new IllegalArgumentException("precision is a typed shape, not string arguments");
      }

      public Constraint(LoweredNodeId id, LoweredOrigin origin, ConstraintKind kind, List<String> arguments) {
         this(id, origin, kind, arguments, Optional.empty());
      }
   }

   enum ConstraintKind {
      NOT_NULL,
      PRECISION,
      TIME_ZONE,
       NOT_BLANK,
      EMAIL,
      SIZE,
      DECIMAL_MIN,
      DECIMAL_MAX;
   }

   record EntityDeclaration(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      String tableName,
      SpringBootDeclaration.Identity identity,
      List<SpringBootDeclaration.Property> fields,
      Optional<SpringBootDeclaration.VersionSpec> version
   ) implements SpringBootDeclaration {
      public EntityDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         SpringBootDeclaration.requireText(tableName, "tableName");
         Objects.requireNonNull(identity, "identity");
         fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
         Objects.requireNonNull(version, "version");
      }

      public EntityDeclaration(
         LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName, String tableName,
         SpringBootDeclaration.Identity identity, List<SpringBootDeclaration.Property> fields
      ) {
         this(id, origin, sourceSymbol, javaName, tableName, identity, fields, Optional.empty());
      }

   }

   record EnumDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName, List<SpringBootDeclaration.EnumMember> members)
      implements SpringBootDeclaration {
      public EnumDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         members = List.copyOf(Objects.requireNonNull(members, "members"));
      }
   }

   record EnumMember(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName) {
      public EnumMember {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
      }
   }

   record ErrorDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName, SpringBootDeclaration.HttpStatus httpStatus)
      implements SpringBootDeclaration {
      public ErrorDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         Objects.requireNonNull(httpStatus, "httpStatus");
      }
   }

   /**
    * An entity's concurrency token: the value the target initializes a new row to, and the step the
    * statement adds to it. The step lives here rather than only in the write policy so that an
    * entity-level artifact (its mapper) can be rendered from the entity declaration alone — nothing
    * about that artifact may depend on which capabilities currently exist (Q15).
    */
   record VersionSpec(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId fieldSymbol,
      String javaName,
      String columnName,
      LoweredJavaType.Scalar type,
      long initialValue,
      long versionIncrement
   ) {
      public VersionSpec {
         SpringBootDeclaration.requireDeclaration(id, origin, fieldSymbol, javaName);
         SpringBootDeclaration.requireText(columnName, "columnName");
         Objects.requireNonNull(type, "type");
         if (versionIncrement < 1) {
            throw new IllegalArgumentException("versionIncrement must be positive");
         }
      }
   }

   enum Generation {
      AUTO_INCREMENT,
      UUID;
   }

   enum HttpMethod {
      GET,
      POST,
      PUT,
      PATCH,
      DELETE;
   }

   enum HttpStatus {
      BAD_REQUEST,
      UNAUTHORIZED,
      FORBIDDEN,
      NOT_FOUND,
      CONFLICT;
   }

   record Identity(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      String columnName,
      LoweredJavaType.Scalar type,
      SpringBootDeclaration.Generation generation
   ) {
      public Identity {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         SpringBootDeclaration.requireText(columnName, "columnName");
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(generation, "generation");
      }
   }

   record InputDeclaration(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      List<SpringBootDeclaration.Property> fields,
      Optional<SpringBootDeclaration.PatchSpec> patch
   ) implements SpringBootDeclaration {
      public InputDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
         Objects.requireNonNull(patch, "patch");
      }

      public InputDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName, List<SpringBootDeclaration.Property> fields) {
         this(id, origin, sourceSymbol, javaName, fields, Optional.empty());
      }
   }

   /**
   * A patch payload's transport contract.
   *
   * <p>The identity travels beside the change set rather than inside it, and every change carries the
   * entity member it applies to, so a request can never be applied by matching names again.
   */
   record PatchSpec(
      SymbolId sourceEntitySymbol,
      String sourceEntityJavaName,
      SymbolId identityFieldSymbol,
      String identityPropertyName,
      String changesPropertyName,
      String expectedVersionPropertyName,
      List<SpringBootDeclaration.PatchChange> changes
   ) {
      public PatchSpec {
         Objects.requireNonNull(sourceEntitySymbol, "sourceEntitySymbol");
         SpringBootDeclaration.requireText(sourceEntityJavaName, "sourceEntityJavaName");
         Objects.requireNonNull(identityFieldSymbol, "identityFieldSymbol");
         SpringBootDeclaration.requireText(identityPropertyName, "identityPropertyName");
         SpringBootDeclaration.requireText(changesPropertyName, "changesPropertyName");
         SpringBootDeclaration.requireText(expectedVersionPropertyName, "expectedVersionPropertyName");
         changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
         if (changes.isEmpty()) {
         throw new IllegalArgumentException("a patch payload must declare at least one change");
         }
      }
   }

   /** One payload field of a patch, with the entity column it changes. */
   record PatchChange(
      SymbolId payloadFieldSymbol,
      String payloadPropertyName,
      SymbolId entityFieldSymbol,
      String entityPropertyName,
      String entityColumnName
   ) {
      public PatchChange {
         Objects.requireNonNull(payloadFieldSymbol, "payloadFieldSymbol");
         SpringBootDeclaration.requireText(payloadPropertyName, "payloadPropertyName");
         Objects.requireNonNull(entityFieldSymbol, "entityFieldSymbol");
         SpringBootDeclaration.requireText(entityPropertyName, "entityPropertyName");
         SpringBootDeclaration.requireText(entityColumnName, "entityColumnName");
      }
   }

   /**
   * A read-only response projection of {@code sourceEntitySymbol}.
   *
   * <p>Each field keeps both its own identity and the entity field it reads, together with the target
   * property name of that entity field, so the generator maps a row to a response DTO without
   * re-deriving anything from names.
   */
    record ViewDeclaration(
       LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName,
       SymbolId sourceEntitySymbol, String sourceEntityJavaName, List<ViewField> fields,
       Optional<SpringBootWorkflow.Variable> sourceRow
    ) implements SpringBootDeclaration {
       public ViewDeclaration {
          SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
          Objects.requireNonNull(sourceEntitySymbol, "sourceEntitySymbol");
          SpringBootDeclaration.requireText(sourceEntityJavaName, "sourceEntityJavaName");
          fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
          Objects.requireNonNull(sourceRow, "sourceRow");
       }
       public ViewDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName,
             SymbolId sourceEntitySymbol, String sourceEntityJavaName, List<ViewField> fields) {
          this(id, origin, sourceSymbol, javaName, sourceEntitySymbol, sourceEntityJavaName, fields, Optional.empty());
       }
    }

    record ViewField(
       LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName,
       LoweredJavaType type, ViewValue value
    ) {
       public ViewField {
          SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
          Objects.requireNonNull(type, "type");
          Objects.requireNonNull(value, "value");
       }
       public ViewField(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName,
             LoweredJavaType type, SymbolId sourceFieldSymbol, String sourcePropertyName, Optional<ViewRelationPlan> relation) {
          this(id, origin, sourceSymbol, javaName, type,
             relation.<ViewValue>map(plan -> new RelatedViewValue(sourceFieldSymbol, sourcePropertyName, plan))
                .orElseGet(() -> new ColumnViewValue(sourceFieldSymbol, sourcePropertyName)));
       }
       public ViewField(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName,
             LoweredJavaType type, SymbolId sourceFieldSymbol, String sourcePropertyName) {
          this(id, origin, sourceSymbol, javaName, type, sourceFieldSymbol, sourcePropertyName, Optional.empty());
       }
       public SymbolId sourceFieldSymbol() {
          return switch (value) {
             case ColumnViewValue column -> column.sourceFieldSymbol();
             case RelatedViewValue related -> related.sourceFieldSymbol();
             case ComputedViewValue ignored -> throw new IllegalStateException("computed projection has no column");
          };
       }
       public String sourcePropertyName() {
          return switch (value) {
             case ColumnViewValue column -> column.sourcePropertyName();
             case RelatedViewValue related -> related.sourcePropertyName();
             case ComputedViewValue ignored -> throw new IllegalStateException("computed projection has no column");
          };
       }
       public Optional<ViewRelationPlan> relation() {
          return value instanceof RelatedViewValue related ? Optional.of(related.plan()) : Optional.empty();
       }
    }

    sealed interface ViewValue permits ColumnViewValue, RelatedViewValue, ComputedViewValue {}

    record ColumnViewValue(SymbolId sourceFieldSymbol, String sourcePropertyName) implements ViewValue {
       public ColumnViewValue {
          Objects.requireNonNull(sourceFieldSymbol, "sourceFieldSymbol");
          SpringBootDeclaration.requireText(sourcePropertyName, "sourcePropertyName");
       }
    }
    record RelatedViewValue(SymbolId sourceFieldSymbol, String sourcePropertyName, ViewRelationPlan plan) implements ViewValue {
       public RelatedViewValue {
          Objects.requireNonNull(sourceFieldSymbol, "sourceFieldSymbol");
          SpringBootDeclaration.requireText(sourcePropertyName, "sourcePropertyName");
          Objects.requireNonNull(plan, "plan");
       }
    }
    record ComputedViewValue(SpringExpression expression) implements ViewValue {
       public ComputedViewValue { Objects.requireNonNull(expression, "expression"); }
    }

   /** How many related rows a nested projection reads. */
   enum RelationCardinality {
      /** At most one related row, found through the reference on this entity. */
      TO_ONE,
      /** A collection of related rows, found through the reference the related rows hold. */
      TO_MANY
   }

   /**
   * The batch read a nested projection needs, as a plan the renderer follows.
   *
   * <p>{@link #sourceKeyPropertyName()} names the property read from the rows already loaded to
   * build the query, {@link #targetLookupPropertyName()} the property it is compared against on the
   * related entity, {@link #indexKeyPropertyName()} the property a loaded related row is indexed by,
   * and {@link #orderPropertyName()} the identity a collection is ordered by so the same page always
   * yields the same response. Which side holds the reference is a decision recorded here — a single
   * related row is reached through the reference on this entity, a collection through the reference
   * the related rows hold — so the renderer never has to look for one.
   */
   record ViewRelationPlan(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId viewFieldSymbol,
      SpringBootDeclaration.RelationCardinality cardinality,
      SymbolId targetViewSymbol,
      SymbolId targetEntitySymbol,
      String sourceKeyPropertyName,
      String targetLookupPropertyName,
      String indexKeyPropertyName,
      Optional<String> orderPropertyName
   ) {
      public ViewRelationPlan {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(viewFieldSymbol, "viewFieldSymbol");
         Objects.requireNonNull(cardinality, "cardinality");
         Objects.requireNonNull(targetViewSymbol, "targetViewSymbol");
         Objects.requireNonNull(targetEntitySymbol, "targetEntitySymbol");
         SpringBootDeclaration.requireText(sourceKeyPropertyName, "sourceKeyPropertyName");
         SpringBootDeclaration.requireText(targetLookupPropertyName, "targetLookupPropertyName");
         SpringBootDeclaration.requireText(indexKeyPropertyName, "indexKeyPropertyName");
         Objects.requireNonNull(orderPropertyName, "orderPropertyName");
         orderPropertyName.ifPresent(name -> SpringBootDeclaration.requireText(name, "orderPropertyName"));
         if (cardinality == SpringBootDeclaration.RelationCardinality.TO_MANY && orderPropertyName.isEmpty()) {
            throw new IllegalArgumentException("a collection relation must state the order it is read in");
         }
      }
   }

   enum PersistenceShape {
      NONE,
      SCALAR,
      ENUM_TEXT,
      REFERENCE_ID;
   }

   record Property(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      Optional<String> columnName,
      LoweredJavaType type,
      SpringBootDeclaration.PersistenceShape persistenceShape,
      List<SpringBootDeclaration.Constraint> constraints
   ) {
      public Property {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         Objects.requireNonNull(columnName, "columnName");
         columnName.ifPresent(value -> SpringBootDeclaration.requireText(value, "columnName"));
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(persistenceShape, "persistenceShape");
         constraints = List.copyOf(Objects.requireNonNull(constraints, "constraints"));
      }
   }

   enum TransactionMode {
      NONE,
      REQUIRED,
      READ_COMMITTED,
      READ_ONLY,
      REPEATABLE_READ;
   }
}
