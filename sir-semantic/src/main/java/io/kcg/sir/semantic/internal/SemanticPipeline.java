package io.kcg.sir.semantic.internal;

import io.kcg.sir.ast.AstSoftware;
import io.kcg.sir.semantic.api.SemanticAnalysis;
import io.kcg.sir.semantic.context.ResolvedContext;
import io.kcg.sir.semantic.context.TypedContext;
import io.kcg.sir.semantic.context.ValidatedContext;

public final class SemanticPipeline {
   private final AstSoftware software;

   public SemanticPipeline(AstSoftware software) {
      this.software = software;
   }

   public SemanticAnalysis run() {
      ResolvedContext resolved = new ResolvePass(this.software).run();
      TypedContext typed = new TypePass(this.software, resolved).run();
      ValidatedContext validated = new ValidatePass(this.software, typed).run();
      return new NormalizePass(this.software, validated).run();
   }
}
