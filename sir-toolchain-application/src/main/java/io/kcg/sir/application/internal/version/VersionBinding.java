package io.kcg.sir.application.internal.version;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.MAX_GENERATED_FILE;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.MAX_OUTPUT_PATH;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.budget;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.hex;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.problem;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.require;

import io.kcg.sir.application.internal.PathGuard;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Kind;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Lifetime;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Owned;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable evidence of one version transaction, written completely before the first change to
 * the generated project.
 *
 * <p>It lists every operation in the order it will run, the physical identity of every file and
 * directory it will touch, and every object the transaction itself owns (staging copies, backups,
 * the saved candidate version). Recovery trusts nothing else: a file whose identity differs from
 * what is recorded here was replaced by someone else and is left alone.
 */
public record VersionBinding(String transactionId, Path stateRoot, Path outputRoot, String stateKey, String outputKey, String transactionsKey,
        String directoryKey, String slotAKey, String slotBKey, ProjectPublicationHistory.Edge publication, String planDigest, List<Op> ops, List<Owned> owned) {
    public static final String MAGIC = "KCG-PROJECT-VERSION-BINDING-V1\n";
    public static final int MAX_OWNED = 640;
    /** The largest binding record that can be read back. */
    public static final int MAX_BYTES = VersionFrames.MAX_RECORD_BYTES;

    public enum OpKind { MKDIR, CREATE, UPDATE, DELETE, RMDIR }

    /**
     * One step. A value that does not apply to the kind is empty ("" or 0). {@code parentKey} is empty
     * when the parent directory is itself created by this transaction and so has no identity yet.
     */
    public record Op(OpKind kind, String path, String parentKey, String baseKey, String candidateKey, long baseSize, String baseSha, long candidateSize, String candidateSha) {
        public Op {
            Objects.requireNonNull(kind, "kind");
            safePath(path);
            for (String key : List.of(parentKey, baseKey, candidateKey)) {
                if (!key.isEmpty()) {
                    key(key);
                }
            }

            boolean hasBase = !baseKey.isEmpty();
            boolean hasCandidate = !candidateKey.isEmpty();
            boolean baseBytes = !baseSha.isEmpty();
            boolean candidateBytes = !candidateSha.isEmpty();
            if ((baseBytes && !baseSha.matches("[0-9a-f]{64}")) || (candidateBytes && !candidateSha.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("invalid byte evidence");
            }

            boolean sizes = baseSize >= 0 && candidateSize >= 0 && baseSize <= MAX_GENERATED_FILE && candidateSize <= MAX_GENERATED_FILE;
            boolean valid = sizes && switch (kind) {
                case MKDIR -> !hasBase && !hasCandidate && !baseBytes && !candidateBytes && baseSize == 0 && candidateSize == 0;
                case RMDIR -> !parentKey.isEmpty() && hasBase && !hasCandidate && !baseBytes && !candidateBytes && baseSize == 0 && candidateSize == 0;
                case CREATE -> !hasBase && hasCandidate && !baseBytes && candidateBytes && baseSize == 0;
                case DELETE -> !parentKey.isEmpty() && hasBase && !hasCandidate && baseBytes && !candidateBytes && candidateSize == 0;
                case UPDATE -> !parentKey.isEmpty() && hasBase && hasCandidate && !baseKey.equals(candidateKey) && baseBytes && candidateBytes
                    && !(baseSize == candidateSize && baseSha.equals(candidateSha));
            };
            if (!valid) {
                throw new IllegalArgumentException("invalid " + kind + " evidence for " + path);
            }
        }

        /** A step that has a copy of the new bytes waiting in staging. */
        public boolean staged() {
            return this.kind == OpKind.CREATE || this.kind == OpKind.UPDATE;
        }

        /** A step that keeps a hard-link backup of the old bytes. */
        public boolean backedUp() {
            return this.kind == OpKind.UPDATE || this.kind == OpKind.DELETE;
        }
    }

    public VersionBinding {
        hex(transactionId);
        hex(planDigest);
        Objects.requireNonNull(stateRoot);
        Objects.requireNonNull(outputRoot);
        Objects.requireNonNull(publication);
        if (!stateRoot.isAbsolute() || !stateRoot.equals(stateRoot.normalize()) || !outputRoot.isAbsolute() || !outputRoot.equals(outputRoot.normalize())
            || !outputRoot.equals(publication.outputRoot())) {
            throw new IllegalArgumentException("invalid transaction roots");
        }

        ProjectPublicationHistory.validText(stateRoot.toString(), MAX_OUTPUT_PATH);
        ProjectPublicationHistory.validText(outputRoot.toString(), MAX_OUTPUT_PATH);
        for (String k : List.of(stateKey, outputKey, transactionsKey, directoryKey, slotAKey, slotBKey)) {
            key(k);
        }

        if (slotAKey.equals(slotBKey)) {
            throw new IllegalArgumentException("journal slots alias");
        }

        ops = List.copyOf(ops);
        owned = owned.stream().sorted(Comparator.comparing(Owned::relativePath)).toList();
        if (ops.size() > VersionTrail.MAX_OPS || owned.size() > MAX_OWNED) {
            throw new IllegalArgumentException("transaction object count outside budget");
        }

        checkOrder(ops);
        checkOwnership(transactionId, publication, ops, owned);
    }

    /** Directories first (parents before children), then files by path, then directory removals (children first). */
    private static void checkOrder(List<Op> ops) {
        int stage = 0;
        String previous = null;
        for (Op op : ops) {
            int own = switch (op.kind()) {
                case MKDIR -> 0;
                case CREATE, UPDATE, DELETE -> 1;
                case RMDIR -> 2;
            };
            if (own < stage) {
                throw new IllegalArgumentException("operations are out of order");
            }

            if (own != stage) {
                stage = own;
                previous = null;
            }

            if (previous != null) {
                int order = previous.compareTo(op.path());
                if ((own == 2 ? order <= 0 : order >= 0)) {
                    throw new IllegalArgumentException("operation paths must be strictly " + (own == 2 ? "descending" : "ascending"));
                }
            }

            previous = op.path();
        }
    }

    private static void checkOwnership(String transactionId, ProjectPublicationHistory.Edge publication, List<Op> ops, List<Owned> owned) {
        String prefix = "project-transactions/" + transactionId + "/";
        Set<String> paths = new HashSet<>();
        for (Owned o : owned) {
            if (!paths.add(o.relativePath())) {
                throw new IllegalArgumentException("duplicate owned path");
            }

            switch (o.lifetime()) {
                case TRANSACTION -> {
                    boolean allowed = o.kind() == Kind.DIRECTORY && Set.of(prefix + "staging", prefix + "backups").contains(o.relativePath());
                    for (int i = 0; i < ops.size(); i++) {
                        allowed |= o.kind() == Kind.FILE && ((ops.get(i).staged() && o.relativePath().equals(prefix + "staging/" + i))
                            || (ops.get(i).backedUp() && o.relativePath().equals(prefix + "backups/" + i)));
                    }

                    if (!allowed) {
                        throw new IllegalArgumentException("transaction ownership outside exact staging and backup rows");
                    }
                }
                case RECEIPT -> {
                    if (!(o.kind() == Kind.FILE && o.relativePath().equals(prefix + "candidate.sources"))) {
                        throw new IllegalArgumentException("receipt ownership outside the saved candidate sources");
                    }
                }
                case STATE -> {
                    if (!Set.of("project-history", "project-history/receipts", "project-history/origin").contains(o.relativePath())) {
                        throw new IllegalArgumentException("state ownership outside history infrastructure");
                    }
                }
                case CANDIDATE -> {
                    String dir = "baselines/" + publication.b1();
                    boolean allowed = o.kind() == Kind.DIRECTORY && o.relativePath().equals(dir);
                    allowed |= o.kind() == Kind.FILE && (o.relativePath().equals("project-history/" + publication.id())
                        || ProjectBaselineStore.MEMBERS.stream().anyMatch(name -> o.relativePath().equals(dir + "/" + name)));
                    if (!allowed) {
                        throw new IllegalArgumentException("candidate ownership outside exact Bundle members and publication relation");
                    }
                }
            }
        }

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            if (op.staged()) {
                Owned staged = find(owned, prefix + "staging/" + i);
                if (staged.kind() != Kind.FILE || staged.lifetime() != Lifetime.TRANSACTION || !staged.key().equals(op.candidateKey())
                    || staged.size() != op.candidateSize() || !staged.sha().equals(op.candidateSha())) {
                    throw new IllegalArgumentException("missing or mismatched staging ownership for operation " + i);
                }
            }

            if (op.backedUp()) {
                Owned backup = find(owned, prefix + "backups/" + i);
                if (backup.kind() != Kind.FILE || backup.lifetime() != Lifetime.TRANSACTION || !backup.key().equals(op.baseKey())
                    || backup.size() != op.baseSize() || !backup.sha().equals(op.baseSha())) {
                    throw new IllegalArgumentException("missing or mismatched backup ownership for operation " + i);
                }
            }
        }
    }

    private static Owned find(List<Owned> owned, String path) {
        return owned.stream().filter(o -> o.relativePath().equals(path)).findFirst().orElseThrow(() -> new IllegalArgumentException("missing owned path: " + path));
    }

    public String prefix() {
        return "project-transactions/" + this.transactionId;
    }

    public String digest() {
        return Sha256.hexDigest(this.bytes());
    }

    public byte[] bytes() {
        List<String> fields = new ArrayList<>(List.of(this.transactionId, this.stateRoot.toString(), this.outputRoot.toString(), this.stateKey, this.outputKey,
            this.transactionsKey, this.directoryKey, this.slotAKey, this.slotBKey, HexFormat.of().formatHex(this.publication.bytes()), this.planDigest,
            Integer.toString(this.ops.size()), Integer.toString(this.owned.size())));
        for (Op op : this.ops) {
            fields.addAll(List.of(op.kind().name(), op.path(), op.parentKey(), op.baseKey(), op.candidateKey(), Long.toString(op.baseSize()), op.baseSha(),
                Long.toString(op.candidateSize()), op.candidateSha()));
        }

        for (Owned o : this.owned) {
            fields.addAll(List.of(o.relativePath(), o.kind().name(), o.key(), o.lifetime().name(), Long.toString(o.size()), o.sha()));
        }

        return VersionFrames.encode(MAGIC, fields);
    }

    public static VersionBinding decode(byte[] bytes) throws IOException {
        try {
            int count = VersionFrames.announcedCount(bytes, MAGIC);
            budget(count >= 13 && count <= 13 + 9 * VersionTrail.MAX_OPS + 6 * MAX_OWNED, "binding object budget exceeded");
            List<String> f = VersionFrames.decode(bytes, MAGIC, count);
            int opCount = integer(f.get(11));
            int ownedCount = integer(f.get(12));
            budget(opCount >= 0 && opCount <= VersionTrail.MAX_OPS && ownedCount >= 0 && ownedCount <= MAX_OWNED, "binding counts outside budget");
            require(count == 13 + opCount * 9 + ownedCount * 6, "binding counts do not match fields");
            List<Op> ops = new ArrayList<>();
            List<Owned> owned = new ArrayList<>();
            int i = 13;
            for (int n = 0; n < opCount; n++) {
                ops.add(new Op(OpKind.valueOf(f.get(i)), f.get(i + 1), f.get(i + 2), f.get(i + 3), f.get(i + 4), number(f.get(i + 5)), f.get(i + 6), number(f.get(i + 7)), f.get(i + 8)));
                i += 9;
            }

            for (int n = 0; n < ownedCount; n++) {
                owned.add(new Owned(f.get(i), Kind.valueOf(f.get(i + 1)), f.get(i + 2), Lifetime.valueOf(f.get(i + 3)), number(f.get(i + 4)), f.get(i + 5)));
                i += 6;
            }

            VersionBinding result = new VersionBinding(f.get(0), Path.of(f.get(1)), Path.of(f.get(2)), f.get(3), f.get(4), f.get(5), f.get(6), f.get(7), f.get(8),
                ProjectPublicationHistory.Edge.decode(HexFormat.of().parseHex(f.get(9))), f.get(10), ops, owned);
            require(Arrays.equals(bytes, result.bytes()), "noncanonical transaction binding");
            return result;
        } catch (IllegalArgumentException e) {
            throw problem("FORMAT-001", "invalid version binding: " + e.getMessage());
        }
    }

    private static int integer(String s) {
        int n = Integer.parseInt(s);
        if (!Integer.toString(n).equals(s)) {
            throw new IllegalArgumentException("noncanonical integer");
        }

        return n;
    }

    private static long number(String s) {
        long n = Long.parseLong(s);
        if (!Long.toString(n).equals(s)) {
            throw new IllegalArgumentException("noncanonical byte count");
        }

        return n;
    }

    private static void key(String key) {
        ProjectPublicationHistory.validText(key, 256);
        if (key.isBlank()) {
            throw new IllegalArgumentException("blank physical identity");
        }
    }

    private static void safePath(String path) {
        ProjectPublicationHistory.validText(path, MAX_OUTPUT_PATH);
        if (PathGuard.validateRelativePath(path) != null) {
            throw new IllegalArgumentException("unsafe owned or operation path");
        }
    }
}
