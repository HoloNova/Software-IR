package io.kcg.sir.application.internal.state;

import io.kcg.sir.application.api.ChangeExecutionDiagnostic;
import io.kcg.sir.application.api.ChangeExecutionStage;
import io.kcg.sir.application.api.ExecutionSeverity;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class StateRootLock {
   public static final String LOCK_FILE = "LOCK";
   private static final ConcurrentHashMap<Path, StateRootLock.HeldLock> HELD_LOCKS = new ConcurrentHashMap<>();
   private final Path stateRoot;
   volatile Runnable beforeOpenHook = null;
   volatile Runnable afterLockHook = null;

   public StateRootLock(Path normalizedStateRoot) {
      this.stateRoot = Objects.requireNonNull(normalizedStateRoot, "normalizedStateRoot");
      if (!normalizedStateRoot.isAbsolute() || !normalizedStateRoot.equals(normalizedStateRoot.normalize())) {
         throw new IllegalArgumentException("stateRoot must be a normalized absolute path: " + normalizedStateRoot);
      }
   }

   public Path lockFile() {
      return this.stateRoot.resolve("LOCK");
   }

   public StateRootLock.AcquireResult tryAcquire() {
      Path lockPath = this.lockFile();

      try {
         ensureSafeLockFile(lockPath);
      } catch (IOException | SecurityException e) {
         return new StateRootLock.LockFailure(
            "SIR-APP-CHANGE-LOCK-002", "failed to validate LOCK file path: " + e.getClass().getSimpleName() + ": " + e.getMessage()
         );
      }

      FileChannel channel;
      try {
         channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, StandardOpenOption.CREATE);
      } catch (IOException | SecurityException e) {
         return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-002", "failed to open LOCK file: " + e.getClass().getSimpleName() + ": " + e.getMessage());
      }

      return this.acquireOnChannel(channel);
   }

   public StateRootLock.AcquireResult tryAcquireExisting() {
      Path lockPath = this.lockFile();

      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-003", "LOCK file does not exist; register first: " + lockPath);
      } catch (IOException | SecurityException e) {
         return new StateRootLock.LockFailure(
            "SIR-APP-CHANGE-LOCK-002", "failed to read existing LOCK attributes: " + e.getClass().getSimpleName() + ": " + e.getMessage()
         );
      }

      if (!attrs.isSymbolicLink() && !attrs.isOther() && attrs.isRegularFile()) {
         StateRootLock.LockIdentity identity = StateRootLock.LockIdentity.capture(lockPath, attrs);
         Runnable preHook = this.beforeOpenHook;
         if (preHook != null) {
            try {
               preHook.run();
            } catch (RuntimeException e) {
               return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-002", "pre-open hook fault: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
         }

         FileChannel channel;
         try {
            channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
         } catch (IOException | SecurityException e) {
            return new StateRootLock.LockFailure(
               "SIR-APP-CHANGE-LOCK-002", "failed to open existing LOCK file: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
         }

         StateRootLock.AcquireResult acquired = this.acquireOnChannel(channel);
         if (acquired instanceof StateRootLock.HeldLock held) {
            Runnable postHook = this.afterLockHook;
            if (postHook != null) {
               try {
                  postHook.run();
               } catch (RuntimeException e) {
                  held.close();
                  return new StateRootLock.LockFailure(
                     "SIR-APP-CHANGE-LOCK-002", "post-lock hook fault: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                  );
               }
            }

            try {
               if (!StateRootLock.LockIdentity.stillMatches(lockPath, identity)) {
                  held.close();
                  return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-002", "LOCK identity changed between validation and acquisition: " + lockPath);
               } else {
                  return held;
               }
            } catch (IOException | SecurityException e) {
               held.close();
               return new StateRootLock.LockFailure(
                  "SIR-APP-CHANGE-LOCK-002", "failed to re-verify LOCK identity after acquisition: " + e.getClass().getSimpleName() + ": " + e.getMessage()
               );
            }
         } else {
            return acquired;
         }
      } else {
         return new StateRootLock.LockFailure(
            "SIR-APP-CHANGE-LOCK-002",
            "LOCK must be an existing regular non-link file: "
               + lockPath
               + " (symbolicLink="
               + attrs.isSymbolicLink()
               + ", directory="
               + attrs.isDirectory()
               + ", other="
               + attrs.isOther()
               + ")"
         );
      }
   }

   private StateRootLock.AcquireResult acquireOnChannel(FileChannel channel) {
      StateRootLock.HeldLock existing = HELD_LOCKS.putIfAbsent(this.stateRoot, new StateRootLock.HeldLock(channel, null, this.stateRoot));
      if (existing != null) {
         try {
            channel.close();
         } catch (IOException var8) {
         }

         return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-001", "stateRoot already locked in this JVM: " + this.stateRoot);
      } else {
         FileLock fileLock;
         try {
            fileLock = channel.tryLock(0L, Long.MAX_VALUE, false);
         } catch (OverlappingFileLockException e) {
            HELD_LOCKS.remove(this.stateRoot);

            try {
               channel.close();
            } catch (IOException var7) {
            }

            return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-001", "stateRoot already locked in this JVM (overlapping): " + this.stateRoot);
         } catch (IOException | SecurityException e) {
            HELD_LOCKS.remove(this.stateRoot);

            try {
               channel.close();
            } catch (IOException var6) {
            }

            return new StateRootLock.LockFailure(
               "SIR-APP-CHANGE-LOCK-002", "failed to acquire OS file lock: " + e.getClass().getSimpleName() + ": " + e.getMessage()
            );
         }

         if (fileLock == null) {
            HELD_LOCKS.remove(this.stateRoot);

            try {
               channel.close();
            } catch (IOException var9) {
            }

            return new StateRootLock.LockFailure("SIR-APP-CHANGE-LOCK-001", "stateRoot is locked by another process: " + this.stateRoot);
         } else {
            StateRootLock.HeldLock held = new StateRootLock.HeldLock(channel, fileLock, this.stateRoot);
            HELD_LOCKS.replace(this.stateRoot, held);
            return held;
         }
      }
   }

   private static void ensureSafeLockFile(Path lockPath) throws IOException {
      BasicFileAttributes attrs;
      try {
         attrs = Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException e) {
         for (Path parent = lockPath.getParent(); parent != null; parent = parent.getParent()) {
            try {
               BasicFileAttributes pa = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
               if (pa.isSymbolicLink()) {
                  throw new IOException("symlink in LOCK parent chain: " + parent);
               }
            } catch (NoSuchFileException var5) {
            }
         }

         return;
      }

      if (attrs.isSymbolicLink()) {
         throw new IOException("LOCK file must not be a symbolic link: " + lockPath);
      }

      if (!attrs.isRegularFile()) {
         throw new IOException("LOCK file must be a regular file: " + lockPath);
      }
   }

   public sealed interface AcquireResult permits StateRootLock.HeldLock, StateRootLock.LockFailure {
   }

   public static final class HeldLock implements StateRootLock.AcquireResult, AutoCloseable {
      private final FileChannel channel;
      private final FileLock fileLock;
      private final Path stateRoot;
      private volatile boolean released;

      HeldLock(FileChannel channel, FileLock fileLock, Path stateRoot) {
         this.channel = channel;
         this.fileLock = fileLock;
         this.stateRoot = stateRoot;
      }

      public Path stateRoot() {
         return this.stateRoot;
      }

      public boolean isHeld() {
         return !this.released;
      }

      @Override
      public void close() {
         if (!this.released) {
            this.released = true;
            StateRootLock.HELD_LOCKS.remove(this.stateRoot, this);
            if (this.fileLock != null) {
               try {
                  this.fileLock.release();
               } catch (IOException var3) {
               }
            }

            try {
               this.channel.close();
            } catch (IOException var2) {
            }
         }
      }
   }

   public record LockFailure(String code, String message) implements StateRootLock.AcquireResult {
      public LockFailure {
         Objects.requireNonNull(code, "code");
         Objects.requireNonNull(message, "message");
      }

      public ChangeExecutionDiagnostic toDiagnostic() {
         return new ChangeExecutionDiagnostic(this.code, ChangeExecutionStage.BASELINE, ExecutionSeverity.ERROR, this.message, Optional.empty());
      }
   }

   private record LockIdentity(Path path, Object fileKey, long size, FileTime lastModifiedTime) {
      static StateRootLock.LockIdentity capture(Path lockPath, BasicFileAttributes attrs) {
         return new StateRootLock.LockIdentity(lockPath, attrs.fileKey(), attrs.size(), attrs.lastModifiedTime());
      }

      static boolean stillMatches(Path lockPath, StateRootLock.LockIdentity expected) throws IOException {
         if (!expected.path.equals(lockPath)) {
            return false;
         }

         BasicFileAttributes attrs = Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (!attrs.isSymbolicLink() && !attrs.isOther() && attrs.isRegularFile()) {
            Object key = attrs.fileKey();
            boolean keyBefore = expected.fileKey != null;
            boolean keyAfter = key != null;
            if (keyBefore && keyAfter) {
               if (!expected.fileKey.equals(key)) {
                  return false;
               }
            } else if (keyBefore || keyAfter) {
               return false;
            }

            return attrs.size() != expected.size ? false : expected.lastModifiedTime.equals(attrs.lastModifiedTime());
         } else {
            return false;
         }
      }
   }
}
