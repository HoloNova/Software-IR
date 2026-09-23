package io.kcg.sir.internal;

import io.kcg.sir.ast.*;
import io.kcg.sir.source.SourceSpan;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

final class SirAstBuilder {
    private final SourceText source;
    private final AstIdFactory ids;

    SirAstBuilder(SourceText source) {
        this.source = source;
        this.ids = new AstIdFactory(source.sourceId());
    }

    AstDocument build(SirParser.DocumentContext context) {
        String path = ids.rootPath();
        TerminalNode versionNode = context.versionLiteral().DOTTED_NUMBER();
        String versionText = versionNode.getText();
        int separator = versionText.indexOf('.');
        SirVersion version = new SirVersion(
                new BigInteger(versionText.substring(0, separator)),
                new BigInteger(versionText.substring(separator + 1)),
                source.span(versionNode.getSymbol()));
        return new AstDocument(ids.id(path), source.span(context), version, software(context.softwareDecl(), path));
    }

    private AstSoftware software(SirParser.SoftwareDeclContext context, String parent) {
        AstName name = name(context.IDENT());
        String path = ids.named(parent, "software", name.text());
        AstMetadata metadata = metadata(context.metadataBlock(), path);
        AstTarget target = target(context.targetBlock(), path);
        List<AstDeclaration> declarations = context.declarationsBlock().declaration().stream()
                .map(value -> declaration(value, path))
                .toList();
        return new AstSoftware(ids.id(path), source.span(context), name, metadata, target, declarations);
    }

    private AstMetadata metadata(SirParser.MetadataBlockContext context, String parent) {
        String path = ids.fixed(parent, "metadata");
        TerminalNode display = context.STRING(0);
        TerminalNode namespace = context.STRING(1);
        return new AstMetadata(
                ids.id(path),
                source.span(context),
                SirStringDecoder.decode(display.getText()),
                source.span(display.getSymbol()),
                SirStringDecoder.decode(namespace.getText()),
                source.span(namespace.getSymbol()));
    }

    private AstTarget target(SirParser.TargetBlockContext context, String parent) {
        String path = ids.fixed(parent, "target");
        List<TerminalNode> values = context.IDENT();
        return new AstTarget(
                ids.id(path),
                source.span(context),
                targetValue(AstLanguage.JAVA, values.get(0)),
                new BigInteger(context.INT().getText()),
                source.span(context.INT().getSymbol()),
                targetValue(AstFramework.SPRING_BOOT, values.get(1)),
                targetValue(AstPersistence.MYBATIS_PLUS, values.get(2)),
                targetValue(AstDatabase.MYSQL, values.get(3)),
                targetValue(AstBuildTool.MAVEN, values.get(4)),
                targetValue(AstInterfaceKind.REST, values.get(5)));
    }

    private <T> AstTargetValue<T> targetValue(T value, TerminalNode token) {
        return new AstTargetValue<>(value, source.span(token.getSymbol()));
    }

    private AstDeclaration declaration(SirParser.DeclarationContext context, String parent) {
        if (context.enumDecl() != null) {
            return enumDeclaration(context.enumDecl(), parent);
        }
        if (context.entityDecl() != null) {
            return entityDeclaration(context.entityDecl(), parent);
        }
        if (context.inputDecl() != null) {
            return inputDeclaration(context.inputDecl(), parent);
        }
        if (context.viewDecl() != null) {
            return viewDeclaration(context.viewDecl(), parent);
        }
        if (context.errorDecl() != null) {
            return errorDeclaration(context.errorDecl(), parent);
        }
        if (context.capabilityDecl() != null) {
            return capabilityDeclaration(context.capabilityDecl(), parent);
        }
        throw new IllegalStateException("unknown declaration parse tree");
    }

