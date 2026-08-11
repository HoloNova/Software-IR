package io.kcg.sir.semantic.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.DiagnosticCode;
import io.kcg.sir.api.DiagnosticSeverity;
import io.kcg.sir.api.RelatedLocation;
import io.kcg.sir.source.SourceSpan;
import java.util.List;

final class DiagnosticBuilder {
   private DiagnosticBuilder() {
   }

   static Diagnostic error(String code, String message, SourceSpan span) {
      return new Diagnostic(new DiagnosticCode(code), DiagnosticSeverity.ERROR, message, span, List.of(), List.of());
   }

   static Diagnostic errorWithRelated(String code, String message, SourceSpan span, RelatedLocation related) {
      return new Diagnostic(new DiagnosticCode(code), DiagnosticSeverity.ERROR, message, span, List.of(related), List.of());
   }
}
