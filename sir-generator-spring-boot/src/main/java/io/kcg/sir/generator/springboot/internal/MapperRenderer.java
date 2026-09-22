package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact.Role;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
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
      SpringBootWorkflow.ConditionalUpdate conditional = conditionalUpdate(ctx, entityDecl);
      ImportSorter imports = new ImportSorter();
      imports.add("com.baomidou.mybatisplus.core.mapper.BaseMapper");
      imports.add("org.apache.ibatis.annotations.Mapper");
      if (conditional != null) {
         imports.add("org.apache.ibatis.annotations.Param");
         imports.add("org.apache.ibatis.annotations.Select");
         imports.add("org.apache.ibatis.annotations.Update");
      }

      if (!entityArtifact.packageName().equals(packageName)) {
         imports.add(entityFqn);
      }

      String body = renderBody(artifact, entityArtifact, entityDecl, conditional);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(entityDecl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   /**
   * The conditional update this entity takes part in, if any.
   *
   * <p>It is looked up from the lowered workflows rather than inferred from the entity's shape, because
   * the statement's SET list and its version guard both come from that exact plan.
   */
   private static SpringBootWorkflow.ConditionalUpdate conditionalUpdate(GenerationContext ctx, EntityDeclaration entityDecl) {
      for (SpringBootDeclaration declaration : ctx.model().declarations()) {
         if (!(declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability)) {
            continue;
         }

         for (SpringBootWorkflow.Step step : capability.workflow().steps()) {
            Optional<SpringBootWorkflow.ConditionalUpdate> conditional = switch (step) {
               case SpringBootWorkflow.PersistStep persist -> persist.conditional();
               case SpringBootWorkflow.UpdateStep update -> update.conditional();
               default -> Optional.empty();
            };
            if (conditional.isPresent() && conditional.get().entitySymbol().equals(entityDecl.sourceSymbol())) {
               return conditional.get();
            }
         }
      }

      return null;
   }

   private static String renderBody(
      SpringArtifact mapperArtifact,
      SpringArtifact entityArtifact,
      EntityDeclaration entityDecl,
      SpringBootWorkflow.ConditionalUpdate conditional
   ) {
      StringBuilder out = new StringBuilder();
      out.append("@Mapper\n");
      out.append("public interface ").append(mapperArtifact.simpleName()).append(" extends BaseMapper<").append(entityArtifact.simpleName()).append("> {\n");
      if (conditional == null) {
         out.append("}\n");
         return out.toString();
      }

      String entityType = entityArtifact.simpleName();
      out.append('\n');
      out.append("    /**\n");
      out.append("     * Reads the row under a lock so the version this transaction compare-and-sets is the\n");
      out.append("     * version it saw.\n");
      out.append("     */\n");
      out.append("    @Select(\"SELECT * FROM ").append(conditional.tableName()).append(" WHERE ")
         .append(conditional.identityColumnName()).append(" = #{id} FOR UPDATE\")\n");
      out.append("    ").append(entityType).append(" selectByIdForUpdate(@Param(\"id\") ")
         .append(TypeRenderer.renderBoxedType(entityDecl.identity().type())).append(" id);\n\n");
      out.append("    /**\n");
      out.append("     * Writes the merged candidate only while the row still carries the expected version,\n");
      out.append("     * and advances the version in the same statement.\n");
      out.append("     */\n");
      out.append("    @Update(\"").append(renderConditionalUpdateSql(conditional)).append("\")\n");
      out.append("    int updateIfVersionMatches(@Param(\"candidate\") ").append(entityType)
         .append(" candidate, @Param(\"expectedVersion\") long expectedVersion);\n");
      out.append("}\n");
      return out.toString();
   }

   /**
   * The conditional update statement: every change column from the candidate, and a row match on the
   * identity plus the expected version.
   */
   private static String renderConditionalUpdateSql(SpringBootWorkflow.ConditionalUpdate conditional) {
      StringBuilder sql = new StringBuilder("UPDATE ").append(conditional.tableName()).append(" SET ");
      boolean first = true;
      for (SpringBootWorkflow.ColumnAssignment assignment : conditional.assignments()) {
         if (!first) {
            sql.append(", ");
         }

         first = false;
         sql.append(assignment.entityColumnName()).append(" = #{candidate.").append(assignment.entityPropertyName()).append('}');
      }

      sql.append(", ").append(conditional.versionColumnName()).append(" = ").append(conditional.versionColumnName())
         .append(" + ").append(conditional.versionIncrement());
      sql.append(" WHERE ").append(conditional.identityColumnName()).append(" = #{candidate.")
         .append(conditional.identityPropertyName()).append('}');
      sql.append(" AND ").append(conditional.versionColumnName()).append(" = #{expectedVersion}");
      return sql.toString();
   }
}
