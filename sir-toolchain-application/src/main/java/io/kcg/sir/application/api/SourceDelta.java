package io.kcg.sir.application.api;

import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSetManifest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Which source files differ between two source sets, by exact bytes. This says nothing about what the software does. */
public record SourceDelta(List<Entry> entries) {
    public enum Action { ADDED, REMOVED, CHANGED, UNCHANGED }

    public record Entry(SourceId source, Action action, String beforeSha256, String afterSha256) {}

    public SourceDelta {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    public static SourceDelta between(SourceSetManifest before, SourceSetManifest after) {
        Map<String, String> old = new TreeMap<>();
        Map<String, String> next = new TreeMap<>();
        before.files().forEach(f -> old.put(f.sourceId().value(), f.sha256Hex()));
        after.files().forEach(f -> next.put(f.sourceId().value(), f.sha256Hex()));
        Map<String, Boolean> all = new TreeMap<>();
        old.keySet().forEach(k -> all.put(k, true));
        next.keySet().forEach(k -> all.put(k, true));

        List<Entry> out = new ArrayList<>();
        for (String path : all.keySet()) {
            String was = old.get(path);
            String now = next.get(path);
            Action action = was == null ? Action.ADDED : now == null ? Action.REMOVED : was.equals(now) ? Action.UNCHANGED : Action.CHANGED;
            out.add(new Entry(SourceId.of(path), action, was == null ? "" : was, now == null ? "" : now));
        }

        return new SourceDelta(out);
    }

    public boolean changed() {
        return this.entries.stream().anyMatch(e -> e.action() != Action.UNCHANGED);
    }
}
