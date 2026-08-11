package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.SirParser;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.source.SourceId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SirParserFactoryTest {

    @Test
    void factoryParserParsesValidResource() throws Exception {
        ParseResult result = SirParser.create().parse(new SirSource(
                SourceId.of("valid/campus-market.sir"), resource("valid/campus-market.sir")));
        assertTrue(result.isSuccess(), () -> "expected success: " + result.diagnostics());
    }

    @Test
    void factoryReturnsIndependentInstances() {
        SirParser first = SirParser.create();
        SirParser second = SirParser.create();
        assertNotSame(first, second);
        assertEquals(first.getClass(), second.getClass());
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = SirParserFactoryTest.class.getResourceAsStream("/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("missing resource: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
