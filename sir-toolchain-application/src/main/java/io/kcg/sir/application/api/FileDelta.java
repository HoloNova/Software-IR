package io.kcg.sir.application.api;

import io.kcg.sir.application.internal.Sha256;
import io.kcg.sir.generator.springboot.api.GeneratedFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * How the generated implementation files must change to match a new software: the whole managed file
 * set is compared, so one plan can create, update and delete files together.
 *
 * <p>It answers "which files", not "what changed in the software": one added field updates several
 * files, a source that only moved updates none. Each file records the declaration that produced it;
 * a file with no owner is shared (project-level) and is compared like any other, so it cannot be missed.
 *
 * <p>When the output directory was observed, every file the plan would touch is also checked against
 * the bytes actually on disk. A managed file that was edited by hand, or a new file that would land on
 * something already there, is reported as a conflict instead of being overwritten silently.
 */
public record FileDelta(List<Entry> entries) {
    public enum Action { CREATE, UPDATE, DELETE, UNCHANGED }

    /** What was found on disk at the file's path. */
    public enum Disk { NOT_CHECKED, ABSENT, MATCHES_BASE, DIFFERS, UNREADABLE }

    public enum Conflict { UNMANAGED_FILE_EXISTS, MANAGED_FILE_MISSING, MANAGED_FILE_MODIFIED, UNREADABLE }

    /** The result of looking at one path in the output directory. */
    public sealed interface Observation {
        record Absent() implements Observation {}

        record Present(String sha256Hex) implements Observation {}

        record Unreadable(String reason) implements Observation {}
    }

    public record Entry(String path, Action action, Optional<String> owner, String beforeSha256, String afterSha256,
            Disk disk, Optional<Conflict> conflict) {
        public boolean shared() {
            return this.owner.isEmpty();
        }
    }

    public FileDelta {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * @param observe how to look at an output path, or {@code null} when the output directory was not provided
     */
    public static FileDelta compare(List<GeneratedFile> before, List<GeneratedFile> after, Function<String, Observation> observe) {
        Map<String, GeneratedFile> old = byPath(before);
        Map<String, GeneratedFile> next = byPath(after);
        Map<String, Boolean> paths = new TreeMap<>();
        old.keySet().forEach(p -> paths.put(p, true));
        next.keySet().forEach(p -> paths.put(p, true));

        List<Entry> out = new ArrayList<>();
        for (String path : paths.keySet()) {
            GeneratedFile was = old.get(path);
            GeneratedFile now = next.get(path);
            String beforeSha = was == null ? "" : Sha256.hexDigest(was.content());
            String afterSha = now == null ? "" : Sha256.hexDigest(now.content());
            Action action = was == null ? Action.CREATE : now == null ? Action.DELETE : beforeSha.equals(afterSha) ? Action.UNCHANGED : Action.UPDATE;
            GeneratedFile source = now != null ? now : was;
            Optional<String> owner = source.symbolId().map(s -> s.value());

            Disk disk = Disk.NOT_CHECKED;
            Optional<Conflict> conflict = Optional.empty();
            if (observe != null) {
                Observation seen = observe.apply(path);
                disk = switch (seen) {
                    case Observation.Absent a -> Disk.ABSENT;
                    case Observation.Unreadable u -> Disk.UNREADABLE;
                    case Observation.Present p -> was != null && p.sha256Hex().equals(beforeSha) ? Disk.MATCHES_BASE : Disk.DIFFERS;
                };
                if (action != Action.UNCHANGED) {
                    conflict = conflictOf(action, disk);
                }
            }

            out.add(new Entry(path, action, owner, beforeSha, afterSha, disk, conflict));
        }

        return new FileDelta(out);
    }

    private static Optional<Conflict> conflictOf(Action action, Disk disk) {
        if (disk == Disk.UNREADABLE) {
            return Optional.of(Conflict.UNREADABLE);
        }

        if (action == Action.CREATE) {
            return disk == Disk.ABSENT ? Optional.empty() : Optional.of(Conflict.UNMANAGED_FILE_EXISTS);
        }

        return switch (disk) {
            case ABSENT -> Optional.of(Conflict.MANAGED_FILE_MISSING);
            case DIFFERS -> Optional.of(Conflict.MANAGED_FILE_MODIFIED);
            default -> Optional.empty();
        };
    }

    private static Map<String, GeneratedFile> byPath(List<GeneratedFile> files) {
        Map<String, GeneratedFile> map = new TreeMap<>();
        for (GeneratedFile file : files) {
            if (map.put(file.relativePath(), file) != null) {
                throw new IllegalArgumentException("duplicate generated path: " + file.relativePath());
            }
        }

        return map;
    }

    /** The entries a plan would actually touch. */
    public List<Entry> changes() {
        return this.entries.stream().filter(e -> e.action() != Action.UNCHANGED).toList();
    }

    public List<Entry> conflicts() {
        return this.entries.stream().filter(e -> e.conflict().isPresent()).toList();
    }

    public long count(Action action) {
        return this.entries.stream().filter(e -> e.action() == action).count();
    }
}
