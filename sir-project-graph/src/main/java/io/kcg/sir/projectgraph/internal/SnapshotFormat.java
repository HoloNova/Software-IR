package io.kcg.sir.projectgraph.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class SnapshotFormat {
   static final byte[] MAGIC = "KCG-PSG-CANONICAL\n".getBytes(StandardCharsets.US_ASCII);
   static final String TAG_HEADER = "HEADER";
   static final String TAG_PAYLOAD = "PAYLOAD";
   static final String TAG_GRAPH = "GRAPH";
   static final String TAG_NODE = "NODE";
   static final String TAG_EDGE = "EDGE";
   static final String TAG_END = "END";
   static final String FIELD_FORMAT_VERSION = "formatVersion";
   static final String FIELD_PAYLOAD_BYTE_COUNT = "payloadByteCount";
   static final String FIELD_PAYLOAD_SHA256_HEX = "payloadSha256Hex";
   static final String FIELD_CANONICAL_DIGEST = "canonicalDigest";
   static final String FIELD_EDGE_COUNT = "edgeCount";
   static final String FIELD_GRAPH_VERSION = "graphVersion";
   static final String FIELD_NODE_COUNT = "nodeCount";
   static final String FIELD_DISPLAY_NAME = "displayName";
   static final String FIELD_ID_KIND = "id.kind";
   static final String FIELD_ID_VALUE = "id.value";
   static final String FIELD_NODE_KIND = "nodeKind";
   static final String FIELD_PROVENANCE_KIND = "provenance.kind";
   static final String FIELD_PROVENANCE_SOURCE_ID = "provenance.sourceId";
   static final String FIELD_PROVENANCE_SOURCE_NODE_ID = "provenance.sourceNodeId";
   static final String FIELD_SYMBOL_KIND = "symbolKind";
   static final String FIELD_SOURCE_SYMBOL = "sourceSymbol";
   static final String FIELD_QUALIFIED_NAME = "qualifiedName";
   static final String FIELD_ROLE_FAMILY = "role.family";
   static final String FIELD_ROLE_NAME = "role.name";
   static final String FIELD_PROVENANCE_QUALIFIED_NAME = "provenance.qualifiedName";
   static final String FIELD_PROVENANCE_ROLE_FAMILY = "provenance.role.family";
   static final String FIELD_PROVENANCE_ROLE_NAME = "provenance.role.name";
   static final String FIELD_PROVENANCE_OWNER_SYMBOL_PRESENT = "provenance.ownerSymbol.present";
   static final String FIELD_PROVENANCE_OWNER_SYMBOL_VALUE = "provenance.ownerSymbol.value";
   static final String FIELD_ARTIFACT_ID = "artifactId";
   static final String FIELD_BYTE_COUNT = "byteCount";
   static final String FIELD_SHA256_HEX = "sha256Hex";
   static final String FIELD_OWNER_SYMBOL_PRESENT = "ownerSymbol.present";
   static final String FIELD_OWNER_SYMBOL_VALUE = "ownerSymbol.value";
   static final String FIELD_SPAN_SOURCE = "span.source";
   static final String FIELD_SPAN_START_CODE_POINT_OFFSET = "span.start.codePointOffset";
   static final String FIELD_SPAN_START_LINE = "span.start.line";
   static final String FIELD_SPAN_START_COLUMN = "span.start.column";
   static final String FIELD_SPAN_END_CODE_POINT_OFFSET = "span.end.codePointOffset";
   static final String FIELD_SPAN_END_LINE = "span.end.line";
   static final String FIELD_SPAN_END_COLUMN = "span.end.column";
   static final String FIELD_ORIGIN_OWNER_SYMBOL_PRESENT = "origin.ownerSymbol.present";
   static final String FIELD_ORIGIN_OWNER_SYMBOL_VALUE = "origin.ownerSymbol.value";
   static final String FIELD_ORIGIN_SOURCE_NODE_ID = "origin.sourceNodeId";
   static final String FIELD_ORIGIN_SPAN_SOURCE = "origin.span.source";
   static final String FIELD_ORIGIN_SPAN_START_CODE_POINT_OFFSET = "origin.span.start.codePointOffset";
   static final String FIELD_ORIGIN_SPAN_START_LINE = "origin.span.start.line";
   static final String FIELD_ORIGIN_SPAN_START_COLUMN = "origin.span.start.column";
   static final String FIELD_ORIGIN_SPAN_END_CODE_POINT_OFFSET = "origin.span.end.codePointOffset";
   static final String FIELD_ORIGIN_SPAN_END_LINE = "origin.span.end.line";
   static final String FIELD_ORIGIN_SPAN_END_COLUMN = "origin.span.end.column";
   static final String FIELD_EDGE_KIND = "kind";
   static final String FIELD_SOURCE_ID_KIND = "source.id.kind";
   static final String FIELD_SOURCE_ID_VALUE = "source.id.value";
   static final String FIELD_TARGET_ID_KIND = "target.id.kind";
   static final String FIELD_TARGET_ID_VALUE = "target.id.value";
   static final String ID_KIND_PROJECT = "Project";
   static final String ID_KIND_SEMANTIC = "Semantic";
   static final String ID_KIND_LOWERED = "Lowered";
   static final String ID_KIND_FILE = "File";
   static final String NODE_KIND_PROJECT = "Project";
   static final String NODE_KIND_SEMANTIC_DECLARATION = "SemanticDeclaration";
   static final String NODE_KIND_LOWERED_DECLARATION = "LoweredDeclaration";
   static final String NODE_KIND_ARTIFACT = "Artifact";
   static final String NODE_KIND_PROJECT_FILE = "ProjectFile";
   static final String PROV_KIND_PROJECT = "ProjectProvenance";
   static final String PROV_KIND_SEMANTIC = "SemanticProvenance";
   static final String PROV_KIND_LOWERED = "LoweredProvenance";
   static final String PROV_KIND_ARTIFACT = "ArtifactProvenance";
   static final String PROV_KIND_FILE = "FileProvenance";
   static final String ROLE_FAMILY_DECLARATION = "Declaration";
   static final String ROLE_FAMILY_PROJECT = "Project";
   static final String FORMAT_VERSION_V1 = "1";
   static final String GRAPH_VERSION_V0_1 = "V0_1";
   static final String OPTIONAL_PRESENT_0 = "0";
   static final String OPTIONAL_PRESENT_1 = "1";
   static final int MAX_FIELDS_PER_RECORD = 64;
   static final int MAX_PAYLOAD_RECORDS = 100000;
   static final long MAX_PAYLOAD_BYTE_COUNT = 67108864L;

   private SnapshotFormat() {
   }

   static List<String> expectedHeaderFields() {
      return List.of("formatVersion", "payloadByteCount", "payloadSha256Hex");
   }

   static List<String> expectedGraphFields() {
      return List.of("canonicalDigest", "edgeCount", "graphVersion", "nodeCount");
   }
}
