package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InputFileGuard {
   private InputFileGuard() {
   }

   public static List<ExecutionDiagnostic> validate(Path file) {
      List<ExecutionDiagnostic> errors = new ArrayList<>();
      if (!file.isAbsolute()) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-001", "input file must be an absolute path: " + file));
         return errors;
      }

      try {
         InputFileGuard.SafePathContext ctx = new InputFileGuard.SafePathContext(file);
         String originalChainLink = findSymlinkInParentChain(ctx.original());
         if (originalChainLink != null) {
            errors.add(diag("SIR-APP-CHANGE-PROTECT-003", "symbolic link in input file original parent chain: " + originalChainLink));
            return errors;
         } else {
            String normalizedChainLink = findSymlinkInParentChain(ctx.normalized());
            if (normalizedChainLink != null) {
               errors.add(diag("SIR-APP-CHANGE-PROTECT-003", "symbolic link in input file normalized parent chain: " + normalizedChainLink));
               return errors;
            } else {
               Path normalized = ctx.normalized();
               if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-002", "input file does not exist: " + normalized));
                  return errors;
               } else if (Files.isSymbolicLink(normalized)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-003", "input file must not be a symbolic link: " + normalized));
                  return errors;
               } else if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-004", "input file must not be a directory: " + normalized));
                  return errors;
               } else if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                  errors.add(diag("SIR-APP-CHANGE-PROTECT-005", "input file must be a regular file: " + normalized));
                  return errors;
               } else {
                  return errors;
               }
            }
         }
      } catch (SecurityException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "security manager denied input file check: " + e.getMessage()));
         return errors;
      }
   }

   public static Optional<byte[]> readSirFile(Path file, List<ExecutionDiagnostic> errors) {
      List<ExecutionDiagnostic> preErrors = validate(file);
      if (!preErrors.isEmpty()) {
         errors.addAll(preErrors);
         return Optional.empty();
      }

      Path normalized = new InputFileGuard.SafePathContext(file).normalized();

      byte[] bytes;
      try (InputStream in = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
         bytes = in.readAllBytes();
      } catch (NoSuchFileException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-002", "input file does not exist: " + normalized));
         return Optional.empty();
      } catch (SecurityException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "security manager denied read of input file: " + e.getMessage()));
         return Optional.empty();
      } catch (IOException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "failed to read input file: " + e.getMessage()));
         return Optional.empty();
      }

      List<ExecutionDiagnostic> postErrors = validate(file);
      if (!postErrors.isEmpty()) {
         errors.addAll(postErrors);
         return Optional.empty();
      } else {
         return Optional.of(bytes);
      }
   }

   public static Optional<byte[]> readBoundedSnapshot(Path file, long maxBytes, List<ExecutionDiagnostic> errors) {
      List<ExecutionDiagnostic> preErrors = validate(file);
      if (!preErrors.isEmpty()) {
         errors.addAll(preErrors);
         return Optional.empty();
      }

      Path normalized = new InputFileGuard.SafePathContext(file).normalized();

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-002", "baseline snapshot file does not exist: " + normalized));
         return Optional.empty();
      } catch (SecurityException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "security manager denied attribute read of baseline snapshot: " + e.getMessage()));
         return Optional.empty();
      } catch (IOException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "failed to read baseline snapshot file attributes: " + e.getMessage()));
         return Optional.empty();
      }

      if (attrs.isSymbolicLink()) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-003", "baseline snapshot file became a symbolic link: " + normalized));
         return Optional.empty();
      }

      if (!attrs.isRegularFile()) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-005", "baseline snapshot file is not a regular file: " + normalized));
         return Optional.empty();
      }

      long size = attrs.size();
      if (size > maxBytes) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-007", "baseline snapshot exceeds max bytes: size=" + size + " max=" + maxBytes + " file=" + normalized));
         return Optional.empty();
      }

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      boolean exceeded = false;

      try (InputStream in = Files.newInputStream(normalized, LinkOption.NOFOLLOW_LINKS)) {
         byte[] buf = new byte[8192];
         long total = 0L;

         int n;
         while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
               exceeded = true;
               break;
            }

            baos.write(buf, 0, n);
         }
      } catch (NoSuchFileException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-002", "baseline snapshot file does not exist: " + normalized));
         return Optional.empty();
      } catch (SecurityException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "security manager denied read of baseline snapshot: " + e.getMessage()));
         return Optional.empty();
      } catch (IOException e) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-006", "failed to read baseline snapshot file: " + e.getMessage()));
         return Optional.empty();
      }

      if (exceeded) {
         errors.add(diag("SIR-APP-CHANGE-PROTECT-007", "baseline snapshot exceeded max bytes during read: max=" + maxBytes + " file=" + normalized));
         return Optional.empty();
      } else {
         List<ExecutionDiagnostic> postErrors = validate(file);
         if (!postErrors.isEmpty()) {
            errors.addAll(postErrors);
            return Optional.empty();
         } else {
            return Optional.of(baos.toByteArray());
         }
      }
   }

   private static String findSymlinkInParentChain(Path path) {
      for (Path current = path.getParent(); current != null; current = current.getParent()) {
         if (Files.isSymbolicLink(current)) {
            return current.toString();
         }
      }

      return null;
   }

   private static ExecutionDiagnostic diag(String code, String message) {
      return new ExecutionDiagnostic(code, ExecutionStage.READ, ExecutionSeverity.ERROR, message, Optional.empty(), Optional.empty(), Optional.empty());
   }

   private record SafePathContext(Path original, Path normalized) {
      SafePathContext(Path file) {
         this(file.toAbsolutePath(), file.toAbsolutePath().normalize());
      }
   }
}
