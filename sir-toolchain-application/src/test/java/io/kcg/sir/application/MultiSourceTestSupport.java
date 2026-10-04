package io.kcg.sir.application;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.api.*;
import io.kcg.sir.ast.*;
import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.SourceReader;
import io.kcg.sir.semantic.api.*;
import io.kcg.sir.source.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class MultiSourceTestSupport {
    public static final SourceId ENTRY = SourceId.of("project.sir");
    static final List<String> FILES = List.of("project.sir", "modules/course.sir", "modules/student.sir", "modules/enrollment.sir");
    private MultiSourceTestSupport() {}
    public static Path fixture(Path directory) throws Exception {
        Path source = directory.resolve("sources");
        for (String file : FILES) {
            Path path = source.resolve(file); Files.createDirectories(path.getParent());
            try (var stream = MultiSourceTestSupport.class.getClassLoader().getResourceAsStream("valid/multi-course/" + file)) {
                Files.write(path, Objects.requireNonNull(stream).readAllBytes());
            }
        }
        return source;
    }
    static ProjectToolchainResult.Success generate(Path source, Path output) {
        var result = new ToolchainApplication().executeProject(new ProjectToolchainRequest(source, ENTRY, output));
        return assertInstanceOf(ProjectToolchainResult.Success.class, result, result.diagnostics().toString());
    }
    static ProjectSemanticInput parse(SourceSnapshot snapshot) throws Exception {
        var parser = SirParser.create();
        var parsed = parser.parseProject(new SirSource(snapshot.entry(), SourceReader.decodeStrictUtf8(snapshot.bytes(snapshot.entry()))));
        assertTrue(parsed.isSuccess(), parsed.diagnostics().toString());
        var root = (AstSourceUnit.Root) parsed.unit().orElseThrow();
        List<AstSourceUnit.Fragment> fragments = new ArrayList<>();
        for (var file : snapshot.manifest().files()) {
            if (file.sourceId().equals(snapshot.entry())) continue;
            var fragment = parser.parseFragment(new SirSource(file.sourceId(), SourceReader.decodeStrictUtf8(snapshot.bytes(file.sourceId()))));
            assertTrue(fragment.isSuccess(), fragment.diagnostics().toString());
            fragments.add((AstSourceUnit.Fragment) fragment.unit().orElseThrow());
        }
        return new ProjectSemanticInput(root, fragments);
    }
    static NormalizedSemanticModel model(SourceSnapshot snapshot) throws Exception {
        var result = new SirSemanticAnalyzer().analyzeProject(parse(snapshot));
        assertTrue(result.isSuccess(), result.diagnostics().toString());
        return result.model().orElseThrow();
    }
    public static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root)) return result;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) continue;
                String key = root.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) result.put(key, "LINK:" + Files.readSymbolicLink(path));
                else if (Files.isRegularFile(path)) result.put(key, HexFormat.of().formatHex(Files.readAllBytes(path)));
                else result.put(key + "/", "DIRECTORY");
            }
        }
        return result;
    }
    static Map<String, String> files(Path root) throws Exception {
        var tree = tree(root); tree.keySet().removeIf(k -> k.endsWith("/")); return tree;
    }
    static String resource(String file) throws Exception {
        try (var stream = MultiSourceTestSupport.class.getClassLoader().getResourceAsStream(file)) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