    private AstEnumDecl enumDeclaration(SirParser.EnumDeclContext context, String parent) {
        List<TerminalNode> names = context.IDENT();
        AstName name = name(names.getFirst());
        String path = ids.named(parent, "enum", name.text());
        List<AstEnumMember> members = new ArrayList<>();
        for (int index = 1; index < names.size(); index++) {
            AstName memberName = name(names.get(index));
            String memberPath = ids.named(path, "member", memberName.text());
            members.add(new AstEnumMember(ids.id(memberPath), source.span(names.get(index).getSymbol()), memberName));
        }
        return new AstEnumDecl(ids.id(path), source.span(context), name, members);
    }

    private AstEntityDecl entityDeclaration(SirParser.EntityDeclContext context, String parent) {
        AstName name = name(context.IDENT());
        String path = ids.named(parent, "entity", name.text());
        AstIdentity identity = identity(context.identityDecl(), path);
        List<AstField> fields = context.fieldDecl().stream().map(value -> field(value, path)).toList();
        return new AstEntityDecl(ids.id(path), source.span(context), name, identity, fields);
    }

    private AstInputDecl inputDeclaration(SirParser.InputDeclContext context, String parent) {
        AstName name = name(context.IDENT().get(0));
        String path = ids.named(parent, "input", name.text());
        Optional<AstNameRef> patchSourceEntity = context.patchSourceEntity == null
                ? Optional.empty()
                : Optional.of(nameRef(context.patchSourceEntity, path, "patch"));
        List<AstField> fields = context.fieldDecl().stream().map(value -> field(value, path)).toList();
        return new AstInputDecl(ids.id(path), source.span(context), name, patchSourceEntity, fields);
    }

    private AstViewDecl viewDeclaration(SirParser.ViewDeclContext context, String parent) {
        AstName name = name(context.viewName);
        String path = ids.named(parent, "view", name.text());
        AstNameRef sourceEntity = nameRef(context.sourceEntity, path, "from");
        List<AstViewField> fields = context.fieldDecl().stream().map(value -> viewField(value, path)).toList();
        return new AstViewDecl(ids.id(path), source.span(context), name, sourceEntity, fields);
    }

    private AstViewField viewField(SirParser.FieldDeclContext context, String parent) {
        String path = ids.named(parent, "field", context.IDENT().getText());
        List<AstConstraint> constraints = context.constraintList() == null
                ? List.of()
                : context.constraintList().constraintCall().stream()
                        .map(value -> constraint(value, path))
                        .toList();
        return new AstViewField(
                ids.id(path), source.span(context), nameRef(context.IDENT(), path, "name"),
                type(context.typeRef(), path), constraints, context.VERSIONED() != null);
    }

    private AstErrorDecl errorDeclaration(SirParser.ErrorDeclContext context, String parent) {
        AstName name = name(context.IDENT());
        String path = ids.named(parent, "error", name.text());
        Optional<BigInteger> httpStatus = context.INT() == null
                ? Optional.empty()
                : Optional.of(new BigInteger(context.INT().getText()));
        return new AstErrorDecl(ids.id(path), source.span(context), name, httpStatus);
    }

    private AstIdentity identity(SirParser.IdentityDeclContext context, String parent) {
        AstName name = name(context.IDENT());
        String path = ids.named(parent, "identity", name.text());
        AstGenerationStrategy strategy = context.generationStrategy().AUTO() == null
                ? AstGenerationStrategy.UUID
                : AstGenerationStrategy.AUTO;
        return new AstIdentity(
                ids.id(path), source.span(context), name, type(context.typeRef(), path), strategy);
    }

    private AstField field(SirParser.FieldDeclContext context, String parent) {
        AstName name = name(context.IDENT());
        String path = ids.named(parent, "field", name.text());
        List<AstConstraint> constraints = context.constraintList() == null
                ? List.of()
                : context.constraintList().constraintCall().stream()
                        .map(value -> constraint(value, path))
                        .toList();
        return new AstField(
                ids.id(path), source.span(context), name, type(context.typeRef(), path), constraints,
                context.VERSIONED() != null);
    }

