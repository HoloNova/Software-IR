package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.AppliedFile;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.FailureDisposition;
import java.nio.file.Path;
import java.util.List;

public sealed interface TransactionResult permits TransactionResult.Success, TransactionResult.Failure {
   record Failure(FailureDisposition disposition, List<ExecutionDiagnostic> diagnostics) implements TransactionResult {
      public Failure {
         diagnostics = List.copyOf(diagnostics);
      }
   }

   record Success(Path outputRoot, List<AppliedFile> appliedFiles, List<ExecutionDiagnostic> warnings) implements TransactionResult {
      public Success {
         appliedFiles = List.copyOf(appliedFiles);
         warnings = List.copyOf(warnings);
      }
   }
}
