package io.kcg.sir.semantic.api;

import io.kcg.sir.api.Diagnostic;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface SemanticAnalysis permits SemanticAnalysis.Success, SemanticAnalysis.Failure {
   List<Diagnostic> diagnostics();

   default boolean hasErrors() {
      return this.diagnostics().stream().anyMatch(Diagnostic::isError);
   }

   default boolean isSuccess() {
      return !this.hasErrors();
   }

   Optional<NormalizedSemanticModel> model();

   record Failure(List<Diagnostic> diagnostics) implements SemanticAnalysis {
      public Failure {
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (!diagnostics.stream().anyMatch(Diagnostic::isError)) {
            throw new IllegalArgumentException("Failure must contain at least one error diagnostic");
         }
      }

      @Override
      public Optional<NormalizedSemanticModel> model() {
         return Optional.empty();
      }
   }

   record Success(NormalizedSemanticModel normalizedModel, List<Diagnostic> diagnostics) implements SemanticAnalysis {
      public Success {
         Objects.requireNonNull(normalizedModel, "normalizedModel");
         diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
         if (diagnostics.stream().anyMatch(Diagnostic::isError)) {
            throw new IllegalArgumentException("Success must not contain error diagnostics");
         }
      }

      @Override
      public Optional<NormalizedSemanticModel> model() {
         return Optional.of(this.normalizedModel);
      }
   }
}
