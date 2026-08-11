package io.kcg.sir.application.internal.bundle;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.internal.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class OutputManifestVerifier {
   private OutputManifestVerifier() {
   }

   public static List<ChangeExecutionDiagnostic> verify(Path outputRoot, List<BaselineManifestEntry> manifest, ChangeExecutionStage stage) {
      Objects.requireNonNull(outputRoot, "outputRoot");
      Objects.requireNonNull(manifest, "manifest");
      Objects.requireNonNull(stage, "stage");
      if (outputRoot.isAbsolute() && outputRoot.equals(outputRoot.normalize())) {
         List<ChangeExecutionDiagnostic> errors = new ArrayList<>();
         Path rawRoot = outputRoot.toAbsolutePath();

         // [RQ-09] Review finding B: reject duplicate relative paths up front.
         // Two entries for one path mean the plan/generator emitted an ambiguous
         // target; verifying the same file twice could mask a swap between the
         // two verifications. Fail closed instead.
         java.util.Set<String> seen = new java.util.HashSet<>();
         for (BaselineManifestEntry entry : manifest) {
            if (!seen.add(entry.relativePath())) {
               errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-007",
                     "duplicate manifest relative path: " + entry.relativePath(),
                     entry.relativePath()));
            }
         }
         if (!errors.isEmpty()) {
            return List.copyOf(errors);
         }

         for (BaselineManifestEntry entry : manifest) {
            verifyEntry(rawRoot, outputRoot, entry, stage, errors);
            if (!errors.isEmpty()) {
               break;
            }
         }

         return List.copyOf(errors);
      } else {
         throw new IllegalArgumentException("outputRoot must be a normalized absolute path: " + outputRoot);
      }
   }

   private static void verifyEntry(
      Path rawRoot, Path normalizedRoot, BaselineManifestEntry entry, ChangeExecutionStage stage, List<ChangeExecutionDiagnostic> errors
   ) {
      String rel = entry.relativePath();
      Path rawTarget = rawRoot.resolve(rel);
      String rawChainError = checkParentChainSymlinks(rawTarget, normalizedRoot);
      if (rawChainError != null) {
         errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-002", "tracked file " + rel + " raw chain symlink: " + rawChainError, rel));
      } else {
         Path target = rawTarget.normalize();
         if (!target.startsWith(normalizedRoot)) {
            errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-001", "tracked file escapes output root after normalization: " + rel, rel));
         } else {
            String normChainError = checkParentChainSymlinks(target, normalizedRoot);
            if (normChainError != null) {
               errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-002", "tracked file " + rel + " normalized chain symlink: " + normChainError, rel));
            } else {
               BasicFileAttributes attrs;
               try {
                  attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
               } catch (NoSuchFileException e) {
                  errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-003", "tracked file does not exist: " + rel, rel));
                  return;
               } catch (IOException | SecurityException e) {
                  errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-005", "failed to read tracked file attributes: " + rel + ": " + e.getMessage(), rel));
                  return;
               }

               if (attrs.isSymbolicLink()) {
                  errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-002", "tracked file must not be a symlink: " + rel, rel));
               } else if (!attrs.isRegularFile()) {
                  errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-003", "tracked file is not a regular file: " + rel, rel));
               } else {
                  long actualSize = attrs.size();
                  if (actualSize != entry.byteCount()) {
                     errors.add(
                        diag(
                           stage,
                           "SIR-APP-CHANGE-COMMIT-004",
                           "tracked file byte count mismatch: " + rel + " expected=" + entry.byteCount() + " actual=" + actualSize,
                           rel
                        )
                     );
                  } else {
                     byte[] bytes;
                     try {
                        bytes = Files.readAllBytes(target);
                     } catch (IOException | SecurityException e) {
                        errors.add(diag(stage, "SIR-APP-CHANGE-COMMIT-005", "failed to read tracked file bytes: " + rel + ": " + e.getMessage(), rel));
                        return;
                     }

                     String actualSha = Sha256.hexDigest(bytes);
                     if (!actualSha.equals(entry.sha256Hex())) {
                        errors.add(
                           diag(
                              stage,
                              "SIR-APP-CHANGE-COMMIT-004",
                              "tracked file SHA-256 mismatch: " + rel + " expected=" + entry.sha256Hex() + " actual=" + actualSha,
                              rel
                           )
                        );
                     }
                  }
               }
            }
         }
      }
   }

   private static String checkParentChainSymlinks(Path path, Path root) {
      for (Path current = path.getParent(); current != null && !current.equals(root) && current.startsWith(root); current = current.getParent()) {
         try {
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            // [RQ-09] Review finding B2: also reject NTFS junctions (isOther under NOFOLLOW).
            if (attrs.isSymbolicLink() || attrs.isOther()) {
               return current.toString();
            }
         } catch (NoSuchFileException var4) {
         } catch (IOException | SecurityException e) {
            return "I/O failure reading " + current + ": " + e.getMessage();
         }
      }

      return null;
   }

   private static ChangeExecutionDiagnostic diag(ChangeExecutionStage stage, String code, String message, String relativePath) {
      return new ChangeExecutionDiagnostic(code, stage, ExecutionSeverity.ERROR, message, Optional.of(relativePath));
   }
}
