package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.*;
import io.kcg.sir.generator.springboot.api.*;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
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
        var acceptance = new TreeMap<String, String>();
        if (request.acceptanceRoot().isPresent()) {
            String refused = readAcceptance(request.acceptanceRoot().get(), source, output, acceptance);
            if (refused != null) return fail(diagnostics, ExecutionStage.PREFLIGHT, "SIR-APP-PACKAGE-ACCEPTANCE-001", refused);
        }
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
        for (var entry : acceptance.entrySet()) {
            String path = "acceptance/" + entry.getKey();
            files.put(path, new GeneratedFile(path, entry.getValue(), plan.id(), Optional.empty()));
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
    private static final int ACCEPTANCE_FILES = 1000;
    private static final long ACCEPTANCE_FILE_BYTES = 1L << 20, ACCEPTANCE_TOTAL_BYTES = 8L << 20;

    /**
     * Reads a business acceptance Maven project to copy verbatim. It is someone else's judge of the requirement, so it
     * is neither compiled nor interpreted here; only what keeps the package verifiable is enforced: plain UTF-8 text files
     * with safe names, no links, bounded size, and a root pom.xml. Returns the refusal reason, or null.
     */
    private static String readAcceptance(Path root, Path source, Path output, Map<String, String> files) {
        if (!root.isAbsolute() || !root.equals(root.normalize()) || root.startsWith(source) || source.startsWith(root)
                || root.startsWith(output) || output.startsWith(root))
            return "acceptance root must be an absolute normalized path that does not overlap the source or output root";
        for (Path p = root; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p)) return "acceptance root must not be or lie under a symbolic link";
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(root.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS))
            return "acceptance root must be a directory with a root pom.xml";
        long total = 0;
        try (var walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.sorted()::iterator) {
                if (file.equals(root)) continue;
                String relative = root.relativize(file).toString().replace('\\', '/');
                for (String segment : relative.split("/"))
                    if (!segment.matches("[A-Za-z0-9_][A-Za-z0-9._-]*")) return "unsupported acceptance file name: " + relative;
                if (Files.isSymbolicLink(file)) return "acceptance symbolic link refused: " + relative;
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "acceptance entry is not a regular file: " + relative;
                long size = Files.size(file);
                total += size;
                if (files.size() >= ACCEPTANCE_FILES || size > ACCEPTANCE_FILE_BYTES || total > ACCEPTANCE_TOTAL_BYTES)
                    return "acceptance project exceeds 1000 files, 1 MiB per file or 8 MiB in total";
                byte[] bytes = Files.readAllBytes(file);
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException binary) {
                    return "acceptance file is not UTF-8 text (remove build output): " + relative;
                }
                if (text.indexOf('\0') >= 0) return "acceptance file is not text (remove build output): " + relative;
                files.put(relative, text);
            }
        } catch (java.io.IOException | java.io.UncheckedIOException unreadable) {
            return "acceptance project cannot be read: " + unreadable.getClass().getSimpleName();
        }
        return null;
    }
    private static DeploymentPackageResult.Failure fail(List<ExecutionDiagnostic> diagnostics, ExecutionStage stage, String code, String message) {
        diagnostics.add(ExecutionDiagnostic.error(code, stage, message)); return new DeploymentPackageResult.Failure(diagnostics);
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
