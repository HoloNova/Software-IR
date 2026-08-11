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
      List<SpringBootDeclaration.Property> fields
   ) implements SpringBootDeclaration {
      public EntityDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         SpringBootDeclaration.requireText(tableName, "tableName");
         Objects.requireNonNull(identity, "identity");
         fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
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

   enum Generation {
      AUTO_INCREMENT,
      UUID;
   }

   enum HttpMethod {
      GET,
      POST;
   }

   enum HttpStatus {
      BAD_REQUEST;
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

   record InputDeclaration(LoweredNodeId id, LoweredOrigin origin, SymbolId sourceSymbol, String javaName, List<SpringBootDeclaration.Property> fields)
      implements SpringBootDeclaration {
      public InputDeclaration {
         SpringBootDeclaration.requireDeclaration(id, origin, sourceSymbol, javaName);
         fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
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
