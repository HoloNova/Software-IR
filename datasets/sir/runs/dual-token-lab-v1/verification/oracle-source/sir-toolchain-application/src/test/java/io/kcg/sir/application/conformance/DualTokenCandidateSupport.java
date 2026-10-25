package io.kcg.sir.application.conformance;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kcg.sir.application.api.SirValidationApplication;
import io.kcg.sir.application.api.SirValidationRequest;
import io.kcg.sir.application.api.SirValidationResult;
import io.kcg.sir.application.internal.ProjectSourceReader;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/** Test-only transport for one frozen dual-token task; uses the existing source reader and oracle. */
final class DualTokenCandidateSupport {
    private static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final List<String> BINDINGS = List.of("task_sha256", "oracle_sha256", "toolchain_core_sha256");

    record Input(SourceSnapshot sources, Map<String, Object> candidate, Map<String, Object> manifest) {
        Input { candidate = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(candidate)); manifest = Map.copyOf(manifest); }
        String id() { return text(candidate, "id"); }
        SirValidationResult staticCheck() {
            return new SirValidationApplication().check(new SirValidationRequest(id(),
                    new String(sources.bytes(sources.entry()), StandardCharsets.UTF_8)));
        }
    }

    private DualTokenCandidateSupport() {}

    static Optional<Input> read(Function<String, String> property) throws IOException {
        String candidateRoot = property.apply("kcg.slm.candidate-root");
        String bundleRoot = property.apply("kcg.slm.bundle-root");
        if (candidateRoot == null && bundleRoot == null) return Optional.empty();
        Path source = absolute(candidateRoot);
        Path bundle = absolute(bundleRoot);
        Path repo = absolute(property.apply("kcg.slm.repo-root"));
        var diagnostics = new ArrayList<io.kcg.sir.application.api.ExecutionDiagnostic>();
        var loaded = ProjectSourceReader.read(source, SourceId.of("candidate.sir"), diagnostics);
        if (loaded.isEmpty()) throw new IOException("candidate secure source read failed: " + diagnostics);
        Map<String, Object> manifest = readJson(bundle.resolve("manifest.json"));
        verifyInputs(repo, map(manifest, "toolchain_inputs"), text(manifest, "toolchain_core_sha256"));
        verifyInputs(repo, map(manifest, "oracle_inputs"), text(manifest, "oracle_sha256"));
        verifyInputs(bundle, map(manifest, "bundle_inputs"), null);
        return Optional.of(bind(loaded.orElseThrow().snapshot(), readJson(source.resolve("candidate.json")), manifest));
    }

    static Input bind(SourceSnapshot sources, Map<String, Object> candidate, Map<String, Object> manifest) {
        if (sources.manifest().files().size() != 1) throw new IllegalArgumentException("pilot accepts one SIR file only");
        String actual = ConformanceFixtures.sha256Hex(sources.bytes(sources.entry()));
        if (!actual.equals(candidate.get("source_sha256"))) throw new IllegalArgumentException("candidate source fingerprint mismatch");
        if (!text(candidate, "id").matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,159}"))
            throw new IllegalArgumentException("invalid sample id");
        if (!"sir-candidate/1".equals(candidate.get("schema_version")))
            throw new IllegalArgumentException("unsupported candidate schema");
        text(candidate, "task_revision");
        for (String key : BINDINGS) {
            if (!text(candidate, key).matches("[0-9a-f]{64}") || !candidate.get(key).equals(manifest.get(key)))
                throw new IllegalArgumentException("candidate manifest binding mismatch: " + key);
        }
        return new Input(sources, candidate, manifest);
    }

    static void verifyGenerated(Input input, BusinessSliceHarness slice,
                                List<io.kcg.sir.generator.springboot.api.GeneratedFile> files) throws IOException {
        var checked = input.staticCheck();
        String digest = io.kcg.sir.application.internal.ValidationHashes.generated(files);
        boolean same = checked.ok() && checked.stage() == io.kcg.sir.application.api.ValidationStopAfter.GENERATION
                && digest.equals(checked.digest());
        slice.check("candidate runtime compilation matches full static generation", same,
                "sourceSha256=" + checked.sourceSha256() + " generatedDigest=" + digest);
        if (!same) throw new IOException("candidate generation binding failed");
        slice.reportLine("slmSampleId=" + input.id());
        slice.reportLine("slmSourceSha256=" + checked.sourceSha256());
        slice.reportLine("slmGeneratedDigest=" + digest);
    }

    static void writeEvidence(Input input, BusinessSliceHarness slice) throws IOException {
        Map<String, String> header = header(slice.report());
        String build = outcome(header.get("generatedProjectBuild"));
        String start = header.getOrDefault("httpReadiness", "").startsWith("READY") ? "PASSED" : "NOT_RUN";
        if ("PASSED".equals(build) && "FAILED".equals(header.get("verdict")) && !"PASSED".equals(start)) start = "FAILED";
        String business = "PASSED".equals(start) && slice.notRunReason() == null
                ? (slice.passed() ? "PASSED" : "FAILED") : "NOT_RUN";
        var result = new LinkedHashMap<String, Object>();
        result.put("schema_version", "sir-runtime-evidence/1");
        result.put("id", input.id());
        for (String key : BINDINGS) result.put(key, text(input.candidate(), key));
        result.put("source_sha256", text(input.candidate(), "source_sha256"));
        result.put("generated_digest", header.get("slmGeneratedDigest"));
        result.put("run_id", header.get("runId"));
        result.put("gates", Map.of("static", header.containsKey("slmGeneratedDigest") ? "PASSED" : "FAILED",
                "build", build, "start", start, "business", business));
        result.put("environment", Map.of("java", System.getProperty("java.version"),
                "mysql", header.getOrDefault("slmMysqlVersion", "NOT_RUN"),
                "test_revision", text(input.candidate(), "task_revision")));
        result.put("artifacts", artifacts(slice.evidenceRoot()));
        Files.writeString(slice.evidenceRoot().resolve("slm-runtime.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> artifacts(Path root) throws IOException {
        var result = new ArrayList<Map<String, String>>();
        for (String path : List.of("dual-token-slice-report.txt", "logs/maven.stdout.log", "logs/maven.stderr.log",
                "logs/spring.stdout.log", "logs/spring.stderr.log")) {
            Path file = root.resolve(path);
            if (Files.isRegularFile(file)) result.add(Map.of("path", path, "sha256", ConformanceFixtures.fileDigest(file)));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> header(String report) {
        var result = new TreeMap<String, String>();
        for (String line : report.split("\n")) {
            if (line.isEmpty()) break;
            int equal = line.indexOf('=');
            if (equal > 0) result.put(line.substring(0, equal), line.substring(equal + 1));
        }
        return Map.copyOf(result);
    }

    private static String outcome(String value) {
        if (value == null || value.equals("NOT_RUN")) return "NOT_RUN";
        return value.startsWith("PASSED") ? "PASSED" : "FAILED";
    }

    private static Path absolute(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("candidate, bundle and repo roots are all required");
        Path path = Path.of(value);
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IllegalArgumentException("root must be absolute and normalized");
        return path;
    }

    private static Map<String, Object> readJson(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(MAX_JSON_BYTES + 1);
            if (bytes.length > MAX_JSON_BYTES) throw new IOException("experiment metadata exceeds 16 MiB");
            return JSON.readValue(bytes, new TypeReference<Map<String, Object>>() {});
        }
    }

    private static void verifyInputs(Path root, Map<String, Object> inputs, String expected) throws IOException {
        var sorted = new TreeMap<String, String>();
        for (var entry : inputs.entrySet()) {
            Path relative = Path.of(entry.getKey());
            if (relative.isAbsolute() || !relative.equals(relative.normalize()) || entry.getKey().contains("\\"))
                throw new IOException("invalid manifest path");
            Path path = root.resolve(relative).normalize();
            if (!path.startsWith(root) || !ConformanceFixtures.fileDigest(path).equals(entry.getValue()))
                throw new IOException("experiment input changed: " + entry.getKey());
            sorted.put(entry.getKey(), entry.getValue().toString());
        }
        if (expected != null) {
            StringBuilder value = new StringBuilder();
            sorted.forEach((path, hash) -> value.append(path).append('\0').append(hash).append('\n'));
            if (!ConformanceFixtures.sha256Hex(value.toString().getBytes(StandardCharsets.UTF_8)).equals(expected))
                throw new IOException("input manifest fingerprint mismatch");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof Map<?, ?>)) throw new IllegalArgumentException("required map: " + key);
        return (Map<String, Object>) value.get(key);
    }

    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String text) || text.isBlank()) throw new IllegalArgumentException("required text: " + key);
        return text;
    }
}
