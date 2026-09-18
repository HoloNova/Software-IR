package io.kcg.sir.projectgraph.boundary;

import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Deliberate, test-only boundary violation used as a decoy for the Project Graph read-only gate.
 *
 * <p>The task of this class is to be wrong on purpose. It reaches four forbidden capabilities that
 * the Project Graph must never have:
 *
 * <ul>
 *   <li>the file system through {@code java.nio.file};
 *   <li>the process environment through {@code System.getenv} (a {@code java.lang.System} member);
 *   <li>a non-deterministic random source through {@code java.util.UUID};
 *   <li>a re-parse entry point through {@code io.kcg.sir.internal.DefaultSirParser}.
 * </ul>
 *
 * <p>The boundary gate must report all four when it scans this class file, which proves the gate is
 * not a test that can only ever return "no violations". The class lives under {@code src/test},
 * compiles into {@code target/test-classes}, and must never appear in the production class census.
 */
final class ForbiddenReferenceProbe {

    private ForbiddenReferenceProbe() {
    }

    static String describe(Path path, GraphNodeId.File fileId) {
        String environment = String.valueOf(System.getenv("KCG_BOUNDARY_PROBE"));
        String nonce = UUID.randomUUID().toString();
        String content;
        try {
            content = Files.readString(path);
        } catch (IOException e) {
            content = "";
        }
        String parsedBy = new DefaultSirParser().getClass().getName();
        return environment + nonce + content + parsedBy + fileId.canonicalKey() + System.lineSeparator();
    }
}
