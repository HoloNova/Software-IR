package io.kcg.sir.projectgraph.api;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSpan;
import java.util.Objects;
import java.util.Optional;

public sealed interface GraphProvenance
   permits GraphProvenance.ProjectProvenance,
   GraphProvenance.SemanticProvenance,
   GraphProvenance.LoweredProvenance,
   GraphProvenance.ArtifactProvenance,
   GraphProvenance.FileProvenance {
   SourceId sourceId();

   record ArtifactProvenance(SourceId sourceId, LoweredOrigin origin, Optional<SymbolId> ownerSymbol, ArtifactRole role, String qualifiedName)
      implements GraphProvenance {
      public ArtifactProvenance {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(origin, "origin");
         ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
         Objects.requireNonNull(role, "role");
         Objects.requireNonNull(qualifiedName, "qualifiedName");
         if (qualifiedName.isBlank()) {
            throw new IllegalArgumentException("qualifiedName must not be blank");
         }
      }
   }

   record FileProvenance(SourceId sourceId, LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol, long byteCount, String sha256Hex)
      implements GraphProvenance {
      public FileProvenance {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(artifactId, "artifactId");
         ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
         Objects.requireNonNull(sha256Hex, "sha256Hex");
         if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount must not be negative");
         }

         if (!sha256Hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
         }
      }
   }

   record LoweredProvenance(SourceId sourceId, LoweredOrigin origin) implements GraphProvenance {
      public LoweredProvenance {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(origin, "origin");
      }
   }

   record ProjectProvenance(SourceId sourceId) implements GraphProvenance {
      public ProjectProvenance {
         Objects.requireNonNull(sourceId, "sourceId");
      }
   }

   record SemanticProvenance(SourceId sourceId, AstNodeId sourceNodeId, SourceSpan span) implements GraphProvenance {
      public SemanticProvenance {
         Objects.requireNonNull(sourceId, "sourceId");
         Objects.requireNonNull(sourceNodeId, "sourceNodeId");
         Objects.requireNonNull(span, "span");
      }
   }
}
