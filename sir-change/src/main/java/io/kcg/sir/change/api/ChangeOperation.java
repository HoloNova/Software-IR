package io.kcg.sir.change.api;

public sealed interface ChangeOperation
   permits ModifyCapabilityWorkflow,
   AddCapability,
   RemoveCapability,
   ModifyInputFieldConstraints,
   ModifyUnreferencedInputFieldType,
   ModifyActorlessReadonlyCapabilityExposure {
   ChangeTarget target();
}
