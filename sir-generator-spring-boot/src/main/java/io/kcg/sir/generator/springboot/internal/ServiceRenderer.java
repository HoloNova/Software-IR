package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.DeclaredKind;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.TransactionMode;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.TransportPlan.ResponseRepresentation;
import java.util.ArrayList;
import java.util.List;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class ServiceRenderer {
   private static final String SERVICE_ANNOTATION = "org.springframework.stereotype.Service";
   private static final String TRANSACTIONAL_ANNOTATION = "org.springframework.transaction.annotation.Transactional";

   private ServiceRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, CapabilityDeclaration decl) {
      String packageName = artifact.packageName();
      ImportSorter imports = new ImportSorter();
      Map<SymbolId, String> variableNames = buildVariableNames(decl);
      WorkflowRenderer.RenderedWorkflow workflow = WorkflowRenderer.render(ctx, decl, variableNames, imports, packageName);
      imports.add("org.springframework.stereotype.Service");
      if (decl.transactionMode() != TransactionMode.NONE) {
         imports.add("org.springframework.transaction.annotation.Transactional");
      }

      for (WorkflowRenderer.MapperReference mapper : workflow.mappers()) {
         if (!samePackage(mapper.mapperTypeName(), packageName)) {
            imports.add(mapper.mapperTypeName());
         }
      }

      if (decl.input().isPresent()) {
         Variable inputVar = decl.input().get();
         if (inputVar.type() instanceof Declared inputDeclared && inputDeclared.kind() == DeclaredKind.INPUT) {
            SpringArtifact inputArtifact = ctx.artifact(inputDeclared.symbolId(), Role.REQUEST_DTO);
            if (!inputArtifact.packageName().equals(packageName)) {
               imports.add(inputArtifact.packageName() + "." + inputDeclared.javaName());
            }
         }
      }

      ResponseTypeRenderer.collectImports(ctx, imports, decl, packageName);
      String body = renderBody(ctx, artifact, decl, workflow, variableNames);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static Map<SymbolId, String> buildVariableNames(CapabilityDeclaration decl) {
      Map<SymbolId, String> map = new LinkedHashMap<>();
      if (decl.actor().isPresent() && decl.actorBinding().isPresent()) {
         Variable actorVar = decl.actor().get();
         String attributeName = decl.actorBinding().get().attributeName();
         map.put(actorVar.symbol(), attributeName);
      }

      return map;
   }

   private static String renderBody(
      GenerationContext ctx,
      SpringArtifact artifact,
      CapabilityDeclaration decl,
      WorkflowRenderer.RenderedWorkflow workflow,
      Map<SymbolId, String> variableNames
   ) {
      StringBuilder out = new StringBuilder();
      out.append("@Service\n");
      if (decl.transactionMode() == TransactionMode.READ_ONLY) {
         out.append("@Transactional(readOnly = true)\n");
      } else if (decl.transactionMode() == TransactionMode.REQUIRED) {
         out.append("@Transactional\n");
      }

      out.append("public class ").append(decl.serviceName()).append(" {\n\n");

      for (WorkflowRenderer.MapperReference mapper : workflow.mappers()) {
         out.append("    private final ").append(simpleName(mapper.mapperTypeName())).append(' ').append(mapper.mapperFieldName()).append(";\n");
      }

      if (!workflow.mappers().isEmpty()) {
         out.append('\n');
      }

      if (!workflow.mappers().isEmpty()) {
         out.append("    public ").append(decl.serviceName()).append("(");

         for (int i = 0; i < workflow.mappers().size(); i++) {
            if (i > 0) {
               out.append(", ");
            }

            WorkflowRenderer.MapperReference mapper = workflow.mappers().get(i);
            out.append(simpleName(mapper.mapperTypeName())).append(' ').append(mapper.mapperFieldName());
         }

         out.append(") {\n");

         for (WorkflowRenderer.MapperReference mapper : workflow.mappers()) {
            out.append("        this.").append(mapper.mapperFieldName()).append(" = ").append(mapper.mapperFieldName()).append(";\n");
         }

         out.append("    }\n\n");
      }

      String outputType = ResponseTypeRenderer.render(ctx, decl);
      out.append("    public ").append(outputType).append(' ').append(decl.methodName()).append('(');
      boolean firstParam = true;
      if (decl.actor().isPresent() && decl.actorBinding().isPresent()) {
         Variable actorVar = decl.actor().get();
         String actorType = TypeRenderer.renderBoxedType(decl.actorBinding().get().identityStorageType());
         out.append(actorType).append(' ').append(variableNames.get(actorVar.symbol()));
         firstParam = false;
      }

      if (decl.input().isPresent()) {
         if (!firstParam) {
            out.append(", ");
         }

         Variable inputVar = decl.input().get();
         if (inputVar.type() instanceof Declared inputDeclared) {
            out.append(inputDeclared.javaName()).append(' ').append(inputVar.targetName());
         }

         firstParam = false;
      }

      out.append(") {\n");

      for (String line : workflow.bodyLines()) {
         out.append(line).append('\n');
      }

      out.append("    }\n");
      if (!workflow.stringMatches().isEmpty()) {
         out.append('\n');
         renderLikeEscapeHelper(out, workflow.stringMatches().getFirst());
      }

      out.append("}\n");
      return out.toString();
   }

   /**
   * The escape helper makes literal matching literal: without it a requested {@code %} or {@code _}
   * would act as a wildcard. The escape character and the escaped literals come from the lowered
   * match plan rather than from a choice made here.
   */
   private static void renderLikeEscapeHelper(StringBuilder out, SpringExpression.StringMatch match) {
      List<String> conditions = new ArrayList<>();
      for (String literal : match.escapedLiterals()) {
         if (literal.length() == 1) {
         conditions.add("current == " + javaCharacterLiteral(literal.charAt(0)));
         }
      }

      out.append("    private static String escapeLikeLiteral(String value) {\n");
      out.append("        if (value == null) {\n");
      out.append("            return null;\n");
      out.append("        }\n\n");
      out.append("        StringBuilder escaped = new StringBuilder(value.length());\n");
      out.append("        for (int index = 0; index < value.length(); index++) {\n");
      out.append("            char current = value.charAt(index);\n");
      out.append("            if (").append(String.join(" || ", conditions)).append(") {\n");
      out.append("                escaped.append(").append(javaCharacterLiteral(match.escapeCharacter())).append(");\n");
      out.append("            }\n\n");
      out.append("            escaped.append(current);\n");
      out.append("        }\n\n");
      out.append("        return escaped.toString();\n");
      out.append("    }\n");
   }

   private static String javaCharacterLiteral(char value) {
      return switch (value) {
         case '\'' -> "'\\''";
         case '\\' -> "'\\\\'";
         case '\n' -> "'\\n'";
         case '\r' -> "'\\r'";
         case '\t' -> "'\\t'";
         default -> "'" + value + "'";
      };
   }

   private static boolean samePackage(String fqn, String currentPackage) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot < 0 ? false : fqn.substring(0, lastDot).equals(currentPackage);
   }

   private static String simpleName(String fqn) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
   }
}
