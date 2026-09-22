package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.generator.springboot.api.GenerationDiagnostic;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Declared;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.EntityReference;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.ListValue;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.OptionalValue;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType.Scalar;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class GenerationContext {
   private final SpringBootLoweredModel model;
   private final Map<SymbolId, SpringBootDeclaration> declarationsBySymbol = new LinkedHashMap<>();
   private final Map<SymbolId, Map<Role, SpringArtifact>> artifactsByOwner = new LinkedHashMap<>();
   private final List<GeneratedFile> files = new ArrayList<>();
   private final List<GenerationDiagnostic> diagnostics = new ArrayList<>();

   GenerationContext(SpringBootLoweredModel model) {
      this.model = Objects.requireNonNull(model, "model");

      for (SpringBootDeclaration declaration : model.declarations()) {
         this.declarationsBySymbol.put(declaration.sourceSymbol(), declaration);
      }

      for (SpringArtifact artifact : model.artifacts()) {
         this.artifactsByOwner.computeIfAbsent(artifact.ownerSymbol(), k -> new LinkedHashMap<>()).put(artifact.role(), artifact);
      }
   }

   SpringBootLoweredModel model() {
      return this.model;
   }

   SpringBootDeclaration declaration(SymbolId symbol) {
      SpringBootDeclaration value = this.declarationsBySymbol.get(symbol);
      if (value == null) {
         throw new IllegalStateException("no declaration for symbol: " + symbol);
      } else {
         return value;
      }
   }

   Optional<SpringBootDeclaration> tryDeclaration(SymbolId symbol) {
      return Optional.ofNullable(this.declarationsBySymbol.get(symbol));
   }

   EntityDeclaration entity(SymbolId symbol) {
      if (this.declaration(symbol) instanceof EntityDeclaration entity) {
         return entity;
      } else {
         throw new IllegalStateException("symbol is not an entity: " + symbol);
      }
   }

   /**
   * The payload declaration a capability is given.
   *
   * <p>The capability's input is its input variable, whose declared type names the payload; following
   * the resolved type is what keeps the generator from matching anything by name.
   */
   Optional<SpringBootDeclaration.InputDeclaration> payloadOf(SpringBootDeclaration.CapabilityDeclaration capability) {
      return capability
         .input()
         .map(SpringBootWorkflow.Variable::type)
         .filter(Declared.class::isInstance)
         .map(Declared.class::cast)
         .map(Declared::symbolId)
         .map(this::tryDeclaration)
         .flatMap(value -> value)
         .filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
         .map(SpringBootDeclaration.InputDeclaration.class::cast);
   }

   /**
   * The Java type of an entity property.
   *
   * <p>A nullable SIR field is stored as a plain nullable value, not as an {@code Optional}: the
   * persistence layer binds SQL NULL through the driver, and it has no type handler for an
   * {@code Optional} parameter — handing it one fails at runtime rather than at compile time. An
   * {@code Optional} therefore belongs to payloads and views, and the generated code converts between
   * the two forms at the boundary.
   */
   static LoweredJavaType entityPropertyType(LoweredJavaType type) {
      return type instanceof OptionalValue optional ? optional.elementType() : type;
   }

   /** Whether a declared field may hold no value. */
   static boolean isNullable(LoweredJavaType type) {
      return type instanceof OptionalValue;
   }

   /** The patch contract of a capability's payload, when the payload is a patch. */
   Optional<SpringBootDeclaration.PatchSpec> patchSpecOf(SpringBootDeclaration.CapabilityDeclaration capability) {
      return this.payloadOf(capability).map(SpringBootDeclaration.InputDeclaration::patch).flatMap(value -> value);
   }

   SpringArtifact artifact(SymbolId owner, Role role) {
      Map<Role, SpringArtifact> byRole = this.artifactsByOwner.get(owner);
      if (byRole == null) {
         throw new IllegalStateException("no artifact for owner: " + owner);
      } else {
         SpringArtifact artifact = byRole.get(role);
         if (artifact == null) {
            throw new IllegalStateException("no artifact for owner " + owner + " and role " + role);
         } else {
            return artifact;
         }
      }
   }

   void addFile(GeneratedFile file) {
      Objects.requireNonNull(file, "file");
      this.files.add(file);
   }

   void addDiagnostic(GenerationDiagnostic diagnostic) {
      Objects.requireNonNull(diagnostic, "diagnostic");
      this.diagnostics.add(diagnostic);
   }

   void fail(String code, String message, LoweredNodeId nodeId) {
      this.diagnostics.add(GenerationDiagnostic.of(code, message, nodeId));
   }

   boolean hasErrors() {
      return !this.diagnostics.isEmpty();
   }

   List<GeneratedFile> files() {
      return List.copyOf(this.files);
   }

   List<GenerationDiagnostic> diagnostics() {
      return List.copyOf(this.diagnostics);
   }

   String declaredPackage(Declared declared) {
      return switch (declared.kind()) {
         case ENUM, ENTITY -> this.model.basePackage() + ".domain";
            case INPUT, VIEW -> this.model.basePackage() + ".api";
      };
   }

   void collectTypeImports(ImportSorter imports, LoweredJavaType type, String currentPackage) {
      switch (type) {
         case Scalar s:
            TypeRenderer.importFor(s).ifPresent(imports::add);
            break;
         case Declared d:
            String pkg = this.declaredPackage(d);
            if (!pkg.equals(currentPackage)) {
               imports.add(pkg + "." + d.javaName());
            }
            break;
         case OptionalValue o:
            imports.add("java.util.Optional");
            this.collectTypeImports(imports, o.elementType(), currentPackage);
            break;
         case ListValue l:
            imports.add("java.util.List");
            this.collectTypeImports(imports, l.elementType(), currentPackage);
            break;
         case EntityReference ref:
            TypeRenderer.importFor(ref.identityStorageType()).ifPresent(imports::add);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   static String javaPath(String packageName, String simpleName) {
      return "src/main/java/" + packageName.replace('.', '/') + "/" + simpleName + ".java";
   }

   static String assembleSource(String packageName, ImportSorter imports, String body) {
      StringBuilder out = new StringBuilder();
      out.append("package ").append(packageName).append(';').append('\n').append('\n');
      String importBlock = imports.render();
      if (!importBlock.isEmpty()) {
         out.append(importBlock).append('\n').append('\n');
      }

      out.append(body);
      String content = out.toString();
      content = content.replace("\r\n", "\n").replace("\r", "\n");
      int end = content.length();

      while (end >= 2 && content.charAt(end - 1) == '\n' && content.charAt(end - 2) == '\n') {
         end--;
      }

      content = content.substring(0, end);
      if (content.isEmpty() || content.charAt(content.length() - 1) != '\n') {
         content = content + "\n";
      }

      return content;
   }
}
