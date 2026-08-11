package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.DeclaredKind;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ErrorDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Binding;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.CreateStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.FindStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.LoadStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.PersistStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.ReturnStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Step;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.UpdateStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.ValidateStep;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable;
import io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.BinaryOperator;
import io.kcg.sir.lowering.springboot.model.SpringExpression.MemberExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.NameExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.UnaryExpression;
import io.kcg.sir.lowering.springboot.model.SpringExpression.UnaryOperator;
import io.kcg.sir.lowering.springboot.model.TransportPlan.ResponseRepresentation;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkflowRenderer {
   private static final String LAMBDA_QUERY_WRAPPER = "com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper";

   private WorkflowRenderer() {
   }

   static WorkflowRenderer.RenderedWorkflow render(
      GenerationContext ctx, CapabilityDeclaration capability, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage
   ) {
      List<String> lines = new ArrayList<>();
      LinkedHashMap<SymbolId, WorkflowRenderer.MapperReference> mappers = new LinkedHashMap<>();
      SpringBootWorkflow workflow = capability.workflow();

      for (Step step : workflow.steps()) {
         renderStep(ctx, step, workflow, capability.transportPlan().responseRepresentation(), variableNames, imports, currentPackage, lines, mappers);
      }

      return new WorkflowRenderer.RenderedWorkflow(List.copyOf(lines), List.copyOf(mappers.values()));
   }

   private static void renderStep(
      GenerationContext ctx,
      Step step,
      SpringBootWorkflow workflow,
      ResponseRepresentation responseRepresentation,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      switch (step) {
         case ValidateStep validate:
            renderValidate(ctx, validate, variableNames, imports, currentPackage, lines);
            break;
         case LoadStep load:
            renderLoad(ctx, load, variableNames, imports, currentPackage, lines, mappers);
            break;
         case FindStep find:
            renderFind(ctx, find, variableNames, imports, currentPackage, lines, mappers);
            break;
         case CreateStep create:
            renderCreate(ctx, create, variableNames, imports, currentPackage, lines);
            break;
         case UpdateStep update:
            renderUpdate(ctx, update, workflow, variableNames, imports, currentPackage, lines);
            break;
         case PersistStep persist:
            renderPersist(ctx, persist, workflow, imports, currentPackage, lines, mappers);
            break;
         case ReturnStep ret:
            renderReturn(ctx, ret, responseRepresentation, variableNames, imports, currentPackage, lines);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private static void renderValidate(
      GenerationContext ctx, ValidateStep step, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage, List<String> lines
   ) {
      String condition = ExpressionRenderer.renderExpression(step.condition(), variableNames);
      collectExpressionImports(ctx, step.condition(), variableNames, imports, currentPackage);
      String errorName = errorJavaName(ctx, step.errorSymbol(), imports, currentPackage);
      lines.add("        if (!(" + condition + ")) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
   }

   private static void renderLoad(
      GenerationContext ctx,
      LoadStep step,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      WorkflowRenderer.MapperReference mapper = ensureMapper(ctx, step.entitySymbol(), entity.javaName(), mappers);
      String idExpr = ExpressionRenderer.renderExpression(step.idExpression(), variableNames);
      collectExpressionImports(ctx, step.idExpression(), variableNames, imports, currentPackage);
      String resultName = step.result().targetName();
      String errorName = errorJavaName(ctx, step.errorSymbol(), imports, currentPackage);
      lines.add("        var " + resultName + " = " + mapper.mapperFieldName() + ".selectById(" + idExpr + ");");
      lines.add("        if (" + resultName + " == null) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
   }

   private static void renderFind(
      GenerationContext ctx,
      FindStep step,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      WorkflowRenderer.MapperReference mapper = ensureMapper(ctx, step.entitySymbol(), entity.javaName(), mappers);
      imports.add("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper");
      String wrapperChain = renderPredicateChain(ctx, step.predicate(), step.itemVariable(), entity.javaName(), variableNames, imports, currentPackage);
      String resultName = step.result().targetName();
      lines.add("        var wrapper = " + wrapperChain + ";");
      lines.add("        var " + resultName + " = " + mapper.mapperFieldName() + ".selectList(wrapper);");
   }

   private static void renderCreate(
      GenerationContext ctx, CreateStep step, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage, List<String> lines
   ) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      String resultName = step.result().targetName();
      lines.add("        var " + resultName + " = new " + entity.javaName() + "();");

      for (Binding binding : step.bindings()) {
         String value = ExpressionRenderer.renderExpression(binding.value(), variableNames);
         collectExpressionImports(ctx, binding.value(), variableNames, imports, currentPackage);
         lines.add("        " + resultName + "." + setter(binding.targetProperty()) + "(" + value + ");");
      }
   }

   private static void renderUpdate(
      GenerationContext ctx,
      UpdateStep step,
      SpringBootWorkflow workflow,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines
   ) {
      Variable target = resolveVariable(workflow, step.targetVariable());
      String targetName = target.targetName();

      for (Binding binding : step.bindings()) {
         String value = ExpressionRenderer.renderExpression(binding.value(), variableNames);
         collectExpressionImports(ctx, binding.value(), variableNames, imports, currentPackage);
         lines.add("        " + targetName + "." + setter(binding.targetProperty()) + "(" + value + ");");
      }
   }

   private static void renderPersist(
      GenerationContext ctx,
      PersistStep step,
      SpringBootWorkflow workflow,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      Variable target = resolveVariable(workflow, step.targetVariable());
      LoweredJavaType type = target.type();
      if (type instanceof Declared declared && declared.kind() == DeclaredKind.ENTITY) {
         registerEntityImport(ctx, declared, imports, currentPackage);
         WorkflowRenderer.MapperReference mapper = ensureMapper(ctx, declared.symbolId(), declared.javaName(), mappers);
         String targetName = target.targetName();
         String method = step.action() == PersistenceAction.INSERT ? "insert" : "updateById";
         lines.add("        " + mapper.mapperFieldName() + "." + method + "(" + targetName + ");");
      } else {
         throw new IllegalStateException("PersistStep target must be an entity variable, got type: " + type);
      }
   }

   private static void renderReturn(
      GenerationContext ctx,
      ReturnStep step,
      ResponseRepresentation responseRepresentation,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines
   ) {
      if (responseRepresentation == ResponseRepresentation.VOID) {
         lines.add("        return;");
      } else {
         String value = ExpressionRenderer.renderExpression(step.value(), variableNames);
         collectExpressionImports(ctx, step.value(), variableNames, imports, currentPackage);
         lines.add("        return " + value + ";");
      }
   }

   private static String renderPredicateChain(
      GenerationContext ctx,
      SpringExpression predicate,
      Variable itemVariable,
      String entityJavaName,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage
   ) {
      int[] counter = new int[]{0};
      String rootName = nextWrapperName(counter);
      String body = renderPredicateNode(ctx, predicate, itemVariable, entityJavaName, variableNames, imports, currentPackage, rootName, counter);
      return "new LambdaQueryWrapper<" + entityJavaName + ">()\n                .and(" + rootName + " -> " + body + ")";
   }

   private static String renderPredicateNode(
      GenerationContext ctx,
      SpringExpression predicate,
      Variable itemVariable,
      String entityJavaName,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      String wrapperName,
      int[] counter
   ) {
      switch (predicate) {
         case BinaryExpression binary:
            switch (binary.operator()) {
               case EQ:
               case NE:
               case LT:
               case LE:
               case GT:
               case GE:
                  String method = switch (binary.operator()) {
                     case EQ -> "eq";
                     case NE -> "ne";
                     case LT -> "lt";
                     case LE -> "le";
                     case GT -> "gt";
                     case GE -> "ge";
                     default -> throw new IllegalStateException();
                  };
                  String field = renderFindField(binary.left(), itemVariable, entityJavaName);
                  String value = ExpressionRenderer.renderExpression(binary.right(), variableNames);
                  collectExpressionImports(ctx, binary.right(), variableNames, imports, currentPackage);
                  return wrapperName + "." + method + "(" + field + ", " + value + ")";
               case AND:
               case OR:
                  String leftName = nextWrapperName(counter);
                  String rightName = nextWrapperName(counter);
                  String left = renderPredicateNode(ctx, binary.left(), itemVariable, entityJavaName, variableNames, imports, currentPackage, leftName, counter);
                  String right = renderPredicateNode(
                     ctx, binary.right(), itemVariable, entityJavaName, variableNames, imports, currentPackage, rightName, counter
                  );
                  String connector = binary.operator() == BinaryOperator.AND ? "and" : "or";
                  return wrapperName + ".and(" + leftName + " -> " + left + ")." + connector + "(" + rightName + " -> " + right + ")";
               default:
                  throw new MatchException(null, null);
            }
         case UnaryExpression unary when unary.operator() == UnaryOperator.NOT:
            String nestedName = nextWrapperName(counter);
            String nested = renderPredicateNode(ctx, unary.operand(), itemVariable, entityJavaName, variableNames, imports, currentPackage, nestedName, counter);
            return wrapperName + ".not(" + nestedName + " -> " + nested + ")";
         default:
            throw new IllegalStateException("Unsupported find predicate expression: " + predicate);
      }
   }

   private static String nextWrapperName(int[] counter) {
      return "q" + counter[0]++;
   }

   private static String renderFindField(SpringExpression expr, Variable itemVariable, String entityJavaName) {
      if (expr instanceof MemberExpression member
         && member.receiver() instanceof NameExpression nameExpr
         && nameExpr.resolvedSymbol().equals(itemVariable.symbol())) {
         return entityJavaName + "::" + getterName(member.targetMember());
      } else {
         throw new IllegalStateException("Find predicate left side must be item.<field>, got: " + expr);
      }
   }

   private static WorkflowRenderer.MapperReference ensureMapper(
      GenerationContext ctx, SymbolId entitySymbol, String entityJavaName, Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      WorkflowRenderer.MapperReference existing = mappers.get(entitySymbol);
      if (existing != null) {
         return existing;
      }

      SpringArtifact mapperArtifact = ctx.artifact(entitySymbol, Role.MAPPER);
      String mapperFieldName = lowerFirst(entityJavaName) + "Mapper";
      String mapperTypeName = mapperArtifact.packageName() + "." + mapperArtifact.simpleName();
      WorkflowRenderer.MapperReference ref = new WorkflowRenderer.MapperReference(entitySymbol, entityJavaName, mapperFieldName, mapperTypeName);
      mappers.put(entitySymbol, ref);
      return ref;
   }

   private static String errorJavaName(GenerationContext ctx, SymbolId errorSymbol, ImportSorter imports, String currentPackage) {
      if (ctx.declaration(errorSymbol) instanceof ErrorDeclaration error) {
         SpringArtifact artifact = ctx.artifact(errorSymbol, Role.EXCEPTION);
         String pkg = artifact.packageName();
         if (!pkg.equals(currentPackage)) {
            imports.add(pkg + "." + error.javaName());
         }

         return error.javaName();
      } else {
         throw new IllegalStateException("Symbol is not an error: " + errorSymbol);
      }
   }

   private static void registerEntityImport(GenerationContext ctx, EntityDeclaration entity, ImportSorter imports, String currentPackage) {
      SpringArtifact artifact = ctx.artifact(entity.sourceSymbol(), Role.ENTITY_MODEL);
      String pkg = artifact.packageName();
      if (!pkg.equals(currentPackage)) {
         imports.add(pkg + "." + entity.javaName());
      }
   }

   private static void registerEntityImport(GenerationContext ctx, Declared declared, ImportSorter imports, String currentPackage) {
      String pkg = ctx.declaredPackage(declared);
      if (!pkg.equals(currentPackage)) {
         imports.add(pkg + "." + declared.javaName());
      }
   }

   private static void collectExpressionImports(
      GenerationContext ctx, SpringExpression expression, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage
   ) {
      switch (expression) {
         case MemberExpression member:
            SpringExpression receiver = member.receiver();
            if (receiver instanceof NameExpression nameExpr && nameExpr.type() instanceof Declared declared && declared.kind() == DeclaredKind.ENUM) {
               String pkg = ctx.declaredPackage(declared);
               if (!pkg.equals(currentPackage)) {
                  imports.add(pkg + "." + declared.javaName());
               }
            }

            collectExpressionImports(ctx, receiver, variableNames, imports, currentPackage);
            break;
         case UnaryExpression unary:
            collectExpressionImports(ctx, unary.operand(), variableNames, imports, currentPackage);
            break;
         case BinaryExpression binary:
            collectExpressionImports(ctx, binary.left(), variableNames, imports, currentPackage);
            collectExpressionImports(ctx, binary.right(), variableNames, imports, currentPackage);
            break;
         default:
      }
   }

   private static Variable resolveVariable(SpringBootWorkflow workflow, SymbolId symbol) {
      for (Variable variable : workflow.variables()) {
         if (variable.symbol().equals(symbol)) {
            return variable;
         }
      }

      throw new IllegalStateException("Workflow variable not found: " + symbol);
   }

   private static String lowerFirst(String value) {
      if (value.isEmpty()) {
         return value;
      }

      int first = value.codePointAt(0);
      String head = new String(Character.toChars(Character.toLowerCase(first)));
      return head + value.substring(Character.charCount(first));
   }

   private static String setter(String propertyName) {
      return "set" + capitalise(propertyName);
   }

   private static String getterName(String propertyName) {
      return "get" + capitalise(propertyName);
   }

   private static String capitalise(String value) {
      if (value.isEmpty()) {
         return value;
      }

      int first = value.codePointAt(0);
      String head = new String(Character.toChars(Character.toUpperCase(first)));
      return head + value.substring(Character.charCount(first));
   }

   record MapperReference(SymbolId entitySymbol, String entityJavaName, String mapperFieldName, String mapperTypeName) {
   }

   record RenderedWorkflow(List<String> bodyLines, List<WorkflowRenderer.MapperReference> mappers) {
   }
}
