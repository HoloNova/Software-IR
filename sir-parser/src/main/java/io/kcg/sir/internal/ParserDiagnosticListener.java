package io.kcg.sir.internal;

import java.util.List;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

final class ParserDiagnosticListener extends BaseErrorListener {
    private final SourceText source;
    private final DiagnosticCollector diagnostics;

    ParserDiagnosticListener(SourceText source, DiagnosticCollector diagnostics) {
        this.source = source;
        this.diagnostics = diagnostics;
    }

    @Override
    public void syntaxError(
            Recognizer<?, ?> recognizer,
            Object offendingSymbol,
            int line,
            int charPositionInLine,
            String message,
            RecognitionException exception) {
        Token token = offendingSymbol instanceof Token value ? value : null;
        String code;
        String localized;
        boolean missingToken = false;
        if (token != null && token.getType() == Token.EOF) {
            code = "SIR-PARSE-004";
            localized = "输入提前结束，SIR 结构尚未闭合";
        } else if (token != null && misplacedEntityMember(recognizer, token) != null) {
            code = message != null && message.startsWith("extraneous input") ? "SIR-PARSE-003" : "SIR-PARSE-001";
            localized = misplacedEntityMember(recognizer, token);
        } else if (message != null && message.startsWith("missing ")) {
            code = "SIR-PARSE-002";
            localized = "缺少必要的语法元素";
            missingToken = true;
        } else if (message != null && message.startsWith("extraneous input")) {
            code = "SIR-PARSE-003";
            localized = "存在多余的语法元素";
        } else if (token != null && isKeywordWhereNameExpected(recognizer, token)) {
            code = "SIR-PARSE-001";
            localized = "“" + token.getText() + "”是 SIR 的关键字，不能用作名字；换一个名字，例如 " + token.getText() + "Value";
        } else {
            code = "SIR-PARSE-001";
            localized = token == null
                    ? "输入不符合 SIR 语法"
                    : "遇到不符合 SIR 语法的内容：" + printable(token.getText());
        }
        localized = ParserDiagnosticHints.refine(recognizer, token, code, localized);
        Token previous = missingToken && recognizer instanceof Parser parser
                ? parser.getTokenStream().LT(-1)
                : null;
        diagnostics.error(code, localized, missingToken
                ? source.insertionSpanAfter(previous)
                : token == null ? source.zeroSpan(null) : source.span(token));
    }

    /**
     * A word the language owns (order, item, from, ...) written where a name was expected. The generic message would
     * only say the content is not valid, which does not tell the author that the word itself is the problem.
     */
    private static boolean isKeywordWhereNameExpected(Recognizer<?, ?> recognizer, Token token) {
        if (!(recognizer instanceof Parser parser)) {
            return false;
        }
        String literal = recognizer.getVocabulary().getLiteralName(token.getType());
        boolean word = literal != null && literal.length() > 2 && Character.isLetter(literal.charAt(1));
        return word && parser.getExpectedTokens().contains(SirParser.IDENT);
    }

    /** The members of an entity in the only order the grammar accepts. */
    private static final List<String> ENTITY_MEMBER_ORDER = List.of("identity", "field", "unique", "credential", "scope");

    /**
     * An entity member written out of the fixed order (a credential clause before a field, say). The parser then only
     * sees an unexpected word, and the generic message does not say that the member is fine and only misplaced. Only
     * an error raised directly in the entity's own rule qualifies; an error inside one member (a missing semicolon)
     * keeps its own message. Returns the message, or null when this is not such an error.
     */
    private static String misplacedEntityMember(Recognizer<?, ?> recognizer, Token token) {
        if (!(recognizer instanceof Parser parser)) {
            return null;
        }
        String member = token.getText();
        int rank = ENTITY_MEMBER_ORDER.indexOf(member);
        if (rank < 0) {
            return null;
        }
        String order = "实体成员的顺序固定为 " + String.join("、", ENTITY_MEMBER_ORDER);
        // The entity rule enters the identity rule before reading anything, so another member written first is
        // refused there, on the identity's first token.
        if (parser.getContext() instanceof SirParser.IdentityDeclContext identity
                && identity.getParent() instanceof SirParser.EntityDeclContext
                && identity.getStart() == token) {
            return rank == 0 ? null : order + "；第一项必须是 identity 主键声明";
        }
        if (!(parser.getContext() instanceof SirParser.EntityDeclContext entity)) {
            return null;
        }
        if (rank == 0) {
            // An identity that failed to parse (another member stood in its place) does not count as the first one.
            return entity.identityDecl() != null && entity.identityDecl().exception == null
                    ? "一个实体只能有一个 identity；" + order
                    : order + "；identity 要写在第一项";
        }
        String later = entity.scopeClause() != null ? "scope"
                : entity.credentialClause() != null ? "credential"
                : !entity.uniqueKeyClause().isEmpty() ? "unique"
                : null;
        if (later == null || ENTITY_MEMBER_ORDER.indexOf(later) <= rank) {
            return null;
        }
        return order + "；这个 " + member + " 要写在 " + later + " 之前";
    }

    private static String printable(String text) {
        if (text == null || text.isBlank()) {
            return "<空白>";
        }
        return text.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }
}
