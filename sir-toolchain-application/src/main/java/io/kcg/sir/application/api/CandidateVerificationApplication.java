package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.CompilationService;
import io.kcg.sir.application.internal.CompilationStages;
import io.kcg.sir.application.internal.PathGuard;
import io.kcg.sir.application.internal.ValidationHashes;
import io.kcg.sir.application.internal.projectbaseline.GeneratedManifest;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationResult;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Verifies one candidate project as far as the caller explicitly asked, and reports what each level proved.
 *
 * <p>The order is fixed: static generation, then building exactly the generated files, then starting the
 * artifact against an isolated database, then asking an independent oracle. A level that does not pass
 * stops the ones after it (they are {@code NOT_RUN}, not failed). Whatever happens, the application is stopped
 * and the sandbox released, in that order, and the report says whether that was proved.
 *
 * <p>Only the static level is built in. Building, starting, the database and the business judgement come in
 * through {@link VerificationPorts}, and a port that is not configured turns its level into {@code NOT_RUN}
 * with the code {@code PORT_NOT_CONFIGURED} rather than a pass.
 */
public final class CandidateVerificationApplication {
    private final VerificationPorts.Sandbox sandbox;
    private final VerificationPorts.Builder builder;
    private final VerificationPorts.Host host;
    private final VerificationPorts.Oracle oracle;
    private final ResourceLimits limits;

    /** Static generation only: no port, so no level above it can run. */
    public static CandidateVerificationApplication staticOnly() {
        return new CandidateVerificationApplication(null, null, null, null, ResourceLimits.defaults());
    }

