package io.kcg.sir.application.internal;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.DiagnosticSeverity;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.generator.springboot.api.GenerationDiagnostic;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.api.LoweringSeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class DiagnosticMapper {
   private DiagnosticMapper() {
   }

   public static List<ExecutionDiagnostic> fromParser(List<Diagnostic> diagnostics, ExecutionStage stage) {
      List<ExecutionDiagnostic> out = new ArrayList<>(diagnostics.size());

      for (Diagnostic d : diagnostics) {
         out.add(
            new ExecutionDiagnostic(
               d.code().value(), stage, mapSeverity(d.severity()), d.message(), Optional.of(d.primarySpan()), Optional.empty(), Optional.empty()
            )
         );
      }

      return out;
   }

   public static List<ExecutionDiagnostic> fromLowering(List<LoweringDiagnostic> diagnostics, ExecutionStage stage) {
      List<ExecutionDiagnostic> out = new ArrayList<>(diagnostics.size());

      for (LoweringDiagnostic d : diagnostics) {
         out.add(
            new ExecutionDiagnostic(
               d.code().value(), stage, mapLoweringSeverity(d.severity()), d.message(), Optional.of(d.primarySpan()), Optional.empty(), Optional.empty()
            )
         );
      }

      return out;
   }

   public static List<ExecutionDiagnostic> fromGeneration(List<GenerationDiagnostic> diagnostics, ExecutionStage stage) {
      List<ExecutionDiagnostic> out = new ArrayList<>(diagnostics.size());

      for (GenerationDiagnostic d : diagnostics) {
         out.add(new ExecutionDiagnostic(d.code(), stage, ExecutionSeverity.ERROR, d.message(), Optional.empty(), d.nodeId(), Optional.empty()));
      }

      return out;
   }

   private static ExecutionSeverity mapSeverity(DiagnosticSeverity severity) {
      return switch (severity) {
         case ERROR -> ExecutionSeverity.ERROR;
         case WARNING -> ExecutionSeverity.WARNING;
         case INFO -> ExecutionSeverity.INFO;
      };
   }

   private static ExecutionSeverity mapLoweringSeverity(LoweringSeverity severity) {
      return severity.isError() ? ExecutionSeverity.ERROR : ExecutionSeverity.valueOf(severity.name());
   }
}
