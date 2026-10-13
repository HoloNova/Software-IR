package io.kcg.sir.application.internal.version;

import static org.junit.jupiter.api.Assertions.*;

import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Kind;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Lifetime;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Owned;
import io.kcg.sir.application.internal.version.VersionBinding.Op;
import io.kcg.sir.application.internal.version.VersionBinding.OpKind;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** The binding is the only evidence recovery trusts, so it is decoded as strictly as it is encoded. */
class VersionBindingTest {
    private static final String ID = "1".repeat(64);
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final String SHA_C = "c".repeat(64);
    private static final String PREFIX = "project-transactions/" + ID + "/";
    private static final Path STATE = Path.of("/tmp/state");
    private static final Path OUTPUT = Path.of("/tmp/output");

    private static ProjectPublicationHistory.Edge edge() {
        return new ProjectPublicationHistory.Edge("2".repeat(64), "3".repeat(64), "4".repeat(64), OUTPUT, SHA_A, SHA_B, SHA_A, SHA_B, SHA_A, SHA_B, SHA_C, SHA_C, "version-update");
    }

    /** One of every kind, in the legal order. */
    private static List<Op> allKinds() {
        return List.of(
            new Op(OpKind.MKDIR, "src/new", "", "", "", 0, "", 0, ""),
            new Op(OpKind.UPDATE, "src/keep/C.java", "p-keep", "k-base-1", "k-stage-1", 30, SHA_A, 31, SHA_B),
            new Op(OpKind.CREATE, "src/new/A.java", "", "", "k-stage-2", 0, "", 10, SHA_A),
            new Op(OpKind.DELETE, "src/old/B.java", "p-old", "k-base-3", "", 20, SHA_B, 0, ""),
            new Op(OpKind.RMDIR, "src/old", "p-src", "k-dir-old", "", 0, "", 0, ""));
    }

    private static List<Owned> ownedFor(List<Op> ops) {
        List<Owned> owned = new ArrayList<>();
        owned.add(new Owned(PREFIX + "staging", Kind.DIRECTORY, "d1", Lifetime.TRANSACTION, 0, ""));
        owned.add(new Owned(PREFIX + "backups", Kind.DIRECTORY, "d2", Lifetime.TRANSACTION, 0, ""));
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.staged()) {
                owned.add(new Owned(PREFIX + "staging/" + i, Kind.FILE, op.candidateKey(), Lifetime.TRANSACTION, op.candidateSize(), op.candidateSha()));
            }

