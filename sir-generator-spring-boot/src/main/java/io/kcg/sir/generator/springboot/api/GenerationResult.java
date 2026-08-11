package io.kcg.sir.generator.springboot.api;

import java.util.List;
import java.util.Objects;

public sealed interface GenerationResult permits GenerationResult.Success, GenerationResult.Failure {
   record Failure(List<GenerationDiagnostic> diagnostics) implements GenerationResult {
      public Failure {
         Objects.requireNonNull(diagnostics, "diagnostics");
         if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Failure must carry at least one diagnostic");
         }

         diagnostics = List.copyOf(diagnostics);
      }
   }

   record Success(List<GeneratedFile> files) implements GenerationResult {
      public Success {
         Objects.requireNonNull(files, "files");
         files = List.copyOf(files);
      }
   }
}
