package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationDiagnostic;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationMain;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenProject;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EnumDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ErrorDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.InputDeclaration;
import java.util.ArrayList;
import java.util.List;

public final class GenerationEngine {
   private final SpringBootLoweredModel model;
   private final GenerationContext ctx;

   public GenerationEngine(SpringBootLoweredModel model) {
      this.model = model;
      this.ctx = new GenerationContext(model);
   }

   public GenerationEngine.Outcome run() {
      List<GeneratedFile> files = new ArrayList<>();
      this.renderPom(files);
      if (this.ctx.hasErrors()) {
         return new GenerationEngine.Outcome(List.of(), this.ctx.diagnostics());
      }

      this.renderApplication(files);
      if (this.ctx.hasErrors()) {
         return new GenerationEngine.Outcome(List.of(), this.ctx.diagnostics());
      }

      for (SpringArtifact artifact : this.model.artifacts()) {
         if (this.ctx.hasErrors()) {
            break;
         }

         this.renderArtifact(files, artifact);
      }

      return this.ctx.hasErrors()
         ? new GenerationEngine.Outcome(List.of(), this.ctx.diagnostics())
         : new GenerationEngine.Outcome(List.copyOf(files), List.of());
   }

   private void renderPom(List<GeneratedFile> files) {
      try {
         MavenProject project = this.model.mavenProject();
         files.add(PomRenderer.render(project));
      } catch (Exception e) {
         this.ctx.fail("SIR-GEN-NODE-001", "Failed to render pom.xml: " + e.getMessage(), this.model.mavenProject().id());
      }
   }

   private void renderApplication(List<GeneratedFile> files) {
      try {
         ApplicationMain app = this.model.applicationMain();
         files.add(ApplicationRenderer.render(app));
      } catch (Exception e) {
         this.ctx.fail("SIR-GEN-NODE-001", "Failed to render Application main class: " + e.getMessage(), this.model.applicationMain().id());
      }
   }

   private void renderArtifact(List<GeneratedFile> files, SpringArtifact artifact) {
      try {
         SpringBootDeclaration decl = this.ctx.declaration(artifact.ownerSymbol());
         GeneratedFile file = this.dispatch(artifact, decl);
         files.add(file);
      } catch (Exception e) {
         this.ctx.fail("SIR-GEN-NODE-001", "Failed to render " + artifact.role() + " for " + artifact.ownerSymbol() + ": " + e.getMessage(), artifact.id());
      }
   }

   private GeneratedFile dispatch(SpringArtifact artifact, SpringBootDeclaration decl) {
      return switch (artifact.role()) {
         case ENUM -> EnumRenderer.render(this.ctx, artifact, (EnumDeclaration)decl);
         case ENTITY_MODEL -> EntityRenderer.render(this.ctx, artifact, (EntityDeclaration)decl);
         case MAPPER -> MapperRenderer.render(this.ctx, artifact, (EntityDeclaration)decl);
         case REQUEST_DTO -> DtoRenderer.render(this.ctx, artifact, (InputDeclaration)decl);
         case EXCEPTION -> ExceptionRenderer.render(this.ctx, artifact, (ErrorDeclaration)decl);
         case SERVICE -> ServiceRenderer.render(this.ctx, artifact, (CapabilityDeclaration)decl);
         case CONTROLLER -> ControllerRenderer.render(this.ctx, artifact, (CapabilityDeclaration)decl);
      };
   }

   public record Outcome(List<GeneratedFile> files, List<GenerationDiagnostic> diagnostics) {
      public Outcome {
         files = List.copyOf(files);
         diagnostics = List.copyOf(diagnostics);
      }
   }
}
