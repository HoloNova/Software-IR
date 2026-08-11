/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.kcg.sir.ast.AstExposureKind
 *  io.kcg.sir.ast.AstGenerationStrategy
 *  io.kcg.sir.ast.AstNodeId
 *  io.kcg.sir.ast.AstRequirementKind
 *  io.kcg.sir.lowering.api.LoweredIrVersion
 *  io.kcg.sir.lowering.api.LoweredNodeId
 *  io.kcg.sir.lowering.api.LoweredOrigin
 *  io.kcg.sir.semantic.api.NormalizedSemanticModel
 *  io.kcg.sir.semantic.model.NormalizedBinding
 *  io.kcg.sir.semantic.model.NormalizedCapability
 *  io.kcg.sir.semantic.model.NormalizedConstraint
 *  io.kcg.sir.semantic.model.NormalizedDeclaration
 *  io.kcg.sir.semantic.model.NormalizedEntity
 *  io.kcg.sir.semantic.model.NormalizedEnum
 *  io.kcg.sir.semantic.model.NormalizedEnum$NormalizedEnumMember
 *  io.kcg.sir.semantic.model.NormalizedError
 *  io.kcg.sir.semantic.model.NormalizedExpression
 *  io.kcg.sir.semantic.model.NormalizedExpression$BinaryExpression
 *  io.kcg.sir.semantic.model.NormalizedExpression$BooleanLiteral
 *  io.kcg.sir.semantic.model.NormalizedExpression$DecimalLiteral
 *  io.kcg.sir.semantic.model.NormalizedExpression$IntegerLiteral
 *  io.kcg.sir.semantic.model.NormalizedExpression$MemberExpression
 *  io.kcg.sir.semantic.model.NormalizedExpression$NameExpression
 *  io.kcg.sir.semantic.model.NormalizedExpression$NowExpression
 *  io.kcg.sir.semantic.model.NormalizedExpression$StringLiteral
 *  io.kcg.sir.semantic.model.NormalizedExpression$UnaryExpression
 *  io.kcg.sir.semantic.model.NormalizedExpression$UnitLiteral
 *  io.kcg.sir.semantic.model.NormalizedField
 *  io.kcg.sir.semantic.model.NormalizedIdentity
 *  io.kcg.sir.semantic.model.NormalizedInput
 *  io.kcg.sir.semantic.model.NormalizedStep
 *  io.kcg.sir.semantic.model.NormalizedStep$CreateStep
 *  io.kcg.sir.semantic.model.NormalizedStep$FindStep
 *  io.kcg.sir.semantic.model.NormalizedStep$LoadStep
 *  io.kcg.sir.semantic.model.NormalizedStep$PersistStep
 *  io.kcg.sir.semantic.model.NormalizedStep$ReturnStep
 *  io.kcg.sir.semantic.model.NormalizedStep$UpdateStep
 *  io.kcg.sir.semantic.model.NormalizedStep$ValidateStep
 *  io.kcg.sir.semantic.symbol.Symbol
 *  io.kcg.sir.semantic.symbol.Symbol$VariableSymbol
 *  io.kcg.sir.semantic.symbol.SymbolId
 *  io.kcg.sir.semantic.type.DeclaredType
 *  io.kcg.sir.semantic.type.DeclaredType$DeclaredKind
 *  io.kcg.sir.semantic.type.ListType
 *  io.kcg.sir.semantic.type.OptionalType
 *  io.kcg.sir.semantic.type.PrimitiveType
 *  io.kcg.sir.semantic.type.RefType
 *  io.kcg.sir.semantic.type.SirType
 *  io.kcg.sir.source.SourceId
 *  io.kcg.sir.source.SourcePosition
 *  io.kcg.sir.source.SourceSpan
 */
package io.kcg.sir.lowering.springboot.internal;