    private AstConstraint constraint(SirParser.ConstraintCallContext context, String parent) {
        String path = ids.named(parent, "constraint", context.IDENT().getText());
        List<AstExpression> arguments = context.constantArgumentList() == null
                ? List.of()
                : context.constantArgumentList().constantArgument().stream()
                        .map(value -> constant(value, path))
                        .toList();
        return new AstConstraint(
                ids.id(path), source.span(context), nameRef(context.IDENT(), path, "name"), arguments);
    }

    private AstExpression constant(SirParser.ConstantArgumentContext context, String parent) {
        String path = ids.indexed(parent, "argument");
        if (context.TRUE() != null || context.FALSE() != null) {
            return new AstBooleanLiteral(
                    ids.id(path), source.span(context), context.TRUE() != null);
        }
        if (context.STRING() != null) {
            return new AstStringLiteral(
                    ids.id(path), source.span(context), SirStringDecoder.decode(context.STRING().getText()));
        }
        boolean negative = context.MINUS() != null;
        if (context.decimalLiteral() != null) {
            BigDecimal value = new BigDecimal(context.decimalLiteral().getText());
            if (!negative) {
                return new AstDecimalLiteral(ids.id(path), source.span(context), value);
            }
            String operandPath = ids.indexed(path, "expression");
            AstDecimalLiteral operand = new AstDecimalLiteral(
                    ids.id(operandPath), source.span(context.decimalLiteral()), value);
            return new AstUnaryExpression(
                    ids.id(path), source.span(context), AstUnaryOperator.NEGATE, operand);
        }
        BigInteger value = new BigInteger(context.INT().getText());
        if (!negative) {
            return new AstIntegerLiteral(ids.id(path), source.span(context), value);
        }
        String operandPath = ids.indexed(path, "expression");
        AstIntegerLiteral operand = new AstIntegerLiteral(
                ids.id(operandPath), source.span(context.INT().getSymbol()), value);
        return new AstUnaryExpression(
                ids.id(path), source.span(context), AstUnaryOperator.NEGATE, operand);
    }

    private AstTypeRef type(SirParser.TypeRefContext context, String parent) {
        String path = ids.indexed(parent, "type");
        if (context.OPTIONAL() != null) {
            return new AstOptionalTypeRef(ids.id(path), source.span(context), type(context.typeRef(), path));
        }
        if (context.LIST() != null) {
            return new AstListTypeRef(ids.id(path), source.span(context), type(context.typeRef(), path));
        }
        if (context.PAGE() != null) {
            return new AstPageTypeRef(ids.id(path), source.span(context), type(context.typeRef(), path));
        }
        if (context.REF() != null) {
            return new AstRefTypeRef(
                    ids.id(path), source.span(context), nameRef(context.IDENT(), path, "target"));
        }
        return new AstNamedTypeRef(
                ids.id(path), source.span(context), nameRef(context.IDENT(), path, "name"));
    }

    private AstCapabilityDecl capabilityDeclaration(SirParser.CapabilityDeclContext context, String parent) {
        AstName name = name(context.IDENT());
        String path = ids.named(parent, "capability", name.text());
        Optional<AstActorClause> actor = Optional.ofNullable(context.actorClause())
                .map(value -> new AstActorClause(
                        ids.id(ids.fixed(path, "actor")),
                        source.span(value),
                        type(value.typeRef(), ids.fixed(path, "actor"))));
        Optional<AstInputClause> input = Optional.ofNullable(context.inputClause())
                .map(value -> new AstInputClause(
                        ids.id(ids.fixed(path, "input")),
                        source.span(value),
                        type(value.typeRef(), ids.fixed(path, "input"))));
        String outputPath = ids.fixed(path, "output");
        AstOutputClause output = new AstOutputClause(
                ids.id(outputPath),
                source.span(context.outputClause()),
                type(context.outputClause().typeRef(), outputPath));
        List<AstFailsClause> failures = context.failsClause().stream()
                .map(value -> {
                    String clausePath = ids.indexed(path, "fails");
                    return new AstFailsClause(
                            ids.id(clausePath), source.span(value),
                            nameRef(value.IDENT(), clausePath, "error"));
                })
                .toList();
        List<AstRequiresClause> requirements = context.requiresClause().stream()
                .map(value -> {
                    String clausePath = ids.indexed(path, "requires");
                    return new AstRequiresClause(
                            ids.id(clausePath), source.span(value), requirement(value.requirement()));
                })
                .toList();
        String exposePath = ids.fixed(path, "expose");
        AstExposeClause exposure = new AstExposeClause(
                ids.id(exposePath),
                source.span(context.exposeClause()),
                context.exposeClause().COMMAND() == null ? AstExposureKind.QUERY : AstExposureKind.COMMAND);
        return new AstCapabilityDecl(
                ids.id(path),
                source.span(context),
                name,
                actor,
                input,
                output,
                failures,
                requirements,
                exposure,
                workflow(context.workflowDecl(), path));
    }

