package io.kcg.sir.change.internal;

import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.change.api.AddCapability;
import io.kcg.sir.change.api.ArtifactAddition;
import io.kcg.sir.change.api.ArtifactChange;
import io.kcg.sir.change.api.ArtifactDeletion;
import io.kcg.sir.change.api.ChangeAnalysis;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeDiagnostic;
import io.kcg.sir.change.api.ChangeDiagnosticStage;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeOperation;
import io.kcg.sir.change.api.ChangePlan;
import io.kcg.sir.change.api.ChangePlanningInput;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.FileAddition;
import io.kcg.sir.change.api.FileChange;
import io.kcg.sir.change.api.FileDeletion;
import io.kcg.sir.change.api.ImpactedArtifact;
import io.kcg.sir.change.api.ModifyActorlessReadonlyCapabilityExposure;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.change.api.ModifyInputFieldConstraints;
import io.kcg.sir.change.api.ModifyUnreferencedInputFieldType;
import io.kcg.sir.change.api.NoChangeReason;
import io.kcg.sir.change.api.RemoveCapability;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.projectgraph.api.GraphEdgeId;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphEdge;
import io.kcg.sir.projectgraph.api.ProjectGraphNode;
import io.kcg.sir.projectgraph.api.ArtifactRole.DeclarationRole;
import io.kcg.sir.projectgraph.api.GraphNodeId.Lowered;
import io.kcg.sir.projectgraph.api.GraphNodeId.Semantic;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.Artifact;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.LoweredDeclaration;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.Project;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.ProjectFile;
import io.kcg.sir.projectgraph.api.ProjectGraphNode.SemanticDeclaration;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.api.ReferenceRole;
import io.kcg.sir.semantic.api.ReferenceSiteBinding;
import io.kcg.sir.semantic.api.ReferenceSiteBindings;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class PlannerCore {
   private PlannerCore() {
   }

   public static ChangeAnalysis plan(ChangePlanningInput input) {
      List<ChangeDiagnostic> diagnostics = new ArrayList<>();
      ChangeSet changeSet = input.changeSet();
      ChangeIrVersion version = changeSet.version();
      if (version != ChangeIrVersion.V0_1
         && version != ChangeIrVersion.V0_2
         && version != ChangeIrVersion.V0_3
         && version != ChangeIrVersion.V0_4
         && version != ChangeIrVersion.V0_5
         && version != ChangeIrVersion.V0_6) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-REQUEST-001", ChangeDiagnosticStage.REQUEST, 0, "changeSet.version must be V0_1, V0_2, V0_3, V0_4, V0_5, or V0_6: " + version
            )
         );
         return fail(diagnostics);
      }

      if (changeSet.operations().size() != 1) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-REQUEST-002",
               ChangeDiagnosticStage.REQUEST,
               0,
               "changeSet.operations must contain exactly one element; got " + changeSet.operations().size()
            )
         );
         return fail(diagnostics);
      }

      ChangeOperation operation = changeSet.operations().get(0);
      if (version == ChangeIrVersion.V0_1) {
         if (!(operation instanceof ModifyCapabilityWorkflow)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-REQUEST-003",
                  ChangeDiagnosticStage.REQUEST,
                  0,
                  "changeSet.operations[0] must be ModifyCapabilityWorkflow in v0.1; got " + operation.getClass().getName()
               )
            );
            return fail(diagnostics);
         }
      } else if (version == ChangeIrVersion.V0_2) {
         if (!(operation instanceof ModifyCapabilityWorkflow) && !(operation instanceof AddCapability)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-REQUEST-003",
                  ChangeDiagnosticStage.REQUEST,
                  0,
                  "changeSet.operations[0] must be ModifyCapabilityWorkflow or AddCapability in v0.2; got " + operation.getClass().getName()
               )
            );
            return fail(diagnostics);
         }
      } else if (version == ChangeIrVersion.V0_3) {
         if (!(operation instanceof ModifyCapabilityWorkflow) && !(operation instanceof AddCapability) && !(operation instanceof RemoveCapability)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-REQUEST-003",
                  ChangeDiagnosticStage.REQUEST,
                  0,
                  "changeSet.operations[0] must be ModifyCapabilityWorkflow, AddCapability, or RemoveCapability in v0.3; got " + operation.getClass().getName()
               )
            );
            return fail(diagnostics);
         }
      } else if (version == ChangeIrVersion.V0_4) {
         if (!(operation instanceof ModifyCapabilityWorkflow)
            && !(operation instanceof AddCapability)
            && !(operation instanceof RemoveCapability)
            && !(operation instanceof ModifyInputFieldConstraints)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-REQUEST-003",
                  ChangeDiagnosticStage.REQUEST,
                  0,
                  "changeSet.operations[0] must be ModifyCapabilityWorkflow, AddCapability, RemoveCapability, or ModifyInputFieldConstraints in v0.4; got "
                     + operation.getClass().getName()
               )
            );
            return fail(diagnostics);
         }
      } else if (version == ChangeIrVersion.V0_5) {
         if (!(operation instanceof ModifyCapabilityWorkflow)
            && !(operation instanceof AddCapability)
            && !(operation instanceof RemoveCapability)
            && !(operation instanceof ModifyInputFieldConstraints)
            && !(operation instanceof ModifyUnreferencedInputFieldType)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-REQUEST-003",
                  ChangeDiagnosticStage.REQUEST,
                  0,
                  "changeSet.operations[0] must be ModifyCapabilityWorkflow, AddCapability, RemoveCapability, ModifyInputFieldConstraints, or ModifyUnreferencedInputFieldType in v0.5; got "
                     + operation.getClass().getName()
               )
            );
            return fail(diagnostics);
         }
      } else if (!(operation instanceof ModifyCapabilityWorkflow)
         && !(operation instanceof AddCapability)
         && !(operation instanceof RemoveCapability)
         && !(operation instanceof ModifyInputFieldConstraints)
         && !(operation instanceof ModifyUnreferencedInputFieldType)
         && !(operation instanceof ModifyActorlessReadonlyCapabilityExposure)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-REQUEST-003",
               ChangeDiagnosticStage.REQUEST,
               0,
               "changeSet.operations[0] must be ModifyCapabilityWorkflow, AddCapability, RemoveCapability, ModifyInputFieldConstraints, ModifyUnreferencedInputFieldType, or ModifyActorlessReadonlyCapabilityExposure in v0.6; got "
                  + operation.getClass().getName()
            )
         );
         return fail(diagnostics);
      }

      ChangeBaseRevision basedOn = changeSet.basedOn();
      if (input.baseGraph().version() != basedOn.graphVersion()) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-COMPAT-001",
               ChangeDiagnosticStage.COMPAT,
               0,
               "base graph version " + input.baseGraph().version() + " does not match basedOn.graphVersion " + basedOn.graphVersion()
            )
         );
         return fail(diagnostics);
      } else if (input.candidateGraph().version() != basedOn.graphVersion()) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-COMPAT-001",
               ChangeDiagnosticStage.COMPAT,
               0,
               "candidate graph version " + input.candidateGraph().version() + " does not match basedOn.graphVersion " + basedOn.graphVersion()
            )
         );
         return fail(diagnostics);
      } else {
         SourceId baseGraphSourceId = extractGraphSourceId(input.baseGraph());
         SourceId candidateGraphSourceId = extractGraphSourceId(input.candidateGraph());
         if (!baseGraphSourceId.equals(basedOn.sourceId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-COMPAT-002",
                  ChangeDiagnosticStage.COMPAT,
                  0,
                  "base graph sourceId " + baseGraphSourceId + " does not match basedOn.sourceId " + basedOn.sourceId()
               )
            );
            return fail(diagnostics);
         } else if (!candidateGraphSourceId.equals(basedOn.sourceId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-COMPAT-002",
                  ChangeDiagnosticStage.COMPAT,
                  0,
                  "candidate graph sourceId " + candidateGraphSourceId + " does not match basedOn.sourceId " + basedOn.sourceId()
               )
            );
            return fail(diagnostics);
         } else if (operation instanceof ModifyCapabilityWorkflow modifyOp) {
            return planModifyCapabilityWorkflow(input, changeSet, modifyOp, diagnostics);
         } else if (operation instanceof AddCapability addOp) {
            return planAddCapability(input, changeSet, addOp, diagnostics);
         } else if (operation instanceof RemoveCapability removeOp) {
            return planRemoveCapability(input, changeSet, removeOp, diagnostics);
         } else if (operation instanceof ModifyInputFieldConstraints modifyInputOp) {
            return planModifyInputFieldConstraints(input, changeSet, modifyInputOp, diagnostics);
         } else if (operation instanceof ModifyUnreferencedInputFieldType modifyTypeOp) {
            return planModifyUnreferencedInputFieldType(input, changeSet, modifyTypeOp, diagnostics);
         } else if (operation instanceof ModifyActorlessReadonlyCapabilityExposure modifyExposureOp) {
            return planModifyActorlessReadonlyCapabilityExposure(input, changeSet, modifyExposureOp, diagnostics);
         } else {
            diagnostics.add(
               ChangeDiagnostic.error("SIR-CHANGE-REQUEST-003", ChangeDiagnosticStage.REQUEST, 0, "unsupported operation: " + operation.getClass().getName())
            );
            return fail(diagnostics);
         }
      }
   }

   private static ChangeAnalysis planModifyCapabilityWorkflow(
      ChangePlanningInput input, ChangeSet changeSet, ModifyCapabilityWorkflow modifyOp, List<ChangeDiagnostic> diagnostics
   ) {
      ChangeTarget target = modifyOp.target();
      NormalizedDeclaration baseDeclForTarget = findDeclarationById(input.baseSemanticModel(), target.declarationSymbol());
      if (baseDeclForTarget == null) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-001",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol not found in base semantic model: " + target.declarationSymbol().value()
            )
         );
         return fail(diagnostics);
      } else if (!(baseDeclForTarget instanceof NormalizedCapability baseCapability)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-002",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol is not a Capability in base: "
                  + target.declarationSymbol().value()
                  + " (actual kind: "
                  + kindOf(baseDeclForTarget)
                  + ")"
            )
         );
         return fail(diagnostics);
      } else {
         NormalizedCapability baseCapabilityRef = baseCapability;
         if (!baseCapabilityRef.sourceNodeId().equals(target.declarationNodeId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-003",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.declarationNodeId "
                     + target.declarationNodeId().value()
                     + " does not match base Capability sourceNodeId "
                     + baseCapabilityRef.sourceNodeId().value()
                     + " for symbol "
                     + target.declarationSymbol().value()
               )
            );
            return fail(diagnostics);
         } else if (!baseCapabilityRef.workflow().sourceNodeId().equals(target.targetNodeId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-004",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.targetNodeId "
                     + target.targetNodeId().value()
                     + " does not match base Capability workflow.sourceNodeId "
                     + baseCapabilityRef.workflow().sourceNodeId().value()
                     + " for symbol "
                     + target.declarationSymbol().value()
               )
            );
            return fail(diagnostics);
         } else if (!(findDeclarationById(input.candidateSemanticModel(), target.declarationSymbol()) instanceof NormalizedCapability candidateCapability)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-005",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "candidate Capability with same SymbolId not found (or wrong kind): " + target.declarationSymbol().value()
               )
            );
            return fail(diagnostics);
         } else {
            if (!input.baseSemanticModel().softwareName().equals(input.candidateSemanticModel().softwareName())) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-001",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "softwareName changed from '" + input.baseSemanticModel().softwareName() + "' to '" + input.candidateSemanticModel().softwareName() + "'"
                  )
               );
               return fail(diagnostics);
            }

            if (!SemanticProjection.ofMetadata(input.baseSemanticModel().metadata())
               .equals(SemanticProjection.ofMetadata(input.candidateSemanticModel().metadata()))) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-001", ChangeDiagnosticStage.SCOPE, 0, "metadata changed"));
               return fail(diagnostics);
            }

            if (!SemanticProjection.ofTarget(input.baseSemanticModel().target()).equals(SemanticProjection.ofTarget(input.candidateSemanticModel().target()))) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-001", ChangeDiagnosticStage.SCOPE, 0, "target changed"));
               return fail(diagnostics);
            }

            Map<SymbolId, NormalizedDeclaration> baseDecls = indexDeclarations(input.baseSemanticModel());
            Map<SymbolId, NormalizedDeclaration> candidateDecls = indexDeclarations(input.candidateSemanticModel());
            if (baseDecls.size() != candidateDecls.size()) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-001",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "declaration count changed: base=" + baseDecls.size() + ", candidate=" + candidateDecls.size()
                  )
               );
               return fail(diagnostics);
            }

            for (SymbolId sid : baseDecls.keySet()) {
               if (!candidateDecls.containsKey(sid)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-SCOPE-001", ChangeDiagnosticStage.SCOPE, 0, "declaration removed in candidate: " + sid.value())
                  );
                  return fail(diagnostics);
               }
            }

            for (SymbolId sid : candidateDecls.keySet()) {
               if (!baseDecls.containsKey(sid)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-SCOPE-001", ChangeDiagnosticStage.SCOPE, 0, "declaration added in candidate: " + sid.value())
                  );
                  return fail(diagnostics);
               }
            }

            List<SymbolId> baseOrder = new ArrayList<>(baseDecls.keySet());
            List<SymbolId> candidateOrder = new ArrayList<>(candidateDecls.keySet());
            if (!baseOrder.equals(candidateOrder)) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-001", ChangeDiagnosticStage.SCOPE, 0, "declaration order changed"));
               return fail(diagnostics);
            }

            for (SymbolId sid : baseOrder) {
               if (!sid.equals(target.declarationSymbol())) {
                  NormalizedDeclaration baseDecl = baseDecls.get(sid);
                  NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
                  String baseKind = kindOf(baseDecl);
                  String candidateKind = kindOf(candidateDecl);
                  if (!baseKind.equals(candidateKind)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-001",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "declaration kind changed for " + sid.value() + ": base=" + baseKind + ", candidate=" + candidateKind
                        )
                     );
                     return fail(diagnostics);
                  }

                  SemanticProjection.DeclarationProjection baseProj = SemanticProjection.ofFullDeclaration(baseDecl);
                  SemanticProjection.DeclarationProjection candidateProj = SemanticProjection.ofFullDeclaration(candidateDecl);
                  if (!baseProj.equals(candidateProj)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-001", ChangeDiagnosticStage.SCOPE, 0, "non-target declaration semantically changed: " + sid.value()
                        )
                     );
                     return fail(diagnostics);
                  }
               }
            }

            SemanticProjection.CapabilityContractP baseContract = SemanticProjection.ofCapabilityContract(baseCapability);
            SemanticProjection.CapabilityContractP candidateContract = SemanticProjection.ofCapabilityContract(candidateCapability);
            if (!baseContract.equals(candidateContract)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-002", ChangeDiagnosticStage.SCOPE, 0, "target Capability public contract changed: " + target.declarationSymbol().value()
                  )
               );
               return fail(diagnostics);
            } else {
               ClosureComputer.ClosureResult baseClosureResult = ClosureComputer.compute(input.baseGraph(), target.declarationSymbol());
               if (baseClosureResult instanceof ClosureComputer.ClosureResult.MissingTrace baseMissing) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-IMPACT-001", ChangeDiagnosticStage.IMPACT, 0, "base closure trace incomplete: " + baseMissing.detail())
                  );
                  return fail(diagnostics);
               } else {
                  ClosureComputer.Closure baseClosure = ((ClosureComputer.ClosureResult.Success)baseClosureResult).closure();
                  ClosureComputer.ClosureResult candidateClosureResult = ClosureComputer.compute(input.candidateGraph(), target.declarationSymbol());
                  if (candidateClosureResult instanceof ClosureComputer.ClosureResult.MissingTrace candidateMissing) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-001", ChangeDiagnosticStage.IMPACT, 0, "candidate closure trace incomplete: " + candidateMissing.detail()
                        )
                     );
                     return fail(diagnostics);
                  } else {
                     ClosureComputer.Closure candidateClosure = ((ClosureComputer.ClosureResult.Success)candidateClosureResult).closure();
                     if (baseClosure.artifacts().isEmpty()) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-004",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "base Capability has no SERVICE/CONTROLLER artifacts in closure: " + target.declarationSymbol().value()
                           )
                        );
                        return fail(diagnostics);
                     }

                     Map<LoweredNodeId, Artifact> baseArtifacts = new LinkedHashMap<>();

                     for (Artifact a : baseClosure.artifacts()) {
                        baseArtifacts.put(a.artifactId(), a);
                     }

                     Map<LoweredNodeId, Artifact> candidateArtifacts = new LinkedHashMap<>();

                     for (Artifact a : candidateClosure.artifacts()) {
                        candidateArtifacts.put(a.artifactId(), a);
                     }

                     if (baseArtifacts.size() != candidateArtifacts.size()) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-002",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "closure artifact count changed: base="
                                 + baseArtifacts.size()
                                 + ", candidate="
                                 + candidateArtifacts.size()
                                 + " for "
                                 + target.declarationSymbol().value()
                           )
                        );
                        return fail(diagnostics);
                     }

                     for (LoweredNodeId aid : baseArtifacts.keySet()) {
                        Artifact candidateArtifact = candidateArtifacts.get(aid);
                        if (candidateArtifact == null) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "closure artifact removed in candidate: " + aid.value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Artifact baseArtifact = baseArtifacts.get(aid);
                        if (!baseArtifact.role().equals(candidateArtifact.role())) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-002",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure artifact role changed for "
                                    + aid.value()
                                    + ": base="
                                    + baseArtifact.role()
                                    + ", candidate="
                                    + candidateArtifact.role()
                              )
                           );
                           return fail(diagnostics);
                        }

                        if (!baseArtifact.ownerSymbol().equals(candidateArtifact.ownerSymbol())) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-002", ChangeDiagnosticStage.IMPACT, 0, "closure artifact ownerSymbol changed for " + aid.value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        if (!baseArtifact.qualifiedName().equals(candidateArtifact.qualifiedName())) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-002",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure artifact qualifiedName changed for "
                                    + aid.value()
                                    + ": base="
                                    + baseArtifact.qualifiedName()
                                    + ", candidate="
                                    + candidateArtifact.qualifiedName()
                              )
                           );
                           return fail(diagnostics);
                        }
                     }

                     for (LoweredNodeId aid : candidateArtifacts.keySet()) {
                        if (!baseArtifacts.containsKey(aid)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "closure artifact added in candidate: " + aid.value()
                              )
                           );
                           return fail(diagnostics);
                        }
                     }

                     Map<String, ProjectFile> baseFiles = new LinkedHashMap<>();

                     for (ProjectFile f : baseClosure.files()) {
                        baseFiles.put(f.id().relativePath(), f);
                     }

                     Map<String, ProjectFile> candidateFiles = new LinkedHashMap<>();

                     for (ProjectFile f : candidateClosure.files()) {
                        candidateFiles.put(f.id().relativePath(), f);
                     }

                     if (baseFiles.size() != candidateFiles.size()) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-003",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "closure file count changed: base=" + baseFiles.size() + ", candidate=" + candidateFiles.size()
                           )
                        );
                        return fail(diagnostics);
                     }

                     for (String path : baseFiles.keySet()) {
                        ProjectFile candidateFile = candidateFiles.get(path);
                        if (candidateFile == null) {
                           diagnostics.add(
                              ChangeDiagnostic.error("SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "closure file removed in candidate: " + path)
                           );
                           return fail(diagnostics);
                        }

                        ProjectFile baseFile = baseFiles.get(path);
                        if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                           diagnostics.add(
                              ChangeDiagnostic.error("SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "closure file artifactId changed for " + path)
                           );
                           return fail(diagnostics);
                        }

                        if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                           diagnostics.add(
                              ChangeDiagnostic.error("SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "closure file ownerSymbol changed for " + path)
                           );
                           return fail(diagnostics);
                        }
                     }

                     for (String path : candidateFiles.keySet()) {
                        if (!baseFiles.containsKey(path)) {
                           diagnostics.add(
                              ChangeDiagnostic.error("SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "closure file added in candidate: " + path)
                           );
                           return fail(diagnostics);
                        }
                     }

                     Map<String, ProjectFile> allBaseFiles = indexAllFiles(input.baseGraph());
                     Map<String, ProjectFile> allCandidateFiles = indexAllFiles(input.candidateGraph());
                     if (allBaseFiles.size() != allCandidateFiles.size()) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-003",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "total file count changed: base=" + allBaseFiles.size() + ", candidate=" + allCandidateFiles.size()
                           )
                        );
                        return fail(diagnostics);
                     }

                     for (String path : allBaseFiles.keySet()) {
                        ProjectFile baseFile = allBaseFiles.get(path);
                        ProjectFile candidateFile = allCandidateFiles.get(path);
                        if (candidateFile == null) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "file removed in candidate (outside closure): " + path
                              )
                           );
                           return fail(diagnostics);
                        }

                        if (!baseFiles.containsKey(path)) {
                           if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                              || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-001",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "out-of-closure file changed: "
                                       + path
                                       + " (base sha="
                                       + baseFile.provenance().sha256Hex()
                                       + ", candidate sha="
                                       + candidateFile.provenance().sha256Hex()
                                       + ")"
                                 )
                              );
                              return fail(diagnostics);
                           }

                           if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "out-of-closure file artifactId changed: " + path
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }
                     }

                     for (String path : allCandidateFiles.keySet()) {
                        if (!allBaseFiles.containsKey(path)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-003", ChangeDiagnosticStage.IMPACT, 0, "file added in candidate (outside closure): " + path
                              )
                           );
                           return fail(diagnostics);
                        }
                     }

                     SemanticProjection.WorkflowP baseWorkflowProj = SemanticProjection.ofWorkflowOnly(baseCapability.workflow());
                     SemanticProjection.WorkflowP candidateWorkflowProj = SemanticProjection.ofWorkflowOnly(candidateCapability.workflow());
                     boolean workflowSemanticallyIdentical = baseWorkflowProj.equals(candidateWorkflowProj);
                     List<FileChange> fileChanges = new ArrayList<>();

                     for (String path : baseFiles.keySet()) {
                        ProjectFile baseFile = baseFiles.get(path);
                        ProjectFile candidateFile = candidateFiles.get(path);
                        if (!baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())
                           || baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()) {
                           fileChanges.add(
                              new FileChange(
                                 path,
                                 baseFile.provenance().artifactId(),
                                 baseFile.provenance().ownerSymbol(),
                                 baseFile.provenance().byteCount(),
                                 baseFile.provenance().sha256Hex(),
                                 candidateFile.provenance().byteCount(),
                                 candidateFile.provenance().sha256Hex()
                              )
                           );
                        }
                     }

                     fileChanges.sort(Comparator.comparing(FileChange::relativePath));
                     if (workflowSemanticallyIdentical) {
                        if (fileChanges.isEmpty()) {
                           return new ChangeAnalysis.NoChanges(NoChangeReason.SEMANTICALLY_IDENTICAL, sortAndCopy(diagnostics));
                        }

                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-004",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "workflow semantically identical but closure file bytes differ; this indicates a non-deterministic pipeline"
                           )
                        );
                        return fail(diagnostics);
                     } else {
                        if (fileChanges.isEmpty()) {
                           return new ChangeAnalysis.NoChanges(NoChangeReason.OUTPUT_EQUIVALENT, sortAndCopy(diagnostics));
                        }

                        List<ArtifactChange> artifactChanges = new ArrayList<>();

                        for (Artifact artifact : baseClosure.artifacts()) {
                           LoweredNodeId aid = artifact.artifactId();
                           List<FileChange> ownedChanges = new ArrayList<>();

                           for (FileChange fc : fileChanges) {
                              if (fc.artifactId().equals(aid)) {
                                 ownedChanges.add(fc);
                              }
                           }

                           if (!ownedChanges.isEmpty()) {
                              ImpactedArtifact impacted = new ImpactedArtifact(
                                 aid, artifact.ownerSymbol(), artifact.role(), artifact.qualifiedName(), List.copyOf(ownedChanges)
                              );
                              artifactChanges.add(new ArtifactChange(impacted, List.copyOf(ownedChanges)));
                           }
                        }

                        ChangePlan plan = new ChangePlan(changeSet, List.copyOf(artifactChanges), List.copyOf(fileChanges));
                        return new ChangeAnalysis.Planned(plan, sortAndCopy(diagnostics));
                     }
                  }
               }
            }
         }
      }
   }

   private static ChangeAnalysis planAddCapability(ChangePlanningInput input, ChangeSet changeSet, AddCapability addOp, List<ChangeDiagnostic> diagnostics) {
      ChangeTarget target = addOp.target();
      SymbolId targetSymbol = target.declarationSymbol();
      NormalizedDeclaration baseDeclForTarget = findDeclarationById(input.baseSemanticModel(), targetSymbol);
      if (baseDeclForTarget != null) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-101",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol already exists in base semantic model: " + targetSymbol.value() + " (kind: " + kindOf(baseDeclForTarget) + ")"
            )
         );
         return fail(diagnostics);
      } else if (baseGraphContainsSymbolId(input.baseGraph(), targetSymbol)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-102", ChangeDiagnosticStage.TARGET, 0, "target.declarationSymbol already exists in base graph: " + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else {
         NormalizedDeclaration candidateDeclForTarget = findDeclarationById(input.candidateSemanticModel(), targetSymbol);
         if (!(candidateDeclForTarget instanceof NormalizedCapability candidateCapability)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-103",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "candidate Capability with target SymbolId not found (or wrong kind): "
                     + targetSymbol.value()
                     + (candidateDeclForTarget == null ? " (not present)" : " (actual kind: " + kindOf(candidateDeclForTarget) + ")")
               )
            );
            return fail(diagnostics);
         } else {
            if (!candidateCapability.sourceNodeId().equals(target.declarationNodeId())) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-TARGET-104",
                     ChangeDiagnosticStage.TARGET,
                     0,
                     "target.declarationNodeId "
                        + target.declarationNodeId().value()
                        + " does not match candidate Capability sourceNodeId "
                        + candidateCapability.sourceNodeId().value()
                        + " for symbol "
                        + targetSymbol.value()
                  )
               );
               return fail(diagnostics);
            }

            if (!candidateCapability.workflow().sourceNodeId().equals(target.targetNodeId())) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-TARGET-105",
                     ChangeDiagnosticStage.TARGET,
                     0,
                     "target.targetNodeId "
                        + target.targetNodeId().value()
                        + " does not match candidate Capability workflow.sourceNodeId "
                        + candidateCapability.workflow().sourceNodeId().value()
                        + " for symbol "
                        + targetSymbol.value()
                  )
               );
               return fail(diagnostics);
            }

            if (!input.baseSemanticModel().softwareName().equals(input.candidateSemanticModel().softwareName())) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-101",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "softwareName changed from '" + input.baseSemanticModel().softwareName() + "' to '" + input.candidateSemanticModel().softwareName() + "'"
                  )
               );
               return fail(diagnostics);
            }

            if (!SemanticProjection.ofMetadata(input.baseSemanticModel().metadata())
               .equals(SemanticProjection.ofMetadata(input.candidateSemanticModel().metadata()))) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-101", ChangeDiagnosticStage.SCOPE, 0, "metadata changed"));
               return fail(diagnostics);
            }

            if (!SemanticProjection.ofTarget(input.baseSemanticModel().target()).equals(SemanticProjection.ofTarget(input.candidateSemanticModel().target()))) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-101", ChangeDiagnosticStage.SCOPE, 0, "target changed"));
               return fail(diagnostics);
            }

            Map<SymbolId, NormalizedDeclaration> baseDecls = indexDeclarations(input.baseSemanticModel());
            Map<SymbolId, NormalizedDeclaration> candidateDecls = indexDeclarations(input.candidateSemanticModel());
            int newCount = 0;

            for (SymbolId sid : candidateDecls.keySet()) {
               if (!baseDecls.containsKey(sid)) {
                  newCount++;
               }
            }

            if (newCount != 1) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-101",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "candidate must add exactly one new declaration; got " + newCount + " (target: " + targetSymbol.value() + ")"
                  )
               );
               return fail(diagnostics);
            }

            if (!candidateDecls.containsKey(targetSymbol)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-101", ChangeDiagnosticStage.SCOPE, 0, "candidate's single new declaration is not the target: " + targetSymbol.value()
                  )
               );
               return fail(diagnostics);
            }

            List<SymbolId> baseOrder = new ArrayList<>(baseDecls.keySet());
            List<SymbolId> candidateOrderWithoutTarget = new ArrayList<>();

            for (SymbolId sid : candidateDecls.keySet()) {
               if (!sid.equals(targetSymbol)) {
                  candidateOrderWithoutTarget.add(sid);
               }
            }

            if (!baseOrder.equals(candidateOrderWithoutTarget)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-101", ChangeDiagnosticStage.SCOPE, 0, "existing declaration SymbolId order changed after removing target"
                  )
               );
               return fail(diagnostics);
            }

            for (SymbolId sid : baseOrder) {
               NormalizedDeclaration baseDecl = baseDecls.get(sid);
               NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
               String baseKind = kindOf(baseDecl);
               String candidateKind = kindOf(candidateDecl);
               if (!baseKind.equals(candidateKind)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-102",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "existing declaration kind changed for " + sid.value() + ": base=" + baseKind + ", candidate=" + candidateKind
                     )
                  );
                  return fail(diagnostics);
               }

               SemanticProjection.DeclarationProjection baseProj = SemanticProjection.ofFullDeclaration(baseDecl);
               SemanticProjection.DeclarationProjection candidateProj = SemanticProjection.ofFullDeclaration(candidateDecl);
               if (!baseProj.equals(candidateProj)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-SCOPE-102", ChangeDiagnosticStage.SCOPE, 0, "existing declaration semantically changed: " + sid.value())
                  );
                  return fail(diagnostics);
               }
            }

            String targetName = candidateCapability.name();

            for (SymbolId sid : baseDecls.keySet()) {
               NormalizedDeclaration baseDecl = baseDecls.get(sid);
               if (baseDecl instanceof NormalizedCapability baseCap && baseCap.name().equals(targetName)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-103",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "target Capability name '"
                           + targetName
                           + "' already exists in base with different SymbolId: "
                           + baseCap.id().value()
                           + " (target SymbolId: "
                           + targetSymbol.value()
                           + ")"
                     )
                  );
                  return fail(diagnostics);
               }
            }

            ClosureComputer.ClosureResult candidateClosureResult = ClosureComputer.compute(input.candidateGraph(), targetSymbol);
            if (candidateClosureResult instanceof ClosureComputer.ClosureResult.MissingTrace missing) {
               diagnostics.add(
                  ChangeDiagnostic.error("SIR-CHANGE-IMPACT-101", ChangeDiagnosticStage.IMPACT, 0, "candidate closure trace incomplete: " + missing.detail())
               );
               return fail(diagnostics);
            } else {
               ClosureComputer.Closure candidateClosure = ((ClosureComputer.ClosureResult.Success)candidateClosureResult).closure();
               if (!candidateClosure.artifacts().isEmpty() && !candidateClosure.files().isEmpty()) {
                  Map<LoweredNodeId, LoweredDeclaration> baseLowered = indexLowered(input.baseGraph());
                  Map<LoweredNodeId, LoweredDeclaration> candidateLowered = indexLowered(input.candidateGraph());
                  Map<LoweredNodeId, Artifact> baseArtifacts = indexArtifacts(input.baseGraph());
                  Map<LoweredNodeId, Artifact> candidateArtifacts = indexArtifacts(input.candidateGraph());
                  Map<String, ProjectFile> baseFiles = indexAllFiles(input.baseGraph());
                  Map<String, ProjectFile> candidateFiles = indexAllFiles(input.candidateGraph());
                  Set<LoweredNodeId> closureArtifactIds = new HashSet<>();

                  for (Artifact a : candidateClosure.artifacts()) {
                     closureArtifactIds.add(a.artifactId());
                  }

                  Set<String> closureFilePaths = new HashSet<>();

                  for (ProjectFile f : candidateClosure.files()) {
                     closureFilePaths.add(f.id().relativePath());
                  }

                  Set<LoweredNodeId> closureLoweredIds = new HashSet<>();
                  LoweredNodeId targetLoweredId = findLoweredForSymbol(input.candidateGraph(), targetSymbol);
                  if (targetLoweredId != null) {
                     closureLoweredIds.add(targetLoweredId);
                  }

                  for (LoweredNodeId lid : baseLowered.keySet()) {
                     LoweredDeclaration baseLd = baseLowered.get(lid);
                     LoweredDeclaration candidateLd = candidateLowered.get(lid);
                     if (candidateLd == null) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "base LoweredDeclaration removed in candidate: " + lid.value()
                           )
                        );
                        return fail(diagnostics);
                     }

                     if (!baseLd.sourceSymbol().equals(candidateLd.sourceSymbol())) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "LoweredDeclaration sourceSymbol changed for " + lid.value()
                           )
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (LoweredNodeId lid : candidateLowered.keySet()) {
                     if (!baseLowered.containsKey(lid) && !closureLoweredIds.contains(lid)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "candidate-only LoweredDeclaration outside closure: " + lid.value()
                           )
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (LoweredNodeId aid : baseArtifacts.keySet()) {
                     Artifact baseArt = baseArtifacts.get(aid);
                     Artifact candidateArt = candidateArtifacts.get(aid);
                     if (candidateArt == null) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "base Artifact removed in candidate: " + aid.value()
                           )
                        );
                        return fail(diagnostics);
                     }

                     if (!baseArt.role().equals(candidateArt.role())) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "Artifact role changed for " + aid.value())
                        );
                        return fail(diagnostics);
                     }

                     if (!baseArt.ownerSymbol().equals(candidateArt.ownerSymbol())) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "Artifact ownerSymbol changed for " + aid.value())
                        );
                        return fail(diagnostics);
                     }

                     if (!baseArt.qualifiedName().equals(candidateArt.qualifiedName())) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "Artifact qualifiedName changed for " + aid.value())
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (LoweredNodeId aid : candidateArtifacts.keySet()) {
                     if (!baseArtifacts.containsKey(aid) && !closureArtifactIds.contains(aid)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "candidate-only Artifact outside closure: " + aid.value()
                           )
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (String path : baseFiles.keySet()) {
                     ProjectFile baseFile = baseFiles.get(path);
                     ProjectFile candidateFile = candidateFiles.get(path);
                     if (candidateFile == null) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "base file removed in candidate: " + path)
                        );
                        return fail(diagnostics);
                     }

                     if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "base file artifactId changed for " + path)
                        );
                        return fail(diagnostics);
                     }

                     if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "base file ownerSymbol changed for " + path)
                        );
                        return fail(diagnostics);
                     }

                     if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                        || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-102",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "base file bytes changed for "
                                 + path
                                 + " (base sha="
                                 + baseFile.provenance().sha256Hex()
                                 + ", candidate sha="
                                 + candidateFile.provenance().sha256Hex()
                                 + ")"
                           )
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (String path : candidateFiles.keySet()) {
                     if (!baseFiles.containsKey(path) && !closureFilePaths.contains(path)) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-IMPACT-102", ChangeDiagnosticStage.IMPACT, 0, "candidate-only file outside closure: " + path)
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (Artifact closureArtifact : candidateClosure.artifacts()) {
                     LoweredNodeId aid = closureArtifact.artifactId();
                     if (baseArtifacts.containsKey(aid)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-103",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "closure artifact ArtifactId collides with base artifact: " + aid.value()
                           )
                        );
                        return fail(diagnostics);
                     }

                     Optional<SymbolId> ownerOpt = closureArtifact.ownerSymbol();
                     if (ownerOpt.isPresent()) {
                        SymbolId owner = ownerOpt.get();

                        for (Artifact baseArt : baseArtifacts.values()) {
                           if (baseArt.ownerSymbol().isPresent() && baseArt.ownerSymbol().get().equals(owner)) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-103",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "closure artifact ownerSymbol collides with base artifact: " + owner.value()
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }
                     }

                     String closureQn = closureArtifact.qualifiedName();

                     for (Artifact baseArt : baseArtifacts.values()) {
                        if (baseArt.qualifiedName().equals(closureQn)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-103",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure artifact qualifiedName collides with base artifact: " + closureQn
                              )
                           );
                           return fail(diagnostics);
                        }
                     }
                  }

                  for (ProjectFile closureFile : candidateClosure.files()) {
                     String path = closureFile.id().relativePath();
                     if (baseFiles.containsKey(path)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-103", ChangeDiagnosticStage.IMPACT, 0, "closure file relativePath collides with base file: " + path
                           )
                        );
                        return fail(diagnostics);
                     }

                     String folded = path.toLowerCase(Locale.ROOT);

                     for (String basePath : baseFiles.keySet()) {
                        if (basePath.toLowerCase(Locale.ROOT).equals(folded)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-103",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure file relativePath case-fold collides with base file: " + path + " (base: " + basePath + ")"
                              )
                           );
                           return fail(diagnostics);
                        }
                     }
                  }

                  List<ArtifactAddition> artifactAdditions = new ArrayList<>();
                  List<FileAddition> fileAdditions = new ArrayList<>();

                  for (Artifact artifact : candidateClosure.artifacts()) {
                     List<FileAddition> ownedFiles = new ArrayList<>();

                     for (ProjectFile f : candidateClosure.files()) {
                        if (f.provenance().artifactId().equals(artifact.artifactId())) {
                           FileAddition fa = new FileAddition(
                              f.id().relativePath(),
                              artifact.artifactId(),
                              artifact.ownerSymbol()
                                 .orElseThrow(() -> new IllegalStateException("closure artifact ownerSymbol must be present: " + artifact.artifactId().value())),
                              f.provenance().byteCount(),
                              f.provenance().sha256Hex()
                           );
                           ownedFiles.add(fa);
                        }
                     }

                     if (!ownedFiles.isEmpty()) {
                        ownedFiles.sort(Comparator.comparing(FileAddition::relativePath));
                        if (!(artifact.role() instanceof DeclarationRole dr)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-101",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure artifact role is not DeclarationRole: " + artifact.artifactId().value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        ArtifactAddition var93 = new ArtifactAddition(
                           artifact.artifactId(),
                           artifact.ownerSymbol().orElseThrow(() -> new IllegalStateException("closure artifact ownerSymbol must be present")),
                           dr,
                           artifact.qualifiedName(),
                           List.copyOf(ownedFiles)
                        );
                        artifactAdditions.add(var93);
                        fileAdditions.addAll(ownedFiles);
                     }
                  }

                  artifactAdditions.sort(Comparator.comparing(a -> a.artifactId().value()));
                  fileAdditions.sort(Comparator.comparing(FileAddition::relativePath));
                  ChangePlan plan = new ChangePlan(changeSet, List.of(), List.of(), List.copyOf(artifactAdditions), List.copyOf(fileAdditions));
                  return new ChangeAnalysis.Planned(plan, sortAndCopy(diagnostics));
               } else {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-IMPACT-104",
                        ChangeDiagnosticStage.IMPACT,
                        0,
                        "candidate Capability has no SERVICE/CONTROLLER artifacts/files in closure: " + targetSymbol.value()
                     )
                  );
                  return fail(diagnostics);
               }
            }
         }
      }
   }

   private static ChangeAnalysis planRemoveCapability(
      ChangePlanningInput input, ChangeSet changeSet, RemoveCapability removeOp, List<ChangeDiagnostic> diagnostics
   ) {
      ChangeTarget target = removeOp.target();
      SymbolId targetSymbol = target.declarationSymbol();
      NormalizedDeclaration baseDeclForTarget = findDeclarationById(input.baseSemanticModel(), targetSymbol);
      if (baseDeclForTarget == null) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-201", ChangeDiagnosticStage.TARGET, 0, "target.declarationSymbol not found in base semantic model: " + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else if (!(baseDeclForTarget instanceof NormalizedCapability baseCapability)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-202",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol is not a Capability in base: " + targetSymbol.value() + " (actual kind: " + kindOf(baseDeclForTarget) + ")"
            )
         );
         return fail(diagnostics);
      } else {
         if (!baseCapability.sourceNodeId().equals(target.declarationNodeId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-203",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.declarationNodeId "
                     + target.declarationNodeId().value()
                     + " does not match base Capability sourceNodeId "
                     + baseCapability.sourceNodeId().value()
                     + " for symbol "
                     + targetSymbol.value()
               )
            );
            return fail(diagnostics);
         }

         if (!baseCapability.workflow().sourceNodeId().equals(target.targetNodeId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-204",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.targetNodeId "
                     + target.targetNodeId().value()
                     + " does not match base Capability workflow.sourceNodeId "
                     + baseCapability.workflow().sourceNodeId().value()
                     + " for symbol "
                     + targetSymbol.value()
               )
            );
            return fail(diagnostics);
         }

         NormalizedDeclaration candidateDeclForTarget = findDeclarationById(input.candidateSemanticModel(), targetSymbol);
         if (candidateDeclForTarget != null) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-205",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "candidate still retains target SymbolId (RemoveCapability requires removal): "
                     + targetSymbol.value()
                     + " (kind in candidate: "
                     + kindOf(candidateDeclForTarget)
                     + ")"
               )
            );
            return fail(diagnostics);
         }

         if (!input.baseSemanticModel().softwareName().equals(input.candidateSemanticModel().softwareName())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-SCOPE-201",
                  ChangeDiagnosticStage.SCOPE,
                  0,
                  "softwareName changed from '" + input.baseSemanticModel().softwareName() + "' to '" + input.candidateSemanticModel().softwareName() + "'"
               )
            );
            return fail(diagnostics);
         }

         if (!SemanticProjection.ofMetadata(input.baseSemanticModel().metadata())
            .equals(SemanticProjection.ofMetadata(input.candidateSemanticModel().metadata()))) {
            diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-201", ChangeDiagnosticStage.SCOPE, 0, "metadata changed"));
            return fail(diagnostics);
         }

         if (!SemanticProjection.ofTarget(input.baseSemanticModel().target()).equals(SemanticProjection.ofTarget(input.candidateSemanticModel().target()))) {
            diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-201", ChangeDiagnosticStage.SCOPE, 0, "target changed"));
            return fail(diagnostics);
         }

         Map<SymbolId, NormalizedDeclaration> baseDecls = indexDeclarations(input.baseSemanticModel());
         Map<SymbolId, NormalizedDeclaration> candidateDecls = indexDeclarations(input.candidateSemanticModel());
         List<SymbolId> baseOrderMinusTarget = new ArrayList<>();

         for (SymbolId sid : baseDecls.keySet()) {
            if (!sid.equals(targetSymbol)) {
               baseOrderMinusTarget.add(sid);
            }
         }

         List<SymbolId> candidateOrder = new ArrayList<>(candidateDecls.keySet());
         if (!baseOrderMinusTarget.equals(candidateOrder)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-SCOPE-201",
                  ChangeDiagnosticStage.SCOPE,
                  0,
                  "candidate declaration SymbolId sequence must equal base minus target; expected (base minus target)="
                     + baseOrderMinusTarget
                     + ", candidate="
                     + candidateOrder
               )
            );
            return fail(diagnostics);
         }

         for (SymbolId sid : baseOrderMinusTarget) {
            NormalizedDeclaration baseDecl = baseDecls.get(sid);
            NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
            String baseKind = kindOf(baseDecl);
            String candidateKind = kindOf(candidateDecl);
            if (!baseKind.equals(candidateKind)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-202",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "survivor declaration kind changed for " + sid.value() + ": base=" + baseKind + ", candidate=" + candidateKind
                  )
               );
               return fail(diagnostics);
            }

            SemanticProjection.DeclarationProjection baseProj = SemanticProjection.ofFullDeclaration(baseDecl);
            SemanticProjection.DeclarationProjection candidateProj = SemanticProjection.ofFullDeclaration(candidateDecl);
            if (!baseProj.equals(candidateProj)) {
               diagnostics.add(
                  ChangeDiagnostic.error("SIR-CHANGE-SCOPE-202", ChangeDiagnosticStage.SCOPE, 0, "survivor declaration semantically changed: " + sid.value())
               );
               return fail(diagnostics);
            }
         }

         String targetName = baseCapability.name();

         for (SymbolId sid : candidateDecls.keySet()) {
            NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
            if (candidateDecl instanceof NormalizedCapability candidateCap && candidateCap.name().equals(targetName)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-203",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "removed Capability name '"
                        + targetName
                        + "' reused in candidate with different SymbolId: "
                        + candidateCap.id().value()
                        + " (target SymbolId: "
                        + targetSymbol.value()
                        + ")"
                  )
               );
               return fail(diagnostics);
            }
         }

         ClosureComputer.RemovalClosureResult removalResult = ClosureComputer.computeRemovalClosure(input.baseGraph(), targetSymbol);
         if (removalResult instanceof ClosureComputer.RemovalClosureResult.MissingTrace missing) {
            diagnostics.add(
               ChangeDiagnostic.error("SIR-CHANGE-IMPACT-201", ChangeDiagnosticStage.IMPACT, 0, "base removal closure trace incomplete: " + missing.detail())
            );
            return fail(diagnostics);
         } else {
            ClosureComputer.RemovalClosure removalClosure = ((ClosureComputer.RemovalClosureResult.Success)removalResult).closure();
            LoweredNodeId removedLoweredId = removalClosure.loweredDeclaration().id().nodeId();
            Set<LoweredNodeId> removedArtifactIds = new HashSet<>();

            for (Artifact a : removalClosure.artifacts()) {
               removedArtifactIds.add(a.artifactId());
            }

            Set<String> removedFilePaths = new HashSet<>();

            for (ProjectFile f : removalClosure.files()) {
               removedFilePaths.add(f.id().relativePath());
            }

            Map<LoweredNodeId, LoweredDeclaration> baseLowered = indexLowered(input.baseGraph());
            Map<LoweredNodeId, LoweredDeclaration> candidateLowered = indexLowered(input.candidateGraph());
            Map<LoweredNodeId, Artifact> baseArtifacts = indexArtifacts(input.baseGraph());
            Map<LoweredNodeId, Artifact> candidateArtifacts = indexArtifacts(input.candidateGraph());
            Map<String, ProjectFile> baseFiles = indexAllFiles(input.baseGraph());
            Map<String, ProjectFile> candidateFiles = indexAllFiles(input.candidateGraph());
            if (candidateLowered.containsKey(removedLoweredId)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-IMPACT-204",
                     ChangeDiagnosticStage.IMPACT,
                     0,
                     "removed LoweredDeclaration still present in candidate: " + removedLoweredId.value()
                  )
               );
               return fail(diagnostics);
            }

            for (LoweredNodeId lid : baseLowered.keySet()) {
               if (!lid.equals(removedLoweredId)) {
                  LoweredDeclaration baseLd = baseLowered.get(lid);
                  LoweredDeclaration candidateLd = candidateLowered.get(lid);
                  if (candidateLd == null) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-203",
                           ChangeDiagnosticStage.IMPACT,
                           0,
                           "survivor LoweredDeclaration removed from candidate (outside closure): " + lid.value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!baseLd.sourceSymbol().equals(candidateLd.sourceSymbol())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-202", ChangeDiagnosticStage.IMPACT, 0, "survivor LoweredDeclaration sourceSymbol changed for " + lid.value()
                        )
                     );
                     return fail(diagnostics);
                  }
               }
            }

            for (LoweredNodeId lid : candidateLowered.keySet()) {
               if (!baseLowered.containsKey(lid)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-IMPACT-203", ChangeDiagnosticStage.IMPACT, 0, "candidate-only LoweredDeclaration (outside closure): " + lid.value()
                     )
                  );
                  return fail(diagnostics);
               }
            }

            for (LoweredNodeId aid : baseArtifacts.keySet()) {
               if (removedArtifactIds.contains(aid)) {
                  if (candidateArtifacts.containsKey(aid)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-204", ChangeDiagnosticStage.IMPACT, 0, "removed Artifact still present in candidate: " + aid.value()
                        )
                     );
                     return fail(diagnostics);
                  }
               } else {
                  Artifact baseArt = baseArtifacts.get(aid);
                  Artifact candidateArt = candidateArtifacts.get(aid);
                  if (candidateArt == null) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-203",
                           ChangeDiagnosticStage.IMPACT,
                           0,
                           "survivor Artifact removed from candidate (outside closure): " + aid.value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!baseArt.role().equals(candidateArt.role())) {
                     diagnostics.add(
                        ChangeDiagnostic.error("SIR-CHANGE-IMPACT-202", ChangeDiagnosticStage.IMPACT, 0, "survivor Artifact role changed for " + aid.value())
                     );
                     return fail(diagnostics);
                  }

                  if (!baseArt.ownerSymbol().equals(candidateArt.ownerSymbol())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-202", ChangeDiagnosticStage.IMPACT, 0, "survivor Artifact ownerSymbol changed for " + aid.value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!baseArt.qualifiedName().equals(candidateArt.qualifiedName())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-202", ChangeDiagnosticStage.IMPACT, 0, "survivor Artifact qualifiedName changed for " + aid.value()
                        )
                     );
                     return fail(diagnostics);
                  }
               }
            }

            for (LoweredNodeId aid : candidateArtifacts.keySet()) {
               if (!removedArtifactIds.contains(aid) && !baseArtifacts.containsKey(aid)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-IMPACT-203", ChangeDiagnosticStage.IMPACT, 0, "candidate-only Artifact (outside closure): " + aid.value()
                     )
                  );
                  return fail(diagnostics);
               }
            }

            for (String path : baseFiles.keySet()) {
               if (!removedFilePaths.contains(path)) {
                  ProjectFile baseFile = baseFiles.get(path);
                  ProjectFile candidateFile = candidateFiles.get(path);
                  if (candidateFile == null) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-203", ChangeDiagnosticStage.IMPACT, 0, "survivor ProjectFile removed from candidate (outside closure): " + path
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                     diagnostics.add(
                        ChangeDiagnostic.error("SIR-CHANGE-IMPACT-202", ChangeDiagnosticStage.IMPACT, 0, "survivor ProjectFile artifactId changed for " + path)
                     );
                     return fail(diagnostics);
                  }

                  if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                     diagnostics.add(
                        ChangeDiagnostic.error("SIR-CHANGE-IMPACT-202", ChangeDiagnosticStage.IMPACT, 0, "survivor ProjectFile ownerSymbol changed for " + path)
                     );
                     return fail(diagnostics);
                  }

                  if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                     || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-202",
                           ChangeDiagnosticStage.IMPACT,
                           0,
                           "survivor ProjectFile bytes changed for "
                              + path
                              + " (base sha="
                              + baseFile.provenance().sha256Hex()
                              + ", candidate sha="
                              + candidateFile.provenance().sha256Hex()
                              + ")"
                        )
                     );
                     return fail(diagnostics);
                  }
               } else if (candidateFiles.containsKey(path)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-IMPACT-204", ChangeDiagnosticStage.IMPACT, 0, "removed ProjectFile still present in candidate: " + path)
                  );
                  return fail(diagnostics);
               }
            }

            for (String path : candidateFiles.keySet()) {
               if (!removedFilePaths.contains(path) && !baseFiles.containsKey(path)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-IMPACT-203", ChangeDiagnosticStage.IMPACT, 0, "candidate-only ProjectFile (outside closure): " + path)
                  );
                  return fail(diagnostics);
               }
            }

            List<ArtifactDeletion> artifactDeletions = new ArrayList<>();
            List<FileDeletion> fileDeletions = new ArrayList<>();

            for (Artifact artifact : removalClosure.artifacts()) {
               List<FileDeletion> ownedFiles = new ArrayList<>();

               for (ProjectFile f : removalClosure.files()) {
                  if (f.provenance().artifactId().equals(artifact.artifactId())) {
                     FileDeletion fd = new FileDeletion(
                        f.id().relativePath(),
                        artifact.artifactId(),
                        artifact.ownerSymbol()
                           .orElseThrow(() -> new IllegalStateException("closure artifact ownerSymbol must be present: " + artifact.artifactId().value())),
                        f.provenance().byteCount(),
                        f.provenance().sha256Hex()
                     );
                     ownedFiles.add(fd);
                  }
               }

               if (!ownedFiles.isEmpty()) {
                  ownedFiles.sort(Comparator.comparing(FileDeletion::relativePath));
                  if (!(artifact.role() instanceof DeclarationRole dr)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-201",
                           ChangeDiagnosticStage.IMPACT,
                           0,
                           "removal closure artifact role is not DeclarationRole: " + artifact.artifactId().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  ArtifactDeletion var73 = new ArtifactDeletion(
                     artifact.artifactId(),
                     artifact.ownerSymbol().orElseThrow(() -> new IllegalStateException("closure artifact ownerSymbol must be present")),
                     dr,
                     artifact.qualifiedName(),
                     List.copyOf(ownedFiles)
                  );
                  artifactDeletions.add(var73);
                  fileDeletions.addAll(ownedFiles);
               }
            }

            artifactDeletions.sort(Comparator.comparing(a -> a.artifactId().value()));
            fileDeletions.sort(Comparator.comparing(FileDeletion::relativePath));
            ChangePlan plan = new ChangePlan(changeSet, List.of(), List.of(), List.of(), List.of(), List.copyOf(artifactDeletions), List.copyOf(fileDeletions));
            return new ChangeAnalysis.Planned(plan, sortAndCopy(diagnostics));
         }
      }
   }

   private static ChangeAnalysis planModifyInputFieldConstraints(
      ChangePlanningInput input, ChangeSet changeSet, ModifyInputFieldConstraints modifyInputOp, List<ChangeDiagnostic> diagnostics
   ) {
      ChangeTarget target = modifyInputOp.target();
      SymbolId targetSymbol = target.declarationSymbol();
      NormalizedDeclaration baseDeclForTarget = findDeclarationById(input.baseSemanticModel(), targetSymbol);
      if (baseDeclForTarget == null) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-301", ChangeDiagnosticStage.TARGET, 0, "target.declarationSymbol not found in base semantic model: " + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else if (!(baseDeclForTarget instanceof NormalizedInput baseInput)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-302",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol is not an Input in base: " + targetSymbol.value() + " (actual kind: " + kindOf(baseDeclForTarget) + ")"
            )
         );
         return fail(diagnostics);
      } else {
         if (!baseInput.sourceNodeId().equals(target.declarationNodeId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-303",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.declarationNodeId "
                     + target.declarationNodeId().value()
                     + " does not match base Input sourceNodeId "
                     + baseInput.sourceNodeId().value()
                     + " for symbol "
                     + targetSymbol.value()
               )
            );
            return fail(diagnostics);
         }

         NormalizedField baseTargetField = null;

         for (NormalizedField f : baseInput.fields()) {
            if (f.sourceNodeId().equals(target.targetNodeId())) {
               baseTargetField = f;
               break;
            }
         }

         if (baseTargetField == null) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-304",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.targetNodeId "
                     + target.targetNodeId().value()
                     + " does not match any base Input field sourceNodeId for symbol "
                     + targetSymbol.value()
                     + " (field count: "
                     + baseInput.fields().size()
                     + ")"
               )
            );
            return fail(diagnostics);
         } else {
            NormalizedDeclaration candidateDeclForTarget = findDeclarationById(input.candidateSemanticModel(), targetSymbol);
            if (!(candidateDeclForTarget instanceof NormalizedInput candidateInput)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-TARGET-305",
                     ChangeDiagnosticStage.TARGET,
                     0,
                     "candidate Input with same SymbolId not found (or wrong kind): "
                        + targetSymbol.value()
                        + (candidateDeclForTarget == null ? " (not found)" : " (actual kind: " + kindOf(candidateDeclForTarget) + ")")
                  )
               );
               return fail(diagnostics);
            } else {
               NormalizedField candidateTargetField = null;

               for (NormalizedField f : candidateInput.fields()) {
                  if (f.sourceNodeId().equals(target.targetNodeId())) {
                     candidateTargetField = f;
                     break;
                  }
               }

               if (candidateTargetField == null) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-TARGET-306",
                        ChangeDiagnosticStage.TARGET,
                        0,
                        "candidate Input has no field with sourceNodeId == target.targetNodeId "
                           + target.targetNodeId().value()
                           + " for symbol "
                           + targetSymbol.value()
                           + " (candidate field count: "
                           + candidateInput.fields().size()
                           + ")"
                     )
                  );
                  return fail(diagnostics);
               }

               if (!input.baseSemanticModel().softwareName().equals(input.candidateSemanticModel().softwareName())) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-301",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "softwareName changed from '"
                           + input.baseSemanticModel().softwareName()
                           + "' to '"
                           + input.candidateSemanticModel().softwareName()
                           + "'"
                     )
                  );
                  return fail(diagnostics);
               }

               if (!SemanticProjection.ofMetadata(input.baseSemanticModel().metadata())
                  .equals(SemanticProjection.ofMetadata(input.candidateSemanticModel().metadata()))) {
                  diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-301", ChangeDiagnosticStage.SCOPE, 0, "metadata changed"));
                  return fail(diagnostics);
               }

               if (!SemanticProjection.ofTarget(input.baseSemanticModel().target())
                  .equals(SemanticProjection.ofTarget(input.candidateSemanticModel().target()))) {
                  diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-301", ChangeDiagnosticStage.SCOPE, 0, "target changed"));
                  return fail(diagnostics);
               }

               Map<SymbolId, NormalizedDeclaration> baseDecls = indexDeclarations(input.baseSemanticModel());
               Map<SymbolId, NormalizedDeclaration> candidateDecls = indexDeclarations(input.candidateSemanticModel());
               if (baseDecls.size() != candidateDecls.size()) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-302",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "declaration count changed: base=" + baseDecls.size() + ", candidate=" + candidateDecls.size()
                     )
                  );
                  return fail(diagnostics);
               }

               for (SymbolId sid : baseDecls.keySet()) {
                  if (!candidateDecls.containsKey(sid)) {
                     diagnostics.add(
                        ChangeDiagnostic.error("SIR-CHANGE-SCOPE-302", ChangeDiagnosticStage.SCOPE, 0, "declaration removed in candidate: " + sid.value())
                     );
                     return fail(diagnostics);
                  }
               }

               for (SymbolId sid : candidateDecls.keySet()) {
                  if (!baseDecls.containsKey(sid)) {
                     diagnostics.add(
                        ChangeDiagnostic.error("SIR-CHANGE-SCOPE-302", ChangeDiagnosticStage.SCOPE, 0, "declaration added in candidate: " + sid.value())
                     );
                     return fail(diagnostics);
                  }
               }

               List<SymbolId> baseOrder = new ArrayList<>(baseDecls.keySet());
               List<SymbolId> candidateOrder = new ArrayList<>(candidateDecls.keySet());
               if (!baseOrder.equals(candidateOrder)) {
                  diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-302", ChangeDiagnosticStage.SCOPE, 0, "declaration order changed"));
                  return fail(diagnostics);
               }

               for (SymbolId sid : baseOrder) {
                  NormalizedDeclaration baseDecl = baseDecls.get(sid);
                  NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
                  String baseKind = kindOf(baseDecl);
                  String candidateKind = kindOf(candidateDecl);
                  if (!baseKind.equals(candidateKind)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-302",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "declaration kind changed for " + sid.value() + ": base=" + baseKind + ", candidate=" + candidateKind
                        )
                     );
                     return fail(diagnostics);
                  }
               }

               for (SymbolId sid : baseOrder) {
                  if (!sid.equals(targetSymbol)) {
                     NormalizedDeclaration baseDecl = baseDecls.get(sid);
                     NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
                     SemanticProjection.DeclarationProjection baseProj = SemanticProjection.ofFullDeclaration(baseDecl);
                     SemanticProjection.DeclarationProjection candidateProj = SemanticProjection.ofFullDeclaration(candidateDecl);
                     if (!baseProj.equals(candidateProj)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-SCOPE-303", ChangeDiagnosticStage.SCOPE, 0, "non-target declaration semantically changed: " + sid.value()
                           )
                        );
                        return fail(diagnostics);
                     }
                  }
               }

               SemanticProjection.InputContractP baseInputContract = SemanticProjection.ofInputContract(baseInput);
               SemanticProjection.InputContractP candidateInputContract = SemanticProjection.ofInputContract(candidateInput);
               if (!baseInputContract.equals(candidateInputContract)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-304",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "target Input contract changed (id/name/sourceNodeId/field count/field order/per-field identity): " + targetSymbol.value()
                     )
                  );
                  return fail(diagnostics);
               }

               for (int i = 0; i < baseInput.fields().size(); i++) {
                  NormalizedField baseField = baseInput.fields().get(i);
                  if (!baseField.sourceNodeId().equals(target.targetNodeId())) {
                     NormalizedField candidateField = candidateInput.fields().get(i);
                     SemanticProjection.FieldP baseFieldProj = SemanticProjection.ofNonTargetField(baseField);
                     SemanticProjection.FieldP candidateFieldProj = SemanticProjection.ofNonTargetField(candidateField);
                     if (!baseFieldProj.equals(candidateFieldProj)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-SCOPE-304",
                              ChangeDiagnosticStage.SCOPE,
                              0,
                              "non-target Input field semantically changed: "
                                 + baseField.id().value()
                                 + " (field sourceNodeId: "
                                 + baseField.sourceNodeId().value()
                                 + ")"
                           )
                        );
                        return fail(diagnostics);
                     }
                  }
               }

               ReferenceSiteBindings baseRefs = input.baseSemanticModel().referenceSiteBindings();
               ReferenceSiteBindings candidateRefs = input.candidateSemanticModel().referenceSiteBindings();
               SemanticProjection.TypedReferenceSiteProjection baseRefProj = SemanticProjection.ofTypedReferenceSites(baseRefs);
               SemanticProjection.TypedReferenceSiteProjection candidateRefProj = SemanticProjection.ofTypedReferenceSites(candidateRefs);
               if (!baseRefProj.equals(candidateRefProj)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-304",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "typed reference-site projection changed (site AstNodeId / role / target SymbolId)"
                     )
                  );
                  return fail(diagnostics);
               } else {
                  ClosureComputer.InputClosureResult baseClosureResult = ClosureComputer.computeInputClosure(input.baseGraph(), targetSymbol);
                  if (baseClosureResult instanceof ClosureComputer.InputClosureResult.MissingTrace baseMissing) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-301", ChangeDiagnosticStage.IMPACT, 0, "base Input closure trace incomplete: " + baseMissing.detail()
                        )
                     );
                     return fail(diagnostics);
                  } else {
                     ClosureComputer.InputClosure baseClosure = ((ClosureComputer.InputClosureResult.Success)baseClosureResult).closure();
                     ClosureComputer.InputClosureResult candidateClosureResult = ClosureComputer.computeInputClosure(input.candidateGraph(), targetSymbol);
                     if (candidateClosureResult instanceof ClosureComputer.InputClosureResult.MissingTrace candidateMissing) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-301",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "candidate Input closure trace incomplete: " + candidateMissing.detail()
                           )
                        );
                        return fail(diagnostics);
                     } else {
                        ClosureComputer.InputClosure candidateClosure = ((ClosureComputer.InputClosureResult.Success)candidateClosureResult).closure();
                        if (!baseClosure.loweredDeclaration().sourceSymbol().equals(candidateClosure.loweredDeclaration().sourceSymbol())) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-302",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure LoweredDeclaration sourceSymbol changed for " + baseClosure.loweredDeclaration().id().nodeId().value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Map<LoweredNodeId, Artifact> baseArtifacts = new LinkedHashMap<>();

                        for (Artifact a : baseClosure.artifacts()) {
                           baseArtifacts.put(a.artifactId(), a);
                        }

                        Map<LoweredNodeId, Artifact> candidateArtifacts = new LinkedHashMap<>();

                        for (Artifact a : candidateClosure.artifacts()) {
                           candidateArtifacts.put(a.artifactId(), a);
                        }

                        if (baseArtifacts.size() != candidateArtifacts.size()) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-303",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure artifact count changed (graph node drift): base="
                                    + baseArtifacts.size()
                                    + ", candidate="
                                    + candidateArtifacts.size()
                                    + " for "
                                    + targetSymbol.value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        for (LoweredNodeId aid : baseArtifacts.keySet()) {
                           Artifact candidateArtifact = candidateArtifacts.get(aid);
                           if (candidateArtifact == null) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "closure artifact removed in candidate: " + aid.value()
                                 )
                              );
                              return fail(diagnostics);
                           }

                           Artifact baseArtifact = baseArtifacts.get(aid);
                           if (!baseArtifact.role().equals(candidateArtifact.role())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-302",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "closure artifact role changed for "
                                       + aid.value()
                                       + ": base="
                                       + baseArtifact.role()
                                       + ", candidate="
                                       + candidateArtifact.role()
                                 )
                              );
                              return fail(diagnostics);
                           }

                           if (!baseArtifact.ownerSymbol().equals(candidateArtifact.ownerSymbol())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-302", ChangeDiagnosticStage.IMPACT, 0, "closure artifact ownerSymbol changed for " + aid.value()
                                 )
                              );
                              return fail(diagnostics);
                           }

                           if (!baseArtifact.qualifiedName().equals(candidateArtifact.qualifiedName())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-302",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "closure artifact qualifiedName changed for "
                                       + aid.value()
                                       + ": base="
                                       + baseArtifact.qualifiedName()
                                       + ", candidate="
                                       + candidateArtifact.qualifiedName()
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }

                        for (LoweredNodeId aid : candidateArtifacts.keySet()) {
                           if (!baseArtifacts.containsKey(aid)) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "closure artifact added in candidate: " + aid.value()
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }

                        Map<String, ProjectFile> baseClosureFiles = new LinkedHashMap<>();

                        for (ProjectFile f : baseClosure.files()) {
                           baseClosureFiles.put(f.id().relativePath(), f);
                        }

                        Map<String, ProjectFile> candidateClosureFiles = new LinkedHashMap<>();

                        for (ProjectFile f : candidateClosure.files()) {
                           candidateClosureFiles.put(f.id().relativePath(), f);
                        }

                        if (baseClosureFiles.size() != candidateClosureFiles.size()) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-303",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure file count changed: base=" + baseClosureFiles.size() + ", candidate=" + candidateClosureFiles.size()
                              )
                           );
                           return fail(diagnostics);
                        }

                        for (String path : baseClosureFiles.keySet()) {
                           ProjectFile candidateFile = candidateClosureFiles.get(path);
                           if (candidateFile == null) {
                              diagnostics.add(
                                 ChangeDiagnostic.error("SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "closure file removed in candidate: " + path)
                              );
                              return fail(diagnostics);
                           }

                           ProjectFile baseFile = baseClosureFiles.get(path);
                           if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error("SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "closure file artifactId changed for " + path)
                              );
                              return fail(diagnostics);
                           }

                           if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "closure file ownerSymbol changed for " + path
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }

                        for (String path : candidateClosureFiles.keySet()) {
                           if (!baseClosureFiles.containsKey(path)) {
                              diagnostics.add(
                                 ChangeDiagnostic.error("SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "closure file added in candidate: " + path)
                              );
                              return fail(diagnostics);
                           }
                        }

                        Set<GraphEdgeId> baseEdgeIds = new LinkedHashSet<>();

                        for (ProjectGraphEdge e : input.baseGraph().edges()) {
                           baseEdgeIds.add(e.id());
                        }

                        Set<GraphEdgeId> candidateEdgeIds = new LinkedHashSet<>();

                        for (ProjectGraphEdge e : input.candidateGraph().edges()) {
                           candidateEdgeIds.add(e.id());
                        }

                        if (!baseEdgeIds.equals(candidateEdgeIds)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-303",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "graph edge ID set changed: base count=" + baseEdgeIds.size() + ", candidate count=" + candidateEdgeIds.size()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Set<GraphNodeId> baseNodeIds = new LinkedHashSet<>();

                        for (ProjectGraphNode n : input.baseGraph().nodes()) {
                           baseNodeIds.add(n.id());
                        }

                        Set<GraphNodeId> candidateNodeIds = new LinkedHashSet<>();

                        for (ProjectGraphNode n : input.candidateGraph().nodes()) {
                           candidateNodeIds.add(n.id());
                        }

                        if (!baseNodeIds.equals(candidateNodeIds)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-303",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "graph node ID set changed: base count=" + baseNodeIds.size() + ", candidate count=" + candidateNodeIds.size()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Map<GraphNodeId, ProjectGraphNode> baseNodeById = new LinkedHashMap<>();

                        for (ProjectGraphNode n : input.baseGraph().nodes()) {
                           baseNodeById.put(n.id(), n);
                        }

                        Map<GraphNodeId, ProjectGraphNode> candidateNodeById = new LinkedHashMap<>();

                        for (ProjectGraphNode n : input.candidateGraph().nodes()) {
                           candidateNodeById.put(n.id(), n);
                        }

                        for (GraphNodeId nodeId : baseNodeIds) {
                           ProjectGraphNode baseNode = baseNodeById.get(nodeId);
                           ProjectGraphNode candidateNode = candidateNodeById.get(nodeId);
                           if (candidateNode != null) {
                              if (baseNode.getClass() != candidateNode.getClass()) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-303",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "graph node type changed (same id, different type): base="
                                          + baseNode.getClass().getSimpleName()
                                          + ", candidate="
                                          + candidateNode.getClass().getSimpleName()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              boolean isClosureInternalLoweredOrArtifact = false;
                              if (baseNode instanceof LoweredDeclaration ld) {
                                 isClosureInternalLoweredOrArtifact = baseClosure.loweredDeclaration().id().equals(ld.id());
                              } else if (baseNode instanceof Artifact art) {
                                 isClosureInternalLoweredOrArtifact = baseClosure.artifacts().stream().anyMatch(a -> a.artifactId().equals(art.artifactId()));
                              }

                              String loweredOrArtifactCode = isClosureInternalLoweredOrArtifact ? "SIR-CHANGE-IMPACT-302" : "SIR-CHANGE-IMPACT-303";
                              if (baseNode instanceof Project baseProject && candidateNode instanceof Project candidateProject) {
                                 if (!baseProject.provenance().sourceId().equals(candidateProject.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error("SIR-CHANGE-IMPACT-303", ChangeDiagnosticStage.IMPACT, 0, "Project provenance sourceId changed")
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof SemanticDeclaration baseSd && candidateNode instanceof SemanticDeclaration candidateSd) {
                                 if (!baseSd.kind().equals(candidateSd.kind())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-303",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "SemanticDeclaration kind changed for " + baseSd.id().symbolId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseSd.provenance().sourceId().equals(candidateSd.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-303",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "SemanticDeclaration provenance sourceId changed for " + baseSd.id().symbolId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseSd.provenance().sourceNodeId().equals(candidateSd.provenance().sourceNodeId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-303",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "SemanticDeclaration provenance sourceNodeId changed for " + baseSd.id().symbolId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof LoweredDeclaration baseLd && candidateNode instanceof LoweredDeclaration candidateLd) {
                                 if (!baseLd.sourceSymbol().equals(candidateLd.sourceSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration sourceSymbol changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseLd.provenance().sourceId().equals(candidateLd.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration provenance sourceId changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseLd.provenance().origin().ownerSymbol().equals(candidateLd.provenance().origin().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration origin ownerSymbol changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseLd.provenance().origin().sourceNodeId().equals(candidateLd.provenance().origin().sourceNodeId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration origin sourceNodeId changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof Artifact baseArt && candidateNode instanceof Artifact candidateArt) {
                                 if (!baseArt.role().equals(candidateArt.role())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode, ChangeDiagnosticStage.IMPACT, 0, "Artifact role changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.ownerSymbol().equals(candidateArt.ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact ownerSymbol changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.qualifiedName().equals(candidateArt.qualifiedName())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact qualifiedName changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.provenance().sourceId().equals(candidateArt.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact provenance sourceId changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.provenance().origin().ownerSymbol().equals(candidateArt.provenance().origin().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact origin ownerSymbol changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.provenance().origin().sourceNodeId().equals(candidateArt.provenance().origin().sourceNodeId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact origin sourceNodeId changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof ProjectFile baseFile && candidateNode instanceof ProjectFile candidateFile) {
                                 if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-303",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "ProjectFile artifactId changed for " + baseFile.id().relativePath()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-303",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "ProjectFile ownerSymbol changed for " + baseFile.id().relativePath()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 boolean isInClosure = baseClosureFiles.containsKey(baseFile.id().relativePath());
                                 if (!isInClosure) {
                                    if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                                       || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                                       diagnostics.add(
                                          ChangeDiagnostic.error(
                                             "SIR-CHANGE-IMPACT-303",
                                             ChangeDiagnosticStage.IMPACT,
                                             0,
                                             "closure-outside file bytes changed: "
                                                + baseFile.id().relativePath()
                                                + " (base sha="
                                                + baseFile.provenance().sha256Hex()
                                                + ", candidate sha="
                                                + candidateFile.provenance().sha256Hex()
                                                + ")"
                                          )
                                       );
                                       return fail(diagnostics);
                                    }

                                    if (!baseFile.provenance().sourceId().equals(candidateFile.provenance().sourceId())) {
                                       diagnostics.add(
                                          ChangeDiagnostic.error(
                                             "SIR-CHANGE-IMPACT-303",
                                             ChangeDiagnosticStage.IMPACT,
                                             0,
                                             "closure-outside file provenance sourceId changed: " + baseFile.id().relativePath()
                                          )
                                       );
                                       return fail(diagnostics);
                                    }
                                 }
                              }
                           }
                        }

                        SemanticProjection.ConstraintsOnlyP baseConstraints = SemanticProjection.ofConstraintsOnly(baseTargetField);
                        SemanticProjection.ConstraintsOnlyP candidateConstraints = SemanticProjection.ofConstraintsOnly(candidateTargetField);
                        boolean constraintsSemanticallyIdentical = baseConstraints.equals(candidateConstraints);
                        List<FileChange> fileChanges = new ArrayList<>();

                        for (String path : baseClosureFiles.keySet()) {
                           ProjectFile baseFile = baseClosureFiles.get(path);
                           ProjectFile candidateFile = candidateClosureFiles.get(path);
                           if (!baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())
                              || baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()) {
                              fileChanges.add(
                                 new FileChange(
                                    path,
                                    baseFile.provenance().artifactId(),
                                    baseFile.provenance().ownerSymbol(),
                                    baseFile.provenance().byteCount(),
                                    baseFile.provenance().sha256Hex(),
                                    candidateFile.provenance().byteCount(),
                                    candidateFile.provenance().sha256Hex()
                                 )
                              );
                           }
                        }

                        fileChanges.sort(Comparator.comparing(FileChange::relativePath));
                        if (constraintsSemanticallyIdentical) {
                           if (fileChanges.isEmpty()) {
                              return new ChangeAnalysis.NoChanges(NoChangeReason.SEMANTICALLY_IDENTICAL, sortAndCopy(diagnostics));
                           }

                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-304",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "target field constraints semantically identical but closure file bytes differ; this indicates a non-deterministic pipeline"
                              )
                           );
                           return fail(diagnostics);
                        } else {
                           if (fileChanges.isEmpty()) {
                              return new ChangeAnalysis.NoChanges(NoChangeReason.OUTPUT_EQUIVALENT, sortAndCopy(diagnostics));
                           }

                           List<ArtifactChange> artifactChanges = new ArrayList<>();

                           for (Artifact artifact : baseClosure.artifacts()) {
                              LoweredNodeId aid = artifact.artifactId();
                              List<FileChange> ownedChanges = new ArrayList<>();

                              for (FileChange fc : fileChanges) {
                                 if (fc.artifactId().equals(aid)) {
                                    ownedChanges.add(fc);
                                 }
                              }

                              if (!ownedChanges.isEmpty()) {
                                 ImpactedArtifact impacted = new ImpactedArtifact(
                                    aid, artifact.ownerSymbol(), artifact.role(), artifact.qualifiedName(), List.copyOf(ownedChanges)
                                 );
                                 artifactChanges.add(new ArtifactChange(impacted, List.copyOf(ownedChanges)));
                              }
                           }

                           ChangePlan plan = new ChangePlan(changeSet, List.copyOf(artifactChanges), List.copyOf(fileChanges));
                           return new ChangeAnalysis.Planned(plan, sortAndCopy(diagnostics));
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static ChangeAnalysis planModifyUnreferencedInputFieldType(
      ChangePlanningInput input, ChangeSet changeSet, ModifyUnreferencedInputFieldType modifyTypeOp, List<ChangeDiagnostic> diagnostics
   ) {
      ChangeTarget target = modifyTypeOp.target();
      SymbolId targetSymbol = target.declarationSymbol();
      NormalizedDeclaration baseDeclForTarget = findDeclarationById(input.baseSemanticModel(), targetSymbol);
      if (baseDeclForTarget == null) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-401", ChangeDiagnosticStage.TARGET, 0, "target.declarationSymbol not found in base semantic model: " + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else if (!(baseDeclForTarget instanceof NormalizedInput baseInput)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-402",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol is not an Input in base: " + targetSymbol.value() + " (actual kind: " + kindOf(baseDeclForTarget) + ")"
            )
         );
         return fail(diagnostics);
      } else {
         if (!baseInput.sourceNodeId().equals(target.declarationNodeId())) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-403",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.declarationNodeId "
                     + target.declarationNodeId().value()
                     + " does not match base Input sourceNodeId "
                     + baseInput.sourceNodeId().value()
                     + " for symbol "
                     + targetSymbol.value()
               )
            );
            return fail(diagnostics);
         }

         NormalizedField baseTargetField = null;

         for (NormalizedField f : baseInput.fields()) {
            if (f.sourceNodeId().equals(target.targetNodeId())) {
               baseTargetField = f;
               break;
            }
         }

         if (baseTargetField == null) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-404",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "target.targetNodeId "
                     + target.targetNodeId().value()
                     + " does not match any base Input field sourceNodeId for symbol "
                     + targetSymbol.value()
                     + " (field count: "
                     + baseInput.fields().size()
                     + ")"
               )
            );
            return fail(diagnostics);
         } else {
            NormalizedDeclaration candidateDeclForTarget = findDeclarationById(input.candidateSemanticModel(), targetSymbol);
            if (!(candidateDeclForTarget instanceof NormalizedInput candidateInput)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-TARGET-405",
                     ChangeDiagnosticStage.TARGET,
                     0,
                     "candidate Input with same SymbolId not found (or wrong kind): "
                        + targetSymbol.value()
                        + (candidateDeclForTarget == null ? " (not found)" : " (actual kind: " + kindOf(candidateDeclForTarget) + ")")
                  )
               );
               return fail(diagnostics);
            } else {
               NormalizedField candidateTargetField = null;

               for (NormalizedField f : candidateInput.fields()) {
                  if (f.sourceNodeId().equals(target.targetNodeId())) {
                     candidateTargetField = f;
                     break;
                  }
               }

               if (candidateTargetField == null) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-TARGET-406",
                        ChangeDiagnosticStage.TARGET,
                        0,
                        "candidate Input has no field with sourceNodeId == target.targetNodeId "
                           + target.targetNodeId().value()
                           + " for symbol "
                           + targetSymbol.value()
                           + " (candidate field count: "
                           + candidateInput.fields().size()
                           + ")"
                     )
                  );
                  return fail(diagnostics);
               }

               SemanticProjection.FieldTypeSiteP baseSite = SemanticProjection.ofFieldTypeSite(baseTargetField, input.baseSemanticModel());
               if (baseSite.siteId().isEmpty()) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-TARGET-407",
                        ChangeDiagnosticStage.TARGET,
                        0,
                        "base target field has no directNamedTypeReferenceSiteId; field type is not a direct AstNamedTypeRef: " + baseTargetField.id().value()
                     )
                  );
                  return fail(diagnostics);
               }

               if (baseSite.bindingTarget().isEmpty()) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-TARGET-407",
                        ChangeDiagnosticStage.TARGET,
                        0,
                        "base target field directNamedTypeReferenceSiteId has no binding: site="
                           + baseSite.siteId().get().value()
                           + " for field "
                           + baseTargetField.id().value()
                     )
                  );
                  return fail(diagnostics);
               }

               if (!baseSite.role().isEmpty() && baseSite.role().get() == ReferenceRole.NAMED_TYPE) {
                  SemanticProjection.FieldTypeSiteP candidateSite = SemanticProjection.ofFieldTypeSite(candidateTargetField, input.candidateSemanticModel());
                  if (candidateSite.siteId().isEmpty()) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-TARGET-408",
                           ChangeDiagnosticStage.TARGET,
                           0,
                           "candidate target field has no directNamedTypeReferenceSiteId; field type is not a direct AstNamedTypeRef: "
                              + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!candidateSite.siteId().get().equals(baseSite.siteId().get())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-TARGET-408",
                           ChangeDiagnosticStage.TARGET,
                           0,
                           "candidate target field directNamedTypeReferenceSiteId differs from base: base="
                              + baseSite.siteId().get().value()
                              + " candidate="
                              + candidateSite.siteId().get().value()
                              + " for field "
                              + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (candidateSite.bindingTarget().isEmpty()) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-TARGET-408",
                           ChangeDiagnosticStage.TARGET,
                           0,
                           "candidate target field directNamedTypeReferenceSiteId has no binding: site="
                              + candidateSite.siteId().get().value()
                              + " for field "
                              + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (candidateSite.role().isEmpty() || candidateSite.role().get() != ReferenceRole.NAMED_TYPE) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-TARGET-408",
                           ChangeDiagnosticStage.TARGET,
                           0,
                           "candidate target field type-site binding role is not NAMED_TYPE: site="
                              + candidateSite.siteId().get().value()
                              + " role="
                              + candidateSite.role().map(Enum::name).orElse("(none")
                              + " for field "
                              + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!input.baseSemanticModel().softwareName().equals(input.candidateSemanticModel().softwareName())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-401",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "softwareName changed from '"
                              + input.baseSemanticModel().softwareName()
                              + "' to '"
                              + input.candidateSemanticModel().softwareName()
                              + "'"
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!SemanticProjection.ofMetadata(input.baseSemanticModel().metadata())
                     .equals(SemanticProjection.ofMetadata(input.candidateSemanticModel().metadata()))) {
                     diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-401", ChangeDiagnosticStage.SCOPE, 0, "metadata changed"));
                     return fail(diagnostics);
                  }

                  if (!SemanticProjection.ofTarget(input.baseSemanticModel().target())
                     .equals(SemanticProjection.ofTarget(input.candidateSemanticModel().target()))) {
                     diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-401", ChangeDiagnosticStage.SCOPE, 0, "target changed"));
                     return fail(diagnostics);
                  }

                  Map<SymbolId, NormalizedDeclaration> baseDecls = indexDeclarations(input.baseSemanticModel());
                  Map<SymbolId, NormalizedDeclaration> candidateDecls = indexDeclarations(input.candidateSemanticModel());
                  if (baseDecls.size() != candidateDecls.size()) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-402",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "declaration count changed: base=" + baseDecls.size() + ", candidate=" + candidateDecls.size()
                        )
                     );
                     return fail(diagnostics);
                  }

                  for (SymbolId sid : baseDecls.keySet()) {
                     if (!candidateDecls.containsKey(sid)) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-SCOPE-402", ChangeDiagnosticStage.SCOPE, 0, "declaration removed in candidate: " + sid.value())
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (SymbolId sid : candidateDecls.keySet()) {
                     if (!baseDecls.containsKey(sid)) {
                        diagnostics.add(
                           ChangeDiagnostic.error("SIR-CHANGE-SCOPE-402", ChangeDiagnosticStage.SCOPE, 0, "declaration added in candidate: " + sid.value())
                        );
                        return fail(diagnostics);
                     }
                  }

                  List<SymbolId> baseOrder = new ArrayList<>(baseDecls.keySet());
                  List<SymbolId> candidateOrder = new ArrayList<>(candidateDecls.keySet());
                  if (!baseOrder.equals(candidateOrder)) {
                     diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-402", ChangeDiagnosticStage.SCOPE, 0, "declaration order changed"));
                     return fail(diagnostics);
                  }

                  for (SymbolId sid : baseOrder) {
                     NormalizedDeclaration baseDecl = baseDecls.get(sid);
                     NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
                     String baseKind = kindOf(baseDecl);
                     String candidateKind = kindOf(candidateDecl);
                     if (!baseKind.equals(candidateKind)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-SCOPE-402",
                              ChangeDiagnosticStage.SCOPE,
                              0,
                              "declaration kind changed for " + sid.value() + ": base=" + baseKind + ", candidate=" + candidateKind
                           )
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (SymbolId sid : baseOrder) {
                     if (!sid.equals(targetSymbol)) {
                        NormalizedDeclaration baseDecl = baseDecls.get(sid);
                        NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
                        SemanticProjection.DeclarationProjection baseProj = SemanticProjection.ofFullDeclaration(baseDecl);
                        SemanticProjection.DeclarationProjection candidateProj = SemanticProjection.ofFullDeclaration(candidateDecl);
                        if (!baseProj.equals(candidateProj)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-SCOPE-403", ChangeDiagnosticStage.SCOPE, 0, "non-target declaration semantically changed: " + sid.value()
                              )
                           );
                           return fail(diagnostics);
                        }
                     }
                  }

                  if (!baseInput.id().equals(candidateInput.id())
                     || !baseInput.name().equals(candidateInput.name())
                     || !baseInput.sourceNodeId().equals(candidateInput.sourceNodeId())) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "target Input identity changed (SymbolId / name / sourceNodeId): " + targetSymbol.value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (baseInput.fields().size() != candidateInput.fields().size()) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "target Input field count changed: base=" + baseInput.fields().size() + ", candidate=" + candidateInput.fields().size()
                        )
                     );
                     return fail(diagnostics);
                  }

                  for (int i = 0; i < baseInput.fields().size(); i++) {
                     NormalizedField baseField = baseInput.fields().get(i);
                     NormalizedField candidateField = candidateInput.fields().get(i);
                     if (!baseField.id().equals(candidateField.id())
                        || !baseField.name().equals(candidateField.name())
                        || !baseField.sourceNodeId().equals(candidateField.sourceNodeId())) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-SCOPE-404",
                              ChangeDiagnosticStage.SCOPE,
                              0,
                              "target Input field identity changed at index "
                                 + i
                                 + ": base="
                                 + baseField.id().value()
                                 + " candidate="
                                 + candidateField.id().value()
                           )
                        );
                        return fail(diagnostics);
                     }
                  }

                  for (int i = 0; i < baseInput.fields().size(); i++) {
                     NormalizedField baseField = baseInput.fields().get(i);
                     if (!baseField.sourceNodeId().equals(target.targetNodeId())) {
                        NormalizedField candidateField = candidateInput.fields().get(i);
                        SemanticProjection.FieldP baseFieldProj = SemanticProjection.ofNonTargetField(baseField);
                        SemanticProjection.FieldP candidateFieldProj = SemanticProjection.ofNonTargetField(candidateField);
                        if (!baseFieldProj.equals(candidateFieldProj)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-SCOPE-404",
                                 ChangeDiagnosticStage.SCOPE,
                                 0,
                                 "non-target Input field semantically changed: "
                                    + baseField.id().value()
                                    + " (field sourceNodeId: "
                                    + baseField.sourceNodeId().value()
                                    + ")"
                              )
                           );
                           return fail(diagnostics);
                        }
                     }
                  }

                  SemanticProjection.ConstraintsOnlyP baseConstraints = SemanticProjection.ofConstraintsOnly(baseTargetField);
                  SemanticProjection.ConstraintsOnlyP candidateConstraints = SemanticProjection.ofConstraintsOnly(candidateTargetField);
                  if (!baseConstraints.equals(candidateConstraints)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404", ChangeDiagnosticStage.SCOPE, 0, "target Input field constraints changed: " + baseTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  SirType baseType = baseTargetField.type();
                  SirType candidateType = candidateTargetField.type();
                  if (!(baseType instanceof PrimitiveType)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "base target field type is not a direct PrimitiveType: "
                              + baseTargetField.id().value()
                              + " (actual type: "
                              + baseType.getClass().getSimpleName()
                              + ")"
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (!(candidateType instanceof PrimitiveType)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "candidate target field type is not a direct PrimitiveType: "
                              + candidateTargetField.id().value()
                              + " (actual type: "
                              + candidateType.getClass().getSimpleName()
                              + ")"
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (baseType == PrimitiveType.UNIT) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "base target field type is UNIT; v0.5 does not support UNIT: " + baseTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (candidateType == PrimitiveType.UNIT) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-404",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "candidate target field type is UNIT; v0.5 does not support UNIT: " + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (baseSite.primitiveName().isEmpty()) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-405",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "base target field type-site binding target is not a PRIMITIVE Symbol: site="
                              + baseSite.siteId().get().value()
                              + " target="
                              + baseSite.bindingTarget().get().value()
                              + " for field "
                              + baseTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  if (candidateSite.primitiveName().isEmpty()) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-405",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "candidate target field type-site binding target is not a PRIMITIVE Symbol: site="
                              + candidateSite.siteId().get().value()
                              + " target="
                              + candidateSite.bindingTarget().get().value()
                              + " for field "
                              + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  String baseExpectedName = ((PrimitiveType)baseType).sirName();
                  if (!baseSite.primitiveName().get().equals(baseExpectedName)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-405",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "base target field type-site binding target primitive name does not match field PrimitiveType.sirName: field="
                              + baseExpectedName
                              + " symbol="
                              + baseSite.primitiveName().get()
                              + " for field "
                              + baseTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  String candidateExpectedName = ((PrimitiveType)candidateType).sirName();
                  if (!candidateSite.primitiveName().get().equals(candidateExpectedName)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-405",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "candidate target field type-site binding target primitive name does not match field PrimitiveType.sirName: field="
                              + candidateExpectedName
                              + " symbol="
                              + candidateSite.primitiveName().get()
                              + " for field "
                              + candidateTargetField.id().value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  AstNodeId ownedSiteId = baseSite.siteId().get();
                  ReferenceSiteBindings baseRefs = input.baseSemanticModel().referenceSiteBindings();
                  ReferenceSiteBindings candidateRefs = input.candidateSemanticModel().referenceSiteBindings();
                  SemanticProjection.TypedReferenceSiteProjectionV5 baseRefProjExcluded = SemanticProjection.ofTypedReferenceSitesExcluding(
                     baseRefs, ownedSiteId
                  );
                  SemanticProjection.TypedReferenceSiteProjectionV5 candidateRefProjExcluded = SemanticProjection.ofTypedReferenceSitesExcluding(
                     candidateRefs, ownedSiteId
                  );
                  if (!baseRefProjExcluded.equals(candidateRefProjExcluded)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-405",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "typed reference-site projection changed outside the target field's owned site: ownedSite=" + ownedSiteId.value()
                        )
                     );
                     return fail(diagnostics);
                  }

                  for (ReferenceSiteBindings refs : List.of(baseRefs, candidateRefs)) {
                     for (ReferenceSiteBinding b : refs.all()) {
                        SymbolId consumerTarget = b.targetSymbol();
                        if (consumerTarget.equals(baseTargetField.id()) || consumerTarget.equals(candidateTargetField.id())) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-SCOPE-405",
                                 ChangeDiagnosticStage.SCOPE,
                                 0,
                                 "global typed reference-site binding targets the target field SymbolId (field consumer detected): site="
                                    + b.site().id().value()
                                    + " target="
                                    + consumerTarget.value()
                                    + " for field "
                                    + baseTargetField.id().value()
                              )
                           );
                           return fail(diagnostics);
                        }
                     }
                  }

                  ClosureComputer.InputClosureResult baseClosureResult = ClosureComputer.computeInputClosure(input.baseGraph(), targetSymbol);
                  if (baseClosureResult instanceof ClosureComputer.InputClosureResult.MissingTrace baseMissing) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-IMPACT-401", ChangeDiagnosticStage.IMPACT, 0, "base Input closure trace incomplete: " + baseMissing.detail()
                        )
                     );
                     return fail(diagnostics);
                  } else {
                     ClosureComputer.InputClosure baseClosure = ((ClosureComputer.InputClosureResult.Success)baseClosureResult).closure();
                     ClosureComputer.InputClosureResult candidateClosureResult = ClosureComputer.computeInputClosure(input.candidateGraph(), targetSymbol);
                     if (candidateClosureResult instanceof ClosureComputer.InputClosureResult.MissingTrace candidateMissing) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-IMPACT-401",
                              ChangeDiagnosticStage.IMPACT,
                              0,
                              "candidate Input closure trace incomplete: " + candidateMissing.detail()
                           )
                        );
                        return fail(diagnostics);
                     } else {
                        ClosureComputer.InputClosure candidateClosure = ((ClosureComputer.InputClosureResult.Success)candidateClosureResult).closure();
                        if (!baseClosure.loweredDeclaration().sourceSymbol().equals(candidateClosure.loweredDeclaration().sourceSymbol())) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-402",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure LoweredDeclaration sourceSymbol changed for " + baseClosure.loweredDeclaration().id().nodeId().value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Map<LoweredNodeId, Artifact> baseArtifacts = new LinkedHashMap<>();

                        for (Artifact a : baseClosure.artifacts()) {
                           baseArtifacts.put(a.artifactId(), a);
                        }

                        Map<LoweredNodeId, Artifact> candidateArtifacts = new LinkedHashMap<>();

                        for (Artifact a : candidateClosure.artifacts()) {
                           candidateArtifacts.put(a.artifactId(), a);
                        }

                        if (baseArtifacts.size() != candidateArtifacts.size()) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-403",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure artifact count changed (graph node drift): base="
                                    + baseArtifacts.size()
                                    + ", candidate="
                                    + candidateArtifacts.size()
                                    + " for "
                                    + targetSymbol.value()
                              )
                           );
                           return fail(diagnostics);
                        }

                        for (LoweredNodeId aid : baseArtifacts.keySet()) {
                           Artifact candidateArtifact = candidateArtifacts.get(aid);
                           if (candidateArtifact == null) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "closure artifact removed in candidate: " + aid.value()
                                 )
                              );
                              return fail(diagnostics);
                           }

                           Artifact baseArtifact = baseArtifacts.get(aid);
                           if (!baseArtifact.role().equals(candidateArtifact.role())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-402",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "closure artifact role changed for "
                                       + aid.value()
                                       + ": base="
                                       + baseArtifact.role()
                                       + ", candidate="
                                       + candidateArtifact.role()
                                 )
                              );
                              return fail(diagnostics);
                           }

                           if (!baseArtifact.ownerSymbol().equals(candidateArtifact.ownerSymbol())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-402", ChangeDiagnosticStage.IMPACT, 0, "closure artifact ownerSymbol changed for " + aid.value()
                                 )
                              );
                              return fail(diagnostics);
                           }

                           if (!baseArtifact.qualifiedName().equals(candidateArtifact.qualifiedName())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-402",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "closure artifact qualifiedName changed for "
                                       + aid.value()
                                       + ": base="
                                       + baseArtifact.qualifiedName()
                                       + ", candidate="
                                       + candidateArtifact.qualifiedName()
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }

                        for (LoweredNodeId aid : candidateArtifacts.keySet()) {
                           if (!baseArtifacts.containsKey(aid)) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "closure artifact added in candidate: " + aid.value()
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }

                        Map<String, ProjectFile> baseClosureFiles = new LinkedHashMap<>();

                        for (ProjectFile f : baseClosure.files()) {
                           baseClosureFiles.put(f.id().relativePath(), f);
                        }

                        Map<String, ProjectFile> candidateClosureFiles = new LinkedHashMap<>();

                        for (ProjectFile f : candidateClosure.files()) {
                           candidateClosureFiles.put(f.id().relativePath(), f);
                        }

                        if (baseClosureFiles.size() != candidateClosureFiles.size()) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-403",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "closure file count changed: base=" + baseClosureFiles.size() + ", candidate=" + candidateClosureFiles.size()
                              )
                           );
                           return fail(diagnostics);
                        }

                        for (String path : baseClosureFiles.keySet()) {
                           ProjectFile candidateFile = candidateClosureFiles.get(path);
                           if (candidateFile == null) {
                              diagnostics.add(
                                 ChangeDiagnostic.error("SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "closure file removed in candidate: " + path)
                              );
                              return fail(diagnostics);
                           }

                           ProjectFile baseFile = baseClosureFiles.get(path);
                           if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error("SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "closure file artifactId changed for " + path)
                              );
                              return fail(diagnostics);
                           }

                           if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "closure file ownerSymbol changed for " + path
                                 )
                              );
                              return fail(diagnostics);
                           }
                        }

                        for (String path : candidateClosureFiles.keySet()) {
                           if (!baseClosureFiles.containsKey(path)) {
                              diagnostics.add(
                                 ChangeDiagnostic.error("SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "closure file added in candidate: " + path)
                              );
                              return fail(diagnostics);
                           }
                        }

                        Set<GraphEdgeId> baseEdgeIds = new LinkedHashSet<>();

                        for (ProjectGraphEdge e : input.baseGraph().edges()) {
                           baseEdgeIds.add(e.id());
                        }

                        Set<GraphEdgeId> candidateEdgeIds = new LinkedHashSet<>();

                        for (ProjectGraphEdge e : input.candidateGraph().edges()) {
                           candidateEdgeIds.add(e.id());
                        }

                        if (!baseEdgeIds.equals(candidateEdgeIds)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-403",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "graph edge ID set changed: base count=" + baseEdgeIds.size() + ", candidate count=" + candidateEdgeIds.size()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Set<GraphNodeId> baseNodeIds = new LinkedHashSet<>();

                        for (ProjectGraphNode n : input.baseGraph().nodes()) {
                           baseNodeIds.add(n.id());
                        }

                        Set<GraphNodeId> candidateNodeIds = new LinkedHashSet<>();

                        for (ProjectGraphNode n : input.candidateGraph().nodes()) {
                           candidateNodeIds.add(n.id());
                        }

                        if (!baseNodeIds.equals(candidateNodeIds)) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-403",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "graph node ID set changed: base count=" + baseNodeIds.size() + ", candidate count=" + candidateNodeIds.size()
                              )
                           );
                           return fail(diagnostics);
                        }

                        Map<GraphNodeId, ProjectGraphNode> baseNodeById = new LinkedHashMap<>();

                        for (ProjectGraphNode n : input.baseGraph().nodes()) {
                           baseNodeById.put(n.id(), n);
                        }

                        Map<GraphNodeId, ProjectGraphNode> candidateNodeById = new LinkedHashMap<>();

                        for (ProjectGraphNode n : input.candidateGraph().nodes()) {
                           candidateNodeById.put(n.id(), n);
                        }

                        for (GraphNodeId nodeId : baseNodeIds) {
                           ProjectGraphNode baseNode = baseNodeById.get(nodeId);
                           ProjectGraphNode candidateNode = candidateNodeById.get(nodeId);
                           if (candidateNode != null) {
                              if (baseNode.getClass() != candidateNode.getClass()) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-403",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "graph node type changed (same id, different type): base="
                                          + baseNode.getClass().getSimpleName()
                                          + ", candidate="
                                          + candidateNode.getClass().getSimpleName()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              boolean isClosureInternalLoweredOrArtifact = false;
                              if (baseNode instanceof LoweredDeclaration ld) {
                                 isClosureInternalLoweredOrArtifact = baseClosure.loweredDeclaration().id().equals(ld.id());
                              } else if (baseNode instanceof Artifact art) {
                                 isClosureInternalLoweredOrArtifact = baseClosure.artifacts().stream().anyMatch(a -> a.artifactId().equals(art.artifactId()));
                              }

                              String loweredOrArtifactCode = isClosureInternalLoweredOrArtifact ? "SIR-CHANGE-IMPACT-402" : "SIR-CHANGE-IMPACT-403";
                              if (baseNode instanceof Project baseProject && candidateNode instanceof Project candidateProject) {
                                 if (!baseProject.provenance().sourceId().equals(candidateProject.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error("SIR-CHANGE-IMPACT-403", ChangeDiagnosticStage.IMPACT, 0, "Project provenance sourceId changed")
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof SemanticDeclaration baseSd && candidateNode instanceof SemanticDeclaration candidateSd) {
                                 if (!baseSd.kind().equals(candidateSd.kind())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-403",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "SemanticDeclaration kind changed for " + baseSd.id().symbolId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseSd.provenance().sourceId().equals(candidateSd.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-403",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "SemanticDeclaration provenance sourceId changed for " + baseSd.id().symbolId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseSd.provenance().sourceNodeId().equals(candidateSd.provenance().sourceNodeId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-403",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "SemanticDeclaration provenance sourceNodeId changed for " + baseSd.id().symbolId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof LoweredDeclaration baseLd && candidateNode instanceof LoweredDeclaration candidateLd) {
                                 if (!baseLd.sourceSymbol().equals(candidateLd.sourceSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration sourceSymbol changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseLd.provenance().sourceId().equals(candidateLd.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration provenance sourceId changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseLd.provenance().origin().ownerSymbol().equals(candidateLd.provenance().origin().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration origin ownerSymbol changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseLd.provenance().origin().sourceNodeId().equals(candidateLd.provenance().origin().sourceNodeId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "LoweredDeclaration origin sourceNodeId changed for " + baseLd.id().nodeId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof Artifact baseArt && candidateNode instanceof Artifact candidateArt) {
                                 if (!baseArt.role().equals(candidateArt.role())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode, ChangeDiagnosticStage.IMPACT, 0, "Artifact role changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.ownerSymbol().equals(candidateArt.ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact ownerSymbol changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.qualifiedName().equals(candidateArt.qualifiedName())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact qualifiedName changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.provenance().sourceId().equals(candidateArt.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact provenance sourceId changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.provenance().origin().ownerSymbol().equals(candidateArt.provenance().origin().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact origin ownerSymbol changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseArt.provenance().origin().sourceNodeId().equals(candidateArt.provenance().origin().sourceNodeId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          loweredOrArtifactCode,
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "Artifact origin sourceNodeId changed for " + baseArt.artifactId().value()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              } else if (baseNode instanceof ProjectFile baseFile && candidateNode instanceof ProjectFile candidateFile) {
                                 if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-403",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "ProjectFile artifactId changed for " + baseFile.id().relativePath()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-403",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "ProjectFile ownerSymbol changed for " + baseFile.id().relativePath()
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 boolean isInClosure = baseClosureFiles.containsKey(baseFile.id().relativePath());
                                 if (!isInClosure) {
                                    if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                                       || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                                       diagnostics.add(
                                          ChangeDiagnostic.error(
                                             "SIR-CHANGE-IMPACT-403",
                                             ChangeDiagnosticStage.IMPACT,
                                             0,
                                             "closure-outside file bytes changed: "
                                                + baseFile.id().relativePath()
                                                + " (base sha="
                                                + baseFile.provenance().sha256Hex()
                                                + ", candidate sha="
                                                + candidateFile.provenance().sha256Hex()
                                                + ")"
                                          )
                                       );
                                       return fail(diagnostics);
                                    }

                                    if (!baseFile.provenance().sourceId().equals(candidateFile.provenance().sourceId())) {
                                       diagnostics.add(
                                          ChangeDiagnostic.error(
                                             "SIR-CHANGE-IMPACT-403",
                                             ChangeDiagnosticStage.IMPACT,
                                             0,
                                             "closure-outside file provenance sourceId changed: " + baseFile.id().relativePath()
                                          )
                                       );
                                       return fail(diagnostics);
                                    }
                                 }
                              }
                           }
                        }

                        boolean typeDiffers = !baseSite.bindingTarget().get().equals(candidateSite.bindingTarget().get()) && baseType != candidateType;
                        List<FileChange> fileChanges = new ArrayList<>();

                        for (String path : baseClosureFiles.keySet()) {
                           ProjectFile baseFile = baseClosureFiles.get(path);
                           ProjectFile candidateFile = candidateClosureFiles.get(path);
                           if (!baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())
                              || baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()) {
                              fileChanges.add(
                                 new FileChange(
                                    path,
                                    baseFile.provenance().artifactId(),
                                    baseFile.provenance().ownerSymbol(),
                                    baseFile.provenance().byteCount(),
                                    baseFile.provenance().sha256Hex(),
                                    candidateFile.provenance().byteCount(),
                                    candidateFile.provenance().sha256Hex()
                                 )
                              );
                           }
                        }

                        fileChanges.sort(Comparator.comparing(FileChange::relativePath));
                        if (!typeDiffers) {
                           if (fileChanges.isEmpty()) {
                              return new ChangeAnalysis.NoChanges(NoChangeReason.SEMANTICALLY_IDENTICAL, sortAndCopy(diagnostics));
                           }

                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-404",
                                 ChangeDiagnosticStage.IMPACT,
                                 0,
                                 "target field type semantically identical but closure file bytes differ; this indicates a non-deterministic pipeline"
                              )
                           );
                           return fail(diagnostics);
                        } else {
                           if (fileChanges.isEmpty()) {
                              return new ChangeAnalysis.NoChanges(NoChangeReason.OUTPUT_EQUIVALENT, sortAndCopy(diagnostics));
                           }

                           List<ArtifactChange> artifactChanges = new ArrayList<>();

                           for (Artifact artifact : baseClosure.artifacts()) {
                              LoweredNodeId aid = artifact.artifactId();
                              List<FileChange> ownedChanges = new ArrayList<>();

                              for (FileChange fc : fileChanges) {
                                 if (fc.artifactId().equals(aid)) {
                                    ownedChanges.add(fc);
                                 }
                              }

                              if (!ownedChanges.isEmpty()) {
                                 ImpactedArtifact impacted = new ImpactedArtifact(
                                    aid, artifact.ownerSymbol(), artifact.role(), artifact.qualifiedName(), List.copyOf(ownedChanges)
                                 );
                                 artifactChanges.add(new ArtifactChange(impacted, List.copyOf(ownedChanges)));
                              }
                           }

                           ChangePlan plan = new ChangePlan(changeSet, List.copyOf(artifactChanges), List.copyOf(fileChanges));
                           return new ChangeAnalysis.Planned(plan, sortAndCopy(diagnostics));
                        }
                     }
                  }
               } else {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-TARGET-407",
                        ChangeDiagnosticStage.TARGET,
                        0,
                        "base target field type-site binding role is not NAMED_TYPE: site="
                           + baseSite.siteId().get().value()
                           + " role="
                           + baseSite.role().map(Enum::name).orElse("(none")
                           + " for field "
                           + baseTargetField.id().value()
                     )
                  );
                  return fail(diagnostics);
               }
            }
         }
      }
   }

   private static ChangeAnalysis planModifyActorlessReadonlyCapabilityExposure(
      ChangePlanningInput input, ChangeSet changeSet, ModifyActorlessReadonlyCapabilityExposure modifyExposureOp, List<ChangeDiagnostic> diagnostics
   ) {
      ChangeTarget target = modifyExposureOp.target();
      SymbolId targetSymbol = target.declarationSymbol();
      NormalizedDeclaration baseDeclForTarget = findDeclarationById(input.baseSemanticModel(), targetSymbol);
      if (baseDeclForTarget == null) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-501", ChangeDiagnosticStage.TARGET, 0, "target.declarationSymbol not found in base semantic model: " + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else if (!(baseDeclForTarget instanceof NormalizedCapability baseCapability)) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-502",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationSymbol is not a Capability in base: " + targetSymbol.value() + " (actual kind: " + kindOf(baseDeclForTarget) + ")"
            )
         );
         return fail(diagnostics);
      } else if (!baseCapability.sourceNodeId().equals(target.declarationNodeId())) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-503",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.declarationNodeId "
                  + target.declarationNodeId().value()
                  + " does not match base Capability sourceNodeId "
                  + baseCapability.sourceNodeId().value()
                  + " for symbol "
                  + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else if (!target.targetNodeId().equals(target.declarationNodeId())) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-504",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.targetNodeId "
                  + target.targetNodeId().value()
                  + " does not equal target.declarationNodeId "
                  + target.declarationNodeId().value()
                  + " for symbol "
                  + targetSymbol.value()
                  + " (v0.6 requires targetNodeId == declarationNodeId)"
            )
         );
         return fail(diagnostics);
      } else if (!baseCapability.sourceNodeId().equals(target.targetNodeId())) {
         diagnostics.add(
            ChangeDiagnostic.error(
               "SIR-CHANGE-TARGET-504",
               ChangeDiagnosticStage.TARGET,
               0,
               "target.targetNodeId "
                  + target.targetNodeId().value()
                  + " does not match base Capability sourceNodeId "
                  + baseCapability.sourceNodeId().value()
                  + " for symbol "
                  + targetSymbol.value()
            )
         );
         return fail(diagnostics);
      } else {
         NormalizedDeclaration candidateDeclForTarget = findDeclarationById(input.candidateSemanticModel(), targetSymbol);
         if (!(candidateDeclForTarget instanceof NormalizedCapability candidateCapability)) {
            diagnostics.add(
               ChangeDiagnostic.error(
                  "SIR-CHANGE-TARGET-505",
                  ChangeDiagnosticStage.TARGET,
                  0,
                  "candidate Capability with same SymbolId not found (or wrong kind): "
                     + targetSymbol.value()
                     + (candidateDeclForTarget == null ? " (not found)" : " (actual kind: " + kindOf(candidateDeclForTarget) + ")")
               )
            );
            return fail(diagnostics);
         } else {
            if (!candidateCapability.sourceNodeId().equals(baseCapability.sourceNodeId())) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-TARGET-506",
                     ChangeDiagnosticStage.TARGET,
                     0,
                     "candidate Capability sourceNodeId drift: base="
                        + baseCapability.sourceNodeId().value()
                        + ", candidate="
                        + candidateCapability.sourceNodeId().value()
                        + " for symbol "
                        + targetSymbol.value()
                  )
               );
               return fail(diagnostics);
            }

            if (!input.baseSemanticModel().softwareName().equals(input.candidateSemanticModel().softwareName())) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-501",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "softwareName changed from '" + input.baseSemanticModel().softwareName() + "' to '" + input.candidateSemanticModel().softwareName() + "'"
                  )
               );
               return fail(diagnostics);
            }

            if (!SemanticProjection.ofMetadata(input.baseSemanticModel().metadata())
               .equals(SemanticProjection.ofMetadata(input.candidateSemanticModel().metadata()))) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-501", ChangeDiagnosticStage.SCOPE, 0, "metadata changed"));
               return fail(diagnostics);
            }

            if (!SemanticProjection.ofTarget(input.baseSemanticModel().target()).equals(SemanticProjection.ofTarget(input.candidateSemanticModel().target()))) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-501", ChangeDiagnosticStage.SCOPE, 0, "target changed"));
               return fail(diagnostics);
            }

            Map<SymbolId, NormalizedDeclaration> baseDecls = indexDeclarations(input.baseSemanticModel());
            Map<SymbolId, NormalizedDeclaration> candidateDecls = indexDeclarations(input.candidateSemanticModel());
            if (baseDecls.size() != candidateDecls.size()) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-502",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "declaration count changed: base=" + baseDecls.size() + ", candidate=" + candidateDecls.size()
                  )
               );
               return fail(diagnostics);
            }

            for (SymbolId sid : baseDecls.keySet()) {
               if (!candidateDecls.containsKey(sid)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-SCOPE-502", ChangeDiagnosticStage.SCOPE, 0, "declaration removed in candidate: " + sid.value())
                  );
                  return fail(diagnostics);
               }
            }

            for (SymbolId sid : candidateDecls.keySet()) {
               if (!baseDecls.containsKey(sid)) {
                  diagnostics.add(
                     ChangeDiagnostic.error("SIR-CHANGE-SCOPE-502", ChangeDiagnosticStage.SCOPE, 0, "declaration added in candidate: " + sid.value())
                  );
                  return fail(diagnostics);
               }
            }

            List<SymbolId> baseOrder = new ArrayList<>(baseDecls.keySet());
            List<SymbolId> candidateOrder = new ArrayList<>(candidateDecls.keySet());
            if (!baseOrder.equals(candidateOrder)) {
               diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-SCOPE-502", ChangeDiagnosticStage.SCOPE, 0, "declaration order changed"));
               return fail(diagnostics);
            }

            for (SymbolId sid : baseOrder) {
               NormalizedDeclaration baseDecl = baseDecls.get(sid);
               NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
               String baseKind = kindOf(baseDecl);
               String candidateKind = kindOf(candidateDecl);
               if (!baseKind.equals(candidateKind)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-502",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "declaration kind changed for " + sid.value() + ": base=" + baseKind + ", candidate=" + candidateKind
                     )
                  );
                  return fail(diagnostics);
               }
            }

            for (SymbolId sid : baseOrder) {
               if (!sid.equals(targetSymbol)) {
                  NormalizedDeclaration baseDecl = baseDecls.get(sid);
                  NormalizedDeclaration candidateDecl = candidateDecls.get(sid);
                  SemanticProjection.DeclarationProjection baseProj = SemanticProjection.ofFullDeclaration(baseDecl);
                  SemanticProjection.DeclarationProjection candidateProj = SemanticProjection.ofFullDeclaration(candidateDecl);
                  if (!baseProj.equals(candidateProj)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-503", ChangeDiagnosticStage.SCOPE, 0, "non-target declaration semantically changed: " + sid.value()
                        )
                     );
                     return fail(diagnostics);
                  }
               }
            }

            SemanticProjection.CapabilityExceptExposureP baseCapProj = SemanticProjection.ofCapabilityExceptExposure(baseCapability);
            SemanticProjection.CapabilityExceptExposureP candidateCapProj = SemanticProjection.ofCapabilityExceptExposure(candidateCapability);
            if (!baseCapProj.equals(candidateCapProj)) {
               diagnostics.add(
                  ChangeDiagnostic.error(
                     "SIR-CHANGE-SCOPE-504",
                     ChangeDiagnosticStage.SCOPE,
                     0,
                     "target Capability identity/contract changed (excluding exposure): " + targetSymbol.value()
                  )
               );
               return fail(diagnostics);
            } else {
               SemanticProjection.WorkflowP baseWorkflowProj = SemanticProjection.ofWorkflowOnly(baseCapability.workflow());
               SemanticProjection.WorkflowP candidateWorkflowProj = SemanticProjection.ofWorkflowOnly(candidateCapability.workflow());
               if (!baseWorkflowProj.equals(candidateWorkflowProj)) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-504", ChangeDiagnosticStage.SCOPE, 0, "target Capability workflow structure changed for " + targetSymbol.value()
                     )
                  );
                  return fail(diagnostics);
               } else if (baseCapability.actorSymbol().isPresent()) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-504",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "base target Capability is not actorless (actorSymbol present): "
                           + targetSymbol.value()
                           + " actor="
                           + baseCapability.actorSymbol().get().value()
                     )
                  );
                  return fail(diagnostics);
               } else if (candidateCapability.actorSymbol().isPresent()) {
                  diagnostics.add(
                     ChangeDiagnostic.error(
                        "SIR-CHANGE-SCOPE-504",
                        ChangeDiagnosticStage.SCOPE,
                        0,
                        "candidate target Capability is not actorless (actorSymbol present): "
                           + targetSymbol.value()
                           + " actor="
                           + candidateCapability.actorSymbol().get().value()
                     )
                  );
                  return fail(diagnostics);
               } else {
                  List<AstRequirementKind> readonlyOnly = List.of(AstRequirementKind.READONLY);
                  if (!baseCapability.requires().equals(readonlyOnly)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-504",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "base target Capability requires is not [READONLY]: " + targetSymbol.value() + " requires=" + baseCapability.requires()
                        )
                     );
                     return fail(diagnostics);
                  } else if (!candidateCapability.requires().equals(readonlyOnly)) {
                     diagnostics.add(
                        ChangeDiagnostic.error(
                           "SIR-CHANGE-SCOPE-504",
                           ChangeDiagnosticStage.SCOPE,
                           0,
                           "candidate target Capability requires is not [READONLY]: " + targetSymbol.value() + " requires=" + candidateCapability.requires()
                        )
                     );
                     return fail(diagnostics);
                  } else {
                     SemanticProjection.TypedReferenceSiteProjection baseRefs = SemanticProjection.ofTypedReferenceSites(
                        input.baseSemanticModel().referenceSiteBindings()
                     );
                     SemanticProjection.TypedReferenceSiteProjection candidateRefs = SemanticProjection.ofTypedReferenceSites(
                        input.candidateSemanticModel().referenceSiteBindings()
                     );
                     if (!baseRefs.equals(candidateRefs)) {
                        diagnostics.add(
                           ChangeDiagnostic.error(
                              "SIR-CHANGE-SCOPE-505", ChangeDiagnosticStage.SCOPE, 0, "typed reference-site projection changed for " + targetSymbol.value()
                           )
                        );
                        return fail(diagnostics);
                     } else {
                        ClosureComputer.ExposureClosureResult baseClosureResult = ClosureComputer.computeExposureClosure(input.baseGraph(), targetSymbol);
                        if (baseClosureResult instanceof ClosureComputer.ExposureClosureResult.MissingTrace baseMissing) {
                           diagnostics.add(
                              ChangeDiagnostic.error(
                                 "SIR-CHANGE-IMPACT-501", ChangeDiagnosticStage.IMPACT, 0, "base exposure closure trace incomplete: " + baseMissing.detail()
                              )
                           );
                           return fail(diagnostics);
                        } else {
                           ClosureComputer.ExposureClosure baseClosure = ((ClosureComputer.ExposureClosureResult.Success)baseClosureResult).closure();
                           ClosureComputer.ExposureClosureResult candidateClosureResult = ClosureComputer.computeExposureClosure(
                              input.candidateGraph(), targetSymbol
                           );
                           if (candidateClosureResult instanceof ClosureComputer.ExposureClosureResult.MissingTrace candidateMissing) {
                              diagnostics.add(
                                 ChangeDiagnostic.error(
                                    "SIR-CHANGE-IMPACT-501",
                                    ChangeDiagnosticStage.IMPACT,
                                    0,
                                    "candidate exposure closure trace incomplete: " + candidateMissing.detail()
                                 )
                              );
                              return fail(diagnostics);
                           } else {
                              ClosureComputer.ExposureClosure candidateClosure = ((ClosureComputer.ExposureClosureResult.Success)candidateClosureResult)
                                 .closure();
                              if (!baseClosure.semanticDeclaration().id().equals(candidateClosure.semanticDeclaration().id())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SemanticDeclaration id changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.semanticDeclaration().kind().equals(candidateClosure.semanticDeclaration().kind())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SemanticDeclaration kind changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.semanticDeclaration()
                                 .provenance()
                                 .sourceId()
                                 .equals(candidateClosure.semanticDeclaration().provenance().sourceId())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SemanticDeclaration provenance sourceId changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.semanticDeclaration()
                                 .provenance()
                                 .sourceNodeId()
                                 .equals(candidateClosure.semanticDeclaration().provenance().sourceNodeId())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SemanticDeclaration provenance sourceNodeId changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.loweredDeclaration().id().equals(candidateClosure.loweredDeclaration().id())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure LoweredDeclaration id changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.loweredDeclaration().sourceSymbol().equals(candidateClosure.loweredDeclaration().sourceSymbol())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure LoweredDeclaration sourceSymbol changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.loweredDeclaration()
                                 .provenance()
                                 .sourceId()
                                 .equals(candidateClosure.loweredDeclaration().provenance().sourceId())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure LoweredDeclaration provenance sourceId changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.loweredDeclaration()
                                 .provenance()
                                 .origin()
                                 .ownerSymbol()
                                 .equals(candidateClosure.loweredDeclaration().provenance().origin().ownerSymbol())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure LoweredDeclaration origin ownerSymbol changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.serviceArtifact().artifactId().equals(candidateClosure.serviceArtifact().artifactId())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SERVICE artifact id changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.serviceArtifact().role().equals(candidateClosure.serviceArtifact().role())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SERVICE artifact role changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.serviceArtifact().ownerSymbol().equals(candidateClosure.serviceArtifact().ownerSymbol())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SERVICE artifact ownerSymbol changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.serviceArtifact().qualifiedName().equals(candidateClosure.serviceArtifact().qualifiedName())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure SERVICE artifact qualifiedName changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.controllerArtifact().artifactId().equals(candidateClosure.controllerArtifact().artifactId())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure CONTROLLER artifact id changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.controllerArtifact().role().equals(candidateClosure.controllerArtifact().role())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure CONTROLLER artifact role changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.controllerArtifact().ownerSymbol().equals(candidateClosure.controllerArtifact().ownerSymbol())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure CONTROLLER artifact ownerSymbol changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              if (!baseClosure.controllerArtifact().qualifiedName().equals(candidateClosure.controllerArtifact().qualifiedName())) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-502",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure CONTROLLER artifact qualifiedName changed for " + targetSymbol.value()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              Set<GraphEdgeId> baseEdgeIds = new LinkedHashSet<>();

                              for (ProjectGraphEdge e : input.baseGraph().edges()) {
                                 baseEdgeIds.add(e.id());
                              }

                              Set<GraphEdgeId> candidateEdgeIds = new LinkedHashSet<>();

                              for (ProjectGraphEdge e : input.candidateGraph().edges()) {
                                 candidateEdgeIds.add(e.id());
                              }

                              if (!baseEdgeIds.equals(candidateEdgeIds)) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-503",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "graph edge ID set changed: base count=" + baseEdgeIds.size() + ", candidate count=" + candidateEdgeIds.size()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              Set<GraphNodeId> baseNodeIds = new LinkedHashSet<>();

                              for (ProjectGraphNode n : input.baseGraph().nodes()) {
                                 baseNodeIds.add(n.id());
                              }

                              Set<GraphNodeId> candidateNodeIds = new LinkedHashSet<>();

                              for (ProjectGraphNode n : input.candidateGraph().nodes()) {
                                 candidateNodeIds.add(n.id());
                              }

                              if (!baseNodeIds.equals(candidateNodeIds)) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-503",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "graph node ID set changed: base count=" + baseNodeIds.size() + ", candidate count=" + candidateNodeIds.size()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              Map<String, ProjectFile> baseControllerFiles = new LinkedHashMap<>();

                              for (ProjectFile f : baseClosure.controllerFiles()) {
                                 baseControllerFiles.put(f.id().relativePath(), f);
                              }

                              Map<String, ProjectFile> candidateControllerFiles = new LinkedHashMap<>();

                              for (ProjectFile f : candidateClosure.controllerFiles()) {
                                 candidateControllerFiles.put(f.id().relativePath(), f);
                              }

                              Map<String, ProjectFile> baseServiceFiles = new LinkedHashMap<>();

                              for (ProjectFile f : baseClosure.serviceFiles()) {
                                 baseServiceFiles.put(f.id().relativePath(), f);
                              }

                              Map<String, ProjectFile> candidateServiceFiles = new LinkedHashMap<>();

                              for (ProjectFile f : candidateClosure.serviceFiles()) {
                                 candidateServiceFiles.put(f.id().relativePath(), f);
                              }

                              Set<String> closurePaths = new LinkedHashSet<>();
                              closurePaths.addAll(baseServiceFiles.keySet());
                              closurePaths.addAll(baseControllerFiles.keySet());
                              if (baseControllerFiles.size() != candidateControllerFiles.size()) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-503",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure Controller file count changed: base="
                                          + baseControllerFiles.size()
                                          + ", candidate="
                                          + candidateControllerFiles.size()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              for (String path : baseControllerFiles.keySet()) {
                                 ProjectFile candidateFile = candidateControllerFiles.get(path);
                                 if (candidateFile == null) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Controller file removed in candidate: " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 ProjectFile baseFile = baseControllerFiles.get(path);
                                 if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Controller file artifactId changed for " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Controller file ownerSymbol changed for " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              }

                              for (String path : candidateControllerFiles.keySet()) {
                                 if (!baseControllerFiles.containsKey(path)) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Controller file added in candidate: " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              }

                              if (baseServiceFiles.size() != candidateServiceFiles.size()) {
                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-503",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "closure Service file count changed: base=" + baseServiceFiles.size() + ", candidate=" + candidateServiceFiles.size()
                                    )
                                 );
                                 return fail(diagnostics);
                              }

                              for (String path : baseServiceFiles.keySet()) {
                                 ProjectFile candidateFile = candidateServiceFiles.get(path);
                                 if (candidateFile == null) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Service file removed in candidate: " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 ProjectFile baseFile = baseServiceFiles.get(path);
                                 if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Service file artifactId changed for " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Service file ownerSymbol changed for " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                                    || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503",
                                          ChangeDiagnosticStage.IMPACT,
                                          0,
                                          "closure Service file bytes changed: "
                                             + path
                                             + " (base sha="
                                             + baseFile.provenance().sha256Hex()
                                             + ", candidate sha="
                                             + candidateFile.provenance().sha256Hex()
                                             + ")"
                                       )
                                    );
                                    return fail(diagnostics);
                                 }

                                 if (!baseFile.provenance().sourceId().equals(candidateFile.provenance().sourceId())) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Service file provenance sourceId changed: " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              }

                              for (String path : candidateServiceFiles.keySet()) {
                                 if (!baseServiceFiles.containsKey(path)) {
                                    diagnostics.add(
                                       ChangeDiagnostic.error(
                                          "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "closure Service file added in candidate: " + path
                                       )
                                    );
                                    return fail(diagnostics);
                                 }
                              }

                              Map<GraphNodeId, ProjectGraphNode> baseNodeById = new LinkedHashMap<>();

                              for (ProjectGraphNode n : input.baseGraph().nodes()) {
                                 baseNodeById.put(n.id(), n);
                              }

                              Map<GraphNodeId, ProjectGraphNode> candidateNodeById = new LinkedHashMap<>();

                              for (ProjectGraphNode n : input.candidateGraph().nodes()) {
                                 candidateNodeById.put(n.id(), n);
                              }

                              for (GraphNodeId nodeId : baseNodeIds) {
                                 ProjectGraphNode baseNode = baseNodeById.get(nodeId);
                                 ProjectGraphNode candidateNode = candidateNodeById.get(nodeId);
                                 if (candidateNode != null) {
                                    if (baseNode.getClass() != candidateNode.getClass()) {
                                       diagnostics.add(
                                          ChangeDiagnostic.error(
                                             "SIR-CHANGE-IMPACT-503",
                                             ChangeDiagnosticStage.IMPACT,
                                             0,
                                             "graph node type changed (same id, different type): base="
                                                + baseNode.getClass().getSimpleName()
                                                + ", candidate="
                                                + candidateNode.getClass().getSimpleName()
                                          )
                                       );
                                       return fail(diagnostics);
                                    }

                                    if (!(baseNode instanceof Project baseProject) || !(candidateNode instanceof Project candidateProject)) {
                                       if (!(baseNode instanceof SemanticDeclaration baseSd) || !(candidateNode instanceof SemanticDeclaration candidateSd)) {
                                          if (!(baseNode instanceof LoweredDeclaration baseLd) || !(candidateNode instanceof LoweredDeclaration candidateLd)) {
                                             if (baseNode instanceof Artifact baseArt && candidateNode instanceof Artifact candidateArt) {
                                                boolean isClosureInternalArtifact = baseArt.artifactId().equals(baseClosure.serviceArtifact().artifactId())
                                                   || baseArt.artifactId().equals(baseClosure.controllerArtifact().artifactId());
                                                String artifactCode = isClosureInternalArtifact ? "SIR-CHANGE-IMPACT-502" : "SIR-CHANGE-IMPACT-503";
                                                if (!baseArt.role().equals(candidateArt.role())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         artifactCode,
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "Artifact role changed for " + baseArt.artifactId().value()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                if (!baseArt.ownerSymbol().equals(candidateArt.ownerSymbol())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         artifactCode,
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "Artifact ownerSymbol changed for " + baseArt.artifactId().value()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                if (!baseArt.qualifiedName().equals(candidateArt.qualifiedName())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         artifactCode,
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "Artifact qualifiedName changed for " + baseArt.artifactId().value()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                if (!baseArt.provenance().sourceId().equals(candidateArt.provenance().sourceId())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         artifactCode,
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "Artifact provenance sourceId changed for " + baseArt.artifactId().value()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                if (!baseArt.provenance().origin().ownerSymbol().equals(candidateArt.provenance().origin().ownerSymbol())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         artifactCode,
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "Artifact origin ownerSymbol changed for " + baseArt.artifactId().value()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                if (!baseArt.provenance().origin().sourceNodeId().equals(candidateArt.provenance().origin().sourceNodeId())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         artifactCode,
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "Artifact origin sourceNodeId changed for " + baseArt.artifactId().value()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }
                                             } else if (baseNode instanceof ProjectFile baseFile && candidateNode instanceof ProjectFile candidateFile) {
                                                if (!baseFile.provenance().artifactId().equals(candidateFile.provenance().artifactId())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         "SIR-CHANGE-IMPACT-503",
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "ProjectFile artifactId changed for " + baseFile.id().relativePath()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                if (!baseFile.provenance().ownerSymbol().equals(candidateFile.provenance().ownerSymbol())) {
                                                   diagnostics.add(
                                                      ChangeDiagnostic.error(
                                                         "SIR-CHANGE-IMPACT-503",
                                                         ChangeDiagnosticStage.IMPACT,
                                                         0,
                                                         "ProjectFile ownerSymbol changed for " + baseFile.id().relativePath()
                                                      )
                                                   );
                                                   return fail(diagnostics);
                                                }

                                                boolean isClosureInternalController = baseControllerFiles.containsKey(baseFile.id().relativePath());
                                                if (!isClosureInternalController) {
                                                   if (baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()
                                                      || !baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())) {
                                                      diagnostics.add(
                                                         ChangeDiagnostic.error(
                                                            "SIR-CHANGE-IMPACT-503",
                                                            ChangeDiagnosticStage.IMPACT,
                                                            0,
                                                            "closure-outside or Service file bytes changed: "
                                                               + baseFile.id().relativePath()
                                                               + " (base sha="
                                                               + baseFile.provenance().sha256Hex()
                                                               + ", candidate sha="
                                                               + candidateFile.provenance().sha256Hex()
                                                               + ")"
                                                         )
                                                      );
                                                      return fail(diagnostics);
                                                   }

                                                   if (!baseFile.provenance().sourceId().equals(candidateFile.provenance().sourceId())) {
                                                      diagnostics.add(
                                                         ChangeDiagnostic.error(
                                                            "SIR-CHANGE-IMPACT-503",
                                                            ChangeDiagnosticStage.IMPACT,
                                                            0,
                                                            "closure-outside or Service file provenance sourceId changed: " + baseFile.id().relativePath()
                                                         )
                                                      );
                                                      return fail(diagnostics);
                                                   }
                                                }
                                             }
                                          } else {
                                             if (!baseLd.sourceSymbol().equals(candidateLd.sourceSymbol())) {
                                                diagnostics.add(
                                                   ChangeDiagnostic.error(
                                                      "SIR-CHANGE-IMPACT-502",
                                                      ChangeDiagnosticStage.IMPACT,
                                                      0,
                                                      "LoweredDeclaration sourceSymbol changed for " + baseLd.id().nodeId().value()
                                                   )
                                                );
                                                return fail(diagnostics);
                                             }

                                             if (!baseLd.provenance().sourceId().equals(candidateLd.provenance().sourceId())) {
                                                diagnostics.add(
                                                   ChangeDiagnostic.error(
                                                      "SIR-CHANGE-IMPACT-502",
                                                      ChangeDiagnosticStage.IMPACT,
                                                      0,
                                                      "LoweredDeclaration provenance sourceId changed for " + baseLd.id().nodeId().value()
                                                   )
                                                );
                                                return fail(diagnostics);
                                             }

                                             if (!baseLd.provenance().origin().ownerSymbol().equals(candidateLd.provenance().origin().ownerSymbol())) {
                                                diagnostics.add(
                                                   ChangeDiagnostic.error(
                                                      "SIR-CHANGE-IMPACT-502",
                                                      ChangeDiagnosticStage.IMPACT,
                                                      0,
                                                      "LoweredDeclaration origin ownerSymbol changed for " + baseLd.id().nodeId().value()
                                                   )
                                                );
                                                return fail(diagnostics);
                                             }

                                             if (!baseLd.provenance().origin().sourceNodeId().equals(candidateLd.provenance().origin().sourceNodeId())) {
                                                diagnostics.add(
                                                   ChangeDiagnostic.error(
                                                      "SIR-CHANGE-IMPACT-502",
                                                      ChangeDiagnosticStage.IMPACT,
                                                      0,
                                                      "LoweredDeclaration origin sourceNodeId changed for " + baseLd.id().nodeId().value()
                                                   )
                                                );
                                                return fail(diagnostics);
                                             }
                                          }
                                       } else {
                                          if (!baseSd.kind().equals(candidateSd.kind())) {
                                             diagnostics.add(
                                                ChangeDiagnostic.error(
                                                   "SIR-CHANGE-IMPACT-503",
                                                   ChangeDiagnosticStage.IMPACT,
                                                   0,
                                                   "SemanticDeclaration kind changed for " + baseSd.id().symbolId().value()
                                                )
                                             );
                                             return fail(diagnostics);
                                          }

                                          if (!baseSd.provenance().sourceId().equals(candidateSd.provenance().sourceId())) {
                                             diagnostics.add(
                                                ChangeDiagnostic.error(
                                                   "SIR-CHANGE-IMPACT-503",
                                                   ChangeDiagnosticStage.IMPACT,
                                                   0,
                                                   "SemanticDeclaration provenance sourceId changed for " + baseSd.id().symbolId().value()
                                                )
                                             );
                                             return fail(diagnostics);
                                          }

                                          if (!baseSd.provenance().sourceNodeId().equals(candidateSd.provenance().sourceNodeId())) {
                                             diagnostics.add(
                                                ChangeDiagnostic.error(
                                                   "SIR-CHANGE-IMPACT-503",
                                                   ChangeDiagnosticStage.IMPACT,
                                                   0,
                                                   "SemanticDeclaration provenance sourceNodeId changed for " + baseSd.id().symbolId().value()
                                                )
                                             );
                                             return fail(diagnostics);
                                          }
                                       }
                                    } else if (!baseProject.provenance().sourceId().equals(candidateProject.provenance().sourceId())) {
                                       diagnostics.add(
                                          ChangeDiagnostic.error(
                                             "SIR-CHANGE-IMPACT-503", ChangeDiagnosticStage.IMPACT, 0, "Project provenance sourceId changed"
                                          )
                                       );
                                       return fail(diagnostics);
                                    }
                                 }
                              }

                              boolean exposureDiffers = baseCapability.exposure() != candidateCapability.exposure();
                              List<FileChange> fileChanges = new ArrayList<>();

                              for (String path : baseControllerFiles.keySet()) {
                                 ProjectFile baseFile = baseControllerFiles.get(path);
                                 ProjectFile candidateFile = candidateControllerFiles.get(path);
                                 if (!baseFile.provenance().sha256Hex().equals(candidateFile.provenance().sha256Hex())
                                    || baseFile.provenance().byteCount() != candidateFile.provenance().byteCount()) {
                                    fileChanges.add(
                                       new FileChange(
                                          path,
                                          baseFile.provenance().artifactId(),
                                          baseFile.provenance().ownerSymbol(),
                                          baseFile.provenance().byteCount(),
                                          baseFile.provenance().sha256Hex(),
                                          candidateFile.provenance().byteCount(),
                                          candidateFile.provenance().sha256Hex()
                                       )
                                    );
                                 }
                              }

                              fileChanges.sort(Comparator.comparing(FileChange::relativePath));
                              if (!exposureDiffers) {
                                 if (fileChanges.isEmpty()) {
                                    return new ChangeAnalysis.NoChanges(NoChangeReason.SEMANTICALLY_IDENTICAL, sortAndCopy(diagnostics));
                                 }

                                 diagnostics.add(
                                    ChangeDiagnostic.error(
                                       "SIR-CHANGE-IMPACT-504",
                                       ChangeDiagnosticStage.IMPACT,
                                       0,
                                       "target Capability exposure semantically identical but Controller file bytes differ; this indicates a non-deterministic pipeline"
                                    )
                                 );
                                 return fail(diagnostics);
                              } else {
                                 if (fileChanges.isEmpty()) {
                                    return new ChangeAnalysis.NoChanges(NoChangeReason.OUTPUT_EQUIVALENT, sortAndCopy(diagnostics));
                                 }

                                 List<ArtifactChange> artifactChanges = new ArrayList<>();
                                 LoweredNodeId controllerArtifactId = baseClosure.controllerArtifact().artifactId();
                                 List<FileChange> ownedChanges = new ArrayList<>();

                                 for (FileChange fc : fileChanges) {
                                    if (fc.artifactId().equals(controllerArtifactId)) {
                                       ownedChanges.add(fc);
                                    }
                                 }

                                 if (!ownedChanges.isEmpty()) {
                                    ImpactedArtifact impacted = new ImpactedArtifact(
                                       controllerArtifactId,
                                       baseClosure.controllerArtifact().ownerSymbol(),
                                       baseClosure.controllerArtifact().role(),
                                       baseClosure.controllerArtifact().qualifiedName(),
                                       List.copyOf(ownedChanges)
                                    );
                                    artifactChanges.add(new ArtifactChange(impacted, List.copyOf(ownedChanges)));
                                 }

                                 ChangePlan plan = new ChangePlan(changeSet, List.copyOf(artifactChanges), List.copyOf(fileChanges));
                                 return new ChangeAnalysis.Planned(plan, sortAndCopy(diagnostics));
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private static ChangeAnalysis.Failure fail(List<ChangeDiagnostic> diagnostics) {
      if (diagnostics.stream().noneMatch(ChangeDiagnostic::isError)) {
         diagnostics.add(ChangeDiagnostic.error("SIR-CHANGE-REQUEST-004", ChangeDiagnosticStage.REQUEST, 0, "planner failed without a specific diagnostic"));
      }

      return new ChangeAnalysis.Failure(sortAndCopy(diagnostics));
   }

   private static List<ChangeDiagnostic> sortAndCopy(List<ChangeDiagnostic> diagnostics) {
      List<ChangeDiagnostic> sorted = new ArrayList<>(diagnostics);
      sorted.sort(
         Comparator.<ChangeDiagnostic, String>comparing(d -> d.stage().name())
            .thenComparingInt(ChangeDiagnostic::operationIndex)
            .thenComparing(d -> d.targetSymbol().map(SymbolId::value).orElse(""))
            .thenComparing(d -> d.relativePath().orElse(""))
            .thenComparing(d -> d.code())
      );
      return List.copyOf(sorted);
   }

   private static NormalizedDeclaration findDeclarationById(NormalizedSemanticModel model, SymbolId symbolId) {
      for (NormalizedDeclaration decl : model.declarations()) {
         if (decl.id().equals(symbolId)) {
            return decl;
         }
      }

      return null;
   }

   private static Map<SymbolId, NormalizedDeclaration> indexDeclarations(NormalizedSemanticModel model) {
      Map<SymbolId, NormalizedDeclaration> map = new LinkedHashMap<>();

      for (NormalizedDeclaration decl : model.declarations()) {
         map.put(decl.id(), decl);
      }

      return map;
   }

   private static String kindOf(NormalizedDeclaration decl) {
      if (decl instanceof NormalizedEnum) {
         return "ENUM";
      } else if (decl instanceof NormalizedEntity) {
         return "ENTITY";
      } else if (decl instanceof NormalizedInput) {
         return "INPUT";
      } else if (decl instanceof NormalizedError) {
         return "ERROR";
      } else {
         return decl instanceof NormalizedCapability ? "CAPABILITY" : "UNKNOWN";
      }
   }

   private static Map<String, ProjectFile> indexAllFiles(ProjectGraph graph) {
      Map<String, ProjectFile> map = new LinkedHashMap<>();

      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof ProjectFile file) {
            map.put(file.id().relativePath(), file);
         }
      }

      List<String> paths = new ArrayList<>(map.keySet());
      paths.sort(Comparator.naturalOrder());
      Map<String, ProjectFile> ordered = new LinkedHashMap<>();

      for (String p : paths) {
         ordered.put(p, map.get(p));
      }

      return ordered;
   }

   private static Map<LoweredNodeId, LoweredDeclaration> indexLowered(ProjectGraph graph) {
      Map<LoweredNodeId, LoweredDeclaration> map = new LinkedHashMap<>();

      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof LoweredDeclaration ld) {
            map.put(ld.id().nodeId(), ld);
         }
      }

      return map;
   }

   private static Map<LoweredNodeId, Artifact> indexArtifacts(ProjectGraph graph) {
      Map<LoweredNodeId, Artifact> map = new LinkedHashMap<>();

      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof Artifact a) {
            map.put(a.artifactId(), a);
         }
      }

      return map;
   }

   private static boolean baseGraphContainsSymbolId(ProjectGraph graph, SymbolId symbolId) {
      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof SemanticDeclaration sd && sd.id().symbolId().equals(symbolId)) {
            return true;
         }

         if (node instanceof LoweredDeclaration ld && ld.sourceSymbol().equals(symbolId)) {
            return true;
         }

         if (node instanceof Artifact a && a.ownerSymbol().isPresent() && a.ownerSymbol().get().equals(symbolId)) {
            return true;
         }

         if (node instanceof ProjectFile f && f.provenance().ownerSymbol().isPresent() && f.provenance().ownerSymbol().get().equals(symbolId)) {
            return true;
         }
      }

      return false;
   }

   private static LoweredNodeId findLoweredForSymbol(ProjectGraph graph, SymbolId symbolId) {
      Semantic semanticId = new Semantic(symbolId);

      for (ProjectGraphEdge edge : graph.outgoing(semanticId)) {
         if (edge.kind() == GraphEdgeKind.LOWERS_TO && edge.target() instanceof Lowered l) {
            return l.nodeId();
         }
      }

      return null;
   }

   private static SourceId extractGraphSourceId(ProjectGraph graph) {
      for (ProjectGraphNode node : graph.nodes()) {
         if (node instanceof Project project) {
            return project.provenance().sourceId();
         }
      }

      throw new IllegalStateException("ProjectGraph has no Project root node");
   }
}
