package io.kcg.sir.projectgraph.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSpan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProjectGraphInput(
   GraphVersion version,
   SourceId sourceId,
   String projectDisplayName,
   List<ProjectGraphInput.SemanticDeclarationInput> semanticDeclarations,
   List<ProjectGraphInput.LoweredDeclarationInput> loweredDeclarations,
   List<ProjectGraphInput.ArtifactInput> artifacts,
   List<ProjectGraphInput.FileInput> files,
   List<ProjectGraphInput.EdgeBinding> edges
) {
   public ProjectGraphInput {
      Objects.requireNonNull(version, "version");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(projectDisplayName, "projectDisplayName");
      if (projectDisplayName.isBlank()) {
         throw new IllegalArgumentException("projectDisplayName must not be blank");
      }

      semanticDeclarations = List.copyOf(Objects.requireNonNull(semanticDeclarations, "semanticDeclarations"));
      loweredDeclarations = List.copyOf(Objects.requireNonNull(loweredDeclarations, "loweredDeclarations"));
      artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
      files = List.copyOf(Objects.requireNonNull(files, "files"));
      edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
   }

   public record ArtifactInput(LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol, LoweredOrigin origin, ArtifactRole role, String qualifiedName) {
      public ArtifactInput {
         Objects.requireNonNull(artifactId, "artifactId");
         ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
         Objects.requireNonNull(origin, "origin");
         Objects.requireNonNull(role, "role");
         Objects.requireNonNull(qualifiedName, "qualifiedName");
         if (qualifiedName.isBlank()) {
            throw new IllegalArgumentException("qualifiedName must not be blank");
         }
      }
   }

   public record EdgeBinding(GraphEdgeKind kind, GraphNodeId source, GraphNodeId target) {
      public EdgeBinding {
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(source, "source");
         Objects.requireNonNull(target, "target");
      }
   }

   public record FileInput(String relativePath, LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol, long byteCount, String sha256Hex) {
      public FileInput {
         Objects.requireNonNull(relativePath, "relativePath");
         Objects.requireNonNull(artifactId, "artifactId");
         ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
         Objects.requireNonNull(sha256Hex, "sha256Hex");
         if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
         }

         if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount must not be negative");
         }

         if (!sha256Hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
         }
      }
   }

   public record LoweredDeclarationInput(LoweredNodeId nodeId, SymbolId sourceSymbol, String displayName, LoweredOrigin origin) {
      public LoweredDeclarationInput {
         Objects.requireNonNull(nodeId, "nodeId");
         Objects.requireNonNull(sourceSymbol, "sourceSymbol");
         Objects.requireNonNull(displayName, "displayName");
         Objects.requireNonNull(origin, "origin");
         if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
         }
      }
   }

   public record SemanticDeclarationInput(SymbolId symbolId, SymbolKind kind, String displayName, AstNodeId sourceNodeId, SourceSpan span) {
      public SemanticDeclarationInput {
         Objects.requireNonNull(symbolId, "symbolId");
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(displayName, "displayName");
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
         if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
         }
      }
   }
}
