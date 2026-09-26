package io.kcg.sir.change.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The one SHA-256 rule the rename contract uses, so a plan, a source snapshot and the application
 * layer cannot disagree about how a digest is spelled.
 */
public final class Sha256 {
   private Sha256() {
   }

   public static String hex(byte[] bytes) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
         StringBuilder hex = new StringBuilder(digest.length * 2);
         for (byte value : digest) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
         }

         return hex.toString();
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException("SHA-256 is not available", e);
      }
   }

   public static String hex(String text) {
      return hex(text.getBytes(StandardCharsets.UTF_8));
   }
}
