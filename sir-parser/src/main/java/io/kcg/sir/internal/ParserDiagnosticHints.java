package io.kcg.sir.internal;

import java.util.ArrayDeque;
import java.util.Set;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTree;

/** Read-only explanations at known failure boundaries; never participates in parsing or recovery. */
final class ParserDiagnosticHints {
    private static final Set<String> FIELD_MODIFIERS = Set.of("unique", "secret", "default");
    private static final String CONSTRAINTS = "若这里要追加约束，请用逗号分隔，例如 where min(1), max(7)；约束之间不用 and/or，也不能直接相邻。";
    private static final String PAGE = "分页子句须写 Page 页码, 大小 else 错误名 as 结果名；不能从大小直接接 as。";

    private ParserDiagnosticHints() {}

    static String refine(Recognizer<?, ?> recognizer, Token token, String code, String original) {
        if (!(recognizer instanceof SirParser parser) || token == null
                || !(code.equals("SIR-PARSE-001") || code.equals("SIR-PARSE-003"))) return original;
        if (keywordInNameSlot(parser, token)) {
            return "“" + token.getText() + "”是 SIR 的关键字，不能用作名字；换一个名字，例如 " + token.getText() + "Value";
        }
        if (missingConstraintSeparator(parser, token)) return CONSTRAINTS;
        return missingPageFailure(parser, token) ? PAGE : original;
    }

    private static boolean keywordInNameSlot(SirParser parser, Token token) {
        String literal = parser.getVocabulary().getLiteralName(token.getType());
        if (literal == null || literal.length() <= 2 || !Character.isLetter(literal.charAt(1))) return false;
        var expected = parser.getExpectedTokens();
        if (expected.size() != 1 || !expected.contains(SirParser.IDENT)) return false;
        Token previous = parser.getTokenStream().LT(-1);
        if (previous == null) return false;
        var context = parser.getContext();
        if (previous.getType() == SirParser.AS) {
            return context instanceof SirParser.LoadStepContext || context instanceof SirParser.CreateStepContext
                    || context instanceof SirParser.FindStepContext || context instanceof SirParser.ScalarQueryStepContext
                    || context instanceof SirParser.EachStepContext || context instanceof SirParser.RepeatStepContext
                    || context instanceof SirParser.MonthStepContext;
        }
        int introducer = switch (context) {
            case SirParser.ProjectDocumentContext ignored -> SirParser.SOFTWARE;
            case SirParser.EntityDeclContext ignored -> SirParser.ENTITY;
            case SirParser.InputDeclContext ignored -> SirParser.INPUT;
            case SirParser.ViewDeclContext ignored -> SirParser.VIEW;
            case SirParser.CapabilityDeclContext ignored -> SirParser.CAPABILITY;
            case SirParser.ErrorDeclContext ignored -> SirParser.ERROR;
            case SirParser.EnumDeclContext ignored -> SirParser.ENUM;
            case SirParser.IdentityDeclContext ignored -> SirParser.IDENTITY;
            case SirParser.EntityMemberDeclContext ignored -> SirParser.FIELD;
            case SirParser.FieldDeclContext ignored -> SirParser.FIELD;
            case SirParser.ViewFieldDeclContext ignored -> SirParser.FIELD;
            default -> Token.INVALID_TYPE;
        };
        return previous.getType() == introducer;
    }

    private static boolean missingConstraintSeparator(SirParser parser, Token token) {
        if (token.getType() == SirParser.IDENT) {
            if (FIELD_MODIFIERS.contains(token.getText())) return false;
        } else if ((token.getType() != SirParser.AND && token.getType() != SirParser.OR)
                || parser.getTokenStream().LT(2).getType() != SirParser.IDENT) {
            return false;
        }
        var constraints = constraintsAtBoundary(parser.getContext());
        if (constraints == null || constraints.constraintCall().isEmpty()) return false;
        var last = constraints.constraintCall().getLast();
        return endsBeforeCurrentToken(parser, last) && clean(last);
    }

    private static SirParser.ConstraintListContext constraintsAtBoundary(ParserRuleContext context) {
        if (context instanceof SirParser.ConstraintListContext constraints) {
            return declarationConstraints(constraints.getParent()) == constraints ? constraints : null;
        }
        return declarationConstraints(context);
    }

    private static SirParser.ConstraintListContext declarationConstraints(ParserRuleContext context) {
        return switch (context) {
            case SirParser.EntityMemberDeclContext field -> field.constraintList();
            case SirParser.FieldDeclContext field -> field.constraintList();
            case SirParser.ViewFieldDeclContext field -> field.constraintList();
            case SirParser.SemanticTypeDeclContext type -> type.constraintList();
            default -> null;
        };
    }

    private static boolean missingPageFailure(SirParser parser, Token token) {
        if (!(parser.getContext() instanceof SirParser.FindPageClauseContext page) || token.getType() != SirParser.AS) return false;
        var expected = parser.getExpectedTokens();
        if (expected.size() != 1 || !expected.contains(SirParser.ELSE)) return false;
        // This rule can already hold the current exception. Only its completed expressions must be clean.
        return page.pageExpr != null && page.sizeExpr != null && endsBeforeCurrentToken(parser, page.sizeExpr)
                && clean(page.pageExpr) && clean(page.sizeExpr);
    }

    private static boolean endsBeforeCurrentToken(SirParser parser, ParserRuleContext completed) {
        Token previous = parser.getTokenStream().LT(-1);
        return previous != null && completed.getStop() != null && completed.getStop().getTokenIndex() >= 0
                && completed.getStop().getTokenIndex() == previous.getTokenIndex();
    }

    private static boolean clean(ParserRuleContext completed) {
        var pending = new ArrayDeque<ParseTree>();
        pending.push(completed);
        while (!pending.isEmpty()) {
            var node = pending.pop();
            if (node instanceof ErrorNode || node instanceof ParserRuleContext rule && rule.exception != null) return false;
            for (int i = 0; i < node.getChildCount(); i++) pending.push(node.getChild(i));
        }
        return true;
    }
}
