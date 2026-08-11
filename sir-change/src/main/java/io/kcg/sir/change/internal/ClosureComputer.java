package io.kcg.sir.change.internal;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.projectgraph.api.GraphNodeId.Lowered;
import io.kcg.sir.projectgraph.api.GraphNodeId.ProjectNodeId;
import io.kcg.sir.projectgraph.api.GraphNodeId.Semantic;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.Artifact;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.LoweredDeclaration;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.ProjectFile;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.SemanticDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ClosureComputer {
   private ClosureComputer() {
   }

   public static ClosureComputer.ClosureResult compute(ProjectGraph graph, SymbolId targetSymbol) {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(targetSymbol, "targetSymbol");
      Semantic semanticId = new Semantic(targetSymbol);
      Optional<ProjectGraphNode> semanticNodeOpt = graph.node(semanticId);
      if (!semanticNodeOpt.isEmpty() && semanticNodeOpt.get() instanceof SemanticDeclaration) {
         boolean declaresFound = false;

         for (ProjectGraphEdge edge : graph.outgoing(ProjectNodeId.INSTANCE)) {
            if (edge.kind() == GraphEdgeKind.DECLARES && edge.target().equals(semanticId)) {
               declaresFound = true;
               break;
            }
         }

         if (!declaresFound) {
            return new ClosureComputer.ClosureResult.MissingTrace(
               "missing DECLARES edge from Project root to target SemanticDeclaration: " + targetSymbol.value()
            );
         }

         LoweredNodeId loweredId = null;

         for (ProjectGraphEdge edge : graph.outgoing(semanticId)) {
            if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target() instanceof Lowered l) {
               loweredId = l.nodeId();
               break;
            }
         }

         if (loweredId == null) {
            return new ClosureComputer.ClosureResult.MissingTrace("missing typed LOWERS_TO edge from target SemanticDeclaration: " + targetSymbol.value());
         }

         Lowered loweredGraphNodeId = new Lowered(loweredId);
         List<Artifact> serviceControllerArtifacts = new ArrayList<>();
         boolean anyOwnsArtifactEdge = false;

         for (ProjectGraphEdge edge : graph.outgoing(loweredGraphNodeId)) {
            if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT) {
               anyOwnsArtifactEdge = true;
               Optional<ProjectGraphNode> artifactOpt = graph.node(edge.target());
               if (artifactOpt.isEmpty() || !(artifactOpt.get() instanceof Artifact artifact)) {
                  return new ClosureComputer.ClosureResult.MissingTrace("OWNS_ARTIFACT edge target is not an Artifact node for lowered " + loweredId.value());
               }

               if (artifact.role() instanceof DeclarationRole dr && (dr == DeclarationRole.SERVICE || dr == DeclarationRole.CONTROLLER)) {
                  serviceControllerArtifacts.add(artifact);
               }
            }
         }

         if (!anyOwnsArtifactEdge) {
            return new ClosureComputer.ClosureResult.MissingTrace("missing OWNS_ARTIFACT edge from LoweredDeclaration of target: " + targetSymbol.value());
         }

         List<Artifact> orderedArtifacts = new ArrayList<>(serviceControllerArtifacts);
         orderedArtifacts.sort((a, b) -> a.artifactId().value().compareTo(b.artifactId().value()));
         List<ProjectFile> fileList = new ArrayList<>();
         LinkedHashSet<String> seenPaths = new LinkedHashSet<>();

         for (Artifact artifact : orderedArtifacts) {
            Lowered artifactNodeId = new Lowered(artifact.artifactId());
            boolean anyGeneratesFileEdge = false;

            for (ProjectGraphEdge edge : graph.outgoing(artifactNodeId)) {
               if (edge.kind() == GraphEdgeKind.GENERATES_FILE) {
                  anyGeneratesFileEdge = true;
                  Optional<ProjectGraphNode> fileOpt = graph.node(edge.target());
                  if (fileOpt.isEmpty() || !(fileOpt.get() instanceof ProjectFile file)) {
                     return new ClosureComputer.ClosureResult.MissingTrace(
                        "GENERATES_FILE edge target is not a ProjectFile node for artifact " + artifact.artifactId().value()
                     );
                  }

                  if (seenPaths.add(file.id().relativePath())) {
                     fileList.add(file);
                  }
               }
            }

            if (!anyGeneratesFileEdge) {
               return new ClosureComputer.ClosureResult.MissingTrace(
                  "missing GENERATES_FILE edge from SERVICE/CONTROLLER artifact " + artifact.artifactId().value()
               );
            }
         }

         fileList.sort((a, b) -> a.id().relativePath().compareTo(b.id().relativePath()));
         ClosureComputer.Closure closure = new ClosureComputer.Closure(
            List.of(semanticNodeOpt.get().id().canonicalKey()), List.copyOf(orderedArtifacts), List.copyOf(fileList)
         );
         return new ClosureComputer.ClosureResult.Success(closure);
      } else {
         return new ClosureComputer.ClosureResult.MissingTrace("target SemanticDeclaration not found or wrong node type: " + targetSymbol.value());
      }
   }

   public static ClosureComputer.RemovalClosureResult computeRemovalClosure(ProjectGraph graph, SymbolId targetSymbol) {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(targetSymbol, "targetSymbol");
      Semantic semanticId = new Semantic(targetSymbol);
      Optional<ProjectGraphNode> semanticNodeOpt = graph.node(semanticId);
      if (!semanticNodeOpt.isEmpty() && semanticNodeOpt.get() instanceof SemanticDeclaration semanticDecl) {
         boolean var24 = false;

         for (ProjectGraphEdge edge : graph.outgoing(ProjectNodeId.INSTANCE)) {
            if (edge.kind() == GraphEdgeKind.DECLARES && edge.target().equals(semanticId)) {
               var24 = true;
               break;
            }
         }

         if (!var24) {
            return new ClosureComputer.RemovalClosureResult.MissingTrace(
               "missing DECLARES edge from Project root to target SemanticDeclaration: " + targetSymbol.value()
            );
         }

         LoweredNodeId loweredId = null;

         for (ProjectGraphEdge edge : graph.outgoing(semanticId)) {
            if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target() instanceof Lowered l) {
               loweredId = l.nodeId();
               break;
            }
         }

         if (loweredId == null) {
            return new ClosureComputer.RemovalClosureResult.MissingTrace(
               "missing typed LOWERS_TO edge from target SemanticDeclaration: " + targetSymbol.value()
            );
         }

         Lowered loweredGraphNodeId = new Lowered(loweredId);
         Optional<ProjectGraphNode> loweredNodeOpt = graph.node(loweredGraphNodeId);
         if (!loweredNodeOpt.isEmpty() && loweredNodeOpt.get() instanceof LoweredDeclaration loweredDecl) {
            ArrayList var31 = new ArrayList();
            boolean anyOwnsArtifactEdge = false;

            for (ProjectGraphEdge edge : graph.outgoing(loweredGraphNodeId)) {
               if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT) {
                  anyOwnsArtifactEdge = true;
                  Optional<ProjectGraphNode> artifactOpt = graph.node(edge.target());
                  if (artifactOpt.isEmpty() || !(artifactOpt.get() instanceof Artifact artifact)) {
                     return new ClosureComputer.RemovalClosureResult.MissingTrace(
                        "OWNS_ARTIFACT edge target is not an Artifact node for lowered " + loweredId.value()
                     );
                  }

                  if (!(artifact.role() instanceof DeclarationRole dr)) {
                     return new ClosureComputer.RemovalClosureResult.MissingTrace(
                        "OWNS_ARTIFACT edge target has non-DeclarationRole for lowered " + loweredId.value() + ": " + artifact.role()
                     );
                  }

                  if (dr != DeclarationRole.SERVICE && dr != DeclarationRole.CONTROLLER) {
                     return new ClosureComputer.RemovalClosureResult.MissingTrace(
                        "removal closure expects only SERVICE/CONTROLLER artifacts; found " + dr + " for lowered " + loweredId.value()
                     );
                  }

                  var31.add(artifact);
               }
            }

            if (!anyOwnsArtifactEdge) {
               return new ClosureComputer.RemovalClosureResult.MissingTrace(
                  "missing OWNS_ARTIFACT edge from LoweredDeclaration of target: " + targetSymbol.value()
               );
            }

            List<Artifact> orderedArtifacts = new ArrayList<>(var31);
            orderedArtifacts.sort((a, b) -> a.artifactId().value().compareTo(b.artifactId().value()));
            List<ProjectFile> fileList = new ArrayList<>();
            LinkedHashSet<String> seenPaths = new LinkedHashSet<>();

            for (Artifact artifact : orderedArtifacts) {
               Lowered artifactNodeId = new Lowered(artifact.artifactId());
               boolean anyGeneratesFileEdge = false;

               for (ProjectGraphEdge edge : graph.outgoing(artifactNodeId)) {
                  if (edge.kind() == GraphEdgeKind.GENERATES_FILE) {
                     anyGeneratesFileEdge = true;
                     Optional<ProjectGraphNode> fileOpt = graph.node(edge.target());
                     if (fileOpt.isEmpty() || !(fileOpt.get() instanceof ProjectFile file)) {
                        return new ClosureComputer.RemovalClosureResult.MissingTrace(
                           "GENERATES_FILE edge target is not a ProjectFile node for artifact " + artifact.artifactId().value()
                        );
                     }

                     if (seenPaths.add(file.id().relativePath())) {
                        fileList.add(file);
                     }
                  }
               }

               if (!anyGeneratesFileEdge) {
                  return new ClosureComputer.RemovalClosureResult.MissingTrace(
                     "missing GENERATES_FILE edge from SERVICE/CONTROLLER artifact " + artifact.artifactId().value()
                  );
               }
            }

            fileList.sort((a, b) -> a.id().relativePath().compareTo(b.id().relativePath()));
            if (!orderedArtifacts.isEmpty() && !fileList.isEmpty()) {
               ClosureComputer.RemovalClosure closure = new ClosureComputer.RemovalClosure(
                  semanticDecl, loweredDecl, List.copyOf(orderedArtifacts), List.copyOf(fileList)
               );
               return new ClosureComputer.RemovalClosureResult.Success(closure);
            } else {
               return new ClosureComputer.RemovalClosureResult.MissingTrace(
                  "removal closure has empty SERVICE/CONTROLLER artifact or file set for target: " + targetSymbol.value()
               );
            }
         } else {
            return new ClosureComputer.RemovalClosureResult.MissingTrace(
               "LOWERS_TO edge target is not a LoweredDeclaration node for target " + targetSymbol.value()
            );
         }
      } else {
         return new ClosureComputer.RemovalClosureResult.MissingTrace("target SemanticDeclaration not found or wrong node type: " + targetSymbol.value());
      }
   }

   public static ClosureComputer.InputClosureResult computeInputClosure(ProjectGraph graph, SymbolId targetSymbol) {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(targetSymbol, "targetSymbol");
      Semantic semanticId = new Semantic(targetSymbol);
      Optional<ProjectGraphNode> semanticNodeOpt = graph.node(semanticId);
      if (!semanticNodeOpt.isEmpty() && semanticNodeOpt.get() instanceof SemanticDeclaration semanticDecl) {
         boolean var24 = false;

         for (ProjectGraphEdge edge : graph.outgoing(ProjectNodeId.INSTANCE)) {
            if (edge.kind() == GraphEdgeKind.DECLARES && edge.target().equals(semanticId)) {
               var24 = true;
               break;
            }
         }

         if (!var24) {
            return new ClosureComputer.InputClosureResult.MissingTrace(
               "missing DECLARES edge from Project root to target SemanticDeclaration: " + targetSymbol.value()
            );
         }

         LoweredNodeId loweredId = null;

         for (ProjectGraphEdge edge : graph.outgoing(semanticId)) {
            if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target() instanceof Lowered l) {
               loweredId = l.nodeId();
               break;
            }
         }

         if (loweredId == null) {
            return new ClosureComputer.InputClosureResult.MissingTrace("missing typed LOWERS_TO edge from target SemanticDeclaration: " + targetSymbol.value());
         }

         Lowered loweredGraphNodeId = new Lowered(loweredId);
         Optional<ProjectGraphNode> loweredNodeOpt = graph.node(loweredGraphNodeId);
         if (!loweredNodeOpt.isEmpty() && loweredNodeOpt.get() instanceof LoweredDeclaration loweredDecl) {
            ArrayList var31 = new ArrayList();
            boolean anyOwnsArtifactEdge = false;

            for (ProjectGraphEdge edge : graph.outgoing(loweredGraphNodeId)) {
               if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT) {
                  anyOwnsArtifactEdge = true;
                  Optional<ProjectGraphNode> artifactOpt = graph.node(edge.target());
                  if (artifactOpt.isEmpty() || !(artifactOpt.get() instanceof Artifact artifact)) {
                     return new ClosureComputer.InputClosureResult.MissingTrace(
                        "OWNS_ARTIFACT edge target is not an Artifact node for lowered " + loweredId.value()
                     );
                  }

                  if (!(artifact.role() instanceof DeclarationRole dr)) {
                     return new ClosureComputer.InputClosureResult.MissingTrace(
                        "OWNS_ARTIFACT edge target has non-DeclarationRole for lowered " + loweredId.value() + ": " + artifact.role()
                     );
                  }

                  if (dr != DeclarationRole.REQUEST_DTO) {
                     return new ClosureComputer.InputClosureResult.MissingTrace(
                        "input closure expects only REQUEST_DTO artifacts; found " + dr + " for lowered " + loweredId.value()
                     );
                  }

                  var31.add(artifact);
               }
            }

            if (!anyOwnsArtifactEdge) {
               return new ClosureComputer.InputClosureResult.MissingTrace(
                  "missing OWNS_ARTIFACT edge from LoweredDeclaration of target: " + targetSymbol.value()
               );
            }

            if (var31.isEmpty()) {
               return new ClosureComputer.InputClosureResult.MissingTrace(
                  "input closure has empty REQUEST_DTO artifact set for target: " + targetSymbol.value()
               );
            }

            List<Artifact> orderedArtifacts = new ArrayList<>(var31);
            orderedArtifacts.sort((a, b) -> a.artifactId().value().compareTo(b.artifactId().value()));
            List<ProjectFile> fileList = new ArrayList<>();
            LinkedHashSet<String> seenPaths = new LinkedHashSet<>();

            for (Artifact artifact : orderedArtifacts) {
               Lowered artifactNodeId = new Lowered(artifact.artifactId());
               boolean anyGeneratesFileEdge = false;

               for (ProjectGraphEdge edge : graph.outgoing(artifactNodeId)) {
                  if (edge.kind() == GraphEdgeKind.GENERATES_FILE) {
                     anyGeneratesFileEdge = true;
                     Optional<ProjectGraphNode> fileOpt = graph.node(edge.target());
                     if (fileOpt.isEmpty() || !(fileOpt.get() instanceof ProjectFile file)) {
                        return new ClosureComputer.InputClosureResult.MissingTrace(
                           "GENERATES_FILE edge target is not a ProjectFile node for artifact " + artifact.artifactId().value()
                        );
                     }

                     if (seenPaths.add(file.id().relativePath())) {
                        fileList.add(file);
                     }
                  }
               }

               if (!anyGeneratesFileEdge) {
                  return new ClosureComputer.InputClosureResult.MissingTrace(
                     "missing GENERATES_FILE edge from REQUEST_DTO artifact " + artifact.artifactId().value()
                  );
               }
            }

            fileList.sort((a, b) -> a.id().relativePath().compareTo(b.id().relativePath()));
            if (fileList.isEmpty()) {
               return new ClosureComputer.InputClosureResult.MissingTrace("input closure has empty GENERATES_FILE set for target: " + targetSymbol.value());
            }

            ClosureComputer.InputClosure closure = new ClosureComputer.InputClosure(
               semanticDecl, loweredDecl, List.copyOf(orderedArtifacts), List.copyOf(fileList)
            );
            return new ClosureComputer.InputClosureResult.Success(closure);
         } else {
            return new ClosureComputer.InputClosureResult.MissingTrace(
               "LOWERS_TO edge target is not a LoweredDeclaration node for target " + targetSymbol.value()
            );
         }
      } else {
         return new ClosureComputer.InputClosureResult.MissingTrace("target SemanticDeclaration not found or wrong node type: " + targetSymbol.value());
      }
   }

   public static ClosureComputer.ExposureClosureResult computeExposureClosure(ProjectGraph graph, SymbolId targetSymbol) {
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(targetSymbol, "targetSymbol");
      Semantic semanticId = new Semantic(targetSymbol);
      Optional<ProjectGraphNode> semanticNodeOpt = graph.node(semanticId);
      if (!semanticNodeOpt.isEmpty() && semanticNodeOpt.get() instanceof SemanticDeclaration semanticDecl) {
         boolean var19 = false;

         for (ProjectGraphEdge edge : graph.outgoing(ProjectNodeId.INSTANCE)) {
            if (edge.kind() == GraphEdgeKind.DECLARES && edge.target().equals(semanticId)) {
               var19 = true;
               break;
            }
         }

         if (!var19) {
            return new ClosureComputer.ExposureClosureResult.MissingTrace(
               "missing DECLARES edge from Project root to target SemanticDeclaration: " + targetSymbol.value()
            );
         }

         LoweredNodeId loweredId = null;

         for (ProjectGraphEdge edge : graph.outgoing(semanticId)) {
            if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target() instanceof Lowered l) {
               loweredId = l.nodeId();
               break;
            }
         }

         if (loweredId == null) {
            return new ClosureComputer.ExposureClosureResult.MissingTrace(
               "missing typed LOWERS_TO edge from target SemanticDeclaration: " + targetSymbol.value()
            );
         }

         Lowered loweredGraphNodeId = new Lowered(loweredId);
         Optional<ProjectGraphNode> loweredNodeOpt = graph.node(loweredGraphNodeId);
         if (!loweredNodeOpt.isEmpty() && loweredNodeOpt.get() instanceof LoweredDeclaration loweredDecl) {
            Artifact var26 = null;
            Artifact controllerArtifact = null;
            boolean anyOwnsArtifactEdge = false;

            for (ProjectGraphEdge edge : graph.outgoing(loweredGraphNodeId)) {
               if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT) {
                  anyOwnsArtifactEdge = true;
                  Optional<ProjectGraphNode> artifactOpt = graph.node(edge.target());
                  if (artifactOpt.isEmpty() || !(artifactOpt.get() instanceof Artifact artifact)) {
                     return new ClosureComputer.ExposureClosureResult.MissingTrace(
                        "OWNS_ARTIFACT edge target is not an Artifact node for lowered " + loweredId.value()
                     );
                  }

                  if (!(artifact.role() instanceof DeclarationRole dr)) {
                     return new ClosureComputer.ExposureClosureResult.MissingTrace(
                        "OWNS_ARTIFACT edge target has non-DeclarationRole for lowered " + loweredId.value() + ": " + artifact.role()
                     );
                  }

                  if (dr == DeclarationRole.SERVICE) {
                     if (var26 != null) {
                        return new ClosureComputer.ExposureClosureResult.MissingTrace(
                           "exposure closure found duplicate SERVICE artifact for lowered " + loweredId.value()
                        );
                     }

                     var26 = artifact;
                  } else {
                     if (dr != DeclarationRole.CONTROLLER) {
                        return new ClosureComputer.ExposureClosureResult.MissingTrace(
                           "exposure closure expects exactly one SERVICE + one CONTROLLER; found " + dr + " for lowered " + loweredId.value()
                        );
                     }

                     if (controllerArtifact != null) {
                        return new ClosureComputer.ExposureClosureResult.MissingTrace(
                           "exposure closure found duplicate CONTROLLER artifact for lowered " + loweredId.value()
                        );
                     }

                     controllerArtifact = artifact;
                  }
               }
            }

            if (!anyOwnsArtifactEdge) {
               return new ClosureComputer.ExposureClosureResult.MissingTrace(
                  "missing OWNS_ARTIFACT edge from LoweredDeclaration of target: " + targetSymbol.value()
               );
            }

            if (var26 == null) {
               return new ClosureComputer.ExposureClosureResult.MissingTrace("exposure closure missing SERVICE artifact for lowered " + loweredId.value());
            }

            if (controllerArtifact == null) {
               return new ClosureComputer.ExposureClosureResult.MissingTrace("exposure closure missing CONTROLLER artifact for lowered " + loweredId.value());
            }

            List<ProjectFile> serviceFiles = collectExposureFiles(graph, var26);
            if (serviceFiles == null) {
               return new ClosureComputer.ExposureClosureResult.MissingTrace("missing GENERATES_FILE edge from SERVICE artifact " + var26.artifactId().value());
            }

            List<ProjectFile> controllerFiles = collectExposureFiles(graph, controllerArtifact);
            if (controllerFiles == null) {
               return new ClosureComputer.ExposureClosureResult.MissingTrace(
                  "missing GENERATES_FILE edge from CONTROLLER artifact " + controllerArtifact.artifactId().value()
               );
            }

            ClosureComputer.ExposureClosure closure = new ClosureComputer.ExposureClosure(
               semanticDecl, loweredDecl, var26, List.copyOf(serviceFiles), controllerArtifact, List.copyOf(controllerFiles)
            );
            return new ClosureComputer.ExposureClosureResult.Success(closure);
         } else {
            return new ClosureComputer.ExposureClosureResult.MissingTrace(
               "LOWERS_TO edge target is not a LoweredDeclaration node for target " + targetSymbol.value()
            );
         }
      } else {
         return new ClosureComputer.ExposureClosureResult.MissingTrace("target SemanticDeclaration not found or wrong node type: " + targetSymbol.value());
      }
   }

   private static List<ProjectFile> collectExposureFiles(ProjectGraph graph, Artifact artifact) {
      Lowered artifactNodeId = new Lowered(artifact.artifactId());
      List<ProjectFile> files = new ArrayList<>();
      LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
      boolean anyGeneratesFileEdge = false;

      for (ProjectGraphEdge edge : graph.outgoing(artifactNodeId)) {
         if (edge.kind() == GraphEdgeKind.GENERATES_FILE) {
            anyGeneratesFileEdge = true;
            Optional<ProjectGraphNode> fileOpt = graph.node(edge.target());
            if (fileOpt.isEmpty() || !(fileOpt.get() instanceof ProjectFile file)) {
               return null;
            }

            if (seenPaths.add(file.id().relativePath())) {
               files.add(file);
            }
         }
      }

      if (anyGeneratesFileEdge && !files.isEmpty()) {
         files.sort((a, b) -> a.id().relativePath().compareTo(b.id().relativePath()));
         return files;
      } else {
         return null;
      }
   }

   public record Closure(List<String> semanticKeys, List<Artifact> artifacts, List<ProjectFile> files) {
      public Closure {
         semanticKeys = List.copyOf(Objects.requireNonNull(semanticKeys, "semanticKeys"));
         artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
         files = List.copyOf(Objects.requireNonNull(files, "files"));
      }
   }

   public sealed interface ClosureResult permits ClosureComputer.ClosureResult.Success, ClosureComputer.ClosureResult.MissingTrace {
      record MissingTrace(String detail) implements ClosureComputer.ClosureResult {
         public MissingTrace {
            Objects.requireNonNull(detail, "detail");
         }
      }

      record Success(ClosureComputer.Closure closure) implements ClosureComputer.ClosureResult {
         public Success {
            Objects.requireNonNull(closure, "closure");
         }
      }
   }

   public record ExposureClosure(
      SemanticDeclaration semanticDeclaration,
      LoweredDeclaration loweredDeclaration,
      Artifact serviceArtifact,
      List<ProjectFile> serviceFiles,
      Artifact controllerArtifact,
      List<ProjectFile> controllerFiles
   ) {
      public ExposureClosure {
         Objects.requireNonNull(semanticDeclaration, "semanticDeclaration");
         Objects.requireNonNull(loweredDeclaration, "loweredDeclaration");
         Objects.requireNonNull(serviceArtifact, "serviceArtifact");
         serviceFiles = List.copyOf(Objects.requireNonNull(serviceFiles, "serviceFiles"));
         Objects.requireNonNull(controllerArtifact, "controllerArtifact");
         controllerFiles = List.copyOf(Objects.requireNonNull(controllerFiles, "controllerFiles"));
      }
   }

   public sealed interface ExposureClosureResult permits ClosureComputer.ExposureClosureResult.Success, ClosureComputer.ExposureClosureResult.MissingTrace {
      record MissingTrace(String detail) implements ClosureComputer.ExposureClosureResult {
         public MissingTrace {
            Objects.requireNonNull(detail, "detail");
         }
      }

      record Success(ClosureComputer.ExposureClosure closure) implements ClosureComputer.ExposureClosureResult {
         public Success {
            Objects.requireNonNull(closure, "closure");
         }
      }
   }

   public record InputClosure(SemanticDeclaration semanticDeclaration, LoweredDeclaration loweredDeclaration, List<Artifact> artifacts, List<ProjectFile> files) {
      public InputClosure {
         Objects.requireNonNull(semanticDeclaration, "semanticDeclaration");
         Objects.requireNonNull(loweredDeclaration, "loweredDeclaration");
         artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
         files = List.copyOf(Objects.requireNonNull(files, "files"));
      }
   }

   public sealed interface InputClosureResult permits ClosureComputer.InputClosureResult.Success, ClosureComputer.InputClosureResult.MissingTrace {
      record MissingTrace(String detail) implements ClosureComputer.InputClosureResult {
         public MissingTrace {
            Objects.requireNonNull(detail, "detail");
         }
      }

      record Success(ClosureComputer.InputClosure closure) implements ClosureComputer.InputClosureResult {
         public Success {
            Objects.requireNonNull(closure, "closure");
         }
      }
   }

   public record RemovalClosure(
      SemanticDeclaration semanticDeclaration, LoweredDeclaration loweredDeclaration, List<Artifact> artifacts, List<ProjectFile> files
   ) {
      public RemovalClosure {
         Objects.requireNonNull(semanticDeclaration, "semanticDeclaration");
         Objects.requireNonNull(loweredDeclaration, "loweredDeclaration");
         artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
         files = List.copyOf(Objects.requireNonNull(files, "files"));
      }
   }

   public sealed interface RemovalClosureResult permits ClosureComputer.RemovalClosureResult.Success, ClosureComputer.RemovalClosureResult.MissingTrace {
      record MissingTrace(String detail) implements ClosureComputer.RemovalClosureResult {
         public MissingTrace {
            Objects.requireNonNull(detail, "detail");
         }
      }

      record Success(ClosureComputer.RemovalClosure closure) implements ClosureComputer.RemovalClosureResult {
         public Success {
            Objects.requireNonNull(closure, "closure");
         }
      }
   }
}
