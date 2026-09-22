package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.EntityDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Generation;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Identity;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration.Property;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Optional;

final class EntityRenderer {
   private static final String TABLE_ID_TYPE = "com.baomidou.mybatisplus.annotation.IdType";
   private static final String TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId";
   private static final String TABLE_FIELD = "com.baomidou.mybatisplus.annotation.TableField";
   private static final String TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";

   private EntityRenderer() {
   }

   static GeneratedFile render(GenerationContext ctx, SpringArtifact artifact, EntityDeclaration decl) {
      String packageName = artifact.packageName();
      ImportSorter imports = new ImportSorter();
      imports.add("com.baomidou.mybatisplus.annotation.IdType");
      imports.add("com.baomidou.mybatisplus.annotation.TableId");
      imports.add("com.baomidou.mybatisplus.annotation.TableField");
      imports.add("com.baomidou.mybatisplus.annotation.TableName");
      collectIdentityImports(imports, decl.identity(), packageName, ctx);

      for (Property field : decl.fields()) {
         // A nullable field contributes its element type: the column is written as plain NULL.
         ctx.collectTypeImports(imports, GenerationContext.entityPropertyType(field.type()), packageName);
      }

      String body = renderBody(ctx, decl, packageName);
      String content = GenerationContext.assembleSource(packageName, imports, body);
      String path = GenerationContext.javaPath(packageName, artifact.simpleName());
      LoweredNodeId artifactId = artifact.id();
      Optional<SymbolId> symbolId = Optional.of(decl.sourceSymbol());
      return new GeneratedFile(path, content, artifactId, symbolId);
   }

   private static void collectIdentityImports(ImportSorter imports, Identity identity, String currentPackage, GenerationContext ctx) {
      TypeRenderer.importFor(identity.type()).ifPresent(imports::add);
   }

   private static String renderBody(GenerationContext ctx, EntityDeclaration decl, String packageName) {
      StringBuilder out = new StringBuilder();
      out.append("@TableName(").append(StringEscape.javaString(decl.tableName())).append(")\n");
      out.append("public class ").append(decl.javaName()).append(" {\n\n");
      Identity identity = decl.identity();
      String idType = idTypeAnnotation(identity.generation());
      String identityJavaType = TypeRenderer.renderBoxedType(identity.type());
      out.append("    @TableId(value = ").append(StringEscape.javaString(identity.columnName())).append(", type = ").append(idType).append(")\n");
      out.append("    private ").append(identityJavaType).append(' ').append(identity.javaName()).append(";\n\n");

      for (Property field : decl.fields()) {
         renderField(out, field);
      }

      renderGetterSetter(out, identity.javaName(), identityJavaType);

      for (Property field : decl.fields()) {
         String fieldType = TypeRenderer.renderBoxedType(GenerationContext.entityPropertyType(field.type()));
         renderGetterSetter(out, field.javaName(), fieldType);
      }

      out.append("}\n");
      return out.toString();
   }

   private static void renderField(StringBuilder out, Property field) {
      String column = field.columnName().orElseThrow(() -> new IllegalStateException("entity field must have column name: " + field.javaName()));
      String javaType = TypeRenderer.renderBoxedType(GenerationContext.entityPropertyType(field.type()));
      out.append("    @TableField(").append(StringEscape.javaString(column)).append(")\n");
      out.append("    private ").append(javaType).append(' ').append(field.javaName()).append(";\n\n");
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

   private static String idTypeAnnotation(Generation generation) {
      return switch (generation) {
         case AUTO_INCREMENT -> "IdType.AUTO";
         case UUID -> "IdType.ASSIGN_UUID";
      };
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
