package io.kcg.sir.application.api;

public enum ChangePlanningContextFormatVersion {
   V1("KCG_CHANGE_PLANNING_CONTEXT_V1");

   private final String canonicalLiteral;

   ChangePlanningContextFormatVersion(String canonicalLiteral) {
      this.canonicalLiteral = canonicalLiteral;
   }

   public String canonicalLiteral() {
      return this.canonicalLiteral;
   }
}
