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
      SpringBootWorkflow workflow
   ) implements SpringBootDeclaration {
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
      }
   }

   enum CapabilityKind {
      COMMAND,
      QUERY;
   }

   record Constraint(LoweredNodeId id, LoweredOrigin origin, SpringBootDeclaration.ConstraintKind kind, List<String> arguments) {
      public Constraint {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(kind, "kind");
         arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
      }
   }

   enum ConstraintKind {
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

   /** An entity's concurrency token, with the value the target initializes a new row to. */
   record VersionSpec(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId fieldSymbol,
      String javaName,
      String columnName,
      LoweredJavaType.Scalar type,
      long initialValue
   ) {
      public VersionSpec {
         SpringBootDeclaration.requireDeclaration(id, origin, fieldSymbol, javaName);
         SpringBootDeclaration.requireText(columnName, "columnName");
         Objects.requireNonNull(type, "type");
      }
   }

   enum Generation {
      AUTO_INCREMENT,
      UUID;
   }

   enum HttpMethod {
      GET,
      POST,
      PATCH;
   }

   enum HttpStatus {
      BAD_REQUEST,
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
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      SymbolId sourceEntitySymbol,
      String sourceEntityJavaName,
      List<SpringBootDeclaration.ViewField> fields
   ) implements SpringBootDeclaration {
      public ViewDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         Objects.requireNonNull(sourceEntitySymbol, "sourceEntitySymbol");
         SpringBootDeclaration.requireText(sourceEntityJavaName, "sourceEntityJavaName");
         fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
      }
   }

   record ViewField(
      LoweredNodeId id,
      LoweredOrigin origin,
      SymbolId sourceSymbol,
      String javaName,
      LoweredJavaType type,
      SymbolId sourceFieldSymbol,
      String sourcePropertyName
   ) {
      public ViewField {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         Objects.requireNonNull(type, "type");
         Objects.requireNonNull(sourceFieldSymbol, "sourceFieldSymbol");
         SpringBootDeclaration.requireText(sourcePropertyName, "sourcePropertyName");
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
      READ_ONLY;
   }
}
