package io.kcg.sir.change;

import io.kcg.sir.ast.AstExposureKind;
import io.kcg.sir.ast.AstGenerationStrategy;
import io.kcg.sir.ast.AstMetadata;
import io.kcg.sir.ast.AstNodeId;
import io.kcg.sir.ast.AstRequirementKind;
import io.kcg.sir.ast.AstTarget;
import io.kcg.sir.ast.AstTargetValue;
import io.kcg.sir.ast.AstBuildTool;
import io.kcg.sir.ast.AstDatabase;
import io.kcg.sir.ast.AstFramework;
import io.kcg.sir.ast.AstInterfaceKind;
import io.kcg.sir.ast.AstLanguage;
import io.kcg.sir.ast.AstPersistence;
import io.kcg.sir.change.api.ChangeBaseRevision;
import io.kcg.sir.change.api.ChangeIrVersion;
import io.kcg.sir.change.api.ChangeSet;
import io.kcg.sir.change.api.ChangeTarget;
import io.kcg.sir.change.api.ModifyCapabilityWorkflow;
import io.kcg.sir.lowering.api.LoweredNodeId;
import io.kcg.sir.lowering.api.LoweredOrigin;
import io.kcg.sir.projectgraph.api.ArtifactRole;
import io.kcg.sir.projectgraph.api.GraphEdgeKind;
import io.kcg.sir.projectgraph.api.GraphNodeId;
import io.kcg.sir.projectgraph.api.GraphVersion;
import io.kcg.sir.projectgraph.api.ProjectGraph;
import io.kcg.sir.projectgraph.api.ProjectGraphAnalysis;
import io.kcg.sir.projectgraph.api.ProjectGraphBuilder;
import io.kcg.sir.projectgraph.api.ProjectGraphCanonicalFormatVersion;
import io.kcg.sir.projectgraph.api.ProjectGraphInput;
import io.kcg.sir.semantic.api.NormalizedSemanticModel;
import io.kcg.sir.semantic.model.NormalizedCapability;
import io.kcg.sir.semantic.model.NormalizedEntity;
import io.kcg.sir.semantic.model.NormalizedEnum;
import io.kcg.sir.semantic.model.NormalizedError;
import io.kcg.sir.semantic.model.NormalizedExpression;
import io.kcg.sir.semantic.model.NormalizedField;
import io.kcg.sir.semantic.model.NormalizedIdentity;
import io.kcg.sir.semantic.model.NormalizedInput;
import io.kcg.sir.semantic.model.NormalizedStep;
import io.kcg.sir.semantic.model.NormalizedWorkflow;
import io.kcg.sir.semantic.symbol.SymbolId;
import io.kcg.sir.semantic.symbol.SymbolKind;
import io.kcg.sir.semantic.symbol.SymbolTable;
import io.kcg.sir.semantic.type.PrimitiveType;
import io.kcg.sir.semantic.type.RefType;
import io.kcg.sir.source.SourceId;
import io.kcg.sir.source.SourcePosition;
import io.kcg.sir.source.SourceSpan;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Test fixtures for the sir-change planner. Builds a minimal but valid
 * {@link NormalizedSemanticModel} + {@link ProjectGraph} pair representing
 * a campus-market-like scenario with one Entity, one Capability, plus
 * project-level artifacts. The fixtures expose mutators so tests can
 * construct candidate scenarios (modified workflow, broken contract,
 * out-of-closure file change, etc.).
 *
 * <p>The fixture is intentionally minimal: it does not run the real
 * parser / semantic / lowering / generator pipeline. The planner only
 * consumes the already-built {@link NormalizedSemanticModel} and
 * {@link ProjectGraph}, so hand-built fixtures are sufficient.
 */
final class ChangeTestFixtures {

    static final SourceId SOURCE_ID = SourceId.of("campus-market.sir");
    static final GraphVersion GRAPH_VERSION = GraphVersion.V0_1;
    static final ProjectGraphCanonicalFormatVersion SNAPSHOT_VERSION =
            ProjectGraphCanonicalFormatVersion.V1;

    static final SymbolId ENTITY_SYMBOL =
            new SymbolId("sir://software/campus-market/entity/User");
    static final SymbolId CAPABILITY_SYMBOL =
            new SymbolId("sir://software/campus-market/capability/PublishGoods");
    static final SymbolId INPUT_SYMBOL =
            new SymbolId("sir://software/campus-market/input/PublishGoodsInput");
    static final SymbolId ERROR_SYMBOL =
            new SymbolId("sir://software/campus-market/error/InvalidGoodsPrice");
    static final SymbolId ENUM_SYMBOL =
            new SymbolId("sir://software/campus-market/enum/GoodsStatus");

