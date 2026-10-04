package io.kcg.sir.source;

import java.util.*;

/** Exact source bytes read for one compilation. Paths locate sources; neither hashes nor paths are declaration identities. */
public final class SourceSnapshot {
    private final SourceSetManifest manifest;
    private final Map<SourceId, byte[]> bytes;

    public SourceSnapshot(SourceId entry, Map<SourceId, byte[]> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        var copy = new LinkedHashMap<SourceId, byte[]>();
        var entries = new ArrayList<SourceSetManifest.Entry>();
        inputs.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().value())).forEach(e -> {
            byte[] value = e.getValue().clone();
            copy.put(e.getKey(), value);
            entries.add(new SourceSetManifest.Entry(e.getKey(), value.length, SourceSetManifest.sha256(value)));
        });
        this.manifest = new SourceSetManifest(entry, entries);
        this.bytes = Collections.unmodifiableMap(copy);
    }

    public SourceSetManifest manifest() { return manifest; }
    public SourceId entry() { return manifest.entry(); }
    public String sha256Hex() { return manifest.sha256Hex(); }
    public byte[] bytes(SourceId id) {
        byte[] found = bytes.get(id);
        if (found == null) throw new IllegalArgumentException("source outside snapshot: " + id);
        return found.clone();
    }
}
