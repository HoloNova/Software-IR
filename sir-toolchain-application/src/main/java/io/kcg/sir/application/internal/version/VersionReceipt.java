package io.kcg.sir.application.internal.version;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.decodeSources;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.MAX_PAYLOAD;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.require;

import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateBinding.Lifetime;
import io.kcg.sir.application.internal.projectupdate.ProjectUpdateReceipt;
import java.io.IOException;
import java.util.Set;

/**
 * The terminal evidence of a finished version transaction (completed or rolled back): the whole
 * transaction directory, relocated as one unit, and read back with every identity re-checked.
 */
public final class VersionReceipt {
    public static final Set<String> MEMBERS = Set.of("binding", "binding.anchor", "journal.A", "journal.B", "journal.A.anchor", "journal.B.anchor", "journal", "candidate.sources");

    private VersionReceipt() {
    }

    public record Verified(VersionBinding binding, VersionTrail trail, long byteCount) {
    }

    public static Verified read(SecureFileAccess files, String transactionId) throws IOException {
        String dir = ProjectUpdateReceipt.DIRECTORY + "/" + transactionId;
        require(files.list(dir, 12).equals(MEMBERS), "terminal receipt has missing or unknown members");
        byte[] bytes = files.read(dir + "/binding", VersionFrames.MAX_RECORD_BYTES);
        VersionBinding binding = VersionBinding.decode(bytes);
        require(binding.transactionId().equals(transactionId) && binding.stateRoot().equals(files.root()), "receipt transaction and state binding mismatch");
        require(files.directoryIdentity("").toString().equals(binding.stateKey()) && files.directoryIdentity(dir).toString().equals(binding.directoryKey()), "receipt physical directory binding changed");
        require(files.identity(dir + "/binding").equals(files.identity(dir + "/binding.anchor")), "receipt binding anchor replaced");
        require(files.identity(dir + "/journal.A").toString().equals(binding.slotAKey()) && files.identity(dir + "/journal.B").toString().equals(binding.slotBKey()), "receipt slot identity changed");
        require(files.identity(dir + "/journal.A").equals(files.identity(dir + "/journal.A.anchor")) && files.identity(dir + "/journal.B").equals(files.identity(dir + "/journal.B.anchor")), "receipt slot anchors changed");
        String selected = files.identity(dir + "/journal").toString();
        require(selected.equals(binding.slotAKey()) || selected.equals(binding.slotBKey()), "receipt journal selector changed");
        byte[] journal = files.read(dir + "/journal", VersionJournalSlots.MAX_JOURNAL_BYTES);
        VersionTrail trail = VersionTrail.decode(journal);
        require(trail.bindingDigest().equals(binding.digest()) && trail.fileCount() == binding.ops().size(), "receipt journal and header mismatch");
        require(trail.terminal(), "a transaction that is not finished cannot be a history receipt");
        byte[] payload = files.read(dir + "/candidate.sources", MAX_PAYLOAD);
        var evidence = binding.owned().stream().filter(o -> o.relativePath().equals(binding.prefix() + "/candidate.sources") && o.lifetime() == Lifetime.RECEIPT).toList();
        require(evidence.size() == 1, "receipt lacks the unique saved candidate sources");
        require(files.identity(dir + "/candidate.sources").toString().equals(evidence.getFirst().key()) && payload.length == evidence.getFirst().size()
            && Sha256.hexDigest(payload).equals(evidence.getFirst().sha()), "receipt candidate sources were replaced or corrupted");
        require(decodeSources(payload).sha256Hex().equals(binding.publication().candidateSource()), "receipt candidate source set mismatch");
        long total = bytes.length + journal.length + payload.length;
        total += files.read(dir + "/journal.A", VersionJournalSlots.MAX_JOURNAL_BYTES).length;
        total += files.read(dir + "/journal.B", VersionJournalSlots.MAX_JOURNAL_BYTES).length;
        return new Verified(binding, trail, total);
    }
}