    // Second Capability (SearchGoods) used for v0.2 AddCapability tests.
    // The base model/graph never contains these; only the candidate does.
    static final SymbolId SEARCH_GOODS_INPUT_SYMBOL =
            new SymbolId("sir://software/campus-market/input/SearchGoodsInput");
    static final SymbolId SEARCH_GOODS_CAPABILITY_SYMBOL =
            new SymbolId("sir://software/campus-market/capability/SearchGoods");
    static final SymbolId SEARCH_GOODS_ERROR_SYMBOL =
            new SymbolId("sir://software/campus-market/error/SearchFailed");

    static final AstNodeId SEARCH_GOODS_INPUT_AST =
            new AstNodeId("software/campus-market/input/SearchGoodsInput");
    static final AstNodeId SEARCH_GOODS_CAPABILITY_AST =
            new AstNodeId("software/campus-market/capability/SearchGoods");
    static final AstNodeId SEARCH_GOODS_WORKFLOW_AST =
            new AstNodeId("software/campus-market/capability/SearchGoods/workflow");
    static final AstNodeId SEARCH_GOODS_ERROR_AST =
            new AstNodeId("software/campus-market/error/SearchFailed");
    static final AstNodeId SEARCH_GOODS_WORKFLOW_FIND_AST =
            new AstNodeId("software/campus-market/capability/SearchGoods/workflow/find-1");
    static final AstNodeId SEARCH_GOODS_WORKFLOW_RETURN_AST =
            new AstNodeId("software/campus-market/capability/SearchGoods/workflow/return-1");

    static final LoweredNodeId SEARCH_GOODS_INPUT_LOWERED =
            new LoweredNodeId("lir://spring/input/SearchGoodsInput");
    static final LoweredNodeId SEARCH_GOODS_CAPABILITY_LOWERED =
            new LoweredNodeId("lir://spring/capability/SearchGoods");
    static final LoweredNodeId SEARCH_GOODS_ERROR_LOWERED =
            new LoweredNodeId("lir://spring/error/SearchFailed");
    static final LoweredNodeId SEARCH_GOODS_SERVICE_ARTIFACT =
            new LoweredNodeId("lir://spring/artifact-service/SearchGoods");
    static final LoweredNodeId SEARCH_GOODS_CONTROLLER_ARTIFACT =
            new LoweredNodeId("lir://spring/artifact-controller/SearchGoods");

    static final String SEARCH_GOODS_SERVICE_FILE_PATH =
            "src/main/java/io/kcg/campusmarket/application/SearchGoodsService.java";
    static final String SEARCH_GOODS_CONTROLLER_FILE_PATH =
            "src/main/java/io/kcg/campusmarket/api/SearchGoodsController.java";

    static final AstNodeId ENTITY_AST =
            new AstNodeId("software/campus-market/entity/User");
    static final AstNodeId CAPABILITY_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods");
    static final AstNodeId WORKFLOW_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods/workflow");
    static final AstNodeId INPUT_AST =
            new AstNodeId("software/campus-market/input/PublishGoodsInput");
    static final AstNodeId ERROR_AST =
            new AstNodeId("software/campus-market/error/InvalidGoodsPrice");
    static final AstNodeId ENUM_AST =
            new AstNodeId("software/campus-market/enum/GoodsStatus");

    static final AstNodeId WORKFLOW_VALIDATE_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods/workflow/validate-1");
    static final AstNodeId WORKFLOW_CREATE_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods/workflow/create-1");
    static final AstNodeId WORKFLOW_PERSIST_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods/workflow/persist-1");
    static final AstNodeId WORKFLOW_RETURN_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods/workflow/return-1");

    // Alternate validate step AstNodeId used to construct a different
    // candidate workflow structure. The candidate's workflow.sourceNodeId
    // itself stays identical to base's; only the inner step content changes.
    static final AstNodeId WORKFLOW_VALIDATE_ALT_AST =
            new AstNodeId("software/campus-market/capability/PublishGoods/workflow/validate-alt");

