package io.kcg.cli;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded command transport parser, independent of check's streaming JSONL protocol. */
final class StrictJson {
    static final int MAX_DEPTH = 64;
    static final int MAX_VALUES = 100000;
    static final int MAX_STRING_BYTES = 1024 * 1024;
    private final String input;
    private int offset, values;
    private StrictJson(String input) { this.input = input; }

    static Object parse(String input) {
        var parser = new StrictJson(input);
        Object result = parser.value(1);
        parser.space();
        if (parser.offset != input.length()) throw bad("expected one JSON object, not trailing input");
        return result;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH || ++values > MAX_VALUES) throw bad("JSON exceeds depth/value budget");
        space();
        if (offset == input.length()) throw bad("unexpected end of JSON");
        return switch (input.charAt(offset)) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        expect('{');
        var result = new LinkedHashMap<String, Object>();
        if (!take('}')) {
            do {
                String key = string();
                if (result.containsKey(key)) throw bad("duplicate JSON field: " + key);
                expect(':'); result.put(key, value(depth + 1));
            } while (take(','));
            expect('}');
        }
        return result;
    }

    private List<Object> array(int depth) {
        expect('[');
        var result = new ArrayList<>();
        if (!take(']')) {
            do { result.add(value(depth + 1)); } while (take(','));
            expect(']');
        }
        return result;
    }

    private String string() {
        expect('"');
        var result = new StringBuilder();
        while (offset < input.length()) {
            char c = input.charAt(offset++);
            if (c == '"') {
                String text = result.toString();
                for (int i = 0; i < text.length(); i++) {
                    char unit = text.charAt(i);
                    if (Character.isHighSurrogate(unit)) {
                        if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw bad("unpaired Unicode surrogate");
                    } else if (Character.isLowSurrogate(unit)) throw bad("unpaired Unicode surrogate");
                }
                if (text.getBytes(StandardCharsets.UTF_8).length > MAX_STRING_BYTES) throw bad("JSON string exceeds 1 MiB");
                return text;
            }
            if (c < 32) throw bad("unescaped control character");
            if (c == '\\') {
                if (offset == input.length()) throw bad("unfinished escape");
                c = switch (input.charAt(offset++)) {
                    case '"' -> '"'; case '\\' -> '\\'; case '/' -> '/';
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw bad("unknown JSON escape");
                };
            }
            result.append(c);
            if (result.length() > MAX_STRING_BYTES) throw bad("JSON string exceeds 1 MiB");
        }
        throw bad("unterminated JSON string");
    }

    private char unicode() {
        if (offset + 4 > input.length()) throw bad("unfinished Unicode escape");
        int result = 0;
        for (int i = 0; i < 4; i++) {
            char c = input.charAt(offset++);
            int digit = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) throw bad("invalid Unicode escape");
            result = result * 16 + digit;
        }
        return (char) result;
    }

    private BigDecimal number() {
        int start = offset;
        if (offset < input.length() && input.charAt(offset) == '-') offset++;
        if (offset < input.length() && input.charAt(offset) == '0') offset++;
        else digits();
        if (offset < input.length() && input.charAt(offset) == '.') { offset++; digits(); }
        if (offset < input.length() && "eE".indexOf(input.charAt(offset)) >= 0) {
            offset++;
            if (offset < input.length() && "+-".indexOf(input.charAt(offset)) >= 0) offset++;
            digits();
        }
        if (offset - start > 32) throw bad("JSON number is too long");
        try { return new BigDecimal(input.substring(start, offset)); }
        catch (NumberFormatException e) { throw bad("invalid JSON number"); }
    }

    private void digits() {
        int start = offset;
        while (offset < input.length() && input.charAt(offset) >= '0' && input.charAt(offset) <= '9') offset++;
        if (offset == start) throw bad("expected a JSON value");
    }
    private Object literal(String text, Object result) {
        if (!input.startsWith(text, offset)) throw bad("invalid JSON literal");
        offset += text.length(); return result;
    }
    private void space() { while (offset < input.length() && " \t\r\n".indexOf(input.charAt(offset)) >= 0) offset++; }
    private boolean take(char c) { space(); if (offset < input.length() && input.charAt(offset) == c) { offset++; return true; } return false; }
    private void expect(char c) { if (!take(c)) throw bad("expected '" + c + "'"); }
    static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
