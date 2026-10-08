package io.kcg.sir.change.internal;

import io.kcg.sir.change.api.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Strict, bounded framing for project-only evidence. No default-encoding replacement of malformed Unicode. */
public final class ProjectChangeDigest {
    private ProjectChangeDigest() {}
    private static final int MAX=16*1024*1024;
    public static byte[] revisionBytes(ProjectChangeRevision r) {
        var out=new ByteArrayOutputStream();text(out,"KCG-PROJECT-CHANGE-REVISION-V1");text(out,Integer.toString(r.formatVersion()));
        frame(out,r.sources().canonicalBytes());text(out,r.graphVersion().name());text(out,r.graphCanonicalDigest());text(out,r.snapshotFormatVersion().name());return out.toByteArray();
    }
    public static String plan(ProjectChangePlan p) {
        var out=new ByteArrayOutputStream();text(out,"KCG-PROJECT-WORKFLOW-PLAN-V1");text(out,Integer.toString(p.formatVersion()));
        frame(out,p.basedOn().canonicalBytes());frame(out,p.candidate().canonicalBytes());text(out,"ModifyCapabilityWorkflow");
        var t=p.operation().target();text(out,t.declarationSymbol().value());text(out,t.declarationNodeId().value());text(out,t.targetNodeId().value());
        text(out,Integer.toString(p.artifactChanges().size()));
        for(var a:p.artifactChanges()) {
            var i=a.artifact();text(out,i.artifactId().value());text(out,i.ownerSymbol().map(s->s.value()).orElse(""));
            switch(i.role()) {
                case io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole role -> { text(out,"DECLARATION");text(out,role.name()); }
                case io.kcg.sir.projectgraph.api.ArtifactRole.ProjectRole role -> { text(out,"PROJECT");text(out,role.name()); }
            }
            text(out,i.qualifiedName());files(out,i.fileChanges());files(out,a.fileChanges());
        }
        files(out,p.fileChanges());return Sha256.hex(out.toByteArray());
    }
    private static void files(ByteArrayOutputStream out,java.util.List<FileChange> files) {
        text(out,Integer.toString(files.size()));for(var f:files) {
            text(out,f.relativePath());text(out,f.artifactId().value());text(out,f.ownerSymbol().map(s->s.value()).orElse(""));
            text(out,Long.toString(f.baseByteCount()));text(out,f.baseSha256Hex());text(out,Long.toString(f.candidateByteCount()));text(out,f.candidateSha256Hex());
        }
    }
    private static void text(ByteArrayOutputStream out,String text) {
        if(text.length()>16384 || !StandardCharsets.UTF_8.newEncoder().canEncode(text))throw new IllegalArgumentException("invalid/oversized evidence text");
        frame(out,text.getBytes(StandardCharsets.UTF_8));
    }
    private static void frame(ByteArrayOutputStream out,byte[] bytes) {
        if(bytes.length>MAX-4-out.size())throw new IllegalArgumentException("project evidence exceeds 16 MiB");
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());out.writeBytes(bytes);
    }
}
