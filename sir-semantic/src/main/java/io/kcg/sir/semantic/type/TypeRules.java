package io.kcg.sir.semantic.type;

public final class TypeRules {
   private TypeRules() {
   }

   public static boolean isAssignable(SirType source, SirType target) {
      if (source.equals(target)) {
         return true;
      } else if (target instanceof OptionalType optionalTarget) {
         return source instanceof OptionalType ? source.equals(optionalTarget) : isAssignable(source, optionalTarget.element());
      } else {
         return false;
      }
   }

   public static boolean isComparable(SirType type) {
      if (type instanceof PrimitiveType p) {
         switch (p) {
            case INT32:
            case INT64:
            case DECIMAL:
            case STRING:
            case DATE:
            case DATE_TIME:
               return true;
         }
      }

      return false;
   }

   public static boolean isNumeric(SirType type) {
      if (type instanceof PrimitiveType p) {
         switch (p) {
            case INT32:
            case INT64:
            case DECIMAL:
               return true;
         }
      }

      return false;
   }

   public static boolean isBoolean(SirType type) {
      return type == PrimitiveType.BOOLEAN;
   }
}
