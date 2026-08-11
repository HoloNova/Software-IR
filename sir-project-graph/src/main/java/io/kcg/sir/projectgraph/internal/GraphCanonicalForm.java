package io.kcg.sir.projectgraph.internal;

import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphProvenance;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalizer;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.source.SourceSpan;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class GraphCanonicalForm {
   static final Comparator<ProjectGraphNode> NODE_COMPARATOR = ProjectGraphCanonicalizer.nodeComparator();

   private GraphCanonicalForm() {
   }

   public static String digest(GraphVersion version, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
      byte[] bytes = canonicalBytes(version, nodes, edges);

      try {
         byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
         return toHexLower(hash);
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 algorithm not available", e);
      }
   }

   public static byte[] canonicalBytes(GraphVersion version, List<ProjectGraphNode> nodes, List<ProjectGraphEdge> edges) {
      List<ProjectGraphNode> sortedNodes = new ArrayList<>(nodes);
      sortedNodes.sort(ProjectGraphCanonicalizer.nodeComparator());
      List<ProjectGraphEdge> sortedEdges = new ArrayList<>(edges);
      sortedEdges.sort(Comparator.comparing(ProjectGraphEdge::id));
      CanonicalForm form = new CanonicalForm();
      form.raw("VERSION").field(version.name());

      for (ProjectGraphNode node : sortedNodes) {
         emitNode(form, node);
      }

      for (ProjectGraphEdge edge : sortedEdges) {
         emitEdge(form, edge);
      }

      return form.toByteArray();
   }

   private static void emitNode(CanonicalForm form, ProjectGraphNode node) {
      form.raw("NODE");
      form.field(nodeKind(node));
      form.field(node.id().canonicalKey());
      switch (node) {
         case ProjectGraphNode.Project p:
            break;
         case ProjectGraphNode.SemanticDeclaration s:
            form.field(s.kind().name());
            break;
         case ProjectGraphNode.LoweredDeclaration l:
            form.field(l.sourceSymbol().value());
            break;
         case ProjectGraphNode.Artifact a:
            break;
         case ProjectGraphNode.ProjectFile var8:
            break;
         default:
            throw new MatchException(null, null);
      }

      emitProvenance(form, node.provenance());
   }

   private static void emitEdge(CanonicalForm form, ProjectGraphEdge edge) {
      form.raw("EDGE");
      form.field(edge.kind().name());
      form.field(edge.source().canonicalKey());
      form.field(edge.target().canonicalKey());
   }

   private static void emitProvenance(CanonicalForm form, GraphProvenance provenance) {
      form.field(provenanceKind(provenance));
      form.field(provenance.sourceId().value());
      switch (provenance) {
         case GraphProvenance.ProjectProvenance p:
            break;
         case GraphProvenance.SemanticProvenance p:
            form.field(p.sourceNodeId().value());
            emitSpan(form, p.span());
            break;
         case GraphProvenance.LoweredProvenance p:
            emitOrigin(form, p.origin());
            break;
         case GraphProvenance.ArtifactProvenance p:
            emitOrigin(form, p.origin());
            form.field(CanonicalForm.optional(p.ownerSymbol().map(sym -> sym.value())));
            form.field(roleKey(p.role()));
            form.field(p.qualifiedName());
            break;
         case GraphProvenance.FileProvenance p:
            form.field(p.artifactId().value());
            form.field(CanonicalForm.optional(p.ownerSymbol().map(sym -> sym.value())));
            form.field(Long.toString(p.byteCount()));
            form.field(p.sha256Hex());
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private static void emitOrigin(CanonicalForm form, LoweredOrigin origin) {
      form.field(origin.sourceNodeId().value());
      form.field(CanonicalForm.optional(origin.ownerSymbol().map(sym -> sym.value())));
      emitSpan(form, origin.span());
   }

   private static void emitSpan(CanonicalForm form, SourceSpan span) {
      form.field(span.source().value());
      form.field(Integer.toString(span.start().codePointOffset()));
      form.field(Integer.toString(span.start().line()));
      form.field(Integer.toString(span.start().column()));
      form.field(Integer.toString(span.end().codePointOffset()));
      form.field(Integer.toString(span.end().line()));
      form.field(Integer.toString(span.end().column()));
   }

   private static String nodeKind(ProjectGraphNode node) {
      return switch (node) {
         case ProjectGraphNode.Project p -> "Project";
         case ProjectGraphNode.SemanticDeclaration s -> "SemanticDeclaration";
         case ProjectGraphNode.LoweredDeclaration l -> "LoweredDeclaration";
         case ProjectGraphNode.Artifact a -> "Artifact";
         case ProjectGraphNode.ProjectFile f -> "ProjectFile";
         default -> throw new MatchException(null, null);
      };
   }

   private static String provenanceKind(GraphProvenance provenance) {
      return switch (provenance) {
         case GraphProvenance.ProjectProvenance p -> "ProjectProvenance";
         case GraphProvenance.SemanticProvenance p -> "SemanticProvenance";
         case GraphProvenance.LoweredProvenance p -> "LoweredProvenance";
         case GraphProvenance.ArtifactProvenance p -> "ArtifactProvenance";
         case GraphProvenance.FileProvenance p -> "FileProvenance";
         default -> throw new MatchException(null, null);
      };
   }

   private static String roleKey(ArtifactRole role) {
      if (role instanceof ArtifactRole.DeclarationRole dr) {
         return "decl:" + dr.name();
      } else {
         return role instanceof ArtifactRole.ProjectRole pr ? "project:" + pr.name() : "unknown";
      }
   }

   private static String toHexLower(byte[] bytes) {
      StringBuilder sb = new StringBuilder(bytes.length * 2);

      for (byte b : bytes) {
         int v = b & 255;
         if (v < 16) {
            sb.append('0');
         }

         sb.append(Integer.toHexString(v));
      }

      return sb.toString().toLowerCase(Locale.ROOT);
   }
}
