package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Property;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.VersionSpec;
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
      // Q15: the mapper is an entity-level artifact, so its content is decided by the entity
      // declaration alone. Reading the concurrency token off the entity (rather than looking for a
      // capability that performs a conditional update) is what keeps a remove-capability plan a pure
      // deletion: an entity that declares a version column always carries its compare-and-set helpers.
      Optional<VersionSpec> version = entityDecl.version();
      ImportSorter imports = new ImportSorter();
      imports.add("com.baomidou.mybatisplus.core.mapper.BaseMapper");
      imports.add("org.apache.ibatis.annotations.Mapper");
      if (version.isPresent()) {
         imports.add("org.apache.ibatis.annotations.Param");
         imports.add("org.apache.ibatis.annotations.Select");
         imports.add("org.apache.ibatis.annotations.Update");
      }

      if (!entityArtifact.packageName().equals(packageName)) {
         imports.add(entityFqn);
      }

      String body = renderBody(artifact, entityArtifact, entityDecl, version.orElse(null));
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(entityDecl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static String renderBody(
      SpringArtifact mapperArtifact,
      SpringArtifact entityArtifact,
      EntityDeclaration entityDecl,
      VersionSpec version
   ) {
      StringBuilder out = new StringBuilder();
      out.append("@Mapper\n");
      out.append("public interface ").append(mapperArtifact.simpleName()).append(" extends BaseMapper<").append(entityArtifact.simpleName()).append("> {\n");
      if (version == null) {
         out.append("}\n");
         return out.toString();
      }

      String entityType = entityArtifact.simpleName();
      SpringBootDeclaration.Identity identity = entityDecl.identity();
      out.append('\n');
      out.append("    /**\n");
      out.append("     * Reads the row under a lock so the version this transaction compare-and-sets is the\n");
      out.append("     * version it saw.\n");
      out.append("     */\n");
      out.append("    @Select(\"SELECT * FROM ").append(entityDecl.tableName()).append(" WHERE ")
         .append(identity.columnName()).append(" = #{id} FOR UPDATE\")\n");
      out.append("    ").append(entityType).append(" selectByIdForUpdate(@Param(\"id\") ")
         .append(TypeRenderer.renderBoxedType(identity.type())).append(" id);\n\n");
      out.append("    /**\n");
      out.append("     * Writes the merged candidate only while the row still carries the expected version,\n");
      out.append("     * and advances the version in the same statement.\n");
      out.append("     */\n");
      out.append("    @Update(\"").append(renderConditionalUpdateSql(entityDecl, version)).append("\")\n");
      out.append("    int updateIfVersionMatches(@Param(\"candidate\") ").append(entityType)
         .append(" candidate, @Param(\"expectedVersion\") long expectedVersion);\n");
      out.append("}\n");
      return out.toString();
   }

   /**
    * The conditional update statement: every change column of the entity, and a row match on the
    * identity plus the expected version.
    *
    * <p>The SET list is the entity's change columns — its persistent columns other than the version
    * column — not the columns one capability happens to bind. Only the entity-derived list is
    * independent of which capabilities exist, and writing a column back with the value the candidate
    * row was loaded with is a no-op, so the statement stays semantically equivalent.
    */
   private static String renderConditionalUpdateSql(EntityDeclaration entityDecl, VersionSpec version) {
      StringBuilder sql = new StringBuilder("UPDATE ").append(entityDecl.tableName()).append(" SET ");
      boolean first = true;
      for (Property property : entityDecl.fields()) {
         if (property.columnName().isEmpty() || property.sourceSymbol().equals(version.fieldSymbol())) {
            continue;
         }

         if (!first) {
            sql.append(", ");
         }

         first = false;
         sql.append(property.columnName().get()).append(" = #{candidate.").append(property.javaName()).append('}');
      }

      sql.append(", ").append(version.columnName()).append(" = ").append(version.columnName())
         .append(" + ").append(version.versionIncrement());
      sql.append(" WHERE ").append(entityDecl.identity().columnName()).append(" = #{candidate.")
         .append(entityDecl.identity().javaName()).append('}');
      sql.append(" AND ").append(version.columnName()).append(" = #{expectedVersion}");
      return sql.toString();
   }
}
