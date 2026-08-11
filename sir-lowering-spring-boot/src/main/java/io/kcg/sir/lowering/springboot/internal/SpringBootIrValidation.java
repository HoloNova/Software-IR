package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.api.LoweringDiagnostic;
import io.kcg.sir.lowering.springboot.model.ActorBinding;
import io.kcg.sir.lowering.springboot.model.ActorIdentityRequirement;
import io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import io.kcg.sir.lowering.springboot.profile.SpringBootTargetProfile;
import io.kcg.sir.semantic.symbol.SymbolId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SpringBootIrValidation {
   private final SpringBootLoweredModel model;
   private final List<LoweringDiagnostic> diagnostics = new ArrayList<>();
   private final Set<LoweredNodeId> nodeIds = new HashSet<>();
   private final Map<SymbolId, SpringBootDeclaration> declarations = new LinkedHashMap<>();
   private final Set<SymbolId> knownSymbols = new LinkedHashSet<>();

   public SpringBootIrValidation(SpringBootLoweredModel model) {
      this.model = model;
   }

   public List<LoweringDiagnostic> validate() {
      SpringBootIrValidation.VersionProfileStatus rootStatus = this.validateRoot();
      if (rootStatus == SpringBootIrValidation.VersionProfileStatus.UNKNOWN) {
         return LoweringDiagnostics.sorted(this.diagnostics);
      }

      if (rootStatus == SpringBootIrValidation.VersionProfileStatus.OK) {
         this.validateTransportPlanMatrix();
      }

      this.validateProjectArtifacts();
      this.indexDeclarations();

      for (SpringBootDeclaration declaration : this.model.declarations()) {
         this.validateDeclaration(declaration);
      }

      this.validateArtifacts();
      return LoweringDiagnostics.sorted(this.diagnostics);
   }

   private void validateProjectArtifacts() {
      ProjectArtifact.MavenProject pom = this.model.mavenProject();
      this.addNode(pom.id(), pom.origin(), "maven-project");
      if (!pom.path().equals("pom.xml")) {
         this.error(pom.origin(), "MavenProject path must be 'pom.xml': " + pom.path());
      }

      if (pom.javaVersion() != SpringBootTargetProfile.V0_1.javaVersion()) {
         this.error(pom.origin(), "MavenProject javaVersion must be " + SpringBootTargetProfile.V0_1.javaVersion() + ": " + pom.javaVersion());
      }

      if (!pom.springBootParentVersion().equals(SpringBootTargetProfile.V0_1.springBootVersion())) {
         this.error(
            pom.origin(),
            "MavenProject springBootParentVersion must be " + SpringBootTargetProfile.V0_1.springBootVersion() + ": " + pom.springBootParentVersion()
         );
      }

      if (pom.dependencies().isEmpty()) {
         this.error(pom.origin(), "MavenProject must declare at least one dependency");
      }

      if (pom.plugins().isEmpty()) {
         this.error(pom.origin(), "MavenProject must declare at least one plugin");
      }

      ProjectArtifact.ApplicationMain app = this.model.applicationMain();
      this.addNode(app.id(), app.origin(), "application-main");
      if (!app.path().startsWith("src/main/java/") || !app.path().endsWith(".java")) {
         this.error(app.origin(), "ApplicationMain path must be a Java source path: " + app.path());
      }

      if (!app.packageName().equals(this.model.basePackage())) {
         this.error(app.origin(), "ApplicationMain packageName must match basePackage: " + app.packageName() + " vs " + this.model.basePackage());
      }

      if (!app.simpleName().equals("Application")) {
         this.error(app.origin(), "ApplicationMain simpleName must be 'Application': " + app.simpleName());
      }

      if (!app.mapperScanPackage().equals(this.model.basePackage() + ".persistence")) {
         this.error(app.origin(), "ApplicationMain mapperScanPackage must be '<basePackage>.persistence': " + app.mapperScanPackage());
      }
   }

   private SpringBootIrValidation.VersionProfileStatus validateRoot() {
      LoweredIrVersion ir = this.model.irVersion();
      SpringBootTargetProfile profile = this.model.profile();
      boolean irKnown = LoweredIrVersion.V0_1.equals(ir) || LoweredIrVersion.V0_2.equals(ir);
      boolean profileKnown = SpringBootTargetProfile.V0_1.equals(profile) || SpringBootTargetProfile.V0_2.equals(profile);
      if (!irKnown) {
         this.error(this.model.applicationMain().origin(), "unsupported Lowered IR version: " + ir.value());
         return SpringBootIrValidation.VersionProfileStatus.UNKNOWN;
      } else if (!profileKnown) {
         this.error(this.model.applicationMain().origin(), "unsupported Spring Boot target profile: " + profile.id());
         return SpringBootIrValidation.VersionProfileStatus.UNKNOWN;
      } else {
         boolean v01 = LoweredIrVersion.V0_1.equals(ir) && SpringBootTargetProfile.V0_1.equals(profile);
         boolean v02 = LoweredIrVersion.V0_2.equals(ir) && SpringBootTargetProfile.V0_2.equals(profile);
         if (!v01 && !v02) {
            this.error(this.model.applicationMain().origin(), "Lowered IR version " + ir.value() + " does not match profile " + profile.id());
            return SpringBootIrValidation.VersionProfileStatus.MISMATCH;
         } else {
            return SpringBootIrValidation.VersionProfileStatus.OK;
         }
      }
   }

   private void validateTransportPlanMatrix() {
      Optional<ActorIdentityTransportPlan> planOpt = this.model.applicationMain().actorIdentityTransportPlan();
      long actorCount = this.model
         .declarations()
         .stream()
         .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
         .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
         .filter(cap -> cap.actor().isPresent())
         .count();
      boolean isV01 = LoweredIrVersion.V0_1.equals(this.model.irVersion());
      if (isV01) {
         if (planOpt.isPresent()) {
            this.error(this.model.applicationMain().origin(), "V0_1 Lowered IR must not carry an ActorIdentityTransportPlan");
         }
      } else if (actorCount == 0L) {
         if (planOpt.isPresent()) {
            this.error(this.model.applicationMain().origin(), "V0_2 Lowered IR with no actor capability must not carry an ActorIdentityTransportPlan");
         }
      } else if (planOpt.isEmpty()) {
         this.error(
            this.model.applicationMain().origin(), "V0_2 Lowered IR with " + actorCount + " actor capability(ies) must carry an ActorIdentityTransportPlan"
         );
      } else {
         this.validateTransportPlanExactness(planOpt.get(), actorCount);
      }
   }

   private void validateTransportPlanExactness(ActorIdentityTransportPlan plan, long expectedActorCount) {
      if (plan.policy() != ActorIdentityTransportPlan.Policy.EXPLICIT_EXTERNAL_OR_LOCAL_FIXED) {
         this.error(this.model.applicationMain().origin(), "ActorIdentityTransportPlan policy must be EXPLICIT_EXTERNAL_OR_LOCAL_FIXED: " + plan.policy());
      } else if (plan.modeProperty().equals("kcg.actor-identity.mode")
         && plan.externalAdapterBeanName().equals("kcgActorIdentityTransport")
         && plan.localProfile().equals("kcg-actor-local")
         && plan.localIdentityProperty().equals("kcg.actor-identity.local.id")) {
         List<ActorIdentityRequirement> requirements = plan.requirements();
         if (requirements.size() != expectedActorCount) {
            this.error(
               this.model.applicationMain().origin(),
               "ActorIdentityTransportPlan requirement count " + requirements.size() + " does not match actor capability count " + expectedActorCount
            );
         } else {
            Map<SymbolId, SpringBootDeclaration.EntityDeclaration> entitiesBySymbol = new LinkedHashMap<>();

            for (SpringBootDeclaration declaration : this.model.declarations()) {
               if (declaration instanceof SpringBootDeclaration.EntityDeclaration entity) {
                  entitiesBySymbol.put(entity.sourceSymbol(), entity);
               }
            }

            int requirementIndex = 0;
            Set<String> seenMethodRoute = new HashSet<>();

            for (SpringBootDeclaration declaration : this.model.declarations()) {
               if (declaration instanceof SpringBootDeclaration.CapabilityDeclaration capability && !capability.actor().isEmpty()) {
                  if (requirementIndex >= requirements.size()) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan is missing a requirement for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  ActorIdentityRequirement requirement = requirements.get(requirementIndex);
                  String methodRoute = requirement.httpMethod().name() + " " + requirement.route();
                  if (!seenMethodRoute.add(methodRoute)) {
                     this.error(this.model.applicationMain().origin(), "ActorIdentityTransportPlan contains a duplicate (httpMethod, route): " + methodRoute);
                     return;
                  }

                  if (!requirement.capabilitySymbol().equals(capability.sourceSymbol())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement at index " + requirementIndex + " does not reference capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (capability.actorBinding().isEmpty()) {
                     this.error(this.model.applicationMain().origin(), "actor capability is missing its actorBinding: " + capability.sourceSymbol());
                     return;
                  }

                  ActorBinding binding = capability.actorBinding().get();
                  if (!requirement.attributeName().equals(binding.attributeName())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement attributeName does not match actorBinding for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!requirement.identityStorageType().equals(binding.identityStorageType())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement identityStorageType does not match actorBinding for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!requirement.httpMethod().equals(capability.httpMethod())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement httpMethod does not match capability for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!requirement.route().equals(capability.route())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement route does not match capability for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  SpringBootWorkflow.Variable actorVar = capability.actor().get();

                  SymbolId expectedEntitySymbol = switch (actorVar.type()) {
                     case LoweredJavaType.Declared declared when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.symbolId();
                     case LoweredJavaType.EntityReference reference -> reference.entitySymbol();
                     default -> null;
                  };
                  if (expectedEntitySymbol == null || !requirement.actorEntitySymbol().equals(expectedEntitySymbol)) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement actorEntitySymbol does not match actor variable for capability " + capability.sourceSymbol()
                     );
                     return;
                  }

                  SpringBootDeclaration.EntityDeclaration actorEntity = entitiesBySymbol.get(requirement.actorEntitySymbol());
                  if (actorEntity == null) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement actorEntitySymbol does not reference a lowered EntityDeclaration for capability "
                           + capability.sourceSymbol()
                     );
                     return;
                  }

                  if (!actorEntity.javaName().equals(requirement.actorEntityJavaName())) {
                     this.error(
                        this.model.applicationMain().origin(),
                        "ActorIdentityTransportPlan requirement actorEntityJavaName '"
                           + requirement.actorEntityJavaName()
                           + "' does not match the typed EntityDeclaration.javaName() '"
                           + actorEntity.javaName()
                           + "' for actor entity "
                           + requirement.actorEntitySymbol()
                           + " on capability "
                           + capability.sourceSymbol()
                     );
                     return;
                  }

                  requirementIndex++;
               }
            }

            if (requirementIndex != requirements.size()) {
               this.error(
                  this.model.applicationMain().origin(),
                  "ActorIdentityTransportPlan contains " + (requirements.size() - requirementIndex) + " extra requirement(s) beyond the actor capability count"
               );
            }
         }
      } else {
         this.error(this.model.applicationMain().origin(), "ActorIdentityTransportPlan carries a frozen constant that does not match the v0.2 contract");
      }
   }

   private void indexDeclarations() {
      Set<String> javaNames = new HashSet<>();

      for (SpringBootDeclaration declaration : this.model.declarations()) {
         this.addNode(declaration.id(), declaration.origin(), "declaration");
         if (this.declarations.putIfAbsent(declaration.sourceSymbol(), declaration) != null) {
            this.error(declaration, "duplicate declaration source SymbolId: " + declaration.sourceSymbol());
         }

         if (!javaNames.add(declaration.javaName())) {
            this.error(declaration, "duplicate declaration Java name: " + declaration.javaName());
         }

         this.knownSymbols.add(declaration.sourceSymbol());
         this.indexNestedSymbols(declaration);
      }
   }

   private void indexNestedSymbols(SpringBootDeclaration declaration) {
      switch (declaration) {
         case SpringBootDeclaration.EnumDeclaration value:
            value.members().forEach(member -> this.knownSymbols.add(member.sourceSymbol()));
            break;
         case SpringBootDeclaration.EntityDeclaration value:
            this.knownSymbols.add(value.identity().sourceSymbol());
            value.fields().forEach(field -> this.knownSymbols.add(field.sourceSymbol()));
            break;
         case SpringBootDeclaration.InputDeclaration value:
            value.fields().forEach(field -> this.knownSymbols.add(field.sourceSymbol()));
            break;
         case SpringBootDeclaration.ErrorDeclaration ignored:
            break;
         case SpringBootDeclaration.CapabilityDeclaration value:
            value.actor().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));
            value.input().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));
            value.workflow().variables().forEach(variable -> this.knownSymbols.add(variable.symbol()));
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateDeclaration(SpringBootDeclaration declaration) {
      switch (declaration) {
         case SpringBootDeclaration.EnumDeclaration value:
            for (SpringBootDeclaration.EnumMember member : value.members()) {
               this.addNode(member.id(), member.origin(), "enum member");
               this.knownSymbols.add(member.sourceSymbol());
            }
            break;
         case SpringBootDeclaration.EntityDeclaration value:
            this.addNode(value.identity().id(), value.identity().origin(), "identity");
            this.knownSymbols.add(value.identity().sourceSymbol());
            this.validateType(value.identity().type(), value);

            for (SpringBootDeclaration.Property field : value.fields()) {
               this.validateProperty(field, value);
            }
            break;
         case SpringBootDeclaration.InputDeclaration value:
            for (SpringBootDeclaration.Property field : value.fields()) {
               this.validateProperty(field, value);
            }
            break;
         case SpringBootDeclaration.ErrorDeclaration ignored:
            break;
         case SpringBootDeclaration.CapabilityDeclaration value:
            this.validateCapability(value);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateProperty(SpringBootDeclaration.Property property, SpringBootDeclaration owner) {
      this.addNode(property.id(), property.origin(), "property");
      this.knownSymbols.add(property.sourceSymbol());
      this.validateType(property.type(), owner);

      for (SpringBootDeclaration.Constraint constraint : property.constraints()) {
         this.addNode(constraint.id(), constraint.origin(), "constraint");
      }
   }

   private void validateCapability(SpringBootDeclaration.CapabilityDeclaration capability) {
      capability.actor().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));
      capability.input().ifPresent(variable -> this.knownSymbols.add(variable.symbol()));

      for (SpringBootWorkflow.Variable variable : capability.workflow().variables()) {
         this.knownSymbols.add(variable.symbol());
         this.validateType(variable.type(), capability);
      }

      this.validateType(capability.outputType(), capability);

      for (SymbolId failure : capability.failures()) {
         if (!(this.declarations.get(failure) instanceof SpringBootDeclaration.ErrorDeclaration)) {
            this.error(capability, "capability failure does not reference a lowered Error: " + failure);
         }
      }

      if (capability.kind() == SpringBootDeclaration.CapabilityKind.QUERY
         && (capability.httpMethod() != SpringBootDeclaration.HttpMethod.GET || capability.transactionMode() != SpringBootDeclaration.TransactionMode.READ_ONLY)
         )
       {
         this.error(capability, "query capability must use GET and a read-only transaction");
      }

      if (capability.kind() == SpringBootDeclaration.CapabilityKind.COMMAND && capability.httpMethod() != SpringBootDeclaration.HttpMethod.POST) {
         this.error(capability, "command capability must use POST");
      }

      this.validateActorBinding(capability);
      this.validateTransportPlan(capability);
      this.validateWorkflow(capability);
   }

   private void validateActorBinding(SpringBootDeclaration.CapabilityDeclaration capability) {
      if (capability.actor().isPresent() && capability.actorBinding().isEmpty()) {
         this.error(capability, "capability has actor but missing actorBinding");
      }

      if (capability.actor().isEmpty() && capability.actorBinding().isPresent()) {
         this.error(capability, "capability has actorBinding but no actor");
      }

      capability.actorBinding().ifPresent(binding -> {
         if (binding.kind() != ActorBinding.ActorKind.REQUEST_ATTRIBUTE) {
            this.error(capability, "actorBinding kind must be REQUEST_ATTRIBUTE: " + binding.kind());
         }

         if (!binding.attributeName().equals("actorId")) {
            this.error(capability, "actorBinding attributeName must be 'actorId': " + binding.attributeName());
         }

         capability.actor().ifPresent(actor -> {
            LoweredJavaType.Scalar expectedIdentity = this.actorIdentityType(actor.type());
            if (expectedIdentity == null || !binding.identityStorageType().equals(expectedIdentity)) {
               this.error(capability, "actorBinding identityStorageType does not match actor Entity identity: " + binding.identityStorageType());
            }
         });
      });
   }

   private LoweredJavaType.Scalar actorIdentityType(LoweredJavaType actorType) {
      SymbolId entitySymbol = switch (actorType) {
         case LoweredJavaType.Declared declared when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.symbolId();
         case LoweredJavaType.EntityReference reference -> reference.entitySymbol();
         default -> null;
      };
      return (entitySymbol == null ? null : (SpringBootDeclaration)this.declarations.get(entitySymbol)) instanceof SpringBootDeclaration.EntityDeclaration entity
         ? entity.identity().type()
         : null;
   }

   private void validateTransportPlan(SpringBootDeclaration.CapabilityDeclaration capability) {
      TransportPlan plan = capability.transportPlan();
      TransportPlan.InputBinding expectedInput;
      if (capability.input().isEmpty()) {
         expectedInput = TransportPlan.InputBinding.NONE;
      } else if (capability.httpMethod() == SpringBootDeclaration.HttpMethod.POST) {
         expectedInput = TransportPlan.InputBinding.REQUEST_BODY;
      } else {
         expectedInput = TransportPlan.InputBinding.MODEL_ATTRIBUTE;
      }

      if (plan.inputBinding() != expectedInput) {
         this.error(
            capability, "transportPlan inputBinding must be " + expectedInput + " for httpMethod " + capability.httpMethod() + ": " + plan.inputBinding()
         );
      }

      TransportPlan.ResponseRepresentation expectedResponse = this.expectedResponse(capability.outputType());
      if (plan.responseRepresentation() != expectedResponse) {
         this.error(
            capability,
            "transportPlan responseRepresentation must be "
               + expectedResponse
               + " for outputType "
               + capability.outputType()
               + ": "
               + plan.responseRepresentation()
         );
      }
   }

   private TransportPlan.ResponseRepresentation expectedResponse(LoweredJavaType outputType) {
      return switch (outputType) {
         case LoweredJavaType.Scalar scalar -> scalar.kind() == LoweredJavaType.ScalarKind.VOID
            ? TransportPlan.ResponseRepresentation.VOID
            : TransportPlan.ResponseRepresentation.VALUE;
         case LoweredJavaType.EntityReference ignored -> TransportPlan.ResponseRepresentation.ENTITY_BODY;
         case LoweredJavaType.Declared declared -> declared.kind() == LoweredJavaType.DeclaredKind.ENTITY
            ? TransportPlan.ResponseRepresentation.ENTITY_BODY
            : TransportPlan.ResponseRepresentation.VALUE;
         case LoweredJavaType.ListValue ignored -> TransportPlan.ResponseRepresentation.LIST;
         case LoweredJavaType.OptionalValue ignored -> TransportPlan.ResponseRepresentation.OPTIONAL;
         default -> throw new MatchException(null, null);
      };
   }

   private void validateWorkflow(SpringBootDeclaration.CapabilityDeclaration capability) {
      SpringBootWorkflow workflow = capability.workflow();
      this.addNode(workflow.id(), workflow.origin(), "workflow");
      long returnCount = workflow.steps().stream().filter(SpringBootWorkflow.ReturnStep.class::isInstance).count();
      if (returnCount != 1L || workflow.steps().isEmpty() || !(workflow.steps().get(workflow.steps().size() - 1) instanceof SpringBootWorkflow.ReturnStep)) {
         this.error(capability, "workflow must contain exactly one final Return step");
      }

      for (SpringBootWorkflow.Step step : workflow.steps()) {
         this.addNode(step.id(), step.origin(), "workflow step");
         this.validateStep(step, capability);
      }
   }

   private void validateStep(SpringBootWorkflow.Step step, SpringBootDeclaration.CapabilityDeclaration owner) {
      switch (step) {
         case SpringBootWorkflow.ValidateStep value:
            this.validateExpression(value.condition(), owner);
            this.requireDeclaration(value.errorSymbol(), SpringBootDeclaration.ErrorDeclaration.class, owner);
            break;
         case SpringBootWorkflow.LoadStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.idExpression(), owner);
            this.requireKnown(value.result().symbol(), owner, "load result variable");
            this.requireDeclaration(value.errorSymbol(), SpringBootDeclaration.ErrorDeclaration.class, owner);
            break;
         case SpringBootWorkflow.FindStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.validateExpression(value.predicate(), owner);
            this.requireKnown(value.result().symbol(), owner, "find result variable");
            this.requireKnown(value.itemVariable().symbol(), owner, "find item variable");
            break;
         case SpringBootWorkflow.CreateStep value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            this.requireKnown(value.result().symbol(), owner, "create result variable");
            value.bindings().forEach(binding -> this.validateBinding(binding, owner));
            break;
         case SpringBootWorkflow.UpdateStep value:
            this.requireKnown(value.targetVariable(), owner, "update target variable");
            value.bindings().forEach(binding -> this.validateBinding(binding, owner));
            break;
         case SpringBootWorkflow.PersistStep value:
            this.requireKnown(value.targetVariable(), owner, "persist target variable");
            break;
         case SpringBootWorkflow.ReturnStep value:
            this.validateExpression(value.value(), owner);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateBinding(SpringBootWorkflow.Binding binding, SpringBootDeclaration.CapabilityDeclaration owner) {
      this.requireKnown(binding.fieldSymbol(), owner, "binding field");
      this.validateExpression(binding.value(), owner);
   }

   private void validateExpression(SpringExpression expression, SpringBootDeclaration owner) {
      this.addNode(expression.id(), expression.origin(), "expression");
      this.validateType(expression.type(), owner);
      switch (expression) {
         case SpringExpression.NameExpression value:
            this.requireKnown(value.resolvedSymbol(), owner, "name expression");
            break;
         case SpringExpression.MemberExpression value:
            this.validateExpression(value.receiver(), owner);
            this.requireKnown(value.resolvedMember(), owner, "member expression");
            break;
         case SpringExpression.UnaryExpression value:
            this.validateExpression(value.operand(), owner);
            break;
         case SpringExpression.BinaryExpression value:
            this.validateExpression(value.left(), owner);
            this.validateExpression(value.right(), owner);
            break;
         default:
      }
   }

   private void validateType(LoweredJavaType type, SpringBootDeclaration owner) {
      switch (type) {
         case LoweredJavaType.Scalar ignored:
            break;
         case LoweredJavaType.OptionalValue value:
            this.validateType(value.elementType(), owner);
            break;
         case LoweredJavaType.ListValue value:
            this.validateType(value.elementType(), owner);
            break;
         case LoweredJavaType.EntityReference value:
            this.requireDeclaration(value.entitySymbol(), SpringBootDeclaration.EntityDeclaration.class, owner);
            break;
         case LoweredJavaType.Declared value:
            Class<? extends SpringBootDeclaration> expected = switch (value.kind()) {
               case ENUM -> SpringBootDeclaration.EnumDeclaration.class;
               case ENTITY -> SpringBootDeclaration.EntityDeclaration.class;
               case INPUT -> SpringBootDeclaration.InputDeclaration.class;
            };
            this.requireDeclaration(value.symbolId(), expected, owner);
            break;
         default:
            throw new MatchException(null, null);
      }
   }

   private void validateArtifacts() {
      Set<String> qualifiedNames = new HashSet<>();
      Map<SymbolId, Set<SpringArtifact.Role>> actualRoles = new LinkedHashMap<>();

      for (SpringArtifact artifact : this.model.artifacts()) {
         this.addNode(artifact.id(), artifact.origin(), "artifact");
         if (!this.declarations.containsKey(artifact.ownerSymbol())) {
            this.error(artifact.origin(), "artifact owner is not a lowered declaration: " + artifact.ownerSymbol());
         }

         if (!qualifiedNames.add(artifact.qualifiedName())) {
            this.error(artifact.origin(), "duplicate artifact qualified name: " + artifact.qualifiedName());
         }

         actualRoles.computeIfAbsent(artifact.ownerSymbol(), ignored -> EnumSet.noneOf(SpringArtifact.Role.class)).add(artifact.role());
      }

      for (SpringBootDeclaration declaration : this.model.declarations()) {
         Set<SpringArtifact.Role> expected = this.expectedRoles(declaration);
         Set<SpringArtifact.Role> actual = actualRoles.getOrDefault(declaration.sourceSymbol(), Set.of());
         if (!actual.equals(expected)) {
            this.error(declaration, "artifact ownership mismatch; expected " + expected + " but found " + actual);
         }
      }
   }

   private Set<SpringArtifact.Role> expectedRoles(SpringBootDeclaration declaration) {
      return switch (declaration) {
         case SpringBootDeclaration.EnumDeclaration ignored -> EnumSet.of(SpringArtifact.Role.ENUM);
         case SpringBootDeclaration.EntityDeclaration ignored -> EnumSet.of(SpringArtifact.Role.ENTITY_MODEL, SpringArtifact.Role.MAPPER);
         case SpringBootDeclaration.InputDeclaration ignored -> EnumSet.of(SpringArtifact.Role.REQUEST_DTO);
         case SpringBootDeclaration.ErrorDeclaration ignored -> EnumSet.of(SpringArtifact.Role.EXCEPTION);
         case SpringBootDeclaration.CapabilityDeclaration ignored -> EnumSet.of(SpringArtifact.Role.SERVICE, SpringArtifact.Role.CONTROLLER);
         default -> throw new MatchException(null, null);
      };
   }

   private void requireDeclaration(SymbolId id, Class<? extends SpringBootDeclaration> expected, SpringBootDeclaration owner) {
      if (!expected.isInstance(this.declarations.get(id))) {
         this.error(owner, "SymbolId does not reference " + expected.getSimpleName() + ": " + id);
      }
   }

   private void requireKnown(SymbolId id, SpringBootDeclaration owner, String role) {
      if (!this.knownSymbols.contains(id) || id.value().startsWith("sir://unknown")) {
         this.error(owner, role + " is not present in the Lowered IR symbol manifest: " + id);
      }
   }

   private void addNode(LoweredNodeId id, LoweredOrigin origin, String role) {
      if (!this.nodeIds.add(id)) {
         this.error(origin, "duplicate LoweredNodeId for " + role + ": " + id);
      }
   }

   private void error(SpringBootDeclaration declaration, String message) {
      if (declaration != null) {
         this.error(declaration.origin(), message);
      }
   }

   private void error(LoweredOrigin origin, String message) {
      this.diagnostics.add(LoweringDiagnostics.error("SIR-LOWER-IR-001", message, origin.span(), origin.ownerSymbol().orElse(null), origin.sourceNodeId()));
   }

   private enum VersionProfileStatus {
      OK,
      MISMATCH,
      UNKNOWN;
   }
}
