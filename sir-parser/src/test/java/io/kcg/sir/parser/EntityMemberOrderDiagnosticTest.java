package io.kcg.sir.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.kcg.sir.api.Diagnostic;
import io.kcg.sir.api.SirSource;
import io.kcg.sir.api.SourceUnitParseResult;
import io.kcg.sir.internal.DefaultSirParser;
import io.kcg.sir.source.SourceId;
import org.junit.jupiter.api.Test;

/**
 * Entity members have one fixed order. A member written out of it is named as misplaced, with the order, instead of
 * being reported as extra syntax; the first teacher candidates all failed this way with only the generic message.
 */
class EntityMemberOrderDiagnosticTest {
    private static final String ORDER = "实体成员的顺序固定为 identity、field、unique、credential、scope";
    private final DefaultSirParser parser = new DefaultSirParser();

    @Test
    void aFieldAfterTheCredentialClauseIsNamedAsMisplaced() {
        var result = parse("""
                entity Token persistent {
                  identity id: Token generated uuid;
                  field expiresAt: Moment;
                  credential subject owner expires expiresAt;
                  field owner: Id;
                }""");

        assertEquals(ORDER + "；这个 field 要写在 credential 之前", firstMessage(result), result.diagnostics()::toString);
    }

    @Test
    void aUniqueKeyAfterTheScopeIsNamedAsMisplaced() {
        var result = parse("""
                entity Seat persistent {
                  identity id: Id generated auto;
                  field row: Count;
                  field removed: Flag;
                  scope removed == false;
                  unique (row, removed);
                }""");

        assertEquals(ORDER + "；这个 unique 要写在 scope 之前", firstMessage(result), result.diagnostics()::toString);
    }

    @Test
    void aSecondIdentityIsNamed() {
        var result = parse("""
                entity Thing persistent {
                  identity id: Id generated auto;
                  identity code: Token generated uuid;
                }""");

        assertEquals("一个实体只能有一个 identity；" + ORDER, firstMessage(result), result.diagnostics()::toString);
    }

    @Test
    void aFieldBeforeTheIdentityIsNamed() {
        var result = parse("""
                entity Thing persistent {
                  field name: Text;
                  identity id: Id generated auto;
                }""");

        assertEquals(ORDER + "；第一项必须是 identity 主键声明", firstMessage(result), result.diagnostics()::toString);
    }

    @Test
    void aMistakeInsideOneMemberKeepsItsOwnMessage() {
        var result = parse("""
                entity Thing persistent {
                  identity id: Id generated auto;
                  field name: Text
                  field note: Text;
                }""");

        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.message().contains(ORDER)), result.diagnostics()::toString);
    }

    @Test
    void theGenericMessageNoLongerNamesAnOldVersion() {
        var result = parse("entity Thing persistent { identity id: Id generated auto; field : Text; }");

        assertFalse(result.isSuccess());
        assertTrue(result.diagnostics().stream().noneMatch(d -> d.message().contains("v0.1")), result.diagnostics()::toString);
    }

    private static String firstMessage(SourceUnitParseResult result) {
        assertFalse(result.isSuccess());
        return result.diagnostics().stream().map(Diagnostic::message).findFirst().orElse("");
    }

    private SourceUnitParseResult parse(String declarations) {
        return parser.parseProject(new SirSource(SourceId.of("tests/member-order.sir"), """
                sir 0.3
                software MemberOrder {
                  metadata { displayName "Member Order"; namespace "tests.order"; }
                  target { language java 21; framework spring_boot; persistence mybatis_plus; database mysql; build maven; interface rest; }
                  sources { }
                  imports { }
                  declarations {
                    %s
                  }
                }
                """.formatted(declarations)));
    }
}
