package io.kcg.sir.application.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.kcg.sir.application.api.VersionControl;
import io.kcg.sir.application.api.VersionControl.Failed;
import io.kcg.sir.application.api.VersionControl.State;
import io.kcg.sir.application.api.VersionControl.Taken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The adapter runs the real {@code git}. What is under test is the contract the product relies on: a snapshot
 * saves exactly the named files and nothing else, names are never read as patterns, and a missing or unusable
 * Git is reported instead of being worked around.
 */
class GitVersionControlTest {
    @TempDir Path temp;
    private Path home;
    private Path work;
    private GitVersionControl git;

    @BeforeEach void setUp() throws IOException {
        // An empty home and no system configuration: no Git identity exists unless a test makes one.
        home = Files.createDirectory(temp.resolve("home"));
        work = Files.createDirectory(temp.resolve("work"));
        git = new GitVersionControl("git", Map.of("HOME", home.toString(), "GIT_CONFIG_NOSYSTEM", "1"), Duration.ofSeconds(60));
        assumeTrue(git.inspect(work).state() != State.UNAVAILABLE, "git is not installed");
    }

    @Test void reportsWhetherThereIsARepositoryAndWhereItsTopIs() throws Exception {
        assertEquals(State.NO_REPOSITORY, git.inspect(work).state());
        assertTrue(git.initialize(work).isEmpty());
        assertEquals(State.REPOSITORY, git.inspect(work).state());

        Path inside = Files.createDirectory(work.resolve("inside"));
        var nested = git.inspect(inside);
        assertEquals(State.NOT_TOP_LEVEL, nested.state(), nested.detail());
        assertEquals(State.NO_REPOSITORY, git.inspect(temp.resolve("missing")).state());
    }

    @Test void aGitThatCannotBeRunIsReportedAsUnavailableAndSavesNothing() throws Exception {
        var broken = new GitVersionControl("kcg-no-such-git-program", Map.of("HOME", home.toString()), Duration.ofSeconds(5));
        assertEquals(State.UNAVAILABLE, broken.inspect(work).state());
        write("a.txt", "a");
        assertInstanceOf(Failed.class, broken.snapshot(work, List.of("a.txt"), "x"));
        assertTrue(broken.initialize(work).isPresent(), "init must report failure too");
        assertFalse(Files.exists(work.resolve(".git")));
    }

    @Test void snapshotSavesOnlyTheNamedFilesAndLeavesTheRestAlone() throws Exception {
        git.initialize(work);
        write("src/a.sir", "one");
        write("gen/B.java", "class B {}");
        write("notes.txt", "mine, not for the snapshot");
        write("staged.txt", "already staged by the user");
        run("add", "staged.txt");

        var outcome = assertInstanceOf(Taken.class, git.snapshot(work, List.of("src/a.sir", "gen/B.java"), "first"));
        assertTrue(outcome.created());
        assertTrue(outcome.commit().isPresent());
        assertEquals(List.of("gen/B.java", "src/a.sir"), run("show", "--name-only", "--format=", "HEAD").lines().sorted().toList());
        // The user's own state is exactly as it was: still untracked, still staged and not committed.
        assertEquals("A  staged.txt\n?? notes.txt", run("status", "--porcelain").strip());
    }

    @Test void savingTheSameFilesAgainCreatesNoCommit() throws Exception {
        git.initialize(work);
        write("a.txt", "one");
        var first = assertInstanceOf(Taken.class, git.snapshot(work, List.of("a.txt"), "first"));
        var second = assertInstanceOf(Taken.class, git.snapshot(work, List.of("a.txt"), "second"));
        assertFalse(second.created());
        assertEquals(first.commit(), second.commit());
        assertEquals("1", run("rev-list", "--count", "HEAD").strip());
    }

    @Test void changesNewFilesAndRemovalsAreAllSaved() throws Exception {
        git.initialize(work);
        write("keep.txt", "k");
        write("change.txt", "v1");
        write("gone.txt", "g");
        assertInstanceOf(Taken.class, git.snapshot(work, List.of("keep.txt", "change.txt", "gone.txt"), "base"));

        write("change.txt", "v2");
        Files.delete(work.resolve("gone.txt"));
        write("new/dir/added.txt", "n");
        var outcome = assertInstanceOf(Taken.class, git.snapshot(work, List.of("keep.txt", "change.txt", "gone.txt", "new/dir/added.txt"), "step"));
        assertTrue(outcome.created());
        assertEquals(List.of("A\tnew/dir/added.txt", "D\tgone.txt", "M\tchange.txt"), run("show", "--name-status", "--format=", "HEAD").lines().sorted().toList());
        assertEquals("", run("status", "--porcelain").strip());
    }

    @Test void aNameThatLooksLikeAPatternOrAnOptionIsTakenLiterally() throws Exception {
        git.initialize(work);
        write("ab.txt", "pattern would also match this");
        write("[ab].txt", "literal");
        write("-n.txt", "option-like");
        assertInstanceOf(Taken.class, git.snapshot(work, List.of("[ab].txt", "-n.txt"), "literal"));
        assertEquals(List.of("-n.txt", "[ab].txt"), run("show", "--name-only", "--format=", "HEAD").lines().sorted().toList());
        assertEquals("?? ab.txt", run("status", "--porcelain").strip());
    }

    @Test void pathsOutsideTheWorkingTreeOrInsideGitAreRefused() throws Exception {
        git.initialize(work);
        for (String path : List.of("../x", "/etc/passwd", "a/../b", ".git/config", "a\\b", "dir/", "")) {
            assertInstanceOf(Failed.class, git.snapshot(work, List.of(path), "x"), "must refuse: " + path);
        }

        assertEquals(State.REPOSITORY, git.inspect(work).state());
    }

    @Test void aFileGitIgnoresIsReportedNotSilentlySkipped() throws Exception {
        git.initialize(work);
        write(".gitignore", "*.log\n");
        write("app.log", "ignored");
        var outcome = git.snapshot(work, List.of("app.log"), "x");
        assertInstanceOf(Failed.class, outcome);
        assertTrue(((Failed)outcome).reason().contains("ignore"), ((Failed)outcome).reason());
    }

    @Test void thereIsNothingToSaveInADirectoryThatIsNotARepository() throws Exception {
        write("a.txt", "a");
        var outcome = git.snapshot(work, List.of("a.txt"), "x");
        assertInstanceOf(Failed.class, outcome);
    }

    @Test void withoutAnyGitIdentityTheFallbackIsUsedAndNoConfigurationIsWritten() throws Exception {
        git.initialize(work);
        write("a.txt", "a");
        assertInstanceOf(Taken.class, git.snapshot(work, List.of("a.txt"), "x"));
        assertEquals("KCG <kcg@localhost>", run("log", "-1", "--format=%an <%ae>").strip());
        assertFalse(run("config", "--local", "--list").contains("user."), "the user's configuration must not be written");
    }

    @Test void theUsersOwnGitIdentityIsUsedWhenThereIsOne() throws Exception {
        git.initialize(work);
        run("config", "user.name", "Alice");
        run("config", "user.email", "alice@example.com");
        write("a.txt", "a");
        assertInstanceOf(Taken.class, git.snapshot(work, List.of("a.txt"), "x"));
        assertEquals("Alice <alice@example.com>", run("log", "-1", "--format=%an <%ae>").strip());
    }

    private void write(String relative, String text) throws IOException {
        Path file = work.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Reads what Git itself says, independently of the adapter under test. */
    private String run(String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), String.join(" ", command) + ": " + output);
        return output;
    }
}
