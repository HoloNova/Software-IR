package io.kcg.sir.application.internal.bundle;

import io.kcg.sir.application.api.ChangeExecutionBaselineFormatVersion;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.SirCompilation;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization;
import io.kcg.sir.projectgraph.api.ProjectGraphSerializer;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization.Failure;
import io.kcg.sir.projectgraph.api.ProjectGraphSerialization.Success;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BaselineBuilder {
   private BaselineBuilder() {
   }

   public static BaselineBundle build(
      SirCompilation.CompilationSnapshot snapshot, ProjectGraph graph, byte[] sourceBytes, byte[] snapshotBytes, SourceId sourceId, Path outputRoot
   ) {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(graph, "graph");
      Objects.requireNonNull(sourceBytes, "sourceBytes");
      Objects.requireNonNull(snapshotBytes, "snapshotBytes");
      Objects.requireNonNull(sourceId, "sourceId");
      Objects.requireNonNull(outputRoot, "outputRoot");
      if (outputRoot.isAbsolute() && outputRoot.equals(outputRoot.normalize())) {
         List<BaselineManifestEntry> manifest = buildManifest(snapshot.generatedFiles());
         String manifestDigest = BaselineDescriptorCodec.computeManifestDigest(manifest);
         BaselineDescriptor descriptor = new BaselineDescriptor(
            ChangeExecutionBaselineFormatVersion.V1,
            outputRoot,
            sourceId,
            sourceBytes.length,
            Sha256.hexDigest(sourceBytes),
            graph.version(),
            graph.canonicalDigest(),
            ProjectGraphCanonicalFormatVersion.V1,
            snapshotBytes.length,
            Sha256.hexDigest(snapshotBytes),
            snapshot.loweredModel().targetId(),
            snapshot.loweredModel().irVersion(),
            manifest,
            manifestDigest
         );
         return BaselineBundle.materialize(descriptor, sourceBytes, snapshotBytes);
      } else {
         throw new IllegalArgumentException("outputRoot must be a normalized absolute path: " + outputRoot);
      }
   }

   public static List<BaselineManifestEntry> buildManifest(List<GeneratedFile> generatedFiles) {
      Objects.requireNonNull(generatedFiles, "generatedFiles");
      List<BaselineManifestEntry> entries = new ArrayList<>(generatedFiles.size());

      for (GeneratedFile gf : generatedFiles) {
         byte[] contentBytes = gf.content().getBytes(StandardCharsets.UTF_8);
         entries.add(new BaselineManifestEntry(gf.relativePath(), contentBytes.length, Sha256.hexDigest(contentBytes), gf.artifactId(), gf.symbolId()));
      }

      entries.sort(Comparator.comparing(BaselineManifestEntry::relativePath));
      return entries;
   }

   public static byte[] serializeGraph(ProjectGraph graph) {
      Objects.requireNonNull(graph, "graph");
      ProjectGraphSerialization serialization = new ProjectGraphSerializer().serialize(graph, ProjectGraphCanonicalFormatVersion.V1);
      return serialization instanceof Failure ? null : ((Success)serialization).document().bytes();
   }

   public static ChangeBaseRevision toBaseRevision(BaselineDescriptor descriptor) {
      return descriptor.toBaseRevision();
   }
}
