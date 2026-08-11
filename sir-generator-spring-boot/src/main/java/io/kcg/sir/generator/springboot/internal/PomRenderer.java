package io.kcg.sir.generator.springboot.internal;

import io.kcg.sir.generator.springboot.api.GeneratedFile;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenDependency;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenPlugin;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact.MavenProject;
import java.util.Optional;

final class PomRenderer {
   private PomRenderer() {
   }

   static GeneratedFile render(MavenProject project) {
      String content = renderContent(project);
      content = normalizeLf(content);
      return new GeneratedFile(project.path(), content, project.id(), Optional.empty());
   }

   private static String renderContent(MavenProject project) {
      StringBuilder out = new StringBuilder();
      out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      out.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
      out.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
      out.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
      out.append("    <modelVersion>4.0.0</modelVersion>\n");
      out.append('\n');
      out.append("    <parent>\n");
      out.append("        <groupId>org.springframework.boot</groupId>\n");
      out.append("        <artifactId>spring-boot-starter-parent</artifactId>\n");
      out.append("        <version>").append(StringEscape.xml(project.springBootParentVersion())).append("</version>\n");
      out.append("        <relativePath/>\n");
      out.append("    </parent>\n");
      out.append('\n');
      out.append("    <groupId>").append(StringEscape.xml(project.groupId())).append("</groupId>\n");
      out.append("    <artifactId>").append(StringEscape.xml(project.artifactId())).append("</artifactId>\n");
      out.append("    <version>").append(StringEscape.xml(project.version())).append("</version>\n");
      out.append('\n');
      out.append("    <properties>\n");
      out.append("        <java.version>").append(project.javaVersion()).append("</java.version>\n");
      out.append("    </properties>\n");
      out.append('\n');
      out.append("    <dependencies>\n");

      for (MavenDependency dep : project.dependencies()) {
         renderDependency(out, dep);
      }

      out.append("    </dependencies>\n");
      out.append('\n');
      out.append("    <build>\n");
      out.append("        <plugins>\n");

      for (MavenPlugin plugin : project.plugins()) {
         renderPlugin(out, plugin);
      }

      out.append("        </plugins>\n");
      out.append("    </build>\n");
      out.append('\n');
      out.append("</project>\n");
      return out.toString();
   }

   private static void renderDependency(StringBuilder out, MavenDependency dep) {
      out.append("        <dependency>\n");
      out.append("            <groupId>").append(StringEscape.xml(dep.groupId())).append("</groupId>\n");
      out.append("            <artifactId>").append(StringEscape.xml(dep.artifactId())).append("</artifactId>\n");
      if (!dep.version().isBlank()) {
         out.append("            <version>").append(StringEscape.xml(dep.version())).append("</version>\n");
      }

      if (!dep.scope().isBlank() && !"compile".equals(dep.scope())) {
         out.append("            <scope>").append(StringEscape.xml(dep.scope())).append("</scope>\n");
      }

      out.append("        </dependency>\n");
   }

   private static void renderPlugin(StringBuilder out, MavenPlugin plugin) {
      out.append("            <plugin>\n");
      out.append("                <groupId>").append(StringEscape.xml(plugin.groupId())).append("</groupId>\n");
      out.append("                <artifactId>").append(StringEscape.xml(plugin.artifactId())).append("</artifactId>\n");
      out.append("                <version>").append(StringEscape.xml(plugin.version())).append("</version>\n");
      out.append("            </plugin>\n");
   }

   private static String normalizeLf(String content) {
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
