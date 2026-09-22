package io.kcg.sir.projectgraph.api;

public sealed interface ArtifactRole permits ArtifactRole.DeclarationRole, ArtifactRole.ProjectRole {
   enum DeclarationRole implements ArtifactRole {
      ENUM,
      ENTITY_MODEL,
      MAPPER,
      REQUEST_DTO,
      VIEW_DTO,
      EXCEPTION,
      SERVICE,
      CONTROLLER;
   }

   enum ProjectRole implements ArtifactRole {
      MAVEN_PROJECT,
      APPLICATION_MAIN,
      PAGE_RESPONSE,
      API_ERROR_RESPONSE,
      API_EXCEPTION_BASE,
      API_EXCEPTION_ADVICE,
      VALIDATION_SUPPORT,
      APPLICATION_CONFIG;
   }
}
