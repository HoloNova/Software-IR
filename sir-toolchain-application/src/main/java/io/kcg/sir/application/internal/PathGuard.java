package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.ConflictPolicy;
import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.api.ExecutionSeverity;
import io.kcg.sir.application.api.ExecutionStage;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PathGuard {
   private static final Set<String> RESERVED_NAMES = reservedNames();
   private static final String ILLEGAL_CHARS = "<>:\"|?*";

   private PathGuard() {
   }

   public static List<ExecutionDiagnostic> preflight(Path outputRoot, List<GeneratedFile> files, ConflictPolicy policy) {
      List<ExecutionDiagnostic> errors = new ArrayList<>();
      if (!outputRoot.isAbsolute()) {
         errors.add(diag("SIR-APP-REQUEST-002", "output root must be an absolute path: " + outputRoot));
         return errors;
      }

      for (GeneratedFile file : files) {
         String reason = validateRelativePath(file.relativePath());
         if (reason != null) {
            errors.add(diag("SIR-APP-PATH-001", "illegal generated path " + file.relativePath() + ": " + reason));
         }
      }

      if (!errors.isEmpty()) {
         return errors;
      }

      for (String dup : findCaseFoldDuplicates(files)) {
         errors.add(diag("SIR-APP-PATH-002", "duplicate target path after Locale.ROOT case fold: " + dup));
      }

      if (!errors.isEmpty()) {
         return errors;
      }

      try {
         String linkError = checkRootParentChainSymlinks(outputRoot);
         if (linkError != null) {
            errors.add(diag("SIR-APP-PATH-003", linkError));
            return errors;
         }

         for (GeneratedFile file : files) {
            String targetLink = checkTargetSymlinks(outputRoot, file.relativePath());
            if (targetLink != null) {
               errors.add(diag("SIR-APP-PATH-003", "symbolic link in target chain for " + file.relativePath() + ": " + targetLink));
            }
         }

         if (!errors.isEmpty()) {
            return errors;
         }

         for (GeneratedFile file : files) {
            Path target = outputRoot.resolve(file.relativePath());
            Path normalizedTarget = target.normalize();
            if (!normalizedTarget.startsWith(outputRoot)) {
               errors.add(diag("SIR-APP-PATH-004", "target escapes output root after normalization: " + file.relativePath()));
            }
         }

         if (!errors.isEmpty()) {
            return errors;
         }

         conflictCheck(outputRoot, files, policy, errors);
      } catch (IOException e) {
         errors.add(diag("SIR-APP-PATH-005", "I/O error during path preflight: " + e.getMessage()));
      }

      return errors;
   }

   private static String validateRelativePath(String path) {
      if (path.startsWith("/")) {
         return "path must not be absolute";
      }

      if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
         return "path must not contain drive letter";
      }

      if (path.indexOf(92) >= 0) {
         return "path must not contain backslash";
      }

      String[] segments = path.split("/", -1);

      for (String segment : segments) {
         if (segment.isEmpty()) {
            return "path must not contain empty segment";
         }

         if (".".equals(segment)) {
            return "path must not contain '.' segment";
         }

         if ("..".equals(segment)) {
            return "path must not contain '..' segment";
         }

         String segmentIssue = validateSegment(segment);
         if (segmentIssue != null) {
            return segmentIssue;
         }
      }

      return null;
   }

   private static String validateSegment(String segment) {
      for (int i = 0; i < segment.length(); i++) {
         char c = segment.charAt(i);
         if (c < ' ' || c == 127) {
            return "segment contains control character";
         }

         if ("<>:\"|?*".indexOf(c) >= 0) {
            return "segment contains illegal character '" + c + "'";
         }
      }

      char last = segment.charAt(segment.length() - 1);
      if (last != '.' && last != ' ') {
         String base = segment;
         int dot = segment.indexOf(46);
         if (dot > 0) {
            base = segment.substring(0, dot);
         }

         return RESERVED_NAMES.contains(base.toUpperCase(Locale.ROOT)) ? "segment uses Windows reserved name: " + base : null;
      } else {
         return "segment must not end with dot or space";
      }
   }

   private static List<String> findCaseFoldDuplicates(List<GeneratedFile> files) {
      Map<String, String> seen = new LinkedHashMap<>();
      Set<String> duplicates = new LinkedHashSet<>();

      for (GeneratedFile file : files) {
         String key = file.relativePath().toLowerCase(Locale.ROOT);
         if (seen.containsKey(key)) {
            duplicates.add(file.relativePath());
         } else {
            seen.put(key, file.relativePath());
         }
      }

      return new ArrayList<>(duplicates);
   }

   private static String checkRootParentChainSymlinks(Path outputRoot) throws IOException {
      Path absolute = outputRoot.toAbsolutePath().normalize();
      Path existing = findExistingAncestor(absolute);

      for (Path current = existing; current != null; current = current.getParent()) {
         if (isLinkLike(current)) {
            return "symbolic link or reparse point in output root chain: " + current;
         }
      }

      return Files.exists(absolute, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(absolute) ? "output root is a symbolic link: " + absolute : null;
   }

   private static String checkTargetSymlinks(Path outputRoot, String relativePath) throws IOException {
      Path target = outputRoot.resolve(relativePath).normalize();
      Path existing = findExistingAncestor(target);

      for (Path current = existing; current != null && !current.equals(outputRoot); current = current.getParent()) {
         if (isLinkLike(current)) {
            return "symbolic link or reparse point in target parent chain: " + current;
         }
      }

      return Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target) ? "target is a symbolic link: " + target : null;
   }

   private static boolean isLinkLike(Path path) {
      // isSymbolicLink() alone does not report NTFS
      // junctions (JDK reports them as isOther under NOFOLLOW). Fail closed on
      // any link-or-other type in the chain.
      try {
         java.nio.file.attribute.BasicFileAttributes attrs = Files.readAttributes(
               path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         return attrs.isSymbolicLink() || attrs.isOther();
      } catch (IOException e) {
         return false; // caller's exists(NOFOLLOW) checks already handled absence
      }
   }

   private static Path findExistingAncestor(Path path) {
      Path current = path;

      while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
         current = current.getParent();
      }

      return current;
   }

   private static void conflictCheck(Path outputRoot, List<GeneratedFile> files, ConflictPolicy policy, List<ExecutionDiagnostic> errors) throws IOException {
      boolean rootExists = Files.exists(outputRoot, LinkOption.NOFOLLOW_LINKS);
      if (policy == ConflictPolicy.FAIL_IF_EXISTS) {
         for (GeneratedFile file : files) {
            Path target = outputRoot.resolve(file.relativePath()).normalize();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
               errors.add(diag("SIR-APP-CONFLICT-001", "target already exists and policy is FAIL_IF_EXISTS: " + file.relativePath()));
            }
         }
      } else {
         for (GeneratedFile file : files) {
            Path target = outputRoot.resolve(file.relativePath()).normalize();
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
               if (Files.isSymbolicLink(target)) {
                  errors.add(diag("SIR-APP-CONFLICT-002", "cannot replace symbolic link: " + file.relativePath()));
               } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                  errors.add(diag("SIR-APP-CONFLICT-002", "cannot replace directory: " + file.relativePath()));
               } else if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                  errors.add(diag("SIR-APP-CONFLICT-002", "cannot replace non-regular file: " + file.relativePath()));
               }
            }
         }

         if (rootExists && errors.isEmpty()) {
         }
      }
   }

   private static ExecutionDiagnostic diag(String code, String message) {
      return new ExecutionDiagnostic(code, ExecutionStage.PREFLIGHT, ExecutionSeverity.ERROR, message, Optional.empty(), Optional.empty(), Optional.empty());
   }

   private static Set<String> reservedNames() {
      Set<String> names = new LinkedHashSet<>();
      names.add("CON");
      names.add("PRN");
      names.add("AUX");
      names.add("NUL");

      for (int i = 1; i <= 9; i++) {
         names.add("COM" + i);
         names.add("LPT" + i);
      }

      return names;
   }
}
