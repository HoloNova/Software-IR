package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import java.nio.file.Path;
import java.util.Objects;

/** Project storage evidence only, deliberately not a ChangeBaseRevision or target catalog. */
public record ProjectBaselineReceipt(String baselineId,Path boundOutputRoot,SourceId entry,String sourceSetSha256Hex,
        String graphCanonicalDigest,String manifestDigest) {
    public ProjectBaselineReceipt {
        Objects.requireNonNull(boundOutputRoot);Objects.requireNonNull(entry);
        if (!boundOutputRoot.isAbsolute() || !boundOutputRoot.equals(boundOutputRoot.normalize())) throw new IllegalArgumentException("unnormalized outputRoot");
        for (String hash : new String[]{baselineId,sourceSetSha256Hex,graphCanonicalDigest,manifestDigest})
            if (hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid digest");
    }
    public int formatVersion() { return 2; }
}
