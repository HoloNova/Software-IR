package io.kcg.sir.application.internal.version;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.budget;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.hex;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.problem;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.require;

import io.kcg.sir.application.internal.projectupdate.JournalSlots;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * The complete, replayed history of one version transaction. A status alone is never trusted:
 * every decode replays every event and refuses an impossible order.
 *
 * <p>It follows the older project update trail, with two differences that the new transaction needs:
 * there may be zero operations (only the source changes), and an operation is one of five kinds
 * (create directory, create file, update file, delete file, remove directory) instead of only a file
 * replacement. Operations run in the listed order going forward and in reverse going back.
 */
public record VersionTrail(String bindingDigest, int fileCount, List<String> events) implements JournalSlots.SlotTrail {
    public static final int MAX_OPS = 256;
    public static final int MAX_EVENTS = 1400;
    public static final String MAGIC = "KCG-PROJECT-VERSION-JOURNAL-V1\n";

    public enum Phase { PREPARED, UPDATING, FILES_DONE, COMMITTING, PUBLISHED, CLEANING, COMPLETED, ROLLING_BACK, ROLLED_BACK }

    public enum OpState { STAGED, APPLYING, APPLIED, RESTORING, RESTORED }

    public record State(Phase phase, List<OpState> ops) {
        public State {
            ops = List.copyOf(ops);
        }
    }

    public VersionTrail {
        hex(bindingDigest);
        events = List.copyOf(events);
        if (fileCount < 0 || fileCount > MAX_OPS || events.isEmpty() || events.size() > MAX_EVENTS) {
            throw new IllegalArgumentException("journal count outside budget");
        }

        replay(fileCount, events);
    }

    public static VersionTrail start(String digest, int count) {
        return new VersionTrail(digest, count, List.of("P:PREPARED"));
    }

    public State state() {
        return replay(this.fileCount, this.events);
    }

    @Override
    public boolean terminal() {
        Phase phase = this.state().phase();
        return phase == Phase.COMPLETED || phase == Phase.ROLLED_BACK;
    }

    public VersionTrail phase(Phase phase) {
        List<String> list = new ArrayList<>(this.events);
        list.add("P:" + phase);
        return new VersionTrail(this.bindingDigest, this.fileCount, list);
    }

    public VersionTrail op(int index, OpState state) {
        List<String> list = new ArrayList<>(this.events);
        list.add("F:" + index + ":" + state);
        return new VersionTrail(this.bindingDigest, this.fileCount, list);
    }

    private static State replay(int count, List<String> events) {
        if (!events.getFirst().equals("P:PREPARED")) {
            throw new IllegalArgumentException("journal must start PREPARED");
        }

        Phase phase = Phase.PREPARED;
        List<OpState> ops = new ArrayList<>(Collections.nCopies(count, OpState.STAGED));
        for (int n = 1; n < events.size(); n++) {
            String event = events.get(n);
            if (event.startsWith("P:")) {
                Phase next = Phase.valueOf(event.substring(2));
                boolean allowed = switch (phase) {
                    case PREPARED -> next == Phase.UPDATING || next == Phase.ROLLING_BACK;
                    case UPDATING -> next == Phase.FILES_DONE || next == Phase.ROLLING_BACK;
                    case FILES_DONE -> next == Phase.COMMITTING || next == Phase.ROLLING_BACK;
                    case COMMITTING -> next == Phase.PUBLISHED || next == Phase.ROLLING_BACK;
                    case PUBLISHED -> next == Phase.CLEANING;
                    case CLEANING -> next == Phase.COMPLETED;
                    case ROLLING_BACK -> next == Phase.ROLLED_BACK;
                    case COMPLETED, ROLLED_BACK -> false;
                };
                if (!allowed) {
                    throw new IllegalArgumentException("illegal version journal transition: " + phase + " -> " + next);
                }

                if (EnumSet.of(Phase.FILES_DONE, Phase.COMMITTING, Phase.PUBLISHED, Phase.CLEANING, Phase.COMPLETED).contains(next) && ops.stream().anyMatch(o -> o != OpState.APPLIED)) {
                    throw new IllegalArgumentException("commit with unfinished operations");
                }

                if (next == Phase.ROLLED_BACK && ops.stream().anyMatch(o -> o != OpState.RESTORED)) {
                    throw new IllegalArgumentException("rollback with unfinished operations");
                }

                phase = next;
            } else if (event.startsWith("F:")) {
                String[] parts = event.split(":", -1);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("invalid operation event");
                }

                int index = Integer.parseInt(parts[1]);
                if (!Integer.toString(index).equals(parts[1]) || index < 0 || index >= count) {
                    throw new IllegalArgumentException("noncanonical operation index");
                }

                OpState before = ops.get(index);
                OpState next = OpState.valueOf(parts[2]);
                boolean allowed;
                if (phase == Phase.UPDATING) {
                    allowed = (before == OpState.STAGED && next == OpState.APPLYING) || (before == OpState.APPLYING && next == OpState.APPLIED);
                    for (int i = 0; i < index; i++) {
                        allowed &= ops.get(i) == OpState.APPLIED;
                    }

                    for (int i = index + 1; i < count; i++) {
                        allowed &= ops.get(i) == OpState.STAGED;
                    }
                } else if (phase == Phase.ROLLING_BACK) {
                    allowed = (EnumSet.of(OpState.STAGED, OpState.APPLYING, OpState.APPLIED).contains(before) && next == OpState.RESTORING)
                        || (before == OpState.RESTORING && next == OpState.RESTORED);
                    for (int i = index + 1; i < count; i++) {
                        allowed &= ops.get(i) == OpState.RESTORED;
                    }
                } else {
                    allowed = false;
                }

                if (!allowed) {
                    throw new IllegalArgumentException("illegal operation transition: " + phase + " " + index + " " + before + " -> " + next);
                }

                ops.set(index, next);
            } else {
                throw new IllegalArgumentException("unknown journal event");
            }
        }

        return new State(phase, ops);
    }

    @Override
    public byte[] bytes() {
        List<String> fields = new ArrayList<>();
        fields.add(this.bindingDigest);
        fields.add(Integer.toString(this.fileCount));
        fields.addAll(this.events);
        return VersionFrames.encode(MAGIC, fields);
    }

    public static VersionTrail decode(byte[] bytes) throws IOException {
        try {
            int count = VersionFrames.announcedCount(bytes, MAGIC);
            budget(count >= 3 && count <= MAX_EVENTS + 2, "journal event budget exceeded");
            List<String> fields = VersionFrames.decode(bytes, MAGIC, count);
            int files = Integer.parseInt(fields.get(1));
            require(Integer.toString(files).equals(fields.get(1)), "noncanonical operation count");
            VersionTrail result = new VersionTrail(fields.get(0), files, fields.subList(2, fields.size()));
            require(Arrays.equals(bytes, result.bytes()), "noncanonical journal");
            return result;
        } catch (IllegalArgumentException e) {
            throw problem("FORMAT-001", "invalid version journal: " + e.getMessage());
        }
    }
}
