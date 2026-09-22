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
      if (decl.httpMethod() == HttpMethod.POST) {
         imports.add("org.springframework.web.bind.annotation.PostMapping");
      } else if (decl.httpMethod() == HttpMethod.PATCH) {
         imports.add("org.springframework.web.bind.annotation.PatchMapping");
      } else {
         imports.add("org.springframework.web.bind.annotation.GetMapping");
      }

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

         if (inputHasConstraints) {
            imports.add("jakarta.validation.Valid");
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
      out.append("    private final ").append(serviceSimpleName(ctx, decl)).append(" service;\n\n");
      out.append("    public ").append(decl.controllerName()).append("(").append(serviceSimpleName(ctx, decl)).append(" service) {\n");
      out.append("        this.service = service;\n");
      out.append("    }\n\n");
      String httpMapping = switch (decl.httpMethod()) {
         case POST -> "@PostMapping";
         case PATCH -> "@PatchMapping";
         case GET -> "@GetMapping";
      };
      out.append("    ").append(httpMapping).append('\n');
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

      if (decl.input().isPresent() && inputJavaName != null) {
         if (!firstParam) {
            out.append(", ");
         }

         Variable inputVar = decl.input().get();
         String bindingAnnotation = decl.transportPlan().inputBinding() == InputBinding.REQUEST_BODY ? "@RequestBody" : "@ModelAttribute";
         if (inputHasConstraints) {
            out.append("@Valid ");
         }

         out.append(bindingAnnotation).append(' ').append(inputJavaName).append(' ').append(inputVar.targetName());
      }

      out.append(") {\n");
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
