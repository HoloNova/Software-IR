package io.kcg.sir.projectgraph.internal;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeId;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphProvenance;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ProjectGraphValidator;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class SnapshotLoader {
   private SnapshotLoader() {
   }

   public static SnapshotLoader.LoadedGraph load(RawSnapshotDocument raw, List<ProjectGraphDiagnostic> diagnostics) {
      List<ProjectGraphNode> nodes = new ArrayList<>();

      for (RawSnapshotDocument.RawRecord rawNode : raw.nodes) {
         ProjectGraphNode node = constructNode(rawNode, diagnostics);
         if (node == null) {
            return null;
         }

         nodes.add(node);
      }

      List<ProjectGraphEdge> edges = new ArrayList<>();

      for (RawSnapshotDocument.RawRecord rawEdge : raw.edges) {
         ProjectGraphEdge edge = constructEdge(rawEdge, diagnostics);
         if (edge == null) {
            return null;
         }

         edges.add(edge);
      }

      ProjectGraphValidator validator = new ProjectGraphValidator();
      List<ProjectGraphDiagnostic> validateDiags = validator.validate(GraphVersion.V0_1, nodes, edges);
      diagnostics.addAll(validateDiags);
      if (validateDiags.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      } else {
         String recomputedDigest = GraphCanonicalForm.digest(GraphVersion.V0_1, nodes, edges);
         String documentDigest = raw.graph.fields.get("canonicalDigest");
         if (!recomputedDigest.equals(documentDigest)) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-INTEGRITY-002",
                  "canonical digest mismatch: GRAPH record declares " + documentDigest + " but recomputed digest is " + recomputedDigest
               )
            );
            return null;
         } else {
            return new SnapshotLoader.LoadedGraph(GraphVersion.V0_1, nodes, edges, recomputedDigest);
         }
      }
   }

   private static ProjectGraphNode constructNode(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      if (!isAsciiSorted(raw.fields.keySet())) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-018", "fields are not in ASCII name ascending order"));
         return null;
      }

      String nodeKind = raw.fields.get("nodeKind");
      if (nodeKind == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: nodeKind"));
         return null;
      }

      try {
         return switch (nodeKind) {
            case "Project" -> constructProjectNode(raw, diagnostics);
            case "SemanticDeclaration" -> constructSemanticNode(raw, diagnostics);
            case "LoweredDeclaration" -> constructLoweredNode(raw, diagnostics);
            case "Artifact" -> constructArtifactNode(raw, diagnostics);
            case "ProjectFile" -> constructFileNode(raw, diagnostics);
            default -> {
               diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-003", "unknown nodeKind: '" + nodeKind + "'"));
               yield null;
            }
         };
      } catch (SnapshotLoader.ConstructionFailure e) {
         diagnostics.add(e.diagnostic);
         return null;
      } catch (Exception e) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "unexpected construction failure: " + e.getMessage()));
         return null;
      }
   }

   private static ProjectGraphNode.Project constructProjectNode(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      validateFieldSet(raw, diagnostics, Set.of("displayName", "id.kind", "id.value", "nodeKind", "provenance.kind", "provenance.sourceId"));
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      requireEquals(raw, "id.kind", "Project", diagnostics);
      requireEquals(raw, "id.value", "", diagnostics);
      requireEquals(raw, "provenance.kind", "ProjectProvenance", diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      String displayName = raw.fields.get("displayName");
      if (displayName != null && !displayName.isBlank()) {
         SourceId sourceId = constructSourceId(raw, "provenance.sourceId", diagnostics);
         if (sourceId == null) {
            return null;
         }

         GraphProvenance.ProjectProvenance prov = new GraphProvenance.ProjectProvenance(sourceId);
         return new ProjectGraphNode.Project(GraphNodeId.ProjectNodeId.INSTANCE, prov, displayName);
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "displayName must not be blank"));
         return null;
      }
   }

   private static ProjectGraphNode.SemanticDeclaration constructSemanticNode(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      validateFieldSet(
         raw,
         diagnostics,
         Set.of(
            "displayName",
            "id.kind",
            "id.value",
            "nodeKind",
            "provenance.kind",
            "provenance.sourceId",
            "provenance.sourceNodeId",
            "span.source",
            "span.start.codePointOffset",
            "span.start.line",
            "span.start.column",
            "span.end.codePointOffset",
            "span.end.line",
            "span.end.column",
            "symbolKind"
         )
      );
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      requireEquals(raw, "id.kind", "Semantic", diagnostics);
      requireEquals(raw, "provenance.kind", "SemanticProvenance", diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      String displayName = raw.fields.get("displayName");
      if (displayName != null && !displayName.isBlank()) {
         String idValue = raw.fields.get("id.value");
         if (idValue != null && !idValue.isBlank()) {
            SymbolId symbolId = safeConstruct(
               () -> new SymbolId(idValue), raw, "SIR-GRAPH-FORMAT-012", "invalid SymbolId value: '" + idValue + "'", diagnostics
            );
            if (symbolId == null) {
               return null;
            }

            String symbolKindName = raw.fields.get("symbolKind");
            SymbolKind symbolKind = safeEnum(SymbolKind.class, symbolKindName);
            if (symbolKind == null) {
               diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-007", "unknown symbolKind: '" + symbolKindName + "'"));
               return null;
            }

            SourceId sourceId = constructSourceId(raw, "provenance.sourceId", diagnostics);
            if (sourceId == null) {
               return null;
            }

            AstNodeId sourceNodeId = constructAstNodeId(raw, "provenance.sourceNodeId", diagnostics);
            if (sourceNodeId == null) {
               return null;
            }

            SourceSpan span = constructSpan(raw, "", diagnostics);
            if (span == null) {
               return null;
            }

            GraphProvenance.SemanticProvenance prov = new GraphProvenance.SemanticProvenance(sourceId, sourceNodeId, span);
            return new ProjectGraphNode.SemanticDeclaration(new GraphNodeId.Semantic(symbolId), prov, symbolKind, displayName);
         } else {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "id.value must not be blank"));
            return null;
         }
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "displayName must not be blank"));
         return null;
      }
   }

   private static ProjectGraphNode.LoweredDeclaration constructLoweredNode(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      validateFieldSet(
         raw,
         diagnostics,
         Set.of(
            "displayName",
            "id.kind",
            "id.value",
            "nodeKind",
            "origin.ownerSymbol.present",
            "origin.ownerSymbol.value",
            "origin.sourceNodeId",
            "origin.span.source",
            "origin.span.start.codePointOffset",
            "origin.span.start.line",
            "origin.span.start.column",
            "origin.span.end.codePointOffset",
            "origin.span.end.line",
            "origin.span.end.column",
            "provenance.kind",
            "provenance.sourceId",
            "sourceSymbol"
         )
      );
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      requireEquals(raw, "id.kind", "Lowered", diagnostics);
      requireEquals(raw, "provenance.kind", "LoweredProvenance", diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      String displayName = raw.fields.get("displayName");
      if (displayName != null && !displayName.isBlank()) {
         LoweredNodeId nodeId = constructLoweredNodeId(raw, "id.value", diagnostics);
         if (nodeId == null) {
            return null;
         }

         SymbolId sourceSymbol = constructSymbolId(raw, "sourceSymbol", diagnostics);
         if (sourceSymbol == null) {
            return null;
         }

         SourceId sourceId = constructSourceId(raw, "provenance.sourceId", diagnostics);
         if (sourceId == null) {
            return null;
         }

         LoweredOrigin origin = constructOrigin(raw, diagnostics);
         if (origin == null) {
            return null;
         }

         GraphProvenance.LoweredProvenance prov = new GraphProvenance.LoweredProvenance(sourceId, origin);
         return new ProjectGraphNode.LoweredDeclaration(new GraphNodeId.Lowered(nodeId), prov, sourceSymbol, displayName);
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "displayName must not be blank"));
         return null;
      }
   }

   private static ProjectGraphNode.Artifact constructArtifactNode(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      validateFieldSet(
         raw,
         diagnostics,
         Set.of(
            "id.kind",
            "id.value",
            "nodeKind",
            "origin.ownerSymbol.present",
            "origin.ownerSymbol.value",
            "origin.sourceNodeId",
            "origin.span.source",
            "origin.span.start.codePointOffset",
            "origin.span.start.line",
            "origin.span.start.column",
            "origin.span.end.codePointOffset",
            "origin.span.end.line",
            "origin.span.end.column",
            "provenance.kind",
            "provenance.sourceId",
            "provenance.ownerSymbol.present",
            "provenance.ownerSymbol.value",
            "provenance.qualifiedName",
            "provenance.role.family",
            "provenance.role.name",
            "qualifiedName",
            "role.family",
            "role.name"
         )
      );
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      requireEquals(raw, "id.kind", "Lowered", diagnostics);
      requireEquals(raw, "provenance.kind", "ArtifactProvenance", diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      LoweredNodeId nodeId = constructLoweredNodeId(raw, "id.value", diagnostics);
      if (nodeId == null) {
         return null;
      }

      ArtifactRole nodeRole = constructRole(raw, diagnostics);
      if (nodeRole == null) {
         return null;
      }

      String nodeQualifiedName = raw.fields.get("qualifiedName");
      if (nodeQualifiedName != null && !nodeQualifiedName.isBlank()) {
         ArtifactRole provRole = constructProvenanceRole(raw, diagnostics);
         if (provRole == null) {
            return null;
         }

         String provQualifiedName = raw.fields.get("provenance.qualifiedName");
         if (provQualifiedName != null && !provQualifiedName.isBlank()) {
            Optional<SymbolId> provOwnerSymbol = constructOptionalSymbolId(raw, "provenance.ownerSymbol.present", "provenance.ownerSymbol.value", diagnostics);
            if (provOwnerSymbol == null) {
               return null;
            }

            SourceId sourceId = constructSourceId(raw, "provenance.sourceId", diagnostics);
            if (sourceId == null) {
               return null;
            }

            LoweredOrigin origin = constructOrigin(raw, diagnostics);
            if (origin == null) {
               return null;
            }

            GraphProvenance.ArtifactProvenance prov = new GraphProvenance.ArtifactProvenance(sourceId, origin, provOwnerSymbol, provRole, provQualifiedName);
            return new ProjectGraphNode.Artifact(new GraphNodeId.Lowered(nodeId), prov, nodeRole, nodeQualifiedName);
         } else {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "provenance.qualifiedName must not be blank"));
            return null;
         }
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "qualifiedName must not be blank"));
         return null;
      }
   }

   private static ProjectGraphNode.ProjectFile constructFileNode(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      validateFieldSet(
         raw,
         diagnostics,
         Set.of(
            "artifactId",
            "byteCount",
            "id.kind",
            "id.value",
            "nodeKind",
            "ownerSymbol.present",
            "ownerSymbol.value",
            "provenance.kind",
            "provenance.sourceId",
            "sha256Hex"
         )
      );
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      requireEquals(raw, "id.kind", "File", diagnostics);
      requireEquals(raw, "provenance.kind", "FileProvenance", diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      String relativePath = raw.fields.get("id.value");
      if (relativePath != null && !relativePath.isBlank()) {
         String pathViolation = validateFilePathCanonical(relativePath);
         if (pathViolation != null) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "invalid file path (" + pathViolation + "): " + relativePath));
            return null;
         }

         GraphNodeId.File fileId = safeConstruct(
            () -> new GraphNodeId.File(relativePath), raw, "SIR-GRAPH-FORMAT-012", "invalid GraphNodeId.File: " + relativePath, diagnostics
         );
         if (fileId == null) {
            return null;
         }

         LoweredNodeId artifactId = constructLoweredNodeId(raw, "artifactId", diagnostics);
         if (artifactId == null) {
            return null;
         }

         Optional<SymbolId> ownerSymbol = constructOptionalSymbolId(raw, "ownerSymbol.present", "ownerSymbol.value", diagnostics);
         if (ownerSymbol == null) {
            return null;
         }

         String byteCountStr = raw.fields.get("byteCount");
         if (!isShortestDecimal(byteCountStr)) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-007", "byteCount is not shortest decimal: '" + byteCountStr + "'"));
            return null;
         }

         long byteCount;
         try {
            byteCount = Long.parseLong(byteCountStr);
         } catch (NumberFormatException e) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "byteCount is not a valid decimal: '" + byteCountStr + "'"));
            return null;
         }

         if (byteCount < 0L) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "byteCount must not be negative: " + byteCount));
            return null;
         }

         String sha256Hex = raw.fields.get("sha256Hex");
         if (!Sha256Helper.isLowerCaseHex64(sha256Hex)) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "sha256Hex is not a 64-character lowercase hex string"));
            return null;
         }

         SourceId sourceId = constructSourceId(raw, "provenance.sourceId", diagnostics);
         if (sourceId == null) {
            return null;
         }

         GraphProvenance.FileProvenance prov = new GraphProvenance.FileProvenance(sourceId, artifactId, ownerSymbol, byteCount, sha256Hex);
         return new ProjectGraphNode.ProjectFile(fileId, prov);
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "id.value (relativePath) must not be blank"));
         return null;
      }
   }

   private static ProjectGraphEdge constructEdge(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      if (!isAsciiSorted(raw.fields.keySet())) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-018", "fields are not in ASCII name ascending order"));
         return null;
      }

      validateFieldSet(raw, diagnostics, Set.of("kind", "source.id.kind", "source.id.value", "target.id.kind", "target.id.value"));
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      String kindName = raw.fields.get("kind");
      GraphEdgeKind kind = safeEnum(GraphEdgeKind.class, kindName);
      if (kind == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-006", "unknown edge kind: '" + kindName + "'"));
         return null;
      }

      GraphNodeId source = constructNodeIdRef(raw, "source", diagnostics);
      if (source == null) {
         return null;
      }

      GraphNodeId target = constructNodeIdRef(raw, "target", diagnostics);
      return target == null ? null : new ProjectGraphEdge(new GraphEdgeId(kind, source, target));
   }

   private static GraphNodeId constructNodeIdRef(RawSnapshotDocument.RawRecord raw, String prefix, List<ProjectGraphDiagnostic> diagnostics) {
      String kind = raw.fields.get(prefix + ".id.kind");
      String value = raw.fields.get(prefix + ".id.value");
      if (kind == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + prefix + ".id.kind"));
         return null;
      }

      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + prefix + ".id.value"));
         return null;
      }

      return switch (kind) {
         case "Project" -> {
            if (!value.isEmpty()) {
               diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-010", "id.kind=Project but id.value is non-empty: '" + value + "'"));
               yield null;
            } else {
               yield GraphNodeId.ProjectNodeId.INSTANCE;
            }
         }
         case "Semantic" -> {
            if (value.isBlank()) {
               diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", prefix + ".id.value must not be blank for Semantic id"));
               yield null;
            } else {
               SymbolId sid = safeConstruct(() -> new SymbolId(value), raw, "SIR-GRAPH-FORMAT-012", "invalid SymbolId: '" + value + "'", diagnostics);
               yield sid != null ? new GraphNodeId.Semantic(sid) : null;
            }
         }
         case "Lowered" -> {
            if (value.isBlank()) {
               diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", prefix + ".id.value must not be blank for Lowered id"));
               yield null;
            } else {
               LoweredNodeId lid = safeConstruct(
                  () -> new LoweredNodeId(value), raw, "SIR-GRAPH-FORMAT-012", "invalid LoweredNodeId: '" + value + "'", diagnostics
               );
               yield lid != null ? new GraphNodeId.Lowered(lid) : null;
            }
         }
         case "File" -> {
            if (value.isBlank()) {
               diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", prefix + ".id.value must not be blank for File id"));
               yield null;
            } else {
               String pathViolation = validateFilePathCanonical(value);
               if (pathViolation != null) {
                  diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "invalid file path (" + pathViolation + "): " + value));
                  yield null;
               } else {
                  yield safeConstruct(() -> new GraphNodeId.File(value), raw, "SIR-GRAPH-FORMAT-012", "invalid GraphNodeId.File: " + value, diagnostics);
               }
            }
         }
         default -> {
            diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-005", "unknown id.kind: '" + kind + "'"));
            yield null;
         }
      };
   }

   private static LoweredOrigin constructOrigin(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      Optional<SymbolId> owner = constructOptionalSymbolId(raw, "origin.ownerSymbol.present", "origin.ownerSymbol.value", diagnostics);
      if (owner == null) {
         return null;
      }

      AstNodeId sourceNodeId = constructAstNodeId(raw, "origin.sourceNodeId", diagnostics);
      if (sourceNodeId == null) {
         return null;
      }

      SourceSpan span = constructSpan(raw, "origin", diagnostics);
      return span == null ? null : new LoweredOrigin(owner, sourceNodeId, span);
   }

   private static SourceSpan constructSpan(RawSnapshotDocument.RawRecord raw, String prefix, List<ProjectGraphDiagnostic> diagnostics) {
      if (prefix.isEmpty()) {
         String sourceField = "span.source";
      } else {
         "origin.span.source".replace("origin.", prefix + ".");
      }

      String p = prefix.isEmpty() ? "span" : prefix + ".span";
      String sourceStr = raw.fields.get(p + ".source");
      if (sourceStr == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + p + ".source"));
         return null;
      }

      SourceId source = constructSourceId(raw, p + ".source", diagnostics);
      if (source == null) {
         return null;
      }

      int startCodePoint = requireNonNegInt(raw, p + ".start.codePointOffset", diagnostics);
      int startLine = requireNonNegInt(raw, p + ".start.line", diagnostics);
      int startColumn = requireNonNegInt(raw, p + ".start.column", diagnostics);
      int endCodePoint = requireNonNegInt(raw, p + ".end.codePointOffset", diagnostics);
      int endLine = requireNonNegInt(raw, p + ".end.line", diagnostics);
      int endColumn = requireNonNegInt(raw, p + ".end.column", diagnostics);
      if (diagnostics.stream().anyMatch(ProjectGraphDiagnostic::isError)) {
         return null;
      }

      try {
         SourcePosition start = new SourcePosition(startCodePoint, startLine, startColumn);
         SourcePosition end = new SourcePosition(endCodePoint, endLine, endColumn);
         return new SourceSpan(source, start, end);
      } catch (IllegalArgumentException e) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", "invalid SourceSpan: " + e.getMessage()));
         return null;
      }
   }

   private static SymbolId constructSymbolId(RawSnapshotDocument.RawRecord raw, String field, List<ProjectGraphDiagnostic> diagnostics) {
      String value = raw.fields.get(field);
      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + field));
         return null;
      } else if (value.isBlank()) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", field + " must not be blank"));
         return null;
      } else {
         return safeConstruct(() -> new SymbolId(value), raw, "SIR-GRAPH-FORMAT-012", "invalid SymbolId: '" + value + "'", diagnostics);
      }
   }

   private static LoweredNodeId constructLoweredNodeId(RawSnapshotDocument.RawRecord raw, String field, List<ProjectGraphDiagnostic> diagnostics) {
      String value = raw.fields.get(field);
      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + field));
         return null;
      } else if (value.isBlank()) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", field + " must not be blank"));
         return null;
      } else {
         return safeConstruct(() -> new LoweredNodeId(value), raw, "SIR-GRAPH-FORMAT-012", "invalid LoweredNodeId: '" + value + "'", diagnostics);
      }
   }

   private static AstNodeId constructAstNodeId(RawSnapshotDocument.RawRecord raw, String field, List<ProjectGraphDiagnostic> diagnostics) {
      String value = raw.fields.get(field);
      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + field));
         return null;
      } else if (value.isBlank()) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", field + " must not be blank"));
         return null;
      } else {
         return safeConstruct(() -> new AstNodeId(value), raw, "SIR-GRAPH-FORMAT-012", "invalid AstNodeId: '" + value + "'", diagnostics);
      }
   }

   private static SourceId constructSourceId(RawSnapshotDocument.RawRecord raw, String field, List<ProjectGraphDiagnostic> diagnostics) {
      String value = raw.fields.get(field);
      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + field));
         return null;
      } else if (value.isBlank()) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", field + " must not be blank"));
         return null;
      } else {
         String violation = validateSourceIdCanonical(value);
         if (violation != null) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", field + " is not canonical (" + violation + "): " + value));
            return null;
         } else {
            return safeConstruct(() -> SourceId.of(value), raw, "SIR-GRAPH-FORMAT-012", "invalid SourceId: " + value, diagnostics);
         }
      }
   }

   private static Optional<SymbolId> constructOptionalSymbolId(
      RawSnapshotDocument.RawRecord raw, String presentField, String valueField, List<ProjectGraphDiagnostic> diagnostics
   ) {
      String present = raw.fields.get(presentField);
      String value = raw.fields.get(valueField);
      if (present == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + presentField));
         return null;
      }

      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + valueField));
         return null;
      }

      if ("0".equals(present)) {
         if (!value.isEmpty()) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", presentField + "=0 but " + valueField + " is non-empty: '" + value + "'"));
            return null;
         } else {
            return Optional.empty();
         }
      } else if ("1".equals(present)) {
         if (value.isBlank()) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", presentField + "=1 but " + valueField + " is blank"));
            return null;
         } else {
            SymbolId sid = safeConstruct(() -> new SymbolId(value), raw, "SIR-GRAPH-FORMAT-012", "invalid SymbolId in optional: '" + value + "'", diagnostics);
            return sid != null ? Optional.of(sid) : null;
         }
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", presentField + " must be '0' or '1' but is '" + present + "'"));
         return null;
      }
   }

   private static ArtifactRole constructRole(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      String family = raw.fields.get("role.family");
      String name = raw.fields.get("role.name");
      return lookupRole(raw, family, name, "role.family", "role.name", diagnostics);
   }

   private static ArtifactRole constructProvenanceRole(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics) {
      String family = raw.fields.get("provenance.role.family");
      String name = raw.fields.get("provenance.role.name");
      return lookupRole(raw, family, name, "provenance.role.family", "provenance.role.name", diagnostics);
   }

   private static ArtifactRole lookupRole(
      RawSnapshotDocument.RawRecord raw, String family, String name, String familyField, String nameField, List<ProjectGraphDiagnostic> diagnostics
   ) {
      if (family == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + familyField));
         return null;
      }

      if (name == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + nameField));
         return null;
      }

      if ("Declaration".equals(family)) {
         for (ArtifactRole.DeclarationRole dr : ArtifactRole.DeclarationRole.values()) {
            if (dr.name().equals(name)) {
               return dr;
            }
         }

         diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-009", "unknown DeclarationRole name: '" + name + "'"));
         return null;
      } else if ("Project".equals(family)) {
         for (ArtifactRole.ProjectRole pr : ArtifactRole.ProjectRole.values()) {
            if (pr.name().equals(name)) {
               return pr;
            }
         }

         diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-009", "unknown ProjectRole name: '" + name + "'"));
         return null;
      } else {
         diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-008", "unknown role.family: '" + family + "'"));
         return null;
      }
   }

   private static void validateFieldSet(RawSnapshotDocument.RawRecord raw, List<ProjectGraphDiagnostic> diagnostics, Set<String> expected) {
      Set<String> actual = raw.fields.keySet();

      for (String field : actual) {
         if (!expected.contains(field)) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-009", "unknown field: '" + field + "'"));
         }
      }

      for (String field : expected) {
         if (!actual.contains(field)) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + field));
         }
      }
   }

   private static void requireEquals(RawSnapshotDocument.RawRecord raw, String field, String expected, List<ProjectGraphDiagnostic> diagnostics) {
      String actual = raw.fields.get(field);
      if (actual != null) {
         if (!expected.equals(actual)) {
            diagnostics.add(formatError(raw, "SIR-GRAPH-COMPAT-010", field + " must be '" + expected + "' but is '" + actual + "'"));
         }
      }
   }

   private static int requireNonNegInt(RawSnapshotDocument.RawRecord raw, String field, List<ProjectGraphDiagnostic> diagnostics) {
      String value = raw.fields.get(field);
      if (value == null) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-011", "missing required field: " + field));
         return -1;
      }

      if (!isShortestDecimal(value)) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-007", field + " is not shortest decimal: '" + value + "'"));
         return -1;
      }

      try {
         return Integer.parseInt(value);
      } catch (NumberFormatException e) {
         diagnostics.add(formatError(raw, "SIR-GRAPH-FORMAT-012", field + " is not a valid integer: '" + value + "'"));
         return -1;
      }
   }

   private static boolean isShortestDecimal(String value) {
      if (value != null && !value.isEmpty()) {
         if (value.length() > 1 && value.charAt(0) == '0') {
            return false;
         }

         for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean isAsciiSorted(Set<String> keys) {
      String previous = null;

      for (String key : keys) {
         if (previous != null && previous.compareTo(key) >= 0) {
            return false;
         }

         previous = key;
      }

      return true;
   }

   private static String validateSourceIdCanonical(String value) {
      if (value.indexOf(92) >= 0) {
         return "must not contain backslash";
      }

      if (value.startsWith("/")) {
         return "must not be absolute";
      }

      if (value.length() >= 2 && value.charAt(1) == ':' && Character.isLetter(value.charAt(0))) {
         return "must not contain drive letter";
      }

      String[] segments = value.split("/", -1);

      for (String segment : segments) {
         if (segment.isEmpty()) {
            return "must not contain empty segment";
         }

         if ("..".equals(segment) || ".".equals(segment)) {
            return "must not contain '.' or '..' segment";
         }
      }

      return null;
   }

   private static String validateFilePathCanonical(String path) {
      if (path.startsWith("/")) {
         return "path must not be absolute";
      }

      if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
         return "path must not contain drive letter";
      }

      if (path.indexOf(92) >= 0) {
         return "path must not contain backslash";
      }

      String[] segments = path.split("/", -1);

      for (String segment : segments) {
         if (segment.isEmpty()) {
            return "path must not contain empty segment";
         }

         if ("..".equals(segment)) {
            return "path must not contain parent reference";
         }

         if (".".equals(segment)) {
            return "path must not contain current directory reference";
         }
      }

      return null;
   }

   private static <T> T safeConstruct(
      Supplier<T> supplier, RawSnapshotDocument.RawRecord raw, String errorCode, String message, List<ProjectGraphDiagnostic> diagnostics
   ) {
      try {
         return supplier.get();
      } catch (Exception e) {
         diagnostics.add(formatError(raw, errorCode, message + ": " + e.getMessage()));
         return null;
      }
   }

   private static <T extends Enum<T>> T safeEnum(Class<T> enumClass, String name) {
      if (name == null) {
         return null;
      }

      for (T value : enumClass.getEnumConstants()) {
         if (value.name().equals(name)) {
            return value;
         }
      }

      return null;
   }

   private static ProjectGraphDiagnostic formatError(RawSnapshotDocument.RawRecord raw, String code, String message) {
      return ProjectGraphDiagnostic.error(code, message + "; record index " + raw.recordIndex + ", byte offset " + raw.byteOffset);
   }

   private static final class ConstructionFailure extends RuntimeException {
      final ProjectGraphDiagnostic diagnostic;

      ConstructionFailure(ProjectGraphDiagnostic diagnostic) {
         super(diagnostic.message());
         this.diagnostic = diagnostic;
      }
   }

   public static final class LoadedGraph {
      public final GraphVersion version;
      public final List<ProjectGraphNode> nodes;
      public final List<ProjectGraphEdge> edges;
      public final String canonicalDigest;

      LoadedGraph(GraphVersion version, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges, String canonicalDigest) {
         this.version = version;
         this.nodes = List.copyOf(nodes);
         this.edges = List.copyOf(edges);
         this.canonicalDigest = canonicalDigest;
      }
   }
}
