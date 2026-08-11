package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface ProjectArtifact permits ProjectArtifact.MavenProject, ProjectArtifact.ApplicationMain {
   LoweredNodeId id();

   LoweredOrigin origin();

   String path();

   private static void requireText(String value, String name) {
      Objects.requireNonNull(value, name);
      if (value.isBlank()) {
         throw new IllegalArgumentException(name + " must not be blank");
      }
   }

   record ApplicationMain(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String packageName,
      String simpleName,
      String mapperScanPackage,
      Optional<ActorIdentityTransportPlan> actorIdentityTransportPlan
   ) implements ProjectArtifact {
      public ApplicationMain {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(packageName, "packageName");
         ProjectArtifact.requireText(simpleName, "simpleName");
         ProjectArtifact.requireText(mapperScanPackage, "mapperScanPackage");
         Objects.requireNonNull(actorIdentityTransportPlan, "actorIdentityTransportPlan");
      }

      public ApplicationMain(LoweredNodeId id, LoweredOrigin origin, String path, String packageName, String simpleName, String mapperScanPackage) {
         this(id, origin, path, packageName, simpleName, mapperScanPackage, Optional.empty());
      }
   }

   record MavenDependency(String groupId, String artifactId, String version, String scope) {
      public MavenDependency {
         ProjectArtifact.requireText(groupId, "groupId");
         ProjectArtifact.requireText(artifactId, "artifactId");
         Objects.requireNonNull(version, "version");
         Objects.requireNonNull(scope, "scope");
      }
   }

   record MavenPlugin(String groupId, String artifactId, String version) {
      public MavenPlugin {
         ProjectArtifact.requireText(groupId, "groupId");
         ProjectArtifact.requireText(artifactId, "artifactId");
         ProjectArtifact.requireText(version, "version");
      }
   }

   record MavenProject(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String groupId,
      String artifactId,
      String version,
      int javaVersion,
      String springBootParentVersion,
      List<ProjectArtifact.MavenDependency> dependencies,
      List<ProjectArtifact.MavenPlugin> plugins
   ) implements ProjectArtifact {
      public MavenProject {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(groupId, "groupId");
         ProjectArtifact.requireText(artifactId, "artifactId");
         ProjectArtifact.requireText(version, "version");
         if (javaVersion <= 0) {
            throw new IllegalArgumentException("javaVersion must be positive");
         }

         ProjectArtifact.requireText(springBootParentVersion, "springBootParentVersion");
         dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
         plugins = List.copyOf(Objects.requireNonNull(plugins, "plugins"));
      }
   }
}
