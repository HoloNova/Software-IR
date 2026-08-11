# KCG-Code 灾后测试资产与权威状态盘点（RQ-00）

> 创建日期：2026-08-10
> 状态：COMPLETE（RQ-00 交付物）
> 证据边界：本清单区分【历史记录】（来自 Codex 会话 JSONL 或旧文档，未在灾后重新验证）与【当前运行证据】（2026-08-10 实际执行命令的输出）。
> 会话记录根目录：`C:\Users\zdw00\.codex\sessions\2026\`（约 359 MB，107 个 JSONL 会话文件）。

## 0. 一句话结论

- 当前 28 个测试 Java 文件 + 15 个测试资源；**实际可通过 64 项测试**（parser 43、lowering-api 4、spring-lowering 13、generator 4），全部来自 2026-08-10 全新离线运行。
- 当前 `sir-toolchain-application` 与 `kcg-cli` 的测试 **testCompile 失败**（缺测试支持类），`sir-semantic`、`sir-project-graph`、`sir-change` **完全没有测试源码**。
- 历史 392 项基线是 2026-07-18 记录；灾前（2026-08-04）最后全 reactor 记录为 toolchain-application 1130 测试 + kcg-cli 54 测试，10/10 模块 BUILD SUCCESS。两者都不能作为当前结论。
- 磁盘上所有 28 个当前测试文件均有历史路径证据（无"来源未知"路径），但**内容级**来源（历史原文 / 反编译 / 灾后新写）需各 RQ 任务逐文件核对。

---

## 1. 当前运行证据（2026-08-10 实测）

### 1.1 运行方式

`mvn` 脚本在本机 git-bash 中报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`（环境问题，非项目问题）。**可用替代调用**（后续 RQ 任务通用）：

```bash
cd "/c/Users/zdw00/Desktop/Software IR" && \
java -Dmaven.multiModuleProjectDirectory="C:\Users\zdw00\Desktop\Software IR" \
  -Dmaven.home="D:\environment\apache-maven-3.9.14" \
  -Dclassworlds.conf="D:\environment\apache-maven-3.9.14\bin\m2.conf" \
  -cp "D:\environment\apache-maven-3.9.14\boot\plexus-classworlds-2.9.0.jar" \
  org.codehaus.plexus.classworlds.launcher.Launcher \
  "-Dmaven.repo.local=D:\maven-repo" -o <目标>
```

（Maven 3.9.14，Java 21.0.11 Temurin。PowerShell 用户直接使用 `mvn.cmd` 无此问题。）

### 1.2 全 reactor `mvn test`（`-Dmaven.test.failure.ignore=true`，离线）结果

| 模块 | 结果 | Surefire 发现/通过 | 说明 |
|---|---|---|---|
| sir-parser | SUCCESS | **43 通过**，0 fail/0 err/0 skip | 8 个测试类全部运行 |
| sir-semantic | SUCCESS（无测试） | 0 | 无 `src/test` 源码 |
| sir-lowering-api | SUCCESS | **4 通过** | LoweringContractTest |
| sir-lowering-spring-boot | SUCCESS | **13 通过** | 5 个测试类（LoweringTestSupport 为辅助类不运行） |
| sir-generator-spring-boot | SUCCESS | **4 通过** | GeneratorArchitectureRegressionTest 3 + ImportSorterTest 1 |
| sir-project-graph | SUCCESS（无测试） | 0 | 无 `src/test` 源码 |
| sir-change | SUCCESS（无测试） | 0 | 无 `src/test` 源码 |
| sir-toolchain-application | **FAILURE（testCompile）** | 0 | 缺 `ConformanceFixtures`、`SchemaName`、`ConformanceResult`、`ConformanceFailureKind`、`SpringBootTargetConformanceIT` |
| kcg-cli | SKIPPED（reactor 中断）；单独 `-pl kcg-cli test-compile` 亦 **FAILURE** | 0 | 缺 `MvpEvidenceRoot`、`MvpEvidence`、`MvpSupport.TreeSnapshot` 等测试支持类 |

**当前实际通过合计：64 项（0 failure、0 error、0 skip）** —— 与灾后已知上下文完全一致。

parser 逐类实测：AstDeterminismTest 2、GrammarBoundaryTest 12、LexicalDiagnosticTest 7、ModelInvariantTest 4、ParserDiagnosticSpanTest 1、SirParserFactoryTest 2、SirParserTest 12、WorkflowAstShapeTest 3。
spring-lowering 逐类实测：ActorIdentityBoundaryTest 1、SpringBootLoweredIrValidatorTest 1、SpringBootLoweringBoundaryTest 5、SpringBootLoweringDeterminismTest 2、SpringBootLoweringIntegrationTest 4。

