package io.kcg.sir.projectgraph.api;

import io.kcg.sir.projectgraph.internal.RawSnapshotDocument;
import io.kcg.sir.projectgraph.internal.SnapshotDecoder;
import io.kcg.sir.projectgraph.internal.SnapshotEncoder;
import io.kcg.sir.projectgraph.internal.SnapshotLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ProjectGraphLoader {
   public ProjectGraphAnalysis load(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes");
      byte[] copy = (byte[])bytes.clone();
      return this.loadInternal(copy);
   }

   public ProjectGraphAnalysis load(CanonicalProjectGraphDocument document) {
      Objects.requireNonNull(document, "document");
      return this.loadInternal(document.bytes());
   }

   private ProjectGraphAnalysis loadInternal(byte[] bytes) {
      List<ProjectGraphDiagnostic> diagnostics = new ArrayList<>();
      RawSnapshotDocument raw = SnapshotDecoder.decode(bytes, diagnostics);
      if (raw == null) {
         return fail(diagnostics);
      }

      SnapshotLoader.LoadedGraph loaded = SnapshotLoader.load(raw, diagnostics);
      if (loaded == null) {
         return fail(diagnostics);
      }

      ProjectGraph graph;
      try {
         graph = new ProjectGraph(loaded.version, loaded.nodes, loaded.edges, loaded.canonicalDigest);
      } catch (Exception e) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-INTEGRITY-002", "ProjectGraph construction failed: " + e.getMessage()));
         return fail(diagnostics);
      }

      List<ProjectGraphDiagnostic> reserializeDiags = new ArrayList<>();
      SnapshotEncoder.EncodeResult reserialized = SnapshotEncoder.encode(graph, ProjectGraphCanonicalFormatVersion.V1, reserializeDiags);
      if (reserialized == null) {
         String firstReason = reserializeDiags.isEmpty()
            ? "encoder returned null without diagnostics"
            : reserializeDiags.get(0).code() + ": " + reserializeDiags.get(0).message();
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-INTEGRITY-003", "re-serialization failed after load: " + firstReason));
         return fail(diagnostics);
      } else if (!Arrays.equals(bytes, reserialized.documentBytes)) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-INTEGRITY-003",
               "loaded graph does not re-serialize to the same bytes; this indicates an information loss during decode or a non-canonical input (extra whitespace, non-shortest decimal, etc.)"
            )
         );
         return fail(diagnostics);
      } else {
         return new ProjectGraphAnalysis.Success(graph, diagnostics);
      }
   }

   private static ProjectGraphAnalysis.Failure fail(List<ProjectGraphDiagnostic> diagnostics) {
      if (diagnostics.stream().noneMatch(ProjectGraphDiagnostic::isError)) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-012", "load failed without a specific diagnostic"));
      }

      return new ProjectGraphAnalysis.Failure(diagnostics);
   }
}
