package io.kcg.sir.projectgraph.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ProjectGraphValidator {
   public List<ProjectGraphDiagnostic> validateInput(ProjectGraphInput input) {
      Objects.requireNonNull(input, "input");
      List<ProjectGraphDiagnostic> diagnostics = new ArrayList<>();

      for (ProjectGraphInput.FileInput fi : input.files()) {
         String violation = GraphNodeId.PathCheck.validate(fi.relativePath());
         if (violation != null) {
            diagnostics.add(
               ProjectGraphDiagnostic.error("SIR-GRAPH-PATH-001", "illegal ProjectFile relativePath (" + violation + "): " + fi.relativePath(), null, null)
            );
         }
      }

      return diagnostics;
   }

   public List<ProjectGraphDiagnostic> validate(GraphVersion version, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
      List<ProjectGraphDiagnostic> diagnostics = new ArrayList<>();
      if (version == null) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-VERSION-001", "graph version must not be null"));
         return diagnostics;
      }

      if (version != GraphVersion.V0_1) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-VERSION-002", "unsupported graph version: " + version));
      }

      Map<GraphNodeId, ProjectGraphNode> nodeById = new LinkedHashMap<>();

      for (ProjectGraphNode node : nodes) {
         ProjectGraphNode previous = nodeById.put(node.id(), node);
         if (previous != null) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-NODE-001", "duplicate node id: " + node.id().canonicalKey(), node.id(), null));
         }

         validateProvenanceKind(node, diagnostics);
      }

      Set<GraphEdgeId> reportedDuplicateEdge = new HashSet<>();
      Map<GraphEdgeId, ProjectGraphEdge> edgeById = new LinkedHashMap<>();

      for (ProjectGraphEdge edge : edges) {
         GraphEdgeId id = edge.id();
         ProjectGraphEdge previous = edgeById.put(id, edge);
         if (previous != null && reportedDuplicateEdge.add(id)) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-EDGE-001", "duplicate edge id: " + id.canonicalKey(), null, id));
         }
      }

      List<ProjectGraphEdge> uniqueEdges = new ArrayList<>(edgeById.values());

      for (ProjectGraphEdge edge : uniqueEdges) {
         GraphEdgeId id = edge.id();
         if (id.source().equals(id.target())) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-EDGE-003", "self-loop edge: " + id.canonicalKey(), null, id));
         }
      }

      for (ProjectGraphEdge edge : uniqueEdges) {
         GraphEdgeId id = edge.id();
         if (!nodeById.containsKey(id.source())) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-EDGE-002", "dangling edge source: " + id.canonicalKey(), null, id));
         }

         if (!nodeById.containsKey(id.target())) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-EDGE-002", "dangling edge target: " + id.canonicalKey(), null, id));
         }
      }

      for (ProjectGraphEdge edge : uniqueEdges) {
         validateEdgeEndpointKinds(edge, nodeById, diagnostics);
      }

      validateProjectRoot(nodes, diagnostics);
      validateSemanticKinds(nodes, diagnostics);
      validateTraceEdges(nodes, uniqueEdges, diagnostics);
      validateProvenanceConsistency(nodes, diagnostics);
      validateArtifactFieldConsistency(nodes, diagnostics);
      validateLoweredOwnership(nodes, nodeById, uniqueEdges, diagnostics);
      validateArtifactOwnershipAndRole(nodeById, uniqueEdges, diagnostics);
      validateFileOwnership(nodeById, uniqueEdges, diagnostics);
      validateFilePaths(nodes, diagnostics);
      validateCanonicalOrder(nodes, edges, diagnostics);
      return diagnostics;
   }

   private static void validateProjectRoot(List<ProjectGraphNode> nodes, List<ProjectGraphDiagnostic> diagnostics) {
      long projectCount = nodes.stream().filter(n -> n instanceof ProjectGraphNode.Project).count();
      if (projectCount == 0L) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-NODE-002", "graph must have exactly one Project root; found 0", null, null));
      } else if (projectCount > 1L) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-NODE-003", "graph must have exactly one Project root; found " + projectCount, null, null));
      }
   }

   private static void validateSemanticKinds(List<ProjectGraphNode> nodes, List<ProjectGraphDiagnostic> diagnostics) {
      for (ProjectGraphNode node : nodes) {
         if (node instanceof ProjectGraphNode.SemanticDeclaration s) {
            SymbolKind kind = s.kind();
            boolean allowed = kind == SymbolKind.ENUM
               || kind == SymbolKind.ENTITY
               || kind == SymbolKind.INPUT
               || kind == SymbolKind.ERROR
               || kind == SymbolKind.CAPABILITY;
            if (!allowed) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-NODE-004",
                     "top-level SemanticDeclaration has illegal SymbolKind " + kind + " (only ENUM, ENTITY, INPUT, ERROR, CAPABILITY are allowed)",
                     s.id(),
                     null
                  )
               );
            }
         }
      }
   }

   private static void validateArtifactFieldConsistency(List<ProjectGraphNode> nodes, List<ProjectGraphDiagnostic> diagnostics) {
      for (ProjectGraphNode node : nodes) {
         if (node instanceof ProjectGraphNode.Artifact a) {
            GraphProvenance.ArtifactProvenance prov = a.provenance();
            if (!a.role().equals(prov.role()) || !a.qualifiedName().equals(prov.qualifiedName())) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-PROVENANCE-002",
                     "Artifact node-level role/qualifiedName ("
                        + a.role()
                        + "/"
                        + a.qualifiedName()
                        + ") does not match ArtifactProvenance ("
                        + prov.role()
                        + "/"
                        + prov.qualifiedName()
                        + ")",
                     a.id(),
                     null
                  )
               );
            }
         }
      }
   }

   private static void validateTraceEdges(List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges, List<ProjectGraphDiagnostic> diagnostics) {
      Map<GraphNodeId, Integer> declaresIn = new LinkedHashMap<>();
      Map<GraphNodeId, Integer> lowersToOut = new LinkedHashMap<>();
      Map<GraphNodeId, Integer> lowersToIn = new LinkedHashMap<>();
      Map<GraphNodeId, Integer> ownsArtifactIn = new LinkedHashMap<>();
      Map<GraphNodeId, Integer> generatesFileIn = new LinkedHashMap<>();

      for (ProjectGraphEdge edge : edges) {
         switch (edge.kind()) {
            case DECLARES:
               declaresIn.merge(edge.target(), 1, Integer::sum);
               break;
            case LOWERS_TO:
               lowersToOut.merge(edge.source(), 1, Integer::sum);
               lowersToIn.merge(edge.target(), 1, Integer::sum);
               break;
            case OWNS_ARTIFACT:
               ownsArtifactIn.merge(edge.target(), 1, Integer::sum);
               break;
            case GENERATES_FILE:
               generatesFileIn.merge(edge.target(), 1, Integer::sum);
         }
      }

      for (ProjectGraphNode node : nodes) {
         switch (node) {
            case ProjectGraphNode.SemanticDeclaration s:
               int declares = declaresIn.getOrDefault(node.id(), 0);
               if (declares == 0) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-EDGE-008", "SemanticDeclaration has no incoming DECLARES edge: " + node.id().canonicalKey(), node.id(), null
                     )
                  );
               }

               int lowersx = lowersToOut.getOrDefault(node.id(), 0);
               if (lowersx == 0) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-EDGE-010", "SemanticDeclaration has no outgoing LOWERS_TO edge: " + node.id().canonicalKey(), node.id(), null
                     )
                  );
               } else if (lowersx > 1) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-EDGE-011",
                        "SemanticDeclaration has " + lowersx + " outgoing LOWERS_TO edges (expected 1): " + node.id().canonicalKey(),
                        node.id(),
                        null
                     )
                  );
               }
               break;
            case ProjectGraphNode.LoweredDeclaration l:
               int lowers = lowersToIn.getOrDefault(node.id(), 0);
               if (lowers == 0) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-EDGE-012", "LoweredDeclaration has no incoming LOWERS_TO edge: " + node.id().canonicalKey(), node.id(), null
                     )
                  );
               }
               break;
            case ProjectGraphNode.Artifact a:
               int owns = ownsArtifactIn.getOrDefault(node.id(), 0);
               if (owns == 0) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-EDGE-013", "Artifact has no incoming OWNS_ARTIFACT edge: " + node.id().canonicalKey(), node.id(), null
                     )
                  );
               }
               break;
            case ProjectGraphNode.ProjectFile f:
               int gen = generatesFileIn.getOrDefault(node.id(), 0);
               if (gen == 0) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-EDGE-014", "ProjectFile has no incoming GENERATES_FILE edge: " + node.id().canonicalKey(), node.id(), null
                     )
                  );
               }
               break;
            default:
         }
      }
   }

   private static void validateProvenanceConsistency(List<ProjectGraphNode> nodes, List<ProjectGraphDiagnostic> diagnostics) {
      SourceId graphSourceId = null;

      for (ProjectGraphNode node : nodes) {
         if (node instanceof ProjectGraphNode.Project p) {
            graphSourceId = p.provenance().sourceId();
            break;
         }
      }

      if (graphSourceId != null) {
         for (ProjectGraphNode node : nodes) {
            GraphProvenance prov = node.provenance();
            if (!prov.sourceId().equals(graphSourceId)) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-PROVENANCE-006",
                     "node provenance sourceId (" + prov.sourceId() + ") does not match graph sourceId (" + graphSourceId + ")",
                     node.id(),
                     null
                  )
               );
            } else {
               SourceSpan span = switch (prov) {
                  case GraphProvenance.ProjectProvenance p -> null;
                  case GraphProvenance.SemanticProvenance p -> p.span();
                  case GraphProvenance.LoweredProvenance p -> p.origin().span();
                  case GraphProvenance.ArtifactProvenance p -> p.origin().span();
                  case GraphProvenance.FileProvenance p -> null;
                  default -> throw new MatchException(null, null);
               };
               if (span != null && !span.source().equals(graphSourceId)) {
                  boolean exempt = false;
                  if (node instanceof ProjectGraphNode.Artifact artifact
                     && artifact.role() instanceof ArtifactRole.ProjectRole
                     && artifact.ownerSymbol().isEmpty()) {
                     exempt = true;
                  }

                  if (!exempt) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-007",
                           "provenance span source (" + span.source() + ") does not match graph sourceId (" + graphSourceId + ")",
                           node.id(),
                           null
                        )
                     );
                  }
               }
            }
         }
      }
   }

   private static void validateProvenanceKind(ProjectGraphNode node, List<ProjectGraphDiagnostic> diagnostics) {
      GraphProvenance prov = node.provenance();

      boolean ok = switch (node) {
         case ProjectGraphNode.Project p -> prov instanceof GraphProvenance.ProjectProvenance;
         case ProjectGraphNode.SemanticDeclaration s -> prov instanceof GraphProvenance.SemanticProvenance;
         case ProjectGraphNode.LoweredDeclaration l -> prov instanceof GraphProvenance.LoweredProvenance;
         case ProjectGraphNode.Artifact a -> prov instanceof GraphProvenance.ArtifactProvenance;
         case ProjectGraphNode.ProjectFile f -> prov instanceof GraphProvenance.FileProvenance;
         default -> throw new MatchException(null, null);
      };
      if (!ok) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-PROVENANCE-001", "provenance variant does not match node kind for " + node.id().canonicalKey(), node.id(), null
            )
         );
      }
   }

   private static void validateEdgeEndpointKinds(ProjectGraphEdge edge, Map<GraphNodeId, ProjectGraphNode> nodeById, List<ProjectGraphDiagnostic> diagnostics) {
      GraphEdgeId id = edge.id();
      ProjectGraphNode source = nodeById.get(id.source());
      ProjectGraphNode target = nodeById.get(id.target());

      boolean ok = switch (id.kind()) {
         case DECLARES -> isProject(source) && isSemanticDeclaration(target);
         case LOWERS_TO -> isSemanticDeclaration(source) && isLoweredDeclaration(target);
         case OWNS_ARTIFACT -> (isLoweredDeclaration(source) || isProject(source)) && isArtifact(target);
         case GENERATES_FILE -> isArtifact(source) && isProjectFile(target);
      };
      if (!ok) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-EDGE-004", "edge endpoints have wrong kind for " + id.canonicalKey(), null, id));
      }
   }

   private static void validateLoweredOwnership(
      List<ProjectGraphNode> nodes, Map<GraphNodeId, ProjectGraphNode> nodeById, List<ProjectGraphEdge> edges, List<ProjectGraphDiagnostic> diagnostics
   ) {
      Map<GraphNodeId, GraphNodeId> lowersToSource = new LinkedHashMap<>();
      Set<GraphNodeId> multiSourceLowered = new HashSet<>();

      for (ProjectGraphEdge edge : edges) {
         if (edge.kind() == GraphEdgeKind.LOWERS_TO) {
            GraphNodeId previous = lowersToSource.put(edge.target(), edge.source());
            if (previous != null && multiSourceLowered.add(edge.target())) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-EDGE-005", "multiple LOWERS_TO edges target the same LoweredDeclaration: " + edge.target().canonicalKey(), null, edge.id()
                  )
               );
            }
         }
      }

      for (ProjectGraphNode node : nodes) {
         if (node instanceof ProjectGraphNode.LoweredDeclaration lowered && !multiSourceLowered.contains(lowered.id())) {
            GraphNodeId semanticId = lowersToSource.get(lowered.id());
            if (semanticId != null && semanticId instanceof GraphNodeId.Semantic sem) {
               if (!sem.symbolId().equals(lowered.sourceSymbol())) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-PROVENANCE-003",
                        "LoweredDeclaration sourceSymbol (" + lowered.sourceSymbol() + ") does not match LOWERS_TO source (" + sem.symbolId() + ")",
                        lowered.id(),
                        null
                     )
                  );
               }

               Optional<SymbolId> originOwner = lowered.provenance().origin().ownerSymbol();
               if (originOwner.isPresent() && !originOwner.get().equals(lowered.sourceSymbol())) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-PROVENANCE-003",
                        "LoweredDeclaration origin owner (" + originOwner.get() + ") does not match sourceSymbol (" + lowered.sourceSymbol() + ")",
                        lowered.id(),
                        null
                     )
                  );
               }

               if (originOwner.isEmpty()) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-PROVENANCE-003",
                        "LoweredDeclaration origin owner is empty but sourceSymbol is present: " + lowered.sourceSymbol(),
                        lowered.id(),
                        null
                     )
                  );
               }
            }
         }
      }
   }

   private static void validateArtifactOwnershipAndRole(
      Map<GraphNodeId, ProjectGraphNode> nodeById, List<ProjectGraphEdge> edges, List<ProjectGraphDiagnostic> diagnostics
   ) {
      Map<GraphNodeId, GraphNodeId> ownerOfArtifact = new LinkedHashMap<>();
      Set<GraphNodeId> multiOwnerArtifact = new HashSet<>();

      for (ProjectGraphEdge edge : edges) {
         if (edge.kind() == GraphEdgeKind.OWNS_ARTIFACT) {
            GraphNodeId previous = ownerOfArtifact.put(edge.target(), edge.source());
            if (previous != null && multiOwnerArtifact.add(edge.target())) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-EDGE-006", "multiple OWNS_ARTIFACT edges target the same Artifact: " + edge.target().canonicalKey(), null, edge.id()
                  )
               );
            }
         }
      }

      Set<String> artifactTargetOwnerRole = new HashSet<>();

      for (ProjectGraphNode node : nodeById.values()) {
         if (node instanceof ProjectGraphNode.Artifact artifact) {
            GraphNodeId ownerId = ownerOfArtifact.get(artifact.id());
            if (ownerId != null && !multiOwnerArtifact.contains(artifact.id())) {
               ProjectGraphNode ownerNode = nodeById.get(ownerId);
               if (ownerNode instanceof ProjectGraphNode.Project) {
                  if (artifact.ownerSymbol().isPresent()) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-004",
                           "project-level Artifact must have empty ownerSymbol: " + artifact.id().canonicalKey(),
                           artifact.id(),
                           null
                        )
                     );
                  }

                  Optional<SymbolId> originOwner = artifact.provenance().origin().ownerSymbol();
                  if (originOwner.isPresent()) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-004",
                           "project-level Artifact must have empty origin.ownerSymbol: " + artifact.id().canonicalKey(),
                           artifact.id(),
                           null
                        )
                     );
                  }
               } else if (ownerNode instanceof ProjectGraphNode.LoweredDeclaration lowered) {
                  if (artifact.ownerSymbol().isEmpty() || !artifact.ownerSymbol().get().equals(lowered.sourceSymbol())) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-004",
                           "declaration-level Artifact ownerSymbol ("
                              + artifact.ownerSymbol()
                              + ") does not match owning LoweredDeclaration sourceSymbol ("
                              + lowered.sourceSymbol()
                              + ")",
                           artifact.id(),
                           null
                        )
                     );
                  }

                  Optional<SymbolId> originOwner = artifact.provenance().origin().ownerSymbol();
                  if (originOwner.isEmpty() || !originOwner.get().equals(lowered.sourceSymbol())) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-004",
                           "declaration-level Artifact origin.ownerSymbol ("
                              + originOwner
                              + ") does not match owning LoweredDeclaration sourceSymbol ("
                              + lowered.sourceSymbol()
                              + ")",
                           artifact.id(),
                           null
                        )
                     );
                  }
               }

               String ownerRoleKey = ownerId.canonicalKey() + "|" + roleKey(artifact.role());
               if (!artifactTargetOwnerRole.add(ownerRoleKey)) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error("SIR-GRAPH-PROVENANCE-005", "duplicate Artifact owner+role: " + ownerRoleKey, artifact.id(), null)
                  );
               }
            }
         }
      }
   }

   private static void validateFileOwnership(
      Map<GraphNodeId, ProjectGraphNode> nodeById, List<ProjectGraphEdge> edges, List<ProjectGraphDiagnostic> diagnostics
   ) {
      Map<GraphNodeId, GraphNodeId> artifactOfFile = new LinkedHashMap<>();
      Set<GraphNodeId> multiArtifactFile = new HashSet<>();

      for (ProjectGraphEdge edge : edges) {
         if (edge.kind() == GraphEdgeKind.GENERATES_FILE) {
            GraphNodeId previous = artifactOfFile.put(edge.target(), edge.source());
            if (previous != null && multiArtifactFile.add(edge.target())) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-EDGE-007", "multiple GENERATES_FILE edges target the same ProjectFile: " + edge.target().canonicalKey(), null, edge.id()
                  )
               );
            }
         }
      }

      for (ProjectGraphNode node : nodeById.values()) {
         if (node instanceof ProjectGraphNode.ProjectFile file) {
            GraphNodeId artifactId = artifactOfFile.get(file.id());
            if (artifactId != null && !multiArtifactFile.contains(file.id())) {
               ProjectGraphNode artifactNode = nodeById.get(artifactId);
               if (artifactNode instanceof ProjectGraphNode.Artifact artifact) {
                  LoweredNodeId fileArt = file.provenance().artifactId();
                  LoweredNodeId edgeArt = ((GraphNodeId.Lowered)artifactId).nodeId();
                  if (!fileArt.equals(edgeArt)) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-004",
                           "ProjectFile provenance artifactId (" + fileArt + ") does not match GENERATES_FILE source (" + edgeArt + ")",
                           file.id(),
                           null
                        )
                     );
                  }

                  if (!file.provenance().ownerSymbol().equals(artifact.ownerSymbol())) {
                     diagnostics.add(
                        ProjectGraphDiagnostic.error(
                           "SIR-GRAPH-PROVENANCE-004",
                           "ProjectFile ownerSymbol ("
                              + file.provenance().ownerSymbol()
                              + ") does not match Artifact ownerSymbol ("
                              + artifact.ownerSymbol()
                              + ")",
                           file.id(),
                           null
                        )
                     );
                  }
               }
            }
         }
      }
   }

   private static void validateFilePaths(List<ProjectGraphNode> nodes, List<ProjectGraphDiagnostic> diagnostics) {
      Set<String> seenCaseFolded = new HashSet<>();

      for (ProjectGraphNode node : nodes) {
         if (node instanceof ProjectGraphNode.ProjectFile file) {
            String path = file.id().relativePath();
            String folded = path.toLowerCase(Locale.ROOT);
            if (!seenCaseFolded.add(folded)) {
               diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-PATH-002", "case-colliding ProjectFile path: " + path, file.id(), null));
            }
         }
      }
   }

   private static void validateCanonicalOrder(List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges, List<ProjectGraphDiagnostic> diagnostics) {
      List<ProjectGraphNode> sortedNodes = new ArrayList<>(nodes);
      sortedNodes.sort(ProjectGraphCanonicalizer.nodeComparator());

      for (int i = 0; i < nodes.size(); i++) {
         if (!nodes.get(i).equals(sortedNodes.get(i))) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-ORDER-001", "nodes are not in canonical order at index " + i + ": " + nodes.get(i).id().canonicalKey(), nodes.get(i).id(), null
               )
            );
            break;
         }
      }

      List<ProjectGraphEdge> sortedEdges = new ArrayList<>(edges);
      sortedEdges.sort(Comparator.comparing(ProjectGraphEdge::id));

      for (int i = 0; i < edges.size(); i++) {
         if (!edges.get(i).equals(sortedEdges.get(i))) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-ORDER-002", "edges are not in canonical order at index " + i + ": " + edges.get(i).id().canonicalKey(), null, edges.get(i).id()
               )
            );
            break;
         }
      }
   }

   private static boolean isProject(ProjectGraphNode node) {
      return node instanceof ProjectGraphNode.Project;
   }

   private static boolean isSemanticDeclaration(ProjectGraphNode node) {
      return node instanceof ProjectGraphNode.SemanticDeclaration;
   }

   private static boolean isLoweredDeclaration(ProjectGraphNode node) {
      return node instanceof ProjectGraphNode.LoweredDeclaration;
   }

   private static boolean isArtifact(ProjectGraphNode node) {
      return node instanceof ProjectGraphNode.Artifact;
   }

   private static boolean isProjectFile(ProjectGraphNode node) {
      return node instanceof ProjectGraphNode.ProjectFile;
   }

   private static String roleKey(ArtifactRole role) {
      if (role instanceof ArtifactRole.DeclarationRole dr) {
         return "decl:" + dr.name();
      } else {
         return role instanceof ArtifactRole.ProjectRole pr ? "project:" + pr.name() : "unknown";
      }
   }
}
