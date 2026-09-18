package io.kcg.sir.application.conformance;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Writes run evidence beneath an {@link EvidenceDirectory}.
 *
 * <p>Every path goes through {@link EvidenceDirectory#resolve(String)}, so a
 * relative path cannot escape the evidence root through {@code ..} or an absolute
 * path. Evidence files are created with {@link StandardOpenOption#CREATE_NEW}, so a
 * rerun can never silently destroy a previous run's evidence.
 *
 * <p>When the writer is bound to an {@link EvidenceOwnershipInventory} (the
 * conformance run always is), every file it creates is also registered with that
 * inventory and finalized when its stream closes. That is what makes the final
 * evidence scan meaningful: the scan compares the tree on disk against the
 * inventory's expected set in both directions, so a file this writer did not
 * register would be reported as an unknown object rather than silently accepted.
 *
 * <p>The lifecycle of each file is the inventory's:
 * <ol>
 *   <li>the file is created with {@code CREATE_NEW};</li>
 *   <li>{@link EvidenceOwnershipInventory#recordPending} captures its creation-time
 *       strong identity;</li>
 *   <li>on successful close, {@link EvidenceOwnershipInventory#finalize} records the
 *       final byte count and SHA-256 and re-verifies the identity, so a file swapped
 *       between creation and close is rejected.</li>
 * </ol>
 */
public final class EvidenceWriter {

    private final EvidenceDirectory evidenceRoot;
    private final EvidenceOwnershipInventory inventory;

    private EvidenceWriter(EvidenceDirectory evidenceRoot, EvidenceOwnershipInventory inventory) {
        this.evidenceRoot = evidenceRoot;
        this.inventory = inventory;
    }

    /**
     * Create a writer bound to an already-created evidence directory, without
     * ownership tracking.
     *
     * <p>Only for callers that manage ownership themselves. A conformance run must use
     * {@link #create(EvidenceDirectory, EvidenceOwnershipInventory)} so its evidence is
     * reconcilable by the scanner.
     *
     * @param evidenceRoot the evidence root (must not be {@code null})
     * @return the writer
     */
    public static EvidenceWriter create(EvidenceDirectory evidenceRoot) {
        return new EvidenceWriter(Objects.requireNonNull(evidenceRoot, "evidenceRoot"), null);
    }

    /**
     * Create a writer bound to an evidence directory and its ownership inventory.
     *
     * @param evidenceRoot the evidence root (must not be {@code null})
     * @param inventory    the ownership inventory for the same root
     * @return the writer
     */
    public static EvidenceWriter create(EvidenceDirectory evidenceRoot,
                                        EvidenceOwnershipInventory inventory) {
        return new EvidenceWriter(Objects.requireNonNull(evidenceRoot, "evidenceRoot"),
                Objects.requireNonNull(inventory, "inventory"));
    }

    /**
     * The evidence root this writer is bound to.
     *
     * @return the evidence root path
     */
    public Path root() {
        return evidenceRoot.root();
    }

    /**
     * @return the ownership inventory when tracking is enabled, or {@code null}
     */
    EvidenceOwnershipInventory inventory() {
        return inventory;
    }

    /**
     * Open a fresh stream for one evidence file, creating its parent directories.
     *
     * <p>The caller owns the returned stream and must close it. The writer stays
     * usable across streams, so subprocess stdout and stderr can be captured
     * concurrently. Closing the stream finalizes the file in the ownership inventory;
     * a close that fails leaves the file pending, which makes the final evidence scan
     * fail closed rather than accept an unverified file.
     *
     * @param relativePath path relative to the evidence root
     * @return a writable stream for the newly created file
     * @throws IOException              if a parent is not a real directory, the path
     *                                  escapes the evidence root, or finalization fails
     * @throws java.nio.file.FileAlreadyExistsException if the evidence file exists
     */
    public OutputStream openStream(String relativePath) throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        Path target = evidenceRoot.resolve(relativePath);
        OutputStream raw = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        if (inventory == null) {
            return new BufferedOutputStream(raw);
        }
        inventory.recordPending(target, EvidenceOwnershipInventory.FileUsage.EVIDENCE);
        return new FinalizingStream(new BufferedOutputStream(raw), target, inventory);
    }

    /**
     * Publish the run report: one file written once, tracked as an unsealed report
     * candidate.
     *
     * <p>The report is written and finalized as {@code REPORT_TEMP}. The orchestration
     * then transitions it to unsealed and sealed
     * ({@link #sealReport(String)}) before the final evidence scan, so the report is
     * itself scanned and reconciled like every other evidence file.
     *
     * @param relativePath path relative to the evidence root
     * @param content      the report content (UTF-8)
     * @return the absolute report path
     * @throws IOException if the report cannot be written, finalized, or tracked
     */
    public Path publishReport(String relativePath, String content) throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(content, "content");
        Path target = evidenceRoot.resolve(relativePath);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(target, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.SYNC);
        if (inventory != null) {
            inventory.recordPending(target, EvidenceOwnershipInventory.FileUsage.REPORT_TEMP);
            inventory.finalize(target);
        }
        return target;
    }

    /**
     * Seal the published report so the final evidence scan expects it on disk.
     *
     * <p>Sealing is a two-step transition in the ownership inventory: the report
     * becomes {@code REPORT_UNSEALED} and then {@code REPORT_SEALED}. Only the sealed
     * state is part of the scanner's expected file set, so a run that fails before
     * sealing cannot be reconciled as clean.
     *
     * @param relativePath the path passed to {@link #publishReport}
     * @return the absolute report path
     * @throws IOException if the report was not published or its identity drifted
     */
    public Path sealReport(String relativePath) throws IOException {
        Objects.requireNonNull(relativePath, "relativePath");
        Path target = evidenceRoot.root().resolve(relativePath).toAbsolutePath().normalize();
        if (inventory == null) {
            return target;
        }
        inventory.markUnsealed(target);
        inventory.markSealed(target);
        return target;
    }

    /**
     * A stream that finalizes its file in the ownership inventory when closed.
     *
     * <p>The underlying stream is closed first: only a fully flushed file can be
     * finalized, and a close failure must surface to the caller instead of being
     * reported as a finalized file.
     */
    private static final class FinalizingStream extends OutputStream {

        private final OutputStream delegate;
        private final Path target;
        private final EvidenceOwnershipInventory inventory;
        private boolean closed;

        FinalizingStream(OutputStream delegate, Path target, EvidenceOwnershipInventory inventory) {
            this.delegate = delegate;
            this.target = target;
            this.inventory = inventory;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } catch (IOException e) {
                // The file stays pending; the final scan reports it as unreconciled.
                throw e;
            }
            inventory.finalize(target);
        }
    }
}
