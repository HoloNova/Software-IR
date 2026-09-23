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

      for (Step step : workflow.steps()) {
         renderStep(ctx, workflow, capability, step, capability.transportPlan().responseRepresentation(), inputName, variableNames, imports, currentPackage, lines, mappers, stringMatches);
      }

      return new WorkflowRenderer.RenderedWorkflow(List.copyOf(lines), List.copyOf(mappers.values()), List.copyOf(stringMatches));
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
            renderPersist(ctx, capability, persist, workflow, imports, currentPackage, lines, mappers);
            break;
         case ReturnStep ret:
            renderReturn(ctx, capability, ret, responseRepresentation, variableNames, imports, currentPackage, lines);
            break;
         default:
            throw new MatchException(null, null);
      }
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
      String resultName = step.result().targetName();
      String errorName = errorJavaName(ctx, step.errorSymbol(), imports, currentPackage);
      String readMethod = step.forUpdate() ? "selectByIdForUpdate" : "selectById";
      lines.add("        var " + resultName + " = " + mapper.mapperFieldName() + "." + readMethod + "(" + idExpr + ");");
      lines.add("        if (" + resultName + " == null) {");
      lines.add("            throw new " + errorName + "();");
      lines.add("        }");
      renderVersionGuard(ctx, capability, step, entity, resultName, imports, currentPackage, lines);
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

      String resultName = step.result().targetName();
      String wrapperChain = renderPredicateChain(ctx, step.predicate(), step.itemVariable(), entity, matches, variableNames, imports, currentPackage)
         + renderOrderClause(entity, step.orderKeys());
      lines.add("        var wrapper = " + wrapperChain + ";");

      if (step.page().isEmpty()) {
      lines.add("        var " + resultName + " = " + mapper.mapperFieldName() + ".selectList(wrapper);");
         return;
   }

      SpringBootWorkflow.PageSpec page = step.page().get();
      if (inputName == null) {
         throw new IllegalStateException("a paged find requires a capability input: " + step.id());
      }

      String pageName = resultName + "Page";
      String sizeName = resultName + "Size";
      lines.add("        int " + pageName + " = " + inputName + "." + getterName(page.pagePropertyName()) + "() == null ? 1 : " + inputName + "." + getterName(page.pagePropertyName()) + "();");
      lines.add("        int " + sizeName + " = " + inputName + "." + getterName(page.sizePropertyName()) + "() == null ? " + page.defaultSize() + " : " + inputName + "." + getterName(page.sizePropertyName()) + "();");
      lines.add("        if (" + pageName + " < 1 || " + pageName + " > " + page.maxPageNumber()
         + " || " + sizeName + " < 1 || " + sizeName + " > " + page.maxSize() + ") {");
      lines.add("            throw new " + errorJavaName(ctx, page.errorSymbol(), imports, currentPackage) + "();");
      lines.add("        }");
      lines.add("        long " + resultName + "Total = " + mapper.mapperFieldName() + ".selectCount(wrapper);");
      String recordsName = resultName + "Records";
      lines.add("        var " + recordsName + " = " + mapper.mapperFieldName() + ".selectList(wrapper.last(\"LIMIT \" + (" + pageName + " - 1) * " + sizeName + " + \", \" + " + sizeName + "));");
      ViewDeclaration view = responseView(ctx, step);
      Map<String, WorkflowRenderer.RelationRead> reads = new LinkedHashMap<>();
      readAssociations(ctx, view, recordsName, reads, imports, currentPackage, lines, mappers, new int[]{0}, "");
      lines.add("        var " + resultName + " = new PageResponse<>(" + resultName + "Total, " + pageName + ", " + sizeName + ", "
         + recordsName + ".stream().map(row -> "
         + renderProjectionRow(ctx, view, "row", reads, "", imports, currentPackage) + ").toList());");
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
      Map<String, WorkflowRenderer.RelationRead> reads,
      ImportSorter imports,
      String currentPackage,
      List<String> lines,
      Map<SymbolId, WorkflowRenderer.MapperReference> mappers,
      int[] counter,
      String path
   ) {
      EntityDeclaration owner = ctx.entity(view.sourceEntitySymbol());
      for (SpringBootDeclaration.ViewField field : view.fields()) {
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
         String keys = "related" + suffix + "Keys";
         String relatedRows = "related" + suffix;
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
               + ", related -> related)") + ");");
         ViewDeclaration targetView = ctx.declaration(plan.targetViewSymbol()) instanceof ViewDeclaration value ? value : null;
         if (targetView == null) {
            throw new IllegalStateException("a relation must target a declared view: " + plan.targetViewSymbol());
         }

         reads.put(site, new WorkflowRenderer.RelationRead(plan, index, "related" + suffix + "Row"));
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
      Map<String, WorkflowRenderer.RelationRead> reads,
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

         SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElse(null);
         if (plan == null) {
            String member = row + "." + getterName(field.sourcePropertyName()) + "()";
            out.append(GenerationContext.isNullable(field.type())
               ? "java.util.Optional.ofNullable(" + member + ")"
               : member);
            continue;
         }

         String site = path.isEmpty() ? field.javaName() : path + "." + field.javaName();
         WorkflowRenderer.RelationRead read = reads.get(site);
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
   private record RelationRead(
      SpringBootDeclaration.ViewRelationPlan plan, String indexVariable, String lambdaName) {
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
      String resultName = step.result().targetName();
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
      String targetName = target.targetName();
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

         for (Step step : candidate.workflow().steps()) {
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
      String targetName = target.targetName();
      EntityDeclaration entity = ctx.entity(declared.symbolId());
      renderCandidateChecks(ctx, capability, step, entity, targetName, imports, currentPackage, lines);
      SpringBootWorkflow.ConditionalUpdate conditional = step.conditional().orElse(null);
      if (conditional == null) {
         if (step.action() == PersistenceAction.INSERT) {
            // A new row starts at the version the target initializes, so the first change can be
            // matched against it.
            entity.version().ifPresent(version -> lines.add("        " + targetName + "."
               + setter(version.javaName()) + "(" + version.initialValue() + "L);"));
            lines.add("        " + mapper.mapperFieldName() + ".insert(" + targetName + ");");
            return;
         }

         lines.add("        " + mapper.mapperFieldName() + ".updateById(" + targetName + ");");
         return;
      }

      String inputName = capability.input().map(Variable::targetName).orElseThrow(
         () -> new IllegalStateException("a conditional update requires a capability input: " + capability.javaName()));
      String expectedVersion = inputName + "." + getterName(expectedVersionPropertyOf(capability, ctx)) + "()";
      String errorName = conditional.failure().isPresent()
         ? errorJavaName(ctx, conditional.failure().orElseThrow(), imports, currentPackage)
         : null;
      String affected = targetName + "Affected";
      lines.add("        int " + affected + " = " + mapper.mapperFieldName() + ".updateIfVersionMatches(" + targetName + ", " + expectedVersion + ");");
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
      String violations = targetName + "Violations";
      lines.add("        List<" + simpleName(fieldError) + "> " + violations + " = new ArrayList<>();");
      for (WorkflowRenderer.CandidateField candidate : candidates) {
         for (SpringBootDeclaration.Constraint constraint : candidate.constraints()) {
            // The check judges the merged value the entity now holds, which is a plain nullable value
            // for a nullable field, so no unwrapping is involved.
            String value = targetName + "." + getterName(candidate.propertyName()) + "()";
            lines.add("        " + simpleName(validationSupport) + "." + constraintCall(
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
      boolean decimal
   ) {
   }

   private static List<WorkflowRenderer.CandidateField> candidateFields(
      GenerationContext ctx, CapabilityDeclaration capability, PersistStep step, EntityDeclaration entity
   ) {
      List<WorkflowRenderer.CandidateField> fields = new ArrayList<>();
      SpringBootDeclaration.PatchSpec patch = ctx.patchSpecOf(capability).orElse(null);
      for (Step candidate : capability.workflow().steps()) {
         if (candidate instanceof CreateStep create && create.result().symbol().equals(step.targetVariable())) {
            for (Binding binding : create.bindings()) {
               fields.add(new WorkflowRenderer.CandidateField(
                  binding.targetProperty(),
                  binding.sourcePropertyName().orElse(binding.targetProperty()),
                  constraintsOf(entity, binding.fieldSymbol()),
                  isDecimal(entity, binding.fieldSymbol())));
            }
         } else if (candidate instanceof UpdateStep update && update.targetVariable().equals(step.targetVariable())
            && update.conditional().isPresent()) {
            for (Binding binding : update.bindings()) {
               String change = payloadPropertyOf(update.conditional().orElseThrow(), binding, ctx);
               fields.add(new WorkflowRenderer.CandidateField(
                  binding.targetProperty(),
                  patch == null ? change : patch.changesPropertyName() + "." + change,
                  constraintsOf(entity, binding.fieldSymbol()),
                  isDecimal(entity, binding.fieldSymbol())));
            }
         }
      }

      return fields;
   }

   private static boolean isDecimal(EntityDeclaration entity, SymbolId fieldSymbol) {
      return entity.fields().stream()
         .filter(field -> field.sourceSymbol().equals(fieldSymbol))
         .map(SpringBootDeclaration.Property::type)
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
         case NOT_BLANK -> "notBlank(" + violations + ", " + pathLiteral + ", " + value + ");";
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
      List<String> lines
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

         String member = value + "." + getterName(field.sourcePropertyName()) + "()";
         // A nullable view field is an Optional, the entity property behind it is plain: the value is
         // wrapped as it crosses over.
         out.append(GenerationContext.isNullable(field.type())
            ? "java.util.Optional.ofNullable(" + member + ")"
            : member);
      }

      return out.append(')').toString();
   }

   private static String renderPredicateChain(
      GenerationContext ctx,
      SpringExpression predicate,
      Variable itemVariable,
      EntityDeclaration entity,
      Map<LoweredNodeId, SpringExpression.StringMatch> stringMatches,
      Map<SymbolId, String> variableNames,
      ImportSorter imports,
      String currentPackage
   ) {
      int[] counter = new int[]{0};
      String rootName = nextWrapperName(counter);
      String body = renderPredicateNode(ctx, predicate, itemVariable, entity, stringMatches, variableNames, imports, currentPackage, rootName, counter);
      return "new LambdaQueryWrapper<" + entity.javaName() + ">()\n                .and(" + rootName + " -> " + body + ")";
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
         case SpringExpression.ExistsPredicate exists:
            // The correlated subquery is decided before rendering: this only quotes its text and
            // binds the values the plan declares, in the order it declares them.
            return wrapperName + ".apply(" + StringEscape.javaString("EXISTS (" + exists.subquerySql() + ")")
               + renderExistsArguments(exists) + ")";
         default:
            throw new IllegalStateException("Unsupported find predicate expression: " + predicate);
      }
   }

   private static String renderExistsArguments(SpringExpression.ExistsPredicate exists) {
      StringBuilder out = new StringBuilder();
      for (SpringExpression.ExistsArgument argument : exists.arguments()) {
         out.append(", ").append(renderExistsArgument(argument));
      }

      return out.toString();
   }

   private static String renderExistsArgument(SpringExpression.ExistsArgument argument) {
      return switch (argument) {
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

   private static String columnOf(EntityDeclaration entity, SymbolId member) {
      if (entity.identity().sourceSymbol().equals(member)) {
         return entity.identity().columnName();
      }

      for (SpringBootDeclaration.Property field : entity.fields()) {
         if (field.sourceSymbol().equals(member)) {
         return field.columnName().orElseThrow(() -> new IllegalStateException("entity field has no column: " + field.javaName()));
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

   record RenderedWorkflow(
      List<String> bodyLines, List<WorkflowRenderer.MapperReference> mappers, List<SpringExpression.StringMatch> stringMatches
   ) {
   }
}
