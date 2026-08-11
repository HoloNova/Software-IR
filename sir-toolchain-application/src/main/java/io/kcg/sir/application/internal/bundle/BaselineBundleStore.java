package io.kcg.sir.application.internal.bundle;

import io.kcg.sir.application.internal.Sha256;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class BaselineBundleStore {
   public static final String DESCRIPTOR_FILE = "descriptor.kcg-baseline";
   public static final String SOURCE_FILE = "source.sir";
   public static final String SNAPSHOT_FILE = "graph.kcg-psg";
   public static final String CURRENT_FILE = "CURRENT";
   private final Path stateRoot;

   public BaselineBundleStore(Path stateRoot) {
      this.stateRoot = Objects.requireNonNull(stateRoot, "stateRoot").toAbsolutePath().normalize();
   }

   public Path stateRoot() {
      return this.stateRoot;
   }

   public Path baselinesDir() {
      return this.stateRoot.resolve("baselines");
   }

   public Path bundleDir(String baselineId) {
      return this.baselinesDir().resolve(baselineId);
   }

   public Path currentFile() {
      return this.stateRoot.resolve("CURRENT");
   }

   public BaselineBundleStore.CurrentReadResult readCurrent() {
      Path current = this.currentFile();

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return new BaselineBundleStore.CurrentReadResult.Absent();
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.CurrentReadResult.Failure(
            "SIR-APP-CHANGE-BASELINE-002", "failed to read CURRENT: " + e.getClass().getSimpleName() + ": " + e.getMessage()
         );
      }

      if (attrs.isSymbolicLink()) {
         return new BaselineBundleStore.CurrentReadResult.Failure("SIR-APP-CHANGE-BASELINE-002", "CURRENT must not be a symbolic link: " + current);
      }

      if (!attrs.isRegularFile()) {
         return new BaselineBundleStore.CurrentReadResult.Failure("SIR-APP-CHANGE-BASELINE-002", "CURRENT must be a regular file: " + current);
      }

      byte[] bytes;
      try {
         bytes = Files.readAllBytes(current);
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.CurrentReadResult.Failure(
            "SIR-APP-CHANGE-BASELINE-002", "failed to read CURRENT bytes: " + e.getClass().getSimpleName() + ": " + e.getMessage()
         );
      }

      if (bytes.length != 65) {
         return new BaselineBundleStore.CurrentReadResult.Failure(
            "SIR-APP-CHANGE-BASELINE-002", "CURRENT must be exactly 65 bytes (64 hex + LF), got " + bytes.length
         );
      }

      if (bytes[64] != 10) {
         return new BaselineBundleStore.CurrentReadResult.Failure("SIR-APP-CHANGE-BASELINE-002", "CURRENT must end with LF");
      }

      String id = new String(bytes, 0, 64, StandardCharsets.US_ASCII);
      return !id.matches("[0-9a-f]{64}")
         ? new BaselineBundleStore.CurrentReadResult.Failure(
            "SIR-APP-CHANGE-BASELINE-002", "CURRENT must contain a 64-char lowercase hex baseline id, got: " + id
         )
         : new BaselineBundleStore.CurrentReadResult.Present(id);
   }

   public void writeCurrentAtomic(String baselineId) throws IOException {
      this.writeCurrentNew(baselineId);
      this.moveCurrentNewToCurrent();
   }

   public void writeCurrentNew(String baselineId) throws IOException {
      Objects.requireNonNull(baselineId, "baselineId");
      if (!baselineId.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("baselineId must be 64-char lowercase hex");
      }

      Path temp = this.stateRoot.resolve("CURRENT.new");
      byte[] bytes = (baselineId + "\n").getBytes(StandardCharsets.US_ASCII);
      Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
   }

   public void moveCurrentNewToCurrent() throws IOException {
      Path temp = this.stateRoot.resolve("CURRENT.new");
      Path current = this.currentFile();

      try {
         Files.move(temp, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
         try {
            Files.deleteIfExists(temp);
         } catch (IOException var5) {
         }

         throw e;
      }
   }

   public BaselineBundleStore.LoadResult loadBundle(String baselineId) {
      Objects.requireNonNull(baselineId, "baselineId");
      if (!baselineId.matches("[0-9a-f]{64}")) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "baselineId must be 64-char lowercase hex: " + baselineId);
      }

      Path dir = this.bundleDir(baselineId);

      BasicFileAttributes dirAttrs;
      try {
         dirAttrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle directory does not exist: " + dir);
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "failed to read bundle directory attributes: " + e.getMessage());
      }

      if (dirAttrs.isSymbolicLink()) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle directory must not be a symlink: " + dir);
      }

      if (!dirAttrs.isDirectory()) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle path is not a directory: " + dir);
      }

      List<String> children;
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
         children = new ArrayList<>();
         stream.forEach(p -> children.add(p.getFileName().toString()));
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "failed to list bundle directory: " + e.getMessage());
      }

      if (children.size() == 3 && children.contains("descriptor.kcg-baseline") && children.contains("source.sir") && children.contains("graph.kcg-psg")) {
         byte[] sourceBytes;
         byte[] snapshotBytes;
         byte[] descriptorBytes;
         try {
            descriptorBytes = readRegularFileBytes(dir.resolve("descriptor.kcg-baseline"));
            sourceBytes = readRegularFileBytes(dir.resolve("source.sir"));
            snapshotBytes = readRegularFileBytes(dir.resolve("graph.kcg-psg"));
         } catch (IOException | SecurityException e) {
            return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "failed to read bundle file: " + e.getMessage());
         } catch (BaselineBundleStore.BundleFileException e) {
            return new BaselineBundleStore.LoadResult.Failure(e.code, e.getMessage());
         }

         BaselineDescriptorCodec.DecodeResult decodeResult = BaselineDescriptorCodec.decode(descriptorBytes);
         if (decodeResult instanceof BaselineDescriptorCodec.DecodeFailure f) {
            return new BaselineBundleStore.LoadResult.Failure(f.code(), f.message());
         } else {
            BaselineDescriptor descriptor = ((BaselineDescriptorCodec.DecodeOk)decodeResult).descriptor();
            if (sourceBytes.length != descriptor.sourceByteCount()) {
               return new BaselineBundleStore.LoadResult.Failure(
                  "SIR-APP-CHANGE-BASELINE-004", "source byte count mismatch: descriptor=" + descriptor.sourceByteCount() + " actual=" + sourceBytes.length
               );
            }

            String actualSourceSha = Sha256.hexDigest(sourceBytes);
            if (!actualSourceSha.equals(descriptor.sourceSha256Hex())) {
               return new BaselineBundleStore.LoadResult.Failure(
                  "SIR-APP-CHANGE-BASELINE-004", "source SHA-256 mismatch: descriptor=" + descriptor.sourceSha256Hex() + " actual=" + actualSourceSha
               );
            }

            if (snapshotBytes.length != descriptor.snapshotByteCount()) {
               return new BaselineBundleStore.LoadResult.Failure(
                  "SIR-APP-CHANGE-BASELINE-004",
                  "snapshot byte count mismatch: descriptor=" + descriptor.snapshotByteCount() + " actual=" + snapshotBytes.length
               );
            }

            String actualSnapshotSha = Sha256.hexDigest(snapshotBytes);
            if (!actualSnapshotSha.equals(descriptor.snapshotSha256Hex())) {
               return new BaselineBundleStore.LoadResult.Failure(
                  "SIR-APP-CHANGE-BASELINE-004", "snapshot SHA-256 mismatch: descriptor=" + descriptor.snapshotSha256Hex() + " actual=" + actualSnapshotSha
               );
            }

            BaselineBundle bundle;
            try {
               bundle = new BaselineBundle(descriptor, descriptorBytes, sourceBytes, snapshotBytes, baselineId);
            } catch (IllegalArgumentException e) {
               return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle integrity check failed: " + e.getMessage());
            }

            return new BaselineBundleStore.LoadResult.Success(bundle);
         }
      } else {
         return new BaselineBundleStore.LoadResult.Failure(
            "SIR-APP-CHANGE-BASELINE-003", "bundle directory must contain exactly descriptor.kcg-baseline, source.sir, graph.kcg-psg; got " + children
         );
      }
   }

   public BaselineBundleStore.LoadResult stageBundle(BaselineBundle bundle) {
      Objects.requireNonNull(bundle, "bundle");
      Path dir = this.bundleDir(bundle.baselineId());

      try {
         Files.createDirectories(this.baselinesDir());
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-PUBLISH-001", "failed to create baselines directory: " + e.getMessage());
      }

      BasicFileAttributes existing;
      try {
         existing = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         existing = null;
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-PUBLISH-001", "failed to check existing bundle dir: " + e.getMessage());
      }

      if (existing != null) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-PUBLISH-001", "bundle directory already exists (immutable): " + dir);
      }

      try {
         Files.createDirectory(dir);
         Files.write(
            dir.resolve("descriptor.kcg-baseline"),
            bundle.descriptorBytes(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
         );
         Files.write(
            dir.resolve("source.sir"),
            bundle.sourceBytes(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
         );
         Files.write(
            dir.resolve("graph.kcg-psg"),
            bundle.snapshotBytes(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
         );
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-PUBLISH-001", "failed to stage bundle files: " + e.getMessage());
      }

      return this.loadBundle(bundle.baselineId());
   }

   public BaselineBundleStore.LoadResult stageCandidateBundle(BaselineBundle bundle, Path candidateDir) {
      Objects.requireNonNull(bundle, "bundle");
      Objects.requireNonNull(candidateDir, "candidateDir");
      Path dir = candidateDir.toAbsolutePath().normalize();

      try {
         Files.createDirectories(dir);
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-PUBLISH-001", "failed to create candidate-baseline directory: " + e.getMessage());
      }

      try {
         Files.write(
            dir.resolve("descriptor.kcg-baseline"),
            bundle.descriptorBytes(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
         );
         Files.write(
            dir.resolve("source.sir"),
            bundle.sourceBytes(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
         );
         Files.write(
            dir.resolve("graph.kcg-psg"),
            bundle.snapshotBytes(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC
         );
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-PUBLISH-001", "failed to stage candidate bundle files: " + e.getMessage());
      }

      return this.loadBundleFromDir(dir, bundle.baselineId());
   }

   public void publishCandidateBundle(Path candidateDir, String baselineId) throws IOException {
      Objects.requireNonNull(candidateDir, "candidateDir");
      Objects.requireNonNull(baselineId, "baselineId");
      if (!baselineId.matches("[0-9a-f]{64}")) {
         throw new IllegalArgumentException("baselineId must be 64-char lowercase hex");
      }

      Path source = candidateDir.toAbsolutePath().normalize();
      Path target = this.bundleDir(baselineId);
      Files.createDirectories(this.baselinesDir());
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
         throw new IOException("target baseline directory already exists: " + target);
      }

      List<String> children = new ArrayList<>();

      try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
         stream.forEach(p -> children.add(p.getFileName().toString()));
      }

      if (children.size() == 3 && children.contains("descriptor.kcg-baseline") && children.contains("source.sir") && children.contains("graph.kcg-psg")) {
         try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
         }
      } else {
         throw new IOException("candidate directory does not contain a valid bundle: " + source);
      }
   }

   public BaselineBundleStore.LoadResult loadBundleFromDir(Path dir, String baselineId) {
      Objects.requireNonNull(dir, "dir");
      Objects.requireNonNull(baselineId, "baselineId");
      if (!baselineId.matches("[0-9a-f]{64}")) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "baselineId must be 64-char lowercase hex: " + baselineId);
      }

      Path resolvedDir = dir.toAbsolutePath().normalize();

      BasicFileAttributes dirAttrs;
      try {
         dirAttrs = Files.readAttributes(resolvedDir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle directory does not exist: " + resolvedDir);
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "failed to read bundle directory attributes: " + e.getMessage());
      }

      if (dirAttrs.isSymbolicLink()) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle directory must not be a symlink: " + resolvedDir);
      }

      if (!dirAttrs.isDirectory()) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle path is not a directory: " + resolvedDir);
      }

      List<String> children;
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(resolvedDir)) {
         children = new ArrayList<>();
         stream.forEach(p -> children.add(p.getFileName().toString()));
      } catch (IOException | SecurityException e) {
         return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "failed to list bundle directory: " + e.getMessage());
      }

      if (children.size() == 3 && children.contains("descriptor.kcg-baseline") && children.contains("source.sir") && children.contains("graph.kcg-psg")) {
         byte[] sourceBytes;
         byte[] snapshotBytes;
         byte[] descriptorBytes;
         try {
            descriptorBytes = readRegularFileBytes(resolvedDir.resolve("descriptor.kcg-baseline"));
            sourceBytes = readRegularFileBytes(resolvedDir.resolve("source.sir"));
            snapshotBytes = readRegularFileBytes(resolvedDir.resolve("graph.kcg-psg"));
         } catch (IOException | SecurityException e) {
            return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "failed to read bundle file: " + e.getMessage());
         } catch (BaselineBundleStore.BundleFileException e) {
            return new BaselineBundleStore.LoadResult.Failure(e.code, e.getMessage());
         }

         BaselineDescriptorCodec.DecodeResult decodeResult = BaselineDescriptorCodec.decode(descriptorBytes);
         if (decodeResult instanceof BaselineDescriptorCodec.DecodeFailure f) {
            return new BaselineBundleStore.LoadResult.Failure(f.code(), f.message());
         } else {
            BaselineDescriptor descriptor = ((BaselineDescriptorCodec.DecodeOk)decodeResult).descriptor();
            if (sourceBytes.length != descriptor.sourceByteCount()) {
               return new BaselineBundleStore.LoadResult.Failure(
                  "SIR-APP-CHANGE-BASELINE-004", "source byte count mismatch: descriptor=" + descriptor.sourceByteCount() + " actual=" + sourceBytes.length
               );
            }

            if (!Sha256.hexDigest(sourceBytes).equals(descriptor.sourceSha256Hex())) {
               return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-004", "source SHA-256 mismatch");
            }

            if (snapshotBytes.length != descriptor.snapshotByteCount()) {
               return new BaselineBundleStore.LoadResult.Failure(
                  "SIR-APP-CHANGE-BASELINE-004",
                  "snapshot byte count mismatch: descriptor=" + descriptor.snapshotByteCount() + " actual=" + snapshotBytes.length
               );
            }

            if (!Sha256.hexDigest(snapshotBytes).equals(descriptor.snapshotSha256Hex())) {
               return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-004", "snapshot SHA-256 mismatch");
            }

            BaselineBundle bundle;
            try {
               bundle = new BaselineBundle(descriptor, descriptorBytes, sourceBytes, snapshotBytes, baselineId);
            } catch (IllegalArgumentException e) {
               return new BaselineBundleStore.LoadResult.Failure("SIR-APP-CHANGE-BASELINE-003", "bundle integrity check failed: " + e.getMessage());
            }

            return new BaselineBundleStore.LoadResult.Success(bundle);
         }
      } else {
         return new BaselineBundleStore.LoadResult.Failure(
            "SIR-APP-CHANGE-BASELINE-003", "bundle directory must contain exactly descriptor.kcg-baseline, source.sir, graph.kcg-psg; got " + children
         );
      }
   }

   private static byte[] readRegularFileBytes(Path file) throws IOException, BaselineBundleStore.BundleFileException {
      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         throw new BaselineBundleStore.BundleFileException("SIR-APP-CHANGE-BASELINE-003", "bundle file missing: " + file);
      } catch (IOException | SecurityException e) {
         throw new BaselineBundleStore.BundleFileException(
            "SIR-APP-CHANGE-BASELINE-003", "failed to read bundle file attributes: " + file + ": " + e.getMessage()
         );
      }

      if (attrs.isSymbolicLink()) {
         throw new BaselineBundleStore.BundleFileException("SIR-APP-CHANGE-BASELINE-003", "bundle file must not be a symlink: " + file);
      }

      if (!attrs.isRegularFile()) {
         throw new BaselineBundleStore.BundleFileException("SIR-APP-CHANGE-BASELINE-003", "bundle file must be a regular file: " + file);
      }

      try {
         return Files.readAllBytes(file);
      } catch (IOException | SecurityException e) {
         throw new BaselineBundleStore.BundleFileException("SIR-APP-CHANGE-BASELINE-003", "failed to read bundle file bytes: " + file + ": " + e.getMessage());
      }
   }

   private static final class BundleFileException extends Exception {
      final String code;

      BundleFileException(String code, String message) {
         super(message);
         this.code = code;
      }
   }

   public sealed interface CurrentReadResult
      permits BaselineBundleStore.CurrentReadResult.Present,
      BaselineBundleStore.CurrentReadResult.Absent,
      BaselineBundleStore.CurrentReadResult.Failure {
      record Absent() implements BaselineBundleStore.CurrentReadResult {
      }

      record Failure(String code, String message) implements BaselineBundleStore.CurrentReadResult {
         public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
         }
      }

      record Present(String baselineId) implements BaselineBundleStore.CurrentReadResult {
         public Present {
            Objects.requireNonNull(baselineId, "baselineId");
            if (!baselineId.matches("[0-9a-f]{64}")) {
               throw new IllegalArgumentException("baselineId must be 64-char lowercase hex");
            }
         }
      }
   }

   public sealed interface LoadResult permits BaselineBundleStore.LoadResult.Success, BaselineBundleStore.LoadResult.Failure {
      record Failure(String code, String message) implements BaselineBundleStore.LoadResult {
         public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
         }
      }

      record Success(BaselineBundle bundle) implements BaselineBundleStore.LoadResult {
         public Success {
            Objects.requireNonNull(bundle, "bundle");
         }
      }
   }
}
