package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.kcg.sir.api.ParseResult;
import io.kcg.sir.api.ParseStatus;
import io.kcg.sir.ast.AstEnumDecl;
import io.kcg.sir.ast.AstEnumMember;
import io.kcg.sir.ast.AstName;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ModelInvariantTest {
    @Test
    void sourceIdNormalizesSeparatorsAndRejectsUnsafePaths() {
        assertEquals("tests/example.sir", SourceId.of("tests\\example.sir").value());
        assertThrows(IllegalArgumentException.class, () -> SourceId.of("C:\\project\\example.sir"));
        assertThrows(IllegalArgumentException.class, () -> SourceId.of("../example.sir"));
        assertThrows(IllegalArgumentException.class, () -> SourceId.of("tests//example.sir"));
    }

    @Test
    void sourceSpanRejectsReversedRanges() {
        SourceId source = SourceId.of("tests/example.sir");
        SourcePosition start = new SourcePosition(5, 1, 6);
        SourcePosition end = new SourcePosition(4, 1, 5);
        assertThrows(IllegalArgumentException.class, () -> new SourceSpan(source, start, end));
    }

    @Test
    void astCollectionsAreDefensivelyCopied() {
        SourceSpan span = span();
        var members = new ArrayList<AstEnumMember>();
        members.add(new AstEnumMember(
                new AstNodeId("ast://tests/example#enum/State/member/ACTIVE"),
                span,
                new AstName("ACTIVE", span)));
        AstEnumDecl declaration = new AstEnumDecl(
                new AstNodeId("ast://tests/example#enum/State"),
                span,
                new AstName("State", span),
                members);

        members.clear();
        assertEquals(1, declaration.members().size());
        assertThrows(UnsupportedOperationException.class, declaration.members()::clear);
    }

    @Test
    void parseResultRejectsContradictoryStates() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParseResult(ParseStatus.SUCCESS, Optional.empty(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ParseResult(ParseStatus.FAILED, Optional.empty(), List.of()));
    }

    private static SourceSpan span() {
        SourceId source = SourceId.of("tests/example.sir");
        SourcePosition position = new SourcePosition(0, 1, 1);
        return new SourceSpan(source, position, position);
    }
}
