package io.kcg.cli;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KcgPackageCommandTest {
    @TempDir Path temp;
    @Test void packageIsAnExportNotADeploymentAndRefusesOverwrite() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source")), output = temp.resolve("package");
        String text;
        try (var input = getClass().getResourceAsStream("/io/kcg/cli/dual-token-base.sir")) {
            text = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        text = text.substring(0, text.indexOf("    capability PostNote")) + "  }\n}\n";
        text = text.replace("field name: Text unique;", "field name: Text where length(1, 64) unique;");
        Files.writeString(source.resolve("project.sir"), text);
        var result = run("package", "--source-root", source.toString(), "--entry", "project.sir", "--output-root", output.toString());
        assertEquals(0, result.exit(), result.stdout() + result.stderr());
        var document = CommandJson.object(StrictJson.parse(result.stdout()));
        assertEquals("PACKAGE_EXPORTED", document.get("outcome"));
        assertEquals("package", document.get("command"));
        assertTrue(Files.isRegularFile(output.resolve("deploy/compose.yaml")));
        assertEquals(text, Files.readString(source.resolve("project.sir")));
        assertEquals(3, run("package", "--source-root", source.toString(), "--entry", "project.sir", "--output-root", output.toString()).exit());
        assertEquals(2, run("package", "--source-root", source.toString()).exit());
    }
    private CliTestFixtures.ProcResult run(String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Xmx128m", "-XX:MaxMetaspaceSize=128m", "-cp", System.getProperty("java.class.path"), "io.kcg.cli.KcgCli"));
        command.addAll(List.of(args));
        Path stdout = temp.resolve(UUID.randomUUID() + ".out"), stderr = temp.resolve(UUID.randomUUID() + ".err");
        var p = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("CLI timeout"); }
        return new CliTestFixtures.ProcResult(p.exitValue(), Files.readString(stdout), Files.readString(stderr));
    }
}
