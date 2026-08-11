package io.kcg.sir.projectgraph.api;

import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.Objects;

public sealed interface GraphNodeId permits GraphNodeId.ProjectNodeId, GraphNodeId.Semantic, GraphNodeId.Lowered, GraphNodeId.File {
   String canonicalKey();

   record File(String relativePath) implements GraphNodeId {
      public File {
         Objects.requireNonNull(relativePath, "relativePath");
         String violation = GraphNodeId.PathCheck.validate(relativePath);
         if (violation != null) {
            throw new IllegalArgumentException("relativePath invalid: " + violation);
         }
      }

      @Override
      public String canonicalKey() {
         return "file:" + this.relativePath;
      }
   }

   record Lowered(LoweredNodeId nodeId) implements GraphNodeId {
      public Lowered {
         Objects.requireNonNull(nodeId, "nodeId");
      }

      @Override
      public String canonicalKey() {
         return "lowered:" + this.nodeId.value();
      }
   }

   final class PathCheck {
      private PathCheck() {
      }

      static String validate(String path) {
         if (path.startsWith("/")) {
            return "path must not be absolute";
         }

         if (path.length() >= 2 && path.charAt(1) == ':' && Character.isLetter(path.charAt(0))) {
            return "path must not contain drive letter";
         }

         if (path.indexOf(92) >= 0) {
            return "path must not contain backslash";
         }

         String[] segments = path.split("/", -1);

         for (String segment : segments) {
            if (segment.isEmpty()) {
               return "path must not contain empty segment";
            }

            if ("..".equals(segment)) {
               return "path must not contain parent reference";
            }

            if (".".equals(segment)) {
               return "path must not contain current directory reference";
            }
         }

         return null;
      }
   }

   record ProjectNodeId() implements GraphNodeId {
      public static final GraphNodeId.ProjectNodeId INSTANCE = new GraphNodeId.ProjectNodeId();

      @Override
      public String canonicalKey() {
         return "project";
      }

      @Override
      public String toString() {
         return "ProjectNodeId";
      }
   }

   record Semantic(SymbolId symbolId) implements GraphNodeId {
      public Semantic {
         Objects.requireNonNull(symbolId, "symbolId");
      }

      @Override
      public String canonicalKey() {
         return "semantic:" + this.symbolId.value();
      }
   }
}
