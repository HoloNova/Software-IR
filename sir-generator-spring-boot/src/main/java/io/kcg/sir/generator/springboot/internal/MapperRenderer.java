package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

final class MapperRenderer {
   private static final String BASE_MAPPER = "com.baomidou.mybatisplus.core.mapper.BaseMapper";
   private static final String MAPPER_ANNOTATION = "org.apache.ibatis.annotations.Mapper";

   private MapperRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, EntityDeclaration entityDecl) {
      String packageName = artifact.packageName();
      SpringArtifact entityArtifact = ctx.artifact(entityDecl.sourceSymbol(), Role.ENTITY_MODEL);
      String entityFqn = entityArtifact.packageName() + "." + entityArtifact.simpleName();
      ImportSorter imports = new ImportSorter();
      imports.add("com.baomidou.mybatisplus.core.mapper.BaseMapper");
      imports.add("org.apache.ibatis.annotations.Mapper");
      if (!entityArtifact.packageName().equals(packageName)) {
         imports.add(entityFqn);
      }

      String body = renderBody(artifact, entityArtifact);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(entityDecl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static String renderBody(SpringArtifact mapperArtifact, SpringArtifact entityArtifact) {
      StringBuilder out = new StringBuilder();
      out.append("@Mapper\n");
      out.append("public interface ").append(mapperArtifact.simpleName()).append(" extends BaseMapper<").append(entityArtifact.simpleName()).append("> {\n");
      out.append("}\n");
      return out.toString();
   }
}
