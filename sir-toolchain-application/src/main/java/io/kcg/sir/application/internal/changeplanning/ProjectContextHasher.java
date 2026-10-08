package io.kcg.sir.application.internal.changeplanning;

import io.kcg.sir.application.api.*;
import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.source.SourceSpan;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Separate project domains. Context uses raw typed targets, so targetKey binding has no hash cycle. */
public final class ProjectContextHasher {
    private static final int MAX=16*1024*1024;
    private ProjectContextHasher() {}
    public static String context(int version,java.nio.file.Path stateRoot,ProjectBaselineReceipt b,
            io.kcg.sir.change.api.ProjectChangeRevision basedOn,io.kcg.sir.change.api.ProjectChangeRevision candidate,java.util.List<ProjectWorkflowTarget> targets) {
        var out=new ByteArrayOutputStream();text(out,"KCG-PROJECT-WORKFLOW-CONTEXT-V1");text(out,Integer.toString(version));
        text(out,stateRoot.toString());text(out,Integer.toString(b.formatVersion()));text(out,b.baselineId());text(out,b.boundOutputRoot().toString());
        text(out,b.entry().value());text(out,b.sourceSetSha256Hex());text(out,b.graphCanonicalDigest());text(out,b.manifestDigest());
        frame(out,basedOn.canonicalBytes());frame(out,candidate.canonicalBytes());text(out,Integer.toString(targets.size()));
        for(var t:targets)targetFields(out,t);return Sha256.hexDigest(out.toByteArray());
    }
    public static String target(String contextId,ProjectWorkflowTarget target) {
        var out=new ByteArrayOutputStream();text(out,"KCG-PROJECT-WORKFLOW-TARGET-V1");text(out,contextId);targetFields(out,target);return Sha256.hexDigest(out.toByteArray());
    }
    public static String plan(String contextId,String planId) {
        var out=new ByteArrayOutputStream();text(out,"KCG-PROJECT-WORKFLOW-APPLICATION-PLAN-V1");text(out,contextId);text(out,planId);return Sha256.hexDigest(out.toByteArray());
    }
    private static void targetFields(ByteArrayOutputStream out,ProjectWorkflowTarget t) {
        var value=t.target();text(out,"BASE_CAPABILITY_WORKFLOW");text(out,value.declarationSymbol().value());text(out,value.declarationNodeId().value());text(out,value.targetNodeId().value());
        text(out,t.displayName());span(out,t.declarationSpan());span(out,t.workflowSpan());
    }
    private static void span(ByteArrayOutputStream out,SourceSpan s) {
        text(out,s.source().value());text(out,Integer.toString(s.start().codePointOffset()));text(out,Integer.toString(s.start().line()));text(out,Integer.toString(s.start().column()));
        text(out,Integer.toString(s.end().codePointOffset()));text(out,Integer.toString(s.end().line()));text(out,Integer.toString(s.end().column()));
    }
    private static void text(ByteArrayOutputStream out,String value) {
        if(value.length()>16384 || !StandardCharsets.UTF_8.newEncoder().canEncode(value))throw new IllegalArgumentException("invalid/oversized project context text");frame(out,value.getBytes(StandardCharsets.UTF_8));
    }
    private static void frame(ByteArrayOutputStream out,byte[] value) {
        if(value.length>MAX-4-out.size())throw new IllegalArgumentException("project context exceeds 16 MiB");out.writeBytes(ByteBuffer.allocate(4).putInt(value.length).array());out.writeBytes(value);
    }
}
