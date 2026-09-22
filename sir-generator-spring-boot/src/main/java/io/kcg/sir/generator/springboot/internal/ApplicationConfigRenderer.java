package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import java.util.Optional;

/**
 * The generated application configuration.
 *
 * <p>Refusing unknown request properties is part of the transport contract: a client that sends a
 * property the payload does not declare is told so instead of having the value silently dropped.
 */
final class ApplicationConfigRenderer {
    private ApplicationConfigRenderer() {
    }

    static GeneratedFile render(ProjectArtifact.ApplicationConfig artifact) {
        StringBuilder out = new StringBuilder();
        out.append("spring:\n");
        out.append("  application:\n");
        out.append("    name: ").append(artifact.applicationName()).append('\n');
        if (artifact.rejectUnknownRequestProperties()) {
            out.append("  jackson:\n");
            out.append("    deserialization:\n");
            out.append("      fail-on-unknown-properties: true\n");
        }

        return new GeneratedFile(artifact.path(), out.toString(), artifact.id(), Optional.empty());
    }
}
