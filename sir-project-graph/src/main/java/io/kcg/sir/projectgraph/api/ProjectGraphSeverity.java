package io.kcg.sir.projectgraph.api;

public enum ProjectGraphSeverity {
   ERROR,
   WARNING,
   INFO;

   public boolean isError() {
      return this == ERROR;
   }
}
