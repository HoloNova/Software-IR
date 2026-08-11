package io.kcg.sir.projectgraph.internal;

import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalizer;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ProjectGraphValidator;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceSpan;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Map.Entry;

public final class SnapshotEncoder {
   private SnapshotEncoder() {
   }

   public static SnapshotEncoder.EncodeResult encode(
      ProjectGraph graph, ProjectGraphCanonicalFormatVersion formatVersion, List<ProjectGraphDiagnostic> diagnostics
   ) {
      if (formatVersion != ProjectGraphCanonicalFormatVersion.V1) {
         diagnostics.add(
            ProjectGraphDiagnostic.error("SIR-GRAPH-COMPAT-001", "unsupported canonical format version: " + formatVersion + " (only V1 is supported)")
         );
         return null;
      }

      ProjectGraphValidator validator = new ProjectGraphValidator();
      List<ProjectGraphNode> nodes = new ArrayList<>(graph.nodes());
      List<ProjectGraphEdge> edges = new ArrayList<>(graph.edges());
      List<ProjectGraphDiagnostic> validateDiags = validator.validate(graph.version(), nodes, edges);
      if (validateDiags.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-SERIALIZE-003",
               "validator reports ERROR diagnostics during serialization; first: " + validateDiags.get(0).code() + " " + validateDiags.get(0).message()
            )
         );
         return null;
      }

      String recomputedDigest = GraphCanonicalForm.digest(graph.version(), nodes, edges);
      if (!recomputedDigest.equals(graph.canonicalDigest())) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-SERIALIZE-002",
               "existing canonicalDigest (" + graph.canonicalDigest() + ") does not match recomputed digest (" + recomputedDigest + ")"
            )
         );
         return null;
      }

      byte[] payload = encodePayload(graph, nodes, edges, diagnostics);
      if (payload == null) {
         return null;
      }

      String payloadSha256Hex = Sha256Helper.hexDigest(payload);
      byte[] header = encodeHeader(payload.length, payloadSha256Hex);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.write(SnapshotFormat.MAGIC, 0, SnapshotFormat.MAGIC.length);
      out.write(header, 0, header.length);
      byte[] payloadMarker = "PAYLOAD\n".getBytes(StandardCharsets.US_ASCII);
      out.write(payloadMarker, 0, payloadMarker.length);
      out.write(payload, 0, payload.length);
      return new SnapshotEncoder.EncodeResult(out.toByteArray(), payloadSha256Hex);
   }

   private static byte[] encodePayload(ProjectGraph graph, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges, List<ProjectGraphDiagnostic> diagnostics) {
      List<ProjectGraphNode> sortedNodes = new ArrayList<>(nodes);
      sortedNodes.sort(ProjectGraphCanonicalizer.nodeComparator());
      List<ProjectGraphEdge> sortedEdges = new ArrayList<>(edges);
      sortedEdges.sort(Comparator.comparing(ProjectGraphEdge::id));
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Map<String, String> graphFields = new TreeMap<>();
      graphFields.put("canonicalDigest", graph.canonicalDigest());
      graphFields.put("edgeCount", Integer.toString(sortedEdges.size()));
      graphFields.put("graphVersion", graph.version().name());
      graphFields.put("nodeCount", Integer.toString(sortedNodes.size()));
      writeRecord(out, "GRAPH", graphFields, diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      for (ProjectGraphNode node : sortedNodes) {
         Map<String, String> nodeFields = encodeNode(node, diagnostics);
         if (nodeFields == null) {
            return null;
         }

         writeRecord(out, "NODE", nodeFields, diagnostics);
         if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
            return null;
         }
      }

      for (ProjectGraphEdge edge : sortedEdges) {
         Map<String, String> edgeFields = encodeEdge(edge);
         writeRecord(out, "EDGE", edgeFields, diagnostics);
         if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
            return null;
         }
      }

      writeRecord(out, "END", new TreeMap<>(), diagnostics);
      return diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError) ? null : out.toByteArray();
   }

   private static byte[] encodeHeader(int payloadByteCount, String payloadSha256Hex) {
      Map<String, String> headerFields = new TreeMap<>();
      headerFields.put("formatVersion", "1");
      headerFields.put("payloadByteCount", Integer.toString(payloadByteCount));
      headerFields.put("payloadSha256Hex", payloadSha256Hex);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      writeAscii(out, "HEADER\n");
      writeAscii(out, Integer.toString(headerFields.size()) + "\n");
      writeFields(out, headerFields);
      return out.toByteArray();
   }

   private static Map<String, String> encodeNode(ProjectGraphNode node, List<ProjectGraphDiagnostic> diagnostics) {
      Map<String, String> f = new TreeMap<>();
      switch (node) {
         case ProjectGraphNode.Project p:
            f.put("displayName", p.displayName());
            f.put("id.kind", "Project");
            f.put("id.value", "");
            f.put("nodeKind", "Project");
            f.put("provenance.kind", "ProjectProvenance");
            f.put("provenance.sourceId", p.provenance().sourceId().value());
            break;
         case ProjectGraphNode.SemanticDeclaration s:
            f.put("displayName", s.displayName());
            f.put("id.kind", "Semantic");
            f.put("id.value", s.id().symbolId().value());
            f.put("nodeKind", "SemanticDeclaration");
            f.put("provenance.kind", "SemanticProvenance");
            f.put("provenance.sourceId", s.provenance().sourceId().value());
            f.put("provenance.sourceNodeId", s.provenance().sourceNodeId().value());
            putSpan(f, "", s.provenance().span());
            f.put("symbolKind", s.kind().name());
            break;
         case ProjectGraphNode.LoweredDeclaration l:
            f.put("displayName", l.displayName());
            f.put("id.kind", "Lowered");
            f.put("id.value", l.id().nodeId().value());
            f.put("nodeKind", "LoweredDeclaration");
            putOrigin(f, l.provenance().origin());
            f.put("provenance.kind", "LoweredProvenance");
            f.put("provenance.sourceId", l.provenance().sourceId().value());
            f.put("sourceSymbol", l.sourceSymbol().value());
            break;
         case ProjectGraphNode.Artifact a:
            f.put("id.kind", "Lowered");
            f.put("id.value", a.id().nodeId().value());
            f.put("nodeKind", "Artifact");
            putOrigin(f, a.provenance().origin());
            putOptional(f, "provenance.ownerSymbol.present", "provenance.ownerSymbol.value", a.provenance().ownerSymbol().map(SymbolId::value));
            f.put("provenance.kind", "ArtifactProvenance");
            f.put("provenance.sourceId", a.provenance().sourceId().value());
            f.put("provenance.qualifiedName", a.provenance().qualifiedName());
            putRole(f, "provenance.role.family", "provenance.role.name", a.provenance().role());
            f.put("qualifiedName", a.qualifiedName());
            putRole(f, "role.family", "role.name", a.role());
            break;
         case ProjectGraphNode.ProjectFile file:
            f.put("artifactId", file.provenance().artifactId().value());
            f.put("byteCount", Long.toString(file.provenance().byteCount()));
            f.put("id.kind", "File");
            f.put("id.value", file.id().relativePath());
            f.put("nodeKind", "ProjectFile");
            putOptional(f, "ownerSymbol.present", "ownerSymbol.value", file.provenance().ownerSymbol().map(SymbolId::value));
            f.put("provenance.kind", "FileProvenance");
            f.put("provenance.sourceId", file.provenance().sourceId().value());
            f.put("sha256Hex", file.provenance().sha256Hex());
            break;
         default:
            throw new MatchException(null, null);
      }

      for (Entry<String, String> entry : f.entrySet()) {
         byte[] encoded = StrictUtf8.encodeStrict(entry.getValue());
         if (encoded == null) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-SERIALIZE-001", "node contains non-encodable Unicode scalar in field " + entry.getKey() + " for node " + node.id().canonicalKey()
               )
            );
            return null;
         }
      }

      return f;
   }

   private static Map<String, String> encodeEdge(ProjectGraphEdge edge) {
      Map<String, String> f = new TreeMap<>();
      f.put("kind", edge.kind().name());
      putNodeIdRef(f, "source", edge.source());
      putNodeIdRef(f, "target", edge.target());
      return f;
   }

   private static void putNodeIdRef(Map<String, String> f, String prefix, GraphNodeId id) {
      if (id instanceof GraphNodeId.ProjectNodeId) {
         f.put(prefix + ".id.kind", "Project");
         f.put(prefix + ".id.value", "");
      } else if (id instanceof GraphNodeId.Semantic s) {
         f.put(prefix + ".id.kind", "Semantic");
         f.put(prefix + ".id.value", s.symbolId().value());
      } else if (id instanceof GraphNodeId.Lowered l) {
         f.put(prefix + ".id.kind", "Lowered");
         f.put(prefix + ".id.value", l.nodeId().value());
      } else if (id instanceof GraphNodeId.File file) {
         f.put(prefix + ".id.kind", "File");
         f.put(prefix + ".id.value", file.relativePath());
      }
   }

   private static void putSpan(Map<String, String> f, String prefix, SourceSpan span) {
      String p = prefix.isEmpty() ? "span" : prefix + ".span";
      f.put(p + ".source", span.source().value());
      f.put(p + ".start.codePointOffset", Integer.toString(span.start().codePointOffset()));
      f.put(p + ".start.line", Integer.toString(span.start().line()));
      f.put(p + ".start.column", Integer.toString(span.start().column()));
      f.put(p + ".end.codePointOffset", Integer.toString(span.end().codePointOffset()));
      f.put(p + ".end.line", Integer.toString(span.end().line()));
      f.put(p + ".end.column", Integer.toString(span.end().column()));
   }

   private static void putOrigin(Map<String, String> f, LoweredOrigin origin) {
      putOptional(f, "origin.ownerSymbol.present", "origin.ownerSymbol.value", origin.ownerSymbol().map(SymbolId::value));
      f.put("origin.sourceNodeId", origin.sourceNodeId().value());
      putSpan(f, "origin", origin.span());
   }

   private static void putOptional(Map<String, String> f, String presentField, String valueField, Optional<String> value) {
      if (value.isPresent()) {
         f.put(presentField, "1");
         f.put(valueField, value.get());
      } else {
         f.put(presentField, "0");
         f.put(valueField, "");
      }
   }

   private static void putRole(Map<String, String> f, String familyField, String nameField, ArtifactRole role) {
      if (role instanceof ArtifactRole.DeclarationRole dr) {
         f.put(familyField, "Declaration");
         f.put(nameField, dr.name());
      } else if (role instanceof ArtifactRole.ProjectRole pr) {
         f.put(familyField, "Project");
         f.put(nameField, pr.name());
      }
   }

   private static void writeRecord(ByteArrayOutputStream out, String tag, Map<String, String> fields, List<ProjectGraphDiagnostic> diagnostics) {
      writeAscii(out, tag + "\n");
      writeAscii(out, Integer.toString(fields.size()) + "\n");
      writeFields(out, fields);
   }

   private static void writeFields(ByteArrayOutputStream out, Map<String, String> fields) {
      for (Entry<String, String> entry : fields.entrySet()) {
         String name = entry.getKey();
         String value = entry.getValue();
         byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
         byte[] valueBytes = StrictUtf8.encodeStrict(value);
         if (valueBytes == null) {
            valueBytes = new byte[0];
         }

         out.write(nameBytes, 0, nameBytes.length);
         out.write(10);
         writeAscii(out, Integer.toString(valueBytes.length));
         out.write(58);
         out.write(valueBytes, 0, valueBytes.length);
         out.write(10);
      }
   }

   private static void writeAscii(ByteArrayOutputStream out, String ascii) {
      byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
      out.write(bytes, 0, bytes.length);
   }

   public static final class EncodeResult {
      public final byte[] documentBytes;
      public final String payloadSha256Hex;

      EncodeResult(byte[] documentBytes, String payloadSha256Hex) {
         this.documentBytes = documentBytes;
         this.payloadSha256Hex = payloadSha256Hex;
      }
   }
}
