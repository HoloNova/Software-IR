package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public sealed interface LoweredJavaType
   permits LoweredJavaType.Scalar,
   LoweredJavaType.Declared,
   LoweredJavaType.OptionalValue,
   LoweredJavaType.ListValue,
   LoweredJavaType.EntityReference {
   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   record Declared(LoweredJavaType.DeclaredKind kind, SymbolId symbolId, String javaName) implements LoweredJavaType {
      public Declared {
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(symbolId, "symbolId");
         LoweredJavaType.requireText(javaName, "javaName");
      }
   }

   enum DeclaredKind {
      ENUM,
      ENTITY,
      INPUT;
   }

   record EntityReference(SymbolId entitySymbol, String entityJavaName, LoweredJavaType.Scalar identityStorageType) implements LoweredJavaType {
      public EntityReference {
         Objects.requireNonNull(entitySymbol, "entitySymbol");
         LoweredJavaType.requireText(entityJavaName, "entityJavaName");
         Objects.requireNonNull(identityStorageType, "identityStorageType");
      }
   }

   record ListValue(LoweredJavaType elementType) implements LoweredJavaType {
      public ListValue {
         Objects.requireNonNull(elementType, "elementType");
      }
   }

   record OptionalValue(LoweredJavaType elementType) implements LoweredJavaType {
      public OptionalValue {
         Objects.requireNonNull(elementType, "elementType");
      }
   }

   record Scalar(LoweredJavaType.ScalarKind kind) implements LoweredJavaType {
      public Scalar {
         Objects.requireNonNull(kind, "kind");
      }
   }

   enum ScalarKind {
      BOOLEAN,
      INTEGER,
      LONG,
      BIG_DECIMAL,
      STRING,
      UUID,
      LOCAL_DATE,
      INSTANT,
      VOID;
   }
}
