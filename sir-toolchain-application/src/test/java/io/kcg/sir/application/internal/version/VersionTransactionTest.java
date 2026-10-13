package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineVerification;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateReceipt;
import io.kcg.sir.application.internal.version.VersionScenarios.Kind;
import io.kcg.sir.application.internal.version.VersionScenarios.Scenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The transaction ends in the candidate version, and the generated project equals a from-scratch generation. */
class VersionTransactionTest {
    static final String TX = "a".repeat(64);

    @TempDir Path temp;

    static void apply(Scenario s, List<String> trace) throws Exception {
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            new VersionTransaction(state, output, TX, trace::add).apply(s.base(), s.candidate(), s.plan());
        }
    }

    /** The one definition of "the candidate version is now in place". */
    static void assertCandidateInPlace(Scenario s) throws Exception {
        assertEquals(s.candidateTree(), MultiSourceTestSupport.tree(s.output()), "the generated project equals a from-scratch generation of the candidate");
        assertEquals(s.candidateId(), Files.readString(s.state().resolve("CURRENT")).trim());
        try (var state = new SecureFileAccess(s.state())) {
            assertEquals(Optional.of(s.candidateId()), new ProjectBaselineStore(state).current());
            new ProjectBaselineStore(state).guardState(s.candidateId(), false);
            assertEquals(java.util.Set.of(), state.list(VersionTransaction.ACTIVE, 1), "no transaction is left active");
            var receipt = VersionReceipt.read(state, TX);
            assertEquals(VersionTrail.Phase.COMPLETED, receipt.trail().state().phase());
            assertTrue(state.exists(ProjectUpdateReceipt.DIRECTORY + "/" + TX));
            var verified = ProjectBaselineVerification.read(state, s.candidateId(), s.output(), new ArrayList<>());
            assertNotNull(verified, "the stored candidate replays and matches the output");
        }
    }

    @Test
    void everyKindOfVersionChangeEndsInTheCandidateExactlyAsAFreshGenerationWould() throws Exception {
        for (Kind kind : Kind.values()) {
            Scenario s = VersionScenarios.create(this.temp.resolve(kind.name()), kind);
            if (kind != Kind.SOURCE_ONLY) {
                assertNotEquals(s.baseTree(), s.candidateTree(), kind + " must actually change the generated project");
            }

            List<String> trace = new ArrayList<>();

            apply(s, trace);

            assertCandidateInPlace(s);
            assertTrue(trace.contains("binding-complete") && trace.contains("current-published") && trace.contains("finished"), kind.toString());
        }
    }

    @Test
    void theFilePlansHaveTheKindsOfOperationEachScenarioIsMeantToCover() throws Exception {
        VersionPlan mixed = VersionScenarios.create(this.temp.resolve("m"), Kind.MIXED).plan();
        assertTrue(mixed.count(VersionPlan.Action.CREATE) > 0 && mixed.count(VersionPlan.Action.UPDATE) > 0 && mixed.count(VersionPlan.Action.DELETE) > 0);
        VersionPlan rename = VersionScenarios.create(this.temp.resolve("r"), Kind.RENAME).plan();
        assertTrue(rename.count(VersionPlan.Action.CREATE) > 0 && rename.count(VersionPlan.Action.DELETE) > 0, "a new package moves every generated file");
        assertTrue(VersionScenarios.create(this.temp.resolve("s"), Kind.SOURCE_ONLY).plan().sourceOnly());
        VersionPlan add = VersionScenarios.create(this.temp.resolve("a"), Kind.ADD_ONLY).plan();
        assertEquals(0, add.count(VersionPlan.Action.DELETE));
        VersionPlan delete = VersionScenarios.create(this.temp.resolve("d"), Kind.DELETE_ONLY).plan();
        assertEquals(0, delete.count(VersionPlan.Action.CREATE));
    }

    @Test
    void aNewPackageCreatesAndRemovesWholeDirectoryTreesWithNothingLeftBehind() throws Exception {
        Scenario s = VersionScenarios.create(this.temp.resolve("rename"), Kind.RENAME);
        assertTrue(s.baseTree().containsKey("src/main/java/com/example/courseadmin/"));
        assertFalse(s.candidateTree().containsKey("src/main/java/com/example/courseadmin/"));

        apply(s, new ArrayList<>());

        assertCandidateInPlace(s);
        assertFalse(Files.exists(s.output().resolve("src/main/java/com/example/courseadmin")), "the old package directory is gone");
        assertTrue(Files.isDirectory(s.output().resolve("src/main/java/com/example/coursemanager")));
    }

    @Test
    void aFileOfTheUsersInsideAnOldDirectoryKeepsThatDirectory() throws Exception {
        Scenario s = VersionScenarios.create(this.temp.resolve("rename"), Kind.RENAME);
        Path mine = s.output().resolve("src/main/java/com/example/courseadmin/api/NOTES.txt");
        Files.writeString(mine, "not generated, not mine to delete\n");

        apply(s, new ArrayList<>());

        assertEquals("not generated, not mine to delete\n", Files.readString(mine));
        assertTrue(Files.isDirectory(s.output().resolve("src/main/java/com/example/courseadmin/api")));
        assertFalse(Files.exists(s.output().resolve("src/main/java/com/example/courseadmin/api/GetCourseController.java")));
        assertEquals(s.candidateId(), Files.readString(s.state().resolve("CURRENT")).trim());
    }

    @Test
    void aSourceOnlyVersionChangesTheStoredVersionAndNoGeneratedFile() throws Exception {
        Scenario s = VersionScenarios.create(this.temp.resolve("source-only"), Kind.SOURCE_ONLY);
        assertEquals(s.baseTree(), s.candidateTree());

        apply(s, new ArrayList<>());

        assertCandidateInPlace(s);
        assertNotEquals(s.baseId(), s.candidateId());
    }

    @Test
    void theTransactionRefusesBeforeChangingAnythingWhenItsPreconditionsAreFalse() throws Exception {
        Scenario s = VersionScenarios.create(this.temp.resolve("refuse"), Kind.MIXED);
        Map<String, String> state = MultiSourceTestSupport.tree(s.state());
        Map<String, String> output = MultiSourceTestSupport.tree(s.output());

        // a plan for another pair of versions
        Scenario other = VersionScenarios.create(this.temp.resolve("other"), Kind.ADD_ONLY);
        assertThrows(IOException.class, () -> {
            try (var st = new SecureFileAccess(s.state()); var out = new SecureFileAccess(s.output())) {
                new VersionTransaction(st, out, TX, x -> { }).apply(s.base(), s.candidate(), other.plan());
            }
        });
        // a managed file edited by hand
        VersionPlan.FileOp updated = s.plan().files().stream().filter(f -> f.action() == VersionPlan.Action.UPDATE).findFirst().orElseThrow();
        Files.writeString(s.output().resolve(updated.path()), "edited by hand\n");
        assertThrows(IOException.class, () -> apply(s, new ArrayList<>()));
        assertEquals(state, MultiSourceTestSupport.tree(s.state()), "a refusal leaves the state untouched");

        String original = s.base().compilation().generatedFiles().stream().filter(f -> f.relativePath().equals(updated.path())).findFirst().orElseThrow().content();
        Files.writeString(s.output().resolve(updated.path()), original);
        assertEquals(output, MultiSourceTestSupport.tree(s.output()));

        // a new file would land on a path somebody else already uses
        VersionPlan.FileOp created = s.plan().files().stream().filter(f -> f.action() == VersionPlan.Action.CREATE).findFirst().orElseThrow();
        Files.writeString(s.output().resolve(created.path()), "somebody else's file\n");
        assertThrows(IOException.class, () -> apply(s, new ArrayList<>()));
        assertEquals(state, MultiSourceTestSupport.tree(s.state()));
        Files.delete(s.output().resolve(created.path()));

        // and with everything in order it goes through, but the same plan cannot be applied twice
        apply(s, new ArrayList<>());
        assertCandidateInPlace(s);
        assertThrows(IOException.class, () -> apply(s, new ArrayList<>()), "CURRENT is no longer the version the plan starts from");
    }
}
