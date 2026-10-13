package io.kcg.sir.application.execution;

import io.kcg.sir.application.api.LogRef;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

/**
 * One process's output kept as evidence: redacted line by line before it reaches disk, bounded in size, and
 * summarised by a digest of exactly the bytes written. A line longer than {@link #MAX_LINE_BYTES} is dropped and
 * replaced by a note, because cutting it could leave the front half of a secret in the log. Once the size limit is
 * reached the rest is read and discarded, so a chatty process can never block on a full pipe.
 */
final class ProcessLog {
    static final int MAX_LINE_BYTES = 64 * 1024;
    private final Path file;
    private final String relativePath;
    private final LogRedactor redactor;
    private final long maxBytes;
    private final MessageDigest digest;
    private OutputStream out;
    private long written;
    private boolean truncated;
    private String finalDigest;

    ProcessLog(Path file, String relativePath, LogRedactor redactor, long maxBytes) throws IOException {
        this.file = Objects.requireNonNull(file, "file");
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.maxBytes = maxBytes;
        Files.createDirectories(file.getParent());
        this.out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the stream to its end. */
    void pump(InputStream source) {
        try (InputStream in = new BufferedInputStream(source)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean overlong = false;
            int b;
            while ((b = in.read()) >= 0) {
                if (b == '\n') {
                    emit(line, overlong);
                    line.reset();
                    overlong = false;
                } else if (!overlong) {
                    line.write(b);
                    if (line.size() > MAX_LINE_BYTES) {
                        overlong = true;
                        line.reset();
                    }
                }
            }

            if (line.size() > 0 || overlong) {
                emit(line, overlong);
            }
        } catch (IOException ignored) {
            // The stream was closed under us (the process was killed); what was read is already kept.
        }
    }

    private synchronized void emit(ByteArrayOutputStream line, boolean overlong) {
        String text = overlong ? "[line longer than " + MAX_LINE_BYTES + " bytes omitted]"
                : redactor.redact(new String(line.toByteArray(), StandardCharsets.UTF_8));
        byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (truncated || out == null) {
            return;
        }

        if (written + bytes.length > maxBytes) {
            truncated = true;
            bytes = ("[log truncated at " + maxBytes + " bytes]\n").getBytes(StandardCharsets.UTF_8);
        }

        try {
            out.write(bytes);
            digest.update(bytes);
            written += bytes.length;
        } catch (IOException e) {
            truncated = true;
        }
    }

    /** Closes the file and returns the evidence reference; later output is ignored. */
    synchronized LogRef finish(LogRef.Kind kind) throws IOException {
        if (out != null) {
            out.close();
            out = null;
            finalDigest = HexFormat.of().formatHex(digest.digest());
        }

        return new LogRef(kind, relativePath, written, finalDigest);
    }

    Path file() {
        return file;
    }
}
