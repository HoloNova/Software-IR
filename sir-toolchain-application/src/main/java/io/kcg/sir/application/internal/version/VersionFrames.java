package io.kcg.sir.application.internal.version;

import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.budget;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.problem;
import static io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec.require;

import io.kcg.sir.application.internal.projectbaseline.ProjectBaselineCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Strict, bounded, length-prefixed text records for the version transaction family.
 *
 * <p>The older project records cap one record at 64 KiB, which a transaction that creates and deletes
 * dozens of files exceeds. These records carry the same framing with their own, larger cap. Decoding
 * rejects anything that is not exactly what encoding would have produced.
 */
final class VersionFrames {
    /** One record, binding included. */
    static final int MAX_RECORD_BYTES = 512 * 1024;

    private VersionFrames() {
    }

    static byte[] encode(String magic, List<String> fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(magic.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(ByteBuffer.allocate(4).putInt(fields.size()).array());
        for (String field : fields) {
            if (field.length() > ProjectBaselineCodec.MAX_TEXT || field.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(field)) {
                throw new IllegalArgumentException("invalid or oversized text field");
            }

            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            if (out.size() + 4L + bytes.length > MAX_RECORD_BYTES) {
                throw new IllegalArgumentException("record budget exceeded");
            }

            out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }

        return out.toByteArray();
    }

    /** The frame count announced right after the magic, so a caller can budget before decoding. */
    static int announcedCount(byte[] bytes, String magic) throws IOException {
        int offset = magic.getBytes(StandardCharsets.US_ASCII).length;
        require(bytes.length >= offset + 4, "missing record frame count");
        return ByteBuffer.wrap(bytes).getInt(offset);
    }

    static List<String> decode(byte[] bytes, String magic, int count) throws IOException {
        budget(bytes.length <= MAX_RECORD_BYTES, "transaction record too large");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            for (byte expected : magic.getBytes(StandardCharsets.US_ASCII)) {
                require(buffer.get() == expected, "unknown transaction record magic");
            }

            require(buffer.getInt() == count, "invalid record field count");
            List<String> fields = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int size = buffer.getInt();
                require(size >= 0 && size <= ProjectBaselineCodec.MAX_TEXT && size <= buffer.remaining(), "invalid record field frame");
                ByteBuffer slice = buffer.slice();
                slice.limit(size);
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString();
                require(text.indexOf('\0') < 0, "NUL in record field");
                fields.add(text);
                buffer.position(buffer.position() + size);
            }

            require(!buffer.hasRemaining(), "trailing record bytes");
            return List.copyOf(fields);
        } catch (BufferUnderflowException | CharacterCodingException e) {
            throw problem("FORMAT-001", "truncated or invalid record: " + e.getMessage());
        }
    }
}
