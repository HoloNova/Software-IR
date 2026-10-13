package io.kcg.sir.application.internal.projectupdate;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.hex;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.require;

import io.kcg.sir.application.internal.projectbaseline.SecureFileAccess;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Two fixed, physically bound journal slots: the last complete trail survives a partial write of the
 * next one. The mechanism is the same for every transaction family; each family supplies only its own
 * trail type and decoder.
 */
public abstract class JournalSlots<T extends JournalSlots.SlotTrail> {
    /** What the slot mechanism needs to know about a trail, and nothing more. */
    public interface SlotTrail {
        String bindingDigest();

        int fileCount();

        List<String> events();

        byte[] bytes();

        /** Completed or rolled back: nothing left to recover. */
        boolean terminal();
    }

    private final SecureFileAccess files;
    private final String prefix;
    private final String binding;
    private final String keyA;
    private final String keyB;
    private final Consumer<String> checkpoint;
    private final int maxBytes;

    protected JournalSlots(SecureFileAccess files, String prefix, String binding, String keyA, String keyB, Consumer<String> checkpoint, int maxBytes) {
        if (!prefix.matches("project-transactions/[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid project transaction path");
        }

        hex(binding);
        if (keyA == null || keyB == null || keyA.isBlank() || keyB.isBlank() || keyA.equals(keyB)) {
            throw new IllegalArgumentException("invalid slot identity binding");
        }

        this.files = Objects.requireNonNull(files);
        this.prefix = prefix;
        this.binding = binding;
        this.keyA = keyA;
        this.keyB = keyB;
        this.checkpoint = Objects.requireNonNull(checkpoint);
        this.maxBytes = maxBytes;
    }

    protected abstract T decode(byte[] bytes) throws IOException;

    private String name(String leaf) {
        return this.prefix + "/" + leaf;
    }

    private Object slot(String leaf, String expected) throws IOException {
        Object key = this.files.identity(this.name(leaf));
        require(key.toString().equals(expected) && key.equals(this.files.identity(this.name(leaf + ".anchor"))), "journal slot is an external replacement: " + leaf);
        return key;
    }

    public void initialize(T initial) throws IOException {
        require(initial.bindingDigest().equals(this.binding) && initial.events().equals(List.of("P:PREPARED")), "invalid initial journal");
        require(!this.files.exists(this.name("journal")) && !this.files.exists(this.name("journal.new")), "journal already present");
        Object a = this.slot("journal.A", this.keyA);
        this.slot("journal.B", this.keyB);
        this.files.overwriteOwned(this.name("journal.A"), a, initial.bytes(), this.checkpoint);
        this.files.linkOwned(this.name("journal.A"), a, this.files, this.name("journal"));
        this.checkpoint.accept("journal-initialized");
        require(this.read().equals(initial), "initialized journal differs");
    }

    public T read() throws IOException {
        String identity = this.files.identity(this.name("journal")).toString();
        require(identity.equals(this.keyA) || identity.equals(this.keyB), "journal selector has external physical identity");
        T trail = this.decode(this.files.read(this.name("journal"), this.maxBytes));
        require(trail.bindingDigest().equals(this.binding), "journal belongs to another transaction binding");
        if (this.files.exists(this.name("journal.A"))) {
            this.slot("journal.A", this.keyA);
        } else {
            require(trail.terminal(), "active journal anchor A missing");
        }

        if (this.files.exists(this.name("journal.B"))) {
            this.slot("journal.B", this.keyB);
        } else {
            require(trail.terminal(), "active journal anchor B missing");
        }

        if (this.files.exists(this.name("journal.new"))) {
            String pending = this.files.identity(this.name("journal.new")).toString();
            require(pending.equals(this.keyA) || pending.equals(this.keyB), "pending journal is an external replacement");
            T next = this.decode(this.files.read(this.name("journal.new"), this.maxBytes));
            require(next.bindingDigest().equals(this.binding) && next.fileCount() == trail.fileCount(), "pending journal binding mismatch");
            require(next.events().size() == trail.events().size() + 1 && next.events().subList(0, trail.events().size()).equals(trail.events()),
                "pending journal is not the next complete transition");
        }

        return trail;
    }

    public void store(T next) throws IOException {
        T before = this.read();
        require(!this.files.exists(this.name("journal.new")), "pending journal requires explicit recovery");
        require(next.bindingDigest().equals(this.binding) && next.fileCount() == before.fileCount() && next.events().size() == before.events().size() + 1
            && next.events().subList(0, before.events().size()).equals(before.events()), "journal write is not one legal next event");
        Object current = this.files.identity(this.name("journal"));
        boolean a = current.toString().equals(this.keyA);
        String target = a ? "journal.B" : "journal.A";
        Object inactive = this.slot(target, a ? this.keyB : this.keyA);
        this.files.overwriteOwned(this.name(target), inactive, next.bytes(), this.checkpoint);
        this.checkpoint.accept("journal-slot-written");
        require(Arrays.equals(this.files.read(this.name(target), this.maxBytes), next.bytes()), "new journal slot readback differs");
        this.files.linkOwned(this.name(target), inactive, this.files, this.name("journal.new"));
        this.checkpoint.accept("journal-pending");
        this.files.moveOwned(this.name("journal.new"), inactive, this.files, this.name("journal"), current, this.checkpoint);
        this.checkpoint.accept("journal-selected");
        require(this.read().equals(next), "journal transition not published exactly");
    }

    /** Only recovery removes the physically verified unpublished selector; no read-side cleanup. */
    public void discardPending() throws IOException {
        this.read();
        if (this.files.exists(this.name("journal.new"))) {
            Object key = this.files.identity(this.name("journal.new"));
            this.files.deleteOwned(this.name("journal.new"), key);
        }
    }
}