### 1.3 当前磁盘资产（逐模块）

| 模块 | 当前测试 Java | 当前测试资源 | Surefire 可运行 |
|---|---|---|---|
| sir-parser | 8 | 7（invalid/ 4 + valid/ 3） | 8 类 / 43 项 ✓ |
| sir-semantic | 0 | 0 | — |
| sir-lowering-api | 1 | 0 | 1 类 / 4 项 ✓ |
| sir-lowering-spring-boot | 6（5 测试 + 1 辅助） | 3 | 5 类 / 13 项 ✓ |
| sir-generator-spring-boot | 3（2 测试 + 1 辅助） | 3 | 2 类 / 4 项 ✓ |
| sir-project-graph | 0 | 0 | — |
| sir-change | 0 | 0 | — |
| sir-toolchain-application | 9 | 2 | **0（testCompile 阻断）** |
| kcg-cli | 1 | 0 | **0（testCompile 阻断）** |
| **合计** | **28** | **15** | **16 类 / 64 项** |

当前 9 个 toolchain 测试文件：`api/ToolchainStageFailureTest`、`ApplicationArchitectureTest`、`conformance/ConformanceFixtureSqlTest`、`conformance/SpringBootTargetConformanceITTest`、`conformance/StrongFileIdentity`、`conformance/StrongFileIdentityTest`、`conformance/WindowsFileIdInfo`、`internal/FileTransactionFaultInjectionTest`、`ToolchainApplicationFailureTest`。
当前 1 个 kcg-cli 测试文件：`io/kcg/cli/mvp/MvpEvidenceHardeningTest`。

---

## 2. 历史记录（来自 Codex 会话与旧文档，非当前结论）

### 2.1 阶段基线演进（全部为【历史记录】）

