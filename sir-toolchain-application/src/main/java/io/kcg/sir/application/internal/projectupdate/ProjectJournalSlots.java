package io.kcg.sir.application.internal.projectupdate;

import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import java.io.IOException;
import java.util.function.Consumer;

/** The journal slots of the project UPDATE transaction; the mechanism lives in {@link JournalSlots}. */
public final class ProjectJournalSlots extends JournalSlots<ProjectUpdateTrail> {
    public ProjectJournalSlots(SecureFileAccess files, String prefix, String binding, String keyA, String keyB, Consumer<String> checkpoint) {
        super(files, prefix, binding, keyA, keyB, checkpoint, 65536);
    }

    @Override
    protected ProjectUpdateTrail decode(byte[] bytes) throws IOException {
        return ProjectUpdateTrail.decode(bytes);
    }
}
