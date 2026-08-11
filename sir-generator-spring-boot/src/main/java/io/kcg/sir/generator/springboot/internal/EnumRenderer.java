package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EnumDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EnumMember;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

final class EnumRenderer {
   private EnumRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, EnumDeclaration decl) {
      String packageName = artifact.packageName();
      ImportSorter imports = new ImportSorter();
      String body = renderBody(decl);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static String renderBody(EnumDeclaration decl) {
      StringBuilder out = new StringBuilder();
      out.append("public enum ").append(decl.javaName()).append(" {\n");

      for (int i = 0; i < decl.members().size(); i++) {
         EnumMember member = decl.members().get(i);
         out.append("    ").append(member.javaName());
         if (i < decl.members().size() - 1) {
            out.append(",");
         } else {
            out.append(";");
         }

         out.append("\n");
      }

      out.append("}\n");
      return out.toString();
   }
}
