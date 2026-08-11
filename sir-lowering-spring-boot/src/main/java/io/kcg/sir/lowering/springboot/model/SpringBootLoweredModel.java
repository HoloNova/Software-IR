package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.api.LoweredModel;
import io.kcg.sir.lowering.springboot.profile.SpringBootTargetProfile;
import java.util.List;
import java.util.Objects;

public record SpringBootLoweredModel(
   LoweredIrVersion irVersion,
   SpringBootTargetProfile profile,
   String softwareName,
   String displayName,
   String basePackage,
   List<SpringBootDeclaration> declarations,
   List<SpringArtifact> artifacts,
   ProjectArtifact.MavenProject mavenProject,
   ProjectArtifact.ApplicationMain applicationMain
) implements LoweredModel {
   public SpringBootLoweredModel {
      Objects.requireNonNull(irVersion, "irVersion");
      Objects.requireNonNull(profile, "profile");
      requireText(softwareName, "softwareName");
      requireText(displayName, "displayName");
      requireText(basePackage, "basePackage");
      declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
      artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
      Objects.requireNonNull(mavenProject, "mavenProject");
      Objects.requireNonNull(applicationMain, "applicationMain");
   }

   @Override
   public String targetId() {
      return this.profile.id();
   }

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }
}
