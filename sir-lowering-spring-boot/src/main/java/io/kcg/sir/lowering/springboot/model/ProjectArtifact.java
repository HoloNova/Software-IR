package io.kcg.sir.lowering.springboot.model;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface ProjectArtifact
   permits ProjectArtifact.MavenProject,
   ProjectArtifact.ApplicationMain,
   ProjectArtifact.PageResponse,
   ProjectArtifact.ApiErrorResponse,
   ProjectArtifact.ApiExceptionBase,
   ProjectArtifact.ApiExceptionAdvice,
   ProjectArtifact.ValidationSupport,
   ProjectArtifact.ApplicationConfig {
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

   /**
   * The generated generic page envelope returned by paged queries.
   *
   * <p>Paging itself runs as a counted query plus an explicit limit: the frozen dependency set has no
   * MyBatis-Plus pagination interceptor, so no interceptor plan is part of the IR.
   */
   record PageResponse(LoweredNodeId id, LoweredOrigin origin, String path, String packageName, String simpleName) implements ProjectArtifact {
      public PageResponse {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(packageName, "packageName");
         ProjectArtifact.requireText(simpleName, "simpleName");
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

   /**
   * The error envelope every failing request answers with.
   *
   * <p>One artifact declares both the response and its field entries, so a client reads one shape for
   * a declared failure, a rejected payload, and a malformed request.
   */
   record ApiErrorResponse(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String packageName,
      String simpleName,
      String fieldErrorSimpleName,
      String invalidRequestCode
   ) implements ProjectArtifact {
      public ApiErrorResponse {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(packageName, "packageName");
         ProjectArtifact.requireText(simpleName, "simpleName");
         ProjectArtifact.requireText(fieldErrorSimpleName, "fieldErrorSimpleName");
         ProjectArtifact.requireText(invalidRequestCode, "invalidRequestCode");
      }
   }

   /** The base class of the declared failure exceptions, carrying code, status, and field errors. */
   record ApiExceptionBase(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String packageName,
      String simpleName,
      String errorResponseSimpleName,
      String fieldErrorTypeName,
      String invalidRequestCode
   ) implements ProjectArtifact {
      public ApiExceptionBase {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(packageName, "packageName");
         ProjectArtifact.requireText(simpleName, "simpleName");
         ProjectArtifact.requireText(errorResponseSimpleName, "errorResponseSimpleName");
         ProjectArtifact.requireText(fieldErrorTypeName, "fieldErrorTypeName");
         ProjectArtifact.requireText(invalidRequestCode, "invalidRequestCode");
      }
   }

   /** The advice that turns a declared failure or a rejected request into the error envelope. */
   record ApiExceptionAdvice(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String packageName,
      String simpleName,
      String exceptionBaseSimpleName,
      String errorResponseSimpleName,
      String fieldErrorSimpleName,
      String invalidRequestCode
   ) implements ProjectArtifact {
      public ApiExceptionAdvice {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(packageName, "packageName");
         ProjectArtifact.requireText(simpleName, "simpleName");
         ProjectArtifact.requireText(exceptionBaseSimpleName, "exceptionBaseSimpleName");
         ProjectArtifact.requireText(errorResponseSimpleName, "errorResponseSimpleName");
         ProjectArtifact.requireText(fieldErrorSimpleName, "fieldErrorSimpleName");
         ProjectArtifact.requireText(invalidRequestCode, "invalidRequestCode");
      }
   }

   /** The constraint primitives a generated candidate check calls. */
   record ValidationSupport(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String packageName,
      String simpleName,
      String fieldErrorSimpleName,
      List<String> constraintCodes
   ) implements ProjectArtifact {
      public ValidationSupport {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(packageName, "packageName");
         ProjectArtifact.requireText(simpleName, "simpleName");
         ProjectArtifact.requireText(fieldErrorSimpleName, "fieldErrorSimpleName");
         constraintCodes = List.copyOf(Objects.requireNonNull(constraintCodes, "constraintCodes"));
         if (constraintCodes.isEmpty()) {
         throw new IllegalArgumentException("constraintCodes must not be empty");
         }
      }
   }

   /** The generated {@code application.yml}, including the request-decoding strictness. */
   record ApplicationConfig(
      LoweredNodeId id,
      LoweredOrigin origin,
      String path,
      String applicationName,
      boolean rejectUnknownRequestProperties
   ) implements ProjectArtifact {
      public ApplicationConfig {
         Objects.requireNonNull(id, "id");
         Objects.requireNonNull(origin, "origin");
         ProjectArtifact.requireText(path, "path");
         ProjectArtifact.requireText(applicationName, "applicationName");
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
