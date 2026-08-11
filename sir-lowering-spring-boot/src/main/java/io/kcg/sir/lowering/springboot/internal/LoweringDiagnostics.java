package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.api.LoweringDiagnosticCode;
import io.kcg.sir.lowering.api.LoweringSeverity;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

final class LoweringDiagnostics {
   static final String INPUT = "SIR-LOWER-INPUT-001";
   static final String TARGET = "SIR-LOWER-TARGET-001";
   static final String BINDING = "SIR-LOWER-BINDING-001";
   static final String TYPE = "SIR-LOWER-TYPE-001";
   static final String FEATURE = "SIR-LOWER-FEATURE-001";
   static final String NAME = "SIR-LOWER-NAME-001";
   static final String IR = "SIR-LOWER-IR-001";

   private LoweringDiagnostics() {
   }

   static LoweringDiagnostic error(String code, String message, SourceSpan span, SymbolId symbol, AstNodeId nodeId) {
      return new LoweringDiagnostic(
         new LoweringDiagnosticCode(code), LoweringSeverity.ERROR, message, span, Optional.ofNullable(symbol), Optional.ofNullable(nodeId)
      );
   }

   static List<LoweringDiagnostic> sorted(List<LoweringDiagnostic> diagnostics) {
      List<LoweringDiagnostic> sorted = new ArrayList<>(diagnostics);
      sorted.sort((left, right) -> {
         int comparison = Integer.compare(left.primarySpan().start().codePointOffset(), right.primarySpan().start().codePointOffset());
         if (comparison != 0) {
            return comparison;
         }

         comparison = left.code().value().compareTo(right.code().value());
         return comparison != 0 ? comparison : left.message().compareTo(right.message());
      });
      return List.copyOf(new LinkedHashSet<>(sorted));
   }
}
