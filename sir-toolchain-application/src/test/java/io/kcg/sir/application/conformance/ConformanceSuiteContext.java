package io.kcg.sir.application.conformance;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable context for one conformance suite orchestration. Holds all
 * the dependencies that {@link ConformanceSuite#orchestrate} needs to
 * execute the five scenarios end-to-end.
 *
 * <p>Per ADR-017 搂3 / 搂6, the run-specific {@code workRoot} and
 * {@code evidenceRoot} are <em>not</em> pre-created by the caller. The
 * caller supplies the parent directories and a run token; the orchestrate
 * method itself creates the run-specific directories at the correct
 * lifecycle phases:
 *
 * <ul>
 *   <li>{@code EVIDENCE_ROOT_CREATE}: create {@link EvidenceDirectory}</li>
 *   <li>{@code WORK_ROOT_CREATE}: create {@link OwnedRunDirectory}
 *       (only after schema + marker + fixture have succeeded)</li>
 * </ul>
 *
 * <p>The caller-supplied parent directories must already exist as real
 * directories with no symlink in their parent chains. The orchestrate
 * method proves absence of the run-specific paths during
 * {@code STATIC_PRECONDITION}.
 *
 * @param environment         the recorded reference-environment tuple
 * @param workParent          the caller-supplied workRoot parent
 * @param evidenceParent      the caller-supplied evidenceRoot parent
 * @param runToken            the run-specific child name (used for both
 *                            workRoot and evidenceRoot)
 * @param control             the dedicated MySQL control session
 * @param runtimeFixture      the runtime MySQL credential (no CREATE/DROP)
 * @param redactor            the initialized streaming secret redactor
 * @param secretScanner       the final evidence secret scanner
 * @param mavenRunner         the Maven project runner
 * @param springProcess       the Spring application process controller
 * @param childEnvironment    the child-process environment builder
 * @param serverPort          the loopback server port
 * @param mavenGraceMillis    the Maven build grace period
 * @param springGraceMillis   the Spring process stop grace period
 * @param readinessTimeoutMs  the readiness probe total timeout
 * @param readinessPollMs     the readiness probe poll interval
 * @param secrets             the exact secret values for redaction/scanning
 */
public record ConformanceSuiteContext(
        ConformanceEnvironment environment,
        Path workParent,
        Path evidenceParent,
        String runToken,
        MysqlControlSession control,
        MysqlRuntimeFixture runtimeFixture,
        StreamingSecretRedactor redactor,
        EvidenceSecretScanner secretScanner,
        MavenProjectRunner mavenRunner,
        SpringApplicationProcess springProcess,
        ChildEnvironmentBuilder childEnvironment,
        int serverPort,
        long mavenGraceMillis,
        long springGraceMillis,
        long readinessTimeoutMs,
        long readinessPollMs,
        List<String> secrets) {
    public ConformanceSuiteContext {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(workParent, "workParent");
        Objects.requireNonNull(evidenceParent, "evidenceParent");
        Objects.requireNonNull(runToken, "runToken");
        if (runToken.isBlank()) {
            throw new IllegalArgumentException("runToken must not be blank");
        }
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(runtimeFixture, "runtimeFixture");
        Objects.requireNonNull(redactor, "redactor");
        Objects.requireNonNull(secretScanner, "secretScanner");
        Objects.requireNonNull(mavenRunner, "mavenRunner");
        Objects.requireNonNull(springProcess, "springProcess");
        Objects.requireNonNull(childEnvironment, "childEnvironment");
        Objects.requireNonNull(secrets, "secrets");
        secrets = List.copyOf(secrets);
    }
}