package io.kcg.sir.change.api;

import io.kcg.sir.change.internal.PlannerCore;
import java.util.Objects;

public final class ChangePlanner {
   public ChangeAnalysis plan(ChangePlanningInput input) {
      Objects.requireNonNull(input, "input");
      return PlannerCore.plan(input);
   }
}
