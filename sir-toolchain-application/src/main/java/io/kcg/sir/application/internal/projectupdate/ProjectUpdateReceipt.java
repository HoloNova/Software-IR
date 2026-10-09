package io.kcg.sir.application.internal.projectupdate;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import java.io.*;
import java.util.*;

/** Bounded terminal evidence, atomically relocated as a directory; not an active log or a commit head. */
public final class ProjectUpdateReceipt {
    public static final String DIRECTORY="project-history/receipts";
    public static final Set<String> MEMBERS=Set.of("binding","binding.anchor","journal.A","journal.B","journal.A.anchor","journal.B.anchor","journal","candidate.sources","pins");
    public static final int MAX_RECEIPTS=32;
    private ProjectUpdateReceipt() {}
    public record Verified(ProjectUpdateBinding binding,ProjectUpdateTrail trail,long byteCount) {}
    public static Verified read(SecureFileAccess files,String transactionId) throws IOException {
        hex(transactionId);String dir=DIRECTORY+"/"+transactionId;
        require(files.list(dir,12).equals(MEMBERS),"terminal receipt has missing/unknown members");
        byte[] bytes=files.read(dir+"/binding",65536);var binding=ProjectUpdateBinding.decode(bytes);
        require(binding.transactionId().equals(transactionId) && binding.stateRoot().equals(files.root()),"receipt transaction/state binding mismatch");
        require(files.directoryIdentity("").toString().equals(binding.stateKey()) && files.directoryIdentity(dir).toString().equals(binding.directoryKey()),"receipt physical directory binding changed");
        require(files.identity(dir+"/binding").equals(files.identity(dir+"/binding.anchor")),"receipt binding anchor replaced");
        require(files.identity(dir+"/journal.A").toString().equals(binding.slotAKey()) && files.identity(dir+"/journal.B").toString().equals(binding.slotBKey()),"receipt slot identity changed");
        require(files.identity(dir+"/journal.A").equals(files.identity(dir+"/journal.A.anchor")) && files.identity(dir+"/journal.B").equals(files.identity(dir+"/journal.B.anchor")),"receipt slot physical pins changed");
        String selected=files.identity(dir+"/journal").toString();require(selected.equals(binding.slotAKey()) || selected.equals(binding.slotBKey()),"receipt journal selector changed");
        byte[] journal=files.read(dir+"/journal",65536);var trail=ProjectUpdateTrail.decode(journal);
        require(trail.bindingDigest().equals(binding.digest()) && trail.fileCount()==binding.updates().size(),"receipt journal/header mismatch");
        require(trail.state().phase()==ProjectUpdateTrail.Phase.COMPLETED || trail.state().phase()==ProjectUpdateTrail.Phase.ROLLED_BACK,"nonterminal transaction cannot be a history receipt");
        byte[] payload=files.read(dir+"/candidate.sources",MAX_PAYLOAD);
        var owned=binding.owned().stream().filter(o->o.relativePath().equals(binding.prefix()+"/candidate.sources") && o.lifetime()==ProjectUpdateBinding.Lifetime.RECEIPT).toList();require(owned.size()==1,"receipt lacks unique candidate replay evidence");
        var evidence=owned.getFirst();require(files.identity(dir+"/candidate.sources").toString().equals(evidence.key()) && payload.length==evidence.size() && io.kcg.sir.application.internal.Sha256.hexDigest(payload).equals(evidence.sha()),"receipt candidate replay was replaced/corrupted");
        require(decodeSources(payload).sha256Hex().equals(binding.publication().candidateSource()),"receipt candidate source-set mismatch");
        long total=bytes.length+journal.length+payload.length;total+=files.read(dir+"/journal.A",65536).length;total+=files.read(dir+"/journal.B",65536).length;
        var pins=binding.owned().stream().filter(o->o.lifetime()==ProjectUpdateBinding.Lifetime.RECEIPT && o.relativePath().startsWith(binding.prefix()+"/pins/")).toList();
        var directory=binding.owned().stream().filter(o->o.relativePath().equals(binding.prefix()+"/pins") && o.kind()==ProjectUpdateBinding.Kind.DIRECTORY).findFirst().orElseThrow(()->problem("STATE-001","receipt physical pin directory missing"));
        require(files.directoryIdentity(dir+"/pins").toString().equals(directory.key()),"receipt pin directory replaced");
        var names=pins.stream().map(o->o.relativePath().substring((binding.prefix()+"/pins/").length())).collect(java.util.stream.Collectors.toSet());require(files.list(dir+"/pins",256).equals(names),"unknown/missing receipt pin members");
        for(var pin:pins) {String path=dir+pin.relativePath().substring(binding.prefix().length());total+=pin.size();budget(total<=ProjectPublicationHistory.MAX_HISTORY_BYTES,"receipt byte budget exceeded before pin read");byte[] raw=files.read(path,(int)pin.size());require(files.identity(path).toString().equals(pin.key()) && raw.length==pin.size() && io.kcg.sir.application.internal.Sha256.hexDigest(raw).equals(pin.sha()),"receipt physical pin replaced/corrupted");}
        return new Verified(binding,trail,total);
    }
}
