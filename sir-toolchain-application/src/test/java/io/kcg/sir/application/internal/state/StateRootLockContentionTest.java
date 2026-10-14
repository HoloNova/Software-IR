package io.kcg.sir.application.internal.state;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lock held by one caller must stay held for other processes even when a second caller in the same JVM is
 * refused. Closing any channel of a file releases every OS lock this JVM holds on it, so a refused second caller
 * must not open the file at all.
 */
class StateRootLockContentionTest {
    @TempDir Path temp;

    /** Starts a second JVM that tries to take the OS lock of {@code lockFile}. */
    private static String otherProcessSees(Path lockFile) throws Exception {
        Path classes = Path.of(LockProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classes.toString(),
                LockProbe.class.getName(), lockFile.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe JVM did not finish");
            return new String(process.getInputStream().readAllBytes()).strip();
        } finally {
            process.destroyForcibly();
        }
    }

    private Path newState() throws IOException {
        Path root = Files.createDirectories(temp.resolve("state"));
        Files.createFile(root.resolve("LOCK"));
        return root;
    }

    @Test void aRefusedSecondCallerInTheSameJvmDoesNotReleaseTheOsLockOfTheFirst() throws Exception {
        Path root = newState();
        try (var held = (StateRootLock.HeldLock) new StateRootLock(root).tryAcquireExisting()) {
            assertEquals("LOCKED_BY_OTHER", otherProcessSees(root.resolve("LOCK")), "sanity: the holder's lock is visible to another process");

            var second = new StateRootLock(root).tryAcquireExisting();
            assertEquals("SIR-APP-CHANGE-LOCK-001", assertInstanceOf(StateRootLock.LockFailure.class, second).code());
            var third = new StateRootLock(root).tryAcquire();
            assertEquals("SIR-APP-CHANGE-LOCK-001", assertInstanceOf(StateRootLock.LockFailure.class, third).code());

            assertTrue(held.isHeld());
            assertEquals("LOCKED_BY_OTHER", otherProcessSees(root.resolve("LOCK")), "a refused caller must not drop the holder's lock for other processes");
        }

        assertEquals("FREE", otherProcessSees(root.resolve("LOCK")), "released after the holder closes");
        assertInstanceOf(StateRootLock.HeldLock.class, new StateRootLock(root).tryAcquireExisting());
    }

    @Test void theLockCanBeTakenAgainAfterEveryRefusal() throws Exception {
        Path root = newState();
        List<StateRootLock.AcquireResult> refusals = new java.util.ArrayList<>();
        try (var held = (StateRootLock.HeldLock) new StateRootLock(root).tryAcquireExisting()) {
            for (int i = 0; i < 3; i++) {
                refusals.add(new StateRootLock(root).tryAcquireExisting());
            }

            assertTrue(refusals.stream().allMatch(r -> r instanceof StateRootLock.LockFailure));
            assertTrue(held.isHeld());
        }

        try (var again = (StateRootLock.HeldLock) new StateRootLock(root).tryAcquireExisting()) {
            assertTrue(again.isHeld());
        }
    }

    @Test void anotherSpellingOfTheSameDirectoryIsTheSameLock() throws Exception {
        Path root = newState();
        Path alias = Files.createSymbolicLink(temp.resolve("alias"), root);
        Path hardLinked = Files.createDirectories(temp.resolve("twin"));
        Files.createLink(hardLinked.resolve("LOCK"), root.resolve("LOCK"));

        try (var held = (StateRootLock.HeldLock) new StateRootLock(root).tryAcquireExisting()) {
            assertEquals("SIR-APP-CHANGE-LOCK-001", assertInstanceOf(StateRootLock.LockFailure.class, new StateRootLock(alias).tryAcquireExisting()).code(),
                    "a symlinked spelling of the directory");
            assertEquals("SIR-APP-CHANGE-LOCK-001", assertInstanceOf(StateRootLock.LockFailure.class, new StateRootLock(hardLinked).tryAcquireExisting()).code(),
                    "a hard link to the same LOCK file");
            assertEquals("LOCKED_BY_OTHER", otherProcessSees(root.resolve("LOCK")), "no refused spelling may free the holder's lock");
            assertTrue(held.isHeld());
        }

        assertEquals("FREE", otherProcessSees(root.resolve("LOCK")));
    }

    @Test void manyThreadsRefusedAtOnceNeverFreeTheHoldersLock() throws Exception {
        Path root = newState();
        try (var held = (StateRootLock.HeldLock) new StateRootLock(root).tryAcquireExisting()) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            try {
                List<java.util.concurrent.Future<Integer>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(pool.submit(() -> {
                        int refused = 0;
                        for (int n = 0; n < 40; n++) {
                            if (new StateRootLock(root).tryAcquireExisting() instanceof StateRootLock.LockFailure) {
                                refused++;
                            }
                        }

                        return refused;
                    }));
                }

                for (var future : futures) {
                    assertEquals(40, future.get(60, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals("LOCKED_BY_OTHER", otherProcessSees(root.resolve("LOCK")));
            assertTrue(held.isHeld());
        }

        assertEquals("FREE", otherProcessSees(root.resolve("LOCK")));
    }
}
