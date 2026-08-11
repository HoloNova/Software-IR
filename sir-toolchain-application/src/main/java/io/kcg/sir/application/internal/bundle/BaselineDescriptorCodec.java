package io.kcg.sir.application.internal.bundle;

import io.kcg.sir.application.api.ChangeExecutionBaselineFormatVersion;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.source.SourceId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BaselineDescriptorCodec {
   static final byte[] MAGIC = "KCG-CHANGE-BASELINE-V1\n".getBytes(StandardCharsets.US_ASCII);
   static final byte[] BASELINE_ID_DOMAIN = "KCG-CHANGE-BASELINE-V1".getBytes(StandardCharsets.US_ASCII);
   private static final byte[] MANIFEST_MARKER = "manifest\n".getBytes(StandardCharsets.US_ASCII);
   private static final byte[] END_MARKER = "end\n".getBytes(StandardCharsets.US_ASCII);
   private static final List<String> FIXED_FIELD_ORDER = List.of(
      "formatVersion",
      "boundOutputRoot",
      "sourceId",
      "sourceByteCount",
      "sourceSha256Hex",
      "graphVersion",
      "graphCanonicalDigest",
      "snapshotFormatVersion",
      "snapshotByteCount",
      "snapshotSha256Hex",
      "targetId",
      "loweredIrVersion",
      "manifestCount",
      "manifestDigest"
   );

   private BaselineDescriptorCodec() {
   }

   public static byte[] encode(BaselineDescriptor descriptor) {
      Objects.requireNonNull(descriptor, "descriptor");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      out.writeBytes(MAGIC);
      writeField(out, "formatVersion", descriptor.formatVersion().name());
      writeField(out, "boundOutputRoot", descriptor.boundOutputRoot().toString());
      writeField(out, "sourceId", descriptor.sourceId().value());
      writeField(out, "sourceByteCount", Long.toString(descriptor.sourceByteCount(), 10));
      writeField(out, "sourceSha256Hex", descriptor.sourceSha256Hex());
      writeField(out, "graphVersion", descriptor.graphVersion().name());
      writeField(out, "graphCanonicalDigest", descriptor.graphCanonicalDigest());
      writeField(out, "snapshotFormatVersion", descriptor.snapshotFormatVersion().name());
      writeField(out, "snapshotByteCount", Long.toString(descriptor.snapshotByteCount(), 10));
      writeField(out, "snapshotSha256Hex", descriptor.snapshotSha256Hex());
      writeField(out, "targetId", descriptor.targetId());
      writeField(out, "loweredIrVersion", descriptor.loweredIrVersion().value());
      writeField(out, "manifestCount", Integer.toString(descriptor.manifest().size(), 10));
      writeField(out, "manifestDigest", descriptor.manifestDigest());
      out.writeBytes(MANIFEST_MARKER);

      for (BaselineManifestEntry entry : descriptor.manifest()) {
         out.writeBytes(encodeManifestEntry(entry).getBytes(StandardCharsets.UTF_8));
         out.write(10);
      }

      out.writeBytes(END_MARKER);
      return out.toByteArray();
   }

   public static String computeManifestDigest(List<BaselineManifestEntry> manifest) {
      Objects.requireNonNull(manifest, "manifest");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      List<BaselineManifestEntry> sorted = new ArrayList<>(manifest);
      sorted.sort(Comparator.comparing(BaselineManifestEntry::relativePath));

      for (BaselineManifestEntry entry : sorted) {
         out.writeBytes(encodeManifestEntry(entry).getBytes(StandardCharsets.UTF_8));
         out.write(10);
      }

      return Sha256.hexDigest(out.toByteArray());
   }

   public static byte[] encodeManifestBlock(List<BaselineManifestEntry> manifest) {
      Objects.requireNonNull(manifest, "manifest");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      List<BaselineManifestEntry> sorted = new ArrayList<>(manifest);
      sorted.sort(Comparator.comparing(BaselineManifestEntry::relativePath));

      for (BaselineManifestEntry entry : sorted) {
         out.writeBytes(encodeManifestEntry(entry).getBytes(StandardCharsets.UTF_8));
         out.write(10);
      }

      return out.toByteArray();
   }

   private static String encodeManifestEntry(BaselineManifestEntry entry) {
      StringBuilder sb = new StringBuilder();
      sb.append(percentEncode(entry.relativePath()));
      sb.append('|');
      sb.append(Long.toString(entry.byteCount(), 10));
      sb.append('|');
      sb.append(entry.sha256Hex());
      sb.append('|');
      sb.append(percentEncode(entry.artifactId().value()));
      sb.append('|');
      sb.append((char)(entry.ownerSymbol().isPresent() ? '1' : '0'));
      sb.append('|');
      sb.append(entry.ownerSymbol().isPresent() ? percentEncode(entry.ownerSymbol().get().value()) : "");
      return sb.toString();
   }

   public static String computeBaselineId(byte[] descriptorBytes, byte[] sourceBytes, byte[] snapshotBytes) {
      Objects.requireNonNull(descriptorBytes, "descriptorBytes");
      Objects.requireNonNull(sourceBytes, "sourceBytes");
      Objects.requireNonNull(snapshotBytes, "snapshotBytes");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      writeFrame(out, BASELINE_ID_DOMAIN);
      writeFrame(out, descriptorBytes);
      writeFrame(out, sourceBytes);
      writeFrame(out, snapshotBytes);
      return Sha256.hexDigest(out.toByteArray());
   }

   public static BaselineDescriptorCodec.DecodeResult decode(byte[] bytes) {
      Objects.requireNonNull(bytes, "bytes");
      if (bytes.length == 0) {
         return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "empty descriptor");
      }

      if (bytes.length >= 3 && bytes[0] == -17 && bytes[1] == -69 && bytes[2] == -65) {
         return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "BOM not allowed");
      }

      if (bytes.length >= MAGIC.length && startsWith(bytes, MAGIC)) {
         for (byte b : bytes) {
            if (b == 13) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "CR not allowed");
            }
         }

         BaselineDescriptorCodec.LineCursor cursor = new BaselineDescriptorCodec.LineCursor(bytes, MAGIC.length);
         Map<String, String> fields = new LinkedHashMap<>();

         for (String expectedKey : FIXED_FIELD_ORDER) {
            Optional<String> line = cursor.next();
            if (line.isEmpty()) {
               return new BaselineDescriptorCodec.DecodeFailure(
                  "SIR-APP-CHANGE-BASELINE-001", "unexpected end of descriptor while reading field: " + expectedKey
               );
            }

            BaselineDescriptorCodec.FieldParseResult field = parseField(line.get());
            if (field instanceof BaselineDescriptorCodec.FieldParseResult.Failure f) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "malformed field line for " + expectedKey + ": " + f.message());
            }

            BaselineDescriptorCodec.FieldParseResult.Ok ok = (BaselineDescriptorCodec.FieldParseResult.Ok)field;
            if (!ok.key().equals(expectedKey)) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "expected field " + expectedKey + " but got " + ok.key());
            }

            if (fields.put(ok.key(), ok.value()) != null) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "duplicate field: " + ok.key());
            }
         }

         Optional<String> markerLine = cursor.next();
         if (!markerLine.isEmpty() && markerLine.get().equals("manifest")) {
            int manifestCount;
            try {
               manifestCount = Integer.parseInt(fields.get("manifestCount"), 10);
            } catch (NumberFormatException e) {
               return new BaselineDescriptorCodec.DecodeFailure(
                  "SIR-APP-CHANGE-BASELINE-001", "manifestCount is not a decimal integer: " + fields.get("manifestCount")
               );
            }

            if (manifestCount < 0) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "manifestCount must not be negative: " + manifestCount);
            }

            List<BaselineManifestEntry> entries = new ArrayList<>(manifestCount);
            String lastPath = null;

            for (int i = 0; i < manifestCount; i++) {
               Optional<String> line = cursor.next();
               if (line.isEmpty()) {
                  return new BaselineDescriptorCodec.DecodeFailure(
                     "SIR-APP-CHANGE-BASELINE-001", "unexpected end of descriptor while reading manifest entry " + i
                  );
               }

               BaselineDescriptorCodec.EntryParseResult entryResult = parseManifestEntry(line.get());
               if (entryResult instanceof BaselineDescriptorCodec.EntryParseResult.Failure f) {
                  return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "malformed manifest entry " + i + ": " + f.message());
               }

               BaselineManifestEntry entry = ((BaselineDescriptorCodec.EntryParseResult.Ok)entryResult).entry();
               if (lastPath != null && entry.relativePath().compareTo(lastPath) <= 0) {
                  return new BaselineDescriptorCodec.DecodeFailure(
                     "SIR-APP-CHANGE-BASELINE-001",
                     "manifest entries must be sorted ascending by relativePath; got " + entry.relativePath() + " after " + lastPath
                  );
               }

               lastPath = entry.relativePath();
               entries.add(entry);
            }

            Optional<String> endLine = cursor.next();
            if (endLine.isEmpty() || !endLine.get().equals("end")) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "expected 'end' marker line");
            }

            if (cursor.hasNext()) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "trailing bytes after 'end' marker");
            }

            String expectedManifestDigest = computeManifestDigest(entries);
            if (!expectedManifestDigest.equals(fields.get("manifestDigest"))) {
               return new BaselineDescriptorCodec.DecodeFailure(
                  "SIR-APP-CHANGE-BASELINE-001",
                  "manifestDigest mismatch: descriptor=" + fields.get("manifestDigest") + " recomputed=" + expectedManifestDigest
               );
            }

            BaselineDescriptor descriptor;
            try {
               descriptor = buildDescriptor(fields, entries);
            } catch (IllegalArgumentException e) {
               return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "descriptor field validation failed: " + e.getMessage());
            }

            return new BaselineDescriptorCodec.DecodeOk(descriptor);
         } else {
            return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "expected 'manifest' marker line");
         }
      } else {
         return new BaselineDescriptorCodec.DecodeFailure("SIR-APP-CHANGE-BASELINE-001", "missing or wrong magic header");
      }
   }

   private static BaselineDescriptor buildDescriptor(Map<String, String> fields, List<BaselineManifestEntry> entries) {
      ChangeExecutionBaselineFormatVersion formatVersion = ChangeExecutionBaselineFormatVersion.valueOf(fields.get("formatVersion"));
      Path boundOutputRoot = Path.of(fields.get("boundOutputRoot")).toAbsolutePath().normalize();
      SourceId sourceId = SourceId.of(fields.get("sourceId"));
      long sourceByteCount = Long.parseLong(fields.get("sourceByteCount"), 10);
      String sourceSha256Hex = fields.get("sourceSha256Hex");
      GraphVersion graphVersion = GraphVersion.valueOf(fields.get("graphVersion"));
      String graphCanonicalDigest = fields.get("graphCanonicalDigest");
      ProjectGraphCanonicalFormatVersion snapshotFormatVersion = ProjectGraphCanonicalFormatVersion.valueOf(fields.get("snapshotFormatVersion"));
      long snapshotByteCount = Long.parseLong(fields.get("snapshotByteCount"), 10);
      String snapshotSha256Hex = fields.get("snapshotSha256Hex");
      String targetId = fields.get("targetId");
      LoweredIrVersion loweredIrVersion = new LoweredIrVersion(fields.get("loweredIrVersion"));
      String manifestDigest = fields.get("manifestDigest");
      return new BaselineDescriptor(
         formatVersion,
         boundOutputRoot,
         sourceId,
         sourceByteCount,
         sourceSha256Hex,
         graphVersion,
         graphCanonicalDigest,
         snapshotFormatVersion,
         snapshotByteCount,
         snapshotSha256Hex,
         targetId,
         loweredIrVersion,
         entries,
         manifestDigest
      );
   }

   private static BaselineDescriptorCodec.FieldParseResult parseField(String line) {
      int eq = line.indexOf(61);
      if (eq <= 0) {
         return new BaselineDescriptorCodec.FieldParseResult.Failure("missing '=' separator");
      }

      String key = line.substring(0, eq);
      String rest = line.substring(eq + 1);
      int colon = rest.indexOf(58);
      if (colon < 0) {
         return new BaselineDescriptorCodec.FieldParseResult.Failure("missing ':' length separator in value");
      }

      String lenStr = rest.substring(0, colon);

      int len;
      try {
         len = Integer.parseInt(lenStr, 10);
      } catch (NumberFormatException e) {
         return new BaselineDescriptorCodec.FieldParseResult.Failure("value length is not decimal: " + lenStr);
      }

      if (len < 0) {
         return new BaselineDescriptorCodec.FieldParseResult.Failure("value length must not be negative: " + len);
      }

      if (lenStr.length() > 1 && lenStr.charAt(0) == '0') {
         return new BaselineDescriptorCodec.FieldParseResult.Failure("value length has leading zero: " + lenStr);
      }

      if (lenStr.isEmpty()) {
         return new BaselineDescriptorCodec.FieldParseResult.Failure("value length is empty");
      }

      String value = rest.substring(colon + 1);
      int actualByteLen = value.getBytes(StandardCharsets.UTF_8).length;
      return actualByteLen != len
         ? new BaselineDescriptorCodec.FieldParseResult.Failure("value byte length mismatch: declared=" + len + " actual=" + actualByteLen)
         : new BaselineDescriptorCodec.FieldParseResult.Ok(key, value);
   }

   private static BaselineDescriptorCodec.EntryParseResult parseManifestEntry(String line) {
      String[] parts = line.split("\\|", -1);
      if (parts.length != 6) {
         return new BaselineDescriptorCodec.EntryParseResult.Failure("manifest entry must have 6 pipe-separated parts, got " + parts.length);
      }

      String relativePath;
      long byteCount;
      String sha256Hex;
      String artifactIdStr;
      int ownerPresent;
      try {
         relativePath = percentDecode(parts[0]);
         byteCount = Long.parseLong(parts[1], 10);
         sha256Hex = parts[2];
         artifactIdStr = percentDecode(parts[3]);
         ownerPresent = Integer.parseInt(parts[4], 10);
      } catch (IllegalArgumentException e) {
         return new BaselineDescriptorCodec.EntryParseResult.Failure("manifest entry numeric parse failed: " + e.getMessage());
      }

      if (ownerPresent != 0 && ownerPresent != 1) {
         return new BaselineDescriptorCodec.EntryParseResult.Failure("ownerPresent must be 0 or 1: " + ownerPresent);
      }

      if (ownerPresent == 1 && parts[5].isEmpty()) {
         return new BaselineDescriptorCodec.EntryParseResult.Failure("ownerPresent=1 but ownerSymbol is empty");
      }

      if (ownerPresent == 0 && !parts[5].isEmpty()) {
         return new BaselineDescriptorCodec.EntryParseResult.Failure("ownerPresent=0 but ownerSymbol is non-empty");
      }

      Optional<SymbolId> ownerSymbol = ownerPresent == 1 ? Optional.of(new SymbolId(percentDecode(parts[5]))) : Optional.empty();

      BaselineManifestEntry entry;
      try {
         entry = new BaselineManifestEntry(relativePath, byteCount, sha256Hex, new LoweredNodeId(artifactIdStr), ownerSymbol);
      } catch (IllegalArgumentException e) {
         return new BaselineDescriptorCodec.EntryParseResult.Failure("manifest entry validation failed: " + e.getMessage());
      }

      return new BaselineDescriptorCodec.EntryParseResult.Ok(entry);
   }

   private static String percentEncode(String s) {
      StringBuilder sb = new StringBuilder(s.length());

      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         if (c != '%' && c != '|' && c != '\n' && c != '\r') {
            sb.append(c);
         } else {
            sb.append('%');
            String hex = Integer.toHexString(c & 255).toUpperCase(Locale.ROOT);
            if (hex.length() < 2) {
               sb.append('0');
            }

            sb.append(hex);
         }
      }

      return sb.toString();
   }

   private static String percentDecode(String s) {
      StringBuilder sb = new StringBuilder(s.length());
      int i = 0;

      while (i < s.length()) {
         char c = s.charAt(i);
         if (c == '%') {
            if (i + 2 >= s.length()) {
               throw new IllegalArgumentException("incomplete percent-escape at position " + i);
            }

            String hex = s.substring(i + 1, i + 3);

            try {
               int v = Integer.parseInt(hex, 16);
               sb.append((char)v);
            } catch (NumberFormatException e) {
               throw new IllegalArgumentException("invalid percent-escape '%" + hex + "' at position " + i);
            }

            i += 3;
         } else {
            sb.append(c);
            i++;
         }
      }

      return sb.toString();
   }

   private static void writeField(ByteArrayOutputStream out, String key, String value) {
      out.writeBytes(key.getBytes(StandardCharsets.US_ASCII));
      out.write(61);
      String lenStr = Integer.toString(value.getBytes(StandardCharsets.UTF_8).length, 10);
      out.writeBytes(lenStr.getBytes(StandardCharsets.US_ASCII));
      out.write(58);
      out.writeBytes(value.getBytes(StandardCharsets.UTF_8));
      out.write(10);
   }

   private static void writeFrame(ByteArrayOutputStream out, byte[] payload) {
      long len = payload.length;

      for (int i = 7; i >= 0; i--) {
         out.write((int)(len >>> i * 8 & 255L));
      }

      out.writeBytes(payload);
   }

   private static boolean startsWith(byte[] bytes, byte[] prefix) {
      if (bytes.length < prefix.length) {
         return false;
      }

      for (int i = 0; i < prefix.length; i++) {
         if (bytes[i] != prefix[i]) {
            return false;
         }
      }

      return true;
   }

   public record DecodeFailure(String code, String message) implements BaselineDescriptorCodec.DecodeResult {
      public DecodeFailure(String code, String message) {
         Objects.requireNonNull(code, "code");
         Objects.requireNonNull(message, "message");
         if (!code.isBlank() && !message.isBlank()) {
            this.code = code;
            this.message = message;
         } else {
            throw new IllegalArgumentException("code and message must not be blank");
         }
      }
   }

   public record DecodeOk(BaselineDescriptor descriptor) implements BaselineDescriptorCodec.DecodeResult {
      public DecodeOk {
         Objects.requireNonNull(descriptor, "descriptor");
      }
   }

   public sealed interface DecodeResult permits BaselineDescriptorCodec.DecodeOk, BaselineDescriptorCodec.DecodeFailure {
   }

   private sealed interface EntryParseResult permits BaselineDescriptorCodec.EntryParseResult.Ok, BaselineDescriptorCodec.EntryParseResult.Failure {
      record Failure(String message) implements BaselineDescriptorCodec.EntryParseResult {
         public Failure {
            Objects.requireNonNull(message, "message");
         }
      }

      record Ok(BaselineManifestEntry entry) implements BaselineDescriptorCodec.EntryParseResult {
         public Ok {
            Objects.requireNonNull(entry, "entry");
         }
      }
   }

   private sealed interface FieldParseResult permits BaselineDescriptorCodec.FieldParseResult.Ok, BaselineDescriptorCodec.FieldParseResult.Failure {
      record Failure(String message) implements BaselineDescriptorCodec.FieldParseResult {
         public Failure {
            Objects.requireNonNull(message, "message");
         }
      }

      record Ok(String key, String value) implements BaselineDescriptorCodec.FieldParseResult {
         public Ok {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
         }
      }
   }

   private static final class LineCursor {
      private final byte[] bytes;
      private int pos;

      LineCursor(byte[] bytes, int start) {
         this.bytes = bytes;
         this.pos = start;
      }

      Optional<String> next() {
         if (this.pos >= this.bytes.length) {
            return Optional.empty();
         }

         int start = this.pos;

         while (this.pos < this.bytes.length && this.bytes[this.pos] != 10) {
            this.pos++;
         }

         if (this.pos >= this.bytes.length) {
            return Optional.empty();
         }

         int end = this.pos++;
         return Optional.of(new String(this.bytes, start, end - start, StandardCharsets.UTF_8));
      }

      boolean hasNext() {
         return this.pos < this.bytes.length;
      }
   }
}
