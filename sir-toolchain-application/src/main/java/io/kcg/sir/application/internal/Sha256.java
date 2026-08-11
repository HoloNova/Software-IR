package io.kcg.sir.application.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class Sha256 {
   private Sha256() {
   }

   public static String hexDigest(String content) {
      return hexDigest(content.getBytes(StandardCharsets.UTF_8));
   }

   public static String hexDigest(byte[] bytes) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
         return toHexLower(digest);
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 algorithm not available", e);
      }
   }

   static long utf8ByteCount(String content) {
      return content.getBytes(StandardCharsets.UTF_8).length;
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