    static final LoweredNodeId ENTITY_LOWERED =
            new LoweredNodeId("lir://spring/entity/User");
    static final LoweredNodeId CAPABILITY_LOWERED =
            new LoweredNodeId("lir://spring/capability/PublishGoods");
    static final LoweredNodeId ENUM_LOWERED =
            new LoweredNodeId("lir://spring/enum/GoodsStatus");
    static final LoweredNodeId INPUT_LOWERED =
            new LoweredNodeId("lir://spring/input/PublishGoodsInput");
    static final LoweredNodeId ERROR_LOWERED =
            new LoweredNodeId("lir://spring/error/InvalidGoodsPrice");
    static final LoweredNodeId ENTITY_MODEL_ARTIFACT =
            new LoweredNodeId("lir://spring/artifact-entity_model/User");
    static final LoweredNodeId SERVICE_ARTIFACT =
            new LoweredNodeId("lir://spring/artifact-service/PublishGoods");
    static final LoweredNodeId CONTROLLER_ARTIFACT =
            new LoweredNodeId("lir://spring/artifact-controller/PublishGoods");
    static final LoweredNodeId MAVEN_PROJECT_ARTIFACT =
            new LoweredNodeId("lir://spring/maven/project");
    static final LoweredNodeId APPLICATION_MAIN_ARTIFACT =
            new LoweredNodeId("lir://spring/application-main/project");

    static final String ENTITY_FILE_PATH =
            "src/main/java/io/kcg/campusmarket/domain/User.java";
    static final String SERVICE_FILE_PATH =
            "src/main/java/io/kcg/campusmarket/application/PublishGoodsService.java";
    static final String CONTROLLER_FILE_PATH =
            "src/main/java/io/kcg/campusmarket/api/PublishGoodsController.java";
    static final String POM_FILE_PATH = "pom.xml";
    static final String APPLICATION_FILE_PATH =
            "src/main/java/io/kcg/campusmarket/Application.java";

    private ChangeTestFixtures() {
    }

    static SourceSpan span() {
        SourcePosition pos = new SourcePosition(0, 1, 1);
        return new SourceSpan(SOURCE_ID, pos, pos);
    }

    static LoweredOrigin loweredOrigin(SymbolId owner, AstNodeId ast) {
        return new LoweredOrigin(Optional.of(owner), ast, span());
    }

    static LoweredOrigin projectOrigin() {
        SourcePosition pos = new SourcePosition(0, 1, 1);
        SourceSpan projectSpan = new SourceSpan(SourceId.of("project"), pos, pos);
        return new LoweredOrigin(Optional.empty(), new AstNodeId("project"), projectSpan);
    }

    static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                int v = b & 0xFF;
                if (v < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(v));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static AstMetadata metadata() {
        return new AstMetadata(
                new AstNodeId("software/campus-market/metadata"),
                span(),
                "Campus Market",
                span(),
                "com.example.campusmarket",
                span());
    }

    static AstTarget target() {
        return new AstTarget(
                new AstNodeId("software/campus-market/target"),
                span(),
                new AstTargetValue<>(AstLanguage.JAVA, span()),
                BigInteger.valueOf(21),
                span(),
                new AstTargetValue<>(AstFramework.SPRING_BOOT, span()),
                new AstTargetValue<>(AstPersistence.MYBATIS_PLUS, span()),
                new AstTargetValue<>(AstDatabase.MYSQL, span()),
                new AstTargetValue<>(AstBuildTool.MAVEN, span()),
                new AstTargetValue<>(AstInterfaceKind.REST, span()));
    }

    static NormalizedEnum enumDecl() {
        return new NormalizedEnum(
                ENUM_SYMBOL,
                "GoodsStatus",
                span(),
                ENUM_AST,
                List.of(new NormalizedEnum.NormalizedEnumMember(
                        new SymbolId("sir://software/campus-market/enum/GoodsStatus/AVAILABLE"),
                        "AVAILABLE", span(),
                        new AstNodeId("software/campus-market/enum/GoodsStatus/AVAILABLE"))));
    }

    static NormalizedEntity entityDecl() {
        return new NormalizedEntity(
                ENTITY_SYMBOL,
                "User",
                span(),
                ENTITY_AST,
                true,
                new NormalizedIdentity(
                        new SymbolId("sir://software/campus-market/entity/User/identity/id"),
                        "id",
                        span(),
                        new AstNodeId("software/campus-market/entity/User/identity/id"),
                        PrimitiveType.INT64,
                        AstGenerationStrategy.AUTO),
                List.of(new NormalizedField(
                        new SymbolId("sir://software/campus-market/entity/User/field/name"),
                        "name",
                        span(),
                        new AstNodeId("software/campus-market/entity/User/field/name"),
                        PrimitiveType.STRING,
                        List.of())));
    }

