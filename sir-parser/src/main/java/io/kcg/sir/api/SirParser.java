package io.kcg.sir.api;

import io.kcg.sir.internal.DefaultSirParser;

public interface SirParser {
    ParseResult parse(SirSource source);

    static SirParser create() {
        return new DefaultSirParser();
    }
}