    /** Any port may be {@code null}; the levels that need it are then reported as not run. */
    public CandidateVerificationApplication(VerificationPorts.Sandbox sandbox, VerificationPorts.Builder builder,
            VerificationPorts.Host host, VerificationPorts.Oracle oracle, ResourceLimits limits) {
        this.sandbox = sandbox;
        this.builder = builder;
        this.host = host;
        this.oracle = oracle;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public VerificationReport verify(VerificationRequest request) {
        Objects.requireNonNull(request, "request");
        Run run = new Run(request);
        run.staticLevel();
        if (run.passed(VerificationStage.STATIC) && request.through() != VerificationStage.STATIC) {
            run.runtimeLevels();
        }

        return run.report();
    }

    private final class Run {
        private final VerificationRequest request;
        private final StageResult[] results = new StageResult[VerificationStage.values().length];
        private final Map<String, String> environment = new TreeMap<>();
        private final List<CleanupProof> cleanup = new ArrayList<>();
        private VerificationBinding binding;
        private List<GeneratedFile> files = List.of();
        private VerificationPorts.Sandbox.Lease lease;
        private VerificationPorts.Running running;
        private Path artifact;

        Run(VerificationRequest request) {
            this.request = request;
        }

        boolean passed(VerificationStage stage) {
            return results[stage.ordinal()] != null && results[stage.ordinal()].status() == StageStatus.PASSED;
        }

        VerificationReport report() {
            for (VerificationStage stage : VerificationStage.values()) {
                if (results[stage.ordinal()] == null) {
                    boolean requested = stage.ordinal() <= request.through().ordinal();
                    record(stage, requested
                            ? StageOutcome.notRun("PREVIOUS_STAGE_NOT_PASSED", "an earlier level did not pass")
                            : StageOutcome.notRun("NOT_REQUESTED", "this level was not requested"), System.nanoTime());
                }
            }

            return new VerificationReport(request.id(), request.through(), Optional.ofNullable(binding), Arrays.asList(results),
                    cleanup.isEmpty() ? CleanupProof.nothingToClean() : CleanupProof.combine(cleanup), environment, limits);
        }

        private void record(VerificationStage stage, StageOutcome outcome, long startedNanos) {
            results[stage.ordinal()] = new StageResult(stage, outcome, Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
        }

        // ---------------------------------------------------------------- static

        void staticLevel() {
            long started = System.nanoTime();
            var executionDiagnostics = new ArrayList<ExecutionDiagnostic>();
            var raw = new ArrayList<ValidationDiagnostic>();
            var entered = new ValidationStopAfter[]{ValidationStopAfter.PARSE};
            CompilationStages.Snapshot snapshot;
            try {
                java.util.function.Function<SpringBootLoweredModel, GenerationResult> generation = model -> new SpringBootGenerator().generate(model);
                snapshot = CompilationService.project(request.sources(), ValidationStopAfter.GENERATION, generation, executionDiagnostics,
                        stage -> entered[0] = stage, raw);
            } catch (RuntimeException | StackOverflowError e) {
                record(VerificationStage.STATIC, StageOutcome.failed("STATIC_INTERNAL",
                        "static generation could not complete (" + entered[0] + ")"), started);
                return;
            }

            boolean ok = snapshot.success() && snapshot.stage() == ValidationStopAfter.GENERATION
                    && snapshot.diagnostics().stream().noneMatch(d -> "ERROR".equals(d.severity())) && !snapshot.files().isEmpty();
            if (!ok) {
                List<String> lines = new ArrayList<>();
                for (ValidationDiagnostic d : snapshot.diagnostics()) {
                    if (lines.size() == StageOutcome.MAX_DIAGNOSTICS) {
                        break;
                    }

                    String line = "[" + d.stage() + "] " + d.code() + ": " + d.message();
                    lines.add(line.length() > StageOutcome.MAX_DIAGNOSTIC_CHARS ? line.substring(0, StageOutcome.MAX_DIAGNOSTIC_CHARS) : line);
                }

                record(VerificationStage.STATIC, StageOutcome.failed("STATIC_" + snapshot.stage().name(),
                        "the sources did not generate a project (stopped at " + snapshot.stage() + ")", lines, List.of()), started);
                return;
            }

            files = snapshot.files();
            binding = new VerificationBinding(request.sources().sha256Hex(),
                    GeneratedManifest.digest(GeneratedManifest.of(files)),
                    ValidationHashes.generated(files), files.size(), snapshot.loweredModel().targetId(),
                    snapshot.loweredModel().irVersion().value());
            record(VerificationStage.STATIC, StageOutcome.passed("the sources generate " + files.size() + " files"), started);
        }

        // --------------------------------------------------------- runtime levels

        void runtimeLevels() {
            long started = System.nanoTime();
            if (sandbox == null) {
                record(VerificationStage.BUILD, StageOutcome.notRun("PORT_NOT_CONFIGURED", "no sandbox is configured"), started);
                return;
            }

            boolean needsDatabase = request.through().ordinal() >= VerificationStage.START.ordinal();
            VerificationPorts.Sandbox.Unavailable databaseUnavailable = null;
            try {
                lease = open(needsDatabase);
            } catch (VerificationPorts.Sandbox.Unavailable unavailable) {
                if (!needsDatabase) {
                    record(VerificationStage.BUILD, StageOutcome.notRun(unavailable.code(), unavailable.getMessage()), started);
                    return;
                }

                // The directory may still be available: building does not need the database, so keep that evidence.
                databaseUnavailable = unavailable;
                try {
                    lease = open(false);
                } catch (VerificationPorts.Sandbox.Unavailable again) {
                    record(VerificationStage.BUILD, StageOutcome.notRun(again.code(), again.getMessage()), started);
                    return;
                } catch (RuntimeException e) {
                    record(VerificationStage.BUILD, portFailure("sandbox", e), started);
                    return;
                }
            } catch (RuntimeException e) {
                record(VerificationStage.BUILD, portFailure("sandbox", e), started);
                return;
            }

            try {
                environment.putAll(prefixed("sandbox.", sandbox.environment()));
                buildLevel();
                if (passed(VerificationStage.BUILD) && request.through().ordinal() >= VerificationStage.START.ordinal()) {
                    startLevel(databaseUnavailable);
                }

                if (passed(VerificationStage.START) && request.through() == VerificationStage.BUSINESS) {
                    businessLevel();
                }
            } finally {
                releaseEverything();
            }
        }

        private VerificationPorts.Sandbox.Lease open(boolean needsDatabase) throws VerificationPorts.Sandbox.Unavailable {
            return Objects.requireNonNull(sandbox.open(needsDatabase), "sandbox returned no lease");
        }

        private void buildLevel() {
            long started = System.nanoTime();
            if (builder == null) {
                record(VerificationStage.BUILD, StageOutcome.notRun("PORT_NOT_CONFIGURED", "no builder is configured"), started);
                return;
            }

            try {
                materialize(lease.candidateRoot());
            } catch (IOException | RuntimeException e) {
                record(VerificationStage.BUILD, StageOutcome.failed("MATERIALIZATION_FAILED",
                        "the generated files could not be written exactly into the candidate directory (" + e.getClass().getSimpleName() + ")"), started);
                return;
            }

            environment.putAll(prefixed("builder.", builder.environment()));
            VerificationPorts.BuildOutcome built;
            try {
                built = Objects.requireNonNull(builder.build(lease.candidateRoot(), lease.evidenceRoot(), limits), "builder returned no outcome");
            } catch (RuntimeException e) {
                record(VerificationStage.BUILD, portFailure("builder", e), started);
                return;
            }

            StageOutcome outcome = built.outcome();
            if (outcome.status() == StageStatus.PASSED) {
                Path path = built.artifact().orElse(null);
                Path root = lease.candidateRoot().toAbsolutePath().normalize();
                boolean usable = path != null && path.toAbsolutePath().normalize().startsWith(root)
                        && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
                if (!usable) {
                    record(VerificationStage.BUILD, StageOutcome.failed("BUILD_ARTIFACT_MISSING",
                            "the build reported success but produced no runnable artifact inside the candidate directory", List.of(), outcome.logs()), started);
                    return;
                }

                artifact = path;
            }

            record(VerificationStage.BUILD, outcome, started);
        }

        private void startLevel(VerificationPorts.Sandbox.Unavailable databaseUnavailable) {
            long started = System.nanoTime();
            if (databaseUnavailable != null) {
                record(VerificationStage.START, StageOutcome.notRun(databaseUnavailable.code(), databaseUnavailable.getMessage()), started);
                return;
            }

            if (host == null) {
                record(VerificationStage.START, StageOutcome.notRun("PORT_NOT_CONFIGURED", "no application host is configured"), started);
                return;
            }

            environment.putAll(prefixed("host.", host.environment()));
            VerificationPorts.StartOutcome start;
            try {
                start = Objects.requireNonNull(host.start(artifact, lease.applicationEnvironment(), lease.evidenceRoot(), limits), "host returned no outcome");
            } catch (RuntimeException e) {
                record(VerificationStage.START, portFailure("host", e), started);
                return;
            }

            // Keep the handle before judging the outcome, so a half-started application is always stopped.
            running = start.application().orElse(null);
            StageOutcome outcome = start.outcome();
            if (outcome.status() == StageStatus.PASSED && running == null) {
                outcome = StageOutcome.failed("START_NO_APPLICATION", "the host reported success but returned no running application", List.of(), outcome.logs());
            }

            record(VerificationStage.START, outcome, started);
        }

        private void businessLevel() {
            long started = System.nanoTime();
            if (oracle == null) {
                record(VerificationStage.BUSINESS, StageOutcome.notRun("PORT_NOT_CONFIGURED", "no business oracle is configured"), started);
                return;
            }

            try {
                environment.put("oracle.revision", oracle.revision());
                record(VerificationStage.BUSINESS, Objects.requireNonNull(oracle.check(running.baseUrl(), lease.evidenceRoot(), limits), "oracle returned no outcome"), started);
            } catch (RuntimeException e) {
                record(VerificationStage.BUSINESS, portFailure("oracle", e), started);
            }
        }

        private void releaseEverything() {
            if (running != null) {
                try {
                    VerificationPorts.Stopped stopped = running.stop();
                    cleanup.add(stopped.cleanup());
                    attachApplicationLogs(stopped.logs());
                } catch (RuntimeException e) {
                    cleanup.add(CleanupProof.notProved("application: stopping it threw " + e.getClass().getSimpleName()));
                }
            }

            if (lease != null) {
                try {
                    cleanup.add(lease.release());
                } catch (RuntimeException e) {
                    cleanup.add(CleanupProof.notProved("sandbox: releasing it threw " + e.getClass().getSimpleName()));
                }
            }
        }

        /** The finished application logs belong to the start level, whether it passed or failed. */
        private void attachApplicationLogs(List<LogRef> logs) {
            StageResult start = results[VerificationStage.START.ordinal()];
            if (start == null || logs.isEmpty()) {
                return;
            }

            StageOutcome o = start.outcome();
            List<LogRef> all = new ArrayList<>(o.logs());
            all.addAll(logs);
            results[VerificationStage.START.ordinal()] = new StageResult(VerificationStage.START,
                    new StageOutcome(o.status(), o.code(), o.message(), o.diagnostics(), all), start.elapsedMillis());
        }

        /** Writes exactly the generated files into the empty owned directory and proves nothing else is there. */
        private void materialize(Path root) throws IOException {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("candidate root is not a directory");
            }

            try (Stream<Path> existing = Files.list(root)) {
                if (existing.findAny().isPresent()) {
                    throw new IOException("candidate root is not empty");
                }
            }

            Path normalizedRoot = root.toAbsolutePath().normalize();
            Set<String> expected = new HashSet<>();
            for (GeneratedFile file : files) {
                if (PathGuard.validateRelativePath(file.relativePath()) != null) {
                    throw new IOException("unsafe generated path");
                }

                Path target = normalizedRoot.resolve(file.relativePath()).normalize();
                if (!target.startsWith(normalizedRoot)) {
                    throw new IOException("generated path leaves the candidate root");
                }

                Files.createDirectories(target.getParent());
                Files.write(target, file.content().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                expected.add(file.relativePath());
            }

            Set<String> found = new HashSet<>();
            try (Stream<Path> walk = Files.walk(normalizedRoot)) {
                for (Path path : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        found.add(normalizedRoot.relativize(path).toString().replace('\\', '/'));
                    }
                }
            }

            if (!found.equals(expected)) {
                throw new IOException("the candidate directory does not hold exactly the generated files");
            }

            for (GeneratedFile file : files) {
                if (!Arrays.equals(Files.readAllBytes(normalizedRoot.resolve(file.relativePath())), file.content().getBytes(StandardCharsets.UTF_8))) {
                    throw new IOException("a written file differs from the generated bytes");
                }
            }
        }
    }

    private static StageOutcome portFailure(String port, RuntimeException e) {
        // The exception text is not copied: it may carry a secret the port did not redact.
        return StageOutcome.failed("PORT_EXCEPTION", port + " port threw " + e.getClass().getName());
    }

    private static Map<String, String> prefixed(String prefix, Map<String, String> values) {
        Map<String, String> result = new TreeMap<>();
        values.forEach((key, value) -> result.put(prefix + key, value));
        return result;
    }
}
