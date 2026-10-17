package io.kcg.sir.application;

import io.kcg.sir.application.api.*;
import io.kcg.sir.source.SourceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Source export and standalone bootstrap only. Never Docker, Maven builds or business application processes. */
class DeploymentPackageApplicationTest {
    @TempDir Path temp;
    private static final SourceId ENTRY = SourceId.of("project.sir");

    @Test void exportIsDeterministicCompleteAndLeavesSourcesUntouched() throws Exception {
        Path source = source(true); String before = Files.readString(source.resolve("project.sir"));
        var first = export(source, temp.resolve("delivery one"));
        var second = export(source, temp.resolve("delivery two"));
        assertEquals(first.packageId(), second.packageId());
        assertEquals(before, Files.readString(source.resolve("project.sir")));
        assertEquals(before, Files.readString(first.manifest().outputRoot().resolve("sir/project.sir")));
        String sql = Files.readString(first.manifest().outputRoot().resolve("deploy/migrations/V1__initialize.sql"));
        assertTrue(sql.contains("`password_hash` VARCHAR(255) NOT NULL"));
        assertTrue(sql.contains("`name` VARCHAR(64) NOT NULL UNIQUE"));
        assertTrue(sql.contains("CHAR(36)")); assertTrue(sql.contains("FOREIGN KEY"));
        assertTrue(sql.indexOf("ALTER TABLE") > sql.lastIndexOf("CREATE TABLE"));
        assertFalse(Files.readString(first.manifest().outputRoot().resolve("app/pom.xml")).contains("flyway"), "app must not get migration credentials/engine");
        assertTrue(Files.readString(first.manifest().outputRoot().resolve("app/pom.xml")).contains("starter-actuator"));
        for (String script : List.of("deploy.sh", "build.sh", "accounts.sh"))
            assertEquals(0, process(List.of("sh", "-n", first.manifest().outputRoot().resolve("deploy/" + script).toString()), temp.resolve(script + ".log")));
    }

    @Test void unsupportedStorageAndExistingOutputsAreRefusedBeforePublication() throws Exception {
        Path source = source(false), output = temp.resolve("refused");
        var result = new DeploymentPackageApplication().export(new DeploymentPackageRequest(source, ENTRY, output));
        assertInstanceOf(DeploymentPackageResult.Failure.class, result);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-DEPLOY-UNSUPPORTED-001")));
        assertFalse(Files.exists(output));
        Files.createDirectory(output); Files.writeString(output.resolve("owned"), "keep");
        assertInstanceOf(DeploymentPackageResult.Failure.class, new DeploymentPackageApplication().export(new DeploymentPackageRequest(source, ENTRY, output)));
        assertEquals("keep", Files.readString(output.resolve("owned")));
        assertInstanceOf(DeploymentPackageResult.Failure.class, new DeploymentPackageApplication().export(new DeploymentPackageRequest(source, ENTRY, source.resolve("overlap"))));
    }

    @Test void bootstrapPersistsCredentialsAndRejectsTamperedPackagesOrMissingSecrets() throws Exception {
        Path root = export(source(true), temp.resolve("delivery")).manifest().outputRoot();
        Path classes = Files.createDirectory(temp.resolve("bootstrap-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), root.resolve("deploy/Bootstrap.java").toString()));
        Path state = temp.resolve("persistent state");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xmx64m", "-XX:MaxMetaspaceSize=96m",
            "-Dkcg.package.root=" + root, "-cp", classes.toString(), "Bootstrap", state.toString(), "demo", "18080");
        assertEquals(0, process(command, temp.resolve("bootstrap-first.log")));
        String secret = Files.readString(state.resolve("app-password"));
        String environment = Files.readString(state.resolve("environment.id"));
        assertTrue(secret.matches("[0-9a-f]{64}"));
        assertEquals(0, process(command, temp.resolve("bootstrap-repeat.log")));
        assertEquals(secret, Files.readString(state.resolve("app-password")));
        assertEquals(environment, Files.readString(state.resolve("environment.id")));
        Files.delete(state.resolve("app-password"));
        assertNotEquals(0, process(command, temp.resolve("bootstrap-missing.log")));
        assertFalse(Files.exists(state.resolve("app-password")), "never regenerate credentials for an existing database");
        Files.writeString(root.resolve("deploy/schema.tsv"), "tampered");
        assertNotEquals(0, process(command, temp.resolve("bootstrap-tampered.log")));
    }

    @Test void entryRejectsAStateAncestorSymlinkBeforeAnyDockerAcquisition() throws Exception {
        Path root = export(source(true), temp.resolve("delivery")).manifest().outputRoot();
        Path alias = temp.resolve("alias"); Files.createSymbolicLink(alias, root);
        Path bin = Files.createDirectory(temp.resolve("fake-bin"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\n: > \"$KCG_TEST_DOCKER_MARKER\"\nexit 90\n");
        assertTrue(docker.toFile().setExecutable(true));
        Path marker = temp.resolve("docker-was-invoked"), log = temp.resolve("symlink-refusal.log");
        var builder = new ProcessBuilder("sh", root.resolve("deploy/deploy.sh").toString(), "deploy", "--instance", "demo",
            "--state-root", alias.resolve("state").toString());
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("KCG_TEST_DOCKER_MARKER", marker.toString());
        var process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(3, process.exitValue());
        assertTrue(Files.readString(log).contains("symlink"));
        assertFalse(Files.exists(marker)); assertFalse(Files.exists(root.resolve("state")));
    }

    private DeploymentPackageResult.Exported export(Path source, Path output) {
        var result = new DeploymentPackageApplication().export(new DeploymentPackageRequest(source, ENTRY, output));
        return assertInstanceOf(DeploymentPackageResult.Exported.class, result, result.toString());
    }
    private Path source(boolean bounded) throws Exception {
        Path root = Files.createTempDirectory(temp, "source-");
        try (var input = getClass().getResourceAsStream("/valid/dual-token-scaffolded.sir")) {
            String text = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
            if (bounded) text = text.replace("field name: String unique;", "field name: String where length(1, 64) unique;");
            Files.writeString(root.resolve("project.sir"), text);
        }
        return root;
    }
    private static int process(List<String> command, Path log) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); fail("process timeout: " + command.getFirst()); }
        return p.exitValue();
    }
}
