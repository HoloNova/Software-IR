package io.kcg.cli.boundary;

import io.kcg.sir.application.internal.Sha256;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A deliberately non-conforming class used to prove the boundary check is not vacuous.
 *
 * <p>It breaks every rule {@link CliProductionBoundaryTest} enforces: it reaches into the
 * Application's internal package and it does file I/O directly instead of going through the typed
 * Application API. If the scan does not report both, the scan is not reading what it claims to read.
 *
 * <p>Never referenced by production code and never executed; it exists only so its constant pool can
 * be inspected.
 */
@SuppressWarnings({"unused", "checkstyle:UnusedImports"})
final class CliBoundaryProbe {

    private CliBoundaryProbe() {
    }

    /** Reaches into the Application's internal package. */
    static Object forbiddenInternalAccess(Path anyPath) {
        return Sha256.hexDigest(anyPath.toString().getBytes());
    }

    /** Does file I/O directly instead of using the typed Application API. */
    static boolean forbiddenDirectIo(File file) throws Exception {
        return Files.exists(file.toPath());
    }
}
