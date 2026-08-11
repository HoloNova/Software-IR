package io.kcg.sir.generator.springboot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StringEscapeTest {

    @Test
    void javaStringEscapesQuotesSlashesWhitespaceAndLowControls() {
        String input = "quote\" slash\\ tab\t line\n return\r control" + (char) 1;

        assertEquals(
                "\"quote\\\" slash\\\\ tab\\t line\\n return\\r control\\u0001\"",
                StringEscape.javaString(input));
    }

    @Test
    void xmlEscapesAllReservedTextCharacters() {
        assertEquals(
                "&quot;&amp;&apos;&lt;&gt;",
                StringEscape.xml("\"&'<>"));
    }
}