import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstGenerationStrategy;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.lowering.api.LoweredIrVersion;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.lowering.springboot.model.ActorBinding;
import io.kcg.sir.lowering.springboot.model.ActorIdentityRequirement;
import io.kcg.sir.lowering.springboot.model.ActorIdentityTransportPlan;
import io.kcg.sir.lowering.springboot.model.LoweredJavaType;
import io.kcg.sir.lowering.springboot.model.PersistenceAction;
import io.kcg.sir.lowering.springboot.model.ProjectArtifact;
import io.kcg.sir.lowering.springboot.model.SpringArtifact;
import io.kcg.sir.lowering.springboot.model.SpringBootDeclaration;
import io.kcg.sir.lowering.springboot.model.SpringBootLoweredModel;
import io.kcg.sir.lowering.springboot.model.SpringBootWorkflow;
import io.kcg.sir.lowering.springboot.model.SpringExpression;
import io.kcg.sir.lowering.springboot.model.TransportPlan;
import io.kcg.sir.lowering.springboot.profile.SpringBootTargetProfile;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedBinding;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedConstraint;
import io.kcg.sir.semantic.model.NormalizedDeclaration;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedIdentity;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.symbol.Symbol;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.type.DeclaredType;
import io.kcg.sir.semantic.type.ListType;
import io.kcg.sir.semantic.type.OptionalType;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.semantic.type.SirType;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class SpringBootModelLowerer {
    private final NormalizedSemanticModel source;
    private final Map<SymbolId, NormalizedEntity> entities = new LinkedHashMap<SymbolId, NormalizedEntity>();
    private final Map<SymbolId, String> targetMemberNames = new LinkedHashMap<SymbolId, String>();

    public SpringBootModelLowerer(NormalizedSemanticModel source) {
        this.source = source;
        for (NormalizedDeclaration declaration : source.declarations()) {
            if (declaration instanceof NormalizedEntity) {
                NormalizedEntity entity = (NormalizedEntity)declaration;
                this.entities.put(entity.id(), entity);
                this.targetMemberNames.put(entity.identity().id(), entity.identity().name());
                for (NormalizedField field : entity.fields()) {
                    this.targetMemberNames.put(field.id(), this.targetPropertyName(field, true));
                }
                continue;
            }
            if (declaration instanceof NormalizedInput) {
                NormalizedInput input = (NormalizedInput)declaration;
                for (NormalizedField field : input.fields()) {
                    this.targetMemberNames.put(field.id(), this.targetPropertyName(field, false));
                }
                continue;
            }
            if (!(declaration instanceof NormalizedEnum)) continue;
            NormalizedEnum enumDeclaration = (NormalizedEnum)declaration;
            for (NormalizedEnum.NormalizedEnumMember member : enumDeclaration.members()) {
                this.targetMemberNames.put(member.id(), member.name());
            }
        }
    }

    public SpringBootLoweredModel lower() {
        ArrayList<SpringBootDeclaration> declarations = new ArrayList<SpringBootDeclaration>();
        ArrayList<SpringArtifact> artifacts = new ArrayList<SpringArtifact>();
        for (NormalizedDeclaration declaration : this.source.declarations()) {
            SpringBootDeclaration lowered = this.lowerDeclaration(declaration);
            declarations.add(lowered);
            artifacts.addAll(this.artifactsFor(declaration, lowered));
        }
        Optional<ActorIdentityTransportPlan> transportPlan = this.buildActorIdentityTransportPlan(declarations);
        return new SpringBootLoweredModel(LoweredIrVersion.V0_2, SpringBootTargetProfile.V0_2, this.source.softwareName(), this.source.metadata().displayName(), this.source.metadata().namespace(), declarations, artifacts, this.lowerMavenProject(), this.lowerApplicationMain(transportPlan));
    }

    private ProjectArtifact.MavenProject lowerMavenProject() {
        List<ProjectArtifact.MavenDependency> dependencies = List.of(new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-web", "3.5.3", "compile"), new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-validation", "3.5.3", "compile"), new ProjectArtifact.MavenDependency("com.baomidou", "mybatis-plus-spring-boot3-starter", "3.5.12", "compile"), new ProjectArtifact.MavenDependency("com.mysql", "mysql-connector-j", "9.3.0", "runtime"), new ProjectArtifact.MavenDependency("org.springframework.boot", "spring-boot-starter-test", "3.5.3", "test"));
        List<ProjectArtifact.MavenPlugin> plugins = List.of(new ProjectArtifact.MavenPlugin("org.springframework.boot", "spring-boot-maven-plugin", "3.5.3"));
        return new ProjectArtifact.MavenProject(this.nodeId("project", "maven"), this.projectOrigin(), "pom.xml", this.source.metadata().namespace(), this.kebab(this.source.softwareName()), "0.1.0-SNAPSHOT", SpringBootTargetProfile.V0_1.javaVersion(), SpringBootTargetProfile.V0_1.springBootVersion(), dependencies, plugins);
    }

    private ProjectArtifact.ApplicationMain lowerApplicationMain(Optional<ActorIdentityTransportPlan> transportPlan) {
        String basePackage = this.source.metadata().namespace();
        String simpleName = "Application";
        return new ProjectArtifact.ApplicationMain(this.nodeId("project", "application-main"), this.projectOrigin(), "src/main/java/" + basePackage.replace('.', '/') + "/" + simpleName + ".java", basePackage, simpleName, basePackage + ".persistence", transportPlan);
    }

    private Optional<ActorIdentityTransportPlan> buildActorIdentityTransportPlan(List<SpringBootDeclaration> declarations) {
        ArrayList<ActorIdentityRequirement> requirements = new ArrayList<ActorIdentityRequirement>();
        for (SpringBootDeclaration declaration : declarations) {
            SpringBootDeclaration.CapabilityDeclaration capability;
            if (!(declaration instanceof SpringBootDeclaration.CapabilityDeclaration) || (capability = (SpringBootDeclaration.CapabilityDeclaration)declaration).actor().isEmpty() || capability.actorBinding().isEmpty()) continue;
            SpringBootWorkflow.Variable actorVar = capability.actor().get();
            ActorBinding binding = capability.actorBinding().get();
            SymbolId actorEntitySymbol = this.actorEntitySymbol(actorVar);
            String actorEntityJavaName = this.actorEntityJavaName(actorVar);
            requirements.add(new ActorIdentityRequirement(capability.sourceSymbol(), actorEntitySymbol, actorEntityJavaName, capability.httpMethod(), capability.route(), binding.attributeName(), binding.identityStorageType()));
        }
        if (requirements.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ActorIdentityTransportPlan(ActorIdentityTransportPlan.Policy.EXPLICIT_EXTERNAL_OR_LOCAL_FIXED, "kcg.actor-identity.mode", "kcgActorIdentityTransport", "kcg-actor-local", "kcg.actor-identity.local.id", List.copyOf(requirements)));
    }

    private SymbolId actorEntitySymbol(SpringBootWorkflow.Variable actorVar) {
        return switch (actorVar.type()) {
            case LoweredJavaType.Declared declared
                    when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.symbolId();
            case LoweredJavaType.EntityReference reference -> reference.entitySymbol();
            default -> throw new IllegalStateException(
                    "actor variable must be Declared[ENTITY] or EntityReference: " + actorVar.type());
        };
    }

    private String actorEntityJavaName(SpringBootWorkflow.Variable actorVar) {
        return switch (actorVar.type()) {
            case LoweredJavaType.Declared declared
                    when declared.kind() == LoweredJavaType.DeclaredKind.ENTITY -> declared.javaName();
            case LoweredJavaType.EntityReference reference -> reference.entityJavaName();
            default -> throw new IllegalStateException(
                    "actor variable must be Declared[ENTITY] or EntityReference: " + actorVar.type());
        };
    }

    private LoweredOrigin projectOrigin() {
        SourcePosition pos = new SourcePosition(0, 1, 1);
        SourceSpan span = new SourceSpan(SourceId.of((String)"project"), pos, pos);
        return new LoweredOrigin(Optional.empty(), new AstNodeId("project"), span);
    }

    private SpringBootDeclaration lowerDeclaration(NormalizedDeclaration declaration) {
        return switch (declaration) {
            case NormalizedEnum value -> lowerEnum(value);
            case NormalizedEntity value -> lowerEntity(value);
            case NormalizedInput value -> lowerInput(value);
            case NormalizedError value -> new SpringBootDeclaration.ErrorDeclaration(
                    symbolId(value.id(), "exception"),
                    origin(value.id(), value.sourceNodeId(), value.span()),
                    value.id(), value.name() + "Exception", SpringBootDeclaration.HttpStatus.BAD_REQUEST);
            case NormalizedCapability value -> lowerCapability(value);
        };
    }

    private SpringBootDeclaration.EnumDeclaration lowerEnum(NormalizedEnum value) {
        List<SpringBootDeclaration.EnumMember> members = value.members().stream().map(member -> new SpringBootDeclaration.EnumMember(this.symbolId(member.id(), "enum-member"), this.origin(value.id(), member.sourceNodeId(), member.span()), member.id(), member.name())).toList();
        return new SpringBootDeclaration.EnumDeclaration(this.symbolId(value.id(), "enum"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), members);
    }

    private SpringBootDeclaration.EntityDeclaration lowerEntity(NormalizedEntity value) {
        NormalizedIdentity identity = value.identity();
        SpringBootDeclaration.Identity loweredIdentity = new SpringBootDeclaration.Identity(this.symbolId(identity.id(), "identity"), this.origin(value.id(), identity.sourceNodeId(), identity.span()), identity.id(), identity.name(), this.snake(identity.name()), this.scalar(identity.type()), identity.generation() == AstGenerationStrategy.AUTO ? SpringBootDeclaration.Generation.AUTO_INCREMENT : SpringBootDeclaration.Generation.UUID);
        List<SpringBootDeclaration.Property> fields = value.fields().stream().map(field -> this.lowerProperty((NormalizedField)field, value.id(), true)).toList();
        return new SpringBootDeclaration.EntityDeclaration(this.symbolId(value.id(), "entity"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), this.snake(value.name()), loweredIdentity, fields);
    }

    private SpringBootDeclaration.InputDeclaration lowerInput(NormalizedInput value) {
        List<SpringBootDeclaration.Property> fields = value.fields().stream().map(field -> this.lowerProperty((NormalizedField)field, value.id(), false)).toList();
        return new SpringBootDeclaration.InputDeclaration(this.symbolId(value.id(), "input"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), fields);
    }

    private SpringBootDeclaration.Property lowerProperty(NormalizedField field, SymbolId owner, boolean persistent) {
        DeclaredType declared;
        SirType sirType;
        SpringBootDeclaration.PersistenceShape shape = !persistent ? SpringBootDeclaration.PersistenceShape.NONE : (field.type() instanceof RefType ? SpringBootDeclaration.PersistenceShape.REFERENCE_ID : ((sirType = field.type()) instanceof DeclaredType && (declared = (DeclaredType)sirType).kind() == DeclaredType.DeclaredKind.ENUM ? SpringBootDeclaration.PersistenceShape.ENUM_TEXT : SpringBootDeclaration.PersistenceShape.SCALAR));
        String column = field.type() instanceof RefType ? this.snake(field.name()) + "_id" : this.snake(field.name());
        List<SpringBootDeclaration.Constraint> constraints = field.constraints().stream().map(constraint -> this.lowerConstraint((NormalizedConstraint)constraint, owner)).toList();
        return new SpringBootDeclaration.Property(this.symbolId(field.id(), "property"), this.origin(owner, field.sourceNodeId(), field.span()), field.id(), this.targetPropertyName(field, persistent), persistent ? Optional.of(column) : Optional.empty(), this.lowerType(field.type()), shape, constraints);
    }

    private SpringBootDeclaration.Constraint lowerConstraint(NormalizedConstraint constraint, SymbolId owner) {
        SpringBootDeclaration.ConstraintKind kind = switch (constraint.name()) {
            case "notBlank" -> SpringBootDeclaration.ConstraintKind.NOT_BLANK;
            case "email" -> SpringBootDeclaration.ConstraintKind.EMAIL;
            case "length" -> SpringBootDeclaration.ConstraintKind.SIZE;
            case "min" -> SpringBootDeclaration.ConstraintKind.DECIMAL_MIN;
            case "max" -> SpringBootDeclaration.ConstraintKind.DECIMAL_MAX;
            default -> throw new IllegalStateException("validated constraint is unsupported: " + constraint.name());
        };
        List<String> arguments = constraint.arguments().stream().map(this::constantValue).toList();
        return new SpringBootDeclaration.Constraint(this.nodeId(constraint.sourceNodeId().value(), "constraint"), this.origin(owner, constraint.sourceNodeId(), constraint.span()), kind, arguments);
    }

    private SpringBootDeclaration.CapabilityDeclaration lowerCapability(NormalizedCapability value) {
        boolean authenticated = value.requires().contains(AstRequirementKind.AUTHENTICATED);
        SpringBootDeclaration.TransactionMode transactionMode = value.requires().contains(AstRequirementKind.READONLY) ? SpringBootDeclaration.TransactionMode.READ_ONLY : (value.requires().contains(AstRequirementKind.ATOMIC) ? SpringBootDeclaration.TransactionMode.REQUIRED : SpringBootDeclaration.TransactionMode.NONE);
        SpringBootDeclaration.CapabilityKind kind = switch (value.exposure()) {
            default -> throw new MatchException(null, null);
            case AstExposureKind.COMMAND -> SpringBootDeclaration.CapabilityKind.COMMAND;
            case AstExposureKind.QUERY -> SpringBootDeclaration.CapabilityKind.QUERY;
        };
        SpringBootDeclaration.HttpMethod httpMethod = kind == SpringBootDeclaration.CapabilityKind.QUERY ? SpringBootDeclaration.HttpMethod.GET : SpringBootDeclaration.HttpMethod.POST;
        Optional<SpringBootWorkflow.Variable> actor = value.actorSymbol().map(this::variable);
        Optional<SpringBootWorkflow.Variable> input = value.inputSymbol().map(this::variable);
        Optional<ActorBinding> actorBinding = value.actorSymbol().map(this::lowerActorBinding);
        TransportPlan transportPlan = this.lowerTransportPlan(value, httpMethod, input.isPresent());
        return new SpringBootDeclaration.CapabilityDeclaration(this.symbolId(value.id(), "capability"), this.origin(value.id(), value.sourceNodeId(), value.span()), value.id(), value.name(), value.name() + "Service", value.name() + "Controller", this.lowerFirst(value.name()), httpMethod, "/api/" + this.kebab(value.name()), kind, transactionMode, authenticated, actor, input, actorBinding, transportPlan, this.lowerType(value.outputType()), value.fails(), this.lowerWorkflow(value));
    }

    private ActorBinding lowerActorBinding(SymbolId actorSymbol) {
        DeclaredType declaredType;
        LoweredJavaType.Scalar identityStorageType;
        Symbol.VariableSymbol symbol = (Symbol.VariableSymbol)this.source.symbols().byId(actorSymbol).orElseThrow();
        SirType actorType = symbol.type();
        if (actorType instanceof RefType) {
            RefType refType = (RefType)actorType;
            NormalizedEntity entity = this.entities.get(refType.entityId());
            identityStorageType = this.scalar(entity.identity().type());
        } else if (actorType instanceof DeclaredType && (declaredType = (DeclaredType)actorType).kind() == DeclaredType.DeclaredKind.ENTITY) {
            NormalizedEntity entity = this.entities.get(declaredType.symbolId());
            identityStorageType = this.scalar(entity.identity().type());
        } else {
            throw new IllegalStateException("actor must be an entity ref, got: " + String.valueOf(actorType));
        }
        return new ActorBinding(ActorBinding.ActorKind.REQUEST_ATTRIBUTE, "actorId", identityStorageType);
    }

    private TransportPlan lowerTransportPlan(NormalizedCapability capability, SpringBootDeclaration.HttpMethod httpMethod, boolean hasInput) {
        TransportPlan.InputBinding inputBinding = !hasInput ? TransportPlan.InputBinding.NONE : (httpMethod == SpringBootDeclaration.HttpMethod.POST ? TransportPlan.InputBinding.REQUEST_BODY : TransportPlan.InputBinding.MODEL_ATTRIBUTE);
        TransportPlan.ResponseRepresentation response = this.lowerResponseRepresentation(capability.outputType());
        return new TransportPlan(inputBinding, response);
    }

    private TransportPlan.ResponseRepresentation lowerResponseRepresentation(SirType outputType) {
        return switch (outputType) {
            case PrimitiveType value -> value == PrimitiveType.UNIT
                    ? TransportPlan.ResponseRepresentation.VOID
                    : TransportPlan.ResponseRepresentation.VALUE;
            case RefType ignored -> TransportPlan.ResponseRepresentation.ENTITY_BODY;
            case DeclaredType value -> value.kind() == DeclaredType.DeclaredKind.ENTITY
                    ? TransportPlan.ResponseRepresentation.ENTITY_BODY
                    : TransportPlan.ResponseRepresentation.VALUE;
            case OptionalType ignored -> TransportPlan.ResponseRepresentation.OPTIONAL;
            case ListType ignored -> TransportPlan.ResponseRepresentation.LIST;
        };
    }

    private SpringBootWorkflow lowerWorkflow(NormalizedCapability capability) {
        Set<SymbolId> entityHoldingVars = this.collectEntityHoldingVariables(capability);
        List<SpringBootWorkflow.Variable> variables = this.source.symbols().all().stream().filter(Symbol.VariableSymbol.class::isInstance).map(Symbol.VariableSymbol.class::cast).filter(symbol -> symbol.id().value().startsWith(capability.id().value() + "/")).map(symbol -> new SpringBootWorkflow.Variable(symbol.id(), symbol.name(), this.lowerVariableType(symbol.type(), entityHoldingVars.contains(symbol.id())))).toList();
        LinkedHashMap<SymbolId, Provenance> provenance = new LinkedHashMap<SymbolId, Provenance>();
        if (capability.actorSymbol().isPresent()) {
            provenance.put((SymbolId)capability.actorSymbol().get(), Provenance.EXISTING);
        }
        if (capability.inputSymbol().isPresent()) {
            provenance.put((SymbolId)capability.inputSymbol().get(), Provenance.EXISTING);
        }
        ArrayList<SpringBootWorkflow.Step> steps = new ArrayList<SpringBootWorkflow.Step>();
        for (NormalizedStep step : capability.workflow().steps()) {
            SpringBootWorkflow.Step lowered = this.lowerStep(step, capability.id(), provenance, capability.actorSymbol());
            steps.add(lowered);
            if (step instanceof NormalizedStep.CreateStep) {
                NormalizedStep.CreateStep createStep = (NormalizedStep.CreateStep)step;
                provenance.put(createStep.resultVariable(), Provenance.NEW);
                continue;
            }
            if (step instanceof NormalizedStep.LoadStep) {
                NormalizedStep.LoadStep loadStep = (NormalizedStep.LoadStep)step;
                provenance.put(loadStep.resultVariable(), Provenance.EXISTING);
                continue;
            }
            if (!(step instanceof NormalizedStep.PersistStep)) continue;
            NormalizedStep.PersistStep persistStep = (NormalizedStep.PersistStep)step;
            provenance.put(persistStep.targetVariable(), Provenance.EXISTING);
        }
        return new SpringBootWorkflow(this.nodeId(capability.workflow().sourceNodeId().value(), "workflow"), this.origin(capability.id(), capability.workflow().sourceNodeId(), capability.workflow().span()), variables, List.copyOf(steps));
    }

    private SpringBootWorkflow.Step lowerStep(NormalizedStep step, SymbolId owner, Map<SymbolId, Provenance> provenance, Optional<SymbolId> actorSymbol) {
        LoweredNodeId id = this.nodeId(step.sourceNodeId().value(), "step");
        LoweredOrigin origin = this.origin(owner, step.sourceNodeId(), step.span());
        return switch (step) {
            case NormalizedStep.ValidateStep value -> new SpringBootWorkflow.ValidateStep(
                    id, origin, lowerExpression(value.condition(), owner, actorSymbol), value.errorSymbol());
            case NormalizedStep.LoadStep value -> new SpringBootWorkflow.LoadStep(
                    id, origin, value.entitySymbol(), lowerExpression(value.idExpression(), owner, actorSymbol),
                    variable(value.resultVariable()), value.errorSymbol());
            case NormalizedStep.FindStep value -> new SpringBootWorkflow.FindStep(
                    id, origin, value.entitySymbol(), lowerExpression(value.predicate(), owner, actorSymbol),
                    variable(value.resultVariable()), variable(value.itemVariable()));
            case NormalizedStep.CreateStep value -> new SpringBootWorkflow.CreateStep(
                    id, origin, value.entitySymbol(), variable(value.resultVariable()),
                    value.bindings().stream().map(binding -> lowerBinding(binding, owner, actorSymbol)).toList());
            case NormalizedStep.UpdateStep value -> new SpringBootWorkflow.UpdateStep(
                    id, origin, value.targetVariable(),
                    value.bindings().stream().map(binding -> lowerBinding(binding, owner, actorSymbol)).toList());
            case NormalizedStep.PersistStep value -> {
                Provenance p = provenance.get(value.targetVariable());
                if (p == null) {
                    throw new IllegalStateException(
                            "cannot determine persistence action: unknown provenance for " + value.targetVariable());
                }
                PersistenceAction action = p == Provenance.NEW ? PersistenceAction.INSERT : PersistenceAction.UPDATE;
                yield new SpringBootWorkflow.PersistStep(id, origin, value.targetVariable(), action);
            }
            case NormalizedStep.ReturnStep value -> new SpringBootWorkflow.ReturnStep(
                    id, origin, lowerExpression(value.value(), owner, actorSymbol));
        };
    }

    private SpringBootWorkflow.Binding lowerBinding(NormalizedBinding binding, SymbolId owner, Optional<SymbolId> actorSymbol) {
        String targetName = this.targetMemberNames.get(binding.fieldSymbol());
        return new SpringBootWorkflow.Binding(binding.fieldSymbol(), targetName, this.lowerExpression(binding.value(), owner, actorSymbol));
    }

    private SpringExpression lowerExpression(NormalizedExpression expression, SymbolId owner) {
        return this.lowerExpression(expression, owner, Optional.empty());
    }

    private SpringExpression lowerExpression(NormalizedExpression expression, SymbolId owner, Optional<SymbolId> actorSymbol) {
        LoweredNodeId id = this.nodeId(expression.sourceNodeId().value(), "expression");
        LoweredOrigin origin = this.origin(owner, expression.sourceNodeId(), expression.span());
        LoweredJavaType type = this.lowerType(expression.type());
        return switch (expression) {
            case NormalizedExpression.BooleanLiteral value ->
                    new SpringExpression.BooleanLiteral(id, origin, type, value.value());
            case NormalizedExpression.IntegerLiteral value ->
                    new SpringExpression.IntegerLiteral(id, origin, type, value.value());
            case NormalizedExpression.DecimalLiteral value ->
                    new SpringExpression.DecimalLiteral(id, origin, type, value.value());
            case NormalizedExpression.StringLiteral value ->
                    new SpringExpression.StringLiteral(id, origin, type, value.value());
            case NormalizedExpression.UnitLiteral ignored -> new SpringExpression.UnitLiteral(id, origin, type);
            case NormalizedExpression.NowExpression ignored -> new SpringExpression.NowExpression(id, origin, type);
            case NormalizedExpression.NameExpression value -> new SpringExpression.NameExpression(
                    id, origin, type, value.resolvedSymbol(),
                    source.symbols().byId(value.resolvedSymbol()).orElseThrow().name());
            case NormalizedExpression.MemberExpression value -> {
                if (actorSymbol.isPresent()
                        && value.receiver() instanceof NormalizedExpression.NameExpression receiver
                        && receiver.resolvedSymbol().equals(actorSymbol.get())) {
                    yield new SpringExpression.NameExpression(
                            id, origin, type, actorSymbol.get(),
                            source.symbols().byId(actorSymbol.get()).orElseThrow().name());
                }
                yield new SpringExpression.MemberExpression(
                        id, origin, type, lowerExpression(value.receiver(), owner, actorSymbol),
                        value.resolvedMember(),
                        targetMemberNames.getOrDefault(
                                value.resolvedMember(),
                                source.symbols().byId(value.resolvedMember()).orElseThrow().name()));
            }
            case NormalizedExpression.UnaryExpression value -> new SpringExpression.UnaryExpression(
                    id, origin, type, SpringExpression.UnaryOperator.valueOf(value.operator().name()),
                    lowerExpression(value.operand(), owner, actorSymbol));
            case NormalizedExpression.BinaryExpression value -> new SpringExpression.BinaryExpression(
                    id, origin, type, lowerExpression(value.left(), owner, actorSymbol),
                    SpringExpression.BinaryOperator.valueOf(value.operator().name()),
                    lowerExpression(value.right(), owner, actorSymbol));
        };
    }

    private SpringBootWorkflow.Variable variable(SymbolId symbolId) {
        Symbol.VariableSymbol symbol = (Symbol.VariableSymbol)this.source.symbols().byId(symbolId).orElseThrow();
        return new SpringBootWorkflow.Variable(symbol.id(), symbol.name(), this.lowerVariableType(symbol.type(), true));
    }

    private Set<SymbolId> collectEntityHoldingVariables(NormalizedCapability capability) {
        LinkedHashSet<SymbolId> ids = new LinkedHashSet<SymbolId>();
        for (NormalizedStep step : capability.workflow().steps()) {
            switch (step) {
                case NormalizedStep.LoadStep s -> ids.add(s.resultVariable());
                case NormalizedStep.CreateStep s -> ids.add(s.resultVariable());
                case NormalizedStep.FindStep s -> {
                    ids.add(s.resultVariable());
                    ids.add(s.itemVariable());
                }
                default -> { }
            }
        }
        return ids;
    }

    private LoweredJavaType lowerVariableType(SirType type, boolean entityHolding) {
        LoweredJavaType lowered = this.lowerType(type);
        if (entityHolding && lowered instanceof LoweredJavaType.EntityReference) {
            LoweredJavaType.EntityReference ref = (LoweredJavaType.EntityReference)lowered;
            return new LoweredJavaType.Declared(LoweredJavaType.DeclaredKind.ENTITY, ref.entitySymbol(), ref.entityJavaName());
        }
        return lowered;
    }

    private LoweredJavaType lowerType(SirType type) {
        return switch (type) {
            case PrimitiveType value -> scalar(value);
            case OptionalType value -> new LoweredJavaType.OptionalValue(lowerType(value.element()));
            case ListType value -> new LoweredJavaType.ListValue(lowerType(value.element()));
            case DeclaredType value -> new LoweredJavaType.Declared(
                    LoweredJavaType.DeclaredKind.valueOf(value.kind().name()), value.symbolId(), value.name());
            case RefType value -> {
                NormalizedEntity entity = this.entities.get(value.entityId());
                yield new LoweredJavaType.EntityReference(
                        value.entityId(), value.entityName(), scalar(entity.identity().type()));
            }
        };
    }

    private LoweredJavaType.Scalar scalar(SirType type) {
        PrimitiveType primitive = (PrimitiveType)type;
        LoweredJavaType.ScalarKind kind = switch (primitive) {
            default -> throw new MatchException(null, null);
            case PrimitiveType.BOOLEAN -> LoweredJavaType.ScalarKind.BOOLEAN;
            case PrimitiveType.INT32 -> LoweredJavaType.ScalarKind.INTEGER;
            case PrimitiveType.INT64 -> LoweredJavaType.ScalarKind.LONG;
            case PrimitiveType.DECIMAL -> LoweredJavaType.ScalarKind.BIG_DECIMAL;
            case PrimitiveType.STRING -> LoweredJavaType.ScalarKind.STRING;
            case PrimitiveType.UUID -> LoweredJavaType.ScalarKind.UUID;
            case PrimitiveType.DATE -> LoweredJavaType.ScalarKind.LOCAL_DATE;
            case PrimitiveType.DATE_TIME -> LoweredJavaType.ScalarKind.INSTANT;
            case PrimitiveType.UNIT -> LoweredJavaType.ScalarKind.VOID;
        };
        return new LoweredJavaType.Scalar(kind);
    }

    private List<SpringArtifact> artifactsFor(NormalizedDeclaration sourceDeclaration, SpringBootDeclaration lowered) {
        ArrayList<SpringArtifact> result = new ArrayList<SpringArtifact>();
        switch (lowered) {
            case SpringBootDeclaration.EnumDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.ENUM, this.source.metadata().namespace() + ".domain", value.javaName()));
            case SpringBootDeclaration.EntityDeclaration value -> {
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.ENTITY_MODEL, this.source.metadata().namespace() + ".domain", value.javaName()));
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.MAPPER, this.source.metadata().namespace() + ".persistence", value.javaName() + "Mapper"));
            }
            case SpringBootDeclaration.InputDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.REQUEST_DTO, this.source.metadata().namespace() + ".api", value.javaName()));
            case SpringBootDeclaration.ErrorDeclaration value ->
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.EXCEPTION, this.source.metadata().namespace() + ".api", value.javaName()));
            case SpringBootDeclaration.CapabilityDeclaration value -> {
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.SERVICE, this.source.metadata().namespace() + ".application", value.serviceName()));
                result.add(this.artifact(sourceDeclaration, value.origin(), SpringArtifact.Role.CONTROLLER, this.source.metadata().namespace() + ".api", value.controllerName()));
            }
        }
        return List.copyOf(result);
    }

    private SpringArtifact artifact(NormalizedDeclaration declaration, LoweredOrigin origin, SpringArtifact.Role role, String packageName, String simpleName) {
        return new SpringArtifact(this.symbolId(declaration.id(), "artifact-" + role.name().toLowerCase(Locale.ROOT)), origin, declaration.id(), role, packageName, simpleName);
    }

    private LoweredNodeId symbolId(SymbolId symbolId, String role) {
        return this.nodeId(symbolId.value(), role);
    }

    private LoweredNodeId nodeId(String sourceId, String role) {
        String encoded = URLEncoder.encode(sourceId, StandardCharsets.UTF_8);
        String profileId = SpringBootTargetProfile.V0_2.id();
        return new LoweredNodeId("lir://" + profileId + "/" + role + "/" + encoded);
    }

    private LoweredOrigin origin(SymbolId owner, AstNodeId nodeId, SourceSpan span) {
        return new LoweredOrigin(Optional.of(owner), nodeId, span);
    }

    private String constantValue(NormalizedExpression expression) {
        return switch (expression) {
            case NormalizedExpression.IntegerLiteral value -> value.value().toString();
            case NormalizedExpression.DecimalLiteral value -> value.value().toPlainString();
            case NormalizedExpression.UnaryExpression value -> value.operator().name().equals("NEGATE")
                    ? "-" + constantValue(value.operand())
                    : "!" + constantValue(value.operand());
            default -> throw new IllegalStateException("validated constraint argument is not constant");
        };
    }

    private String lowerFirst(String value) {
        int first = value.codePointAt(0);
        return new String(Character.toChars(Character.toLowerCase(first))) + value.substring(Character.charCount(first));
    }

    private String targetPropertyName(NormalizedField field, boolean persistent) {
        return field.type() instanceof RefType ? field.name() + "Id" : field.name();
    }

    private String snake(String value) {
        return this.separated(value, '_');
    }

    private String kebab(String value) {
        return this.separated(value, '-');
    }

    private String separated(String value, char separator) {
        int point;
        StringBuilder result = new StringBuilder();
        for (int offset = 0; offset < value.length(); offset += Character.charCount(point)) {
            point = value.codePointAt(offset);
            if (Character.isUpperCase(point) && result.length() > 0) {
                result.append(separator);
            }
            result.appendCodePoint(Character.toLowerCase(point));
        }
        return result.toString().toLowerCase(Locale.ROOT);
    }

    private static enum Provenance {
        NEW,
        EXISTING;

    }
}