| 日期 | 记录 | 出处 |
|---|---|---|
| 2026-07-14 | parser 41 + semantic 78 = **119 tests** | `.memory/ACTIVE/BUGS.md`、`.memory/DESIGN/EXPERIMENTS.md`、`.memory/INDEX.md`、REBUILD_CONTEXT §4 |
| 2026-07-16 | parser 41 + semantic 78 + lowering-api 4 + spring-lowering 12 = **135 tests** | `.memory/INDEX.md` 快速摘要、`.memory/ACTIVE/BUGS.md` |
| 2026-07-17 | Parser/Semantic/Lowering/Generator/Application = **283 tests** | REBUILD_CONTEXT §4 |
| 2026-07-18 | parser 43、semantic 78、lowering-api 4、spring-lowering 32、spring-generator 47、project-graph 94、application 94 = **392 tests**，0 failure/0 error，4 PathGuard Windows 条件跳过 | `AGENTS.md` §5、`.memory/ACTIVE/DEVELOPMENT.md`；会话 `2026/07/18/rollout-2026-07-18T19-25-37-*.jsonl` 可见 `Tests run: 94, Skipped: 4` 与重复 reactor 运行 |
| 2026-07-18 后 | typed reference-site 契约**已实现**（历史 `TypedReferenceSiteContractTest` 24 项、`ResolveOnceArchitectureTest` 9 项），semantic 后期总数未在后期报告单独引用 | 会话 `2026/07/19` 起；08/04 会话 compaction 中的新版 AGENTS.md 描述 `ReferenceSiteBindings/ReferenceSite/ReferenceRole` 与 `NormalizedField` 七组件 record |
| 2026-07-26 | Stage E conformance harness 闭环：toolchain-application **969 tests**（20 skip），reactor 9/9 SUCCESS | 会话 `2026/07/26/rollout-2026-07-26T17-24-58-019f9dbd-*.jsonl`（验收表原文） |
| 2026-07-30 | Stage E 在声明 MySQL reference tuple 上两次外部资格运行 `QUALIFIED`（只适用记录环境）；Stage F 只读 `kcg context/plan` 已存在 | `.memory/INDEX.md` 阶段状态、REBUILD_CONTEXT §4 |
| 2026-08-04 | Stage E 返工：toolchain 772（20 skip）；M1/M2：**toolchain-application 1130（22 skip）、kcg-cli 42→54**，全 reactor 10/10 模块 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify` BUILD SUCCESS（单 fork 模式）；M3 定向命令通过；未宣称 M4/MVP_FEASIBLE；外部 IT 未运行、非 QUALIFIED | 会话 `2026/08/04/rollout-2026-08-04T20-41-08-019fccca-*.jsonl`（M1/M2/M3 报告原文；1038 为 M1 模块回归记录，20 skip = PathGuard 4 + PlanProtector 9 + ChangePlanningApplicationTest 7） |

注意：toolchain 模块数 546→772→969→1038→1130 之间的升降对应 conformance 测试的 opt-in/默认归属变化与新增测试，**不应**解读为测试被删除。

### 2.2 历史关键验收命令（【历史记录】，来自会话报告原文）

```powershell
# 07-18 基线（AGENTS.md §5 协议命令）
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
# 07-26/08-04 Stage E（单 fork 模式规避 Windows ATOMIC_MOVE flaky）
mvn -o -pl sir-toolchain-application -am test -q
mvn -o clean verify -q -DforkCount=1 -DreuseForks=false
# 08-04 M3 定向
mvn -o -pl kcg-cli -am -DforkCount=1 -DreuseForks=false -Dtest=MvpApplyRecoverTest,KcgCliWorkflowTest test
# 历史 CLI 冒烟（REBUILD_CONTEXT §6）
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl kcg-cli exec:java "-Dexec.mainClass=io.kcg.cli.KcgCli" "-Dexec.args=--help"
```

已知历史环境事实：默认并行 surefire 下 `ChangeExecutionApplyTest`/`ChangeApplyCrashWindowTest`/`ChangePlanningApplicationTest` 偶发 `SIR-APP-WRITE-002`（Windows `ATOMIC_MOVE` flaky，08-04 报告确认与改动无关）；`SpringBootTargetConformanceIT`（*IT 后缀）不被 Surefire 默认 includes 拾取，且受 `kcg.conformance.enabled=true` 系统属性门禁（08-04 报告原文）。

---

## 3. 逐模块三类资产清单

图例：【当前】= 磁盘存在且（如可编译）可运行；【缺失】= 历史存在、当前不存在；【未知】= 当前存在但历史无路径证据。所有路径均为 `src/test/java/` 或 `src/test/resources/` 相对模块根。

### 3.1 sir-parser（当前 8 类 / 43 项通过）

| 类别 | 资产 |
|---|---|
| 【当前】 | java：`io/kcg/sir/parser/{AstDeterminismTest, GrammarBoundaryTest, LexicalDiagnosticTest, ModelInvariantTest, ParserDiagnosticSpanTest, SirParserFactoryTest, SirParserTest, WorkflowAstShapeTest}.java`；resources：`invalid/{arbitrary-call, chained-comparison, unsupported-target, unsupported-version}.sir`、`valid/{all-workflow-steps, campus-market, unit-capability}.sir` |
| 【缺失】 | java：`io/kcg/sir/AstNodeIdTest.java`（历史路径证据：会话 2026/07 全月；未在任何"Running"列表出现，可能为早期类后被并入其他测试） |
| 【未知】 | 无（当前 8 类全部命中历史路径） |

历史逐类最大测试数（surefire 输出聚合）：GrammarBoundaryTest 12、SirParserTest 9、LexicalDiagnosticTest 7、ModelInvariantTest 4、WorkflowAstShapeTest 3、AstDeterminismTest 2、SirParserFactoryTest 2、ParserDiagnosticSpanTest 1。当前实测 43 与 AGENTS.md 历史 43 一致。

### 3.2 sir-semantic（当前 0 —— RQ-01 目标，缺口最大模块之一）

| 类别 | 资产 |
|---|---|
| 【当前】 | 无 |
| 【缺失】 | java（15 文件，历史路径证据：会话 2026/07/14–07/23）：`io/kcg/sir/semantic/{ConstraintValidationTest, DeterminismTest, LegalSemanticTest, NormalizationTest, RectificationRegressionTest, ResolveOnceArchitectureTest, SymbolResolutionTest, TypeCheckingTest, TypedReferenceSiteContractTest, WorkflowValidationTest}.java`、`io/kcg/sir/semantic/internal/{ResolveOnceArchitectureTest, TypedReferenceSiteContractTest}.java`（internal 版为后期迁移产物）、支持类 `io/kcg/sir/semantic/{SemanticTestSupport, TestSources, TestSupport}.java`；resources（3）：`valid/{all-workflow-steps, campus-market, unit-capability}.sir` |
| 【未知】 | 无 |

历史逐类最大测试数：RectificationRegressionTest 28、TypedReferenceSiteContractTest 24、ConstraintValidationTest 6、NormalizationTest 6、DeterminismTest 5、LegalSemanticTest 8、ResolveOnceArchitectureTest 9、SymbolResolutionTest 7、TypeCheckingTest 9、WorkflowValidationTest 9。历史总基线：78（07-18）；typed reference-site 加入后未再见单独总数。
**注意**：当前主源码已含 `ReferenceSiteBindings/ReferenceSite/ReferenceRole`（typed reference-site 已实现），恢复测试时应面向当前实现，且不得按名称重查（Resolve-once 边界）。

### 3.3 sir-lowering-api（当前 1 类 / 4 项通过）

| 类别 | 资产 |
|---|---|
| 【当前】 | java：`io/kcg/sir/lowering/api/LoweringContractTest.java` |
| 【缺失】 | 无（历史亦只有此类；无历史资源） |
| 【未知】 | 无 |

历史逐类最大测试数：LoweringContractTest 4 —— 与当前实测一致。

### 3.4 sir-lowering-spring-boot（当前 5 类 / 13 项通过；历史 32）

| 类别 | 资产 |
|---|---|
| 【当前】 | java：`io/kcg/sir/lowering/springboot/{ActorIdentityBoundaryTest, SpringBootLoweredIrValidatorTest, SpringBootLoweringBoundaryTest, SpringBootLoweringDeterminismTest, SpringBootLoweringIntegrationTest}.java` + 辅助 `LoweringTestSupport.java`；resources：`invalid/actor-non-identity.sir`、`valid/{all-workflow-steps, campus-market}.sir` |
| 【缺失】 | java：`ActorIdentityBindingTest`、`ActorIdentityRequirementValidationTest`（历史最大 12）、`ActorIdentityTransportMatrixTest`（11）、`LoweredIrContractTest`（7）、`LoweredIrHardeningTest`（5，历史"Running"次数最多 259 次）、`LoweringArchitectureGateTest`（2）、`SpringBootLoweringTest`；resources：`valid/{input-ref-test, multi-actor, persist-provenance}.sir` |
| 【未知】 | 无 |

历史逐类最大测试数合计（缺失 7 类）：约 45 项（含当前 13 项中的重叠类）。历史总数 32（07-18）→ 后期含 V0_2/actor identity 矩阵测试后未见单独总数；注意 `LoweredIrHardeningTest` 是历史运行最频繁的类（259 次 Running 记录）。

### 3.5 sir-generator-spring-boot（当前 2 类 / 4 项通过；历史 47）

| 类别 | 资产 |
|---|---|
| 【当前】 | java：`io/kcg/sir/generator/springboot/GeneratorArchitectureRegressionTest.java`、`internal/ImportSorterTest.java` + 辅助 `GeneratorTestSupport.java`；resources：`valid/{campus-market, compound-find, unit-output}.sir` |
| 【缺失】 | java：`ActorTransportGenerationTest`（历史最大 21）、`GeneratorArchitectureTest`、`GeneratorDetailedTest`（23）、`GeneratorDeterminismTest`、`GeneratorIntegrationTest`（9）、`GeneratorPomXmlTest`（10）、`OfflineJavaCompilerTest`（3）；resources：`valid/{actor-identity, all-workflow-steps}.sir` |
| 【未知】 | 无 |

### 3.6 sir-project-graph（当前 0 —— RQ-04 目标）

| 类别 | 资产 |
|---|---|
| 【当前】 | 无 |
| 【缺失】 | java（9 文件）：`io/kcg/sir/projectgraph/{CanonicalSnapshotMalformedTest, CanonicalSnapshotTest, GraphTestSupport, ProjectGraphArchitectureTest, ProjectGraphBuilderTest, ProjectGraphDeterminismTest, ProjectGraphImmutabilityTest, ProjectGraphQueryTest, ProjectGraphValidatorTest}.java`；无历史资源 |
| 【未知】 | 无 |

历史逐类最大测试数：CanonicalSnapshotMalformedTest 38、CanonicalSnapshotTest 32、ProjectGraphValidatorTest 36、ProjectGraphArchitectureTest 19、ProjectGraphBuilderTest 14、ProjectGraphQueryTest 14、ProjectGraphImmutabilityTest 9、ProjectGraphDeterminismTest 8。历史总数 94（07-18，0 skip）。

### 3.7 sir-change（当前 0 —— RQ-06 目标；历史 Change SIR v0.1–v0.6 时代）

| 类别 | 资产 |
|---|---|
| 【当前】 | 无 |
| 【缺失】 | java（15 文件）：`io/kcg/sir/change/{ChangeApiImmutabilityTest, ChangePlannerAddCapabilityTest, ChangePlannerArchitectureTest, ChangePlannerDeterminismTest, ChangePlannerHappyPathTest, ChangePlannerImpactTest, ChangePlannerModifyActorlessReadonlyCapabilityExposureTest, ChangePlannerModifyInputFieldConstraintsTest, ChangePlannerModifyUnreferencedInputFieldTypeTest, ChangePlannerRemoveCapabilityTest, ChangePlannerScopeTest, ChangePlannerTargetTest, ChangeTestFixtures, ClosureComputerTest}.java`、`io/kcg/sir/change/internal/SemanticProjectionTest.java`；无历史资源 |
| 【未知】 | 无 |

历史逐类最大测试数：ChangePlannerModifyActorlessReadonlyCapabilityExposureTest 57、ChangePlannerModifyUnreferencedInputFieldTypeTest 57、ChangePlannerModifyInputFieldConstraintsTest 49、ChangeApiImmutabilityTest 47、ChangePlannerRemoveCapabilityTest 31、ChangePlannerAddCapabilityTest 23、ChangePlannerScopeTest 17、ChangePlannerImpactTest 8、ChangePlannerTargetTest 7、ChangePlannerDeterminismTest 6、ChangePlannerHappyPathTest 6、ChangePlannerArchitectureTest 4、SemanticProjectionTest 8、ClosureComputerTest 2。缺失类合计约 **300+ 项**（最大数求和 306）。

### 3.8 sir-toolchain-application（当前 9 文件但 testCompile 阻断；历史 94→1130）

| 类别 | 资产 |
|---|---|
| 【当前】 | java（9，全部被缺失支持类阻断）：`api/ToolchainStageFailureTest`、`ApplicationArchitectureTest`、`conformance/{ConformanceFixtureSqlTest, SpringBootTargetConformanceITTest, StrongFileIdentity, StrongFileIdentityTest, WindowsFileIdInfo}`、`internal/FileTransactionFaultInjectionTest`、`ToolchainApplicationFailureTest`；resources：`invalid/{actor-non-identity, semantic-unresolved-name}.sir` |
| 【缺失】 | ① **阻断当前 testCompile 的最小闭包**（REBUILD_CONTEXT §8 + 本次实测一致）：`conformance/{ConformanceFixtures, SchemaName, ConformanceResult, ConformanceFailureKind, SpringBootTargetConformanceIT}`；② 历史存在的主测试类（会话 2026/07/19–08/04）：`api/{DigestBoundApplyTest, GeneratedBaselineRegistrationTest, M1TestSupport, StageFTocTouApiTest, ToolchainGraphStageFailureTest}`、`application/{ApplicationTestSupport, B0B1DeltaBindingIntegrationTest, ChangeApplyCrashWindowTest, ChangeCreateApplyTest, ChangeCreateCrashWindowTest, ChangeCreateJournalRecoveryTest, ChangeCreateRecoveryReworkTest, ChangeDeleteApplyTest, ChangeDeleteCrashWindowTest, ChangeDeleteRecoveryReworkTest, ChangeDeleteRecoveryTest, ChangeExecutionApplyTest, ChangeExecutionCrossJvmLockContentionTest, ChangeExecutionRecoveryTest, ChangeExecutionRegistrationTest, ChangeExecutionTestSupport, ChangePlanningApplicationTest, ChangePlanningContextInspectionTest, ChangePlanningHashMatrixTest, ChangePlanningTestSupport, DPhaseE2EAcceptanceDriver, E2EAcceptanceDriver, EPhaseE2EAcceptanceDriver, FileTransactionFaultInjectionTest(旧路径，已移至 internal), LockHolderChildProcess, RealGenerationValidationTest, SirParserFactoryTest, StageFExistingOnlyLockTest, StageFTocTouTest, ToolchainApplicationTest, ToolchainConflictPolicyTest, ToolchainDeterminismTest, ToolchainHappyPathTest, ToolchainManifestImmutabilityTest, ToolchainProjectGraphIntegrationTest, ToolchainReadStageTest, ZeroWriteSnapshots}`、`internal/{PathGuardTest, PlanProtectorTest}`、`internal/bundle/BaselineDescriptorCodecTest`、`internal/state/{ChangeRecoveryDeltaBindingTest, DeleteManifestDeltaVerifierTest, DeleteTransactionJournalStrictParseTest, SafeTargetResolverTest, SimulatedProcessCrash, StateRootLockExistingOnlyTest, TransactionJournalStrictParseTest}`；③ conformance harness 其余 ~25 类（08/04 报告："全部 30 个类"；如 `ConformanceRun/StateMachine/Suite/Environment/Scenario*、OwnedRunDirectory、EvidenceDirectory、Mysql*、TargetDependencyInspector、MavenProjectRunner、SpringApplicationProcess` 等，完整清单见历史路径提取）；resources：`campus-market.sir`、`valid/` 下 13 个 `.sir`（campus-market 系列 base/candidate）、`conformance/{campus-market-ddl.sql, mysql/campus-market-ddl.sql, mysql/campus-market-seed.sql, sir/campus-market-ig-readonly.sir}` |
| 【未知】 | 无（9 个当前文件全部命中历史路径） |

历史总数：94（07-18）→ 419（D 阶段报告）→ 546 → 969（07/26）→ 772（08/04 返工）→ 1038（M1 模块回归）→ **1130（M1/M2 全量，22 skip）**。conformance 默认测试 226 项明细（08/04 报告）：BusinessEndpointReadinessTest 16、ChildEnvironmentAndSecretsTest 37、ConformanceRunTest 17、ConformanceStateMachineTest 19、DropAuthorizationProofTest 21、EvidenceDirectoryTest 10、MysqlSchemaInventoryTest 18、MysqlSqlConstructionTest 9、OwnedRunDirectoryTest 13、ScenarioEndpointTest 35、SchemaNameTest 21、TargetDependencyInspectorTest 10。

### 3.9 kcg-cli（当前 1 文件但 testCompile 阻断；历史 42→54）

| 类别 | 资产 |
|---|---|
| 【当前】 | java：`io/kcg/cli/mvp/MvpEvidenceHardeningTest.java`（历史最大 5 项） |
| 【缺失】 | ① **阻断当前 testCompile 的支持类**（实测）：`io/kcg/cli/mvp/{MvpEvidenceRoot, MvpEvidence}`、`io/kcg/cli/mvp/MvpSupport`（含嵌套 `TreeSnapshot`）；② 历史测试类：`io/kcg/cli/{CliCommandLineTest（17）, KcgCliWorkflowTest（6）, MvpApplyRecoverTest（16）, MvpGenerateRegisterTest（11）, StageFInternalErrorTest（5）, RecoveryTestFixtures, JsonAssert, StrictSnapshot, CliTestFixtures}`、`io/kcg/cli/mvp/{LocalSoftwareIrMvpAcceptanceIT（1）, MvpEvidenceTest（7）, MvpSupportSnapshotTest（8）, MvpCliSeams, MvpControlSession, MvpEnvironment, MvpProcesses, MvpSanitize, MvpScenarios}`；resources（13）：`io/kcg/cli/{campus-market.sir, campus-market-actorless-readonly-base.sir, campus-market-actorless-readonly-candidate.sir, campus-market-candidate.sir, campus-market-candidate-add-capability.sir, campus-market-candidate-modify-input-field-constraints.sir, campus-market-candidate-remove-capability.sir, campus-market-minimal.sir, campus-market-minimal-add-search-goods.sir, campus-market-two-capabilities.sir, campus-market-two-capabilities-remove-publish-goods.sir, v05-base.sir, v05-candidate.sir}` |
| 【未知】 | 无 |

历史总数：42（M1 报告）→ **54（M2 报告）**。M2 报告原文：21 个 Stage F workflow 测试、17 个 CliCommandLineTest、5 个 StageFInternalErrorTest 原样通过。

---

## 4. 最优先缺失文件列表（按恢复优先级）

| 优先级 | 文件（模块/路径） | 阻断内容 | 对应 RQ |
|---|---|---|---|
| P0-1 | toolchain `conformance/{ConformanceFixtures, SchemaName, ConformanceResult, ConformanceFailureKind, SpringBootTargetConformanceIT}` | 当前全 reactor `mvn test` 中断于此（testCompile） | RQ-05 |
| P0-2 | kcg-cli `mvp/{MvpEvidenceRoot, MvpEvidence, MvpSupport}` | kcg-cli 当前唯一测试无法编译 | RQ-08 |
| P1-1 | sir-semantic 15 个测试文件 + 3 资源 | 历史 78+ 项测试全部缺失；RQ-01 唯一目标 | RQ-01 |
| P1-2 | sir-project-graph 9 个测试文件 | 历史 94 项测试全部缺失 | RQ-04 |
| P1-3 | sir-change 15 个测试文件 | 历史约 306 项测试全部缺失 | RQ-06 |
| P2-1 | toolchain 其余 ~90 文件（api/application/internal/state + conformance 其余） | 历史 94→1130 的主体 | RQ-07（+RQ-05） |
| P2-2 | generator 7 文件 + 2 资源 | 历史 47→当前 4 的缺口 | RQ-03 |
| P2-3 | lowering-spring-boot 7 文件 + 3 资源 | 历史 32→当前 13 的缺口 | RQ-02 |
| P2-4 | kcg-cli 其余 ~18 文件 + 13 资源 | 历史 54 的其余部分 | RQ-08 |
| P3 | parser `AstNodeIdTest` | 唯一历史 parser 缺失类 | RQ-00 后续 |

---

## 5. 文档冲突列表（仅记录，不裁决删除/回退）

| # | 冲突 | 涉及文件 | 说明 |
|---|---|---|---|
| C1 | 392 基线被表述为"当前验收基线" | `AGENTS.md` §5 vs `REBUILD_CONTEXT` §8 / 本次实测 | AGENTS.md 写"当前验收基线是 … 共 392 项测试"，但 392 是 07-18 历史记录；灾后当前实际为 64 项通过。AGENTS.md 未在灾后更新 |
| C2 | 模块列表缺 sir-change 与 kcg-cli | `AGENTS.md` §3 vs `pom.xml`（9 模块） | AGENTS.md 只描述 7 个模块，未含 Change SIR v0.1–v0.6、Apply/Recovery、CLI 及 ADR-014~019 时代架构 |
| C3 | typed reference-site 被描述为"下一阶段建议" | `AGENTS.md` §6 vs 当前主源码 | 当前 `sir-semantic/src/main/java` **已含** `ReferenceSiteBindings/ReferenceSite/ReferenceRole`，08/04 会话中的 AGENTS.md 新版亦已记录该特性；磁盘上 AGENTS.md 是 07-18 旧版（落后于代码） |
| C4 | 文档入口引用大量缺失文件 | `AGENTS.md` §7 vs `docs/` | 缺失：`KCG-Code_项目总纲与技术上下文.md`、`ADR-002`、`ADR-004`、`ADR-014~018`、`2026-07-14-sir-v0.1-semantic-model-design.md`、`2026-07-16-sir-v0.1-generator-design.md`、`2026-07-17-project-symbol-graph-v0.1-design.md`；仅 ADR-001/003/019 在场。REBUILD_CONTEXT §7："缺文件不表示决策不存在；应优先从 Codex 历史重建，不得凭标题补写" |
| C5 | INDEX.md 快速摘要停留在 07-16 | `.memory/INDEX.md` vs 当前代码/AGENTS.md | "parser 41 + semantic 78 + lowering-api 4 + spring-lowering 12 = 135"且"尚未生成文件"——与 Generator/Application/PSG/Change/CLI 均已实现的现状冲突 |
| C6 | INDEX.md 重复段落 | `.memory/INDEX.md` | "阶段状态（2026-07-30）"整段重复两次；`STAGE_E_QUALIFICATION_LESSONS.md` 与"第三轮最终复核"各列两次 |
| C7 | 测试下限过时 | `.memory/CORE/CONSTRAINTS.md`（"parser 41、semantic 78、合计 119"）vs `AGENTS.md`（parser 43、392） | 07-16 时代下限 |
| C8 | 早期测试数记录 | `.memory/ACTIVE/BUGS.md`（"119 项全部通过"/"135 项通过"）vs 后期记录 | 07-14/07-16 时代；非当前结论 |
| C9 | Stage E QUALIFIED 表述 | `.memory/INDEX.md` 阶段状态（07-30）vs REBUILD_CONTEXT §4 | 历史确实 QUALIFIED（两次外部资格运行），但只适用记录的环境；灾后未重新验证。08/04 报告亦明确"未执行外部资格矩阵、不是 QUALIFIED"。不得把历史 QUALIFIED 当当前资格 |
| C10 | DEVELOPMENT.md 为 7 模块时代 | `.memory/ACTIVE/DEVELOPMENT.md`（07-18） | 392 基线与其日期一致，但与 9 模块现状及 Change/CLI 时代不符；属正常历史文档，非错误 |
| C11 | 工作分解文档与 REBUILD 互相一致，但均未反映 AGENTS.md 过时 | `docs/recovery/2026-08-10-*workplan.md`、`.memory/REBUILD_CONTEXT_2026-08-10.md` | 两者已正确标注 392/64 的证据边界；建议后续 RQ-11 收口时统一 AGENTS.md |

---

## 6. 下一任务 RQ-01 的精确输入

**任务**：恢复 `sir-semantic` 历史测试（工作分解文档 RQ-01）。

1. **恢复目标（15 个 java + 3 个资源，全部有历史路径证据）**：
   - 8 个 07-14 时代类：`ConstraintValidationTest`、`DeterminismTest`、`LegalSemanticTest`、`NormalizationTest`、`RectificationRegressionTest`、`SymbolResolutionTest`、`TypeCheckingTest`、`WorkflowValidationTest`（历史逐类最大测试数：28/6/5/8/6/7/9/9，合计 78 与基线一致）
   - 2 个 typed reference-site 时代类：`TypedReferenceSiteContractTest`（24）、`ResolveOnceArchitectureTest`（9）——**当前主源码已含 ReferenceSiteBindings/ReferenceSite/ReferenceRole，恢复测试应面向当前实现**
   - 2 个 internal 版（迁移产物，按重命名对待，优先恢复 root 版）
   - 3 个支持类：`SemanticTestSupport`、`TestSources`、`TestSupport`
   - 3 个资源：`src/test/resources/valid/{all-workflow-steps, campus-market, unit-capability}.sir`
2. **会话搜索范围**：`~/.codex/sessions/2026/07/{13,14,16,17,18}/*.jsonl`（原 8 类与 78 基线，已知含 `Tests run: 78` 的文件：`07/13/rollout-2026-07-13T08-14-05-019f58d2-*.jsonl`、`07/14/rollout-2026-07-14T06-54-14-019f5db0-*.jsonl`、`07/16/rollout-2026-07-16T14-57-48-019f69b7-*.jsonl`、`07/17/rollout-2026-07-17T06-42-50-019f6d18-*.jsonl`）；typed reference-site 类在 `2026/07/19` 起的会话。提取技巧：`grep -h -o 'sir-semantic/src/test/java/[A-Za-z0-9_/]*\.java' -r <会话目录> | sort -u` 定位文件输出/补丁位置
3. **验收命令**（注意本机 `mvn` 脚本 classworlds 故障，用 §1.1 的 java 直启等价式）：
   ```powershell
   mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-semantic -am test
   ```
4. **验收约束**：先 testCompile 再运行；不得以历史 78 为必须伪造的数字，少于 78 时逐项列出缺失类/fixture；不得削弱 Resolve/Type/Validate/Normalize 约束；不新增语法、不修改 ANTLR、不实现 typed reference-site 新功能（只恢复测试）；对任何主源码修改必须给出历史补丁/字节码/失败测试证据
5. **已知陷阱**：`NormalizedField` 当前为七组件 record（含 `directNamedTypeReferenceSiteId`），旧测试若用六参构造器应可用（历史保留委托构造器）；`sir-semantic` 只依赖 `sir-parser`，测试不得引入 Spring/MyBatis/Redis 依赖

---

## 7. 附：本盘点未覆盖/未决事项

- 当前 28 个测试文件的**内容级**来源（历史原文 vs 反编译 vs 灾后新写）未逐文件判定，需各 RQ 任务处理（路径证据全部命中历史）。
- 历史会话中 `Tests run: 0` 的行（`-Dtest` 筛选运行的非目标类）已按"max per class"聚合法排除影响。
- 07/19–07/23 的 Change 时代会话未逐文件定位到具体 JSONL（仅目录级证据）；RQ-06 需在该范围内搜索。
- 全量会话 235 MB+ 的逐文件补丁定位留给各 RQ 任务按需提取，本清单已给出路径模式与已知关键文件。

---

## 8. 2026-08-11 状态收敛（RQ-01 至 RQ-11 完成后）

### 已恢复且已验证（当前运行证据）

| RQ | 结果 | 说明 |
|---|---|---|
| RQ-01 | 103/103 | semantic（历史 78 + typed-ref-site 25），0 失败 |
| RQ-02 | 36/36 | lowering-api 4 + spring-lowering 32 |
| RQ-06 | 22/22 | sir-change（19+3）；12/15 类无内容证据 → 未验证 |
| RQ-07 | 101 过 + 10 skip | 非 conformance；6 skip 资源缺失 + 4 Windows 限制 |
| RQ-08 | 5/5 + 冒烟 | MvpEvidenceHardening 全过；KcgCliWorkflowTest 13 项类级 skip（资源缺失）；7 命令 Maven exec 冒烟 |
| RQ-09 | 12/12 | PathSecurityReviewTest；3 发现（路径收紧/junction fail-closed/manifest 重复）全闭合 |
| RQ-10 | 9/9 | RecoveryStateMachineTest；8 条 Recovery 不变量测试固化，零 src/main 修改 |

### 部分完成

- **RQ-05**：conformance 约 40 文件恢复；2 缺口（ConformanceSuite 403 行 / SpringBootTargetConformanceIT 550 行截断，07-26 输出 40KB 截断为恢复上限）；POM testExcludes 绕过编译，缺口未修复。

### 明确未验证 / 缺失

- Change 时代 SIR 资源（campus-market-candidate*.sir、v01-c.sir 等 20+ 个）在会话中仅有 ~134 字符预览，无完整 dump → kcg-cli 13 项与 Application 6 项 assumption skip。
- RQ-06 的 12/15 个测试类仅 surefire 运行记录（Scope=17、Impact=8、AddCapabilityTest=23 等），无源码内容。
- RQ-10 残余：DELETE 完整端到端发布路径（真实 B1 差异场景）、V2 CREATE / V1 UPDATE RecoveryEngine 路径、卷挂载点 isOther 行为——仅编译证据。
- RQ-03（generator 47）、RQ-04（PSG 94）未排期。

### 未覆盖平台差异

- junction 测试在 Windows 原生运行（本机）；POSIX symlink 场景由既有 PathGuardTest 覆盖；卷挂载点（volume mount point）isOther 行为无第二卷未实测。
