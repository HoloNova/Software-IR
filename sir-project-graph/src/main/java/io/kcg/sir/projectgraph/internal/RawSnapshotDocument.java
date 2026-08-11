package io.kcg.sir.projectgraph.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class RawSnapshotDocument {
   public final RawSnapshotDocument.RawHeader header;
   public final RawSnapshotDocument.RawGraph graph;
   public final List<RawSnapshotDocument.RawRecord> nodes;
   public final List<RawSnapshotDocument.RawRecord> edges;
   public final RawSnapshotDocument.RawRecord endRecord;

   RawSnapshotDocument(
      RawSnapshotDocument.RawHeader header,
      RawSnapshotDocument.RawGraph graph,
      List<RawSnapshotDocument.RawRecord> nodes,
      List<RawSnapshotDocument.RawRecord> edges,
      RawSnapshotDocument.RawRecord endRecord
   ) {
      this.header = Objects.requireNonNull(header, "header");
      this.graph = Objects.requireNonNull(graph, "graph");
      this.nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
      this.edges = List.copyOf(Objects.requireNonNull(edges, "edges"));
      this.endRecord = endRecord;
   }

   static Map<String, String> newFieldMap() {
      return new LinkedHashMap<>();
   }

   static Optional<String> getOptional(Map<String, String> fields, String prefix) {
      String present = fields.get(prefix + ".present");
      String value = fields.get(prefix + ".value");
      return "1".equals(present) ? Optional.of(value != null ? value : "") : Optional.empty();
   }

   public static final class RawGraph {
      public final long byteOffset;
      public final int recordIndex;
      public final Map<String, String> fields;

      RawGraph(long byteOffset, int recordIndex, Map<String, String> fields) {
         this.byteOffset = byteOffset;
         this.recordIndex = recordIndex;
         this.fields = fields;
      }
   }

   public static final class RawHeader {
      public final long byteOffset;
      public final Map<String, String> fields;

      RawHeader(long byteOffset, Map<String, String> fields) {
         this.byteOffset = byteOffset;
         this.fields = fields;
      }
   }

   public static final class RawRecord {
      public final String tag;
      public final long byteOffset;
      public final int recordIndex;
      public final Map<String, String> fields;

      RawRecord(String tag, long byteOffset, int recordIndex, Map<String, String> fields) {
         this.tag = tag;
         this.byteOffset = byteOffset;
         this.recordIndex = recordIndex;
         this.fields = fields;
      }
   }
}
