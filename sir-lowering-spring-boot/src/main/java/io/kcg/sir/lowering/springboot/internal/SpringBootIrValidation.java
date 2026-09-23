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
import io.kcg.sir.lowering.springboot.profile.SpringBootQueryPolicy;
import io.kcg.sir.lowering.springboot.profile.SpringBootQueryPolicy;
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

      this.validatePaginationSupport(app);
   }

   /**
   * The page envelope has to appear exactly when a capability pages, and its identity must match the
   * package the generator writes it to.
   */
   private void validatePaginationSupport(ProjectArtifact.ApplicationMain app) {
      List<ProjectArtifact.PageResponse> pages = this.model.projectArtifacts().stream()
         .filter(ProjectArtifact.PageResponse.class::isInstance)
         .map(ProjectArtifact.PageResponse.class::cast)
         .toList();
      boolean pagesQuery = this.model.declarations().stream()
         .filter(SpringBootDeclaration.CapabilityDeclaration.class::isInstance)
         .map(SpringBootDeclaration.CapabilityDeclaration.class::cast)
         .flatMap(capability -> capability.workflow().steps().stream())
         .anyMatch(step -> step instanceof SpringBootWorkflow.FindStep find && find.page().isPresent());

      for (ProjectArtifact artifact : this.model.projectArtifacts()) {
         this.addNode(artifact.id(), artifact.origin(), "project artifact");
      }

      for (ProjectArtifact.PageResponse page : pages) {
         if (!page.path().startsWith("src/main/java/") || !page.path().endsWith(".java")) {
         this.error(page.origin(), "PageResponse path must be a Java source path: " + page.path());
         }

         String expectedPackage = this.model.basePackage() + ".api";
         if (!page.packageName().equals(expectedPackage)) {
         this.error(page.origin(), "PageResponse packageName must be '" + expectedPackage + "': " + page.packageName());
         }

         String expectedPath = "src/main/java/" + expectedPackage.replace('.', '/') + "/" + page.simpleName() + ".java";
         if (!page.path().equals(expectedPath)) {
         this.error(page.origin(), "PageResponse path must be '" + expectedPath + "': " + page.path());
         }
      }

      if (pagesQuery && pages.size() != 1) {
         this.error(app.origin(), "pagination requires exactly one PageResponse project artifact: " + pages.size());
      }

      if (!pagesQuery && !pages.isEmpty()) {
         this.error(app.origin(), "PageResponse is present but no capability declares a paged find");
      }
   }

   private SpringBootQueryPolicy queryPolicy() {
      return SpringBootQueryPolicy.V0_1;
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
         case SpringBootDeclaration.ViewDeclaration value:
         value.fields().forEach(field -> {
               this.knownSymbols.add(field.sourceSymbol());
               this.knownSymbols.add(field.sourceFieldSymbol());
         });
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

            this.validateEntityVersion(value);
            break;
         case SpringBootDeclaration.InputDeclaration value:
            for (SpringBootDeclaration.Property field : value.fields()) {
               this.validateProperty(field, value);
            }

            this.validatePatchSpec(value);
            break;
         case SpringBootDeclaration.ViewDeclaration value:
         this.validateView(value);
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

   /**
   * The version spec must point at a declared field of its own entity and carry an integer type.
   *
   * <p>The version column is written by the target's conditional update, so an entity that declares a
   * version but no matching field would leave that update with nothing to compare against.
   */
   private void validateEntityVersion(SpringBootDeclaration.EntityDeclaration entity) {
      if (entity.version().isEmpty()) {
         return;
      }

      SpringBootDeclaration.VersionSpec version = entity.version().orElseThrow();
      this.addNode(version.id(), version.origin(), "version");
      this.knownSymbols.add(version.fieldSymbol());
      this.validateType(version.type(), entity);
      SpringBootDeclaration.Property field = entity.fields().stream()
         .filter(property -> property.sourceSymbol().equals(version.fieldSymbol()))
         .findFirst()
         .orElse(null);
      if (field == null) {
         this.error(entity, "the version field must be a declared field of " + entity.javaName());
         return;
      }

      if (!version.javaName().equals(field.javaName()) || !version.columnName().equals(field.columnName().orElse(null))) {
         this.error(entity, "the version spec must name its own field and column: " + version.fieldSymbol());
      }
   }

   /**
   * A patch payload's envelope must match the entity: the identity, and a change set that covers
   * exactly the payload fields outside it.
   */
   private void validatePatchSpec(SpringBootDeclaration.InputDeclaration input) {
      if (input.patch().isEmpty()) {
         return;
      }

      SpringBootDeclaration.PatchSpec patch = input.patch().orElseThrow();
      this.requireDeclaration(patch.sourceEntitySymbol(), SpringBootDeclaration.EntityDeclaration.class, input);
      SpringBootDeclaration.EntityDeclaration entity = this.declarations.get(patch.sourceEntitySymbol())
         instanceof SpringBootDeclaration.EntityDeclaration value ? value : null;

      if (input.fields().stream().noneMatch(field -> field.sourceSymbol().equals(patch.identityFieldSymbol()))) {
         this.error(input, "the patch identity field must be a declared payload field: " + patch.identityFieldSymbol());
      }

      if (entity != null && !entity.identity().sourceSymbol().equals(
            entity.fields().stream()
               .filter(field -> field.javaName().equals(patch.identityPropertyName()))
               .map(SpringBootDeclaration.Property::sourceSymbol)
               .findFirst()
               .orElse(entity.identity().sourceSymbol()))) {
         this.error(input, "the patch identity must name the entity's own identity: " + patch.identityPropertyName());
      }

      List<SymbolId> payloadFields = input.fields().stream().map(SpringBootDeclaration.Property::sourceSymbol).toList();
      List<SymbolId> changeFields = patch.changes().stream().map(SpringBootDeclaration.PatchChange::payloadFieldSymbol).toList();
      if (changeFields.stream().anyMatch(field -> !payloadFields.contains(field))) {
         this.error(input, "every patch change must be a declared payload field");
      }

      if (new java.util.LinkedHashSet<>(changeFields).size() != changeFields.size()) {
         this.error(input, "a patch change must appear once per payload field");
      }

      if (payloadFields.size() != changeFields.size() + 1) {
         this.error(input, "the change set must be exactly the payload fields outside its identity");
      }

      for (SpringBootDeclaration.PatchChange change : patch.changes()) {
         this.knownSymbols.add(change.payloadFieldSymbol());
         this.knownSymbols.add(change.entityFieldSymbol());
         if (entity != null && entity.fields().stream().noneMatch(field -> field.sourceSymbol().equals(change.entityFieldSymbol()))) {
            this.error(input, "a patch change must name a field of its entity: " + change.entityFieldSymbol());
         }
      }
   }

   /**
   * A view must project an entity, and each projected field must carry the very type of the entity
   * field it reads.
   */
   private void validateView(SpringBootDeclaration.ViewDeclaration view) {
      this.requireDeclaration(view.sourceEntitySymbol(), SpringBootDeclaration.EntityDeclaration.class, view);
      SpringBootDeclaration.EntityDeclaration entity = this.declarations.get(view.sourceEntitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration value
         ? value
         : null;
      if (entity != null && !entity.javaName().equals(view.sourceEntityJavaName())) {
         this.error(view, "view source entity Java name must match its entity: " + view.sourceEntityJavaName());
      }

      for (SpringBootDeclaration.ViewField field : view.fields()) {
         this.addNode(field.id(), field.origin(), "view field");
         this.knownSymbols.add(field.sourceSymbol());
         this.knownSymbols.add(field.sourceFieldSymbol());
         this.validateType(field.type(), view);
         if (field.relation().isPresent()) {
            this.validateViewRelation(view, field, entity);
            continue;
         }

         if (entity == null) {
         continue;
         }

         LoweredJavaType sourceType = this.entityFieldType(entity, field.sourceFieldSymbol());
         if (sourceType == null) {
         this.error(view, "view field does not project a field of " + entity.javaName() + ": " + field.sourceFieldSymbol());
         } else if (!sourceType.equals(field.type())) {
         this.error(view, "view field type must equal its source entity field type: " + field.javaName());
         }
      }
   }

   /**
   * A relation projection must name the two declarations it joins and the properties the batch read
   * uses, and those properties must exist on the sides the plan says they do.
   *
   * <p>The type rule of a column projection deliberately does not apply here: a relation reads a
   * reference, and the view it produces is a different type entirely.
   */
   private void validateViewRelation(
      SpringBootDeclaration.ViewDeclaration view, SpringBootDeclaration.ViewField field, SpringBootDeclaration.EntityDeclaration entity) {
      SpringBootDeclaration.ViewRelationPlan plan = field.relation().orElseThrow();
      this.addNode(plan.id(), plan.origin(), "view relation plan");
      LoweredJavaType element = field.type() instanceof LoweredJavaType.ListValue list ? list.elementType() : field.type();
      boolean projectsThePlannedView = element instanceof LoweredJavaType.Declared declared
         && declared.kind() == LoweredJavaType.DeclaredKind.VIEW
         && declared.symbolId().equals(plan.targetViewSymbol());
      if (!projectsThePlannedView) {
         this.error(view, "a relation must project the view its plan reads: " + field.javaName() + " -> " + field.type());
      }
      SpringBootDeclaration.ViewDeclaration target = this.declarations.get(plan.targetViewSymbol()) instanceof SpringBootDeclaration.ViewDeclaration value
         ? value
         : null;
      this.requireDeclaration(plan.targetViewSymbol(), SpringBootDeclaration.ViewDeclaration.class, view);
      SpringBootDeclaration.EntityDeclaration targetEntity = this.declarations.get(plan.targetEntitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration value
         ? value
         : null;
      this.requireDeclaration(plan.targetEntitySymbol(), SpringBootDeclaration.EntityDeclaration.class, view);
      if (target != null && target.sourceEntitySymbol() != plan.targetEntitySymbol()) {
         this.error(view, "a relation must read the entity its target view projects: " + field.javaName());
      }

      if (entity == null || targetEntity == null) {
         return;
      }

      if (plan.cardinality() == SpringBootDeclaration.RelationCardinality.TO_MANY) {
         if (!this.hasProperty(targetEntity, plan.targetLookupPropertyName())) {
            this.error(view, "a collection relation must key on a property of the related entity: " + plan.targetLookupPropertyName());
         }

         if (!this.hasProperty(entity, plan.sourceKeyPropertyName())) {
            this.error(view, "a collection relation must collect its keys from the projecting entity: " + plan.sourceKeyPropertyName());
         }

         if (plan.orderPropertyName().isEmpty() || !this.hasProperty(targetEntity, plan.orderPropertyName().get())) {
            this.error(view, "a collection relation must state the identity it is ordered by: " + field.javaName());
         }
      } else {
         if (!this.hasProperty(targetEntity, plan.targetLookupPropertyName())) {
            this.error(view, "a single-row relation must look the related row up by its own property: " + plan.targetLookupPropertyName());
         }

         if (!this.hasProperty(entity, plan.sourceKeyPropertyName())) {
            this.error(view, "a single-row relation must read its reference from the projecting entity: " + plan.sourceKeyPropertyName());
         }

         if (plan.orderPropertyName().isPresent()) {
            this.error(view, "a single-row relation is not ordered: " + field.javaName());
         }
      }

      if (!plan.indexKeyPropertyName().equals(plan.targetLookupPropertyName())) {
         this.error(view, "a relation is indexed by the property it was read with: " + field.javaName());
      }
   }

   private boolean hasProperty(SpringBootDeclaration.EntityDeclaration entity, String javaName) {
      if (entity.identity().javaName().equals(javaName)) {
         return true;
      }

      return entity.fields().stream().anyMatch(property -> property.javaName().equals(javaName));
   }

   private LoweredJavaType entityFieldType(SpringBootDeclaration.EntityDeclaration entity, SymbolId fieldSymbol) {
      if (entity.identity().sourceSymbol().equals(fieldSymbol)) {
         return entity.identity().type();
      }

      for (SpringBootDeclaration.Property property : entity.fields()) {
         if (property.sourceSymbol().equals(fieldSymbol)) {
         return property.type();
         }
      }

      return null;
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

      if (capability.kind() == SpringBootDeclaration.CapabilityKind.COMMAND
         && capability.httpMethod() != SpringBootDeclaration.HttpMethod.POST
         && capability.httpMethod() != SpringBootDeclaration.HttpMethod.PATCH) {
         this.error(capability, "command capability must use POST or PATCH");
      }

      boolean patchPayload = this.payloadOf(capability).flatMap(SpringBootDeclaration.InputDeclaration::patch).isPresent();
      if (patchPayload && capability.httpMethod() != SpringBootDeclaration.HttpMethod.PATCH) {
         this.error(capability, "a capability whose payload patches an entity must use PATCH");
      }

      this.validateActorBinding(capability);
      this.validateTransportPlan(capability);
      this.validateWorkflow(capability);
      this.validatePageDemand(capability);
      this.validateWriteWorkflow(capability);
   }

   /**
   * The write slice's invariants: a projection answers with a declared view, and a conditional update
   * only appears for a versioned entity whose payload supplies every assigned column.
   */
   private void validateWriteWorkflow(SpringBootDeclaration.CapabilityDeclaration capability) {
      if (capability.transportPlan().responseRepresentation() == TransportPlan.ResponseRepresentation.PROJECTION
         && !(this.declarations.get(this.declaredTypeSymbol(capability.outputType())) instanceof SpringBootDeclaration.ViewDeclaration)) {
         this.error(capability, "a projected response must declare a view output");
      }

      SpringBootDeclaration.PatchSpec patch = this.payloadOf(capability)
         .flatMap(SpringBootDeclaration.InputDeclaration::patch)
         .orElse(null);
      long conditionalUpdates = 0L;
      for (SpringBootWorkflow.Step step : capability.workflow().steps()) {
         if (!(step instanceof SpringBootWorkflow.UpdateStep update)) {
            if (step instanceof SpringBootWorkflow.LoadStep load && load.forUpdate()) {
               if (this.versionedEntity(load.entitySymbol()) == null) {
                  this.error(capability, "only a versioned entity is loaded for update: " + load.entitySymbol());
               }

               // A lock without a version-bounded write would hold a row for no reason, which is the
               // shape the target must never generate by accident.
               boolean underConditionalUpdate = capability.workflow().steps().stream()
                  .anyMatch(candidate -> candidate instanceof SpringBootWorkflow.UpdateStep update
                     && update.conditional().isPresent()
                     && update.targetVariable().equals(load.result().symbol()));
               if (!underConditionalUpdate) {
                  this.error(capability,
                     "a load for update must precede a conditional update on the same variable: " + load.result().symbol());
               }
            }

            if (step instanceof SpringBootWorkflow.ValidateStep validate) {
               this.validatePresence(capability, validate.condition(), patch);
            }

            if (step instanceof SpringBootWorkflow.PersistStep persist) {
               persist.conditional().ifPresent(conditional -> this.validateConditionalUpdate(capability, conditional, patch));
            }

            continue;
         }

         if (update.conditional().isEmpty()) {
            continue;
         }

         conditionalUpdates++;
         this.validateConditionalUpdate(capability, update.conditional().orElseThrow(), patch);
      }

      if (conditionalUpdates > 1L) {
         this.error(capability, "a capability performs at most one conditional update");
      }
   }

   /**
   * A presence test must ask about a property of this capability's change set.
   *
   * <p>Presence is a fact of the decoded payload, so a test naming anything else would compile into a
   * question the request cannot answer.
   */
   private void validatePresence(
      SpringBootDeclaration.CapabilityDeclaration capability,
      SpringExpression expression,
      SpringBootDeclaration.PatchSpec patch
   ) {
      switch (expression) {
         case SpringExpression.PayloadPresence presence -> {
            if (patch == null) {
               this.error(capability, "a presence test requires its entity's patch payload");
               return;
            }

            boolean known = patch.changes().stream()
               .anyMatch(change -> change.payloadPropertyName().equals(presence.sourcePropertyName()));
            if (!known) {
               this.error(capability, "a presence test must name a change set property, got an unknown change set property: "
                  + presence.sourcePropertyName());
            }
         }
         case SpringExpression.UnaryExpression unary -> this.validatePresence(capability, unary.operand(), patch);
         case SpringExpression.BinaryExpression binary -> {
            this.validatePresence(capability, binary.left(), patch);
            this.validatePresence(capability, binary.right(), patch);
         }
         default -> {
         }
      }
   }

   private void validateConditionalUpdate(
      SpringBootDeclaration.CapabilityDeclaration capability,
      SpringBootWorkflow.ConditionalUpdate conditional,
      SpringBootDeclaration.PatchSpec patch
   ) {
      SpringBootDeclaration.EntityDeclaration entity = this.versionedEntity(conditional.entitySymbol());
      if (entity == null) {
         this.error(capability, "a conditional update must target a versioned entity: " + conditional.entitySymbol());
         return;
      }

      SpringBootDeclaration.VersionSpec version = entity.version().orElseThrow();
      if (!version.fieldSymbol().equals(conditional.versionFieldSymbol())
         || !version.columnName().equals(conditional.versionColumnName())) {
         this.error(capability, "a conditional update must bound on its own entity's version column");
      }

      if (!entity.identity().sourceSymbol().equals(conditional.identityFieldSymbol())
         || !entity.identity().javaName().equals(conditional.identityPropertyName())) {
         this.error(capability, "a conditional update must bound on its own entity's identity");
      }

      if (patch == null) {
         this.error(capability, "a conditional update requires its entity's patch payload");
         return;
      }

      List<SymbolId> assigned = conditional.assignments().stream()
         .map(SpringBootWorkflow.ColumnAssignment::entityFieldSymbol)
         .toList();
      List<SymbolId> changed = patch.changes().stream()
         .map(SpringBootDeclaration.PatchChange::entityFieldSymbol)
         .toList();
      if (!assigned.equals(changed)) {
         this.error(capability, "a conditional update must assign exactly the payload's change set, in order");
      }
   }

   /**
   * The payload declaration a capability is given.
   *
   * <p>The capability's input is its input variable, whose declared type names the payload, so the
   * lookup follows the resolved type rather than guessing from a name.
   */
   private java.util.Optional<SpringBootDeclaration.InputDeclaration> payloadOf(
      SpringBootDeclaration.CapabilityDeclaration capability
   ) {
      return capability.input()
         .map(SpringBootWorkflow.Variable::type)
         .filter(LoweredJavaType.Declared.class::isInstance)
         .map(LoweredJavaType.Declared.class::cast)
         .map(LoweredJavaType.Declared::symbolId)
         .map(this.declarations::get)
         .filter(SpringBootDeclaration.InputDeclaration.class::isInstance)
         .map(SpringBootDeclaration.InputDeclaration.class::cast);
   }

   private SpringBootDeclaration.EntityDeclaration versionedEntity(SymbolId entitySymbol) {
      return this.declarations.get(entitySymbol) instanceof SpringBootDeclaration.EntityDeclaration entity
         && entity.version().isPresent() ? entity : null;
   }

   private SymbolId declaredTypeSymbol(LoweredJavaType type) {
      return type instanceof LoweredJavaType.Declared declared ? declared.symbolId() : null;
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
      } else if (capability.httpMethod() == SpringBootDeclaration.HttpMethod.POST
         || capability.httpMethod() == SpringBootDeclaration.HttpMethod.PATCH) {
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
      if (plan.responseRepresentation() == TransportPlan.ResponseRepresentation.PROJECTION
         && capability.outputType() instanceof LoweredJavaType.Declared declared
         && declared.kind() == LoweredJavaType.DeclaredKind.VIEW) {
         return;
      }

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
         case LoweredJavaType.PageValue ignored -> TransportPlan.ResponseRepresentation.PAGE;
         default -> throw new MatchException(null, null);
      };
   }

   /** A page output and a paged find step only make sense together. */
   private void validatePageDemand(SpringBootDeclaration.CapabilityDeclaration capability) {
      long paged = capability.workflow().steps().stream()
         .filter(SpringBootWorkflow.FindStep.class::isInstance)
         .map(SpringBootWorkflow.FindStep.class::cast)
         .filter(find -> find.page().isPresent())
         .count();
      boolean pageOutput = capability.outputType() instanceof LoweredJavaType.PageValue;
      if (pageOutput && paged != 1L) {
         this.error(capability, "Page output requires exactly one paged find step, found " + paged);
      }

      if (!pageOutput && paged > 0L) {
         this.error(capability, "a paged find step requires a Page output");
      }
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
             this.validateFindQuery(value, owner);
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

   /**
   * Ordering, pagination and literal matches must all point at declared entity fields, and the
   * literal-match plan must describe exactly the predicate's literal matches — in both directions.
   */
   private void validateFindQuery(SpringBootWorkflow.FindStep find, SpringBootDeclaration.CapabilityDeclaration owner) {
      SpringBootDeclaration.EntityDeclaration entity = this.declarations.get(find.entitySymbol()) instanceof SpringBootDeclaration.EntityDeclaration value
         ? value
         : null;
      for (SpringBootWorkflow.OrderKey key : find.orderKeys()) {
         this.requireKnown(key.fieldSymbol(), owner, "order key");
         if (entity != null && this.entityFieldType(entity, key.fieldSymbol()) == null) {
         this.error(owner, "order key is not a field of the find entity: " + key.fieldSymbol());
         }
      }

      find.page().ifPresent(page -> this.validatePageSpec(owner, page));
      this.validateStringMatches(find, owner);
      this.validateStatementBudget(find, owner);

      if (find.page().isPresent() && !(find.result().type() instanceof LoweredJavaType.PageValue)) {
         this.error(owner, "a paged find must produce a Page value: " + find.result().type());
      }
   }

   /**
   * The declared read budget must match what the statement promises: the page reads, and one batch
   * read for every association the response projection nests.
   *
   * <p>The budget is what the target is held to on a real database, so it is recomputed here from
   * the projection rather than trusted; a projection that grows an association without the budget
   * following it is a contradiction inside the IR.
   */
   private void validateStatementBudget(SpringBootWorkflow.FindStep find, SpringBootDeclaration.CapabilityDeclaration owner) {
      int pageStatements = find.page().isPresent() ? 2 : 1;
      int associations = this.associationReads(find.result().type());
      SpringBootWorkflow.StatementBudget budget = find.statementBudget();
      if (budget.pageStatements() != pageStatements) {
         this.error(owner, "the page reads of a find must be " + pageStatements + " but the budget says " + budget.pageStatements());
      }

      if (budget.associationStatements() != associations) {
         this.error(owner, "the response projection nests " + associations + " batch reads but the budget says "
            + budget.associationStatements());
      }
   }

   /** The batch reads the rows a find returns need, counted through the projection it answers with. */
   private int associationReads(LoweredJavaType type) {
      LoweredJavaType element = switch (type) {
         case LoweredJavaType.PageValue value -> value.elementType();
         case LoweredJavaType.ListValue value -> value.elementType();
         default -> type;
      };
      if (!(element instanceof LoweredJavaType.Declared declared)
         || declared.kind() != LoweredJavaType.DeclaredKind.VIEW
         || !(this.declarations.get(declared.symbolId()) instanceof SpringBootDeclaration.ViewDeclaration view)) {
         return 0;
      }

      int associations = 0;
      for (SpringBootDeclaration.ViewField field : view.fields()) {
         if (field.relation().isPresent()) {
            associations += 1 + this.associationReads(field.type());
         }
      }

      return associations;
   }

   private void validatePageSpec(SpringBootDeclaration.CapabilityDeclaration owner, SpringBootWorkflow.PageSpec page) {
      this.requireDeclaration(page.errorSymbol(), SpringBootDeclaration.ErrorDeclaration.class, owner);
      this.requireKnown(page.pageFieldSymbol(), owner, "pagination page field");
      this.requireKnown(page.sizeFieldSymbol(), owner, "pagination size field");
      if (page.pageFieldSymbol().equals(page.sizeFieldSymbol())) {
         this.error(owner, "pagination requires two distinct input fields");
      }

      if (!owner.failures().contains(page.errorSymbol())) {
         this.error(owner, "pagination error must be declared in the capability failures: " + page.errorSymbol());
      }

      if (page.defaultSize() != this.queryPolicy().pageDefaultSize()
         || page.maxSize() != this.queryPolicy().pageMaxSize()
         || page.maxPageNumber() != this.queryPolicy().pageMaxNumber()) {
         this.error(owner, "pagination bounds must match the target query policy: default=" + page.defaultSize()
         + " maxSize=" + page.maxSize() + " maxPageNumber=" + page.maxPageNumber());
      }
   }

   private void validateStringMatches(SpringBootWorkflow.FindStep find, SpringBootDeclaration.CapabilityDeclaration owner) {
      Set<LoweredNodeId> planned = new LinkedHashSet<>();
      for (SpringExpression.StringMatch match : find.stringMatches()) {
         this.addNode(match.id(), match.origin(), "string match plan");
         if (!planned.add(match.expressionId())) {
         this.error(owner, "duplicate literal-match plan entry: " + match.expressionId());
         }

         if (match.escapeCharacter() != this.queryPolicy().likeEscapeCharacter()
         || !match.escapedLiterals().equals(this.queryPolicy().likeEscapedLiterals())) {
         this.error(owner, "literal-match escaping must match the target query policy: " + match.escapedLiterals());
         }
      }

      Set<LoweredNodeId> actual = new LinkedHashSet<>();
      this.collectLiteralMatches(find.predicate(), actual);
      for (LoweredNodeId expressionId : actual) {
         if (!planned.contains(expressionId)) {
         this.error(owner, "literal match in the predicate has no plan entry: " + expressionId);
         }
      }

      for (LoweredNodeId expressionId : planned) {
         if (!actual.contains(expressionId)) {
         this.error(owner, "literal-match plan entry has no predicate node: " + expressionId);
         }
      }
   }

   private void collectLiteralMatches(SpringExpression expression, Set<LoweredNodeId> sink) {
      switch (expression) {
         case SpringExpression.BinaryExpression binary:
         if (binary.operator() == SpringExpression.BinaryOperator.CONTAINS_LITERAL) {
               sink.add(binary.id());
         }

         this.collectLiteralMatches(binary.left(), sink);
         this.collectLiteralMatches(binary.right(), sink);
         break;
         case SpringExpression.UnaryExpression unary:
         this.collectLiteralMatches(unary.operand(), sink);
         break;
         default:
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
         case SpringExpression.ExistsPredicate value:
            this.validateExistsPredicate(value, owner);
            break;
         default:
      }
   }

   /**
   * A correlated subquery must carry one value for every placeholder it uses, in order.
   *
   * <p>The renderer binds the values positionally, so a placeholder without a value (or a value
   * nobody uses) would silently shift every parameter that follows it.
   */
   private void validateExistsPredicate(SpringExpression.ExistsPredicate exists, SpringBootDeclaration owner) {
      int placeholders = 0;
      while (exists.subquerySql().contains("{" + placeholders + "}")) {
         placeholders++;
      }

      if (placeholders != exists.arguments().size()) {
         this.error(owner, "a correlated subquery must bind exactly the values it declares: " + placeholders
            + " placeholders for " + exists.arguments().size() + " values");
      }

      for (int index = 0; index < placeholders; index++) {
         if (!exists.subquerySql().contains("{" + index + "}")) {
            this.error(owner, "a correlated subquery must number its placeholders without gaps: " + index);
         }
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
         case LoweredJavaType.PageValue value:
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
               case VIEW -> SpringBootDeclaration.ViewDeclaration.class;
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
          case SpringBootDeclaration.ViewDeclaration ignored -> EnumSet.of(SpringArtifact.Role.VIEW_DTO);
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
