import io.kcg.sir.application.api.ExecutionDiagnostic;
import io.kcg.sir.application.internal.ProjectCompilation;
import io.kcg.sir.application.internal.ValidationHashes;
import io.kcg.sir.generator.springboot.api.SpringBootGenerator;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;

/** Verification only: actual CI compiler, one file set, both existing digest protocols. */
class VerifyBlogGeneration {
    public static void main(String[] args) throws Exception {
        var bytes = Files.readAllBytes(Path.of(args[0]));
        var entry = SourceId.of("blog.sir");
        var diagnostics = new ArrayList<ExecutionDiagnostic>();
        var compiled = ProjectCompilation.compile(new SourceSnapshot(entry, Map.of(entry, bytes)),
                new SpringBootGenerator()::generate, diagnostics).orElseThrow();
        if (diagnostics.stream().anyMatch(ExecutionDiagnostic::isError)) throw new IllegalStateException(diagnostics.toString());
        var files = compiled.generatedFiles();
        var legacy = MessageDigest.getInstance("SHA-256");
        for (var file : files) {
            legacy.update(file.relativePath().getBytes(StandardCharsets.UTF_8));
            legacy.update((byte) 0);
            legacy.update(file.content().getBytes(StandardCharsets.UTF_8));
            legacy.update((byte) 0);
        }
        var missing = new ArrayList<String>();
        for (String name : new String[] {"Publish", "AttachTag", "PostsByTag", "LikePost"}) {
            if (files.stream().noneMatch(f -> f.relativePath().endsWith("/" + name + "Controller.java"))) missing.add(name);
        }
        System.out.printf("{\"source_sha256\":\"%s\",\"files\":%d,\"legacy_combined_sha256\":\"%s\",\"validation_generated_v1_sha256\":\"%s\",\"missing_controllers\":[%s]}%n",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), files.size(),
                HexFormat.of().formatHex(legacy.digest()), ValidationHashes.generated(files),
                missing.stream().map(s -> "\"" + s + "\"").collect(java.util.stream.Collectors.joining(",")));
    }
}
