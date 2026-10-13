package io.kcg.sir.application.internal.version;

import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import io.kcg.sir.application.internal.projectupdate.JournalSlots;
import java.io.IOException;
import java.util.function.Consumer;

/** The two journal slots of a version transaction; the mechanism is shared with the older transaction. */
final class VersionJournalSlots extends JournalSlots<VersionTrail> {
    static final int MAX_JOURNAL_BYTES = 256 * 1024;

    VersionJournalSlots(SecureFileAccess files, String prefix, String binding, String keyA, String keyB, Consumer<String> checkpoint) {
        super(files, prefix, binding, keyA, keyB, checkpoint, MAX_JOURNAL_BYTES);
    }

    @Override
    protected VersionTrail decode(byte[] bytes) throws IOException {
        return VersionTrail.decode(bytes);
    }
}
