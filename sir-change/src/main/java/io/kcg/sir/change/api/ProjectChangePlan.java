package io.kcg.sir.change.api;

import io.kcg.sir.change.internal.ProjectChangeDigest;
import java.util.*;

/** Read-only UPDATE plan. Cannot be supplied to the legacy apply API. Rehashing is not verification. */
public record ProjectChangePlan(int formatVersion, ProjectChangeRevision basedOn, ProjectChangeRevision candidate,
        ModifyCapabilityWorkflow operation, List<ArtifactChange> artifactChanges, List<FileChange> fileChanges) {
    public ProjectChangePlan {
        Objects.requireNonNull(basedOn);Objects.requireNonNull(candidate);Objects.requireNonNull(operation);
        artifactChanges=List.copyOf(artifactChanges);fileChanges=List.copyOf(fileChanges);
        if(formatVersion<1 || fileChanges.isEmpty() || fileChanges.size()>8192 || artifactChanges.size()>8192) throw new IllegalArgumentException("invalid project plan size/version");
        var seen=new HashSet<String>();String previous=null;
        for(var f:fileChanges) {
            if(!f.bytesChanged() || !seen.add(f.relativePath()) || (previous!=null && previous.compareTo(f.relativePath())>=0)) throw new IllegalArgumentException("UPDATE files must be unique, changed and sorted");
            previous=f.relativePath();
        }
        // Constructors preserve immutable evidence. Complete coverage and ownership are checked by replanning.
    }
    public String sha256Hex() { return ProjectChangeDigest.plan(this); }
}
