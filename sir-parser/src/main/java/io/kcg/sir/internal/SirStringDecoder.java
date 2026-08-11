package io.kcg.sir.internal;

final class SirStringDecoder {
    private SirStringDecoder() {}

    static String decode(String tokenText) {
        if (tokenText == null || tokenText.length() < 2
                || tokenText.charAt(0) != '"'
                || tokenText.charAt(tokenText.length() - 1) != '"') {
            throw new IllegalArgumentException("not a complete SIR string token");
        }
        StringBuilder decoded = new StringBuilder();
        for (int index = 1; index < tokenText.length() - 1; index++) {
            char current = tokenText.charAt(index);
            if (current != '\\') {
                decoded.append(current);
                continue;
            }
            char escape = tokenText.charAt(++index);
            switch (escape) {
                case '"' -> decoded.append('"');
                case '\\' -> decoded.append('\\');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                default -> throw new IllegalArgumentException("unsupported string escape");
            }
        }
        ensureWellFormedSurrogates(decoded);
        return decoded.toString();
    }

    private static void ensureWellFormedSurrogates(CharSequence value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("isolated high surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("isolated low surrogate");
            }
        }
    }
}
