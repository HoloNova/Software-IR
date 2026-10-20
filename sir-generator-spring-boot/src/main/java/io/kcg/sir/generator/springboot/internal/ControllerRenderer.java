package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.DeclaredKind;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.CapabilityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.HttpMethod;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.InputDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Property;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.Variable;
import io.kcg.sir.lowering.springboot.model.TransportPlan.InputBinding;
import io.kcg.sir.lowering.springboot.model.TransportPlan.ResponseRepresentation;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

final class ControllerRenderer {
   private static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
   private static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
   private static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
   private static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
   private static final String REQUEST_ATTRIBUTE = "org.springframework.web.bind.annotation.RequestAttribute";
   private static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";
   private static final String MODEL_ATTRIBUTE = "org.springframework.web.bind.annotation.ModelAttribute";
   private static final String VALID = "jakarta.validation.Valid";

   private ControllerRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, CapabilityDeclaration decl) {
      String packageName = artifact.packageName();
      ImportSorter imports = new ImportSorter();
      imports.add("org.springframework.web.bind.annotation.RestController");
      imports.add("org.springframework.web.bind.annotation.RequestMapping");
      imports.add("org.springframework.web.bind.annotation." + mappingName(decl.httpMethod()));

      if (createsEntity(decl)) {
         imports.add("org.springframework.http.HttpStatus");
         imports.add("org.springframework.web.bind.annotation.ResponseStatus");
      }

      if (decl.actor().isPresent() && decl.actorBinding().isPresent()) {
         imports.add("org.springframework.web.bind.annotation.RequestAttribute");
      }

      TransportPlan transport = decl.transportPlan();
      String inputJavaName = null;
      boolean inputHasConstraints = false;
      if (decl.input().isPresent()) {
         Variable inputVar = decl.input().get();
         if (inputVar.type() instanceof Declared inputDeclared && inputDeclared.kind() == DeclaredKind.INPUT) {
            inputJavaName = inputDeclared.javaName();
            SpringArtifact inputArtifact = ctx.artifact(inputDeclared.symbolId(), Role.REQUEST_DTO);
            if (!inputArtifact.packageName().equals(packageName)) {
               imports.add(inputArtifact.packageName() + "." + inputJavaName);
            }

            inputHasConstraints = inputDeclarationHasConstraints(ctx, inputDeclared.symbolId());
         }

         if (transport.inputBinding() == InputBinding.REQUEST_BODY) {
            imports.add("org.springframework.web.bind.annotation.RequestBody");
         } else {
            imports.add("org.springframework.web.bind.annotation.ModelAttribute");
         }

         if (inputHasConstraints && !validatesAfterPath(decl)) {
            imports.add("jakarta.validation.Valid");
         }
      }

      if (!pathVariables(ctx, decl).isEmpty()) {
         imports.add("org.springframework.web.bind.annotation.PathVariable");
      }

      if (validatesAfterPath(decl) && inputHasConstraints) {
         WriteSupport support = WriteSupport.of(ctx.model());
         imports.add("jakarta.validation.Validator");
         if (!samePackage(support.exceptionBaseFqn(), packageName)) {
            imports.add(support.exceptionBaseFqn());
         }
         if (!samePackage(support.errorResponseFqn(), packageName)) {
            imports.add(support.errorResponseFqn());
         }
      }

      SpringArtifact serviceArtifact = ctx.artifact(decl.sourceSymbol(), Role.SERVICE);
      if (!serviceArtifact.packageName().equals(packageName)) {
         imports.add(serviceArtifact.packageName() + "." + serviceArtifact.simpleName());
      }

      ResponseTypeRenderer.collectImports(ctx, imports, decl, packageName);
      String body = renderBody(ctx, artifact, decl, inputJavaName, inputHasConstraints);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static String renderBody(
      GenerationContext ctx, SpringArtifact artifact, CapabilityDeclaration decl, String inputJavaName, boolean inputHasConstraints
   ) {
      StringBuilder out = new StringBuilder();
      out.append("@RestController\n");
      out.append("@RequestMapping(").append(StringEscape.javaString(decl.route())).append(")\n");
      out.append("public class ").append(decl.controllerName()).append(" {\n\n");
      boolean validator = validatesAfterPath(decl) && inputHasConstraints;
      out.append("    private final ").append(serviceSimpleName(ctx, decl)).append(" service;\n");
      if (validator) {
         out.append("    private final Validator validator;\n");
      }
      out.append('\n');
      out.append("    public ").append(decl.controllerName()).append("(").append(serviceSimpleName(ctx, decl)).append(" service")
         .append(validator ? ", Validator validator" : "").append(") {\n");
      out.append("        this.service = service;\n");
      if (validator) {
         out.append("        this.validator = validator;\n");
      }
      out.append("    }\n\n");
      out.append("    @").append(mappingName(decl.httpMethod())).append('\n');
      if (createsEntity(decl)) {
         out.append("    @ResponseStatus(HttpStatus.CREATED)\n");
      }

      String outputType = ResponseTypeRenderer.render(ctx, decl);
      out.append("    public ").append(outputType).append(' ').append(decl.methodName()).append('(');
      boolean firstParam = true;
      if (decl.actor().isPresent() && decl.actorBinding().isPresent()) {
         String attributeName = decl.actorBinding().get().attributeName();
         String actorType = TypeRenderer.renderBoxedType(decl.actorBinding().get().identityStorageType());
         out.append("@RequestAttribute(").append(StringEscape.javaString(attributeName)).append(") ").append(actorType).append(' ').append(attributeName);
         firstParam = false;
      }

      java.util.List<Property> pathVariables = pathVariables(ctx, decl);
      for (Property variable : pathVariables) {
         if (!firstParam) {
            out.append(", ");
         }

         out.append("@PathVariable(").append(StringEscape.javaString(variable.javaName())).append(") ")
            .append(TypeRenderer.renderBoxedType(variable.type())).append(' ').append(pathParameter(variable));
         firstParam = false;
      }

      if (decl.input().isPresent() && inputJavaName != null) {
         if (!firstParam) {
            out.append(", ");
         }

         Variable inputVar = decl.input().get();
         String bindingAnnotation = decl.transportPlan().inputBinding() == InputBinding.REQUEST_BODY ? "@RequestBody" : "@ModelAttribute";
         if (inputHasConstraints && !validatesAfterPath(decl)) {
            out.append("@Valid ");
         }

         out.append(bindingAnnotation).append(' ').append(inputJavaName).append(' ').append(inputVar.targetName());
      }

      out.append(") {\n");
      if (validatesAfterPath(decl)) {
         // The path's values belong to the input; the body may not override them. The input is checked only once they are
         // in it, with the same field errors a body check gives.
         String inputName = decl.input().orElseThrow().targetName();
         for (Property variable : pathVariables) {
            out.append("        ").append(inputName).append(".set").append(capitalise(variable.javaName())).append('(')
               .append(pathParameter(variable)).append(");\n");
         }
         if (validator) {
            WriteSupport support = WriteSupport.of(ctx.model());
            String fieldError = simpleName(support.errorResponseFqn()) + "." + support.fieldErrorFqn().substring(support.fieldErrorFqn().lastIndexOf('.') + 1);
            out.append("        var violations = validator.validate(").append(inputName).append(");\n");
            out.append("        if (!violations.isEmpty()) {\n");
            out.append("            throw ").append(simpleName(support.exceptionBaseFqn())).append(".invalidRequest(violations.stream()\n");
            out.append("                    .map(violation -> new ").append(fieldError).append("(violation.getPropertyPath().toString(),\n");
            out.append("                            violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName(), violation.getMessage()))\n");
            out.append("                    .toList());\n");
            out.append("        }\n");
         }
      }
      boolean voidResponse = decl.transportPlan().responseRepresentation() == ResponseRepresentation.VOID;
      out.append("        ");
      if (!voidResponse) {
         out.append("return ");
      }

      out.append("service.").append(decl.methodName()).append('(');
      boolean firstArg = true;
      if (decl.actor().isPresent() && decl.actorBinding().isPresent()) {
         out.append(decl.actorBinding().get().attributeName());
         firstArg = false;
      }

      if (decl.input().isPresent()) {
         if (!firstArg) {
            out.append(", ");
         }

         out.append(decl.input().get().targetName());
      }

      out.append(");\n");
      out.append("    }\n");
      out.append("}\n");
      return out.toString();
   }

   /**
   * Whether the capability creates the row it answers with.
   *
   * <p>A creation reports it, so the status is read from the workflow the lowering produced rather than
   * from the capability's name.
   */
   private static boolean createsEntity(CapabilityDeclaration decl) {
      return decl.workflow().steps().stream()
         .anyMatch(step -> step instanceof io.kcg.sir.lowering.springboot.model.SpringBootWorkflow.CreateStep);
   }

   private static String mappingName(HttpMethod method) {
      return switch (method) {
         case GET -> "GetMapping";
         case POST -> "PostMapping";
         case PUT -> "PutMapping";
         case PATCH -> "PatchMapping";
         case DELETE -> "DeleteMapping";
      };
   }

   /** A body request whose path carries some of the input: the path's values are written in, then the input is checked. */
   private static boolean validatesAfterPath(CapabilityDeclaration decl) {
      return decl.transportPlan().inputBinding() == InputBinding.REQUEST_BODY && decl.route().contains("{");
   }

   /** The input fields the route's {name} segments name, in the route's order. */
   private static java.util.List<Property> pathVariables(GenerationContext ctx, CapabilityDeclaration decl) {
      java.util.List<Property> variables = new java.util.ArrayList<>();
      java.util.regex.Matcher segment = java.util.regex.Pattern.compile("\\{([^}]*)}").matcher(decl.route());
      while (segment.find()) {
         String name = segment.group(1);
         Variable input = decl.input().orElseThrow(() -> new IllegalStateException("route variable without an input: " + name));
         InputDeclaration inputDecl = (InputDeclaration) ctx.declaration(((Declared) input.type()).symbolId());
         variables.add(inputDecl.fields().stream().filter(f -> f.javaName().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalStateException("route variable is not an input field: " + name)));
      }
      return variables;
   }

   /** The controller parameter that receives a path value; suffixed so it never shadows the input's own name. */
   private static String pathParameter(Property variable) {
      return variable.javaName() + "FromPath";
   }

   private static String capitalise(String value) {
      return Character.toUpperCase(value.charAt(0)) + value.substring(1);
   }

   private static String simpleName(String fqn) {
      return fqn.substring(fqn.lastIndexOf('.') + 1);
   }

   private static boolean samePackage(String fqn, String packageName) {
      return fqn.substring(0, fqn.lastIndexOf('.')).equals(packageName);
   }

   private static String serviceSimpleName(GenerationContext ctx, CapabilityDeclaration decl) {
      SpringArtifact serviceArtifact = ctx.artifact(decl.sourceSymbol(), Role.SERVICE);
      return serviceArtifact.simpleName();
   }

   /**
   * Whether the request payload has to be validated before the workflow runs.
   *
   * <p>A change set is validated even though none of its changes carries a constraint: the envelope
   * itself declares which members are mandatory.
   */
   private static boolean inputDeclarationHasConstraints(GenerationContext ctx, SymbolId inputSymbolId) {
      if (ctx.declaration(inputSymbolId) instanceof InputDeclaration inputDecl) {
         if (inputDecl.patch().isPresent()) {
            return true;
         }

         for (Property field : inputDecl.fields()) {
            if (!field.constraints().isEmpty()) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }
}
