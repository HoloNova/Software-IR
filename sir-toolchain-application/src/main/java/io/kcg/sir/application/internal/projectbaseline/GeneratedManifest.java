package io.kcg.sir.application.internal.projectbaseline;

import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The list of files a version generates, and one digest over it.
 *
 * <p>Why it exists: the identity of a version has to cover what is generated, not only the SIR it came from, so that a
 * generated file that differs in even one byte, or goes missing, is a different version. The digest is taken over each
 * file's path, size, content hash and owning artifact, in path order, so it does not depend on the order the generator
 * listed them in.
 */
public final class GeneratedManifest {
   private GeneratedManifest() {
   }

   public record Entry(String relativePath, long byteCount, String sha256Hex, LoweredNodeId artifactId, Optional<SymbolId> ownerSymbol) {
      public Entry {
         Objects.requireNonNull(relativePath, "relativePath");
         Objects.requireNonNull(sha256Hex, "sha256Hex");
         Objects.requireNonNull(artifactId, "artifactId");
         ownerSymbol = Objects.requireNonNull(ownerSymbol, "ownerSymbol");
         if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
         }

         if (relativePath.indexOf(10) >= 0 || relativePath.indexOf(13) >= 0) {
            throw new IllegalArgumentException("relativePath must not contain newlines");
         }

         if (byteCount < 0L) {
            throw new IllegalArgumentException("byteCount must not be negative");
         }

         if (!sha256Hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256Hex must be a 64-character lowercase hexadecimal SHA-256 digest");
         }
      }
   }

   public static List<Entry> of(List<GeneratedFile> generatedFiles) {
      Objects.requireNonNull(generatedFiles, "generatedFiles");
      List<Entry> entries = new ArrayList<>(generatedFiles.size());

      for (GeneratedFile gf : generatedFiles) {
         byte[] contentBytes = gf.content().getBytes(StandardCharsets.UTF_8);
         entries.add(new Entry(gf.relativePath(), contentBytes.length, Sha256.hexDigest(contentBytes), gf.artifactId(), gf.symbolId()));
      }

      entries.sort(Comparator.comparing(Entry::relativePath));
      return entries;
   }

   public static String digest(List<Entry> manifest) {
      Objects.requireNonNull(manifest, "manifest");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      List<Entry> sorted = new ArrayList<>(manifest);
      sorted.sort(Comparator.comparing(Entry::relativePath));

      for (Entry entry : sorted) {
         out.writeBytes(encode(entry).getBytes(StandardCharsets.UTF_8));
         out.write(10);
      }

      return Sha256.hexDigest(out.toByteArray());
   }

   private static String encode(Entry entry) {
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
}
