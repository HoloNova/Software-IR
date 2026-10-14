package io.kcg.sir.semantic.api;

import java.util.Objects;

public final class SirSemanticAnalyzer {
   public SemanticAnalysis analyzeProject(ProjectSemanticInput input) {
      return io.kcg.sir.semantic.internal.ProjectSourceResolver.analyze(Objects.requireNonNull(input, "input"));
   }
}
