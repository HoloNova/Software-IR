package io.kcg.sir.projectgraph.api;

public sealed interface ArtifactRole permits ArtifactRole.DeclarationRole, ArtifactRole.ProjectRole {
   enum DeclarationRole implements ArtifactRole {
      ENUM,
      ENTITY_MODEL,
      MAPPER,
      REQUEST_DTO,
      EXCEPTION,
      SERVICE,
      CONTROLLER;
   }

   enum ProjectRole implements ArtifactRole {
      MAVEN_PROJECT,
      APPLICATION_MAIN;
   }
}
