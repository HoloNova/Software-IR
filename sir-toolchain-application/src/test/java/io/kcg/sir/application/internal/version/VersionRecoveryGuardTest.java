package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.MultiSourceTestSupport;
import io.kcg.sir.application.api.VersionPlan;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.version.VersionScenarios.Kind;
import io.kcg.sir.application.internal.version.VersionScenarios.Scenario;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery acts on a half-finished transaction, so it must not trust what it finds. Anything that
 * was replaced or altered from outside is refused and left exactly as it is - including when
 * restoring would have been "easy".
 */
class VersionRecoveryGuardTest {
    private static final String TX = VersionTransactionTest.TX;

    @TempDir Path temp;

    private static Scenario halfApplied(Path root, String stop) throws Exception {
        Scenario s = VersionScenarios.create(root, Kind.MIXED);
        VersionFaultMatrixTest.stopApplyAt(s, stop);
        return s;
    }

    private static void assertRefusedAndUntouched(Scenario s, Body body) throws Exception {
        Map<String, String> state = MultiSourceTestSupport.tree(s.state());
        Map<String, String> output = MultiSourceTestSupport.tree(s.output());
        assertThrows(IOException.class, body::run);
        assertEquals(state, MultiSourceTestSupport.tree(s.state()), "a refused recovery changes no state");
        assertEquals(output, MultiSourceTestSupport.tree(s.output()), "a refused recovery changes no generated file");
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    @Test
    void anUpdatedFileReplacedFromOutsideIsNotRolledBackOverTheNewOwner() throws Exception {
        Scenario s = halfApplied(this.temp.resolve("replaced"), "output-applied:3");
        // find a step the transaction already applied and swap the file for another with the same name
        VersionPlan.FileOp updated = s.plan().files().stream().filter(f -> f.action() == VersionPlan.Action.UPDATE).findFirst().orElseThrow();
        Path path = s.output().resolve(updated.path());
        Path other = s.output().resolve(updated.path() + ".other");
        Files.writeString(other, "a different file, a different inode\n");
        Files.move(other, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertRefusedAndUntouched(s, () -> VersionFaultMatrixTest.recover(s));
    }

    @Test
    void aFileSomebodyElsePutOnAPathTheTransactionWasAboutToCreateIsKept() throws Exception {
        Scenario s = halfApplied(this.temp.resolve("occupied"), "binding-complete");
        VersionPlan.FileOp created = s.plan().files().stream().filter(f -> f.action() == VersionPlan.Action.CREATE).findFirst().orElseThrow();
        Files.writeString(s.output().resolve(created.path()), "somebody else's file\n");

        assertRefusedAndUntouched(s, () -> VersionFaultMatrixTest.recover(s));
        assertEquals("somebody else's file\n", Files.readString(s.output().resolve(created.path())));
    }

    @Test
    void aTamperedBindingIsRefusedBecauseTheSavedSourcesDoNotProduceIt() throws Exception {
        Scenario s = halfApplied(this.temp.resolve("tampered"), "output-applied:2");
        Path binding = s.state().resolve("project-transactions/" + TX + "/binding");
        byte[] bytes = Files.readAllBytes(binding);
        // change the byte count of the first file step inside the binding: still a well-formed binding
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int at = text.indexOf("UPDATE");
        assertTrue(at > 0);
        bytes[at + 1] ^= 1;
        Files.write(binding, bytes);

        assertRefusedAndUntouched(s, () -> VersionFaultMatrixTest.recover(s));
    }

    @Test
    void aHandleForAnotherBindingIsRefused() throws Exception {
        Scenario s = halfApplied(this.temp.resolve("handle"), "output-applied:1");

        assertRefusedAndUntouched(s, () -> {
            try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
                new VersionTransaction(state, output, TX, x -> { }).recover(Optional.of("f".repeat(64)));
            }
        });
        // the correct handle works
        try (var state = new SecureFileAccess(s.state())) {
            String digest = VersionTransaction.loadBinding(state, TX).digest();
            try (var output = new SecureFileAccess(s.output())) {
                assertFalse(new VersionTransaction(state, output, TX, x -> { }).recover(Optional.of(digest)), "CURRENT is still the old version, so the direction is back");
            }
        }

        assertEquals(s.baseTree(), MultiSourceTestSupport.tree(s.output()));
    }

    @Test
    void aSecondTransactionCannotStartWhileOneIsUnfinished() throws Exception {
        Scenario s = halfApplied(this.temp.resolve("blocked"), "output-applied:1");
        String other = "b".repeat(64);

        assertRefusedAndUntouched(s, () -> {
            try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
                new VersionTransaction(state, output, other, x -> { }).apply(s.base(), s.candidate(), s.plan());
            }
        });
    }

    @Test
    void afterAnInterruptedRecoveryTheSameRecoveryFinishesAndOnlyThenAnotherUpdateIsPossible() throws Exception {
        Scenario s = halfApplied(this.temp.resolve("twice"), "output-applied:3");
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            int[] count = {0};
            assertThrows(IllegalStateException.class, () -> new VersionTransaction(state, output, TX, p -> {
                if (p.startsWith("restore-intent:") && ++count[0] == 2) {
                    throw new IllegalStateException("stopped");
                }
            }).recover(Optional.empty()));
        }

        assertFalse(VersionFaultMatrixTest.recover(s));
        assertEquals(s.baseTree(), MultiSourceTestSupport.tree(s.output()));
        try (var state = new SecureFileAccess(s.state()); var output = new SecureFileAccess(s.output())) {
            assertThrows(IOException.class, () -> new VersionTransaction(state, output, TX, x -> { }).apply(s.base(), s.candidate(), s.plan()), "a finished transaction ID is never reused");
            new VersionTransaction(state, output, "c".repeat(64), x -> { }).apply(s.base(), s.candidate(), s.plan());
        }

        assertEquals(s.candidateTree(), MultiSourceTestSupport.tree(s.output()));
        assertEquals(s.candidateId(), Files.readString(s.state().resolve("CURRENT")).trim());
    }
}