    static NormalizedInput inputDecl() {
        return new NormalizedInput(
                INPUT_SYMBOL,
                "PublishGoodsInput",
                span(),
                INPUT_AST,
                List.of(new NormalizedField(
                        new SymbolId("sir://software/campus-market/input/PublishGoodsInput/field/title"),
                        "title",
                        span(),
                        new AstNodeId("software/campus-market/input/PublishGoodsInput/field/title"),
                        PrimitiveType.STRING,
                        List.of())));
    }

    static NormalizedError errorDecl() {
        return new NormalizedError(
                ERROR_SYMBOL,
                "InvalidGoodsPrice",
                span(),
                ERROR_AST);
    }

    // [RQ-06 RECOVERY NOTE] Removed methods baseWorkflow/modifiedWorkflow/
    // searchGoodsCapability/searchGoodsWorkflow: their expression helpers
    // (priceGeLiteral/priceGtLiteral/unitLiteral/memberOfInput/decimal bodies)
    // were lost in historical output truncation (session block
    // 2026-07-19T23:27:16 line 1315; only method signatures survive in block
    // 2026-07-19T10:29:10 line 1001). See docs/recovery inventory.
    // [RQ-06 RECOVERY NOTE] Removed methods baseWorkflow/modifiedWorkflow/
    // searchGoodsCapability/searchGoodsWorkflow: their expression helpers
    // (priceGeLiteral/priceGtLiteral/unitLiteral/memberOfInput/decimal bodies)
    // were lost in historical output truncation (session block
    // 2026-07-19T23:27:16 line 1315; only method signatures survive in block
    // 2026-07-19T10:29:10 line 1001). See docs/recovery inventory.
    static NormalizedCapability capabilityWithWorkflow(NormalizedWorkflow workflow) {
        return new NormalizedCapability(
                CAPABILITY_SYMBOL,
                "PublishGoods",
                span(),
                CAPABILITY_AST,
                Optional.of(ENTITY_SYMBOL),
                Optional.of(INPUT_SYMBOL),
                new RefType("User", ENTITY_SYMBOL),
                List.of(ERROR_SYMBOL),
                List.of(AstRequirementKind.AUTHENTICATED, AstRequirementKind.ATOMIC),
                AstExposureKind.COMMAND,
                workflow);
    }

    /**
     * A second, complete, legal Capability (SearchGoods) used for v0.2
     * AddCapability candidate scenarios. Reuses the existing User entity,
     * PublishGoodsInput, and InvalidGoodsPrice error declarations so that
     * the candidate adds exactly one new declaration (the Capability
     * itself) — matching the SCOPE-101 "exactly one new declaration"
     * invariant. The workflow uses a Find step + Return step.
     */
    // [RQ-06 RECOVERY NOTE] Removed methods baseWorkflow/modifiedWorkflow/
    // searchGoodsCapability/searchGoodsWorkflow: their expression helpers
    // (priceGeLiteral/priceGtLiteral/unitLiteral/memberOfInput/decimal bodies)
    // were lost in historical output truncation (session block
    // 2026-07-19T23:27:16 line 1315; only method signatures survive in block
    // 2026-07-19T10:29:10 line 1001). See docs/recovery inventory.
    // [RQ-06 RECOVERY NOTE] Removed methods baseWorkflow/modifiedWorkflow/
    // searchGoodsCapability/searchGoodsWorkflow: their expression helpers
    // (priceGeLiteral/priceGtLiteral/unitLiteral/memberOfInput/decimal bodies)
    // were lost in historical output truncation (session block
    // 2026-07-19T23:27:16 line 1315; only method signatures survive in block
    // 2026-07-19T10:29:10 line 1001). See docs/recovery inventory.

    // [RQ-06 RECOVERY NOTE] One assertion-helper method (build + assertScope101-style,
    // with signature in a 25-token historical truncation gap) was NOT recoverable.
    // Historical evidence: session block 2026-07-19T23:27:16 (rollout-2026-07-18T20-23-06,
    // line 1315) truncates at 39945 chars; no other block contains the signature.
}