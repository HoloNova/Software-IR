package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.*;
import io.kcg.sir.generator.springboot.api.*;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Exports a new standalone directory; all acquisition/build/start actions remain outside this operation. */
public final class DeploymentPackageApplication {
    public DeploymentPackageResult export(DeploymentPackageRequest request) {
        Objects.requireNonNull(request);
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        Path source = request.sourceRoot(), output = request.outputRoot();
        if (!source.isAbsolute() || !output.isAbsolute() || !source.equals(source.normalize()) || !output.equals(output.normalize())
                || source.startsWith(output) || output.startsWith(source)) return fail(diagnostics, ExecutionStage.PREFLIGHT,
                    "SIR-APP-PACKAGE-REQUEST-001", "source/output roots must be absolute normalized nonoverlapping paths");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) return fail(diagnostics, ExecutionStage.PREFLIGHT,
            "SIR-APP-PACKAGE-OUTPUT-001", "package export requires a new output root");
        var loaded = ProjectSourceReader.read(source, request.entry(), diagnostics);
        if (loaded.isEmpty()) return new DeploymentPackageResult.Failure(diagnostics);
        var compiled = ProjectCompilation.compile(loaded.get(), model -> new SpringBootGenerator().generate(model), diagnostics);
        if (compiled.isEmpty()) return new DeploymentPackageResult.Failure(diagnostics);
        var snapshot = compiled.get();
        io.kcg.sir.lowering.springboot.model.DeploymentPlan plan;
        try { plan = new SpringBootDeploymentPlanner().plan(snapshot.semanticModel(), snapshot.loweredModel()); }
        catch (IllegalArgumentException unsupported) {
            return fail(diagnostics, ExecutionStage.LOWERING, "SIR-DEPLOY-UNSUPPORTED-001", unsupported.getMessage());
        }
        var files = new TreeMap<String, GeneratedFile>();
        for (var file : snapshot.generatedFiles()) files.put("app/" + file.relativePath(),
            new GeneratedFile("app/" + file.relativePath(), file.content(), file.artifactId(), file.symbolId()));
        for (var file : new SpringBootDeploymentGenerator().generate(plan)) {
            if (!file.relativePath().equals("app/pom.xml") && files.containsKey(file.relativePath()))
                return fail(diagnostics, ExecutionStage.GENERATION, "SIR-APP-PACKAGE-PATH-001", "delivery artifact collides: " + file.relativePath());
            files.put(file.relativePath(), file);
        }
        for (var entry : loaded.get().snapshot().manifest().files()) {
            String path = "sir/" + entry.sourceId().value();
            files.put(path, new GeneratedFile(path, new String(loaded.get().snapshot().bytes(entry.sourceId()), StandardCharsets.UTF_8), plan.id(), Optional.empty()));
        }
        StringBuilder manifest = new StringBuilder();
        for (var file : files.values()) manifest.append(hash(file.content().getBytes(StandardCharsets.UTF_8)))
            .append("  ").append(file.relativePath()).append('\n');
        String packageId = hash(manifest.toString().getBytes(StandardCharsets.UTF_8));
        files.put("package.sha256", new GeneratedFile("package.sha256", manifest.toString(), plan.id(), Optional.empty()));
        files.put("package.id", new GeneratedFile("package.id", packageId + "\n", plan.id(), Optional.empty()));
        var outputFiles = List.copyOf(files.values());
        diagnostics.addAll(PathGuard.preflight(output, outputFiles));
        if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) return new DeploymentPackageResult.Failure(diagnostics);
        var transaction = new FileTransaction(output, outputFiles).execute();
        if (transaction instanceof TransactionResult.Failure failure) {
            diagnostics.addAll(failure.diagnostics()); return new DeploymentPackageResult.Failure(diagnostics);
        }
        var saved = (TransactionResult.Success) transaction;
        diagnostics.addAll(saved.warnings());
        return new DeploymentPackageResult.Exported(packageId, loaded.get().snapshot().manifest(),
            new ExecutionManifest(output, saved.appliedFiles()), diagnostics);
    }
    private static DeploymentPackageResult.Failure fail(List<ExecutionDiagnostic> diagnostics, ExecutionStage stage, String code, String message) {
        diagnostics.add(ExecutionDiagnostic.error(code, stage, message)); return new DeploymentPackageResult.Failure(diagnostics);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
