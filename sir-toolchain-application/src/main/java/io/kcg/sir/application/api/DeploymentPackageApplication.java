package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.*;
import io.kcg.sir.change.edit.SemanticDelta;
import io.kcg.sir.generator.springboot.api.*;
import io.kcg.sir.lowering.springboot.api.SpringBootDeploymentPlanner;
import io.kcg.sir.lowering.springboot.api.SpringBootSchemaEvolutionPlanner;
import io.kcg.sir.lowering.springboot.model.DeploymentPlan;
import io.kcg.sir.lowering.springboot.model.SchemaContract;
import io.kcg.sir.lowering.springboot.model.SchemaEvolution;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.schema.SoftwareDraft;
import io.kcg.sir.semantic.schema.SoftwareSchema;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSetManifest;
import io.kcg.sir.source.SourceSnapshot;
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
        BaseDelivery base = null;
        if (request.basePackage().isPresent()) {
            Path root = request.basePackage().get();
            if (!root.isAbsolute() || !root.equals(root.normalize()) || overlaps(root, source) || overlaps(root, output)
                    || request.acceptanceRoot().map(x -> overlaps(root, x)).orElse(false))
                return fail(diagnostics, ExecutionStage.PREFLIGHT, "SIR-APP-PACKAGE-BASE-001",
                    "base package must be an absolute normalized path that does not overlap the source, output or acceptance root");
            try {
                base = BaseDelivery.read(root);
            } catch (BaseDelivery.Refused refused) {
                return fail(diagnostics, ExecutionStage.PREFLIGHT, refused.code, refused.getMessage());
            }
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
        Optional<DeploymentPackageResult.Continuation> continuation = Optional.empty();
        if (base != null) {
            // The running delivery's model is rebuilt from its own sources. Only if that rebuild renders exactly the
            // schema the running delivery was published with is it the model the database is actually at.
            var baseDiagnostics = new ArrayList<ExecutionDiagnostic>();
            var rebuilt = ProjectCompilation.compile(base.sources(), model -> new SpringBootGenerator().generate(model), baseDiagnostics);
            if (rebuilt.isEmpty()) return fail(diagnostics, ExecutionStage.PREFLIGHT, "SIR-APP-PACKAGE-BASE-004",
                "base package's SIR does not compile with this toolchain: " + baseDiagnostics.stream().filter(ExecutionDiagnostic::isError)
                    .findFirst().map(d -> d.code() + " " + d.message()).orElse("no diagnostic"));
            NormalizedSemanticModel baseModel = rebuilt.get().semanticModel();
            DeploymentPlan basePlan;
            try { basePlan = new SpringBootDeploymentPlanner().plan(baseModel, rebuilt.get().loweredModel()); }
            catch (IllegalArgumentException unsupported) {
                return fail(diagnostics, ExecutionStage.PREFLIGHT, "SIR-APP-PACKAGE-BASE-004", "base package's SIR is no longer deliverable: " + unsupported.getMessage());
            }
            if (!SchemaContract.render(basePlan.tables()).equals(base.schema())) return fail(diagnostics, ExecutionStage.PREFLIGHT,
                "SIR-APP-PACKAGE-BASE-004", "base package's SIR does not reproduce its schema contract with this toolchain");
            SemanticDelta delta = SemanticDelta.between(SoftwareDraft.of(baseModel), SoftwareDraft.of(snapshot.semanticModel()),
                SoftwareSchema.normalizedModel());
            var outcome = new SpringBootSchemaEvolutionPlanner().plan(basePlan, base.mysqlImage(), base.version(), delta, plan,
                request.confirmBackup());
            if (outcome.evolution().isEmpty()) {
                for (String refusal : outcome.refusals()) {
                    boolean dropsData = refusal.startsWith("TABLE_REMOVED ") || refusal.startsWith("COLUMN_REMOVED ");
                    diagnostics.add(ExecutionDiagnostic.error(refusal.startsWith("SELFCHECK ") ? "SIR-DEPLOY-UPDATE-SELFCHECK-001"
                        : dropsData ? "SIR-DEPLOY-UPDATE-BACKUP-001" : "SIR-DEPLOY-UPDATE-UNSUPPORTED-001", ExecutionStage.LOWERING,
                        dropsData ? refusal + ": this update drops it with the data in it. Back up the running instance, then export again with"
                            + " --confirm-backup yes (the update itself takes no backup)" : refusal));
                }
                return new DeploymentPackageResult.Failure(diagnostics);
            }
            SchemaEvolution evolution = outcome.evolution().get();
            // The inherited chain is published as it was; this source's own rendering of V1 is not used.
            files.keySet().removeIf(path -> path.startsWith("deploy/migrations/") || path.startsWith("deploy/contracts/"));
            base.chain().forEach((path, content) -> files.put(path, new GeneratedFile(path, content, plan.id(), Optional.empty())));
            if (!evolution.steps().isEmpty()) {
                String path = "deploy/contracts/V" + base.version() + ".tsv";
                files.put(path, new GeneratedFile(path, base.schema(), plan.id(), Optional.empty()));
                for (var file : new SpringBootDeploymentGenerator().generateEvolution(plan, evolution, base.schema(),
                        base.chain().getOrDefault(RENAMES, "")))
                    files.put(file.relativePath(), file);
            }
            continuation = Optional.of(new DeploymentPackageResult.Continuation(base.id(), evolution.targetVersion(),
                delta.changes().stream().map(SemanticDelta.Change::toString).toList(),
                evolution.steps().stream().map(SchemaEvolution.Step::describe).toList(),
                dataLoss(evolution)));
        }
        for (var entry : acceptance.entrySet()) {
            String path = "acceptance/" + entry.getKey();
            files.put(path, new GeneratedFile(path, entry.getValue(), plan.id(), Optional.empty()));
        }
        // Which source is the entry, so a later update can rebuild this delivery's model from sir/.
        files.put(SIR_ENTRY, new GeneratedFile(SIR_ENTRY, loaded.get().snapshot().entry().value() + "\n", plan.id(), Optional.empty()));
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
            new ExecutionManifest(output, saved.appliedFiles()), diagnostics, continuation);
    }
    /** The tables and columns the versions drop, with their data. */
    private static List<String> dataLoss(SchemaEvolution evolution) {
        List<String> lost = new ArrayList<>();
        for (var step : evolution.steps()) {
            if (step instanceof SchemaEvolution.DropColumn drop) lost.add(drop.table() + "." + drop.column().name());
            else if (step instanceof SchemaEvolution.DropTable drop) lost.add(drop.table().name());
        }
        return lost;
    }
    private static final String SIR_ENTRY = "sir-entry", RENAMES = "deploy/renames.tsv";

    private static boolean overlaps(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }

    /**
     * The frozen schema side of the delivery an instance runs now, read under the same integrity rules its Bootstrap
     * applies: package.id is the digest of package.sha256, every listed file matches, nothing is unlisted, no links.
     */
    record BaseDelivery(String id, int version, Map<String, String> chain, String schema, String mysqlImage, SourceSnapshot sources) {
        static final class Refused extends Exception {
            final String code;
            Refused(String code, String message) { super(message); this.code = code; }
        }

        static BaseDelivery read(Path root) throws Refused {
            try {
                for (Path p = root; p != null; p = p.getParent())
                    if (Files.isSymbolicLink(p)) throw new Refused("SIR-APP-PACKAGE-BASE-001", "base package must not be or lie under a symbolic link");
                if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new Refused("SIR-APP-PACKAGE-BASE-001", "base package is not a directory");
                byte[] manifest = Files.readAllBytes(root.resolve("package.sha256"));
                String id = Files.readString(root.resolve("package.id")).strip();
                if (!id.matches("[0-9a-f]{64}") || !hash(manifest).equals(id)) throw new Refused("SIR-APP-PACKAGE-BASE-002", "base package manifest does not match its package.id");
                Map<String, String> listed = new TreeMap<>();
                for (String line : text(manifest, "package.sha256").lines().toList()) {
                    if (!line.matches("[0-9a-f]{64}  [A-Za-z0-9_][A-Za-z0-9._/-]*")) throw new Refused("SIR-APP-PACKAGE-BASE-002", "invalid base package manifest line");
                    String path = line.substring(66);
                    Path file = root.resolve(path).normalize();
                    if (!file.startsWith(root) || listed.put(path, line.substring(0, 64)) != null) throw new Refused("SIR-APP-PACKAGE-BASE-002", "invalid base package path: " + path);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !hash(Files.readAllBytes(file)).equals(line.substring(0, 64)))
                        throw new Refused("SIR-APP-PACKAGE-BASE-002", "base package file does not match its manifest: " + path);
                }
                try (var walk = Files.walk(root)) {
                    for (Path file : (Iterable<Path>) walk::iterator) {
                        if (Files.isSymbolicLink(file)) throw new Refused("SIR-APP-PACKAGE-BASE-002", "base package contains a symbolic link");
                        String path = root.relativize(file).toString().replace('\\', '/');
                        if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) && !path.equals("package.sha256") && !path.equals("package.id") && !listed.containsKey(path))
                            throw new Refused("SIR-APP-PACKAGE-BASE-002", "base package contains an unlisted file: " + path);
                    }
                }
                Map<String, String> chain = new TreeMap<>();
                TreeMap<Integer, String> versions = new TreeMap<>();
                for (String path : listed.keySet()) {
                    if (path.startsWith("deploy/migrations/")) {
                        String name = path.substring("deploy/migrations/".length());
                        if (!name.matches("V[1-9][0-9]{0,5}__[a-z0-9_]+\\.sql") || versions.put(Integer.parseInt(name.substring(1, name.indexOf("__"))), name) != null)
                            throw new Refused("SIR-APP-PACKAGE-BASE-003", "unexpected base migration: " + name);
                        chain.put(path, text(Files.readAllBytes(root.resolve(path)), path));
                    }
                }
                int version = versions.size();
                if (version == 0 || versions.firstKey() != 1 || versions.lastKey() != version)
                    throw new Refused("SIR-APP-PACKAGE-BASE-003", "base migrations are not V1..Vn without gaps");
                for (String path : listed.keySet()) {
                    if (!path.startsWith("deploy/contracts/")) continue;
                    String name = path.substring("deploy/contracts/".length());
                    if (!name.matches("V[1-9][0-9]{0,5}\\.tsv") || Integer.parseInt(name.substring(1, name.length() - 4)) >= version)
                        throw new Refused("SIR-APP-PACKAGE-BASE-003", "unexpected base contract: " + name);
                    chain.put(path, text(Files.readAllBytes(root.resolve(path)), path));
                }
                // Which columns earlier versions renamed: inherited with the chain, read by the migration tool.
                if (listed.containsKey(RENAMES)) chain.put(RENAMES, text(Files.readAllBytes(root.resolve(RENAMES)), RENAMES));
                for (int i = 1; i < version; i++) if (!chain.containsKey("deploy/contracts/V" + i + ".tsv"))
                    throw new Refused("SIR-APP-PACKAGE-BASE-003", "base contract V" + i + " is missing");
                if (!listed.containsKey("deploy/schema.tsv") || !listed.containsKey("deploy/compose.yaml"))
                    throw new Refused("SIR-APP-PACKAGE-BASE-003", "base package has no schema contract or compose file");
                String schema = text(Files.readAllBytes(root.resolve("deploy/schema.tsv")), "deploy/schema.tsv");
                // The database service is the first service of the delivery compose file.
                String image = text(Files.readAllBytes(root.resolve("deploy/compose.yaml")), "deploy/compose.yaml").lines()
                    .map(String::strip).filter(l -> l.startsWith("image: ")).findFirst()
                    .map(l -> l.substring("image: ".length())).orElseThrow(() -> new Refused("SIR-APP-PACKAGE-BASE-003", "base compose file names no database image"));
                if (!listed.containsKey(SIR_ENTRY)) throw new Refused("SIR-APP-PACKAGE-BASE-003",
                    "base package records no SIR entry; it was exported by an earlier toolchain and cannot be continued");
                String entry = text(Files.readAllBytes(root.resolve(SIR_ENTRY)), SIR_ENTRY).strip();
                Map<SourceId, byte[]> sources = new LinkedHashMap<>();
                SourceSnapshot snapshot;
                try {
                    SourceId entryId = SourceSetManifest.strictId(entry);
                    for (String path : listed.keySet())
                        if (path.startsWith("sir/")) sources.put(SourceSetManifest.strictId(path.substring(4)), Files.readAllBytes(root.resolve(path)));
                    if (!sources.containsKey(entryId)) throw new Refused("SIR-APP-PACKAGE-BASE-003", "base package SIR entry is missing: " + entry);
                    snapshot = new SourceSnapshot(entryId, sources);
                } catch (IllegalArgumentException invalid) {
                    throw new Refused("SIR-APP-PACKAGE-BASE-003", "base package SIR sources are invalid: " + invalid.getMessage());
                }
                return new BaseDelivery(id, version, chain, schema, image, snapshot);
            } catch (java.io.IOException | java.io.UncheckedIOException unreadable) {
                throw new Refused("SIR-APP-PACKAGE-BASE-001", "base package cannot be read: " + unreadable.getClass().getSimpleName());
            }
        }

        private static String text(byte[] bytes, String path) throws Refused {
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException binary) {
                throw new Refused("SIR-APP-PACKAGE-BASE-003", "base package file is not UTF-8 text: " + path);
            }
        }
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
