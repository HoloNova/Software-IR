package io.kcg.sir.application.internal.state;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Run in a separate JVM: reports whether another process can take the OS lock of a file. JDK classes only. */
public final class LockProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock(0L, Long.MAX_VALUE, false);
            System.out.println(lock == null ? "LOCKED_BY_OTHER" : "FREE");
            if (lock != null) {
                lock.release();
            }
        }
    }

    private LockProbe() {
    }
}