    private AstRequirementKind requirement(SirParser.RequirementContext context) {
        if (context.AUTHENTICATED() != null) {
            return AstRequirementKind.AUTHENTICATED;
        }
        return context.ATOMIC() != null ? AstRequirementKind.ATOMIC : AstRequirementKind.READONLY;
    }

    private AstWorkflow workflow(SirParser.WorkflowDeclContext context, String parent) {
        String path = ids.fixed(parent, "workflow");
        List<AstStep> steps = context.workflowStep().stream().map(value -> step(value, path)).toList();
        return new AstWorkflow(ids.id(path), source.span(context), steps);
    }

    private AstStep step(SirParser.WorkflowStepContext context, String parent) {
        String path = ids.indexed(parent, "step");
        if (context.validateStep() != null) {
            var value = context.validateStep();
            return new AstValidateStep(
                    ids.id(path), source.span(value), expression(value.expression(), path),
                    nameRef(value.IDENT(), path, "error"));
        }
        if (context.loadStep() != null) {
            var value = context.loadStep();
            List<TerminalNode> names = value.IDENT();
            return new AstLoadStep(
                    ids.id(path),
                    source.span(value),
                    nameRef(names.get(0), path, "entity"),
                    expression(value.expression(), path),
                    name(names.get(1)),
                    nameRef(names.get(2), path, "error"));
        }
        if (context.findStep() != null) {
            var value = context.findStep();
            AstExpression predicate = expression(value.predicate, path);
            Optional<AstFindOrder> order = Optional.ofNullable(value.findOrderClause())
                    .map(clause -> findOrder(clause, path));
            Optional<AstFindPage> page = Optional.ofNullable(value.findPageClause())
                    .map(clause -> findPage(clause, path));
            return new AstFindStep(
                    ids.id(path),
                    source.span(value),
                    nameRef(value.entity, path, "entity"),
                    predicate,
                    order,
                    page,
                    name(value.result));
        }
        if (context.createStep() != null) {
            var value = context.createStep();
            return new AstCreateStep(
                    ids.id(path),
                    source.span(value),
                    nameRef(value.IDENT(0), path, "entity"),
                    name(value.IDENT(1)),
                    bindings(value.binding(), path));
        }
        if (context.updateStep() != null) {
            var value = context.updateStep();
            return new AstUpdateStep(
                    ids.id(path), source.span(value), nameRef(value.IDENT(), path, "target"),
                    bindings(value.binding(), path));
        }
        if (context.persistStep() != null) {
            var value = context.persistStep();
            return new AstPersistStep(
                    ids.id(path), source.span(value), nameRef(value.IDENT().get(0), path, "target"),
                    value.IDENT().size() > 1
                            ? Optional.of(nameRef(value.IDENT().get(1), path, "failure"))
                            : Optional.empty());
        }
        if (context.returnStep() != null) {
            var value = context.returnStep();
            return new AstReturnStep(ids.id(path), source.span(value), expression(value.expression(), path));
        }
        throw new IllegalStateException("unknown workflow step parse tree");
    }

