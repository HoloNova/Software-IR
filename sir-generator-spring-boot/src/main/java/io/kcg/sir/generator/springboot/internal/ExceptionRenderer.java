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
      WriteSupport support = WriteSupport.of(ctx.model());
      if (!samePackage(support.exceptionBaseFqn(), packageName)) {
         imports.add(support.exceptionBaseFqn());
      }

      String body = renderBody(ctx, decl, packageName);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   /**
   * A declared failure states its own code and status.
   *
   * <p>The code is the SIR error's name, so a client can branch on it, and the status comes from the
   * declaration rather than from a default the renderer picked.
   */
   private static String renderBody(GenerationContext ctx, ErrorDeclaration decl, String currentPackage) {
      WriteSupport support = WriteSupport.of(ctx.model());
      String simpleBase = simpleName(support.exceptionBaseFqn());
      StringBuilder out = new StringBuilder();
      out.append("public class ").append(decl.javaName()).append(" extends ").append(simpleBase).append(" {\n\n");
      out.append("    public ").append(decl.javaName()).append("() {\n");
      out.append("        super(\"").append(sirErrorName(decl)).append("\", HttpStatus.").append(httpStatusName(decl.httpStatus())).append(");\n");
      out.append("    }\n");
      out.append("}\n");
      return out.toString();
   }

   /** The declared error's own name, which is the stable code a client sees. */
   private static String sirErrorName(ErrorDeclaration decl) {
      String symbol = decl.sourceSymbol().value();
      int lastSlash = symbol.lastIndexOf('/');
      return lastSlash < 0 ? symbol : symbol.substring(lastSlash + 1);
   }

   private static boolean samePackage(String fqn, String currentPackage) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot >= 0 && fqn.substring(0, lastDot).equals(currentPackage);
   }

   private static String simpleName(String fqn) {
      int lastDot = fqn.lastIndexOf(46);
      return lastDot < 0 ? fqn : fqn.substring(lastDot + 1);
   }

   private static String httpStatusName(HttpStatus status) {
      return status.name();
   }
}
