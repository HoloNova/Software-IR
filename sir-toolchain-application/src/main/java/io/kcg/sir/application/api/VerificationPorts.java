package io.kcg.sir.application.api;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the verification needs from the outside world, as ports. The application knows none of Maven,
 * Spring or MySQL: a port says "build this directory", "start this artifact", "judge this running application".
 * Each port reports its own failure as a {@link StageOutcome}; one that throws is reported as a failed stage
 * and everything it opened is still cleaned up.
 *
 * <p>Implementations must redact secrets from every message, diagnostic and log they hand back.
 */
public final class VerificationPorts {
    private VerificationPorts() {}

    /** Owns the candidate directory and, when asked, an isolated database. Never the product database. */
    public interface Sandbox {
        /**
         * Opens an owned, empty candidate directory (and a database, if {@code needsDatabase}).
         *
         * @throws Unavailable when the environment cannot provide it; this means NOT_RUN, never a failed candidate
         */
        Lease open(boolean needsDatabase) throws Unavailable;

        /** Stable facts about the environment (database server version, schema name...), recorded in every report. */
        Map<String, String> environment();

        interface Lease {
            /** An existing empty directory this lease owns; the candidate project is written here. */
            Path candidateRoot();

            /** A directory this lease owns for logs. */
            Path evidenceRoot();

            /** Variables the application needs to reach its isolated database; empty when no database was asked for. */
            Map<String, String> applicationEnvironment();

            /** Removes everything the lease created and proves it. Safe to call once, after the application has stopped. */
            CleanupProof release();
        }

        final class Unavailable extends Exception {
            private final String code;

            public Unavailable(String code, String message) {
                super(message);
                this.code = Objects.requireNonNull(code, "code");
            }

            public String code() {
                return code;
            }
        }
    }

    /** Builds a project directory into a runnable artifact inside it. */
    public interface Builder {
        BuildOutcome build(Path projectRoot, Path evidenceRoot, ResourceLimits limits);

        Map<String, String> environment();
    }

    public record BuildOutcome(StageOutcome outcome, Optional<Path> artifact) {
        public BuildOutcome {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(artifact, "artifact");
        }
    }

    /** Starts the artifact and answers when it is ready. */
    public interface Host {
        StartOutcome start(Path artifact, Map<String, String> environment, Path evidenceRoot, ResourceLimits limits);

        Map<String, String> environment();
    }

    public record StartOutcome(StageOutcome outcome, Optional<Running> application) {
        public StartOutcome {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(application, "application");
        }
    }

    public interface Running {
        URI baseUrl();

        /**
         * Stops the application, proves the process is gone, and returns its finished logs. The application's logs
         * come from here and nowhere else, because they keep growing until it stops: a reference taken earlier
         * would describe a file that is no longer the evidence.
         */
        Stopped stop();
    }

    public record Stopped(CleanupProof cleanup, java.util.List<LogRef> logs) {
        public Stopped {
            Objects.requireNonNull(cleanup, "cleanup");
            logs = java.util.List.copyOf(Objects.requireNonNull(logs, "logs"));
        }
    }

    /**
     * Judges a running application against the original requirement. It is handed the address and nothing else
     * about the candidate: no sources, no generated files. An oracle that derives its expectations from the
     * candidate would accept any candidate, so it is kept out of reach.
     */
    public interface Oracle {
        /** Names the requirement and test pack version, recorded in the report. */
        String revision();

        StageOutcome check(URI baseUrl, Path evidenceRoot, ResourceLimits limits);
    }
}
