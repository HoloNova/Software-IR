package io.kcg.sir.projectgraph.api;

import java.util.Objects;
import java.util.Optional;

public record ProjectGraphDiagnostic(String code, ProjectGraphSeverity severity, String message, Optional<GraphNodeId> nodeId, Optional<GraphEdgeId> edgeId) {
   public ProjectGraphDiagnostic {
      Objects.requireNonNull(code, "code");
      Objects.requireNonNull(severity, "severity");
      Objects.requireNonNull(message, "message");
      nodeId = Objects.requireNonNull(nodeId, "nodeId");
      edgeId = Objects.requireNonNull(edgeId, "edgeId");
      if (code.isBlank()) {
         throw new IllegalArgumentException("code must not be blank");
      }

      if (message.isBlank()) {
         throw new IllegalArgumentException("message must not be blank");
      }
   }

   public static ProjectGraphDiagnostic error(String code, String message, GraphNodeId nodeId, GraphEdgeId edgeId) {
      return new ProjectGraphDiagnostic(code, ProjectGraphSeverity.ERROR, message, Optional.ofNullable(nodeId), Optional.ofNullable(edgeId));
   }

   public static ProjectGraphDiagnostic error(String code, String message) {
      return error(code, message, null, null);
   }

   public static ProjectGraphDiagnostic warning(String code, String message, GraphNodeId nodeId, GraphEdgeId edgeId) {
      return new ProjectGraphDiagnostic(code, ProjectGraphSeverity.WARNING, message, Optional.ofNullable(nodeId), Optional.ofNullable(edgeId));
   }

   public static ProjectGraphDiagnostic warning(String code, String message) {
      return warning(code, message, null, null);
   }

   public static ProjectGraphDiagnostic info(String code, String message, GraphNodeId nodeId, GraphEdgeId edgeId) {
      return new ProjectGraphDiagnostic(code, ProjectGraphSeverity.INFO, message, Optional.ofNullable(nodeId), Optional.ofNullable(edgeId));
   }

   public static ProjectGraphDiagnostic info(String code, String message) {
      return info(code, message, null, null);
   }

   public boolean isError() {
      return this.severity.isError();
   }
}
