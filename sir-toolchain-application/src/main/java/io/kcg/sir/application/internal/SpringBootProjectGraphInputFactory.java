package io.kcg.sir.application.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.ApplicationMain;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenProject;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.projectgraph.api.ArtifactRole.ProjectRole;
import io.kcg.sir.projectgraph.api.GraphNodeId.File;
import io.kcg.sir.projectgraph.api.GraphNodeId.Lowered;
import io.kcg.sir.projectgraph.api.GraphNodeId.ProjectNodeId;
import io.kcg.sir.projectgraph.api.GraphNodeId.Semantic;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.ArtifactInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.EdgeBinding;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.FileInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.LoweredDeclarationInput;
import io.kcg.sir.projectgraph.api.ProjectGraphInput.SemanticDeclarationInput;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedView;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SpringBootProjectGraphInputFactory {
   public ProjectGraphInput build(
      NormalizedSemanticModel semanticModel, SpringBootLoweredModel loweredModel, List<GeneratedFile> generatedFiles, SourceId sourceId
   ) {
      Objects.requireNonNull(semanticModel, "semanticModel");
      Objects.requireNonNull(loweredModel, "loweredModel");
      Objects.requireNonNull(generatedFiles, "generatedFiles");
      Objects.requireNonNull(sourceId, "sourceId");
      List<SemanticDeclarationInput> semanticDeclarations = new ArrayList<>();
      List<LoweredDeclarationInput> loweredDeclarations = new ArrayList<>();
      List<ArtifactInput> artifacts = new ArrayList<>();
      List<FileInput> files = new ArrayList<>();
      List<EdgeBinding> edges = new ArrayList<>();
      ProjectNodeId projectId = ProjectNodeId.INSTANCE;

      for (NormalizedDeclaration decl : semanticModel.declarations()) {
         SymbolKind kind = symbolKindOf(decl);
         semanticDeclarations.add(new SemanticDeclarationInput(decl.id(), kind, decl.name(), decl.sourceNodeId(), decl.span()));
         edges.add(new EdgeBinding(GraphEdgeKind.DECLARES, projectId, new Semantic(decl.id())));
      }

      Map<SymbolId, LoweredNodeId> loweredIdBySourceSymbol = new LinkedHashMap<>();

      for (SpringBootDeclaration ldecl : loweredModel.declarations()) {
         loweredDeclarations.add(new LoweredDeclarationInput(ldecl.id(), ldecl.sourceSymbol(), ldecl.javaName(), ldecl.origin()));
         loweredIdBySourceSymbol.put(ldecl.sourceSymbol(), ldecl.id());
         edges.add(new EdgeBinding(GraphEdgeKind.LOWERS_TO, new Semantic(ldecl.sourceSymbol()), new Lowered(ldecl.id())));
      }

      for (SpringArtifact sa : loweredModel.artifacts()) {
         ArtifactRole role = mapDeclarationRole(sa.role());
         artifacts.add(new ArtifactInput(sa.id(), Optional.of(sa.ownerSymbol()), sa.origin(), role, sa.qualifiedName()));
         LoweredNodeId ownerLoweredId = loweredIdBySourceSymbol.get(sa.ownerSymbol());
         if (ownerLoweredId == null) {
            throw new IllegalStateException("SpringArtifact ownerSymbol has no matching LoweredDeclaration: " + sa.ownerSymbol());
         }

         edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, new Lowered(ownerLoweredId), new Lowered(sa.id())));
      }

      MavenProject maven = loweredModel.mavenProject();
      artifacts.add(new ArtifactInput(maven.id(), Optional.empty(), maven.origin(), ProjectRole.MAVEN_PROJECT, maven.path()));
      edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, projectId, new Lowered(maven.id())));
      ApplicationMain app = loweredModel.applicationMain();
      artifacts.add(new ArtifactInput(app.id(), Optional.empty(), app.origin(), ProjectRole.APPLICATION_MAIN, app.path()));
      edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, projectId, new Lowered(app.id())));

      for (ProjectArtifact projectArtifact : loweredModel.projectArtifacts()) {
         ArtifactRole role = mapProjectRole(projectArtifact);
         artifacts.add(new ArtifactInput(projectArtifact.id(), Optional.empty(), projectArtifact.origin(), role, projectArtifactPath(projectArtifact)));
         edges.add(new EdgeBinding(GraphEdgeKind.OWNS_ARTIFACT, projectId, new Lowered(projectArtifact.id())));
      }

      for (GeneratedFile gf : generatedFiles) {
         String content = gf.content();
         long byteCount = Sha256.utf8ByteCount(content);
         String sha256 = Sha256.hexDigest(content);
         files.add(new FileInput(gf.relativePath(), gf.artifactId(), gf.symbolId(), byteCount, sha256));
         edges.add(new EdgeBinding(GraphEdgeKind.GENERATES_FILE, new Lowered(gf.artifactId()), new File(gf.relativePath())));
      }

      return new ProjectGraphInput(GraphVersion.V0_1, sourceId, loweredModel.displayName(), semanticDeclarations, loweredDeclarations, artifacts, files, edges);
   }

   private static SymbolKind symbolKindOf(NormalizedDeclaration decl) {
      if (decl instanceof NormalizedEnum) {
         return SymbolKind.ENUM;
      } else if (decl instanceof NormalizedEntity) {
         return SymbolKind.ENTITY;
      } else if (decl instanceof NormalizedInput) {
         return SymbolKind.INPUT;
      } else if (decl instanceof NormalizedView) {
         return SymbolKind.VIEW;
      } else if (decl instanceof NormalizedError) {
         return SymbolKind.ERROR;
      } else if (decl instanceof NormalizedCapability) {
         return SymbolKind.CAPABILITY;
      } else {
         throw new IllegalStateException("unsupported NormalizedDeclaration variant: " + decl.getClass());
      }
   }

   /**
    * The role a project-level artifact plays in the graph.
    *
    * <p>Each artifact kind has its own role so the graph distinguishes, for example, the response
    * envelope from the failure advice instead of collapsing them into one "support file" role.
    */
   private static ArtifactRole.ProjectRole mapProjectRole(ProjectArtifact projectArtifact) {
      return switch (projectArtifact) {
         case ProjectArtifact.PageResponse page -> ProjectRole.PAGE_RESPONSE;
         case ProjectArtifact.ApiErrorResponse error -> ProjectRole.API_ERROR_RESPONSE;
         case ProjectArtifact.ApiExceptionBase base -> ProjectRole.API_EXCEPTION_BASE;
         case ProjectArtifact.ApiExceptionAdvice advice -> ProjectRole.API_EXCEPTION_ADVICE;
         case ProjectArtifact.ValidationSupport support -> ProjectRole.VALIDATION_SUPPORT;
         case ProjectArtifact.ApplicationConfig config -> ProjectRole.APPLICATION_CONFIG;
         case ProjectArtifact.MavenProject pom -> ProjectRole.MAVEN_PROJECT;
         case ProjectArtifact.ApplicationMain main -> ProjectRole.APPLICATION_MAIN;
      };
   }

   private static String projectArtifactPath(ProjectArtifact projectArtifact) {
      return switch (projectArtifact) {
         case ProjectArtifact.PageResponse page -> page.path();
         case ProjectArtifact.ApiErrorResponse error -> error.path();
         case ProjectArtifact.ApiExceptionBase base -> base.path();
         case ProjectArtifact.ApiExceptionAdvice advice -> advice.path();
         case ProjectArtifact.ValidationSupport support -> support.path();
         case ProjectArtifact.ApplicationConfig config -> config.path();
         case ProjectArtifact.MavenProject pom -> pom.path();
         case ProjectArtifact.ApplicationMain main -> main.path();
      };
   }

   private static ArtifactRole mapDeclarationRole(Role role) {
      return switch (role) {
         case ENUM -> DeclarationRole.ENUM;
         case ENTITY_MODEL -> DeclarationRole.ENTITY_MODEL;
         case MAPPER -> DeclarationRole.MAPPER;
         case REQUEST_DTO -> DeclarationRole.REQUEST_DTO;
         case VIEW_DTO -> DeclarationRole.VIEW_DTO;
         case EXCEPTION -> DeclarationRole.EXCEPTION;
         case SERVICE -> DeclarationRole.SERVICE;
         case CONTROLLER -> DeclarationRole.CONTROLLER;
      };
   }
}
