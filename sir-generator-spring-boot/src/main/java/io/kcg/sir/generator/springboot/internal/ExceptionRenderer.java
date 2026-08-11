package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.ErrorDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.HttpStatus;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

final class ExceptionRenderer {
   private ExceptionRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, ErrorDeclaration decl) {
      String packageName = artifact.packageName();
      ImportSorter imports = new ImportSorter();
      imports.add("org.springframework.http.HttpStatus");
      imports.add("org.springframework.web.bind.annotation.ResponseStatus");
      String body = renderBody(decl);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static String renderBody(ErrorDeclaration decl) {
      StringBuilder out = new StringBuilder();
      out.append("@ResponseStatus(HttpStatus.");
      out.append(httpStatusName(decl.httpStatus()));
      out.append(")\n");
      out.append("public class ").append(decl.javaName()).append(" extends RuntimeException {\n\n");
      out.append("    public ").append(decl.javaName()).append("() {\n");
      out.append("        super();\n");
      out.append("    }\n");
      out.append("}\n");
      return out.toString();
   }

   private static String httpStatusName(HttpStatus status) {
      return status.name();
   }
}