    private AstFindOrder findOrder(SirParser.FindOrderClauseContext context, String parent) {
        String path = ids.fixed(parent, "order");
        List<AstFindOrderKey> keys = context.orderKey().stream()
                .map(value -> {
                    String keyPath = ids.indexed(path, "key");
                    return new AstFindOrderKey(
                            ids.id(keyPath),
                            source.span(value),
                            nameRef(value.field, keyPath, "field"),
                            value.direction != null && value.direction.getType() == SirParser.DESCENDING);
                })
                .toList();
        return new AstFindOrder(ids.id(path), source.span(context), keys);
    }

    private AstFindPage findPage(SirParser.FindPageClauseContext context, String parent) {
        String path = ids.fixed(parent, "page");
        return new AstFindPage(
                ids.id(path),
                source.span(context),
                expression(context.pageExpr, path),
                expression(context.sizeExpr, path),
                nameRef(context.error, path, "error"));
    }

    private List<AstBinding> bindings(List<SirParser.BindingContext> contexts, String parent) {
        return contexts.stream().map(value -> {
            String path = ids.named(parent, "binding", value.IDENT().getText());
            return new AstBinding(
                    ids.id(path), source.span(value), nameRef(value.IDENT(), path, "field"),
                    expression(value.expression(), path));
        }).toList();
    }

    private AstExpression expression(SirParser.ExpressionContext context, String parent) {
        return orExpression(context.orExpression(), parent);
    }

    private AstExpression orExpression(SirParser.OrExpressionContext context, String parent) {
        List<SirParser.AndExpressionContext> operands = context.andExpression();
        AstExpression result = andExpression(operands.getFirst(), parent);
        for (int index = 1; index < operands.size(); index++) {
            AstExpression right = andExpression(operands.get(index), parent);
            result = binary(result, AstBinaryOperator.OR, right, parent);
        }
        return result;
    }

    private AstExpression andExpression(SirParser.AndExpressionContext context, String parent) {
        List<SirParser.EqualityExpressionContext> operands = context.equalityExpression();
        AstExpression result = equalityExpression(operands.getFirst(), parent);
        for (int index = 1; index < operands.size(); index++) {
            AstExpression right = equalityExpression(operands.get(index), parent);
            result = binary(result, AstBinaryOperator.AND, right, parent);
        }
        return result;
    }

    private AstExpression equalityExpression(SirParser.EqualityExpressionContext context, String parent) {
        List<SirParser.StringMatchExpressionContext> operands = context.stringMatchExpression();
        AstExpression left = stringMatchExpression(operands.getFirst(), parent);
        if (operands.size() == 1) {
            return left;
        }
        AstBinaryOperator operator = context.EQ() == null ? AstBinaryOperator.NE : AstBinaryOperator.EQ;
        return binary(left, operator, stringMatchExpression(operands.get(1), parent), parent);
    }

    private AstExpression stringMatchExpression(SirParser.StringMatchExpressionContext context, String parent) {
        List<SirParser.RelationalExpressionContext> operands = context.relationalExpression();
        AstExpression left = relationalExpression(operands.getFirst(), parent);
        if (operands.size() == 1) {
            return left;
        }
        return binary(left, AstBinaryOperator.CONTAINS_LITERAL, relationalExpression(operands.get(1), parent), parent);
    }

    private AstExpression relationalExpression(SirParser.RelationalExpressionContext context, String parent) {
        List<SirParser.UnaryExpressionContext> operands = context.unaryExpression();
        AstExpression left = unaryExpression(operands.getFirst(), parent);
        if (operands.size() == 1) {
            return left;
        }
        AstBinaryOperator operator;
        if (context.GE() != null) {
            operator = AstBinaryOperator.GE;
        } else if (context.LE() != null) {
            operator = AstBinaryOperator.LE;
        } else if (context.GT() != null) {
            operator = AstBinaryOperator.GT;
        } else {
            operator = AstBinaryOperator.LT;
        }
        return binary(left, operator, unaryExpression(operands.get(1), parent), parent);
    }

    private AstExpression unaryExpression(SirParser.UnaryExpressionContext context, String parent) {
        if (context.postfixExpression() != null) {
            return postfixExpression(context.postfixExpression(), parent);
        }
        String path = ids.indexed(parent, "expression");
        AstUnaryOperator operator = context.NOT() == null ? AstUnaryOperator.NEGATE : AstUnaryOperator.NOT;
        return new AstUnaryExpression(
                ids.id(path), source.span(context), operator, unaryExpression(context.unaryExpression(), path));
    }

