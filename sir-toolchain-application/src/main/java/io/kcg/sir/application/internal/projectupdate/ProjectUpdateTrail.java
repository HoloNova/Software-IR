package io.kcg.sir.application.internal.projectupdate;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.*;
import io.kcg.sir.application.internal.projectbaseline.ProjectPublicationHistory;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A complete bounded transition trail, replayed on every decode. Never trusts a final status alone. */
public record ProjectUpdateTrail(String bindingDigest,int fileCount,List<String> events) {
    public static final int MAX_FILES=32,MAX_EVENTS=256;
    public static final String MAGIC="KCG-PROJECT-UPDATE-JOURNAL-V1\n";
    public enum Phase { PREPARED,UPDATING,FILES_DONE,COMMITTING,PUBLISHED,CLEANING,COMPLETED,ROLLING_BACK,ROLLED_BACK }
    public enum FileState { STAGED,REPLACING,UPDATED,RESTORING,RESTORED }
    public record State(Phase phase,List<FileState> files) {public State {files=List.copyOf(files);}}
    public ProjectUpdateTrail {
        hex(bindingDigest);events=List.copyOf(events);
        if(fileCount<1 || fileCount>MAX_FILES || events.isEmpty() || events.size()>MAX_EVENTS)throw new IllegalArgumentException("journal count outside budget");
        replay(fileCount,events);
    }
    public static ProjectUpdateTrail start(String digest,int count) {return new ProjectUpdateTrail(digest,count,List.of("P:PREPARED"));}
    public State state() {return replay(fileCount,events);}
    public ProjectUpdateTrail phase(Phase phase) {var list=new ArrayList<>(events);list.add("P:"+phase);return new ProjectUpdateTrail(bindingDigest,fileCount,list);}
    public ProjectUpdateTrail file(int index,FileState state) {var list=new ArrayList<>(events);list.add("F:"+index+":"+state);return new ProjectUpdateTrail(bindingDigest,fileCount,list);}
    private static State replay(int count,List<String> events) {
        if(!events.getFirst().equals("P:PREPARED"))throw new IllegalArgumentException("journal must start PREPARED");
        Phase phase=Phase.PREPARED;var files=new ArrayList<FileState>(Collections.nCopies(count,FileState.STAGED));
        for(int n=1;n<events.size();n++) {
            String event=events.get(n);
            if(event.startsWith("P:")) {
                Phase next=Phase.valueOf(event.substring(2));boolean allowed=switch(phase) {
                    case PREPARED -> next==Phase.UPDATING || next==Phase.ROLLING_BACK;
                    case UPDATING -> next==Phase.FILES_DONE || next==Phase.ROLLING_BACK;
                    case FILES_DONE -> next==Phase.COMMITTING || next==Phase.ROLLING_BACK;
                    case COMMITTING -> next==Phase.PUBLISHED || next==Phase.ROLLING_BACK;
                    case PUBLISHED -> next==Phase.CLEANING;
                    case CLEANING -> next==Phase.COMPLETED;
                    case ROLLING_BACK -> next==Phase.ROLLED_BACK;
                    case COMPLETED,ROLLED_BACK -> false;
                };
                if(!allowed)throw new IllegalArgumentException("illegal project journal transition: "+phase+" -> "+next);
                if(EnumSet.of(Phase.FILES_DONE,Phase.COMMITTING,Phase.PUBLISHED,Phase.CLEANING,Phase.COMPLETED).contains(next) && files.stream().anyMatch(f->f!=FileState.UPDATED))throw new IllegalArgumentException("commit with unfinished files");
                if(next==Phase.ROLLED_BACK && files.stream().anyMatch(f->f!=FileState.RESTORED))throw new IllegalArgumentException("rollback with unfinished files");phase=next;
            }else if(event.startsWith("F:")) {
                var parts=event.split(":",-1);if(parts.length!=3)throw new IllegalArgumentException("invalid file event");int index=Integer.parseInt(parts[1]);
                if(!Integer.toString(index).equals(parts[1]) || index<0 || index>=count)throw new IllegalArgumentException("noncanonical file index");
                var before=files.get(index);var next=FileState.valueOf(parts[2]);boolean allowed;
                if(phase==Phase.UPDATING) {
                    allowed=(before==FileState.STAGED && next==FileState.REPLACING)||(before==FileState.REPLACING && next==FileState.UPDATED);
                    for(int i=0;i<index;i++)allowed&=files.get(i)==FileState.UPDATED;
                    for(int i=index+1;i<count;i++)allowed&=files.get(i)==FileState.STAGED;
                }else if(phase==Phase.ROLLING_BACK) {
                    allowed=(EnumSet.of(FileState.STAGED,FileState.REPLACING,FileState.UPDATED).contains(before) && next==FileState.RESTORING)||(before==FileState.RESTORING && next==FileState.RESTORED);
                    for(int i=index+1;i<count;i++)allowed&=files.get(i)==FileState.RESTORED;
                }else allowed=false;
                if(!allowed)throw new IllegalArgumentException("illegal project file transition: "+phase+" "+index+" "+before+" -> "+next);files.set(index,next);
            }else throw new IllegalArgumentException("unknown journal event");
        }
        return new State(phase,files);
    }
    public byte[] bytes() {var fields=new ArrayList<String>();fields.add(bindingDigest);fields.add(Integer.toString(fileCount));fields.addAll(events);return ProjectPublicationHistory.encode(MAGIC,fields);}
    public static ProjectUpdateTrail decode(byte[] bytes) throws IOException {
        try {
            int offset=MAGIC.getBytes(StandardCharsets.US_ASCII).length;require(bytes.length>=offset+4,"missing journal frame count");int count=ByteBuffer.wrap(bytes).getInt(offset);
            budget(count>=3 && count<=MAX_EVENTS+2,"journal event budget exceeded");var fields=ProjectPublicationHistory.decodeFrames(bytes,MAGIC,count);
            int files=Integer.parseInt(fields.get(1));require(Integer.toString(files).equals(fields.get(1)),"noncanonical file count");
            var result=new ProjectUpdateTrail(fields.get(0),files,fields.subList(2,fields.size()));require(Arrays.equals(bytes,result.bytes()),"noncanonical journal");return result;
        }catch(IllegalArgumentException e) {throw problem("FORMAT-001","invalid project journal: "+e.getMessage());}
    }
}
