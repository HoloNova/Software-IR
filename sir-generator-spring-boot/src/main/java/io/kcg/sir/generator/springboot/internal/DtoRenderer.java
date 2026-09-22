package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
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
      if (decl.patch().isPresent()) {
         return renderPatchPayload(ctx, artifact, decl);
      }

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

   /**
   * Renders the patch envelope: the identity and the expected version beside the change set.
   *
   * <p>The change set records which properties the request actually carried, because that is what
   * distinguishes "leave this field alone" from "set it to null". A property nobody declared, and a
   * member of the envelope that is missing, are rejected rather than ignored, and the rejection names
   * the property so the client can act on it.
   */
   private static GeneratedFile renderPatchPayload(GenerationContext ctx, SpringArtifact artifact, InputDeclaration decl) {
      String packageName = artifact.packageName();
      SpringBootDeclaration.PatchSpec patch = decl.patch().orElseThrow();
      ImportSorter imports = new ImportSorter();
      imports.add("com.fasterxml.jackson.annotation.JsonAnySetter");
      imports.add("jakarta.validation.constraints.NotNull");
      imports.add("com.fasterxml.jackson.annotation.JsonProperty");
      imports.add("java.util.LinkedHashMap");
      imports.add("java.util.List");
      imports.add("java.util.Map");
      WriteSupport support = WriteSupport.of(ctx.model());
      if (!samePackage(support.exceptionBaseFqn(), packageName)) {
         imports.add(support.exceptionBaseFqn());
      }

      for (SpringBootDeclaration.PatchChange change : patch.changes()) {
         Property field = decl.fields().stream()
            .filter(candidate -> candidate.sourceSymbol().equals(change.payloadFieldSymbol()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("patch change has no payload field: " + change.payloadPropertyName()));
         ctx.collectTypeImports(imports, field.type(), packageName);
      }

      String body = renderPatchBody(ctx, decl, patch);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      return new GeneratedFile(path, content, artifact.id(), Optional.of(decl.sourceSymbol()));
   }

   private static String renderPatchBody(GenerationContext ctx, InputDeclaration decl, SpringBootDeclaration.PatchSpec patch) {
      WriteSupport support = WriteSupport.of(ctx.model());
      String simpleBase = simpleName(support.exceptionBaseFqn());
      String changesName = capitalise(patch.changesPropertyName());
      String identityName = patch.identityPropertyName();
      String expectedVersionName = patch.expectedVersionPropertyName();
      Property identityField = decl.fields().stream()
         .filter(candidate -> candidate.sourceSymbol().equals(patch.identityFieldSymbol()))
         .findFirst()
         .orElseThrow();
      StringBuilder out = new StringBuilder();
      out.append("public class ").append(decl.javaName()).append(" {\n\n");
      // The identity and the expected version address the change and state what it is matched against,
      // so a request that omits either is refused by name instead of being answered as a conflict.
      out.append("    @NotNull\n");
      out.append("    private ").append(TypeRenderer.renderBoxedType(identityField.type())).append(' ').append(identityName).append(";\n");
      out.append("    @NotNull\n");
      out.append("    private Long ").append(expectedVersionName).append(";\n");
      out.append("    private ").append(changesName).append(' ').append(patch.changesPropertyName()).append(";\n\n");
      renderGetterSetter(out, identityName, TypeRenderer.renderBoxedType(identityField.type()));
      renderGetterSetter(out, expectedVersionName, "Long");
      renderGetterSetter(out, patch.changesPropertyName(), changesName);
      out.append("    /**\n");
      out.append("     * The requested changes, remembering which properties the request carried.\n");
      out.append("     */\n");
      out.append("    public static final class ").append(changesName).append(" {\n\n");
      out.append("        private final java.util.Set<String> present = new java.util.LinkedHashSet<>();\n");
      for (SpringBootDeclaration.PatchChange change : patch.changes()) {
         Property field = decl.fields().stream()
            .filter(candidate -> candidate.sourceSymbol().equals(change.payloadFieldSymbol()))
            .findFirst()
            .orElseThrow();
         out.append("        private ").append(TypeRenderer.renderBoxedType(field.type())).append(' ')
            .append(change.payloadPropertyName()).append(";\n");
      }

      out.append('\n');
      for (SpringBootDeclaration.PatchChange change : patch.changes()) {
         Property field = decl.fields().stream()
            .filter(candidate -> candidate.sourceSymbol().equals(change.payloadFieldSymbol()))
            .findFirst()
            .orElseThrow();
         out.append("        @JsonProperty(").append(StringEscape.javaString(change.payloadPropertyName())).append(")\n");
         out.append("        public void ").append(change.payloadPropertyName()).append('(')
            .append(TypeRenderer.renderBoxedType(field.type())).append(' ').append(change.payloadPropertyName()).append(") {\n");
         out.append("            this.").append(change.payloadPropertyName()).append(" = ").append(change.payloadPropertyName()).append(";\n");
         out.append("            this.present.add(").append(StringEscape.javaString(change.payloadPropertyName())).append(");\n");
         out.append("        }\n\n");
         out.append("        public ").append(TypeRenderer.renderBoxedType(field.type())).append(" get")
            .append(capitalise(change.payloadPropertyName())).append("() {\n");
         out.append("            return this.").append(change.payloadPropertyName()).append(";\n");
         out.append("        }\n\n");
      }

      out.append("        /** Whether the request carried this change at all. */\n");
      out.append("        public boolean has(String property) {\n");
      out.append("            return this.present.contains(property);\n");
      out.append("        }\n");
      out.append("    }\n");
      out.append("}\n");
      return out.toString();
   }

   private static boolean samePackage(String fqn, String currentPackage) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot >= 0 && fqn.substring(0, lastDot).equals(currentPackage);
   }

   private static String simpleName(String fqn) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
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

   /**
   * Renders one payload field.
   *
   * <p>A nullable field is held as an {@code Optional}, which no constraint validator accepts directly:
   * a constraint on such a field belongs on the optional's element, where Bean Validation expects a
   * container element constraint. Without this a declared length would turn every request into a 500
   * instead of a rejection.
   */
   private static void renderField(StringBuilder out, Property field) {
      if (field.type() instanceof io.kcg.sir.lowering.springboot.model.LoweredJavaType.OptionalValue optional) {
         out.append("    private java.util.Optional<");
         for (Constraint constraint : field.constraints()) {
            out.append(renderConstraint(constraint)).append(' ');
         }

         out.append(TypeRenderer.renderBoxedType(optional.elementType())).append("> ")
            .append(field.javaName()).append(";\n\n");
         return;
      }

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
