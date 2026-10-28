package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.DeclaredKind;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ViewDeclaration;
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
import io.kcg.sir.lowering.springboot.profile.MySqlIdentifiers;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkflowRenderer {
   private static final String LAMBDA_QUERY_WRAPPER = "com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper";

   private WorkflowRenderer() {
   }

   static WorkflowRenderer.RenderedWorkflow render(
      GenerationContext ctx, CapabilityDeclaration capability, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage
   ) {
      List<String> lines = new ArrayList<>();
      LinkedHashMap<SymbolId, WorkflowRenderer.MapperReference> mappers = new LinkedHashMap<>();
      List<SpringExpression.StringMatch> stringMatches = new ArrayList<>();
      SpringBootWorkflow workflow = capability.workflow();
      String inputName = capability.input().map(Variable::targetName).orElse(null);
      renderRequiredInputs(ctx, capability, inputName, lines);

      for (Step step : workflow.steps()) {
         renderStep(ctx, workflow, capability, step, capability.transportPlan().responseRepresentation(), inputName, variableNames, imports, currentPackage, lines, mappers, stringMatches);
      }

      return new WorkflowRenderer.RenderedWorkflow(List.copyOf(lines), List.copyOf(mappers.values()), List.copyOf(stringMatches));
   }

   /** Missing or null required input fields answer 400 INVALID_REQUEST with NotNull before any step runs. */
   private static void renderRequiredInputs(GenerationContext ctx, CapabilityDeclaration capability,
         String inputName, List<String> lines) {
      if (capability.requiredInputs().isEmpty()) return;
      WriteSupport support = WriteSupport.of(ctx.model());
      lines.add("        {");
      lines.add("            if (" + inputName + " == null) throw " + support.exceptionBaseFqn() + ".missingMember(\"input\");");
      lines.add("            var requiredInputErrors = new java.util.ArrayList<" + support.fieldErrorFqn() + ">();");
      for (var field : capability.requiredInputs()) {
         lines.add("            " + support.validationSupportFqn() + ".notNull(requiredInputErrors, "
               + StringEscape.javaString(field.fieldPath()) + ", " + inputName + "." + getterName(field.propertyName()) + "());");
      }
      lines.add("            if (!requiredInputErrors.isEmpty()) throw " + support.exceptionBaseFqn() + ".invalidRequest(requiredInputErrors);");
      lines.add("        }");
   }

   private static void renderStep(
      GenerationContext ctx,
      SpringBootWorkflow workflow,
      CapabilityDeclaration capability,
      Step step,
      ResponseRepresentation responseRepresentation,
      String inputName,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers,
      List<SpringExpression.StringMatch> stringMatches
   ) {
      switch (step) {
         case ValidateStep validate:
            renderValidate(ctx, capability, validate, variableNames, imports, currentPackage, lines);
            break;
         case LoadStep load:
            renderLoad(ctx, capability, load, variableNames, imports, currentPackage, lines, mappers);
            break;
         case SpringBootWorkflow.LoadActorStep load:
            renderLoadActor(ctx, load, variableNames, imports, currentPackage, lines, mappers);
            break;
         case SpringBootWorkflow.MonthStep month:
            collectExpressionImports(ctx,month.month(),variableNames,imports,currentPackage);
            collectExpressionImports(ctx,month.zone(),variableNames,imports,currentPackage);
            lines.addAll(CalendarStepRenderer.render(month,variableNames,WriteSupport.of(ctx.model()).validationSupportFqn(),
                errorJavaName(ctx,month.errorSymbol(),imports,currentPackage),inputName));
            break;
         case SpringBootWorkflow.GroupedSumStep query:
            renderGroupedSum(ctx, query, inputName, variableNames, imports, currentPackage, lines, mappers, stringMatches);
            break;
         case SpringBootWorkflow.SumStep query:
            renderSum(ctx, query, variableNames, imports, currentPackage, lines, mappers, stringMatches);
            break;
         case SpringBootWorkflow.ScalarQueryStep query:
            renderScalarQuery(ctx, query, variableNames, imports, currentPackage, lines, mappers, stringMatches);
            break;
         case SpringBootWorkflow.EachStep each:
            renderEach(ctx,workflow,capability,each,responseRepresentation,inputName,variableNames,imports,currentPackage,lines,mappers,stringMatches);
            break;
         case SpringBootWorkflow.RepeatStep repeat:
            renderRepeat(ctx,workflow,capability,repeat,responseRepresentation,inputName,variableNames,imports,currentPackage,lines,mappers,stringMatches);
            break;
         case FindStep find:
            renderFind(ctx, find, inputName, variableNames, imports, currentPackage, lines, mappers, stringMatches);
            break;
         case CreateStep create:
            renderCreate(ctx, create, variableNames, imports, currentPackage, lines);
            break;
         case UpdateStep update:
            renderUpdate(ctx, capability, update, workflow, variableNames, imports, currentPackage, lines);
            break;
         case PersistStep persist:
            renderPersist(ctx, capability, persist, workflow, variableNames, imports, currentPackage, lines, mappers);
            break;
         case ReturnStep ret:
            renderReturn(ctx, capability, ret, responseRepresentation, variableNames, imports, currentPackage, lines, mappers);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private static String variableName(Variable value, Map<SymbolId,String> names) {
      return names.getOrDefault(value.symbol(),value.targetName());
   }
   private static String mapperAccess(MapperReference mapper, Map<SymbolId,String> names) {
      return (names instanceof EachNames ? "this." : "")+mapper.mapperFieldName();
   }
   private static final class EachNames extends LinkedHashMap<SymbolId,String> {
      EachNames(Map<SymbolId,String> outer) { super(outer); }
   }

   private static void renderEach(GenerationContext ctx, SpringBootWorkflow workflow, CapabilityDeclaration capability,
         SpringBootWorkflow.EachStep step, ResponseRepresentation responseRepresentation, String inputName,
         Map<SymbolId,String> names, ImportSorter imports, String currentPackage, List<String> lines,
         Map<SymbolId,MapperReference> mappers, List<SpringExpression.StringMatch> stringMatches) {
      EntityDeclaration entity=ctx.entity(step.entitySymbol());registerEntityImport(ctx,entity,imports,currentPackage);
      MapperReference mapper=ensureMapper(ctx,step.entitySymbol(),entity.javaName(),mappers);
      imports.add("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper");
      Map<LoweredNodeId,SpringExpression.StringMatch> matches=new LinkedHashMap<>();
      for(var match:step.stringMatches())matches.put(match.expressionId(),match);
      stringMatches.addAll(step.stringMatches());
      String columns=step.plan().columns().stream().map(c -> StringEscape.javaString("`"+c.replace("`","``")+"`"))
          .collect(java.util.stream.Collectors.joining(", "));
      String seed="new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<"+entity.javaName()+">("+entity.javaName()+".class).select("+columns+").lambda()";
      String query=renderPredicateChain(ctx,step.predicate(),step.itemVariable(),entity,matches,names,imports,currentPackage,seed,true)
          +renderOrderClause(entity,step.orderKeys())+".last("+StringEscape.javaString("LIMIT "+step.plan().fetchLimit())+")";
      lines.add("        {");
      lines.add("            var $eachRows = this."+mapper.mapperFieldName()+"."+step.plan().mapperMethod()+"("+query+");");
      lines.add("            if ($eachRows.size() > "+step.plan().limit()+") throw new "+errorJavaName(ctx,step.errorSymbol(),imports,currentPackage)+"();");
      lines.add("            for (var $eachRow : $eachRows) {");
      var local=new EachNames(names);local.put(step.row().symbol(),"$eachRow");int index=0;
      // Every row or answer a pass declares gets a name no outer variable can have, so a pass never shadows one.
      for(var child:step.body()) {
         if(child instanceof SpringBootWorkflow.LoadStep load)local.put(load.result().symbol(),"$eachLocal"+index++);
         if(child instanceof SpringBootWorkflow.CreateStep create)local.put(create.result().symbol(),"$eachLocal"+index++);
         if(child instanceof SpringBootWorkflow.ScalarQueryStep scalar)local.put(scalar.result().symbol(),"$eachLocal"+index++);
      }
      var body=new ArrayList<String>();
      for(var child:step.body())renderStep(ctx,workflow,capability,child,responseRepresentation,inputName,local,imports,currentPackage,body,mappers,stringMatches);
      for(String line:body)lines.add("        "+line);
      lines.add("            }");lines.add("        }");
   }

   /** A counted loop: the count is read once and checked against the bound before the first pass, as an each source is. */
   private static void renderRepeat(GenerationContext ctx, SpringBootWorkflow workflow, CapabilityDeclaration capability,
         SpringBootWorkflow.RepeatStep step, ResponseRepresentation responseRepresentation, String inputName,
         Map<SymbolId,String> names, ImportSorter imports, String currentPackage, List<String> lines,
         Map<SymbolId,MapperReference> mappers, List<SpringExpression.StringMatch> stringMatches) {
      collectExpressionImports(ctx,step.count(),names,imports,currentPackage);
      lines.add("        {");
      lines.add("            int $repeatCount = "+ExpressionRenderer.renderExpression(step.count(),names)+";");
      lines.add("            if ($repeatCount > "+step.limit()+") throw new "+errorJavaName(ctx,step.errorSymbol(),imports,currentPackage)+"();");
      // A fresh final copy per pass, so a query lambda in the body may read the pass number.
      lines.add("            for (int $repeatPass = 0; $repeatPass < $repeatCount; $repeatPass++) {");
      lines.add("                final int $repeatIndex = $repeatPass;");
      var local=new EachNames(names);local.put(step.index().symbol(),"$repeatIndex");int index=0;
      for(var child:step.body()) {
         if(child instanceof SpringBootWorkflow.LoadStep load)local.put(load.result().symbol(),"$eachLocal"+index++);
         if(child instanceof SpringBootWorkflow.CreateStep create)local.put(create.result().symbol(),"$eachLocal"+index++);
         if(child instanceof SpringBootWorkflow.ScalarQueryStep scalar)local.put(scalar.result().symbol(),"$eachLocal"+index++);
      }
      var body=new ArrayList<String>();
      for(var child:step.body())renderStep(ctx,workflow,capability,child,responseRepresentation,inputName,local,imports,currentPackage,body,mappers,stringMatches);
      for(String line:body)lines.add("        "+line);
      lines.add("            }");lines.add("        }");
   }

   private static void renderValidate(
         GenerationContext ctx,
         CapabilityDeclaration capability,
         ValidateStep step,
         Map<SymbolId, String> variableNames,
         ImportSorter imports,
         String currentPackage,
         List<String> lines
      ) {
      ExpressionRenderer.PresenceScope scope = ctx.patchSpecOf(capability)
         .map(patch -> new ExpressionRenderer.PresenceScope(
            capability.input().orElseThrow().targetName(), patch.changesPropertyName()))
         .orElse(null);
      String condition = ExpressionRenderer.renderExpression(step.condition(), variableNames, scope);
      collectExpressionImports(ctx, step.condition(), variableNames, imports, currentPackage);
      String errorName = errorJavaName(ctx, step.errorSymbol(), imports, currentPackage);
      lines.add("        if (!(" + condition + ")) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
   }

   private static void renderLoad(
      GenerationContext ctx,
      CapabilityDeclaration capability,
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
      String resultName = variableName(step.result(), variableNames);
      String errorName = errorJavaName(ctx, step.errorSymbol(), imports, currentPackage);
      if (step.keyField().isPresent()) {
         renderKeyedLoad(step, entity, mapperAccess(mapper,variableNames), idExpr, resultName, errorName, imports, lines);
         return;
      }

      if (step.explicitLock()) {
         imports.add("com.baomidou.mybatisplus.core.toolkit.Wrappers");
         lines.add("        var " + resultName + " = " + mapperAccess(mapper, variableNames)
            + ".selectOne(Wrappers.<" + entity.javaName() + ">lambdaQuery().eq(" + entity.javaName() + "::"
            + getterName(entity.identity().javaName()) + ", " + idExpr + ").last(\"FOR UPDATE\"));");
      } else {
         String readMethod = step.forUpdate() ? "selectByIdForUpdate" : "selectById";
         lines.add("        var " + resultName + " = " + mapperAccess(mapper, variableNames) + "." + readMethod + "(" + idExpr + ");");
      }
      lines.add("        if (" + resultName + " == null) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
      renderVersionGuard(ctx, capability, step, entity, resultName, imports, currentPackage, lines);
   }

   /** The authenticated actor's row; when it is gone the request is not authenticated any more. */
   private static void renderLoadActor(
      GenerationContext ctx,
      SpringBootWorkflow.LoadActorStep step,
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
      String exception = WriteSupport.of(ctx.model()).exceptionBaseFqn();
      imports.add(exception);
      imports.add("org.springframework.http.HttpStatus");
      String resultName = variableName(step.result(), variableNames);
      lines.add("        var " + resultName + " = " + mapper.mapperFieldName() + ".selectById(" + idExpr + ");");
      lines.add("        if (" + resultName + " == null) {");
      lines.add("            throw new " + exception.substring(exception.lastIndexOf('.') + 1) + "(\"Unauthenticated\", HttpStatus.UNAUTHORIZED);");
      lines.add("        }");
   }

   /**
   * Loads the one row whose unique field holds the value.
   *
   * <p>Zero rows is the load's declared failure. More than one means the data breaks the uniqueness
   * the author declared, which the request cannot repair, so it fails as an internal error instead of
   * handing back whichever row came first; the query stops at two rows because two are enough to know,
   * and the message names the entity and field but never the value that was looked up.
   */
   private static void renderKeyedLoad(
      LoadStep step,
      EntityDeclaration entity,
      String mapperAccess,
      String valueExpr,
      String resultName,
      String errorName,
      ImportSorter imports,
      List<String> lines
   ) {
      SymbolId keySymbol = step.keyField().orElseThrow();
      SpringBootDeclaration.Property key = entity.fields().stream()
         .filter(property -> property.sourceSymbol().equals(keySymbol))
         .findFirst()
         .orElseThrow(() -> new IllegalStateException("load key is not a stored field of " + entity.javaName() + ": " + keySymbol));
      imports.add("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper");
      String matches = resultName + "Matches";
      lines.add("        var " + matches + " = " + mapperAccess + ".selectList(new LambdaQueryWrapper<" + entity.javaName() + ">()");
      lines.add("                .eq(" + entity.javaName() + "::" + getterName(key.javaName()) + ", " + valueExpr + ")");
      lines.add("                .last(\"LIMIT 2\"));");
      lines.add("        if (" + matches + ".isEmpty()) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
      lines.add("        if (" + matches + ".size() > 1) {");
      lines.add("            throw new IllegalStateException(\"more than one " + entity.javaName() + " row has the same " + key.javaName() + "\");");
      lines.add("        }");
      lines.add("        var " + resultName + " = " + matches + ".get(0);");
   }

   /**
   * Fails fast when the request's expected version is already stale.
   *
   * <p>The conditional update would catch this on its own, but answering before any merge or
   * validation keeps a stale request from being told about the candidate it never got to change.
   */
   private static void renderVersionGuard(
      GenerationContext ctx,
      CapabilityDeclaration capability,
      LoadStep step,
      EntityDeclaration entity,
      String loadedName,
      ImportSorter imports,
      String currentPackage,
      List<String> lines
   ) {
      if (!step.forUpdate() || entity.version().isEmpty()) {
         return;
      }

      SpringBootDeclaration.PatchSpec patch = ctx.patchSpecOf(capability).orElse(null);
      SpringBootWorkflow.ConditionalUpdate conditional = conditionalUpdateOf(ctx, step.entitySymbol());
      if (patch == null || conditional == null) {
         return;
      }

      String inputName = capability.input().orElseThrow().targetName();
      String versionGetter = getterName(entity.version().orElseThrow().javaName());
      String expected = inputName + "." + getterName(patch.expectedVersionPropertyName()) + "()";
      String errorName = conditional.failure().isPresent()
         ? errorJavaName(ctx, conditional.failure().orElseThrow(), imports, currentPackage)
         : null;
      if (errorName == null) {
         return;
      }

      lines.add("        if (!java.util.Objects.equals(" + loadedName + "." + versionGetter + "(), " + expected + ")) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
   }

   private static void renderGroupedSum(GenerationContext ctx, SpringBootWorkflow.GroupedSumStep step, String input,
       Map<SymbolId, String> variables, ImportSorter imports, String pkg, List<String> lines,
       Map<SymbolId, WorkflowRenderer.MapperReference> mappers, List<SpringExpression.StringMatch> matches) {
      var entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, pkg);
      var mapper = ensureMapper(ctx, step.entitySymbol(), entity.javaName(), mappers);
      String helper = ctx.model().basePackage() + ".persistence.ExactAggregate";
      matches.addAll(step.stringMatches());
      Map<LoweredNodeId, SpringExpression.StringMatch> indexed = new LinkedHashMap<>();
      for (var match : step.stringMatches()) indexed.put(match.expressionId(), match);
      String predicate = renderPredicateChain(ctx, step.predicate(), step.itemVariable(), entity, indexed,
          variables, imports, pkg, "$filter", true);
      var key = ((LoweredJavaType.SumGroupValue)((LoweredJavaType.PageValue)step.result().type()).elementType()).keyType();
      String decoder = key.identityStorageType().kind() == LoweredJavaType.ScalarKind.UUID ? "uuidKey" : "longKey";
      var page = step.page();
      lines.add("        var " + variableName(step.result(), variables) + " = " + helper + ".grouped(" + helper + ".query("
          + entity.javaName() + ".class, " + StringEscape.javaString(step.plan().countProjection()) + "), $filter -> " + predicate
          + ", " + input + "." + getterName(page.pagePropertyName()) + "(), " + input + "." + getterName(page.sizePropertyName()) + "(), "
          + page.defaultSize() + ", " + page.maxSize() + ", " + page.maxPageNumber() + ", "
          + errorJavaName(ctx, page.errorSymbol(), imports, pkg) + "::new, "
          + StringEscape.javaString("`" + step.plan().keyColumn().replace("`", "``") + "`") + ", "
          + StringEscape.javaString(step.plan().dataProjection()) + ", " + helper + "::" + decoder + ", "
          + "this." + mapper.mapperFieldName() + "::" + step.plan().countMethod() + ", this." + mapper.mapperFieldName() + "::" + step.plan().dataMethod() + ");");
   }

   private static void renderSum(GenerationContext ctx, SpringBootWorkflow.SumStep step,
       Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage, List<String> lines,
       Map<SymbolId, WorkflowRenderer.MapperReference> mappers, List<SpringExpression.StringMatch> stringMatches) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      var mapper = ensureMapper(ctx, step.entitySymbol(), entity.javaName(), mappers);
      var support = ctx.model().projectArtifacts().stream()
          .filter(io.kcg.sir.lowering.springboot.model.ProjectArtifact.ExactAggregate.class::isInstance)
          .map(io.kcg.sir.lowering.springboot.model.ProjectArtifact.ExactAggregate.class::cast).findFirst().orElseThrow();
      String helper = support.packageName() + "." + support.simpleName();
      stringMatches.addAll(step.stringMatches());
      Map<LoweredNodeId, SpringExpression.StringMatch> matches = new LinkedHashMap<>();
      for (var match : step.stringMatches()) matches.put(match.expressionId(), match);
      String predicate = renderPredicateChain(ctx, step.predicate(), step.itemVariable(), entity, matches,
          variableNames, imports, currentPackage, "item", true);
      lines.add("        java.math.BigDecimal " + variableName(step.result(), variableNames) + " = " + helper + ".read("
          + helper + ".query(" + entity.javaName() + ".class, " + StringEscape.javaString(step.plan().projectionSql()) + "),"
          + " item -> " + predicate + ", " + mapper.mapperFieldName() + "::" + step.plan().mapperMethod() + ");");
   }

   private static void renderScalarQuery(GenerationContext ctx, SpringBootWorkflow.ScalarQueryStep step,
     Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage, List<String> lines,
     Map<SymbolId, WorkflowRenderer.MapperReference> mappers, List<SpringExpression.StringMatch> stringMatches) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      WorkflowRenderer.MapperReference mapper = ensureMapper(ctx, step.entitySymbol(), entity.javaName(), mappers);
      imports.add("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper");
      stringMatches.addAll(step.stringMatches());
      Map<LoweredNodeId, SpringExpression.StringMatch> matches = new LinkedHashMap<>();
      for (SpringExpression.StringMatch match : step.stringMatches()) {
         matches.put(match.expressionId(), match);
      }
      String wrapper = renderPredicateChain(ctx, step.predicate(), step.itemVariable(), entity, matches, variableNames, imports, currentPackage);
      if (step.plan().projectionProperty().isPresent()) {
         wrapper += ".select(" + entity.javaName() + "::" + getterName(step.plan().projectionProperty().orElseThrow()) + ")";
      }
      if (step.plan().tailSql().isPresent()) {
         wrapper += ".last(" + StringEscape.javaString(step.plan().tailSql().orElseThrow()) + ")";
      }
      String call = mapper.mapperFieldName() + "." + step.plan().mapperMethod() + "(" + wrapper + ")";
      boolean exists = step.plan().kind() == SpringBootWorkflow.ScalarResult.EXISTS;
      lines.add("        " + (exists ? "boolean " : "long ") + variableName(step.result(), variableNames) + " = "
         + (exists ? "!" + call + ".isEmpty()" : call) + ";");
   }

   private static void renderFind(
      GenerationContext ctx,
      FindStep step,
      String inputName,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers,
      List<SpringExpression.StringMatch> stringMatches
   ) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      WorkflowRenderer.MapperReference mapper = ensureMapper(ctx, step.entitySymbol(), entity.javaName(), mappers);
      imports.add("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper");
      stringMatches.addAll(step.stringMatches());
      Map<LoweredNodeId, SpringExpression.StringMatch> matches = new LinkedHashMap<>();
      for (SpringExpression.StringMatch match : step.stringMatches()) {
         matches.put(match.expressionId(), match);
      }

      String resultName = variableName(step.result(), variableNames);
      String wrapperChain = renderPredicateChain(ctx, step.predicate(), step.itemVariable(), entity, matches, variableNames, imports, currentPackage)
         + renderOrderClause(entity, step.orderKeys());
      lines.add("        var $wrapper = " + wrapperChain + ";");

      if (step.page().isEmpty()) {
         lines.add("        var " + resultName + " = " + mapper.mapperFieldName() + ".selectList($wrapper);");
         return;
      }

      SpringBootWorkflow.PageSpec page = step.page().get();
      if (inputName == null) {
         throw new IllegalStateException("a paged find requires a capability input: " + step.id());
      }

      String pageName = "$" + resultName + "Page";
      String sizeName = "$" + resultName + "Size";
      lines.add("        int " + pageName + " = " + inputName + "." + getterName(page.pagePropertyName()) + "() == null ? 1 : " + inputName + "." + getterName(page.pagePropertyName()) + "();");
      lines.add("        int " + sizeName + " = " + inputName + "." + getterName(page.sizePropertyName()) + "() == null ? " + page.defaultSize() + " : " + inputName + "." + getterName(page.sizePropertyName()) + "();");
      lines.add("        if (" + pageName + " < 1 || " + pageName + " > " + page.maxPageNumber()
         + " || " + sizeName + " < 1 || " + sizeName + " > " + page.maxSize() + ") {");
      lines.add("            throw new " + errorJavaName(ctx, page.errorSymbol(), imports, currentPackage) + "();");
      lines.add("        }");
      lines.add("        long $" + resultName + "Total = " + mapper.mapperFieldName() + ".selectCount($wrapper);");
      String recordsName = "$" + resultName + "Records";
      lines.add("        var " + recordsName + " = " + mapper.mapperFieldName() + ".selectList($wrapper.last(\"LIMIT \" + (" + pageName + " - 1) * " + sizeName + " + \", \" + " + sizeName + "));");
      ViewDeclaration view = responseView(ctx, step);
      Map<String, WorkflowRenderer.ProjectionRead> reads = new LinkedHashMap<>();
      readAssociations(ctx, view, recordsName, reads, imports, currentPackage, lines, mappers, new int[]{0}, "");
      lines.add("        var " + resultName + " = new PageResponse<>($" + resultName + "Total, " + pageName + ", " + sizeName + ", "
         + recordsName + ".stream().map($row -> "
         + renderProjectionRow(ctx, view, "$row", reads, "", imports, currentPackage) + ").toList());");
   }

   /**
   * One batch read per nested projection use site, plus the index that holds what it read.
   *
   * <p>Every read is taken from the rows already loaded — the page for the first level, the rows a
   * parent read produced for the next — so the whole result costs one statement per association and
   * never one per row. The plan states which property to collect keys from and which to compare
   * against, so this only follows it; the statement budget in the IR is what the result is held to.
   */
   private static void readAssociations(
      GenerationContext ctx,
      ViewDeclaration view,
      String rows,
      Map<String, WorkflowRenderer.ProjectionRead> reads,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers,
      int[] counter,
      String path
   ) {
      EntityDeclaration owner = ctx.entity(view.sourceEntitySymbol());
       for (SpringBootDeclaration.ViewField field : view.fields()) {
          if (field.value() instanceof SpringBootDeclaration.CountViewValue count) {
             var plan=count.plan();
             var child=ctx.entity(plan.targetEntitySymbol());
             var mapper=ensureMapper(ctx,plan.targetEntitySymbol(),child.javaName(),mappers);
             var read=RelatedCountProjectionRenderer.prepare(ctx,view,plan,rows,mapper.mapperFieldName(),Integer.toString(counter[0]++));
             lines.addAll(read.lines());
             String site=path.isEmpty()?field.javaName():path+"."+field.javaName();
             reads.put(site,new CountRead(plan,read.indexVariable()));
             continue;
          }
          SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElse(null);
         if (plan == null) {
            continue;
         }

         EntityDeclaration relatedEntity = ctx.entity(plan.targetEntitySymbol());
         registerEntityImport(ctx, relatedEntity, imports, currentPackage);
         WorkflowRenderer.MapperReference relatedMapper = ensureMapper(ctx, plan.targetEntitySymbol(), relatedEntity.javaName(), mappers);
         imports.add("com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper");
         boolean collection = plan.cardinality() == SpringBootDeclaration.RelationCardinality.TO_MANY;
         String site = path.isEmpty() ? field.javaName() : path + "." + field.javaName();
         String suffix = Integer.toString(counter[0]++);
         String keys = "$related" + suffix + "Keys";
         String relatedRows = "$related" + suffix;
         String index = relatedRows + "Index";
         lines.add("        var " + keys + " = " + rows + ".stream().map(" + owner.javaName() + "::"
            + getterName(plan.sourceKeyPropertyName()) + ")" + (collection ? "" : ".filter(java.util.Objects::nonNull)")
            + ".distinct().toList();");
         lines.add("        var " + relatedRows + " = " + keys + ".isEmpty() ? java.util.List.<" + relatedEntity.javaName() + ">of() : "
            + relatedMapper.mapperFieldName() + ".selectList(new LambdaQueryWrapper<" + relatedEntity.javaName() + ">().in("
            + relatedEntity.javaName() + "::" + getterName(plan.targetLookupPropertyName()) + ", " + keys + ")"
            + plan.orderPropertyName().map(name -> ".orderByAsc(" + relatedEntity.javaName() + "::" + getterName(name) + ")").orElse("")
            + ");");
         lines.add("        var " + index + " = " + relatedRows + ".stream().collect(" + (collection
            ? "java.util.stream.Collectors.groupingBy(" + relatedEntity.javaName() + "::" + getterName(plan.indexKeyPropertyName()) + ")"
            : "java.util.stream.Collectors.toMap(" + relatedEntity.javaName() + "::" + getterName(plan.indexKeyPropertyName())
               + ", $related -> $related)") + ");");
         ViewDeclaration targetView = ctx.declaration(plan.targetViewSymbol()) instanceof ViewDeclaration value ? value : null;
         if (targetView == null) {
            throw new IllegalStateException("a relation must target a declared view: " + plan.targetViewSymbol());
         }

         reads.put(site, new WorkflowRenderer.RelationRead(plan, index, "$related" + suffix + "Row"));
         readAssociations(ctx, targetView, relatedRows, reads, imports, currentPackage, lines, mappers, counter, site);
      }
   }

   /**
   * Projects one row into the declared response view, resolving every nested projection through the
   * index the read for that use site produced.
   */
   private static String renderProjectionRow(
      GenerationContext ctx,
      ViewDeclaration view,
      String row,
      Map<String, WorkflowRenderer.ProjectionRead> reads,
      String path,
      ImportSorter imports,
      String currentPackage
   ) {
      registerViewImport(ctx, view, imports, currentPackage);
      StringBuilder out = new StringBuilder("new ").append(view.javaName()).append('(');
      for (int index = 0; index < view.fields().size(); index++) {
         SpringBootDeclaration.ViewField field = view.fields().get(index);
         if (index > 0) {
            out.append(", ");
         }

          if (field.value() instanceof SpringBootDeclaration.CountViewValue) {
             String site=path.isEmpty()?field.javaName():path+"."+field.javaName();
             if (!(reads.get(site) instanceof CountRead read))throw new IllegalStateException("missing planned count index: "+site);
             out.append(read.indexVariable()).append(".getOrDefault(").append(row).append('.').append(getterName(read.plan().sourceKeyPropertyName()))
                 .append("(), 0L)");
             continue;
          }
          SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElse(null);
          if (plan == null) {
             out.append(renderProjectionValue(ctx, view, field, row, imports, currentPackage));
            continue;
         }

         String site = path.isEmpty() ? field.javaName() : path + "." + field.javaName();
         WorkflowRenderer.RelationRead read = reads.get(site) instanceof RelationRead relation ? relation : null;
         ViewDeclaration target = ctx.declaration(plan.targetViewSymbol()) instanceof ViewDeclaration value ? value : null;
         if (read == null || target == null) {
            throw new IllegalStateException("a nested projection has no read for its use site: " + site);
         }

         String nested = renderProjectionRow(ctx, target, read.lambdaName(), reads, site, imports, currentPackage);
         String key = row + "." + getterName(plan.sourceKeyPropertyName()) + "()";
         if (plan.cardinality() == SpringBootDeclaration.RelationCardinality.TO_MANY) {
            out.append(read.indexVariable()).append(".getOrDefault(").append(key)
               .append(", java.util.List.of()).stream().map(").append(read.lambdaName()).append(" -> ")
               .append(nested).append(").toList()");
         } else {
            out.append("java.util.Optional.ofNullable(").append(read.indexVariable()).append(".get(").append(key)
               .append(")).map(").append(read.lambdaName()).append(" -> ").append(nested).append(").orElse(null)");
         }
      }

      return out.append(')').toString();
   }

   /** The index one nested projection reads its related rows from, and the name its row is bound to. */
    private sealed interface ProjectionRead permits RelationRead, CountRead {}
    private record CountRead(SpringBootDeclaration.RelatedCountPlan plan, String indexVariable) implements ProjectionRead {}
    private record RelationRead(
       SpringBootDeclaration.ViewRelationPlan plan, String indexVariable, String lambdaName) implements ProjectionRead {
    }

   private static SpringBootDeclaration.ViewDeclaration responseView(GenerationContext ctx, FindStep step) {
      if (!(step.result().type() instanceof LoweredJavaType.PageValue pageValue)
         || !(pageValue.elementType() instanceof LoweredJavaType.Declared element)
         || !(ctx.declaration(element.symbolId()) instanceof SpringBootDeclaration.ViewDeclaration view)) {
         throw new IllegalStateException("a paged find must produce a Page of a declared view: " + step.result().type());
      }

      return view;
   }

   private static void registerViewImport(
      GenerationContext ctx, SpringBootDeclaration.ViewDeclaration view, ImportSorter imports, String currentPackage
   ) {
      String packageName = ctx.declaredPackage(new LoweredJavaType.Declared(
         LoweredJavaType.DeclaredKind.VIEW, view.sourceSymbol(), view.javaName()));
      if (!packageName.equals(currentPackage)) {
         imports.add(packageName + "." + view.javaName());
      }
   }

   /**
   * Ordering is part of the query contract, so every declared key becomes an explicit order clause in
   * authored order.
   */
   private static String renderOrderClause(EntityDeclaration entity, List<SpringBootWorkflow.OrderKey> orderKeys) {
      StringBuilder out = new StringBuilder();
      for (SpringBootWorkflow.OrderKey key : orderKeys) {
         out.append("\n                .orderBy")
         .append(key.descending() ? "Desc" : "Asc")
         .append('(').append(entity.javaName()).append("::").append(getterName(key.targetMemberName())).append(')');
      }

      return out.toString();
   }


   private static void renderCreate(
      GenerationContext ctx, CreateStep step, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage, List<String> lines
   ) {
      EntityDeclaration entity = ctx.entity(step.entitySymbol());
      registerEntityImport(ctx, entity, imports, currentPackage);
      String resultName = variableName(step.result(), variableNames);
      lines.add("        var " + resultName + " = new " + entity.javaName() + "();");

      for (Binding binding : step.bindings()) {
         String value = unwrapToEntityProperty(
            ExpressionRenderer.renderExpression(binding.value(), variableNames), binding.value().type());
         collectExpressionImports(ctx, binding.value(), variableNames, imports, currentPackage);
         lines.add("        " + resultName + "." + setter(binding.targetProperty()) + "(" + value + ");");
      }
   }

   private static void renderUpdate(
      GenerationContext ctx,
      CapabilityDeclaration capability,
      UpdateStep step,
      SpringBootWorkflow workflow,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines
   ) {
      Variable target = resolveVariable(workflow, step.targetVariable());
      String targetName = variableName(target, variableNames);
      SpringBootWorkflow.ConditionalUpdate conditional = step.conditional().orElse(null);
      String inputName = capability.input().map(Variable::targetName).orElse(null);
      for (Binding binding : step.bindings()) {
         if (conditional == null || inputName == null) {
            String value = unwrapToEntityProperty(
               ExpressionRenderer.renderExpression(binding.value(), variableNames), binding.value().type());
            collectExpressionImports(ctx, binding.value(), variableNames, imports, currentPackage);
            lines.add("        " + targetName + "." + setter(binding.targetProperty()) + "(" + value + ");");
            continue;
         }

         // A change travels inside the payload's change set, so the merged value is read from there
         // rather than from the entity-shaped path the SIR bound it through.
         String change = payloadPropertyOf(conditional, binding, ctx);
         String changeSet = inputName + "." + getterName(changesPropertyOf(capability, ctx)) + "()";
         String value = unwrapToEntityProperty(changeSet + "." + getterName(change) + "()", binding.value().type());
         lines.add("        " + targetName + "." + setter(binding.targetProperty()) + "("
            + changeSet + ".has(" + StringEscape.javaString(change) + ")"
            + " ? " + value
            + " : " + targetName + "." + getterName(binding.targetProperty()) + "());");
      }
   }

   /**
   * A change value is an {@code Optional} when the declared field is nullable and a plain value when it
   * is not, while the entity property it is written into is always plain.
   */
   private static String unwrapToEntityProperty(String value, LoweredJavaType type) {
      return GenerationContext.isNullable(type) ? value + ".orElse(null)" : value;
   }

   /**
   * The request property that supplies one binding.
   *
   * <p>The conditional update already records, per entity field, which change carries it, so this is a
   * lookup rather than a second guess at the payload's shape.
   */
   private static String payloadPropertyOf(
      SpringBootWorkflow.ConditionalUpdate conditional, Binding binding, GenerationContext ctx
   ) {
      return conditional.assignments().stream()
         .filter(assignment -> assignment.entityFieldSymbol().equals(binding.fieldSymbol()))
         .map(SpringBootWorkflow.ColumnAssignment::payloadPropertyName)
         .findFirst()
         .orElse(binding.sourcePropertyName().orElseThrow(
            () -> new IllegalStateException("a conditional update binding must come from a change set: " + binding.targetProperty())));
   }

   private static String changesPropertyOf(CapabilityDeclaration capability, GenerationContext ctx) {
      return ctx.patchSpecOf(capability)
         .map(SpringBootDeclaration.PatchSpec::changesPropertyName)
         .orElseThrow(() -> new IllegalStateException("a conditional update requires a patch payload: " + capability.javaName()));
   }

   private static SpringBootWorkflow.ConditionalUpdate conditionalUpdateOf(GenerationContext ctx, SymbolId entitySymbol) {
      for (SpringBootDeclaration declaration : ctx.model().declarations()) {
         if (!(declaration instanceof CapabilityDeclaration candidate)) {
            continue;
         }

         for (Step step : SpringBootWorkflow.walk(candidate.workflow().steps()).toList()) {
            Optional<SpringBootWorkflow.ConditionalUpdate> conditional = switch (step) {
               case PersistStep persist -> persist.conditional();
               case UpdateStep update -> update.conditional();
               default -> Optional.empty();
            };
            if (conditional.isPresent() && conditional.get().entitySymbol().equals(entitySymbol)) {
               return conditional.get();
            }
         }
      }

      return null;
   }

   private static void renderPersist(
      GenerationContext ctx,
      CapabilityDeclaration capability,
      PersistStep step,
      SpringBootWorkflow workflow,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      Variable target = resolveVariable(workflow, step.targetVariable());
      LoweredJavaType type = target.type();
      if (!(type instanceof Declared declared) || declared.kind() != DeclaredKind.ENTITY) {
         throw new IllegalStateException("PersistStep target must be an entity variable, got type: " + type);
      }

      registerEntityImport(ctx, declared, imports, currentPackage);
      WorkflowRenderer.MapperReference mapper = ensureMapper(ctx, declared.symbolId(), declared.javaName(), mappers);
      String targetName = variableName(target, variableNames);
      EntityDeclaration entity = ctx.entity(declared.symbolId());
      if (step.action() == PersistenceAction.DELETE) {
         // The row was read by a load of this workflow; nothing is written, so there is nothing to check.
         lines.add("        " + mapperAccess(mapper, variableNames) + ".deleteById(" + targetName + "." + getterName(entity.identity().javaName()) + "());");
         return;
      }

      renderCandidateChecks(ctx, capability, step, entity, targetName, variableNames, imports, currentPackage, lines);
      UpdateStep columnWrite = columnWriteOf(workflow, step);
      if (columnWrite != null) {
         renderColumnPersist(ctx, columnWrite, target, entity, mapper, variableNames, imports, currentPackage, lines);
         return;
      }

      SpringBootWorkflow.ConditionalUpdate conditional = step.conditional().orElse(null);
      if (conditional == null) {
         if (step.action() == PersistenceAction.INSERT) {
            if (entity.identity().generation() == SpringBootDeclaration.Generation.UUID) {
               String identity = entity.identity().javaName();
               lines.add("        if (" + targetName + "." + getterName(identity) + "() == null) {");
               lines.add("            " + targetName + "." + setter(identity) + "(java.util.UUID.randomUUID());");
               lines.add("        }");
            }

            // A new row starts at the version the target initializes, so the first change can be
            // matched against it.
            entity.version().ifPresent(version -> lines.add("        " + targetName + "."
               + setter(version.javaName()) + "(" + version.initialValue() + "L);"));
            if (step.failure().isPresent()) {
               // The row may collide with one that is already there (a unique field or key): that is the declared failure.
               String duplicate = errorJavaName(ctx, step.failure().orElseThrow(), imports, currentPackage);
               lines.add("        try {");
               lines.add("            " + mapperAccess(mapper, variableNames) + ".insert(" + targetName + ");");
               lines.add("        } catch (org.springframework.dao.DuplicateKeyException $duplicate) {");
               lines.add("            throw new " + duplicate + "();");
               lines.add("        }");
               return;
            }

            lines.add("        " + mapperAccess(mapper, variableNames) + ".insert(" + targetName + ");");
            return;
         }

         lines.add("        " + mapperAccess(mapper, variableNames) + ".updateById(" + targetName + ");");
         return;
      }

      String inputName = capability.input().map(Variable::targetName).orElseThrow(
         () -> new IllegalStateException("a conditional update requires a capability input: " + capability.javaName()));
      String expectedVersion = inputName + "." + getterName(expectedVersionPropertyOf(capability, ctx)) + "()";
      String errorName = conditional.failure().isPresent()
         ? errorJavaName(ctx, conditional.failure().orElseThrow(), imports, currentPackage)
         : null;
      String affected = targetName + "Affected";
      lines.add("        int " + affected + " = " + mapperAccess(mapper, variableNames) + ".updateIfVersionMatches(" + targetName + ", " + expectedVersion + ");");
      lines.add("        if (" + affected + " != 1) {");
      if (errorName == null) {
         throw new IllegalStateException("a conditional persist must declare the failure of an unmatched row: " + step.id());
      }

      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
      // The statement advanced the stored version, so the value handed back has to be the committed
      // one rather than the version the request was matched against.
      entity.version().ifPresent(version -> lines.add("        " + targetName + "."
         + setter(version.javaName()) + "(" + expectedVersion + " + " + conditional.versionIncrement() + "L);"));
   }

   /**
   * The update this persist writes as one statement on the stored row, if its row was updated under a guard or has a
   * column changed by an amount. Validation has settled that a row has only one such update.
   */
   private static UpdateStep columnWriteOf(SpringBootWorkflow workflow, PersistStep persist) {
      if (persist.conditional().isPresent() || persist.action() != PersistenceAction.UPDATE) {
         return null;
      }

      return SpringBootWorkflow.walk(workflow.steps())
         .filter(UpdateStep.class::isInstance)
         .map(UpdateStep.class::cast)
         .filter(update -> update.targetVariable().equals(persist.targetVariable()))
         .filter(update -> update.guard().isPresent() || update.bindings().stream().anyMatch(binding -> binding.relative().isPresent()))
         .findFirst()
         .orElse(null);
   }

   /**
   * Writes an update as the one statement that both checks and writes the row.
   *
   * <p>The statement is {@code UPDATE ... SET <only the bound columns> WHERE id = ? [AND <guard>]}. The guard
   * is evaluated by the database against the row as it is stored at that moment, so two requests that both
   * read the row as eligible cannot both write it: the second matches no row and gets the guard's error.
   * A column changed by an amount is written as {@code SET col = col + ?}, so concurrent requests all count
   * instead of overwriting each other with a value computed from what each one read.
   *
   * <p>Only the bound columns are written, because nothing pins the rest of the row the way a version would,
   * and the other values come from the candidate the update step already built in memory, so they are computed
   * once. After a change by an amount the row is read back, so what the request goes on to return is the stored
   * value, not the one computed from the earlier read.
   */
   private static void renderColumnPersist(
      GenerationContext ctx,
      UpdateStep update,
      Variable target,
      EntityDeclaration entity,
      WorkflowRenderer.MapperReference mapper,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines
   ) {
      imports.add("com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper");
      String targetName = variableName(target, variableNames);
      String identity = getterName(entity.identity().javaName());
      String updated = helperName(targetName, variableNames, "Updated");
      renderDecimalDeltas(ctx, update, entity, targetName, variableNames, lines);
      lines.add("        int " + updated + " = " + mapperAccess(mapper, variableNames) + ".update(null, new LambdaUpdateWrapper<" + entity.javaName() + ">()");
      lines.add("                .eq(" + entity.javaName() + "::" + identity + ", " + targetName + "." + identity + "())");
      if (update.guard().isPresent()) {
         var counter = predicateNames(variableNames instanceof EachNames ? "$eachQ" : "q");
         String rootName = nextWrapperName(counter);
         String condition = renderPredicateNode(
            ctx, update.guard().orElseThrow().predicate(), target, entity, Map.of(), variableNames, imports, currentPackage, rootName, counter);
         lines.add("                .and(" + rootName + " -> " + condition + ")");
      }

      for (int index = 0; index < update.bindings().size(); index++) {
         Binding binding = update.bindings().get(index);
         String property = getterName(binding.targetProperty());
         String closing = index == update.bindings().size() - 1 ? ");" : "";
         if (binding.relative().isPresent()) {
            SpringBootWorkflow.Relative relative = binding.relative().orElseThrow();
            String amount = ExpressionRenderer.renderExpression(relative.amount(), variableNames);
            collectExpressionImports(ctx, relative.amount(), variableNames, imports, currentPackage);
            lines.add("                ." + (relative.subtract() ? "setDecrBy" : "setIncrBy") + "("
               + entity.javaName() + "::" + property + ", " + amount + ")" + closing);
         } else {
            lines.add("                .set(" + entity.javaName() + "::" + property + ", " + targetName + "." + property + "())" + closing);
         }
      }

      lines.add("        if (" + updated + " != 1) {");
      if (update.guard().isPresent()) {
         String errorName = errorJavaName(ctx, update.guard().orElseThrow().failure(), imports, currentPackage);
         lines.add("            throw new " + errorName + "();");
      } else {
         lines.add("            throw new IllegalStateException(\"the " + entity.javaName() + " row is gone: \" + " + targetName + "." + identity + "());");
      }

      lines.add("        }");
      List<Binding> counted = update.bindings().stream().filter(binding -> binding.relative().isPresent()).toList();
      if (!counted.isEmpty()) {
         String fresh = helperName(targetName, variableNames, "Stored");
         lines.add("        " + entity.javaName() + " " + fresh + " = " + mapperAccess(mapper, variableNames) + ".selectById(" + targetName + "." + identity + "());");
         for (Binding binding : counted) {
            lines.add("        " + targetName + "." + setter(binding.targetProperty()) + "(" + fresh + "." + getterName(binding.targetProperty()) + "());");
         }
      }
   }

   /**
    * The name of a local the renderer adds for a row: its checks, its affected-row count, its re-read copy. Inside a
    * loop pass, a row from outside the loop gets a {@code $each} prefix, because Java does not let a block declare a
    * name the enclosing method already declared for the same row; a pass-local row already has a {@code $} name.
    */
   private static String helperName(String targetName, Map<SymbolId, String> names, String suffix) {
      return names instanceof EachNames && !targetName.startsWith("$") ? "$each" + targetName + suffix : targetName + suffix;
   }

   private static void renderDecimalDeltas(GenerationContext ctx, UpdateStep update, EntityDeclaration entity,
         String targetName, Map<SymbolId, String> names, List<String> lines) {
      List<Binding> decimals = update.bindings().stream()
          .filter(binding -> binding.relative().flatMap(SpringBootWorkflow.Relative::decimalShape).isPresent()).toList();
      if (decimals.isEmpty()) return;
      WriteSupport support = WriteSupport.of(ctx.model());
      String violations = helperName(targetName, names, "DecimalViolations");
      lines.add("        java.util.List<" + support.fieldErrorFqn() + "> " + violations + " = new java.util.ArrayList<>();");
      for (Binding binding : decimals) {
         var relative = binding.relative().orElseThrow();
         var shape = relative.decimalShape().orElseThrow();
         lines.add("        " + support.validationSupportFqn() + ".decimalScale(" + violations + ", "
             + javaString(binding.sourcePropertyName().orElse(binding.targetProperty())) + ", "
             + ExpressionRenderer.renderExpression(relative.amount(), names) + ", " + shape.scale() + ");");
      }
      lines.add("        if (!" + violations + ".isEmpty()) throw " + support.exceptionBaseFqn() + ".invalidRequest(" + violations + ");");
      lines.add("        " + support.validationSupportFqn() + ".requireStrictDecimalSession(" + entity.javaName() + ".class);");
   }

   private static String expectedVersionPropertyOf(CapabilityDeclaration capability, GenerationContext ctx) {
      return ctx.patchSpecOf(capability)
         .map(SpringBootDeclaration.PatchSpec::expectedVersionPropertyName)
         .orElseThrow(() -> new IllegalStateException("a conditional persist requires a patch payload: " + capability.javaName()));
   }

   /**
   * Checks the candidate the target is about to write against the entity's declared constraints.
   *
   * <p>The payload's own annotations cannot do this for a change set: a field the request left out
   * keeps the value the entity already carries, so only the merged value can be judged. Violations are
   * reported against the request property they came from, and nothing is written while any remain.
   */
   private static void renderCandidateChecks(
      GenerationContext ctx,
      CapabilityDeclaration capability,
      PersistStep step,
      EntityDeclaration entity,
      String targetName,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines
   ) {
      List<WorkflowRenderer.CandidateField> candidates = candidateFields(ctx, capability, step, entity);
      if (candidates.stream().allMatch(candidate -> candidate.constraints().isEmpty())) {
         return;
      }

      WriteSupport support = WriteSupport.of(ctx.model());
      String fieldError = support.fieldErrorFqn();
      String validationSupport = support.validationSupportFqn();
      String exceptionBase = support.exceptionBaseFqn();
      if (!samePackage(fieldError, currentPackage)) {
         imports.add(fieldError);
      }

      if (!samePackage(validationSupport, currentPackage)) {
         imports.add(validationSupport);
      }

      if (!samePackage(exceptionBase, currentPackage)) {
         imports.add(exceptionBase);
      }

      imports.add("java.util.ArrayList");
      imports.add("java.util.List");
      String violations = helperName(targetName, variableNames, "Violations");
      lines.add("        List<" + simpleName(fieldError) + "> " + violations + " = new ArrayList<>();");
      for (WorkflowRenderer.CandidateField candidate : candidates) {
         for (SpringBootDeclaration.Constraint constraint : candidate.constraints()) {
            // The check judges the merged value the entity now holds, which is a plain nullable value
            // for a nullable field, so no unwrapping is involved.
            String value = targetName + "." + getterName(candidate.propertyName()) + "()";
            String presentOnly = candidate.nullable() && constraint.kind() == SpringBootDeclaration.ConstraintKind.NOT_BLANK
                    ? "if (" + value + " != null) " : "";
            lines.add("        " + presentOnly + simpleName(validationSupport) + "." + constraintCall(
               constraint, violations, candidate.requestPath(), value, candidate.decimal()));
         }
      }

      lines.add("        if (!" + violations + ".isEmpty()) {");
      lines.add("            throw " + simpleName(exceptionBase) + ".invalidRequest(" + violations + ");");
      lines.add("        }");
   }

   /**
   * One value the persist is about to write, with the constraints and request property it carries.
   *
   * <p>{@code decimal} records whether the value already is a decimal: an integer candidate has to be
   * widened for a decimal bound, a decimal candidate must not be narrowed.
   */
   private record CandidateField(
      String propertyName,
      String requestPath,
      List<SpringBootDeclaration.Constraint> constraints,
      boolean decimal,
      boolean nullable
   ) {
   }

   private static List<WorkflowRenderer.CandidateField> candidateFields(
      GenerationContext ctx, CapabilityDeclaration capability, PersistStep step, EntityDeclaration entity
   ) {
      List<WorkflowRenderer.CandidateField> fields = new ArrayList<>();
      SpringBootDeclaration.PatchSpec patch = ctx.patchSpecOf(capability).orElse(null);
      for (Step candidate : SpringBootWorkflow.walk(capability.workflow().steps()).toList()) {
         if (candidate instanceof CreateStep create && create.result().symbol().equals(step.targetVariable())) {
            for (Binding binding : create.bindings()) {
               fields.add(new WorkflowRenderer.CandidateField(
                  binding.targetProperty(),
                  binding.sourcePropertyName().orElse(binding.targetProperty()),
                  constraintsOf(entity, binding.fieldSymbol()),
                  isDecimal(entity, binding.fieldSymbol()), isOptional(entity, binding.fieldSymbol())));
            }
         } else if (candidate instanceof UpdateStep update && update.targetVariable().equals(step.targetVariable())) {
            for (Binding binding : update.bindings()) {
               // An atomic delta's stale local preview is not its stored result.
               if (binding.relative().isPresent()) continue;
               String change = update.conditional().isPresent()
                   ? payloadPropertyOf(update.conditional().orElseThrow(), binding, ctx)
                   : binding.sourcePropertyName().orElse(binding.targetProperty());
               fields.add(new WorkflowRenderer.CandidateField(
                  binding.targetProperty(),
                  patch == null ? change : patch.changesPropertyName() + "." + change,
                  constraintsOf(entity, binding.fieldSymbol()),
                  isDecimal(entity, binding.fieldSymbol()), isOptional(entity, binding.fieldSymbol())));
            }
         }
      }

      return fields;
   }

   private static boolean isOptional(EntityDeclaration entity, SymbolId fieldSymbol) {
      return entity.fields().stream().anyMatch(field -> field.sourceSymbol().equals(fieldSymbol)
          && field.type() instanceof LoweredJavaType.OptionalValue);
   }

   private static boolean isDecimal(EntityDeclaration entity, SymbolId fieldSymbol) {
      return entity.fields().stream()
         .filter(field -> field.sourceSymbol().equals(fieldSymbol))
         .map(SpringBootDeclaration.Property::type)
         .map(GenerationContext::entityPropertyType)
         .anyMatch(type -> type instanceof LoweredJavaType.Scalar scalar && scalar.kind() == LoweredJavaType.ScalarKind.BIG_DECIMAL);
   }

   private static List<SpringBootDeclaration.Constraint> constraintsOf(EntityDeclaration entity, SymbolId fieldSymbol) {
      return entity.fields().stream()
         .filter(field -> field.sourceSymbol().equals(fieldSymbol))
         .map(SpringBootDeclaration.Property::constraints)
         .findFirst()
         .orElse(List.of());
   }

   private static String constraintCall(
      SpringBootDeclaration.Constraint constraint, String violations, String path, String value, boolean decimal
   ) {
      String pathArgument = "StringEscapePlaceholder";
      String pathLiteral = javaString(path);
      return switch (constraint.kind()) {
         case NOT_NULL -> "notNull(" + violations + ", " + pathLiteral + ", " + value + ");";
         case PRECISION -> {
            var shape = constraint.decimalShape().orElseThrow();
            yield "precision(" + violations + ", " + pathLiteral + ", " + value + ", " + shape.precision() + ", " + shape.scale() + ");";
         }
         case NOT_BLANK -> "notBlank(" + violations + ", " + pathLiteral + ", " + value + ");";
         case TIME_ZONE -> "timeZone(" + violations + ", " + pathLiteral + ", " + value + ");";
         case EMAIL -> "email(" + violations + ", " + pathLiteral + ", " + value + ");";
         case SIZE -> {
            List<String> args = constraint.arguments();
            if (args.size() != 2) {
               throw new IllegalStateException("SIZE constraint requires 2 arguments, got: " + args);
            }

            yield "length(" + violations + ", " + pathLiteral + ", " + value + ", " + args.get(0) + ", " + args.get(1) + ");";
         }
         case DECIMAL_MIN -> "min(" + violations + ", " + pathLiteral + ", " + numeric(value, decimal)
            + ", new java.math.BigDecimal(" + javaString(argument(constraint)) + "));";
         case DECIMAL_MAX -> "max(" + violations + ", " + pathLiteral + ", " + numeric(value, decimal)
            + ", new java.math.BigDecimal(" + javaString(argument(constraint)) + "));";
      };
   }

   private static String argument(SpringBootDeclaration.Constraint constraint) {
      List<String> args = constraint.arguments();
      if (args.size() != 1) {
         throw new IllegalStateException(constraint.kind() + " constraint requires 1 argument, got: " + args);
      }

      return args.get(0);
   }

   /** Numeric candidates are compared as decimals; an integral value is widened, never the reverse. */
   private static String numeric(String value, boolean decimal) {
      return decimal
         ? value
         : value + " == null ? null : java.math.BigDecimal.valueOf(" + value + ".longValue())";
   }

   private static String javaString(String value) {
      return StringEscape.javaString(value);
   }

   private static boolean samePackage(String fqn, String currentPackage) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot >= 0 && fqn.substring(0, lastDot).equals(currentPackage);
   }

   private static String simpleName(String fqn) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
   }

   private static void renderReturn(
      GenerationContext ctx,
      CapabilityDeclaration capability,
      ReturnStep step,
      ResponseRepresentation responseRepresentation,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers
   ) {
      if (responseRepresentation == ResponseRepresentation.VOID) {
         lines.add("        return;");
         return;
      }

      String value = ExpressionRenderer.renderExpression(step.value(), variableNames);
      collectExpressionImports(ctx, step.value(), variableNames, imports, currentPackage);
      if (responseRepresentation != ResponseRepresentation.PROJECTION) {
         lines.add("        return " + value + ";");
         return;
      }

      if (step.associationStatements() > 0) {
         // The row nests other views: read what it refers to (or what refers to it) the way a page of rows does, with
         // one statement per association, taking this one row as the rows already loaded.
         if (!(capability.outputType() instanceof LoweredJavaType.Declared declared)
            || !(ctx.declaration(declared.symbolId()) instanceof SpringBootDeclaration.ViewDeclaration view)) {
            throw new IllegalStateException("a projected response must declare a view output: " + capability.javaName());
         }

         lines.add("        var $returnedRows = java.util.List.of(" + value + ");");
         Map<String, WorkflowRenderer.ProjectionRead> reads = new LinkedHashMap<>();
         readAssociations(ctx, view, "$returnedRows", reads, imports, currentPackage, lines, mappers, new int[]{0}, "");
         lines.add("        return " + renderProjectionRow(ctx, view, value, reads, "", imports, currentPackage) + ";");
         return;
      }

      lines.add("        return " + renderProjectedValue(ctx, capability, value, imports, currentPackage) + ";");
   }

   /**
   * Projects the returned entity into the declared response view.
   *
   * <p>The view names the entity member behind every field, so the projection reads those members and
   * never the entity's own shape.
   */
   private static String renderProjectedValue(
      GenerationContext ctx,
      CapabilityDeclaration capability,
      String value,
      ImportSorter imports,
      String currentPackage
   ) {
      if (!(capability.outputType() instanceof LoweredJavaType.Declared declared)
         || !(ctx.declaration(declared.symbolId()) instanceof SpringBootDeclaration.ViewDeclaration view)) {
         throw new IllegalStateException("a projected response must declare a view output: " + capability.javaName());
      }

      registerViewImport(ctx, view, imports, currentPackage);
      StringBuilder out = new StringBuilder("new ").append(view.javaName()).append('(');
      for (int index = 0; index < view.fields().size(); index++) {
         SpringBootDeclaration.ViewField field = view.fields().get(index);
         if (index > 0) {
            out.append(", ");
         }

         out.append(renderProjectionValue(ctx, view, field, value, imports, currentPackage));
      }

      return out.append(')').toString();
   }

   /** Both ordinary and batched projections cross the same nullable-value boundary exactly once. */
   private static String renderProjectionValue(GenerationContext ctx, ViewDeclaration view,
         SpringBootDeclaration.ViewField field, String row, ImportSorter imports, String currentPackage) {
      String value;
      if (field.value() instanceof SpringBootDeclaration.ComputedViewValue computed) {
         var binding = view.sourceRow().orElseThrow(() -> new IllegalStateException("computed view has no row binding"));
         Map<SymbolId, String> names = Map.of(binding.symbol(), row);
         collectExpressionImports(ctx, computed.expression(), names, imports, currentPackage);
         value = ExpressionRenderer.renderExpression(computed.expression(), names);
      } else {
         value = row + "." + getterName(field.sourcePropertyName()) + "()";
      }
      return GenerationContext.isNullable(field.type()) ? "java.util.Optional.ofNullable(" + value + ")" : value;
   }

   private static String renderPredicateChain(
       GenerationContext ctx, SpringExpression predicate, Variable itemVariable, EntityDeclaration entity,
       Map<LoweredNodeId, SpringExpression.StringMatch> stringMatches, Map<SymbolId, String> variableNames,
       ImportSorter imports, String currentPackage) {
      return renderPredicateChain(ctx, predicate, itemVariable, entity, stringMatches, variableNames, imports,
          currentPackage, "new LambdaQueryWrapper<" + entity.javaName() + ">()", false);
   }

   private static String renderPredicateChain(
      GenerationContext ctx, SpringExpression predicate, Variable itemVariable, EntityDeclaration entity,
      Map<LoweredNodeId, SpringExpression.StringMatch> stringMatches, Map<SymbolId, String> variableNames,
      ImportSorter imports, String currentPackage, String seed, boolean privateNames) {
      var counter = predicateNames(privateNames ? "$sumQ" : "q");
      String rootName = nextWrapperName(counter);
      String body = renderPredicateNode(ctx, predicate, itemVariable, entity, stringMatches, variableNames, imports, currentPackage, rootName, counter);
      return seed + "\n                .and(" + rootName + " -> " + body + ")";
   }

   private static String renderPredicateNode(
      GenerationContext ctx,
      SpringExpression predicate,
      Variable itemVariable,
      EntityDeclaration entity,
      Map<LoweredNodeId, SpringExpression.StringMatch> stringMatches,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage,
      String wrapperName,
      java.util.Iterator<String> counter
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
                  if (binary.presentComparison().isPresent()) {
                     return renderPresentComparison(ctx, binary, itemVariable, entity, variableNames, imports, currentPackage, wrapperName);
                  }
                  if (binary.right() instanceof MemberExpression otherColumn
                    && otherColumn.receiver() instanceof NameExpression otherName
                    && otherName.resolvedSymbol().equals(itemVariable.symbol())) {
                     // Two columns of the same row are compared by the database, not by a value read earlier.
                     String operator = switch (binary.operator()) {
                        case EQ -> "=";
                        case NE -> "<>";
                        case LT -> "<";
                        case LE -> "<=";
                        case GT -> ">";
                        case GE -> ">=";
                        default -> throw new IllegalStateException();
                     };
                     return wrapperName + ".apply(" + StringEscape.javaString(
                        columnOf(entity, memberSymbol(binary.left(), itemVariable)) + " " + operator + " " + columnOf(entity, otherColumn.resolvedMember())) + ")";
                  }

                  String field = renderFindField(binary.left(), itemVariable, entity.javaName());
                  String value = ExpressionRenderer.renderExpression(binary.right(), variableNames);
                  collectExpressionImports(ctx, binary.right(), variableNames, imports, currentPackage);
                  return wrapperName + "." + method + "(" + field + ", " + value + ")";
               case CONTAINS_LITERAL:
                  SpringExpression.StringMatch match = stringMatches.get(binary.id());
                  if (match == null) {
                     throw new IllegalStateException("literal match has no plan entry: " + binary.id());
                  }

                  String literalValue = ExpressionRenderer.renderExpression(binary.right(), variableNames);
                  collectExpressionImports(ctx, binary.right(), variableNames, imports, currentPackage);
                  return wrapperName + ".apply("
                     + StringEscape.javaString(likeSql(binary.left(), itemVariable, entity, match))
                     + ", escapeLikeLiteral(" + literalValue + "))";
               case AND:
               case OR:
                  String leftName = nextWrapperName(counter);
                  String rightName = nextWrapperName(counter);
                  String left = renderPredicateNode(ctx, binary.left(), itemVariable, entity, stringMatches, variableNames, imports, currentPackage, leftName, counter);
                  String right = renderPredicateNode(
                     ctx, binary.right(), itemVariable, entity, stringMatches, variableNames, imports, currentPackage, rightName, counter
                  );
                  String connector = binary.operator() == BinaryOperator.AND ? "and" : "or";
                  return wrapperName + ".and(" + leftName + " -> " + left + ")." + connector + "(" + rightName + " -> " + right + ")";
               default:
                  throw new MatchException(null, null);
            }
         case UnaryExpression unary when unary.operator() == UnaryOperator.NOT:
            String nestedName = nextWrapperName(counter);
            String nested = renderPredicateNode(ctx, unary.operand(), itemVariable, entity, stringMatches, variableNames, imports, currentPackage, nestedName, counter);
            return wrapperName + ".not(" + nestedName + " -> " + nested + ")";
         case UnaryExpression unary when unary.operator() == UnaryOperator.IS_NONE || unary.operator() == UnaryOperator.IS_PRESENT:
            if (unary.operand() instanceof MemberExpression member && member.receiver() instanceof NameExpression name
                  && name.resolvedSymbol().equals(itemVariable.symbol())) {
               String method = unary.operator() == UnaryOperator.IS_NONE ? "isNull" : "isNotNull";
               return wrapperName + "." + method + "(" + renderFindField(unary.operand(), itemVariable, entity.javaName()) + ")";
            }
            collectExpressionImports(ctx, unary.operand(), variableNames, imports, currentPackage);
            String question = ExpressionRenderer.renderExpression(unary, variableNames);
            return wrapperName + ".apply((" + question + ") ? \"1 = 1\" : \"1 = 0\")";
         case SpringExpression.BooleanLiteral literal:
            // "where true" is every row (a list of everything, narrowed only by the entity's scope); a wrapper has no
            // empty condition, so the constant is written as one.
            return wrapperName + ".apply(" + StringEscape.javaString(literal.value() ? "1 = 1" : "1 = 0") + ")";
         case SpringExpression.ExistsPredicate exists:
            // The correlated subquery is decided before rendering: this only quotes its text and
            // binds the values the plan declares, in the order it declares them.
            return wrapperName + ".apply(" + StringEscape.javaString("EXISTS (" + exists.subquerySql() + ")")
               + renderExistsArguments(ctx, exists, variableNames, imports, currentPackage) + ")";
         default:
            throw new IllegalStateException("Unsupported find predicate expression: " + predicate);
      }
   }

   /** The lowerer declared a present-only comparison: COALESCE removes SQL UNKNOWN before any NOT. */
   private static String renderPresentComparison(
      GenerationContext ctx, BinaryExpression binary, Variable itemVariable, EntityDeclaration entity,
      Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage, String wrapperName
   ) {
      String column = columnOf(entity, memberSymbol(binary.left(), itemVariable));
      String operator = switch (binary.operator()) {
         case EQ -> "=";
         case NE -> "<>";
         case LT -> "<";
         case LE -> "<=";
         case GT -> ">";
         case GE -> ">=";
         default -> throw new IllegalStateException("a comparison plan requires a comparison");
      };
      if (binary.right() instanceof MemberExpression member && member.receiver() instanceof NameExpression name
            && name.resolvedSymbol().equals(itemVariable.symbol())) {
         String rightColumn = columnOf(entity, member.resolvedMember());
         return wrapperName + ".apply(" + StringEscape.javaString("COALESCE(" + column + " " + operator + " " + rightColumn + ", FALSE)") + ")";
      }
      collectExpressionImports(ctx, binary.right(), variableNames, imports, currentPackage);
      String value = ExpressionRenderer.renderExpression(binary.right(), variableNames);
      return wrapperName + ".apply(" + StringEscape.javaString("COALESCE(" + column + " " + operator + " {0}, FALSE)")
         + ", " + value + ")";
   }

   private static String renderExistsArguments(GenerationContext ctx, SpringExpression.ExistsPredicate exists, Map<SymbolId, String> variableNames, ImportSorter imports, String currentPackage) {
      StringBuilder out = new StringBuilder();
      for (SpringExpression.ExistsArgument argument : exists.arguments()) {
         if (argument instanceof SpringExpression.ExistsArgument.Value value) {
            collectExpressionImports(ctx, value.expression(), variableNames, imports, currentPackage);
            out.append(", ").append(ExpressionRenderer.renderExpression(value.expression(), variableNames));
         } else {
            out.append(", ").append(renderExistsArgument(argument));
         }
      }

      return out.toString();
   }

   static String renderExistsArgument(SpringExpression.ExistsArgument argument) {
      return switch (argument) {
         case SpringExpression.ExistsArgument.Value ignored -> throw new IllegalStateException("external values require their bound variable context");
         case SpringExpression.ExistsArgument.Text text -> StringEscape.javaString(text.value());
         case SpringExpression.ExistsArgument.Integral integral -> integral.value() + "L";
         case SpringExpression.ExistsArgument.Decimal decimal ->
            "new java.math.BigDecimal(" + StringEscape.javaString(decimal.value().toPlainString()) + ")";
         case SpringExpression.ExistsArgument.Flag flag -> Boolean.toString(flag.value());
      };
   }

   /**
   * The literal-match SQL is built from the entity's persisted column, so the predicate still matches
   * the same field the lambda comparison would.
   */
   private static String likeSql(
      SpringExpression expression, Variable itemVariable, EntityDeclaration entity, SpringExpression.StringMatch match
   ) {
      String column = columnOf(entity, memberSymbol(expression, itemVariable));
      return column + " LIKE CONCAT('%', {0}, '%') ESCAPE " + sqlCharacterLiteral(match.escapeCharacter());
   }

   private static SymbolId memberSymbol(SpringExpression expression, Variable itemVariable) {
      if (expression instanceof MemberExpression member
         && member.receiver() instanceof NameExpression nameExpr
         && nameExpr.resolvedSymbol().equals(itemVariable.symbol())) {
         return member.resolvedMember();
      }

      throw new IllegalStateException("Find predicate left side must be item.<field>, got: " + expression);
   }

   /** The member's column as it is written in SQL text: a MySQL reserved name such as {@code condition} is quoted. */
   private static String columnOf(EntityDeclaration entity, SymbolId member) {
      if (entity.identity().sourceSymbol().equals(member)) {
         return MySqlIdentifiers.render(entity.identity().columnName());
      }

      for (SpringBootDeclaration.Property field : entity.fields()) {
         if (field.sourceSymbol().equals(member)) {
            return MySqlIdentifiers.render(
               field.columnName().orElseThrow(() -> new IllegalStateException("entity field has no column: " + field.javaName())));
         }
      }

      throw new IllegalStateException("find predicate member is not an entity field: " + member);
   }

   private static String sqlCharacterLiteral(char value) {
      String body = switch (value) {
         case '\'' -> "''";
         case '\\' -> "\\\\";
         default -> String.valueOf(value);
      };
      return "'" + body + "'";
   }

   private static java.util.Iterator<String> predicateNames(String prefix) {
      return java.util.stream.IntStream.range(0, Integer.MAX_VALUE).mapToObj(i -> prefix + i).iterator();
   }

   private static String nextWrapperName(java.util.Iterator<String> counter) {
      return counter.next();
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
         case SpringExpression.IntegralToDecimal value:
            collectExpressionImports(ctx, value.operand(), variableNames, imports, currentPackage);
            break;
         case SpringExpression.OptionalPayloadValue value:
            collectExpressionImports(ctx, value.payloadMember(), variableNames, imports, currentPackage);
            break;
         case UnaryExpression unary:
            collectExpressionImports(ctx, unary.operand(), variableNames, imports, currentPackage);
            break;
         case BinaryExpression binary:
            if (binary.operator() == SpringExpression.BinaryOperator.MATCHES) {
               imports.add(ctx.model().basePackage() + ".persistence.SecretDigest");
            }

            collectExpressionImports(ctx, binary.left(), variableNames, imports, currentPackage);
            collectExpressionImports(ctx, binary.right(), variableNames, imports, currentPackage);
            break;
         case SpringExpression.DigestExpression digest:
            imports.add(ctx.model().basePackage() + ".persistence.SecretDigest");
            collectExpressionImports(ctx, digest.operand(), variableNames, imports, currentPackage);
            break;
         case SpringExpression.TimeShiftExpression shift:
            collectExpressionImports(ctx, shift.base(), variableNames, imports, currentPackage);
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

   record RenderedWorkflow(
      List<String> bodyLines, List<WorkflowRenderer.MapperReference> mappers, List<SpringExpression.StringMatch> stringMatches
   ) {
   }
}
