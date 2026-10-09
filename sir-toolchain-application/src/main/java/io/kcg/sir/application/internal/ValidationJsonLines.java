package io.kcg.sir.application.internal;

import io.kcg.sir.application.api.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/** Bounded transport decoder. Never builds a general JSON tree or retains a batch. */
public final class ValidationJsonLines {
    private ValidationJsonLines() {}
    public static SirValidationBatchSummary check(SirValidationApplication app, SirValidationBatchRequest request) throws IOException {
        var input = new BufferedInputStream(request.input(), 8192);
        long lines = 0; boolean internal = false;
        while (true) {
            var bytes = new ByteArrayOutputStream(512); int size = 0, last = -1, ch;
            while ((ch = input.read()) != -1 && ch != '\n') {
                if (size <= SirValidationApplication.MAX_LINE_BYTES) bytes.write(ch);
                size = Math.min(size + 1, SirValidationApplication.MAX_LINE_BYTES + 2); last = ch;
            }
            if (ch == -1 && size == 0) break;
            lines++;
            SirValidationResult result;
            int contentSize = size - (last == '\r' ? 1 : 0);
            if (lines > SirValidationApplication.MAX_BATCH_LINES)
                result = SirValidationApplication.input(null, "KCG-CHECK-LIMIT-003", "batch exceeds 10000 input lines", null);
            else if (contentSize > SirValidationApplication.MAX_LINE_BYTES)
                result = SirValidationApplication.input(null, "KCG-CHECK-LIMIT-004", "JSON line exceeds 8388608 bytes", null);
            else {
                try {
                    var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                    String text = decoder.decode(ByteBuffer.wrap(bytes.toByteArray(), 0, contentSize)).toString();
                    result = new Line(text).check(app);
                } catch (CharacterCodingException e) {
                    result = SirValidationApplication.input(null, "KCG-CHECK-INPUT-006", "JSON line must be valid UTF-8", null);
                } catch (BadJson e) {
                    result = SirValidationApplication.input(null, e.limit ? "KCG-CHECK-LIMIT-005" : "KCG-CHECK-INPUT-007",
                        e.limit ? "JSON nesting exceeds 32 levels" : "JSON line must be one valid object", null);
                }
            }
            request.sink().accept(result); internal |= result.hasInternalFailure();
            if (ch == -1) break;
        }
        return new SirValidationBatchSummary(lines, internal);
    }
    private static final class BadJson extends RuntimeException {
        final boolean limit;
        BadJson(boolean limit) { super(null, null, false, false); this.limit = limit; }
    }
    private record Text(String value, boolean overflow) {}
    private static final class Frame {
        final char type; int state;
        Frame(char type) { this.type = type; }
    }
    private static final class Line {
        final String input; int pos; boolean schemaError, duplicateId, duplicateSir;
        final boolean[] seen = new boolean[3]; final Text[] fields = new Text[3];
        Line(String input) { this.input = input; }
        SirValidationResult check(SirValidationApplication app) {
            expect('{');
            if (!take('}')) {
                do {
                    Text key = string(32); expect(':');
                    int index = key.overflow ? -1 : switch(key.value) { case "id" -> 0; case "sir" -> 1; case "stopAfter" -> 2; default -> -1; };
                    if (index < 0) { schemaError = true; skip(); }
                    else {
                        boolean duplicate = seen[index];
                        if (duplicate) { schemaError = true; if (index == 0) duplicateId = true; if (index == 1) duplicateSir = true; }
                        seen[index] = true;
                        Text value;
                        if (peek() == '"') value = string(index == 0 ? SirValidationApplication.MAX_ID_BYTES : index == 1 ? SirValidationApplication.MAX_SIR_BYTES : 16);
                        else { skip(); value = null; }
                        if (!duplicate) fields[index] = value;
                    }
                } while (take(','));
                expect('}');
            }
            space(); if (pos != input.length()) fail();
            String id = fields[0] == null || fields[0].overflow || duplicateId ? null : fields[0].value;
            if (id != null && (ValidationHashes.utf8Length(id, SirValidationApplication.MAX_ID_BYTES) < 0 ||
                ValidationHashes.utf8Length(id, SirValidationApplication.MAX_ID_BYTES) > SirValidationApplication.MAX_ID_BYTES)) id = null;
            String sir = fields[1] == null ? null : fields[1].value;
            String sha = !duplicateSir && sir != null && !fields[1].overflow && ValidationHashes.utf8Length(sir, SirValidationApplication.MAX_SIR_BYTES) >= 0 &&
                ValidationHashes.utf8Length(sir, SirValidationApplication.MAX_SIR_BYTES) <= SirValidationApplication.MAX_SIR_BYTES ? ValidationHashes.source(sir) : null;
            if (schemaError) return SirValidationApplication.input(id, "KCG-CHECK-INPUT-008", "JSON object has unknown or duplicate fields", sha);
            if (fields[0] != null && fields[0].overflow) return SirValidationApplication.input(null, "KCG-CHECK-LIMIT-001", "id exceeds 1024 UTF-8 bytes", sha);
            if (id == null) return SirValidationApplication.input(null, "KCG-CHECK-INPUT-002", "id must be a Unicode string", sha);
            if (fields[1] != null && fields[1].overflow) return SirValidationApplication.input(id, "KCG-CHECK-LIMIT-002", "sir exceeds 1048576 UTF-8 bytes", null);
            ValidationStopAfter stop = ValidationStopAfter.GENERATION;
            if (seen[2]) {
                stop = null;
                if (fields[2] != null && !fields[2].overflow)
                    for (var s : ValidationStopAfter.values()) if (s.name().equals(fields[2].value)) stop = s;
            }
            return app.check(new SirValidationRequest(id, sir, stop));
        }
        int peek() { space(); return pos < input.length() ? input.charAt(pos) : -1; }
        void space() { while (pos < input.length() && " \t\r\n".indexOf(input.charAt(pos)) >= 0) pos++; }
        boolean take(char c) { if (peek() == c) { pos++; return true; } return false; }
        void expect(char c) { if (!take(c)) fail(); }
        void fail() { throw new BadJson(false); }
        Text string(int cap) {
            expect('"'); var text = new StringBuilder(Math.min(cap, 128)); boolean overflow = false;
            int encodedBytes = 0; boolean pendingHigh = false;
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return new Text(text.toString(), overflow);
                if (c < 0x20) fail();
                if (c == '\\') {
                    if (pos == input.length()) fail();
                    char escaped = input.charAt(pos++);
                    c = switch (escaped) {
                        case '"', '\\', '/' -> escaped;
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case 'u' -> unicode(); default -> { fail(); yield 0; }
                    };
                }
                if (Character.isLowSurrogate(c) && pendingHigh) { encodedBytes += 4; pendingHigh = false; }
                else {
                    if (pendingHigh) encodedBytes += 3;
                    pendingHigh = Character.isHighSurrogate(c);
                    if (!pendingHigh) encodedBytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
                }
                if (encodedBytes > cap) overflow = true;
                if (!overflow) text.append(c);
            }
            fail(); return null;
        }
        char unicode() {
            if (pos + 4 > input.length()) fail(); int n = 0;
            for (int i = 0; i < 4; i++) {
                char c = input.charAt(pos++);
                int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
                if (digit < 0) fail(); n = n * 16 + digit;
            }
            return (char)n;
        }
        /** Validate and skip arbitrary wrong-shape values with at most 32 explicit frames. */
        void skip() {
            var frames = new ArrayDeque<Frame>(); value(frames);
            while (!frames.isEmpty()) {
                Frame f = frames.peek();
                if (f.type == '{') {
                    if (f.state == 0 || f.state == 3) {
                        if (f.state == 0 && take('}')) { frames.pop(); continue; }
                        string(0); expect(':'); f.state = 2; value(frames);
                    } else {
                        if (take('}')) frames.pop(); else { expect(','); f.state = 3; }
                    }
                } else {
                    if (f.state == 0 || f.state == 2) {
                        if (f.state == 0 && take(']')) { frames.pop(); continue; }
                        f.state = 1; value(frames);
                    } else {
                        if (take(']')) frames.pop(); else { expect(','); f.state = 2; }
                    }
                }
            }
        }
        void value(ArrayDeque<Frame> frames) {
            int c = peek();
            if (c == '{' || c == '[') {
                if (frames.size() >= 31) throw new BadJson(true); pos++; frames.push(new Frame((char)c));
            } else if (c == '"') string(0);
            else if (c == 't') literal("true"); else if (c == 'f') literal("false"); else if (c == 'n') literal("null");
            else number();
        }
        void literal(String value) { if (!input.startsWith(value, pos)) fail(); pos += value.length(); }
        boolean digit() { return pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9'; }
        void digits() { if (!digit()) fail(); while (digit()) pos++; }
        void number() {
            if (peek() == '-') pos++;
            if (pos < input.length() && input.charAt(pos) == '0') pos++; else digits();
            if (pos < input.length() && input.charAt(pos) == '.') { pos++; digits(); }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++; if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++; digits();
            }
        }
    }
}
