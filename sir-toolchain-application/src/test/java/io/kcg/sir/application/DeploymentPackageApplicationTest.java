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
        assertTrue(Files.readString(first.manifest().outputRoot().resolve("app/pom.xml")).contains("springdoc-openapi-starter-webmvc-ui"));
        for (String script : List.of("deploy.sh", "build.sh", "accounts.sh", "contract-test.sh", "acceptance-test.sh"))
            assertEquals(0, process(List.of("sh", "-n", first.manifest().outputRoot().resolve("deploy/" + script).toString()), temp.resolve(script + ".log")));
    }

    @Test void acceptanceSuiteIsCopiedVerbatimIntoTheManifestOrRefusedBeforePublication() throws Exception {
        Path source = source(true);
        Path suite = Files.createDirectories(temp.resolve("suite/src/test/java/a"));
        Path pom = Files.writeString(temp.resolve("suite/pom.xml"), "<project>\u00e9</project>\n");
        Files.writeString(suite.resolve("SuiteTest.java"), "class SuiteTest {}\n");
        var exported = new DeploymentPackageApplication().export(new DeploymentPackageRequest(source, ENTRY, temp.resolve("with suite"), Optional.of(temp.resolve("suite"))));
        Path root = assertInstanceOf(DeploymentPackageResult.Exported.class, exported, exported.toString()).manifest().outputRoot();
        assertArrayEquals(Files.readAllBytes(pom), Files.readAllBytes(root.resolve("acceptance/pom.xml")));
        String manifest = Files.readString(root.resolve("package.sha256"));
        assertTrue(manifest.contains("  acceptance/pom.xml\n") && manifest.contains("  acceptance/src/test/java/a/SuiteTest.java\n"));

        Files.write(suite.resolve("Built.class"), new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        assertAcceptanceRefused(source, "not UTF-8 text");
        Files.delete(suite.resolve("Built.class"));
        Files.createSymbolicLink(suite.resolve("Linked.java"), suite.resolve("SuiteTest.java"));
        assertAcceptanceRefused(source, "symbolic link");
        Files.delete(suite.resolve("Linked.java"));
        Files.delete(pom);
        assertAcceptanceRefused(source, "root pom.xml");
    }

    private void assertAcceptanceRefused(Path source, String reason) {
        Path output = temp.resolve("refused " + UUID.randomUUID());
        var result = new DeploymentPackageApplication().export(new DeploymentPackageRequest(source, ENTRY, output, Optional.of(temp.resolve("suite"))));
        assertInstanceOf(DeploymentPackageResult.Failure.class, result);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("SIR-APP-PACKAGE-ACCEPTANCE-001") && d.message().contains(reason)), result.toString());
        assertFalse(Files.exists(output), "nothing is published");
    }

    @Test void acceptanceTestNeedsTheRepeatedInstanceNameAndASuiteBeforeAnyDockerCall() throws Exception {
        Path root = export(source(true), temp.resolve("delivery")).manifest().outputRoot();
        Path bin = Files.createDirectory(temp.resolve("fake-bin"));
        Path docker = bin.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\n: > \"$KCG_TEST_DOCKER_MARKER\"\nexit 90\n");
        assertTrue(docker.toFile().setExecutable(true));
        Path marker = temp.resolve("docker-was-invoked"), state = temp.resolve("state");
        assertEquals(2, entry(root, bin, marker, "unconfirmed", "acceptance-test", "--instance", "demo", "--state-root", state.toString()));
        assertEquals(2, entry(root, bin, marker, "other-name", "acceptance-test", "--instance", "demo", "--state-root", state.toString(), "--confirm-disposable", "prod"));
        assertEquals(2, entry(root, bin, marker, "wrong-action", "start", "--instance", "demo", "--state-root", state.toString(), "--confirm-disposable", "demo"));
        Path log = temp.resolve("no-suite.log");
        assertEquals(4, entry(root, bin, marker, "no-suite", "acceptance-test", "--instance", "demo", "--state-root", state.toString(), "--confirm-disposable", "demo"));
        assertTrue(Files.readString(log).contains("KCG_ACCEPTANCE_NOT_RUN"));
        assertFalse(Files.exists(marker), "no Docker call before these refusals");
        assertFalse(Files.exists(state));
    }

    private int entry(Path root, Path bin, Path marker, String name, String... args) throws Exception {
        var command = new ArrayList<>(List.of("sh", root.resolve("deploy/deploy.sh").toString()));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("KCG_TEST_DOCKER_MARKER", marker.toString());
        var process = builder.redirectErrorStream(true).redirectOutput(temp.resolve(name + ".log").toFile()).start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return process.exitValue();
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

    @Test void onlyUpdateMayRunAnotherPackageOnExistingStateAndItNeverCreatesOrRewritesState() throws Exception {
        Path source = source(true);
        Path first = export(source, temp.resolve("first")).manifest().outputRoot();
        Files.writeString(source.resolve("project.sir"), Files.readString(source.resolve("project.sir")) + "\n// a later version\n");
        Path second = export(source, temp.resolve("second")).manifest().outputRoot();
        assertNotEquals(Files.readString(first.resolve("package.id")), Files.readString(second.resolve("package.id")));
        Path classes = Files.createDirectory(temp.resolve("bootstrap-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), second.resolve("deploy/Bootstrap.java").toString()));
        Path state = temp.resolve("state");
        assertEquals(0, bootstrap(classes, first, state, "deploy", "bound-first"));
        String bound = Files.readString(state.resolve("package.id"));
        for (String action : List.of("deploy", "start", "stop", "status"))
            assertNotEquals(0, bootstrap(classes, second, state, action, "other-" + action), action + " must not run another package");
        assertTrue(Files.readString(temp.resolve("other-start.log")).contains("use update"));
        assertEquals(0, bootstrap(classes, second, state, "update", "update"));
        assertEquals(bound, Files.readString(state.resolve("package.id")), "the bound package changes only after a completed update");
        assertNotEquals(0, bootstrap(classes, second, temp.resolve("absent"), "update", "update-absent"));
        assertFalse(Files.exists(temp.resolve("absent")), "update never creates an instance");
    }

    private int bootstrap(Path classes, Path root, Path state, String action, String name) throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-Xmx64m", "-XX:MaxMetaspaceSize=96m",
            "-Dkcg.package.root=" + root, "-cp", classes.toString(), "Bootstrap", state.toString(), "demo", "18080", action);
        return process(command, temp.resolve(name + ".log"));
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
