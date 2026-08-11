package io.kcg.sir.projectgraph.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import java.util.Objects;
import java.util.Optional;

public sealed interface ProjectGraphNode
   permits ProjectGraphNode.Project,
   ProjectGraphNode.SemanticDeclaration,
   ProjectGraphNode.LoweredDeclaration,
   ProjectGraphNode.Artifact,
   ProjectGraphNode.ProjectFile {
   GraphNodeId id();

   GraphProvenance provenance();

   String displayName();

   record Artifact(GraphNodeId.Lowered id, GraphProvenance.ArtifactProvenance provenance, ArtifactRole role, String qualifiedName) implements ProjectGraphNode {
      public Artifact {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(provenance, "provenance");
         Objects.requireNonNull(role, "role");
         Objects.requireNonNull(qualifiedName, "qualifiedName");
         if (qualifiedName.isBlank()) {
            throw new IllegalArgumentException("qualifiedName must not be blank");
         }
      }

      public Optional<SymbolId> ownerSymbol() {
         return this.provenance().ownerSymbol();
      }

      public LoweredNodeId artifactId() {
         return this.id().nodeId();
      }

      @Override
      public String displayName() {
         return this.qualifiedName;
      }
   }

   record LoweredDeclaration(GraphNodeId.Lowered id, GraphProvenance.LoweredProvenance provenance, SymbolId sourceSymbol, String displayName)
      implements ProjectGraphNode {
      public LoweredDeclaration {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(provenance, "provenance");
         Objects.requireNonNull(sourceSymbol, "sourceSymbol");
         Objects.requireNonNull(displayName, "displayName");
         if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
         }
      }
   }

   record Project(GraphNodeId.ProjectNodeId id, GraphProvenance.ProjectProvenance provenance, String displayName) implements ProjectGraphNode {
      public Project {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(provenance, "provenance");
         Objects.requireNonNull(displayName, "displayName");
         if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
         }
      }
   }

   record ProjectFile(GraphNodeId.File id, GraphProvenance.FileProvenance provenance) implements ProjectGraphNode {
      public ProjectFile {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(provenance, "provenance");
      }

      @Override
      public String displayName() {
         return this.id.relativePath();
      }
   }

   record SemanticDeclaration(GraphNodeId.Semantic id, GraphProvenance.SemanticProvenance provenance, SymbolKind kind, String displayName)
      implements ProjectGraphNode {
      public SemanticDeclaration {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(provenance, "provenance");
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(displayName, "displayName");
         if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
         }
      }
   }
}