    private AstExpression postfixExpression(SirParser.PostfixExpressionContext context, String parent) {
        AstExpression result = primaryExpression(context.primaryExpression(), parent);
        for (TerminalNode member : context.IDENT()) {
            String path = ids.indexed(parent, "expression");
            result = new AstMemberExpression(
                    ids.id(path), combine(result.span(), source.span(member.getSymbol())), result,
                    nameRef(member, path, "member"));
        }
        if (context.PRESENT() != null) {
            String path = ids.indexed(parent, "expression");
            result = new AstPresentExpression(
                    ids.id(path), combine(result.span(), source.span(context.PRESENT().getSymbol())), result);
        }
        return result;
    }

    private AstExpression primaryExpression(SirParser.PrimaryExpressionContext context, String parent) {
        String path = ids.indexed(parent, "expression");
        if (context.TRUE() != null || context.FALSE() != null) {
            return new AstBooleanLiteral(ids.id(path), source.span(context), context.TRUE() != null);
        }
        if (context.decimalLiteral() != null) {
            return new AstDecimalLiteral(
                    ids.id(path), source.span(context), new BigDecimal(context.decimalLiteral().getText()));
        }
        if (context.INT() != null) {
            return new AstIntegerLiteral(ids.id(path), source.span(context), new BigInteger(context.INT().getText()));
        }
        if (context.STRING() != null) {
            return new AstStringLiteral(
                    ids.id(path), source.span(context), SirStringDecoder.decode(context.STRING().getText()));
        }
        if (context.UNIT() != null) {
            return new AstUnitLiteral(ids.id(path), source.span(context));
        }
        TerminalNode nameNode = context.IDENT();
        if (nameNode == null) {
            nameNode = context.INPUT();
        }
        if (nameNode == null) {
            nameNode = context.ACTOR();
        }
        if (nameNode == null) {
            nameNode = context.ITEM();
        }
        if (nameNode != null) {
            return new AstNameExpression(
                    ids.id(path), source.span(context), nameRef(nameNode, path, "name"));
        }
        if (context.NOW() != null) {
            return new AstNowExpression(ids.id(path), source.span(context));
        }
        if (context.anyPredicate() != null) {
            SirParser.AnyPredicateContext predicate = context.anyPredicate();
            return new AstAnyExpression(
                    ids.id(path),
                    source.span(context),
                    nameRef(predicate.entity, path, "entity"),
                    expression(predicate.conditions, path));
        }
        if (context.groupedExpression() != null) {
            return new AstGroupedExpression(
                    ids.id(path),
                    source.span(context),
                    expression(context.groupedExpression().expression(), path));
        }
        throw new IllegalStateException("unknown primary expression parse tree");
    }

    private AstBinaryExpression binary(
            AstExpression left, AstBinaryOperator operator, AstExpression right, String parent) {
        String path = ids.indexed(parent, "expression");
        return new AstBinaryExpression(ids.id(path), combine(left.span(), right.span()), left, operator, right);
    }

    private AstName name(TerminalNode node) {
        return name(node.getSymbol());
    }

    private AstName name(Token token) {
        return new AstName(token.getText(), source.span(token));
    }

    private AstNameRef nameRef(TerminalNode node, String parent, String role) {
        return nameRef(node.getSymbol(), parent, role);
    }

    private AstNameRef nameRef(Token token, String parent, String role) {
        String path = ids.fixed(parent, role);
        return new AstNameRef(ids.id(path), source.span(token), token.getText());
    }

    private SourceSpan combine(Token start, Token end) {
        return new SourceSpan(source.sourceId(), source.span(start).start(), source.span(end).end());
    }

    private SourceSpan combine(SourceSpan start, SourceSpan end) {
        return new SourceSpan(source.sourceId(), start.start(), end.end());
    }
}
