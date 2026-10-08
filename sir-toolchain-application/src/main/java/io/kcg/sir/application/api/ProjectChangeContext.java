package io.kcg.sir.application.api;

import io.kcg.sir.change.api.ProjectChangeRevision;
import io.kcg.sir.application.internal.changeplanning.ProjectContextHasher;
import java.nio.file.Path;
import java.util.*;

/** Independently versioned, whole-project context. Evidence is re-read before planning, never trusted as a cached AST. */
public record ProjectChangeContext(int formatVersion,Path stateRoot,ProjectBaselineReceipt baseline,
        ProjectChangeRevision basedOn,ProjectChangeRevision candidate,List<ProjectWorkflowTarget> targets,String contextId) {
    public ProjectChangeContext {
        Objects.requireNonNull(stateRoot);Objects.requireNonNull(baseline);Objects.requireNonNull(basedOn);Objects.requireNonNull(candidate);
        targets=List.copyOf(targets);
        if(formatVersion<1 || !stateRoot.isAbsolute() || !stateRoot.equals(stateRoot.normalize()) || targets.size()>8192)throw new IllegalArgumentException("invalid project context size/version/path");
        var seen=new HashSet<String>();String previous=null;
        for(var t:targets) {String id=t.target().declarationSymbol().value();if(!seen.add(id) || (previous!=null && previous.compareTo(id)>=0))throw new IllegalArgumentException("workflow catalog must be unique and sorted");previous=id;}
        String computed=ProjectContextHasher.context(formatVersion,stateRoot,baseline,basedOn,candidate,targets);
        if(contextId!=null && !contextId.equals(computed))throw new IllegalArgumentException("context ID does not match all typed evidence");
        contextId=computed;
    }
    public ProjectChangeContext(int formatVersion,Path stateRoot,ProjectBaselineReceipt baseline,ProjectChangeRevision basedOn,
            ProjectChangeRevision candidate,List<ProjectWorkflowTarget> targets) { this(formatVersion,stateRoot,baseline,basedOn,candidate,targets,null); }
    public String targetKey(ProjectWorkflowTarget target) {
        int index=Collections.binarySearch(targets,target,Comparator.comparing(t->t.target().declarationSymbol().value()));
        if(index<0 || !targets.get(index).equals(target))throw new IllegalArgumentException("workflow outside this context");return ProjectContextHasher.target(contextId,target);
    }
}
