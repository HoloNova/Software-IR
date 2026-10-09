package io.kcg.sir.application.internal.projectupdate;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.PathGuard;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Immutable transaction evidence, prepared completely before the first output replacement. */
public record ProjectUpdateBinding(String transactionId,Path stateRoot,Path outputRoot,String stateKey,String outputKey,
        String transactionsKey,String directoryKey,String slotAKey,String slotBKey,ProjectPublicationHistory.Edge publication,
        List<Update> updates,List<Owned> owned) {
    public static final String MAGIC="KCG-PROJECT-UPDATE-BINDING-V1\n";
    public enum Kind { FILE,DIRECTORY }
    public enum Lifetime { TRANSACTION,CANDIDATE,STATE,RECEIPT }
    public record Update(String relativePath,String parentKey,String baseKey,String candidateKey,long baseSize,String baseSha,long candidateSize,String candidateSha) {
        public Update {
            safePath(relativePath);key(parentKey);key(baseKey);key(candidateKey);hex(baseSha);hex(candidateSha);
            if(baseKey.equals(candidateKey) || baseSize<0 || candidateSize<0 || baseSize>MAX_GENERATED_FILE || candidateSize>MAX_GENERATED_FILE || (baseSize==candidateSize && baseSha.equals(candidateSha)))throw new IllegalArgumentException("invalid UPDATE evidence");
        }
    }
    public record Owned(String relativePath,Kind kind,String key,Lifetime lifetime,long size,String sha) {
        public Owned {
            safePath(relativePath);ProjectUpdateBinding.key(key);Objects.requireNonNull(kind);Objects.requireNonNull(lifetime);
            if(kind==Kind.FILE) {hex(sha);if(size<0 || size>MAX_GRAPH)throw new IllegalArgumentException("owned file budget exceeded");}
            else if(size!=0 || !sha.isEmpty())throw new IllegalArgumentException("directory cannot carry byte evidence");
        }
    }
    public ProjectUpdateBinding {
        hex(transactionId);Objects.requireNonNull(stateRoot);Objects.requireNonNull(outputRoot);Objects.requireNonNull(publication);
        if(!stateRoot.isAbsolute() || !stateRoot.equals(stateRoot.normalize()) || !outputRoot.isAbsolute() || !outputRoot.equals(outputRoot.normalize()) || !outputRoot.equals(publication.outputRoot()))throw new IllegalArgumentException("invalid transaction roots");
        ProjectPublicationHistory.validText(stateRoot.toString(),MAX_OUTPUT_PATH);ProjectPublicationHistory.validText(outputRoot.toString(),MAX_OUTPUT_PATH);
        for(String k:List.of(stateKey,outputKey,transactionsKey,directoryKey,slotAKey,slotBKey))key(k);if(slotAKey.equals(slotBKey))throw new IllegalArgumentException("journal slots alias");
        updates=List.copyOf(updates);owned=owned.stream().sorted(Comparator.comparing(Owned::relativePath)).toList();
        if(updates.isEmpty() || updates.size()>ProjectUpdateTrail.MAX_FILES || owned.size()>256)throw new IllegalArgumentException("transaction object count outside budget");
        String previous=null;for(var u:updates) {if(previous!=null && previous.compareTo(u.relativePath())>=0)throw new IllegalArgumentException("UPDATE paths must be strictly sorted");previous=u.relativePath();}
        var paths=new HashSet<String>();String prefix="project-transactions/"+transactionId+"/";
        for(var o:owned) {
            if(!paths.add(o.relativePath()))throw new IllegalArgumentException("duplicate owned path");
            if(o.lifetime()==Lifetime.TRANSACTION) {
                boolean allowed=o.kind()==Kind.DIRECTORY && Set.of(prefix+"staging",prefix+"backups").contains(o.relativePath());
                for(int i=0;i<updates.size();i++)allowed|=o.kind()==Kind.FILE && Set.of(prefix+"staging/"+i,prefix+"backups/"+i).contains(o.relativePath());
                if(!allowed)throw new IllegalArgumentException("transaction ownership outside exact staging/backups rows");
            }
            if(o.lifetime()==Lifetime.RECEIPT) {
                boolean allowed=o.kind()==Kind.FILE && o.relativePath().equals(prefix+"candidate.sources");
                allowed|=o.kind()==Kind.DIRECTORY && o.relativePath().equals(prefix+"pins");
                allowed|=o.kind()==Kind.FILE && o.relativePath().matches(java.util.regex.Pattern.quote(prefix+"pins/")+"(?:0|[1-9][0-9]{0,2})");
                if(!allowed)throw new IllegalArgumentException("receipt ownership outside exact replay payload/pins");
            }
            if(o.lifetime()==Lifetime.STATE && !Set.of("project-history","project-history/receipts","project-history/origin").contains(o.relativePath()))throw new IllegalArgumentException("state ownership outside history infrastructure");
            if(o.lifetime()==Lifetime.CANDIDATE) {
                String dir="baselines/"+publication.b1();boolean allowed=o.kind()==Kind.DIRECTORY && o.relativePath().equals(dir);
                allowed|=o.kind()==Kind.FILE && (o.relativePath().equals("project-history/"+publication.id()) || io.kcg.sir.application.internal.projectbaseline.ProjectBaselineStore.MEMBERS.stream().anyMatch(name->o.relativePath().equals(dir+"/"+name)));
                if(!allowed)throw new IllegalArgumentException("candidate ownership outside exact Bundle members/publication relation");
            }
        }
        for(var o:owned)if(o.kind()==Kind.FILE && o.lifetime()!=Lifetime.RECEIPT) {
            if(owned.stream().noneMatch(pin->pin.kind()==Kind.FILE && pin.lifetime()==Lifetime.RECEIPT && pin.relativePath().startsWith(prefix+"pins/") && pin.key().equals(o.key()) && pin.size()==o.size() && pin.sha().equals(o.sha())))throw new IllegalArgumentException("owned file lacks retained physical pin: "+o.relativePath());
        }
        for(int i=0;i<updates.size();i++) {
            var u=updates.get(i);var staged=find(owned,prefix+"staging/"+i);var backup=find(owned,prefix+"backups/"+i);
            if(staged.kind()!=Kind.FILE || backup.kind()!=Kind.FILE || staged.lifetime()!=Lifetime.TRANSACTION || backup.lifetime()!=Lifetime.TRANSACTION
                || !staged.key().equals(u.candidateKey()) || !backup.key().equals(u.baseKey()) || staged.size()!=u.candidateSize() || backup.size()!=u.baseSize()
                || !staged.sha().equals(u.candidateSha()) || !backup.sha().equals(u.baseSha()))throw new IllegalArgumentException("missing/mismatched staging and backup ownership");
        }
    }
    private static Owned find(List<Owned> owned,String path) {return owned.stream().filter(o->o.relativePath().equals(path)).findFirst().orElseThrow(()->new IllegalArgumentException("missing owned path: "+path));}
    public String prefix() {return "project-transactions/"+transactionId;}
    public String digest() {return Sha256.hexDigest(bytes());}
    public byte[] bytes() {
        var fields=new ArrayList<>(List.of(transactionId,stateRoot.toString(),outputRoot.toString(),stateKey,outputKey,transactionsKey,directoryKey,slotAKey,slotBKey,HexFormat.of().formatHex(publication.bytes()),Integer.toString(updates.size()),Integer.toString(owned.size())));
        for(var u:updates)fields.addAll(List.of(u.relativePath(),u.parentKey(),u.baseKey(),u.candidateKey(),Long.toString(u.baseSize()),u.baseSha(),Long.toString(u.candidateSize()),u.candidateSha()));
        for(var o:owned)fields.addAll(List.of(o.relativePath(),o.kind().name(),o.key(),o.lifetime().name(),Long.toString(o.size()),o.sha()));return ProjectPublicationHistory.encode(MAGIC,fields);
    }
    public static ProjectUpdateBinding decode(byte[] bytes) throws IOException {
        try {
            int at=MAGIC.getBytes(StandardCharsets.US_ASCII).length;require(bytes.length>=at+4,"missing binding frame count");int count=ByteBuffer.wrap(bytes).getInt(at);
            budget(count>=19 && count<=12+32*8+256*6,"binding object budget exceeded");var f=ProjectPublicationHistory.decodeFrames(bytes,MAGIC,count);
            int uc=integer(f.get(10)),oc=integer(f.get(11));budget(uc>=1 && uc<=32 && oc>=0 && oc<=256,"binding counts outside budget");require(count==12+uc*8+oc*6,"binding counts do not match fields");
            var updates=new ArrayList<Update>();var owned=new ArrayList<Owned>();int i=12;
            for(int n=0;n<uc;n++) {updates.add(new Update(f.get(i),f.get(i+1),f.get(i+2),f.get(i+3),number(f.get(i+4)),f.get(i+5),number(f.get(i+6)),f.get(i+7)));i+=8;}
            for(int n=0;n<oc;n++) {owned.add(new Owned(f.get(i),Kind.valueOf(f.get(i+1)),f.get(i+2),Lifetime.valueOf(f.get(i+3)),number(f.get(i+4)),f.get(i+5)));i+=6;}
            var result=new ProjectUpdateBinding(f.get(0),Path.of(f.get(1)),Path.of(f.get(2)),f.get(3),f.get(4),f.get(5),f.get(6),f.get(7),f.get(8),ProjectPublicationHistory.Edge.decode(HexFormat.of().parseHex(f.get(9))),updates,owned);
            require(Arrays.equals(bytes,result.bytes()),"noncanonical transaction binding");return result;
        }catch(IllegalArgumentException e) {throw problem("FORMAT-001","invalid transaction binding: "+e.getMessage());}
    }
    private static int integer(String s) {int n=Integer.parseInt(s);if(!Integer.toString(n).equals(s))throw new IllegalArgumentException("noncanonical integer");return n;}
    private static long number(String s) {long n=Long.parseLong(s);if(!Long.toString(n).equals(s))throw new IllegalArgumentException("noncanonical byte count");return n;}
    private static void key(String key) {ProjectPublicationHistory.validText(key,256);if(key.isBlank())throw new IllegalArgumentException("blank physical identity");}
    private static void safePath(String path) {ProjectPublicationHistory.validText(path,MAX_OUTPUT_PATH);if(PathGuard.validateRelativePath(path)!=null)throw new IllegalArgumentException("unsafe owned/UPDATE path");}
}