            if (op.backedUp()) {
                owned.add(new Owned(PREFIX + "backups/" + i, Kind.FILE, op.baseKey(), Lifetime.TRANSACTION, op.baseSize(), op.baseSha()));
            }
        }

        owned.add(new Owned(PREFIX + "candidate.sources", Kind.FILE, "k-sources", Lifetime.RECEIPT, 50, SHA_C));
        owned.add(new Owned("project-history", Kind.DIRECTORY, "d3", Lifetime.STATE, 0, ""));
        owned.add(new Owned("baselines/" + edge().b1(), Kind.DIRECTORY, "d4", Lifetime.CANDIDATE, 0, ""));
        for (String member : ProjectBaselineStore.MEMBERS) {
            owned.add(new Owned("baselines/" + edge().b1() + "/" + member, Kind.FILE, "k-" + member, Lifetime.CANDIDATE, 5, SHA_A));
        }

        owned.add(new Owned("project-history/" + edge().id(), Kind.FILE, "k-edge", Lifetime.CANDIDATE, 5, SHA_A));
        return owned;
    }

    private static VersionBinding binding(List<Op> ops, List<Owned> owned) {
        return new VersionBinding(ID, STATE, OUTPUT, "s1", "o1", "t1", "x1", "ja", "jb", edge(), SHA_C, ops, owned);
    }

    private static VersionBinding valid() {
        List<Op> ops = allKinds();
        return binding(ops, ownedFor(ops));
    }

    @Test
    void aBindingWithEveryKindOfStepRoundTripsExactly() throws Exception {
        VersionBinding binding = valid();

        assertEquals(binding, VersionBinding.decode(binding.bytes()));
        assertEquals(binding.digest(), VersionBinding.decode(binding.bytes()).digest());
        assertEquals(5, binding.ops().size());
    }

    @Test
    void aSourceOnlyBindingHasNoStep() throws Exception {
        List<Owned> owned = ownedFor(List.of());

        VersionBinding binding = binding(List.of(), owned);

        assertEquals(binding, VersionBinding.decode(binding.bytes()));
        assertTrue(binding.ops().isEmpty());
    }

    @Test
    void stepsMustBeInTheLegalOrder() {
        List<Op> ops = new ArrayList<>(allKinds());
        // a file step before its directory
        List<Op> swapped = new ArrayList<>(List.of(ops.get(1), ops.get(0), ops.get(2), ops.get(3), ops.get(4)));
        assertThrows(IllegalArgumentException.class, () -> binding(swapped, ownedFor(swapped)));
        // file steps out of path order
        List<Op> unsorted = new ArrayList<>(List.of(ops.get(0), ops.get(1), ops.get(3), ops.get(2), ops.get(4)));
        assertThrows(IllegalArgumentException.class, () -> binding(unsorted, ownedFor(unsorted)));
        // directory removals must go children first
        List<Op> twoRemovals = new ArrayList<>(List.of(ops.get(2),
            new Op(OpKind.RMDIR, "src/old", "p-src", "k1", "", 0, "", 0, ""),
            new Op(OpKind.RMDIR, "src/old/deep", "p-old", "k2", "", 0, "", 0, "")));
        assertThrows(IllegalArgumentException.class, () -> binding(twoRemovals, ownedFor(twoRemovals)));
        // a duplicate path
        List<Op> duplicate = new ArrayList<>(List.of(ops.get(0), ops.get(1), ops.get(1)));
        assertThrows(IllegalArgumentException.class, () -> binding(duplicate, ownedFor(duplicate)));
    }

    @Test
    void everyKindCarriesExactlyTheEvidenceItNeeds() {
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.CREATE, "a/B.java", "", "k-old", "k-new", 0, "", 1, SHA_A), "a CREATE has no old file");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.CREATE, "a/B.java", "", "", "", 0, "", 1, SHA_A), "a CREATE needs its staged identity");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.UPDATE, "a/B.java", "p", "k", "k", 1, SHA_A, 2, SHA_B), "old and new are different files");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.UPDATE, "a/B.java", "p", "k1", "k2", 1, SHA_A, 1, SHA_A), "an UPDATE changes the bytes");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.DELETE, "a/B.java", "", "k1", "", 1, SHA_A, 0, ""), "a DELETE needs its parent");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.MKDIR, "a", "", "k1", "", 0, "", 0, ""), "a MKDIR has no old directory");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.RMDIR, "a", "p", "k1", "", 3, "", 0, ""), "a directory has no bytes");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.CREATE, "../B.java", "", "", "k", 0, "", 1, SHA_A), "unsafe path");
        assertThrows(IllegalArgumentException.class, () -> new Op(OpKind.CREATE, "a/B.java", "", "", "k", 0, "", 1, "xyz"), "digest must be SHA-256 hex");
    }

    @Test
    void ownershipMustMatchTheStepsExactly() {
        List<Op> ops = allKinds();
        UnaryOperator<List<Owned>> dropFirstStaging = list -> list.stream().filter(o -> !o.relativePath().equals(PREFIX + "staging/2")).toList();
        assertThrows(IllegalArgumentException.class, () -> binding(ops, dropFirstStaging.apply(ownedFor(ops))), "the CREATE lost its staging copy");

        UnaryOperator<List<Owned>> dropBackup = list -> list.stream().filter(o -> !o.relativePath().equals(PREFIX + "backups/3")).toList();
        assertThrows(IllegalArgumentException.class, () -> binding(ops, dropBackup.apply(ownedFor(ops))), "the DELETE lost its backup");

        List<Owned> wrongKey = new ArrayList<>(ownedFor(ops));
        wrongKey.replaceAll(o -> o.relativePath().equals(PREFIX + "backups/1") ? new Owned(o.relativePath(), o.kind(), "someone-else", o.lifetime(), o.size(), o.sha()) : o);
        assertThrows(IllegalArgumentException.class, () -> binding(ops, wrongKey), "backup identity differs from the recorded base file");

        List<Owned> extra = new ArrayList<>(ownedFor(ops));
        extra.add(new Owned(PREFIX + "staging/0", Kind.FILE, "k", Lifetime.TRANSACTION, 1, SHA_A));
        assertThrows(IllegalArgumentException.class, () -> binding(ops, extra), "a MKDIR has no staging copy, so nothing may be owned there");

        List<Owned> stray = new ArrayList<>(ownedFor(ops));
        stray.add(new Owned(PREFIX + "elsewhere", Kind.FILE, "k", Lifetime.TRANSACTION, 1, SHA_A));
        assertThrows(IllegalArgumentException.class, () -> binding(ops, stray), "unknown transaction object");

        List<Owned> receipt = new ArrayList<>(ownedFor(ops));
        receipt.add(new Owned(PREFIX + "pins", Kind.DIRECTORY, "k", Lifetime.RECEIPT, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> binding(ops, receipt), "receipts keep only the saved candidate sources");

        List<Owned> candidate = new ArrayList<>(ownedFor(ops));
        candidate.add(new Owned("baselines/" + "9".repeat(64), Kind.DIRECTORY, "k", Lifetime.CANDIDATE, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> binding(ops, candidate), "only the candidate's own Bundle may be owned");

        List<Owned> duplicate = new ArrayList<>(ownedFor(ops));
        duplicate.add(duplicate.get(0));
        assertThrows(IllegalArgumentException.class, () -> binding(ops, duplicate));
    }

    @Test
    void decodingRejectsAnythingEncodingWouldNotHaveProduced() throws Exception {
        byte[] bytes = valid().bytes();

        assertEquals(valid(), VersionBinding.decode(bytes));
        assertThrows(Exception.class, () -> VersionBinding.decode(Arrays.copyOf(bytes, bytes.length - 3)), "truncated");
        assertThrows(Exception.class, () -> VersionBinding.decode(Arrays.copyOf(bytes, bytes.length + 1)), "trailing byte");
        byte[] magic = bytes.clone();
        magic[3] ^= 1;
        assertThrows(Exception.class, () -> VersionBinding.decode(magic), "wrong magic");
        byte[] flipped = bytes.clone();
        flipped[flipped.length - 5] ^= 1;
        assertThrows(Exception.class, () -> VersionBinding.decode(flipped), "a changed byte changes either the meaning or the canonical form");
        assertThrows(Exception.class, () -> VersionBinding.decode(new byte[0]));
    }

    @Test
    void theBindingFitsItsBudgetAtFullSize() throws Exception {
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < VersionTrail.MAX_OPS; i++) {
            ops.add(new Op(OpKind.UPDATE, String.format("src/main/java/com/example/module/deeply/nested/package/File%04d.java", i), "p", "base-" + i, "stage-" + i, 10, SHA_A, 11, SHA_B));
        }

        VersionBinding binding = binding(ops, ownedFor(ops).stream().limit(VersionBinding.MAX_OWNED).toList());

        assertEquals(binding, VersionBinding.decode(binding.bytes()));
        assertTrue(binding.bytes().length < VersionFrames.MAX_RECORD_BYTES);
        assertThrows(IllegalArgumentException.class, () -> {
            List<Op> tooMany = new ArrayList<>(ops);
            tooMany.add(new Op(OpKind.UPDATE, "zzz/Last.java", "p", "base-x", "stage-x", 10, SHA_A, 11, SHA_B));
            binding(tooMany, ownedFor(tooMany));
        });
    }
}
