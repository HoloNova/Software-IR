package io.kcg.cli;

import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyActorlessReadonlyCapabilityExposure;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.ModifyUnreferencedInputFieldType;
import io.kcg.sir.change.api.RemoveCapability;
import java.util.Map;
import java.util.Optional;

public enum OperationToken {
   MODIFY_CAPABILITY_WORKFLOW("modify-capability-workflow") {
      @Override
      public ChangeOperation create(ChangeTarget t) {
         return new ModifyCapabilityWorkflow(t);
      }
   },
   ADD_CAPABILITY("add-capability") {
      @Override
      public ChangeOperation create(ChangeTarget t) {
         return new AddCapability(t);
      }
   },
   REMOVE_CAPABILITY("remove-capability") {
      @Override
      public ChangeOperation create(ChangeTarget t) {
         return new RemoveCapability(t);
      }
   },
   MODIFY_INPUT_FIELD_CONSTRAINTS("modify-input-field-constraints") {
      @Override
      public ChangeOperation create(ChangeTarget t) {
         return new ModifyInputFieldConstraints(t);
      }
   },
   MODIFY_UNREFERENCED_INPUT_FIELD_TYPE("modify-unreferenced-input-field-type") {
      @Override
      public ChangeOperation create(ChangeTarget t) {
         return new ModifyUnreferencedInputFieldType(t);
      }
   },
   MODIFY_ACTORLESS_READONLY_CAPABILITY_EXPOSURE("modify-actorless-readonly-capability-exposure") {
      @Override
      public ChangeOperation create(ChangeTarget t) {
         return new ModifyActorlessReadonlyCapabilityExposure(t);
      }
   };

   private static final Map<String, OperationToken> BY_TOKEN = Map.of(
      "modify-capability-workflow",
      MODIFY_CAPABILITY_WORKFLOW,
      "add-capability",
      ADD_CAPABILITY,
      "remove-capability",
      REMOVE_CAPABILITY,
      "modify-input-field-constraints",
      MODIFY_INPUT_FIELD_CONSTRAINTS,
      "modify-unreferenced-input-field-type",
      MODIFY_UNREFERENCED_INPUT_FIELD_TYPE,
      "modify-actorless-readonly-capability-exposure",
      MODIFY_ACTORLESS_READONLY_CAPABILITY_EXPOSURE
   );
   private final String token;

   OperationToken(String token) {
      this.token = token;
   }

   public String token() {
      return this.token;
   }

   public static Optional<OperationToken> parse(String token) {
      return Optional.ofNullable(BY_TOKEN.get(token));
   }

   public abstract ChangeOperation create(ChangeTarget var1);

   public static boolean acceptedBy(ChangeIrVersion version, OperationToken op) {
      return switch (version) {
         case V0_1 -> op == MODIFY_CAPABILITY_WORKFLOW;
         case V0_2 -> op == MODIFY_CAPABILITY_WORKFLOW || op == ADD_CAPABILITY;
         case V0_3 -> op == MODIFY_CAPABILITY_WORKFLOW || op == ADD_CAPABILITY || op == REMOVE_CAPABILITY;
         case V0_4 -> op == MODIFY_CAPABILITY_WORKFLOW || op == ADD_CAPABILITY || op == REMOVE_CAPABILITY || op == MODIFY_INPUT_FIELD_CONSTRAINTS;
         case V0_5, V0_6 -> true;
      };
   }
}
