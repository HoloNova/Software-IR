package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.EntityReference;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.ListValue;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.OptionalValue;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.ScalarKind;
import java.util.Optional;

final class TypeRenderer {
   private TypeRenderer() {
   }

   static String renderType(LoweredJavaType type) {
      return switch (type) {
         case Scalar s -> scalarPrimitive(s.kind());
         case Declared d -> d.javaName();
         case OptionalValue o -> "java.util.Optional<" + renderBoxedType(o.elementType()) + ">";
         case ListValue l -> "java.util.List<" + renderBoxedType(l.elementType()) + ">";
         case EntityReference ref -> scalarBoxed(ref.identityStorageType().kind());
         default -> throw new MatchException(null, null);
      };
   }

   static String renderBoxedType(LoweredJavaType type) {
      return switch (type) {
         case Scalar s -> scalarBoxed(s.kind());
         case Declared d -> d.javaName();
         case OptionalValue o -> "java.util.Optional<" + renderBoxedType(o.elementType()) + ">";
         case ListValue l -> "java.util.List<" + renderBoxedType(l.elementType()) + ">";
         case EntityReference ref -> scalarBoxed(ref.identityStorageType().kind());
         default -> throw new MatchException(null, null);
      };
   }

   static Optional<String> importFor(LoweredJavaType type) {
      return switch (type) {
         case Scalar s -> scalarImport(s.kind());
         case Declared d -> Optional.empty();
         case OptionalValue o -> {
            Optional<String> inner = importFor(o.elementType());
            yield inner.isPresent() ? inner : Optional.of("java.util.Optional");
         }
         case ListValue l -> {
            Optional<String> inner = importFor(l.elementType());
            yield inner.isPresent() ? inner : Optional.of("java.util.List");
         }
         case EntityReference ref -> scalarImport(ref.identityStorageType().kind());
         default -> throw new MatchException(null, null);
      };
   }

   static boolean needsImport(LoweredJavaType type) {
      return importFor(type).isPresent();
   }

   private static String scalarPrimitive(ScalarKind kind) {
      return switch (kind) {
         case BOOLEAN -> "boolean";
         case INTEGER -> "int";
         case LONG -> "long";
         case BIG_DECIMAL -> "java.math.BigDecimal";
         case STRING -> "String";
         case UUID -> "java.util.UUID";
         case LOCAL_DATE -> "java.time.LocalDate";
         case INSTANT -> "java.time.Instant";
         case VOID -> "void";
      };
   }

   private static String scalarBoxed(ScalarKind kind) {
      return switch (kind) {
         case BOOLEAN -> "Boolean";
         case INTEGER -> "Integer";
         case LONG -> "Long";
         case BIG_DECIMAL -> "java.math.BigDecimal";
         case STRING -> "String";
         case UUID -> "java.util.UUID";
         case LOCAL_DATE -> "java.time.LocalDate";
         case INSTANT -> "java.time.Instant";
         case VOID -> "Void";
      };
   }

   private static Optional<String> scalarImport(ScalarKind kind) {
      return switch (kind) {
         case BIG_DECIMAL -> Optional.of("java.math.BigDecimal");
         default -> Optional.empty();
         case UUID -> Optional.of("java.util.UUID");
         case LOCAL_DATE -> Optional.of("java.time.LocalDate");
         case INSTANT -> Optional.of("java.time.Instant");
      };
   }
}
