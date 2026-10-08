package io.kcg.sir.change.api;

import io.kcg.sir.projectgraph.api.*;
import io.kcg.sir.source.SourceSetManifest;
import io.kcg.sir.change.internal.ProjectChangeDigest;
import java.util.Objects;

/** A whole-project revision. Never a single-source ChangeBaseRevision. */
public record ProjectChangeRevision(int formatVersion, SourceSetManifest sources, GraphVersion graphVersion,
                                    String graphCanonicalDigest, ProjectGraphCanonicalFormatVersion snapshotFormatVersion) {
    public ProjectChangeRevision {
        Objects.requireNonNull(sources);Objects.requireNonNull(graphVersion);Objects.requireNonNull(snapshotFormatVersion);
        if(formatVersion<1 || graphCanonicalDigest==null || !graphCanonicalDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid project revision evidence");
    }
    public String sourceSetSha256Hex() { return sources.sha256Hex(); }
    public byte[] canonicalBytes() { return ProjectChangeDigest.revisionBytes(this); }
}
