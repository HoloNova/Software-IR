package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.bundle.BaselineManifestEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

public final class SafeTargetResolver {
   private final Path root;

   private SafeTargetResolver(Path root) {
      this.root = root;
   }

   public static SafeTargetResolver forRoot(Path root) throws SafeTargetResolver.UnsafePathException {
      Objects.requireNonNull(root, "root");
      Path absolute = root.toAbsolutePath();
      Path normalized = absolute.normalize();
      verifyRawChainNoSymlink(absolute);
      verifyRawChainNoSymlink(normalized);

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException | SecurityException e) {
         throw new SafeTargetResolver.UnsafePathException("failed to read root attributes: " + normalized + ": " + e.getMessage());
      }

      if (attrs.isSymbolicLink() || isReparsePoint(normalized, attrs)) {
         throw new SafeTargetResolver.UnsafePathException("root must not be a symlink or reparse point: " + normalized);
      } else if (!attrs.isDirectory()) {
         throw new SafeTargetResolver.UnsafePathException("root must be a directory: " + normalized);
      } else {
         return new SafeTargetResolver(normalized);
      }
   }

   public Path root() {
      return this.root;
   }

   public Path resolveForCreate(String relativePath) throws SafeTargetResolver.UnsafePathException {
      Path resolved = this.resolveChecked(relativePath);
      Path parent = resolved.getParent();
      if (parent != null) {
         createParentDirs(this.root, parent);
      }

      return resolved;
   }

   public Path resolveNoCreate(String relativePath) throws SafeTargetResolver.UnsafePathException {
      Path resolved = this.resolveChecked(relativePath);
      Path parent = resolved.getParent();
      if (parent != null) {
         verifyExistingParentDirs(this.root, parent);
      }

      return resolved;
   }

   private Path resolveChecked(String relativePath) throws SafeTargetResolver.UnsafePathException {
      String pathError = TransactionJournal.validateRelativePath(relativePath);
      if (pathError != null) {
         throw new SafeTargetResolver.UnsafePathException("unsafe relative path: " + pathError + ": " + relativePath);
      }

      Path resolved = this.root.resolve(relativePath).normalize();
      if (!resolved.startsWith(this.root)) {
         throw new SafeTargetResolver.UnsafePathException("path escapes root: " + relativePath + " -> " + resolved);
      }

      verifyRawChainNoSymlink(this.root.resolve(relativePath));
      return resolved;
   }

   public Path resolveExistingFile(String relativePath) throws SafeTargetResolver.UnsafePathException {
      Path target = this.resolveNoCreate(relativePath);

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         throw new SafeTargetResolver.UnsafePathException("target does not exist: " + relativePath);
      } catch (IOException | SecurityException e) {
         throw new SafeTargetResolver.UnsafePathException("failed to read target attributes: " + relativePath + ": " + e.getMessage());
      }

      if (attrs.isSymbolicLink() || isReparsePoint(target, attrs)) {
         throw new SafeTargetResolver.UnsafePathException("target must not be a symlink or reparse point: " + relativePath);
      } else if (!attrs.isRegularFile()) {
         throw new SafeTargetResolver.UnsafePathException("target must be a regular file: " + relativePath);
      } else {
         return target;
      }
   }

   public Path resolveAndVerifyBytes(String relativePath, long expectedByteCount, String expectedSha256Hex) throws SafeTargetResolver.UnsafePathException {
      Path target = this.resolveExistingFile(relativePath);

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException e) {
         throw new SafeTargetResolver.UnsafePathException("failed to re-read target attributes: " + relativePath + ": " + e.getMessage());
      }

      if (attrs.size() != expectedByteCount) {
         throw new SafeTargetResolver.UnsafePathException(
            "byte count mismatch for " + relativePath + ": expected=" + expectedByteCount + " actual=" + attrs.size()
         );
      }

      try {
         byte[] bytes = Files.readAllBytes(target);
         String actualSha = Sha256.hexDigest(bytes);
         if (!actualSha.equals(expectedSha256Hex)) {
            throw new SafeTargetResolver.UnsafePathException(
               "SHA-256 mismatch for " + relativePath + ": expected=" + expectedSha256Hex + " actual=" + actualSha
            );
         } else {
            return target;
         }
      } catch (IOException | SecurityException e) {
         throw new SafeTargetResolver.UnsafePathException("failed to read target bytes: " + relativePath + ": " + e.getMessage());
      }
   }

   public Path resolveAndVerifyB0(String relativePath, BaselineManifestEntry b0Entry) throws SafeTargetResolver.UnsafePathException {
      Objects.requireNonNull(b0Entry, "b0Entry");
      return this.resolveAndVerifyBytes(relativePath, b0Entry.byteCount(), b0Entry.sha256Hex());
   }

   public Path resolveAndVerifyB1(String relativePath, BaselineManifestEntry b1Entry) throws SafeTargetResolver.UnsafePathException {
      Objects.requireNonNull(b1Entry, "b1Entry");
      return this.resolveAndVerifyBytes(relativePath, b1Entry.byteCount(), b1Entry.sha256Hex());
   }

   private static void verifyRawChainNoSymlink(Path path) throws SafeTargetResolver.UnsafePathException {
      Path current = path;

      while (current != null) {
         BasicFileAttributes attrs;
         try {
            attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            // Only a direct NoSuchFileException proves the segment is
            // absent; any other IOException fails closed via UnsafePathException.
            // Walking up past an absent leaf is safe because every existing
            // ancestor gets its own NOFOLLOW attribute check on the next
            // iteration, including reparse points.
            current = current.getParent();
            continue;
         } catch (IOException | SecurityException e) {
            throw new SafeTargetResolver.UnsafePathException("failed to read attributes in raw chain: " + current + ": " + e.getMessage());
         }

         if (attrs.isSymbolicLink() || isReparsePoint(current, attrs)) {
            throw new SafeTargetResolver.UnsafePathException("symlink or reparse point in raw chain: " + current);
         }

         current = current.getParent();
      }
   }

   private static void createParentDirs(Path root, Path targetParent) throws SafeTargetResolver.UnsafePathException {
      Path current = root;

      for (Path component : root.relativize(targetParent)) {
         current = current.resolve(component);

         BasicFileAttributes attrs;
         try {
            attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            try {
               Files.createDirectory(current);
               continue;
            } catch (IOException | SecurityException ex) {
               throw new SafeTargetResolver.UnsafePathException("failed to create parent directory: " + current + ": " + ex.getMessage());
            }
         } catch (IOException | SecurityException e) {
            throw new SafeTargetResolver.UnsafePathException("failed to read parent attributes: " + current + ": " + e.getMessage());
         }

         if (attrs.isSymbolicLink() || isReparsePoint(current, attrs)) {
            throw new SafeTargetResolver.UnsafePathException("link in parent chain: " + current);
         }

         if (!attrs.isDirectory()) {
            throw new SafeTargetResolver.UnsafePathException("parent is not a directory: " + current);
         }
      }
   }

   private static void verifyExistingParentDirs(Path root, Path targetParent) throws SafeTargetResolver.UnsafePathException {
      Path current = root;

      for (Path component : root.relativize(targetParent)) {
         current = current.resolve(component);

         BasicFileAttributes attrs;
         try {
            attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            throw new SafeTargetResolver.UnsafePathException("parent directory does not exist: " + current);
         } catch (IOException | SecurityException e) {
            throw new SafeTargetResolver.UnsafePathException("failed to read parent attributes: " + current + ": " + e.getMessage());
         }

         if (attrs.isSymbolicLink() || isReparsePoint(current, attrs)) {
            throw new SafeTargetResolver.UnsafePathException("link in parent chain: " + current);
         }

         if (!attrs.isDirectory()) {
            throw new SafeTargetResolver.UnsafePathException("parent is not a directory: " + current);
         }
      }
   }

   private static boolean isReparsePoint(Path path, BasicFileAttributes attrs) {
      // JDK isSymbolicLink() does NOT report NTFS junctions
      // (IO_REPARSE_TAG_MOUNT_POINT) as symlinks, and this JDK build does not expose
      // the dos:reparsePoint attribute. Under NOFOLLOW, a junction is reported as
      // isOther() while being isDirectory()==true — a combination a plain directory
      // never has. Fail closed on any such type uncertainty so a junction parent
      // chain cannot let writes escape the root onto another directory/volume.
      if (attrs.isSymbolicLink()) {
         return false; // already handled by the caller's isSymbolicLink check
      }
      return attrs.isOther();
   }

   public static final class UnsafePathException extends Exception {
      public UnsafePathException(String message) {
         super(message);
      }
   }
}
