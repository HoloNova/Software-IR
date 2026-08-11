package io.kcg.sir.application.internal.changeplanning;

import io.kcg.sir.application.api.ChangePlanningSide;
import io.kcg.sir.application.api.ChangePlanningTarget;
import io.kcg.sir.application.api.ChangePlanningTargetKind;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class TargetCatalogBuilder {
   private static final Comparator<ChangePlanningTarget> CATALOG_ORDER = Comparator.<ChangePlanningTarget>comparingInt(t -> t.side().ordinal())
      .thenComparingInt(t -> t.kind().ordinal())
      .thenComparing(t -> t.target().declarationSymbol().value())
      .thenComparing(t -> t.target().declarationNodeId().value())
      .thenComparing(t -> t.target().targetNodeId().value());

   private TargetCatalogBuilder() {
   }

   public static List<ChangePlanningTarget> build(NormalizedSemanticModel baseModel, NormalizedSemanticModel candidateModel) {
      List<ChangePlanningTarget> targets = new ArrayList<>();
      appendSide(targets, ChangePlanningSide.BASE, baseModel);
      appendSide(targets, ChangePlanningSide.CANDIDATE, candidateModel);
      targets.sort(CATALOG_ORDER);
      Set<String> seenKeys = new HashSet<>();
      Set<String> seenTyped = new HashSet<>();

      for (ChangePlanningTarget t : targets) {
         if (!seenKeys.add(t.targetKey())) {
            throw new IllegalStateException("duplicate target key in catalog: " + t.targetKey());
         }

         String typed = t.side()
            + "|"
            + t.kind()
            + "|"
            + t.target().declarationSymbol().value()
            + "|"
            + t.target().declarationNodeId().value()
            + "|"
            + t.target().targetNodeId().value();
         if (!seenTyped.add(typed)) {
            throw new IllegalStateException("duplicate typed target entry: " + typed);
         }
      }

      return List.copyOf(targets);
   }

   private static void appendSide(List<ChangePlanningTarget> targets, ChangePlanningSide side, NormalizedSemanticModel model) {
      for (NormalizedDeclaration decl : model.declarations()) {
         if (decl instanceof NormalizedCapability cap) {
            appendCapability(targets, side, cap);
         } else if (decl instanceof NormalizedInput input) {
            appendInput(targets, side, input);
         }
      }
   }

   private static void appendCapability(List<ChangePlanningTarget> targets, ChangePlanningSide side, NormalizedCapability cap) {
      SymbolId id = cap.id();
      AstNodeId declNode = cap.sourceNodeId();
      String name = cap.name();
      ChangeTarget declTarget = new ChangeTarget(id, declNode, declNode);
      targets.add(
         new ChangePlanningTarget(
            ChangePlanningHasher.targetKey(side, ChangePlanningTargetKind.CAPABILITY_DECLARATION, declTarget),
            side,
            ChangePlanningTargetKind.CAPABILITY_DECLARATION,
            declTarget,
            name,
            Optional.of(name)
         )
      );
      AstNodeId workflowNode = cap.workflow().sourceNodeId();
      ChangeTarget workflowTarget = new ChangeTarget(id, declNode, workflowNode);
      targets.add(
         new ChangePlanningTarget(
            ChangePlanningHasher.targetKey(side, ChangePlanningTargetKind.CAPABILITY_WORKFLOW, workflowTarget),
            side,
            ChangePlanningTargetKind.CAPABILITY_WORKFLOW,
            workflowTarget,
            name,
            Optional.empty()
         )
      );
   }

   private static void appendInput(List<ChangePlanningTarget> targets, ChangePlanningSide side, NormalizedInput input) {
      SymbolId id = input.id();
      AstNodeId declNode = input.sourceNodeId();
      String inputName = input.name();

      for (NormalizedField field : input.fields()) {
         AstNodeId fieldNode = field.sourceNodeId();
         ChangeTarget fieldTarget = new ChangeTarget(id, declNode, fieldNode);
         targets.add(
            new ChangePlanningTarget(
               ChangePlanningHasher.targetKey(side, ChangePlanningTargetKind.INPUT_FIELD, fieldTarget),
               side,
               ChangePlanningTargetKind.INPUT_FIELD,
               fieldTarget,
               inputName,
               Optional.of(field.name())
            )
         );
      }
   }
}
