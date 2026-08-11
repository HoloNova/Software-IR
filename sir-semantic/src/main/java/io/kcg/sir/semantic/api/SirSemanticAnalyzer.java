package io.kcg.sir.semantic.api;

import io.kcg.sir.ast.AstDocument;
import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.semantic.internal.SemanticPipeline;
import java.util.Objects;

public final class SirSemanticAnalyzer {
   public SemanticAnalysis analyze(AstDocument document) {
      Objects.requireNonNull(document, "document");
      AstSoftware software = document.software();
      return new SemanticPipeline(software).run();
   }
}
