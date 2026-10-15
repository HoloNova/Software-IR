package io.kcg.sir.application.internal;

import static org.junit.jupiter.api.Assertions.*;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourceSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCandidatePublisherTest {
    @TempDir Path temp;
    private SourceSnapshot source() {
        return new SourceSnapshot(SourceId.of("project.sir"), Map.of(SourceId.of("project.sir"), new byte[] {1, 2},
            SourceId.of("modules/child.sir"), new byte[] {3, 4}));
    }
    @Test void failedPublicationRemovesOnlyItsStagingAndNewParents() throws Exception {
        Path candidate = temp.resolve("new-parent/candidate");
        assertThrows(IOException.class, () -> SourceCandidatePublisher.publish(candidate, source(), stage -> { throw new IOException("injected"); }));
        assertFalse(Files.exists(candidate.getParent()));
        try (var entries = Files.list(temp)) { assertEquals(0, entries.count()); }
    }
    @Test void destinationAppearingBeforePublishIsNotRemovedOrOverwritten() throws Exception {
        Path candidate = temp.resolve("candidate");
        assertThrows(IOException.class, () -> SourceCandidatePublisher.publish(candidate, source(), stage -> {
            Files.createDirectory(candidate); Files.writeString(candidate.resolve("marker"), "belongs to caller");
        }));
        assertEquals("belongs to caller", Files.readString(candidate.resolve("marker")));
        try (var entries = Files.list(temp)) { assertEquals(1, entries.count()); }
    }
    @Test void completeSnapshotPreservesBytesAndRejectsLinkedParents() throws Exception {
        Path candidate = temp.resolve("candidate");
        SourceCandidatePublisher.publish(candidate, source());
        assertArrayEquals(new byte[] {3, 4}, Files.readAllBytes(candidate.resolve("modules/child.sir")));
        Path link = Files.createSymbolicLink(temp.resolve("link"), candidate);
        assertThrows(IOException.class, () -> SourceCandidatePublisher.publish(link.resolve("child"), source()));
        assertFalse(Files.exists(candidate.resolve("child")));
    }
}
