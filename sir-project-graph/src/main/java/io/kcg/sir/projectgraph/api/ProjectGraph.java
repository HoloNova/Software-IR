package io.kcg.sir.projectgraph.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Map.Entry;

public final class ProjectGraph {
   private final GraphVersion version;
   private final List<ProjectGraphNode> nodes;
   private final List<ProjectGraphEdge> edges;
   private final String canonicalDigest;
   private final Map<GraphNodeId, ProjectGraphNode> nodeIndex;
   private final Map<GraphNodeId, List<ProjectGraphEdge>> incomingIndex;
   private final Map<GraphNodeId, List<ProjectGraphEdge>> outgoingIndex;
   private final Map<SymbolId, List<ProjectGraphNode.Artifact>> artifactsBySymbol;
   private final Map<LoweredNodeId, List<ProjectGraphNode.ProjectFile>> filesByArtifact;

   ProjectGraph(GraphVersion version, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges, String canonicalDigest) {
      this.version = Objects.requireNonNull(version, "version");
      this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
      this.edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
      this.canonicalDigest = Objects.requireNonNull(canonicalDigest, "canonicalDigest");
      Map<GraphNodeId, ProjectGraphNode> ni = new LinkedHashMap<>();
      Map<GraphNodeId, List<ProjectGraphEdge>> in = new LinkedHashMap<>();
      Map<GraphNodeId, List<ProjectGraphEdge>> out = new LinkedHashMap<>();
      Map<SymbolId, List<ProjectGraphNode.Artifact>> abs = new LinkedHashMap<>();
      Map<LoweredNodeId, List<ProjectGraphNode.ProjectFile>> fba = new LinkedHashMap<>();

      for (ProjectGraphNode node : this.nodes) {
         ni.put(node.id(), node);
         in.putIfAbsent(node.id(), new ArrayList<>());
         out.putIfAbsent(node.id(), new ArrayList<>());
         if (node instanceof ProjectGraphNode.Artifact artifact) {
            artifact.ownerSymbol().ifPresent(symbol -> abs.computeIfAbsent(symbol, k -> new ArrayList<>()).add(artifact));
         }

         if (node instanceof ProjectGraphNode.ProjectFile file) {
            LoweredNodeId artifactId = file.provenance().artifactId();
            fba.computeIfAbsent(artifactId, k -> new ArrayList<>()).add(file);
         }
      }

      for (ProjectGraphEdge edge : this.edges) {
         out.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
         in.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
      }

      Map<GraphNodeId, List<ProjectGraphEdge>> frozenIn = new LinkedHashMap<>();
      Map<GraphNodeId, List<ProjectGraphEdge>> frozenOut = new LinkedHashMap<>();

      for (Entry<GraphNodeId, List<ProjectGraphEdge>> entry : in.entrySet()) {
         frozenIn.put(entry.getKey(), List.copyOf(entry.getValue()));
      }

      for (Entry<GraphNodeId, List<ProjectGraphEdge>> entry : out.entrySet()) {
         frozenOut.put(entry.getKey(), List.copyOf(entry.getValue()));
      }

      Map<SymbolId, List<ProjectGraphNode.Artifact>> frozenAbs = new LinkedHashMap<>();

      for (Entry<SymbolId, List<ProjectGraphNode.Artifact>> entry : abs.entrySet()) {
         frozenAbs.put(entry.getKey(), List.copyOf(entry.getValue()));
      }

      Map<LoweredNodeId, List<ProjectGraphNode.ProjectFile>> frozenFba = new LinkedHashMap<>();

      for (Entry<LoweredNodeId, List<ProjectGraphNode.ProjectFile>> entry : fba.entrySet()) {
         frozenFba.put(entry.getKey(), List.copyOf(entry.getValue()));
      }

      this.nodeIndex = Collections.unmodifiableMap(ni);
      this.incomingIndex = Collections.unmodifiableMap(frozenIn);
      this.outgoingIndex = Collections.unmodifiableMap(frozenOut);
      this.artifactsBySymbol = Collections.unmodifiableMap(frozenAbs);
      this.filesByArtifact = Collections.unmodifiableMap(frozenFba);
   }

   public GraphVersion version() {
      return this.version;
   }

   public List<ProjectGraphNode> nodes() {
      return this.nodes;
   }

   public List<ProjectGraphEdge> edges() {
      return this.edges;
   }

   public String canonicalDigest() {
      return this.canonicalDigest;
   }

   public Optional<ProjectGraphNode> node(GraphNodeId id) {
      return Optional.ofNullable(this.nodeIndex.get(Objects.requireNonNull(id, "id")));
   }

   public List<ProjectGraphEdge> incoming(GraphNodeId target) {
      return this.incomingIndex.getOrDefault(Objects.requireNonNull(target, "target"), List.of());
   }

   public List<ProjectGraphEdge> outgoing(GraphNodeId source) {
      return this.outgoingIndex.getOrDefault(Objects.requireNonNull(source, "source"), List.of());
   }

   public List<ProjectGraphNode.Artifact> artifactsFor(SymbolId symbolId) {
      return this.artifactsBySymbol.getOrDefault(Objects.requireNonNull(symbolId, "symbolId"), List.of());
   }

   public List<ProjectGraphNode.ProjectFile> filesForArtifact(LoweredNodeId artifactId) {
      return this.filesByArtifact.getOrDefault(Objects.requireNonNull(artifactId, "artifactId"), List.of());
   }
}
