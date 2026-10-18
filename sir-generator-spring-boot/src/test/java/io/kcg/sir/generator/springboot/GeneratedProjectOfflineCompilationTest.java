package io.kcg.sir.generator.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeneratedProjectOfflineCompilationTest {

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(3);

    @Test
    void completeCampusMarketProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/campus-market.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "com/example/campusmarket/Application.class",
                "com/example/campusmarket/domain/User.class",
                "com/example/campusmarket/api/PublishGoodsInput.class",
                "com/example/campusmarket/persistence/UserMapper.class",
                "com/example/campusmarket/application/PublishGoodsService.class",
                "com/example/campusmarket/api/PublishGoodsController.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    @Test
    void completePagedQueryProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/course-catalog.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "com/example/coursecatalog/Application.class",
                "com/example/coursecatalog/domain/Course.class",
                "com/example/coursecatalog/api/CourseSummary.class",
                "com/example/coursecatalog/api/PageResponse.class",
                "com/example/coursecatalog/api/InvalidPageParamException.class",
                "com/example/coursecatalog/application/SearchCoursesService.class",
                "com/example/coursecatalog/api/SearchCoursesController.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    @Test
    void completeWriteSliceProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/course-admin.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "com/example/courseadmin/Application.class",
                "com/example/courseadmin/domain/Course.class",
                "com/example/courseadmin/api/CourseDetail.class",
                "com/example/courseadmin/api/UpdateCourseInput.class",
                "com/example/courseadmin/api/UpdateCourseInput$Changes.class",
                "com/example/courseadmin/api/ApiErrorResponse.class",
                "com/example/courseadmin/api/ApiErrorResponse$FieldError.class",
                "com/example/courseadmin/api/ApiException.class",
                "com/example/courseadmin/api/ApiExceptionAdvice.class",
                "com/example/courseadmin/api/ValidationSupport.class",
                "com/example/courseadmin/api/StaleVersionException.class",
                "com/example/courseadmin/persistence/CourseMapper.class",
                "com/example/courseadmin/application/CreateCourseService.class",
                "com/example/courseadmin/application/UpdateCourseService.class",
                "com/example/courseadmin/api/UpdateCourseController.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /**
     * The lookup by a unique field is the one generated shape no other project here contains: a list
     * query bounded at two rows, then the first row. Its Java has to compile, not just be written.
     */
    @Test
    void uniqueLookupProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/unique-lookup.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/lookup/Application.class",
                "example/lookup/domain/Account.class",
                "example/lookup/persistence/AccountMapper.class",
                "example/lookup/application/FindAccountByNameService.class",
                "example/lookup/application/FindAccountByBadgeService.class",
                "example/lookup/application/GetAccountService.class",
                "example/lookup/application/RenameAccountService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /**
     * Plus, minus and time shifts render as {@code Math.addExact} and {@code Instant.plus}: calls on boxed
     * input values and on {@code Instant} and {@code LocalDate}, whose overloads only a compiler can vouch for.
     */
    @Test
    void timeArithmeticProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/time-arithmetic.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/tickets/Application.class",
                "example/tickets/domain/Ticket.class",
                "example/tickets/application/OpenTicketService.class",
                "example/tickets/application/LiveTicketsService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /**
     * A guarded update is written with {@code LambdaUpdateWrapper}: a nested condition, a column per
     * {@code set}, and the row count of {@code update(null, wrapper)}. Only a compiler can vouch for those overloads.
     */
    @Test
    void guardedUpdateProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/guarded-update.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/credentials/Application.class",
                "example/credentials/domain/Credential.class",
                "example/credentials/application/RefreshService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /** A secret field adds a generated digest class and calls it from two services; only a compiler can vouch for both. */
    @Test
    void secretFieldProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/secret-field.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/accounts/persistence/SecretDigest.class",
                "example/accounts/application/RegisterService.class",
                "example/accounts/application/LoginService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /** A credential entity adds a generated authenticator that is a conditional component of the application; only a compiler can vouch for it. */
    @Test
    void bearerCredentialProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/bearer-credential.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/notes/application/CredentialIdentityAdapter.class",
                "example/notes/application/LoginService.class",
                "example/notes/application/PostNoteService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /**
     * One use of Crud per entity writes every service of a create, show, list, change and delete, including a list of
     * every row ordered by a uuid and a patch of a versioned entity; only a compiler can vouch for them.
     */
    @Test
    void crudFeatureProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/crud-feature.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/crud/application/ListItemService.class",
                "example/crud/application/UpdateNoteService.class",
                "example/crud/application/DeleteUserService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    /** Reading the actor's role reads the actor's row in the service; only a compiler can vouch for it. */
    @Test
    void actorRoleProjectCompilesWithFrozenDependenciesOffline(@TempDir Path tempDir)
            throws Exception {
        Path projectRoot = tempDir.resolve("generated-project");
        List<GeneratedFile> files = GeneratorTestSupport.generateSuccess("valid/actor-role.sir");
        materialize(files, projectRoot);
        compileOffline(projectRoot, tempDir);

        for (String classFile : List.of(
                "example/roles/application/RemoveGoodsService.class",
                "example/roles/application/RenameGoodsService.class")) {
            assertTrue(Files.isRegularFile(projectRoot.resolve("target/classes").resolve(classFile)),
                    () -> "offline Maven compile did not create representative class: " + classFile);
        }
    }

    private static void compileOffline(Path projectRoot, Path tempDir) throws Exception {
        Path maven = locateMavenExecutable();
        Path localRepository = frozenLocalRepository();
        Path buildLog = tempDir.resolve("generated-project-maven.log");
        List<String> command = List.of(
                maven.toString(),
                "--batch-mode",
                "--no-transfer-progress",
                "--offline",
                "-Dmaven.repo.local=" + localRepository,
                "-DskipTests",
                "compile");

        Process process = new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(buildLog.toFile())
                .start();

        boolean finished = process.waitFor(BUILD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output = Files.exists(buildLog)
                ? Files.readString(buildLog, StandardCharsets.UTF_8)
                : "<build log was not created>";
        if (!finished) {
            fail(failureMessage(command, projectRoot, "timed out after " + BUILD_TIMEOUT, output));
        }
        assertEquals(0, process.exitValue(),
                () -> failureMessage(command, projectRoot, "exit code " + process.exitValue(), output));
    }

    private static void materialize(List<GeneratedFile> files, Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot);
        for (GeneratedFile file : files) {
            Path target = projectRoot.resolve(file.relativePath()).normalize();
            assertTrue(target.startsWith(projectRoot),
                    () -> "generated path escaped the temporary project: " + file.relativePath());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
            assertEquals(file.content(), Files.readString(target, StandardCharsets.UTF_8),
                    () -> "materialized UTF-8 content changed: " + file.relativePath());
        }
    }

    private static Path frozenLocalRepository() {
        String configured = System.getProperty("maven.repo.local");
        assertTrue(configured != null && !configured.isBlank(),
                "Q1D requires the parent build to pass -Dmaven.repo.local=<frozen repository>");
        Path repository = Path.of(configured).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(repository),
                () -> "configured frozen Maven repository does not exist: " + repository);
        return repository;
    }

    private static Path locateMavenExecutable() {
        String executableName = isWindows() ? "mvn.cmd" : "mvn";
        List<Path> candidates = new ArrayList<>();
        addMavenHomeCandidate(candidates, System.getProperty("maven.home"), executableName);
        addMavenHomeCandidate(candidates, System.getenv("MAVEN_HOME"), executableName);
        addMavenHomeCandidate(candidates, System.getenv("M2_HOME"), executableName);

        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    candidates.add(Path.of(entry).resolve(executableName));
                }
            }
        }

        return candidates.stream()
                .map(candidate -> candidate.toAbsolutePath().normalize())
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "cannot locate " + executableName + " from maven.home, MAVEN_HOME, M2_HOME, or PATH; candidates="
                                + candidates));
    }

    private static void addMavenHomeCandidate(List<Path> candidates, String home, String executableName) {
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home).resolve("bin").resolve(executableName));
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String failureMessage(
            List<String> command, Path projectRoot, String result, String output) {
        return "generated project offline compile failed\n"
                + "working directory: " + projectRoot + "\n"
                + "command: " + String.join(" ", command) + "\n"
                + "result: " + result + "\n"
                + "combined Maven output:\n" + output;
    }
}
