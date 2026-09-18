package io.kcg.cli.boundary;

import io.kcg.sir.application.internal.bundle.BaselineBundleStore;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A deliberately non-conforming class used to prove the boundary check is not vacuous.
 *
 * <p>It breaks every rule {@link CliProductionBoundaryTest} enforces: it reaches into the
 * Application's internal package, it does file I/O directly instead of going through the typed
 * Application API, and it touches a CLI {@code mvp} type from what pretends to be a command-path
 * class. If the scan does not report all of that, the scan is not reading what it claims to read.
 *
 * <p>Never referenced by production code and never executed; it exists only so its constant pool can
 * be inspected.
 */
@SuppressWarnings({"unused", "checkstyle:UnusedImports"})
final class CliBoundaryProbe {

    private CliBoundaryProbe() {
    }

    /** Reaches into the Application's internal state package. */
    static Object forbiddenInternalAccess(Path stateRoot) {
        return new BaselineBundleStore(stateRoot);
    }

    /** Does file I/O directly instead of using the typed Application API. */
    static boolean forbiddenDirectIo(File file) throws Exception {
        return Files.exists(file.toPath());
    }

    /**
     * Names an {@code mvp} type the way a command-path class would if it referenced it.
     *
     * <p>A string constant rather than a real call, because the {@code mvp} types are
     * package-private: nothing outside their package can call them, which is itself part of why the
     * exception is bounded. The constant still lands in this class's pool, so the scan sees the same
     * reference it would see from a genuine (and illegal) caller.
     */
    static String forbiddenMvpAccess() {
        return "io/kcg/cli/mvp/MvpSanitize";
    }
}
