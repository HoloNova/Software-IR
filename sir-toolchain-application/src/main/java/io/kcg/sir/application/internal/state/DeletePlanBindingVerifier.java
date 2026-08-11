package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import io.kcg.sir.change.api.ArtifactDeletion;
import io.kcg.sir.change.api.FileDeletion;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.projectgraph.api.GraphNodeId.File;
import io.kcg.sir.projectgraph.api.GraphNodeId.Lowered;
import io.kcg.sir.projectgraph.api.GraphNodeId.ProjectNodeId;
import io.kcg.sir.projectgraph.api.GraphNodeId.Semantic;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.Artifact;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.LoweredDeclaration;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.ProjectFile;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.SemanticDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class DeletePlanBindingVerifier {
   private DeletePlanBindingVerifier() {
   }

   public static DeletePlanBindingVerifier.Result verify(
      List<FileDeletion> fileDeletions,
      List<ArtifactDeletion> artifactDeletions,
      List<GeneratedFile> baseFiles,
      List<GeneratedFile> candidateFiles,
      List<BaselineManifestEntry> b0Manifest,
      List<BaselineManifestEntry> b1Manifest,
      ProjectGraph baseGraph,
      ProjectGraph candidateGraph
   ) {
      Objects.requireNonNull(fileDeletions, "fileDeletions");
      Objects.requireNonNull(artifactDeletions, "artifactDeletions");
      Objects.requireNonNull(baseFiles, "baseFiles");
      Objects.requireNonNull(candidateFiles, "candidateFiles");
      Objects.requireNonNull(b0Manifest, "b0Manifest");
      Objects.requireNonNull(b1Manifest, "b1Manifest");
      Objects.requireNonNull(baseGraph, "baseGraph");
      Objects.requireNonNull(candidateGraph, "candidateGraph");
      Map<String, GeneratedFile> baseMap = new LinkedHashMap<>();

      for (GeneratedFile gf : baseFiles) {
         if (baseMap.put(gf.relativePath(), gf) != null) {
            return new DeletePlanBindingVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-001", "duplicate relativePath in base GeneratedFiles: " + gf.relativePath(), gf.relativePath())
            );
         }
      }

      Map<String, GeneratedFile> candidateMap = new LinkedHashMap<>();

      for (GeneratedFile gf : candidateFiles) {
         if (candidateMap.put(gf.relativePath(), gf) != null) {
            return new DeletePlanBindingVerifier.Result.Failure(
               diag("SIR-APP-CHANGE-BIND-001", "duplicate relativePath in candidate GeneratedFiles: " + gf.relativePath(), gf.relativePath())
            );
         }
      }

      Map<String, BaselineManifestEntry> b0Map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : b0Manifest) {
         b0Map.put(e.relativePath(), e);
      }

      Map<String, BaselineManifestEntry> b1Map = new LinkedHashMap<>();

      for (BaselineManifestEntry e : b1Manifest) {
         b1Map.put(e.relativePath(), e);
      }

      Set<String> deletionPaths = new LinkedHashSet<>();

      for (FileDeletion fd : fileDeletions) {
         deletionPaths.add(fd.relativePath());
         ChangeExecutionDiagnostic fileError = verifyFileDeletionBinding(fd, baseMap, b0Map, baseGraph, candidateMap, b1Map, candidateGraph);
         if (fileError != null) {
            return new DeletePlanBindingVerifier.Result.Failure(fileError);
         }
      }

      Set<LoweredNodeId> deletionArtifactIds = new LinkedHashSet<>();

      for (ArtifactDeletion ad : artifactDeletions) {
         deletionArtifactIds.add(ad.artifactId());
         ChangeExecutionDiagnostic artifactError = verifyArtifactDeletionBinding(ad, baseGraph, candidateGraph, deletionPaths);
         if (artifactError != null) {
            return new DeletePlanBindingVerifier.Result.Failure(artifactError);
         }
      }

      for (FileDeletion fd : fileDeletions) {
         if (!deletionArtifactIds.contains(fd.artifactId())) {
            return new DeletePlanBindingVerifier.Result.Failure(
               diag(
                  "SIR-APP-CHANGE-BIND-006",
                  "FileDeletion's artifactId has no matching ArtifactDeletion: " + fd.relativePath() + " artifact=" + fd.artifactId().value(),
                  fd.relativePath()
               )
            );
         }
      }

      Set<String> baseOnlyNotDeleted = new LinkedHashSet<>();

      for (String rel : baseMap.keySet()) {
         if (!deletionPaths.contains(rel) && !candidateMap.containsKey(rel)) {
            baseOnlyNotDeleted.add(rel);
         }
      }

      if (!baseOnlyNotDeleted.isEmpty()) {
         List<String> sorted = new ArrayList<>(baseOnlyNotDeleted);
         sorted.sort(Comparator.naturalOrder());
         return new DeletePlanBindingVerifier.Result.Failure(
            diag("SIR-APP-CHANGE-BIND-007", "base GeneratedFiles missing from candidate but not in deletion plan: " + sorted)
         );
      }

      Set<String> candidateOnly = new LinkedHashSet<>();

      for (String rel : candidateMap.keySet()) {
         if (!baseMap.containsKey(rel)) {
            candidateOnly.add(rel);
         }
      }

      if (!candidateOnly.isEmpty()) {
         List<String> sorted = new ArrayList<>(candidateOnly);
         sorted.sort(Comparator.naturalOrder());
         return new DeletePlanBindingVerifier.Result.Failure(
            diag("SIR-APP-CHANGE-BIND-007", "candidate GeneratedFiles contain paths absent from base (must be pure deletion): " + sorted)
         );
      } else {
         return new DeletePlanBindingVerifier.Result.Success();
      }
   }

   private static ChangeExecutionDiagnostic verifyFileDeletionBinding(
      FileDeletion fd,
      Map<String, GeneratedFile> baseMap,
      Map<String, BaselineManifestEntry> b0Map,
      ProjectGraph baseGraph,
      Map<String, GeneratedFile> candidateMap,
      Map<String, BaselineManifestEntry> b1Map,
      ProjectGraph candidateGraph
   ) {
      String rel = fd.relativePath();
      GeneratedFile baseGf = baseMap.get(rel);
      if (baseGf == null) {
         return diag("SIR-APP-CHANGE-BIND-002", "base GeneratedFile missing for planned deletion: " + rel, rel);
      }

      if (!baseGf.artifactId().equals(fd.artifactId())) {
         return diag("SIR-APP-CHANGE-BIND-002", "artifactId mismatch for deletion " + rel + ": plan=" + fd.artifactId() + " base=" + baseGf.artifactId(), rel);
      }

      if (!baseGf.symbolId().isEmpty() && baseGf.symbolId().get().equals(fd.ownerSymbol())) {
         byte[] baseBytes = baseGf.content().getBytes(StandardCharsets.UTF_8);
         if (baseBytes.length != fd.baseByteCount()) {
            return diag(
               "SIR-APP-CHANGE-BIND-003", "baseByteCount mismatch for deletion " + rel + ": plan=" + fd.baseByteCount() + " actual=" + baseBytes.length, rel
            );
         } else {
            String baseSha = Sha256.hexDigest(baseBytes);
            if (!baseSha.equals(fd.baseSha256Hex())) {
               return diag("SIR-APP-CHANGE-BIND-003", "baseSha256 mismatch for deletion " + rel, rel);
            } else {
               BaselineManifestEntry b0Entry = b0Map.get(rel);
               if (b0Entry == null) {
                  return diag("SIR-APP-CHANGE-BIND-002", "B0 manifest entry missing for planned deletion: " + rel, rel);
               } else if (!b0Entry.artifactId().equals(fd.artifactId())) {
                  return diag(
                     "SIR-APP-CHANGE-BIND-002", "artifactId mismatch for deletion " + rel + ": plan=" + fd.artifactId() + " b0=" + b0Entry.artifactId(), rel
                  );
               } else if (b0Entry.ownerSymbol().isEmpty() || !b0Entry.ownerSymbol().get().equals(fd.ownerSymbol())) {
                  return diag(
                     "SIR-APP-CHANGE-BIND-002", "ownerSymbol mismatch for deletion " + rel + ": plan=" + fd.ownerSymbol() + " b0=" + b0Entry.ownerSymbol(), rel
                  );
               } else if (b0Entry.byteCount() != fd.baseByteCount()) {
                  return diag(
                     "SIR-APP-CHANGE-BIND-003",
                     "baseByteCount mismatch for deletion " + rel + ": plan=" + fd.baseByteCount() + " b0=" + b0Entry.byteCount(),
                     rel
                  );
               } else if (!b0Entry.sha256Hex().equals(fd.baseSha256Hex())) {
                  return diag(
                     "SIR-APP-CHANGE-BIND-003", "baseSha256 mismatch for deletion " + rel + ": plan=" + fd.baseSha256Hex() + " b0=" + b0Entry.sha256Hex(), rel
                  );
               } else {
                  File fileId = new File(rel);
                  Optional<ProjectGraphNode> baseFileNodeOpt = baseGraph.node(fileId);
                  if (baseFileNodeOpt.isEmpty()) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG ProjectFile node missing for deletion: " + rel, rel);
                  } else if (!(baseFileNodeOpt.get() instanceof ProjectFile)) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG node for " + rel + " is not a ProjectFile", rel);
                  } else {
                     Lowered artifactGraphNodeId = new Lowered(fd.artifactId());
                     Optional<ProjectGraphNode> baseArtifactNodeOpt = baseGraph.node(artifactGraphNodeId);
                     if (baseArtifactNodeOpt.isEmpty()) {
                        return diag(
                           "SIR-APP-CHANGE-BIND-006", "base PSG Artifact node missing for deletion: " + rel + " artifact=" + fd.artifactId().value(), rel
                        );
                     } else if (!(baseArtifactNodeOpt.get() instanceof Artifact baseArtifact)) {
                        return diag("SIR-APP-CHANGE-BIND-006", "base PSG node for " + rel + " is not an Artifact", rel);
                     } else if (!baseArtifact.ownerSymbol().isEmpty() && baseArtifact.ownerSymbol().get().equals(fd.ownerSymbol())) {
                        boolean generatesFile = false;

                        for (ProjectGraphEdge edge : baseGraph.outgoing(artifactGraphNodeId)) {
                           if (edge.kind() == GraphEdgeKind.GENERATES_FILE && edge.target().equals(fileId)) {
                              generatesFile = true;
                              break;
                           }
                        }

                        if (!generatesFile) {
                           return diag(
                              "SIR-APP-CHANGE-BIND-006",
                              "base PSG missing GENERATES_FILE edge for deletion " + rel + " artifact=" + fd.artifactId().value(),
                              rel
                           );
                        }

                        Lowered ownerLoweredId = null;

                        for (ProjectGraphEdge edge : baseGraph.incoming(artifactGraphNodeId)) {
                           if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT && edge.source() instanceof Lowered src) {
                              ownerLoweredId = src;
                              break;
                           }
                        }

                        if (ownerLoweredId == null) {
                           return diag(
                              "SIR-APP-CHANGE-BIND-006",
                              "base PSG missing OWNS_ARTIFACT in-edge from LoweredDeclaration for deletion " + rel + " artifact=" + fd.artifactId().value(),
                              rel
                           );
                        } else {
                           Optional<ProjectGraphNode> baseLoweredOpt = baseGraph.node(ownerLoweredId);
                           if (baseLoweredOpt.isEmpty()) {
                              return diag(
                                 "SIR-APP-CHANGE-BIND-006",
                                 "base PSG LoweredDeclaration node missing for deletion " + rel + " lowered=" + ownerLoweredId.nodeId().value(),
                                 rel
                              );
                           } else if (!(baseLoweredOpt.get() instanceof LoweredDeclaration baseLowered)) {
                              return diag("SIR-APP-CHANGE-BIND-006", "base PSG node for " + rel + " is not a LoweredDeclaration", rel);
                           } else {
                              if (!baseLowered.sourceSymbol().equals(fd.ownerSymbol())) {
                                 return diag(
                                    "SIR-APP-CHANGE-BIND-006",
                                    "base PSG LoweredDeclaration sourceSymbol mismatch for deletion "
                                       + rel
                                       + ": plan="
                                       + fd.ownerSymbol()
                                       + " lowered="
                                       + baseLowered.sourceSymbol(),
                                    rel
                                 );
                              }

                              Semantic semanticId = new Semantic(fd.ownerSymbol());
                              Optional<ProjectGraphNode> baseSemanticOpt = baseGraph.node(semanticId);
                              if (baseSemanticOpt.isEmpty()) {
                                 return diag(
                                    "SIR-APP-CHANGE-BIND-006",
                                    "base PSG SemanticDeclaration node missing for deletion " + rel + " symbol=" + fd.ownerSymbol().value(),
                                    rel
                                 );
                              }

                              if (!(baseSemanticOpt.get() instanceof SemanticDeclaration)) {
                                 return diag("SIR-APP-CHANGE-BIND-006", "base PSG node for " + rel + " is not a SemanticDeclaration", rel);
                              }

                              boolean lowersTo = false;

                              for (ProjectGraphEdge edge : baseGraph.outgoing(semanticId)) {
                                 if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target().equals(ownerLoweredId)) {
                                    lowersTo = true;
                                    break;
                                 }
                              }

                              if (!lowersTo) {
                                 return diag("SIR-APP-CHANGE-BIND-006", "base PSG missing LOWERS_TO edge for deletion " + rel, rel);
                              }

                              boolean declares = false;

                              for (ProjectGraphEdge edge : baseGraph.incoming(semanticId)) {
                                 if (edge.kind() == GraphEdgeKind.DECLARES && edge.source().equals(ProjectNodeId.INSTANCE)) {
                                    declares = true;
                                    break;
                                 }
                              }

                              if (!declares) {
                                 return diag("SIR-APP-CHANGE-BIND-006", "base PSG missing DECLARES edge for deletion " + rel, rel);
                              } else if (candidateMap.containsKey(rel)) {
                                 return diag("SIR-APP-CHANGE-BIND-007", "deleted path present in candidate GeneratedFiles: " + rel, rel);
                              } else if (b1Map.containsKey(rel)) {
                                 return diag("SIR-APP-CHANGE-BIND-007", "deleted path present in B1 manifest: " + rel, rel);
                              } else if (candidateGraph.node(fileId).isPresent()) {
                                 return diag("SIR-APP-CHANGE-BIND-007", "deleted ProjectFile present in candidate PSG: " + rel, rel);
                              } else if (candidateGraph.node(artifactGraphNodeId).isPresent()) {
                                 return diag("SIR-APP-CHANGE-BIND-007", "deleted Artifact present in candidate PSG: " + fd.artifactId().value(), rel);
                              } else if (candidateGraph.node(ownerLoweredId).isPresent()) {
                                 return diag(
                                    "SIR-APP-CHANGE-BIND-007", "deleted LoweredDeclaration present in candidate PSG: " + ownerLoweredId.nodeId().value(), rel
                                 );
                              } else {
                                 return candidateGraph.node(semanticId).isPresent()
                                    ? diag("SIR-APP-CHANGE-BIND-007", "deleted SemanticDeclaration present in candidate PSG: " + fd.ownerSymbol().value(), rel)
                                    : null;
                              }
                           }
                        }
                     } else {
                        return diag(
                           "SIR-APP-CHANGE-BIND-006",
                           "base PSG Artifact ownerSymbol mismatch for deletion "
                              + rel
                              + ": plan="
                              + fd.ownerSymbol()
                              + " artifact="
                              + baseArtifact.ownerSymbol(),
                           rel
                        );
                     }
                  }
               }
            }
         }
      } else {
         return diag("SIR-APP-CHANGE-BIND-002", "ownerSymbol mismatch for deletion " + rel + ": plan=" + fd.ownerSymbol() + " base=" + baseGf.symbolId(), rel);
      }
   }

   private static ChangeExecutionDiagnostic verifyArtifactDeletionBinding(
      ArtifactDeletion ad, ProjectGraph baseGraph, ProjectGraph candidateGraph, Set<String> deletionPaths
   ) {
      LoweredNodeId artifactId = ad.artifactId();
      SymbolId ownerSymbol = ad.ownerSymbol();
      Lowered artifactGraphNodeId = new Lowered(artifactId);
      Optional<ProjectGraphNode> artifactOpt = baseGraph.node(artifactGraphNodeId);
      if (artifactOpt.isEmpty()) {
         return diag("SIR-APP-CHANGE-BIND-006", "base PSG Artifact node missing for artifact deletion: " + artifactId.value());
      } else if (!(artifactOpt.get() instanceof Artifact baseArtifact)) {
         return diag("SIR-APP-CHANGE-BIND-006", "base PSG node is not an Artifact: " + artifactId.value());
      } else {
         if (!baseArtifact.role().equals(ad.role())) {
            return diag(
               "SIR-APP-CHANGE-BIND-006", "role mismatch for artifact deletion " + artifactId.value() + ": plan=" + ad.role() + " base=" + baseArtifact.role()
            );
         }

         if (!baseArtifact.qualifiedName().equals(ad.qualifiedName())) {
            return diag(
               "SIR-APP-CHANGE-BIND-006",
               "qualifiedName mismatch for artifact deletion " + artifactId.value() + ": plan=" + ad.qualifiedName() + " base=" + baseArtifact.qualifiedName()
            );
         }

         if (!baseArtifact.ownerSymbol().isEmpty() && baseArtifact.ownerSymbol().get().equals(ownerSymbol)) {
            if (ad.role() != DeclarationRole.SERVICE && ad.role() != DeclarationRole.CONTROLLER) {
               return diag("SIR-APP-CHANGE-BIND-006", "ArtifactDeletion role must be SERVICE or CONTROLLER: " + ad.role() + " for " + artifactId.value());
            }

            Lowered ownerLoweredId = null;

            for (ProjectGraphEdge edge : baseGraph.incoming(artifactGraphNodeId)) {
               if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT && edge.source() instanceof Lowered src) {
                  ownerLoweredId = src;
                  break;
               }
            }

            if (ownerLoweredId == null) {
               return diag(
                  "SIR-APP-CHANGE-BIND-006", "base PSG missing OWNS_ARTIFACT in-edge from LoweredDeclaration for artifact deletion: " + artifactId.value()
               );
            } else {
               Optional<ProjectGraphNode> loweredOpt = baseGraph.node(ownerLoweredId);
               if (loweredOpt.isEmpty()) {
                  return diag("SIR-APP-CHANGE-BIND-006", "base PSG LoweredDeclaration node missing for artifact deletion: " + ownerLoweredId.nodeId().value());
               } else if (!(loweredOpt.get() instanceof LoweredDeclaration baseLowered)) {
                  return diag("SIR-APP-CHANGE-BIND-006", "base PSG node is not a LoweredDeclaration: " + ownerLoweredId.nodeId().value());
               } else {
                  if (!baseLowered.sourceSymbol().equals(ownerSymbol)) {
                     return diag(
                        "SIR-APP-CHANGE-BIND-006",
                        "LoweredDeclaration sourceSymbol mismatch for artifact deletion "
                           + artifactId.value()
                           + ": plan="
                           + ownerSymbol
                           + " lowered="
                           + baseLowered.sourceSymbol()
                     );
                  }

                  Semantic semanticId = new Semantic(ownerSymbol);
                  Optional<ProjectGraphNode> semanticOpt = baseGraph.node(semanticId);
                  if (semanticOpt.isEmpty()) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG SemanticDeclaration node missing for artifact deletion: " + ownerSymbol.value());
                  }

                  if (!(semanticOpt.get() instanceof SemanticDeclaration)) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG node is not a SemanticDeclaration: " + ownerSymbol.value());
                  }

                  boolean declares = false;

                  for (ProjectGraphEdge edge : baseGraph.incoming(semanticId)) {
                     if (edge.kind() == GraphEdgeKind.DECLARES && edge.source().equals(ProjectNodeId.INSTANCE)) {
                        declares = true;
                        break;
                     }
                  }

                  if (!declares) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG missing DECLARES edge for artifact deletion: " + ownerSymbol.value());
                  }

                  boolean lowersTo = false;

                  for (ProjectGraphEdge edge : baseGraph.outgoing(semanticId)) {
                     if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target().equals(ownerLoweredId)) {
                        lowersTo = true;
                        break;
                     }
                  }

                  if (!lowersTo) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG missing LOWERS_TO edge for artifact deletion: " + ownerSymbol.value());
                  }

                  boolean ownsArtifact = false;

                  for (ProjectGraphEdge edge : baseGraph.outgoing(ownerLoweredId)) {
                     if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT && edge.target().equals(artifactGraphNodeId)) {
                        ownsArtifact = true;
                        break;
                     }
                  }

                  if (!ownsArtifact) {
                     return diag("SIR-APP-CHANGE-BIND-006", "base PSG missing OWNS_ARTIFACT edge for artifact deletion: " + artifactId.value());
                  }

                  Set<String> actualGeneratedPaths = new LinkedHashSet<>();

                  for (ProjectGraphEdge edge : baseGraph.outgoing(artifactGraphNodeId)) {
                     if (edge.kind() == GraphEdgeKind.GENERATES_FILE && edge.target() instanceof File fileTarget) {
                        actualGeneratedPaths.add(fileTarget.relativePath());
                     }
                  }

                  Set<String> plannedPaths = new LinkedHashSet<>();

                  for (FileDeletion fd : ad.fileDeletions()) {
                     plannedPaths.add(fd.relativePath());
                  }

                  if (!actualGeneratedPaths.equals(plannedPaths)) {
                     List<String> actualSorted = new ArrayList<>(actualGeneratedPaths);
                     actualSorted.sort(Comparator.naturalOrder());
                     List<String> plannedSorted = new ArrayList<>(plannedPaths);
                     plannedSorted.sort(Comparator.naturalOrder());
                     return diag(
                        "SIR-APP-CHANGE-BIND-006",
                        "GENERATES_FILE set mismatch for artifact deletion " + artifactId.value() + ": actual=" + actualSorted + " planned=" + plannedSorted
                     );
                  }

                  for (FileDeletion fd : ad.fileDeletions()) {
                     if (!deletionPaths.contains(fd.relativePath())) {
                        return diag(
                           "SIR-APP-CHANGE-BIND-006",
                           "ArtifactDeletion file not in top-level fileDeletions: " + fd.relativePath() + " artifact=" + artifactId.value(),
                           fd.relativePath()
                        );
                     }
                  }

                  if (candidateGraph.node(artifactGraphNodeId).isPresent()) {
                     return diag("SIR-APP-CHANGE-BIND-007", "deleted Artifact present in candidate PSG: " + artifactId.value());
                  } else if (candidateGraph.node(ownerLoweredId).isPresent()) {
                     return diag("SIR-APP-CHANGE-BIND-007", "deleted LoweredDeclaration present in candidate PSG: " + ownerLoweredId.nodeId().value());
                  } else {
                     return candidateGraph.node(semanticId).isPresent()
                        ? diag("SIR-APP-CHANGE-BIND-007", "deleted SemanticDeclaration present in candidate PSG: " + ownerSymbol.value())
                        : null;
                  }
               }
            }
         } else {
            return diag(
               "SIR-APP-CHANGE-BIND-006",
               "ownerSymbol mismatch for artifact deletion " + artifactId.value() + ": plan=" + ownerSymbol + " base=" + baseArtifact.ownerSymbol()
            );
         }
      }
   }

   private static ChangeExecutionDiagnostic diag(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.PROTECT, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   private static ChangeExecutionDiagnostic diag(String code, String message, String rel) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.PROTECT, ExecutionSeverity.ERROR, message, Optional.of(rel));
   }

   public sealed interface Result permits DeletePlanBindingVerifier.Result.Success, DeletePlanBindingVerifier.Result.Failure {
      record Failure(ChangeExecutionDiagnostic error) implements DeletePlanBindingVerifier.Result {
         public Failure {
            Objects.requireNonNull(error, "error");
         }
      }

      record Success() implements DeletePlanBindingVerifier.Result {
      }
   }
}
