package io.kcg.sir.projectgraph.internal;

import io.kcg.sir.projectgraph.api.ProjectGraphDiagnostic;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SnapshotDecoder {
   private SnapshotDecoder() {
   }

   public static RawSnapshotDocument decode(byte[] bytes, List<ProjectGraphDiagnostic> diagnostics) {
      SnapshotDecoder.DecoderState state = new SnapshotDecoder.DecoderState(bytes);
      if (bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191) {
         diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-003", "byte order mark (BOM) detected at byte 0"));
         return null;
      } else {
         int magicRegionEnd = Math.min(bytes.length, SnapshotFormat.MAGIC.length);
         int crInMagic = findCr(bytes, 0, magicRegionEnd);
         if (crInMagic >= 0) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-FORMAT-004", "carriage return (CR) detected in magic region at byte " + crInMagic + "; structural ASCII tokens must use LF only"
               )
            );
            return null;
         } else if (!state.matchMagic()) {
            diagnostics.add(
               state.formatError(
                  "SIR-GRAPH-FORMAT-001", "invalid or missing magic; expected '" + new String(SnapshotFormat.MAGIC, StandardCharsets.US_ASCII).trim() + "'"
               )
            );
            return null;
         } else {
            RawSnapshotDocument.RawHeader header = readHeader(state, diagnostics);
            if (header == null) {
               return null;
            } else if (!validateFixedRecordFields("HEADER", header.byteOffset, -1, header.fields, SnapshotFormat.expectedHeaderFields(), diagnostics)) {
               return null;
            } else {
               String formatVersion = header.fields.get("formatVersion");
               if (formatVersion == null) {
                  diagnostics.add(state.formatError("SIR-GRAPH-FORMAT-011", "HEADER missing required field: formatVersion"));
                  return null;
               } else if (!"1".equals(formatVersion)) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-COMPAT-001", "unsupported format version: " + formatVersion + " (only '1' is supported); byte offset " + header.byteOffset
                     )
                  );
                  return null;
               } else {
                  String payloadByteCountStr = header.fields.get("payloadByteCount");
                  if (payloadByteCountStr == null) {
                     diagnostics.add(state.formatError("SIR-GRAPH-FORMAT-011", "HEADER missing required field: payloadByteCount"));
                     return null;
                  } else {
                     long payloadByteCount = parseNonNegativeLong(payloadByteCountStr);
                     if (payloadByteCount < 0L) {
                        diagnostics.add(
                           ProjectGraphDiagnostic.error(
                              "SIR-GRAPH-FORMAT-012",
                              "HEADER payloadByteCount is not a non-negative decimal: '" + payloadByteCountStr + "'; byte offset " + header.byteOffset
                           )
                        );
                        return null;
                     } else if (!isShortestDecimal(payloadByteCountStr)) {
                        diagnostics.add(
                           ProjectGraphDiagnostic.error(
                              "SIR-GRAPH-FORMAT-007",
                              "HEADER payloadByteCount is not shortest decimal: '" + payloadByteCountStr + "'; byte offset " + header.byteOffset
                           )
                        );
                        return null;
                     } else if (payloadByteCount > 67108864L) {
                        diagnostics.add(
                           ProjectGraphDiagnostic.error(
                              "SIR-GRAPH-FORMAT-016",
                              "HEADER payloadByteCount exceeds maximum 67108864: " + payloadByteCount + "; byte offset " + header.byteOffset
                           )
                        );
                        return null;
                     } else {
                        String payloadSha256Hex = header.fields.get("payloadSha256Hex");
                        if (payloadSha256Hex == null) {
                           diagnostics.add(state.formatError("SIR-GRAPH-FORMAT-011", "HEADER missing required field: payloadSha256Hex"));
                           return null;
                        } else if (!Sha256Helper.isLowerCaseHex64(payloadSha256Hex)) {
                           diagnostics.add(
                              ProjectGraphDiagnostic.error(
                                 "SIR-GRAPH-FORMAT-012",
                                 "HEADER payloadSha256Hex is not a 64-character lowercase hex string: '"
                                    + payloadSha256Hex
                                    + "'; byte offset "
                                    + header.byteOffset
                              )
                           );
                           return null;
                        } else {
                           int payloadMarkerEnd = Math.min(bytes.length, state.position + "PAYLOAD".length() + 1);
                           int crInPayloadMarker = findCr(bytes, state.position, payloadMarkerEnd);
                           if (crInPayloadMarker >= 0) {
                              diagnostics.add(
                                 ProjectGraphDiagnostic.error(
                                    "SIR-GRAPH-FORMAT-004",
                                    "carriage return (CR) detected in PAYLOAD marker region at byte "
                                       + crInPayloadMarker
                                       + "; structural ASCII tokens must use LF only"
                                 )
                              );
                              return null;
                           } else if (!state.matchAsciiLine("PAYLOAD")) {
                              diagnostics.add(state.formatError("SIR-GRAPH-FORMAT-015", "expected PAYLOAD marker after HEADER"));
                              return null;
                           } else {
                              int payloadStart = state.position;
                              int payloadEnd = payloadStart + (int)payloadByteCount;
                              if (payloadEnd > bytes.length) {
                                 diagnostics.add(
                                    ProjectGraphDiagnostic.error(
                                       "SIR-GRAPH-FORMAT-006",
                                       "payload truncated: declared "
                                          + payloadByteCount
                                          + " bytes but only "
                                          + (bytes.length - payloadStart)
                                          + " available; byte offset "
                                          + payloadStart
                                    )
                                 );
                                 return null;
                              } else {
                                 byte[] payload = new byte[(int)payloadByteCount];
                                 System.arraycopy(bytes, payloadStart, payload, 0, (int)payloadByteCount);
                                 state.position = payloadEnd;
                                 if (state.position != bytes.length) {
                                    diagnostics.add(
                                       ProjectGraphDiagnostic.error(
                                          "SIR-GRAPH-FORMAT-005",
                                          "unexpected trailing bytes after payload: "
                                             + (bytes.length - state.position)
                                             + " bytes at byte offset "
                                             + state.position
                                       )
                                    );
                                    return null;
                                 } else {
                                    String actualPayloadSha256 = Sha256Helper.hexDigest(payload);
                                    if (!actualPayloadSha256.equals(payloadSha256Hex)) {
                                       diagnostics.add(
                                          ProjectGraphDiagnostic.error(
                                             "SIR-GRAPH-INTEGRITY-001",
                                             "payload SHA-256 mismatch: HEADER declares " + payloadSha256Hex + " but actual is " + actualPayloadSha256
                                          )
                                       );
                                       return null;
                                    } else {
                                       return parsePayload(payload, payloadStart, header, diagnostics);
                                    }
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static RawSnapshotDocument.RawHeader readHeader(SnapshotDecoder.DecoderState state, List<ProjectGraphDiagnostic> diagnostics) {
      long headerOffset = state.position;
      int tagEnd = Math.min(state.bytes.length, state.position + "HEADER".length() + 1);
      int crInHeaderTag = findCr(state.bytes, state.position, tagEnd);
      if (crInHeaderTag >= 0) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-FORMAT-004",
               "carriage return (CR) detected in HEADER tag region at byte " + crInHeaderTag + "; structural ASCII tokens must use LF only"
            )
         );
         return null;
      } else if (!state.matchAsciiLine("HEADER")) {
         diagnostics.add(state.formatError("SIR-GRAPH-FORMAT-014", "expected HEADER record tag after magic"));
         return null;
      } else {
         long fieldCountOffset = state.position;
         String fieldCountStr = state.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
         if (fieldCountStr == null) {
            return null;
         } else if (!isShortestDecimal(fieldCountStr)) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-FORMAT-007", "HEADER field count is not shortest decimal: '" + fieldCountStr + "'; byte offset " + fieldCountOffset
               )
            );
            return null;
         } else {
            int fieldCount = parseNonNegativeInt(fieldCountStr);
            if (fieldCount < 0) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-FORMAT-012", "HEADER field count is not a non-negative decimal: '" + fieldCountStr + "'; byte offset " + fieldCountOffset
                  )
               );
               return null;
            } else if (fieldCount > 64) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-FORMAT-016", "HEADER field count exceeds maximum 64: " + fieldCount + "; byte offset " + fieldCountOffset
                  )
               );
               return null;
            } else {
               Map<String, String> fields = readFields(state, fieldCount, diagnostics);
               return fields == null ? null : new RawSnapshotDocument.RawHeader(headerOffset, fields);
            }
         }
      }
   }

   private static RawSnapshotDocument parsePayload(
      byte[] payload, int payloadStart, RawSnapshotDocument.RawHeader header, List<ProjectGraphDiagnostic> diagnostics
   ) {
      SnapshotDecoder.PayloadState ps = new SnapshotDecoder.PayloadState(payload, payloadStart);
      RawSnapshotDocument.RawGraph graph = readGraphRecord(ps, diagnostics);
      if (graph == null) {
         return null;
      }

      if (!validateFixedRecordFields("GRAPH", graph.byteOffset, graph.recordIndex, graph.fields, SnapshotFormat.expectedGraphFields(), diagnostics)) {
         return null;
      }

      String graphVersion = graph.fields.get("graphVersion");
      if (graphVersion == null) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-011", graph.recordIndex, "GRAPH missing required field: graphVersion"));
         return null;
      }

      if (!"V0_1".equals(graphVersion)) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-COMPAT-002",
               "unsupported graph version: "
                  + graphVersion
                  + " (only V0_1 is supported); record index "
                  + graph.recordIndex
                  + ", byte offset "
                  + graph.byteOffset
            )
         );
         return null;
      }

      String canonicalDigest = graph.fields.get("canonicalDigest");
      if (canonicalDigest == null) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-011", graph.recordIndex, "GRAPH missing required field: canonicalDigest"));
         return null;
      }

      if (!Sha256Helper.isLowerCaseHex64(canonicalDigest)) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-FORMAT-012",
               "GRAPH canonicalDigest is not a 64-character lowercase hex string; record index " + graph.recordIndex + ", byte offset " + graph.byteOffset
            )
         );
         return null;
      }

      String nodeCountStr = graph.fields.get("nodeCount");
      if (nodeCountStr == null) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-011", graph.recordIndex, "GRAPH missing required field: nodeCount"));
         return null;
      }

      if (!isShortestDecimal(nodeCountStr)) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-007", graph.recordIndex, "GRAPH nodeCount is not shortest decimal: '" + nodeCountStr + "'"));
         return null;
      }

      int nodeCount = parseNonNegativeInt(nodeCountStr);
      if (nodeCount < 0) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-012", graph.recordIndex, "GRAPH nodeCount is not a non-negative decimal: '" + nodeCountStr + "'"));
         return null;
      }

      String edgeCountStr = graph.fields.get("edgeCount");
      if (edgeCountStr == null) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-011", graph.recordIndex, "GRAPH missing required field: edgeCount"));
         return null;
      }

      if (!isShortestDecimal(edgeCountStr)) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-007", graph.recordIndex, "GRAPH edgeCount is not shortest decimal: '" + edgeCountStr + "'"));
         return null;
      }

      int edgeCount = parseNonNegativeInt(edgeCountStr);
      if (edgeCount < 0) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-012", graph.recordIndex, "GRAPH edgeCount is not a non-negative decimal: '" + edgeCountStr + "'"));
         return null;
      }

      List<RawSnapshotDocument.RawRecord> nodes = new ArrayList<>();
      List<RawSnapshotDocument.RawRecord> edges = new ArrayList<>();
      RawSnapshotDocument.RawRecord endRecord = null;
      boolean endSeen = false;

      while (ps.position < payload.length) {
         if (endSeen) {
            diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-005", ps.recordIndex, "unexpected records after END record"));
            return null;
         }

         long recordOffset = ps.position;
         String tag = ps.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
         if (tag == null) {
            return null;
         }

         if ("NODE".equals(tag)) {
            RawSnapshotDocument.RawRecord rec = readRecord(ps, tag, recordOffset, diagnostics);
            if (rec == null) {
               return null;
            }

            nodes.add(rec);
         } else if ("EDGE".equals(tag)) {
            RawSnapshotDocument.RawRecord rec = readRecord(ps, tag, recordOffset, diagnostics);
            if (rec == null) {
               return null;
            }

            edges.add(rec);
         } else {
            if (!"END".equals(tag)) {
               if ("GRAPH".equals(tag)) {
                  diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-008", ps.recordIndex, "duplicate GRAPH record; only one GRAPH is allowed"));
                  return null;
               }

               diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-008", ps.recordIndex, "unknown record tag: '" + tag + "'"));
               return null;
            }

            RawSnapshotDocument.RawRecord rec = readRecord(ps, tag, recordOffset, diagnostics);
            if (rec == null) {
               return null;
            }

            if (!rec.fields.isEmpty()) {
               diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-017", rec.recordIndex, "END record must have 0 fields but has " + rec.fields.size()));
               return null;
            }

            endRecord = rec;
            endSeen = true;
         }

         if (ps.recordIndex > 100000) {
            diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-016", ps.recordIndex, "payload record count exceeds maximum 100000"));
            return null;
         }
      }

      if (!endSeen) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-006", ps.recordIndex, "payload truncated: missing END record"));
         return null;
      } else if (nodes.size() != nodeCount) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-INTEGRITY-004", "GRAPH nodeCount (" + nodeCount + ") does not match actual NODE records (" + nodes.size() + ")"
            )
         );
         return null;
      } else if (edges.size() != edgeCount) {
         diagnostics.add(
            ProjectGraphDiagnostic.error(
               "SIR-GRAPH-INTEGRITY-004", "GRAPH edgeCount (" + edgeCount + ") does not match actual EDGE records (" + edges.size() + ")"
            )
         );
         return null;
      } else {
         return new RawSnapshotDocument(header, graph, nodes, edges, endRecord);
      }
   }

   private static RawSnapshotDocument.RawGraph readGraphRecord(SnapshotDecoder.PayloadState ps, List<ProjectGraphDiagnostic> diagnostics) {
      long recordOffset = ps.position;
      String tag = ps.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
      if (tag == null) {
         return null;
      }

      if (!"GRAPH".equals(tag)) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-014", ps.recordIndex, "expected GRAPH record tag but got '" + tag + "'"));
         return null;
      }

      long fieldCountOffset = ps.position;
      String fieldCountStr = ps.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
      if (fieldCountStr == null) {
         return null;
      }

      if (!isShortestDecimal(fieldCountStr)) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-007", ps.recordIndex, "GRAPH field count is not shortest decimal: '" + fieldCountStr + "'"));
         return null;
      }

      int fieldCount = parseNonNegativeInt(fieldCountStr);
      if (fieldCount < 0) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-012", ps.recordIndex, "GRAPH field count is not a non-negative decimal: '" + fieldCountStr + "'"));
         return null;
      }

      if (fieldCount > 64) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-016", ps.recordIndex, "GRAPH field count exceeds maximum 64: " + fieldCount));
         return null;
      }

      Map<String, String> fields = readFieldsPayload(ps, fieldCount, diagnostics);
      if (fields == null) {
         return null;
      }

      int thisRecordIndex = ps.recordIndex++;
      return new RawSnapshotDocument.RawGraph(recordOffset, thisRecordIndex, fields);
   }

   private static RawSnapshotDocument.RawRecord readRecord(
      SnapshotDecoder.PayloadState ps, String tag, long recordOffset, List<ProjectGraphDiagnostic> diagnostics
   ) {
      long fieldCountOffset = ps.position;
      String fieldCountStr = ps.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
      if (fieldCountStr == null) {
         return null;
      }

      if (!isShortestDecimal(fieldCountStr)) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-007", ps.recordIndex, tag + " field count is not shortest decimal: '" + fieldCountStr + "'"));
         return null;
      }

      int fieldCount = parseNonNegativeInt(fieldCountStr);
      if (fieldCount < 0) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-012", ps.recordIndex, tag + " field count is not a non-negative decimal: '" + fieldCountStr + "'"));
         return null;
      }

      if (fieldCount > 64) {
         diagnostics.add(ps.formatError("SIR-GRAPH-FORMAT-016", ps.recordIndex, tag + " field count exceeds maximum 64: " + fieldCount));
         return null;
      }

      Map<String, String> fields = readFieldsPayload(ps, fieldCount, diagnostics);
      if (fields == null) {
         return null;
      }

      int thisRecordIndex = ps.recordIndex++;
      return new RawSnapshotDocument.RawRecord(tag, recordOffset, thisRecordIndex, fields);
   }

   private static Map<String, String> readFields(SnapshotDecoder.DecoderState state, int count, List<ProjectGraphDiagnostic> diagnostics) {
      Map<String, String> fields = RawSnapshotDocument.newFieldMap();

      for (int i = 0; i < count; i++) {
         long nameOffset = state.position;
         String name = state.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
         if (name == null) {
            return null;
         }

         if (!isValidFieldName(name)) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-009", "invalid field name '" + name + "'; byte offset " + nameOffset));
            return null;
         }

         String value = state.readLengthPrefixedValue(diagnostics, name);
         if (value == null) {
            return null;
         }

         if (fields.containsKey(name)) {
            diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-010", "duplicate field name '" + name + "'; byte offset " + nameOffset));
            return null;
         }

         fields.put(name, value);
      }

      return fields;
   }

   private static Map<String, String> readFieldsPayload(SnapshotDecoder.PayloadState ps, int count, List<ProjectGraphDiagnostic> diagnostics) {
      Map<String, String> fields = RawSnapshotDocument.newFieldMap();

      for (int i = 0; i < count; i++) {
         long nameOffset = ps.absolutePosition();
         String name = ps.readAsciiLine(diagnostics, "SIR-GRAPH-FORMAT-006", "SIR-GRAPH-FORMAT-007");
         if (name == null) {
            return null;
         }

         if (!isValidFieldName(name)) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-FORMAT-009", "invalid field name '" + name + "'; record index " + ps.recordIndex + ", byte offset " + nameOffset
               )
            );
            return null;
         }

         String value = ps.readLengthPrefixedValue(diagnostics, name);
         if (value == null) {
            return null;
         }

         if (fields.containsKey(name)) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-FORMAT-010", "duplicate field name '" + name + "'; record index " + ps.recordIndex + ", byte offset " + nameOffset
               )
            );
            return null;
         }

         fields.put(name, value);
      }

      return fields;
   }

   private static boolean isValidFieldName(String name) {
      if (name.isEmpty()) {
         return false;
      }

      for (int i = 0; i < name.length(); i++) {
         char c = name.charAt(i);
         if (c == ':' || c == '\n' || c == '\r' || c < ' ' || c > '~') {
            return false;
         }
      }

      return true;
   }

   private static int findCr(byte[] bytes, int start, int end) {
      int actualEnd = Math.min(end, bytes.length);

      for (int i = start; i < actualEnd; i++) {
         if (bytes[i] == 13) {
            return i;
         }
      }

      return -1;
   }

   private static boolean validateFixedRecordFields(
      String recordName, long byteOffset, int recordIndex, Map<String, String> fields, List<String> expected, List<ProjectGraphDiagnostic> diagnostics
   ) {
      List<String> actualKeys = new ArrayList<>(fields.keySet());

      for (String key : actualKeys) {
         if (!expected.contains(key)) {
            diagnostics.add(formatFixedRecordError("SIR-GRAPH-FORMAT-009", recordName, byteOffset, recordIndex, "unknown field: '" + key + "'"));
            return false;
         }
      }

      for (String exp : expected) {
         if (!fields.containsKey(exp)) {
            diagnostics.add(formatFixedRecordError("SIR-GRAPH-FORMAT-011", recordName, byteOffset, recordIndex, "missing required field: " + exp));
            return false;
         }
      }

      if (actualKeys.size() != expected.size()) {
         diagnostics.add(
            formatFixedRecordError(
               "SIR-GRAPH-FORMAT-011", recordName, byteOffset, recordIndex, "field count must be " + expected.size() + " but is " + actualKeys.size()
            )
         );
         return false;
      }

      for (int i = 0; i < expected.size(); i++) {
         if (!expected.get(i).equals(actualKeys.get(i))) {
            diagnostics.add(
               formatFixedRecordError(
                  "SIR-GRAPH-FORMAT-018",
                  recordName,
                  byteOffset,
                  recordIndex,
                  "fields are not in ASCII name ascending order; expected '"
                     + expected.get(i)
                     + "' at position "
                     + i
                     + " but found '"
                     + actualKeys.get(i)
                     + "'"
               )
            );
            return false;
         }
      }

      return true;
   }

   private static ProjectGraphDiagnostic formatFixedRecordError(String code, String recordName, long byteOffset, int recordIndex, String message) {
      StringBuilder sb = new StringBuilder();
      sb.append(recordName).append(' ').append(message);
      sb.append("; byte offset ").append(byteOffset);
      if (recordIndex >= 0) {
         sb.append(", record index ").append(recordIndex);
      }

      return ProjectGraphDiagnostic.error(code, sb.toString());
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

   private static long parseNonNegativeLong(String value) {
      if (!isShortestDecimal(value)) {
         return -1L;
      }

      try {
         return Long.parseLong(value);
      } catch (NumberFormatException e) {
         return -1L;
      }
   }

   private static int parseNonNegativeInt(String value) {
      if (!isShortestDecimal(value)) {
         return -1;
      }

      try {
         return Integer.parseInt(value);
      } catch (NumberFormatException e) {
         return -1;
      }
   }

   private static final class DecoderState {
      final byte[] bytes;
      int position;

      DecoderState(byte[] bytes) {
         this.bytes = bytes;
      }

      boolean matchMagic() {
         if (this.bytes.length < SnapshotFormat.MAGIC.length) {
            return false;
         }

         for (int i = 0; i < SnapshotFormat.MAGIC.length; i++) {
            if (this.bytes[i] != SnapshotFormat.MAGIC[i]) {
               return false;
            }
         }

         this.position = SnapshotFormat.MAGIC.length;
         return true;
      }

      boolean matchAsciiLine(String expected) {
         byte[] expectedBytes = (expected + "\n").getBytes(StandardCharsets.US_ASCII);
         if (this.position + expectedBytes.length > this.bytes.length) {
            return false;
         }

         for (int i = 0; i < expectedBytes.length; i++) {
            if (this.bytes[this.position + i] != expectedBytes[i]) {
               return false;
            }
         }

         this.position += expectedBytes.length;
         return true;
      }

      String readAsciiLine(List<ProjectGraphDiagnostic> diagnostics, String truncatedCode, String nonShortestCode) {
         int start = this.position;
         int lf = -1;

         for (int i = start; i < this.bytes.length; i++) {
            if (this.bytes[i] == 10) {
               lf = i;
               break;
            }

            if (this.bytes[i] == 13) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-FORMAT-004", "carriage return (CR) detected in ASCII line at byte " + i + "; structural ASCII tokens must use LF only"
                  )
               );
               return null;
            }

            if ((this.bytes[i] & 255) > 127) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-FORMAT-002", "non-ASCII byte in ASCII line at byte " + i + ": 0x" + Integer.toHexString(this.bytes[i] & 255)
                  )
               );
               return null;
            }

            if (this.bytes[i] == 58) {
               diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-012", "unexpected ':' in ASCII line at byte " + i));
               return null;
            }
         }

         if (lf < 0) {
            diagnostics.add(ProjectGraphDiagnostic.error(truncatedCode, "truncated input: expected LF-terminated ASCII line at byte " + start));
            return null;
         } else {
            String line = new String(this.bytes, start, lf - start, StandardCharsets.US_ASCII);
            this.position = lf + 1;
            return line;
         }
      }

      String readLengthPrefixedValue(List<ProjectGraphDiagnostic> diagnostics, String fieldName) {
         int start = this.position;
         int colon = -1;

         for (int i = start; i < this.bytes.length; i++) {
            if (this.bytes[i] == 58) {
               colon = i;
               break;
            }

            if (this.bytes[i] == 10) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-012", "unexpected LF in length prefix for field '" + fieldName + "'; byte offset " + start)
               );
               return null;
            }

            if (this.bytes[i] == 13) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-FORMAT-004",
                     "carriage return (CR) detected in length prefix for field '" + fieldName + "' at byte " + i + "; structural ASCII tokens must use LF only"
                  )
               );
               return null;
            }

            if ((this.bytes[i] & 255) > 127) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-012", "non-ASCII byte in length prefix for field '" + fieldName + "'; byte offset " + i)
               );
               return null;
            }
         }

         if (colon < 0) {
            diagnostics.add(
               ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-006", "truncated input: expected '<count>:' for field '" + fieldName + "'; byte offset " + start)
            );
            return null;
         }

         String countStr = new String(this.bytes, start, colon - start, StandardCharsets.US_ASCII);
         if (!SnapshotDecoder.isShortestDecimal(countStr)) {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-FORMAT-007", "non-shortest decimal byte count '" + countStr + "' for field '" + fieldName + "'; byte offset " + start
               )
            );
            return null;
         }

         long byteCount;
         try {
            byteCount = Long.parseLong(countStr);
         } catch (NumberFormatException e) {
            diagnostics.add(
               ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-012", "invalid byte count '" + countStr + "' for field '" + fieldName + "'; byte offset " + start)
            );
            return null;
         }

         if (byteCount >= 0L && byteCount <= 67108864L) {
            int valueStart = colon + 1;
            int valueEnd = valueStart + (int)byteCount;
            if (valueEnd + 1 > this.bytes.length) {
               diagnostics.add(
                  ProjectGraphDiagnostic.error(
                     "SIR-GRAPH-FORMAT-006",
                     "truncated input: field '"
                        + fieldName
                        + "' declares "
                        + byteCount
                        + " bytes but only "
                        + (this.bytes.length - valueStart)
                        + " available; byte offset "
                        + valueStart
                  )
               );
               return null;
            } else {
               byte[] valueBytes = new byte[(int)byteCount];
               System.arraycopy(this.bytes, valueStart, valueBytes, 0, (int)byteCount);
               String value = StrictUtf8.decodeStrict(valueBytes, 0, valueBytes.length);
               if (value == null) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-002", "malformed UTF-8 in field '" + fieldName + "'; byte offset " + valueStart)
                  );
                  return null;
               } else if (StrictUtf8.startsWithBom(value)) {
                  diagnostics.add(ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-003", "BOM detected in field '" + fieldName + "'; byte offset " + valueStart));
                  return null;
               } else if (this.bytes[valueEnd] == 13) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error(
                        "SIR-GRAPH-FORMAT-004",
                        "carriage return (CR) detected at value-end terminator for field '"
                           + fieldName
                           + "' at byte "
                           + valueEnd
                           + "; structural terminator must use LF only"
                     )
                  );
                  return null;
               } else if (this.bytes[valueEnd] != 10) {
                  diagnostics.add(
                     ProjectGraphDiagnostic.error("SIR-GRAPH-FORMAT-012", "expected LF after field '" + fieldName + "' value; byte offset " + valueEnd)
                  );
                  return null;
               } else {
                  this.position = valueEnd + 1;
                  return value;
               }
            }
         } else {
            diagnostics.add(
               ProjectGraphDiagnostic.error(
                  "SIR-GRAPH-FORMAT-016", "byte count out of bounds for field '" + fieldName + "': " + byteCount + "; byte offset " + start
               )
            );
            return null;
         }
      }

      ProjectGraphDiagnostic formatError(String code, String message) {
         return ProjectGraphDiagnostic.error(code, message + "; byte offset " + this.position);
      }
   }

   private static final class PayloadState {
      final byte[] payload;
      final int payloadStart;
      int position;
      int recordIndex;

      PayloadState(byte[] payload, int payloadStart) {
         this.payload = payload;
         this.payloadStart = payloadStart;
      }

      long absolutePosition() {
         return this.payloadStart + this.position;
      }

      String readAsciiLine(List<ProjectGraphDiagnostic> diagnostics, String truncatedCode, String nonShortestCode) {
         int start = this.position;
         int lf = -1;

         for (int i = start; i < this.payload.length; i++) {
            if (this.payload[i] == 10) {
               lf = i;
               break;
            }

            if (this.payload[i] == 13) {
               diagnostics.add(
                  this.formatError(
                     "SIR-GRAPH-FORMAT-004",
                     this.recordIndex,
                     "carriage return (CR) detected in ASCII line at byte " + (this.payloadStart + i) + "; structural ASCII tokens must use LF only"
                  )
               );
               return null;
            }

            if ((this.payload[i] & 255) > 127) {
               diagnostics.add(this.formatError("SIR-GRAPH-FORMAT-002", this.recordIndex, "non-ASCII byte in ASCII line at byte " + (this.payloadStart + i)));
               return null;
            }

            if (this.payload[i] == 58) {
               diagnostics.add(this.formatError("SIR-GRAPH-FORMAT-012", this.recordIndex, "unexpected ':' in ASCII line at byte " + (this.payloadStart + i)));
               return null;
            }
         }

         if (lf < 0) {
            diagnostics.add(
               this.formatError(truncatedCode, this.recordIndex, "truncated input: expected LF-terminated ASCII line at byte " + (this.payloadStart + start))
            );
            return null;
         } else {
            String line = new String(this.payload, start, lf - start, StandardCharsets.US_ASCII);
            this.position = lf + 1;
            return line;
         }
      }

      String readLengthPrefixedValue(List<ProjectGraphDiagnostic> diagnostics, String fieldName) {
         int start = this.position;
         int colon = -1;

         for (int i = start; i < this.payload.length; i++) {
            if (this.payload[i] == 58) {
               colon = i;
               break;
            }

            if (this.payload[i] == 10) {
               diagnostics.add(
                  this.formatError(
                     "SIR-GRAPH-FORMAT-012",
                     this.recordIndex,
                     "unexpected LF in length prefix for field '" + fieldName + "'; byte offset " + (this.payloadStart + start)
                  )
               );
               return null;
            }

            if (this.payload[i] == 13) {
               diagnostics.add(
                  this.formatError(
                     "SIR-GRAPH-FORMAT-004",
                     this.recordIndex,
                     "carriage return (CR) detected in length prefix for field '"
                        + fieldName
                        + "' at byte "
                        + (this.payloadStart + i)
                        + "; structural ASCII tokens must use LF only"
                  )
               );
               return null;
            }

            if ((this.payload[i] & 255) > 127) {
               diagnostics.add(
                  this.formatError(
                     "SIR-GRAPH-FORMAT-012",
                     this.recordIndex,
                     "non-ASCII byte in length prefix for field '" + fieldName + "'; byte offset " + (this.payloadStart + i)
                  )
               );
               return null;
            }
         }

         if (colon < 0) {
            diagnostics.add(
               this.formatError(
                  "SIR-GRAPH-FORMAT-006",
                  this.recordIndex,
                  "truncated input: expected '<count>:' for field '" + fieldName + "'; byte offset " + (this.payloadStart + start)
               )
            );
            return null;
         }

         String countStr = new String(this.payload, start, colon - start, StandardCharsets.US_ASCII);
         if (!SnapshotDecoder.isShortestDecimal(countStr)) {
            diagnostics.add(
               this.formatError(
                  "SIR-GRAPH-FORMAT-007",
                  this.recordIndex,
                  "non-shortest decimal byte count '" + countStr + "' for field '" + fieldName + "'; byte offset " + (this.payloadStart + start)
               )
            );
            return null;
         }

         long byteCount;
         try {
            byteCount = Long.parseLong(countStr);
         } catch (NumberFormatException e) {
            diagnostics.add(
               this.formatError(
                  "SIR-GRAPH-FORMAT-012",
                  this.recordIndex,
                  "invalid byte count '" + countStr + "' for field '" + fieldName + "'; byte offset " + (this.payloadStart + start)
               )
            );
            return null;
         }

         if (byteCount >= 0L && byteCount <= 67108864L) {
            int valueStart = colon + 1;
            int valueEnd = valueStart + (int)byteCount;
            if (valueEnd + 1 > this.payload.length) {
               diagnostics.add(
                  this.formatError(
                     "SIR-GRAPH-FORMAT-006",
                     this.recordIndex,
                     "truncated input: field '"
                        + fieldName
                        + "' declares "
                        + byteCount
                        + " bytes but only "
                        + (this.payload.length - valueStart)
                        + " available; byte offset "
                        + (this.payloadStart + valueStart)
                  )
               );
               return null;
            } else {
               byte[] valueBytes = new byte[(int)byteCount];
               System.arraycopy(this.payload, valueStart, valueBytes, 0, (int)byteCount);
               String value = StrictUtf8.decodeStrict(valueBytes, 0, valueBytes.length);
               if (value == null) {
                  diagnostics.add(
                     this.formatError(
                        "SIR-GRAPH-FORMAT-002",
                        this.recordIndex,
                        "malformed UTF-8 in field '" + fieldName + "'; byte offset " + (this.payloadStart + valueStart)
                     )
                  );
                  return null;
               } else if (StrictUtf8.startsWithBom(value)) {
                  diagnostics.add(
                     this.formatError(
                        "SIR-GRAPH-FORMAT-003", this.recordIndex, "BOM detected in field '" + fieldName + "'; byte offset " + (this.payloadStart + valueStart)
                     )
                  );
                  return null;
               } else if (this.payload[valueEnd] == 13) {
                  diagnostics.add(
                     this.formatError(
                        "SIR-GRAPH-FORMAT-004",
                        this.recordIndex,
                        "carriage return (CR) detected at value-end terminator for field '"
                           + fieldName
                           + "' at byte "
                           + (this.payloadStart + valueEnd)
                           + "; structural terminator must use LF only"
                     )
                  );
                  return null;
               } else if (this.payload[valueEnd] != 10) {
                  diagnostics.add(
                     this.formatError(
                        "SIR-GRAPH-FORMAT-012",
                        this.recordIndex,
                        "expected LF after field '" + fieldName + "' value; byte offset " + (this.payloadStart + valueEnd)
                     )
                  );
                  return null;
               } else {
                  this.position = valueEnd + 1;
                  return value;
               }
            }
         } else {
            diagnostics.add(
               this.formatError(
                  "SIR-GRAPH-FORMAT-016",
                  this.recordIndex,
                  "byte count out of bounds for field '" + fieldName + "': " + byteCount + "; byte offset " + (this.payloadStart + start)
               )
            );
            return null;
         }
      }

      ProjectGraphDiagnostic formatError(String code, int recIndex, String message) {
         return ProjectGraphDiagnostic.error(code, message + "; record index " + recIndex + ", byte offset " + this.absolutePosition());
      }
   }
}
