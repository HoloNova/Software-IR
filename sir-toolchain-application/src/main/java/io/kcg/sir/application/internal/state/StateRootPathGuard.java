package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class StateRootPathGuard {
   private StateRootPathGuard() {
   }

   public static StateRootPathGuard.Result validate(Path stateRoot, Path outputRoot, boolean requireExistingStateRoot) {
      Objects.requireNonNull(stateRoot, "stateRoot");
      Objects.requireNonNull(outputRoot, "outputRoot");
      List<ChangeExecutionDiagnostic> errors = new ArrayList<>();
      if (!stateRoot.isAbsolute()) {
         errors.add(error("SIR-APP-CHANGE-BASELINE-006", "stateRoot must be an absolute path: " + stateRoot));
         return new StateRootPathGuard.Result(null, null, null, errors);
      }

      if (!outputRoot.isAbsolute()) {
         errors.add(error("SIR-APP-CHANGE-BASELINE-006", "outputRoot must be an absolute path: " + outputRoot));
         return new StateRootPathGuard.Result(null, null, null, errors);
      }

      Path rawStateRoot = stateRoot.toAbsolutePath();
      Path normalizedStateRoot = rawStateRoot.normalize();
      Path rawOutputRoot = outputRoot.toAbsolutePath();
      Path normalizedOutputRoot = rawOutputRoot.normalize();
      if (!isSameOrAncestor(normalizedStateRoot, normalizedOutputRoot) && !isSameOrAncestor(normalizedOutputRoot, normalizedStateRoot)) {
         try {
            String stateLink = checkDualChainSymlinks(rawStateRoot, normalizedStateRoot, requireExistingStateRoot);
            if (stateLink != null) {
               errors.add(error("SIR-APP-CHANGE-BASELINE-006", "stateRoot symlink check failed: " + stateLink));
               return new StateRootPathGuard.Result(null, null, null, errors);
            }
         } catch (IOException | SecurityException e) {
            errors.add(error("SIR-APP-CHANGE-BASELINE-006", "I/O or security failure checking stateRoot chain: " + e.getMessage()));
            return new StateRootPathGuard.Result(null, null, null, errors);
         }

         try {
            String outputLink = checkDualChainSymlinks(rawOutputRoot, normalizedOutputRoot, true);
            if (outputLink != null) {
               errors.add(error("SIR-APP-CHANGE-BASELINE-006", "outputRoot symlink check failed: " + outputLink));
               return new StateRootPathGuard.Result(null, null, null, errors);
            }
         } catch (IOException | SecurityException e) {
            errors.add(error("SIR-APP-CHANGE-BASELINE-006", "I/O or security failure checking outputRoot chain: " + e.getMessage()));
            return new StateRootPathGuard.Result(null, null, null, errors);
         }

         try {
            requireDirectory(normalizedStateRoot, "stateRoot", requireExistingStateRoot, errors);
            if (!errors.isEmpty()) {
               return new StateRootPathGuard.Result(null, null, null, errors);
            }

            requireDirectory(normalizedOutputRoot, "outputRoot", true, errors);
            if (!errors.isEmpty()) {
               return new StateRootPathGuard.Result(null, null, null, errors);
            }
         } catch (IOException | SecurityException e) {
            errors.add(error("SIR-APP-CHANGE-BASELINE-006", "I/O or security failure reading root attributes: " + e.getMessage()));
            return new StateRootPathGuard.Result(null, null, null, errors);
         }

         FileStore outputStore;
         FileStore stateStore;
         try {
            stateStore = getFileStoreOfExisting(normalizedStateRoot);
            outputStore = Files.getFileStore(normalizedOutputRoot);
         } catch (IOException | SecurityException e) {
            errors.add(error("SIR-APP-CHANGE-BASELINE-006", "failed to read FileStore of stateRoot or outputRoot: " + e.getMessage()));
            return new StateRootPathGuard.Result(null, null, null, errors);
         }

         if (!stateStore.equals(outputStore)) {
            errors.add(
               error(
                  "SIR-APP-CHANGE-BASELINE-006",
                  "stateRoot and outputRoot must reside on the same FileStore; stateRoot=" + stateStore.name() + " outputRoot=" + outputStore.name()
               )
            );
            return new StateRootPathGuard.Result(null, null, null, errors);
         } else {
            return new StateRootPathGuard.Result(normalizedStateRoot, normalizedOutputRoot, stateStore, List.of());
         }
      } else {
         errors.add(
            error(
               "SIR-APP-CHANGE-BASELINE-006",
               "stateRoot and outputRoot must not contain each other: stateRoot=" + normalizedStateRoot + " outputRoot=" + normalizedOutputRoot
            )
         );
         return new StateRootPathGuard.Result(null, null, null, errors);
      }
   }

   private static String checkDualChainSymlinks(Path raw, Path normalized, boolean requireExists) throws IOException {
      String rawLink = walkParentChainForSymlink(raw);
      if (rawLink != null) {
         return "raw chain: " + rawLink;
      }

      String normLink = walkParentChainForSymlink(normalized);
      if (normLink != null) {
         return "normalized chain: " + normLink;
      }

      if (requireExists) {
         BasicFileAttributes attrs;
         try {
            attrs = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         } catch (NoSuchFileException e) {
            return "leaf does not exist: " + normalized;
         }

         if (attrs.isSymbolicLink()) {
            return "leaf is a symlink: " + normalized;
         }
      } else {
         Path existing = findExistingAncestor(normalized);
         if (existing != null) {
            BasicFileAttributes attrs = Files.readAttributes(existing, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
               return "existing ancestor is a symlink: " + existing;
            }
         }
      }

      return null;
   }

   private static String walkParentChainForSymlink(Path path) throws IOException {
      for (Path current = path.getParent(); current != null; current = current.getParent()) {
         try {
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) {
               return current.toString();
            }
         } catch (NoSuchFileException var3) {
         }
      }

      return null;
   }

   private static Path findExistingAncestor(Path path) throws IOException {
      for (Path current = path; current != null; current = current.getParent()) {
         try {
            Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return current;
         } catch (NoSuchFileException e) {
         }
      }

      return null;
   }

   private static void requireDirectory(Path root, String label, boolean requireExists, List<ChangeExecutionDiagnostic> errors) throws IOException {
      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         if (requireExists) {
            errors.add(error("SIR-APP-CHANGE-BASELINE-006", label + " does not exist: " + root));
         }

         return;
      }

      if (attrs.isSymbolicLink()) {
         errors.add(error("SIR-APP-CHANGE-BASELINE-006", label + " must not be a symbolic link: " + root));
      } else {
         if (!attrs.isDirectory()) {
            errors.add(error("SIR-APP-CHANGE-BASELINE-006", label + " must be a directory: " + root));
         }
      }
   }

   private static boolean isSameOrAncestor(Path ancestor, Path descendant) {
      return ancestor.equals(descendant) ? true : descendant.startsWith(ancestor);
   }

   private static FileStore getFileStoreOfExisting(Path path) throws IOException {
      try {
         return Files.getFileStore(path);
      } catch (NoSuchFileException e) {
         Path existing = findExistingAncestor(path);
         if (existing == null) {
            throw e;
         } else {
            return Files.getFileStore(existing);
         }
      }
   }

   private static ChangeExecutionDiagnostic error(String code, String message) {
      return new ChangeExecutionDiagnostic(code, ChangeExecutionStage.BASELINE, ExecutionSeverity.ERROR, message, Optional.empty());
   }

   public record Result(Path normalizedStateRoot, Path normalizedOutputRoot, FileStore fileStore, List<ChangeExecutionDiagnostic> errors) {
      public Result {
         errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
      }

      public boolean isSuccess() {
         return this.normalizedStateRoot != null && this.normalizedOutputRoot != null && this.fileStore != null;
      }
   }
}
