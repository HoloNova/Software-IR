package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.FileDeletion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PlanProtector {
   private PlanProtector() {
   }

   public static List<ExecutionDiagnostic> protect(Path outputRoot, List<FileChange> fileChanges) {
      List<ExecutionDiagnostic> errors = new ArrayList<>();
      Optional<Path> normalizedRootOpt = validateOutputRoot(outputRoot, errors);
      if (normalizedRootOpt.isEmpty()) {
         return errors;
      }

      Path rawRoot = outputRoot.toAbsolutePath();
      Path normalizedRoot = normalizedRootOpt.get();

      for (FileChange fc : fileChanges) {
         String rel = fc.relativePath();
         Path rawTarget = rawRoot.resolve(rel);

         try {
            String rawChainError = checkTargetParentChainSymlinks(rawTarget, normalizedRoot);
            if (rawChainError != null) {
               errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned path " + rel + ": " + rawChainError));
            } else {
               Path target = rawTarget.normalize();
               if (!target.startsWith(normalizedRoot)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-105", "planned relative path escapes output root after normalization: " + rel));
               } else {
                  String normChainError = checkTargetParentChainSymlinks(target, normalizedRoot);
                  if (normChainError != null) {
                     errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned path " + rel + ": " + normChainError));
                  } else {
                     BasicFileAttributes targetAttrs;
                     try {
                        targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                     } catch (NoSuchFileException e) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-107", "planned target does not exist (must already be generated): " + rel));
                        continue;
                     }

                     if (targetAttrs.isSymbolicLink()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned target must not be a symbolic link: " + rel));
                     } else if (targetAttrs.isDirectory()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-107", "planned target must be a regular file, not a directory: " + rel));
                     } else if (!targetAttrs.isRegularFile()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-107", "planned target must be a regular file: " + rel));
                     } else {
                        byte[] currentBytes = Files.readAllBytes(target);
                        if (currentBytes.length != fc.baseByteCount()) {
                           errors.add(
                              diag(
                                 "SIR-APP-CHANGE-PROTECT-108",
                                 "planned target on-disk byte count differs from base: path="
                                    + rel
                                    + " expected="
                                    + fc.baseByteCount()
                                    + " actual="
                                    + currentBytes.length
                              )
                           );
                        } else {
                           String actualSha256 = Sha256.hexDigest(currentBytes);
                           if (!actualSha256.equals(fc.baseSha256Hex())) {
                              errors.add(
                                 diag(
                                    "SIR-APP-CHANGE-PROTECT-109",
                                    "planned target on-disk SHA-256 differs from base: path="
                                       + rel
                                       + " expected="
                                       + fc.baseSha256Hex()
                                       + " actual="
                                       + actualSha256
                                 )
                              );
                           }
                        }
                     }
                  }
               }
            }
         } catch (IOException e) {
            errors.add(
               diag(
                  "SIR-APP-CHANGE-PROTECT-110", "I/O failure during protection check for " + rel + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
               )
            );
         } catch (SecurityException e) {
            errors.add(
               diag(
                  "SIR-APP-CHANGE-PROTECT-110",
                  "security violation during protection check for " + rel + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
               )
            );
         }
      }

      return List.copyOf(errors);
   }

   public static List<ExecutionDiagnostic> protectAdditions(Path outputRoot, List<FileAddition> fileAdditions) {
      List<ExecutionDiagnostic> errors = new ArrayList<>();
      Optional<Path> normalizedRootOpt = validateOutputRoot(outputRoot, errors);
      if (normalizedRootOpt.isEmpty()) {
         return errors;
      }

      Path rawRoot = outputRoot.toAbsolutePath();
      Path normalizedRoot = normalizedRootOpt.get();

      for (FileAddition fa : fileAdditions) {
         String rel = fa.relativePath();
         Path rawTarget = rawRoot.resolve(rel);

         try {
            String rawChainError = checkTargetParentChainSymlinks(rawTarget, normalizedRoot);
            if (rawChainError != null) {
               errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned addition path " + rel + ": " + rawChainError));
            } else {
               Path target = rawTarget.normalize();
               if (!target.startsWith(normalizedRoot)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-112", "planned addition relative path escapes output root after normalization: " + rel));
               } else {
                  String normChainError = checkTargetParentChainSymlinks(target, normalizedRoot);
                  if (normChainError != null) {
                     errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned addition path " + rel + ": " + normChainError));
                  } else {
                     BasicFileAttributes targetAttrs;
                     try {
                        targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                     } catch (NoSuchFileException e) {
                        continue;
                     }

                     if (targetAttrs.isSymbolicLink()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned addition target must not be a symbolic link: " + rel));
                     } else if (targetAttrs.isDirectory()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-111", "planned addition target already exists as a directory: " + rel));
                     } else if (targetAttrs.isRegularFile()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-111", "planned addition target already exists as a regular file: " + rel));
                     } else {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-111", "planned addition target already exists as a special file: " + rel));
                     }
                  }
               }
            }
         } catch (IOException e) {
            errors.add(
               diag(
                  "SIR-APP-CHANGE-PROTECT-110", "I/O failure during protection check for " + rel + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
               )
            );
         } catch (SecurityException e) {
            errors.add(
               diag(
                  "SIR-APP-CHANGE-PROTECT-110",
                  "security violation during protection check for " + rel + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
               )
            );
         }
      }

      return List.copyOf(errors);
   }

   public static List<ExecutionDiagnostic> protectDeletions(Path outputRoot, List<FileDeletion> fileDeletions) {
      List<ExecutionDiagnostic> errors = new ArrayList<>();
      Optional<Path> normalizedRootOpt = validateOutputRoot(outputRoot, errors);
      if (normalizedRootOpt.isEmpty()) {
         return errors;
      }

      Path rawRoot = outputRoot.toAbsolutePath();
      Path normalizedRoot = normalizedRootOpt.get();

      for (FileDeletion fd : fileDeletions) {
         String rel = fd.relativePath();
         Path rawTarget = rawRoot.resolve(rel);

         try {
            String rawChainError = checkTargetParentChainSymlinks(rawTarget, normalizedRoot);
            if (rawChainError != null) {
               errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned deletion path " + rel + ": " + rawChainError));
            } else {
               Path target = rawTarget.normalize();
               if (!target.startsWith(normalizedRoot)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-105", "planned deletion relative path escapes output root after normalization: " + rel));
               } else {
                  String normChainError = checkTargetParentChainSymlinks(target, normalizedRoot);
                  if (normChainError != null) {
                     errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned deletion path " + rel + ": " + normChainError));
                  } else {
                     BasicFileAttributes targetAttrs;
                     try {
                        targetAttrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                     } catch (NoSuchFileException e) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-107", "planned deletion target does not exist (must still be present): " + rel));
                        continue;
                     }

                     if (targetAttrs.isSymbolicLink()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-106", "planned deletion target must not be a symbolic link: " + rel));
                     } else if (targetAttrs.isDirectory()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-107", "planned deletion target must be a regular file, not a directory: " + rel));
                     } else if (!targetAttrs.isRegularFile()) {
                        errors.add(diag("SIR-APP-CHANGE-PROTECT-107", "planned deletion target must be a regular file: " + rel));
                     } else {
                        byte[] currentBytes = Files.readAllBytes(target);
                        if (currentBytes.length != fd.baseByteCount()) {
                           errors.add(
                              diag(
                                 "SIR-APP-CHANGE-PROTECT-108",
                                 "planned deletion on-disk byte count differs from base: path="
                                    + rel
                                    + " expected="
                                    + fd.baseByteCount()
                                    + " actual="
                                    + currentBytes.length
                              )
                           );
                        } else {
                           String actualSha256 = Sha256.hexDigest(currentBytes);
                           if (!actualSha256.equals(fd.baseSha256Hex())) {
                              errors.add(
                                 diag(
                                    "SIR-APP-CHANGE-PROTECT-109",
                                    "planned deletion on-disk SHA-256 differs from base: path="
                                       + rel
                                       + " expected="
                                       + fd.baseSha256Hex()
                                       + " actual="
                                       + actualSha256
                                 )
                              );
                           }
                        }
                     }
                  }
               }
            }
         } catch (IOException e) {
            errors.add(
               diag(
                  "SIR-APP-CHANGE-PROTECT-110", "I/O failure during protection check for " + rel + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
               )
            );
         } catch (SecurityException e) {
            errors.add(
               diag(
                  "SIR-APP-CHANGE-PROTECT-110",
                  "security violation during protection check for " + rel + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
               )
            );
         }
      }

      return List.copyOf(errors);
   }

   private static Optional<Path> validateOutputRoot(Path outputRoot, List<ExecutionDiagnostic> errors) {
      if (!outputRoot.isAbsolute()) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-101", "output root must be an absolute path: " + outputRoot));
         return Optional.empty();
      }

      BasicFileAttributes rootAttrs;
      try {
         rootAttrs = Files.readAttributes(outputRoot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-102", "output root does not exist: " + outputRoot));
         return Optional.empty();
      } catch (IOException e) {
         errors.add(
            diag(
               "SIR-APP-CHANGE-PROTECT-110",
               "I/O failure reading output root attributes: " + outputRoot + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
            )
         );
         return Optional.empty();
      } catch (SecurityException e) {
         errors.add(
            diag(
               "SIR-APP-CHANGE-PROTECT-110",
               "security violation reading output root attributes: " + outputRoot + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
            )
         );
         return Optional.empty();
      }

      if (rootAttrs.isSymbolicLink()) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-103", "output root must not be a symbolic link: " + outputRoot));
         return Optional.empty();
      }

      Path rawRoot = outputRoot.toAbsolutePath();
      Path normalizedRoot = rawRoot.normalize();

      try {
         String rawChainError = checkExistingParentChainSymlinks(rawRoot);
         if (rawChainError != null) {
            errors.add(diag("SIR-APP-CHANGE-PROTECT-103", "raw chain: " + rawChainError));
            return Optional.empty();
         }

         String normalizedChainError = checkExistingParentChainSymlinks(normalizedRoot);
         if (normalizedChainError != null) {
            errors.add(diag("SIR-APP-CHANGE-PROTECT-103", "normalized chain: " + normalizedChainError));
            return Optional.empty();
         }
      } catch (IOException e) {
         errors.add(
            diag(
               "SIR-APP-CHANGE-PROTECT-110",
               "I/O failure checking output root parent chain: " + outputRoot + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
            )
         );
         return Optional.empty();
      } catch (SecurityException e) {
         errors.add(
            diag(
               "SIR-APP-CHANGE-PROTECT-110",
               "security violation checking output root parent chain: " + outputRoot + ": " + e.getClass().getSimpleName() + ": " + e.getMessage()
            )
         );
         return Optional.empty();
      }

      if (!rootAttrs.isDirectory()) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-104", "output root must be a directory: " + outputRoot));
         return Optional.empty();
      } else {
         return Optional.of(normalizedRoot);
      }
   }

   private static String checkExistingParentChainSymlinks(Path rootCandidate) throws IOException {
      Path existing = findExistingAncestor(rootCandidate);

      for (Path current = existing; current != null; current = current.getParent()) {
         BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (attrs.isSymbolicLink()) {
            return "symbolic link in output root existing parent chain: " + current;
         }
      }

      return null;
   }

   private static String checkTargetParentChainSymlinks(Path target, Path root) throws IOException {
      Path existing = findExistingAncestor(target);

      for (Path current = existing; current != null && !current.equals(root); current = current.getParent()) {
         BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (attrs.isSymbolicLink()) {
            return "symbolic link in target existing parent chain: " + current;
         }

         if (current.equals(existing) && !current.equals(target) && !attrs.isDirectory()) {
            throw new NotDirectoryException(current.toString());
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

   private static ExecutionDiagnostic diag(String code, String message) {
      return new ExecutionDiagnostic(code, ExecutionStage.GRAPH, ExecutionSeverity.ERROR, message, Optional.empty(), Optional.empty(), Optional.empty());
   }
}
