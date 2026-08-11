package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Constraint;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.InputDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Property;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.List;
import java.util.Optional;

final class DtoRenderer {
   private static final String NOT_BLANK = "jakarta.validation.constraints.NotBlank";
   private static final String EMAIL = "jakarta.validation.constraints.Email";
   private static final String SIZE = "jakarta.validation.constraints.Size";
   private static final String DECIMAL_MIN = "jakarta.validation.constraints.DecimalMin";
   private static final String DECIMAL_MAX = "jakarta.validation.constraints.DecimalMax";

   private DtoRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, InputDeclaration decl) {
      String packageName = artifact.packageName();
      ImportSorter imports = new ImportSorter();

      for (Property field : decl.fields()) {
         ctx.collectTypeImports(imports, field.type(), packageName);
         collectConstraintImports(imports, field.constraints());
      }

      String body = renderBody(decl);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static void collectConstraintImports(ImportSorter imports, List<Constraint> constraints) {
      for (Constraint constraint : constraints) {
         String type = switch (constraint.kind()) {
            case NOT_BLANK -> "jakarta.validation.constraints.NotBlank";
            case EMAIL -> "jakarta.validation.constraints.Email";
            case SIZE -> "jakarta.validation.constraints.Size";
            case DECIMAL_MIN -> "jakarta.validation.constraints.DecimalMin";
            case DECIMAL_MAX -> "jakarta.validation.constraints.DecimalMax";
         };
         imports.add(type);
      }
   }

   private static String renderBody(InputDeclaration decl) {
      StringBuilder out = new StringBuilder();
      out.append("public class ").append(decl.javaName()).append(" {\n\n");

      for (Property field : decl.fields()) {
         renderField(out, field);
      }

      for (Property field : decl.fields()) {
         String fieldType = TypeRenderer.renderBoxedType(field.type());
         renderGetterSetter(out, field.javaName(), fieldType);
      }

      out.append("}\n");
      return out.toString();
   }

   private static void renderField(StringBuilder out, Property field) {
      for (Constraint constraint : field.constraints()) {
         out.append("    ").append(renderConstraint(constraint)).append('\n');
      }

      String javaType = TypeRenderer.renderBoxedType(field.type());
      out.append("    private ").append(javaType).append(' ').append(field.javaName()).append(";\n\n");
   }

   private static String renderConstraint(Constraint constraint) {
      return switch (constraint.kind()) {
         case NOT_BLANK -> "@NotBlank";
         case EMAIL -> "@Email";
         case SIZE -> {
            List<String> args = constraint.arguments();
            if (args.size() != 2) {
               throw new IllegalStateException("SIZE constraint requires 2 arguments, got: " + args);
            }

            yield "@Size(min = " + args.get(0) + ", max = " + args.get(1) + ")";
         }
         case DECIMAL_MIN -> "@DecimalMin(" + StringEscape.javaString(argument(constraint)) + ")";
         case DECIMAL_MAX -> "@DecimalMax(" + StringEscape.javaString(argument(constraint)) + ")";
      };
   }

   private static String argument(Constraint constraint) {
      List<String> args = constraint.arguments();
      if (args.size() != 1) {
         throw new IllegalStateException(constraint.kind() + " constraint requires 1 argument, got: " + args);
      } else {
         return args.get(0);
      }
   }

   private static void renderGetterSetter(StringBuilder out, String propertyName, String javaType) {
      String capitalised = capitalise(propertyName);
      out.append("    public ").append(javaType).append(" get").append(capitalised).append("() {\n");
      out.append("        return ").append(propertyName).append(";\n");
      out.append("    }\n\n");
      out.append("    public void set").append(capitalised).append("(").append(javaType).append(' ').append(propertyName).append(") {\n");
      out.append("        this.").append(propertyName).append(" = ").append(propertyName).append(";\n");
      out.append("    }\n\n");
   }

   private static String capitalise(String value) {
      if (value.isEmpty()) {
         return value;
      }

      int first = value.codePointAt(0);
      String head = new String(Character.toChars(Character.toUpperCase(first)));
      return head + value.substring(Character.charCount(first));
   }
}
