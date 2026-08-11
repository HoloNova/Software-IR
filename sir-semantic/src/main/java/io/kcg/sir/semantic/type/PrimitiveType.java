package io.kcg.sir.semantic.type;

public enum PrimitiveType implements SirType {
   BOOLEAN("Boolean"),
   INT32("Int32"),
   INT64("Int64"),
   DECIMAL("Decimal"),
   STRING("String"),
   UUID("Uuid"),
   DATE("Date"),
   DATE_TIME("DateTime"),
   UNIT("Unit");

   private final String sirName;

   PrimitiveType(String sirName) {
      this.sirName = sirName;
   }

   public String sirName() {
      return this.sirName;
   }
}
