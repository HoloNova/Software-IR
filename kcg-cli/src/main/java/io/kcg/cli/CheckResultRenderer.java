package io.kcg.cli;

import io.kcg.sir.application.api.SirValidationResult;
import io.kcg.sir.source.SourceSpan;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Canonical JSONL rendering only; no compilation or filesystem orchestration. */
final class CheckResultRenderer {
    private CheckResultRenderer() {}
    static void render(OutputStream out, SirValidationResult r) throws IOException {
        write(out, "{\"id\":" + string(r.id()) + ",\"ok\":" + r.ok() + ",\"stage\":" + string(r.stage().name()) + ",\"diagnostics\":[");
        boolean first = true;
        for (var d : r.diagnostics()) {
            if (!first) write(out, ","); first = false;
            write(out, "{\"code\":" + string(d.code()) + ",\"severity\":" + string(d.severity()) + ",\"stage\":" + string(d.stage().name()) + ",\"message\":" + string(d.message()) + ",\"span\":" + span(d.span()) + ",\"related\":[");
            boolean next = false;
            for (var related : d.related()) {
                if (next) write(out, ","); next = true;
                write(out, "{\"message\":" + string(related.message()) + ",\"span\":" + span(related.span()) + "}");
            }
            write(out, "],\"fixes\":["); next = false;
            for (var fix : d.fixes()) {
                if (next) write(out, ","); next = true;
                write(out, "{\"message\":" + string(fix.message()) + ",\"span\":" + span(fix.replacementSpan()) + ",\"replacementText\":" + string(fix.replacementText()) + "}");
            }
            write(out, "],\"sourceSymbol\":" + string(d.sourceSymbol()) + ",\"sourceNodeId\":" + string(d.sourceNodeId()) + ",\"loweredNodeId\":" + string(d.loweredNodeId()) + ",\"relativePath\":" + string(d.relativePath()) + "}");
        }
        write(out, "],\"fileCount\":" + r.fileCount() + ",\"digest\":" + string(r.digest()) + ",\"sourceSha256\":" + string(r.sourceSha256()) + "}\n");
        out.flush();
    }
    private static String span(SourceSpan s) {
        if (s == null) return "null";
        return "{\"sourceId\":" + string(s.source().value()) + ",\"startLine\":" + s.start().line() + ",\"startColumn\":" + s.start().column()
            + ",\"endLine\":" + s.end().line() + ",\"endColumn\":" + s.end().column() + ",\"startCodePointOffset\":" + s.start().codePointOffset()
            + ",\"endCodePointOffset\":" + s.end().codePointOffset() + "}";
    }
    private static String string(String s) {
        String encoded = JsonStringEncoder.encode(s); var safe = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < encoded.length() && Character.isLowSurrogate(encoded.charAt(i + 1))) {
                safe.append(c).append(encoded.charAt(++i));
            } else if (Character.isSurrogate(c)) {
                safe.append("\\u"); String hex = Integer.toHexString(c); safe.append("0".repeat(4 - hex.length())).append(hex);
            } else safe.append(c);
        }
        return safe.toString();
    }
    private static void write(OutputStream out, String text) throws IOException { out.write(text.getBytes(StandardCharsets.UTF_8)); }
}
