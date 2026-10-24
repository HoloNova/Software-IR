# 验证说明

本文件说明**怎样验证**、**证据怎么写**，以及目前有哪些证据。能力边界看 [项目状态](../PROJECT_STATUS.md)。2026-10-14 之前累积的资格报告和覆盖清单已移到 [history/qualification](../history/qualification/)，其中的数字和已删除机制的描述只代表当时。

## 1. 验证分三层

| 层 | 做什么 | 在哪里跑 |
| --- | --- | --- |
| 定向测试 | 单模块、`-Dtest=...`，覆盖本次改动影响的类 | 本机（受限资源） |
| 全量闸门 | 一次正式 `mvn -o clean verify`；仅失败时补跑 Completion 诊断，首发失败仍阻断 | GitHub CI |
| 业务／独立验证 IT | 真实 MySQL 8.4 + 生成工程构建／启动 + HTTP 断言 | GitHub CI |

本机资源很小（见 [AGENTS.md](../../AGENTS.md)「测试资源约束」），所以重活交给 CI；Claude 会话环境的约定见 [CLAUDE_EXECUTION_POLICY](../CLAUDE_EXECUTION_POLICY.md)。同一批改动只在 CI 上验证一次，本机不重复跑全量。

## 2. CI 工作流（`.github/workflows/verify.yml`）

源码 push 触发（纯文档过滤），一个 job 内依次：在线 JUnit dry-run 预热插件与 provider → 预热生成工程依赖 → 一次正式离线 Frozen 全量门 → 业务 IT（查询／写入／关系及后续业务，真实 MySQL 8.4）→ 独立验证 IT／CLI → 上传证据。Completion 仅 Frozen 失败后收集其余诊断，先上传首发失败报告；不会把失败转为通过。独立部署接续运行，实际测试列表以 verify.yml 为准。

| 步骤 | 内容 |
| --- | --- |
| 业务场景 IT | `QuerySliceBusinessConformanceIT`、`WriteSliceBusinessConformanceIT`、`RelationSliceBusinessConformanceIT`：生成工程，离线构建，连真实 MySQL，启动 Spring Boot，用独立 JDBC 与 HTTP 断言 |
| 独立验证 IT | `IndependentVerificationIT`：静态／构建／启动／业务四级各自独立出结果（正确样本、静态合法但业务错误的样本、不能编译的候选、数据库不可达）；`ProjectVerificationChainIT`：在 Git 工作区里，一个指令编辑经真实验证后发布，破坏需求的候选在改动前被拦下 |
| 证据 | 每个 IT 的证据目录与 surefire 报告作为 artifact 上传；证据要能对应到 commit SHA 和 run URL |

## 3. 证据口径

1. 测试数量只引用当次 Surefire 输出或 CI 的报告；历史数字不混写。
2. 被跳过、被排除、未启动的测试记为 `SKIPPED`／`BLOCKED`／`NOT_RUN`，不折算成通过。需要真实 MySQL 或参考环境变量的 IT 在环境缺失时只打印 `NOT_RUN`。
3. CI 证据必须覆盖待验收源码：给出 commit SHA、run URL 和 artifact。后续又有新提交时，旧运行不证明新提交。
4. 静态校验通过（`check`）只说明所请求的阶段通过，不证明生成的 Java 能构建、能启动或业务正确；独立验证的四级分别记录。
5. 纯文档修改只做 `git diff --check`、`git status --short` 与链接检查，不跑构建。

## 4. 当前证据

**当前完整验收基线：** `bf14d905b2edce5b67bc0ddf39ed189be2c10aa1` 的 [verify #154](https://github.com/HoloNova/Software-IR/actions/runs/39749656260) 全部门 success，显式锁与区间预约、G10／G6／G11、完整部署均闭环，见[第 25 节](#ci-154-accepted)。#155 主验证成功、部署被取消，不计整轮通过；新借阅限额源码待本批 CI，不能借用旧绿灯。下文旧运行保留为历史证据。

**历史运行：** [verify #88](https://github.com/HoloNova/Software-IR/actions/runs/38901782044)，受测提交 `fe40836`（`main`，feat(cli): kcg scaffold dual-token），success。冻结与完成两条全量闸门、业务场景 IT（含新的 `DualTokenScaffoldBusinessConformanceIT`：用脚手架输出作夹具，逐条验证双 Token 七条行为，含过期的访问令牌与 6 路同时刷新的竞争）、独立验证 IT、Standalone CLI smoke 均成功；核对时读了业务场景 IT 的日志，确认新 IT 运行并全部通过。

**再上一次运行：** [verify #79](https://github.com/HoloNova/Software-IR/actions/runs/38884618786)，受测提交 `6f244fa`（`main`，fix: 真实库核验发现的四个缺陷），success。冻结全量闸门、完成全量闸门、业务场景 IT（含新的 `TokenDeskBusinessConformanceIT`：`Uuid` 主键轮换、“设成同值”的守卫更新、重复行的唯一加载）、独立验证 IT、Standalone CLI smoke 均成功；核对时读了业务场景 IT 步骤的日志，确认新 IT 运行并通过。

**更早：** [verify #78](https://github.com/HoloNova/Software-IR/actions/runs/38715357765)，受测提交 `aa1ad70`（`main`，feat(lang): 守卫更新），success。冻结全量闸门、完成全量闸门、业务场景 IT（含新的 `GuardedTransitionBusinessConformanceIT`，8 线程并发轮换同一凭据恰有一个成功）、独立验证 IT、Standalone CLI smoke 均成功；核对时读了业务场景 IT 步骤的日志，确认新 IT 运行并通过。

**更早：** [verify #77](https://github.com/HoloNova/Software-IR/actions/runs/38712747591)，受测提交 `1f947db`（`main`，feat(lang): 时间平移与数值加减），success。冻结全量闸门、完成全量闸门、业务场景 IT、独立验证 IT、Standalone CLI smoke 五个步骤均成功；它覆盖时间平移与数值加减、表达式变体守卫及新增的离线编译用例；核对时只读了运行与步骤状态。

**再上一次运行：** [verify #76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131)，受测提交 `058c9d6`（`main`，feat(lang): 唯一字段与按唯一字段加载），success。冻结全量闸门、完成全量闸门、业务场景 IT、独立验证 IT、Standalone CLI smoke 五个步骤均成功；artifact 为 `conformance-evidence` 与 `surefire-reports`。它覆盖字段 `unique` 与按唯一字段 `load` 及其新增的离线编译用例；核对时只读了运行与步骤状态。

**再上一次运行：** [verify #73](https://github.com/HoloNova/Software-IR/actions/runs/38677243253)，受测提交 `3ebe5f1`（`main`，feat(cli): 单文件可运行包），success。冻结全量闸门、完成全量闸门、业务场景 IT、独立验证 IT、Standalone CLI smoke 五个步骤均成功；artifact 为 `conformance-evidence` 与 `surefire-reports`。它覆盖单文件包与新增的冒烟步骤，源码与 #72 相同。

**上一次运行：** [verify #72](https://github.com/HoloNova/Software-IR/actions/runs/38671123332)，受测提交 `1c80c73`（feat(cli): 公开CLI流程），success，四个测试步骤均成功；它覆盖公开 CLI 入口（generate／inspect／edit）。

**更早的运行：** [#70](https://github.com/HoloNova/Software-IR/actions/runs/38546065357)（`4e17ca3`，清理后、公开 CLI 入口之前的 `main`；整理前同一内容的 `0350e8f` 为 [#67](https://github.com/HoloNova/Software-IR/actions/runs/38544793410)）通过。[#58](https://github.com/HoloNova/Software-IR/actions/runs/38534225348)（`07f29f6`，整理前的旧 SHA，见 [COMMIT_MAP](../history/COMMIT_MAP.md)）通过。其前一次 #57 失败，原因是 `ProjectVerificationChainIT` 把工作目录建在检出目录内部，Git 顶层与预期不一致而被拒绝（`NOT_TOP_LEVEL`），属于测试夹具的问题，在 `07f29f6` 中修正（IT 自建独立仓库并打印诊断）；生产代码未改。

本文件未逐项抄录 #72 的测试数字和 IT 条目，需要时以该运行页面的报告和 artifact 为准。

**本机定向（2026-10-14，清理后的工作树，各模块 `*Test` 单元测试，不含 IT）：**

| 模块 | run / fail / error / skip |
| --- | --- |
| sir-parser | 96 / 0 / 0 / 0 |
| sir-semantic | 229 / 0 / 0 / 0 |
| sir-lowering-api | 4 / 0 / 0 / 0 |
| sir-lowering-spring-boot | 86 / 0 / 0 / 0 |
| sir-generator-spring-boot | 77 / 0 / 0 / 0 |
| sir-project-graph | 73 / 0 / 0 / 0 |
| sir-change | 28 / 0 / 0 / 0 |
| sir-toolchain-application | 216 / 0 / 0 / 0 |
| kcg-cli | 21 / 0 / 0 / 0 |

这是本机定向结果，不是全量闸门，也不含需要 MySQL 的 IT。

**历史证据：** 版本变更路径的专项证据（七类变更与从零生成逐字节一致、280 个中断检查点逐一重跑）见 [G1 报告](../history/roadmap/G1-workspace-version-report.md)；独立验证见 [R5 报告](../history/roadmap/R5-independent-verification-report.md)；课程查询／写入／关系场景的真实 MySQL 结果见 [history/work-orders](../history/work-orders/) 中 Q9、Q10、Q11、Q13。

### 公开CLI入口（2026-10-14 本机定向；2026-10-15 CI 通过）

新增generate/inspect/edit的本机定向证据为7类最新Surefire XML合计29 / 0 / 0 / 0，包含真实CLI子进程、多源组合候选和临时Git工程应用，详细范围与命令见[阶段报告](../CLI_WORKFLOW_REPORT.md)。这是提交前的本机结果，不加到云端旧830项。同一源码随后由 #72 的全量闸门、业务场景 IT 和独立验证 IT 覆盖。

### 单文件包（2026-10-15，提交 `3ebe5f1`）

`kcg-cli/target/kcg.jar` 与类路径启动的完整流程输出在本机对比一致（除含提交时间的 Git 提交 ID），见[阶段报告第4节](../CLI_WORKFLOW_REPORT.md)。`verify.yml` 新增的“Standalone CLI smoke”步骤在 [verify #73](https://github.com/HoloNova/Software-IR/actions/runs/38677243253)（同 SHA）success。

### 独立部署交付步骤③（2026-10-17，CI #97/#98验收）

`bf14b8d`的[verify #97](https://github.com/HoloNova/Software-IR/actions/runs/39025709647)与`656bb06`的[verify #98](https://github.com/HoloNova/Software-IR/actions/runs/39026265442)两个job均success：schema不变update、v2追加迁移（逐行数据不变、新列为NULL、新接口可引用旧数据）、重复执行为CURRENT、较旧包AHEAD拒绝、新实例全链重放及步骤①②各分项全部PASSED；中断与漂移按取舍记NOT_RUN。详见[报告第8节](../DEPLOYMENT_DELIVERY_REPORT.md)。只覆盖本样例与三类schema变化。

### 独立部署交付步骤②（2026-10-17，CI #95/#96验收）

`fee2a3b`的[verify #95](https://github.com/HoloNova/Software-IR/actions/runs/39021298934)与`ef324d6`的[verify #96](https://github.com/HoloNova/Software-IR/actions/runs/39021834946)两个job均success：Swagger按文档server/路径的真实请求、经公开入口的合同测试（13 PASSED / 0 FAILED / 7 NOT_VERIFIED）、同包第二个一次性实例上的双Token业务验收（26 / 0），以及步骤①各分项全部PASSED。证据为同run的deployment-evidence等artifact，详见[报告第7节](../DEPLOYMENT_DELIVERY_REPORT.md)。它只覆盖本部署样例，不代表步骤③或任意SIR的业务资格。

### 独立部署交付步骤①（2026-10-17，CI #94验收）

新package导出、物理规划、配置逻辑及既有CLI/生成器边界的7类最新XML合计26 / 0 / 0 / 0，详见[步骤①报告](../DEPLOYMENT_DELIVERY_REPORT.md)。没有在本机拉镜像、构建或运行部署/业务；`a7cba11022c04e6a07cb47d5070bde39a06b0adf`的[verify #93](https://github.com/HoloNova/Software-IR/actions/runs/39002409782)已执行：主job两条全量门、业务/独立验证IT及CLI烟测success；新job公开导出、容器构建、Flyway初始化和真实应用启动成功，HTTP验收JSON解析异常，权限/重启保留数据/丢失卷门NOT_RUN，清理PASSED，总workflow为failure。证据为deployment-evidence、backend-source-delivery、surefire-reports等同run artifact，详见报告。阻塞来自验收脚本；修复提交`30f50bdec6f7d4c485365b4a8b6ce5294d25b0c7`的[verify #94](https://github.com/HoloNova/Software-IR/actions/runs/39019809176)两个job全部success，部署、HTTP、权限、持久化、丢失卷和清理六个分项PASSED，步骤①验收。它不混入旧830项，也不代表步骤②③或完整部署产品已通过。

## 5. 未覆盖

- Windows 平台证据（junction 相关用例只在 Windows 上执行）。
- 预构建的发行物与版本化发布渠道（单文件包可由 `mvn package` 产出，CI 只做冒烟，不上传该包）。
- 没有跨进程锁；真实断电验证；对真实 Git 的故障注入（钩子失败、索引锁被占用）。
- SLM 生成样本集上的评测尚未启动，见 [SLM 评测计划](SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)。
- 新部署包已在CI中完成首次初始化，但HTTP及后续门尚未完成，不同包迁移未实现；原业务IT仍使用测试夹具，不能充当产品部署验收。

## 6. G7 可选值（2026-10-20，本机定向，待同源码 CI）

源码基点 `11bb51b5a779907e6659a61543b0cbc5cbe13041`，本节覆盖其上的本批 G7 修改，不是该基点原有 CI 的结论。环境实际为 Windows 11 / Temurin 21.0.11 / Maven 3.9.14，仓库 `D:\maven-repo`，单构建串行，Maven JVM `-Xmx768m`、测试 fork `-Xmx512m`；没有启动业务应用、数据库或容器。

### 已执行与结果

先新增 `OptionalValueSemanticsTest` 与夹具运行：15 / 15 / 0 / 0，失败点为新增 `is` 语法被解析器拒绝（RED）。随后语法与语义转绿；`OptionalValueLoweringTest` 在新增的 `presentComparison()` / `PresentComparison` 尚不存在时编译失败（降级计划的编译期 RED），补齐后转绿。初次语义复跑还纠正了测试夹具：分页视图应声明 `Page<view>`，不能拿未分页的 `List<Ref<Entity>>` 直接返回为 `List<view>`；未为此放宽生产规则。

定向命令（PowerShell；两次选择不同受影响测试，不执行全量门）：

```powershell
$env:MAVEN_OPTS='-Xmx768m'
mvn -B -o -pl sir-toolchain-application -am test "-Dtest=OptionalValueSemanticsTest,OptionalValueLoweringTest,GeneratorOptionalValueContractTest,SirDeclarationWriterRoundTripTest,WriteSliceSemanticsTest,WriteSliceLoweringTest,GeneratorWriteSliceContractTest,TopicSamplesTest,DeploymentPackageApplicationTest,ExpressionVariantCoverageTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine=-Xmx512m"
mvn -B -q -o -pl sir-toolchain-application -am test "-Dtest=OptionalValueSemanticsTest,OptionalValueLoweringTest,GeneratorOptionalValueContractTest,OptionalComparisonRenderingTest,SirDeclarationWriterRoundTripTest,RelationSliceLoweringTest,GeneratorRelationSliceContractTest,OptionalDeploymentFixtureTest,LanguageReferenceExamplesTest,AstDeterminismTest,TypedReferenceSiteContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine=-Xmx512m"
```

以下取本轮各类**最后一次** Surefire XML，不累加重复执行：

| 测试类 | run / fail / error / skip |
| --- | --- |
| OptionalValueSemanticsTest | 16 / 0 / 0 / 0 |
| OptionalValueLoweringTest | 2 / 0 / 0 / 0 |
| GeneratorOptionalValueContractTest | 13 / 0 / 0 / 0 |
| OptionalComparisonRenderingTest | 1 / 0 / 0 / 0 |
| SirDeclarationWriterRoundTripTest | 2 / 0 / 0 / 0 |
| AstDeterminismTest | 2 / 0 / 0 / 0 |
| TypedReferenceSiteContractTest | 18 / 0 / 0 / 0 |
| WriteSliceSemanticsTest | 29 / 0 / 0 / 0 |
| WriteSliceLoweringTest | 21 / 0 / 0 / 0 |
| GeneratorWriteSliceContractTest | 17 / 0 / 0 / 0 |
| RelationSliceLoweringTest | 13 / 0 / 0 / 0 |
| GeneratorRelationSliceContractTest | 6 / 0 / 0 / 0 |
| ExpressionVariantCoverageTest | 4 / 0 / 0 / 0 |
| LanguageReferenceExamplesTest | 3 / 0 / 0 / 0 |
| TopicSamplesTest | 1 / 0 / 0 / 0 |
| OptionalDeploymentFixtureTest | 1 / 0 / 0 / 0 |
| 上述通过范围合计 | **149 / 0 / 0 / 0** |
| DeploymentPackageApplicationTest（环境阻断，真实执行失败） | **16 / 16 / 0 / 0** |

不能将本轮整体称为全部通过：第一条命令因部署导出类失败，第二条命令成功。Windows 读源不支持安全的目录相对访问，导出在 `SIR-APP-SOURCES-READ-001: filesystem does not support secure directory-relative source reads` 处失败，发生在业务规划之前；没有跳过测试、篡改期望或放宽生产读源规则。新增 `OptionalDeploymentFixtureTest` 直接从源快照验证 v3 的编译、生成和交付规划，**不替代**文件系统导出验收。

### 这些证据证明什么

- 空值判断、六个比较操作、可选引用比较与拒绝空引用成员访问、可选算术拒绝；PATCH 的缺席／null／有值约定回归通过。
- 计划记录左右非空保护与底层类型，丢失计划或保护位不匹配会被 IR Validator 拒绝；已有相关子查询内部也在 NOT 前消除 UNKNOWN。
- 把生成的 Java 条件放进临时类，用 JDK 编译并反射执行：11 种条件各覆盖 3×3 有值／空值组合；另测小数数值相等（1.0 与 1.00）、可选时间比较、可选引用编号比较。不是仅断言模板字符串，也不是启动业务应用。
- 八题内存编译／生成／交付规划与语言参考示例通过；重新测量了当前源码与生成文件的行数，不以测量代替构建。
- `.github/scripts/deployment-smoke.py` 已加入 G7 真库断言，Python AST 语法检查通过。Favorite 本来在 v3 才建表，可选 score 随表创建，因此迁移链仍为 V1..V11，不改工作流版本预期。

### 尚未验证

同源码 CI、全量门、八题生成工程离线编译、真实 MySQL／HTTP、Linux 上部署导出：**NOT_RUN**，无新 run URL／artifact。负责人随后授权提交并推送、不等待 CI；CI 结果不作核验，也不默认记为通过，不使用旧 CI 代替。代码覆盖率未测量，不宣称达到 80%；真值表覆盖不等于行覆盖率。AFT Java/JDTLS 在初始化时退出，诊断为 PARTIAL，不能据此宣称无编译错误；上述 Maven 定向编译与测试才是本轮编译证据。无 UI 修改。

<a id="g2-g4-local"></a>

## 7. G2/G4 关联参数与标量查询（2026-10-20，本机定向）

本机验证时的基线为 `33f960b653fb25bd2ed2b9717c44904385ec83b2` 加当时的实现工作区，验证结果随本提交保存。负责人确认设计 12 第 18 节后实现：关联 any 的外部值正式绑定、工作流 exists／count、步骤内 item、声明与引用结构、实体 scope、源写回和通用编辑，以及 NOT 内 containsLiteral 的共用计划收集修复。不把查询后写入当成原子限额。

本机 Windows 11 / Java 21 / Maven 3.9.14。使用 `MAVEN_OPTS=-Xmx768m`、单构建、测试 fork `-Xmx512m`、离线 Maven，不用 `-T`。先以解析拒绝 exists、Lowering 拒绝 `input.status` 记录 RED，再分阶段转绿。扩大回归时旧测试的 LF 假设在 CRLF 夹具上失败；仅修测试入口或截取辅助函数，未改变生产词法规则。被失败阻断的后续类补跑，已通过且源码语义未再变的范围不重复跑。

最终所选 25 类各自最新 Surefire XML 去重合计 **192 tests / 0 failures / 0 errors / 0 skipped**：

| 范围 | 类与通过项数 |
| --- | --- |
| 解析 | AstDeterminismTest 2；ExpressionVariantCoverageTest 4 |
| 语义与结构 | ScalarQuerySemanticsTest 8；QuerySliceSemanticsTest 28；RelationSliceSemanticsTest 17；EntityScopeSemanticsTest 3；OptionalValueSemanticsTest 16；TypedReferenceSiteContractTest 18；StructureViewCoverageTest 1；SirDeclarationWriterRoundTripTest 2 |
| 源编辑 | ScalarQueryEditingTest 1；SourceEditorTest 16（含 CRLF 保真） |
| Lowering | ScalarQueryLoweringTest 5；RelatedQueryParametersTest 2；QuerySliceLoweringTest 16；RelationSliceLoweringTest 13；OptionalValueLoweringTest 2 |
| Generator | GeneratorScalarQueryContractTest 2；GeneratorQuerySliceContractTest 8；GeneratorRelationSliceContractTest 6；GeneratorOptionalValueContractTest 13；GeneratorEntityArtifactInvarianceTest 4 |
| 应用与文档 | TopicSamplesTest 1；OptionalDeploymentFixtureTest 1；LanguageReferenceExamplesTest 3 |

一次性复现上述定向集合的命令如下（这是复现入口，不表示本轮反复执行了这条完整命令）：

```powershell
$env:MAVEN_OPTS='-Xmx768m'
mvn -B -q -o -pl sir-toolchain-application -am test "-Dtest=ScalarQuerySemanticsTest,ScalarQueryLoweringTest,ScalarQueryEditingTest,GeneratorScalarQueryContractTest,RelatedQueryParametersTest,QuerySliceSemanticsTest,QuerySliceLoweringTest,GeneratorQuerySliceContractTest,RelationSliceSemanticsTest,RelationSliceLoweringTest,GeneratorRelationSliceContractTest,EntityScopeSemanticsTest,SirDeclarationWriterRoundTripTest,StructureViewCoverageTest,TypedReferenceSiteContractTest,SourceEditorTest,OptionalValueSemanticsTest,OptionalValueLoweringTest,GeneratorOptionalValueContractTest,GeneratorEntityArtifactInvarianceTest,ExpressionVariantCoverageTest,AstDeterminismTest,TopicSamplesTest,OptionalDeploymentFixtureTest,LanguageReferenceExamplesTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine=-Xmx512m"
```

证据位于各模块 `target/surefire-reports/TEST-*.xml`，192 是这些所列类的最新结果，不累计 RED 或重跑次数。IR 损坏测试检查单条预算、mapper 方法、主键投影与 LIMIT；语义测试含 Int64 大字面量、错误类型、查询结果前向引用、未知源实体和 item 作用域；参数测试含重复外部值、NOT 和空值；生成测试验证转义计划与实体 Mapper 不变性。代码覆盖率未测量，不声明 80%。AFT 的 Java/JDTLS 初始化退出，静态诊断仍为 PARTIAL，不能当成全绿；以上结果以 Maven 编译与实际测试报告为准。

八题重新纯内存生成并测量：blog 397 行 SIR／4072 行 Java／22 个接口，tasks 296／3209／17；其余不变。`TopicSamplesTest` 对八题执行内存编译、生成与交付规划，不编译生成工程。博客补标签关系与选中标签过滤，任务补成员授权；覆盖表更新为 64／5／15（76%／6%／18%），未声称其它多行业务已完成。

`.github/scripts/deployment-smoke.py` 仅通过 Python AST 语法检查，没有运行。部署 v3 增加以下真实数据库断言，均等待 CI：exists/count 的零／一／多行、optional filter、实体 scope 全部隐藏后为 0／false、账号隔离、相关查询绑定文本注入样本与范围过滤。没有新增数据库列或迁移，仍为 V1..V11。

**NOT_RUN（本机）：**全量门、八题生成工程离线编译、真实 MySQL／HTTP／部署冒烟。**CI：本节记录时尚未核验**；负责人随后授权提交并推送，后续已确认 #137 的全量门失败，具体日志和修复见第 8 节。G7 基线虽已按负责人要求推送且不等待 CI，但未查询它的结果，更不能据此认定本批 CI 通过。Windows 安全读源的既有边界仍未放宽；本轮不重复跑已知受其阻断的部署导出测试。没有 UI 改动，无浏览器验收任务，也未启动项目服务。

<a id="ci-136-137-fix"></a>

## 8. CI #136／#137 失败修复（2026-10-20，本机定向）

本机验证基于 `00e3ce9eef16c4419bb5acaec974606657be633a` 加当时的修复工作区，结果随本提交保存。负责人随后已明确授权提交并推送、继续下一阶段；以下本机结果不是同 SHA CI 通过声明。

**已核验的失败证据：**

- [#136](https://github.com/HoloNova/Software-IR/actions/runs/39417535702)，`33f960b653fb25bd2ed2b9717c44904385ec83b2`：`gates-and-business-it` 成功，`standalone-deployment` 失败。`GET /api/favorites/filter?page=1&size=100` 返回 500；deployment-evidence 的 `container-e633e5057237.log` 第 33–41 行记录 MyBatis 参数 `MPGENVAL2` 的 `Cannot convert class java.util.Optional to SQL type`／`java.io.NotSerializableException: java.util.Optional`。真实 DTO 默认是 Optional.empty，不是 null；旧真值表把 getter 换成 Integer 参数，因此漏掉了这个物理表示差异。
- [#137](https://github.com/HoloNova/Software-IR/actions/runs/39425501836)，`00e3ce9eef16c4419bb5acaec974606657be633a`：`gates-and-business-it` 在应用模块 284 项测试中有 5 项失败，部署被跳过。SampleCorpusTest 1 项、SirValidationApplicationTest 2 项及 ToolchainStageFailureTest 1 项仍要求 `note == input.note` 的关联查询被拒绝，G4 后这个样例已经合法；ProjectVisibilityTest 1 项则缺少 `SCALAR_QUERY_ENTITY` 的跨文件实例。

**修复为何存在。** 保留普通 DTO 的 Optional 容器、容器元素校验和 PATCH 请求存在性协议；不把 DTO 全局改成可空字段。在消费比较操作数、空值判断或关联 SQL 参数时，由 Lowering 为 INPUT 的 Optional 字段写入 `OptionalPayloadValue`，明确把 Optional.empty／null 容器读成 null、有值读成基础值。实体列本来就是可空值，不加这个计划。Generator 只渲染带 null 容器保护的解包；IR Validator 验证来源和类型，并拒绝在这些消费位置丢失解包计划的原始 DTO Optional。写入、返回及 PATCH 原有载体不改变。

`OptionalPayloadRuntimeTest` 编译**真实生成的 Filter DTO**及其值表达式，不替换输入 getter，不加依赖、不启动服务。夹具选择 Optional<Id>，避免为了单 DTO 编译引入校验注解依赖；同时旧 13 项 Integer 真值表改为使用 Optional<Integer> 的真实输入载体。验证默认空容器、显式 null 容器、有值、空实体字段、边界比较，以及直接查询和关联查询绑定参数确实是 null／Long 而不是 Optional。

保留原关联请求样例为 `valid/related-request-values.sir` 并新增成功生成断言；原非法夹具改用目标仍不支持的 `expiresAt < now()` 关联表达式，继续验证语义成功但 Lowering 拒绝的分阶段契约。ProjectVisibilityTest 的原有全角色文件导出测试增加 exists Course／count Student；另增加免导出的内存语义测试，逐个验证二者的绑定及缺少 import 时同位置的 `SIR-SOURCE-VISIBILITY-001`，不将新角色排除出覆盖。

**RED：**真实 DTO 参数测试实际得到 Optional.empty 而非 null；比较表达式编译报 Long 与 Optional<Long> 不能使用 `>=`。应用层复现了旧非法夹具的 3 个内存断言失败，并由新的跨文件测试证实 scalar 引用数为 0 而非 2。初次测试夹具的整数主键字面量类型不匹配属于测试搭建问题，改为文本唯一键加载后才记录上述有效 RED。

**GREEN：**本轮下列 14 类中指定方法的最新 XML 合计 **58 tests / 0 failures / 0 errors / 0 skipped**，不累计重跑次数：

| 测试 | 执行项数 |
| --- | --- |
| OptionalPayloadRuntimeTest | 3 |
| OptionalValueLoweringTest | 2（另含丢失解包及错误载体的 IR 拒绝断言） |
| RelatedQueryParametersTest | 2 |
| GeneratorOptionalValueContractTest | 13 |
| GeneratorScalarQueryContractTest | 2 |
| GeneratorWriteSliceContractTest | 17 |
| GeneratorGuardedUpdateContractTest | 4 |
| GeneratorEntityDtoContractTest | 5 |
| SpringBootLoweringDeterminismTest | 2 |
| ProjectVisibilityTest | 仅 scalarQuerySourcesRequireExplicitImportsWithoutFileExport，1 |
| SampleCorpusTest | 3 |
| SirValidationApplicationTest | 仅 stopAfterActuallySkipsLaterStagesIncludingKnownLoweringFailure、originalDiagnosticCodesMessagesAndPositionsRemainExact，2 |
| OptionalDeploymentFixtureTest | 1 |
| TopicSamplesTest | 1 |

实际最终执行命令（Windows，单构建，Maven JVM 768m／测试 fork 512m）：

```powershell
$env:MAVEN_OPTS = '-Xmx768m'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=OptionalPayloadRuntimeTest,OptionalValueLoweringTest,RelatedQueryParametersTest,GeneratorOptionalValueContractTest,GeneratorScalarQueryContractTest,GeneratorWriteSliceContractTest,GeneratorGuardedUpdateContractTest,GeneratorEntityDtoContractTest,SpringBootLoweringDeterminismTest,ProjectVisibilityTest#scalarQuerySourcesRequireExplicitImportsWithoutFileExport,SampleCorpusTest,SirValidationApplicationTest#stopAfterActuallySkipsLaterStagesIncludingKnownLoweringFailure+originalDiagnosticCodesMessagesAndPositionsRemainExact,OptionalDeploymentFixtureTest,TopicSamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m'
```

本机可见的非法夹具仍精确在 LOWERING 报 `SIR-LOWER-FEATURE-001: this target cannot express the existence condition: NowExpression`，语义阶段可以提前停止并成功。报告位于各模块 `target/surefire-reports/TEST-*.xml`。AFT Java/JDTLS 初始化仍失败，诊断为 PARTIAL；以实际 Maven 编译和上述测试为准。代码覆盖率未测量，不声明 80%。

**NOT_RUN：**本修复的全量门、八题生成工程离线编译、原 ProjectVisibilityTest 文件导出集成方法、ToolchainStageFailureTest 文件读写边界、真实 MySQL／HTTP 与同源码 CI。Windows 安全读源限制不变，不重复运行已知被阻断的测试；这些测试未删除、未加跳过条件，待 Linux CI 验收。既有部署 v3 HTTP 断言保留，不变更迁移链。未启动项目进程，无 UI 改动或浏览器测试。

<a id="g9-crud-local"></a>

## 9. G9 Crud 本机定向结果与 CI #138 后续（2026-10-21）

**源码范围：**本批验证采集于 `ee6ddeb458aa6ad572a97dccefb58af2bde50131` 之上的工作区；负责人随后授权阶段性成果直接提交，本机结果不代表同源码 CI。负责人确认设计 12 第 19 节后，实现 text／names 参数、字段集合选择、正式 `FEATURE_ARGUMENT` binding、精确改名及默认引用显式化，并组合 Crud 的角色写权限、系统字段输入／赋值排除、公开读／本人写、多段路径。library 和 market 已采用；八题需求数仍为 64／5／15，不提前上调。

**为何这样实现：**权限仍是普通 actor／validate／owner 检查，排除仍是普通字段选择；不在 Lowering／Generator 引入 Crud 分支。参数类型与来源贯通 Parser／Resolve／Normalize／SourceEditor，避免把列表塞进字符串、依同名文本改错实体字段或枚举。字段排除不是并发锁，普通非版本持久化没有改成部分列原子更新。

**CI #138：**[run](https://github.com/HoloNova/Software-IR/actions/runs/39516345752)，SHA `ee6ddeb458aa6ad572a97dccefb58af2bde50131`。`gates-and-business-it` success，`standalone-deployment` failure。产物为 `conformance-evidence`、`surefire-reports`、`deployment-evidence`、`backend-source-delivery` 和 `standalone-cli`。部署已通过先前失败的 Optional 筛选，随后 `POST /api/favorites/raise` 实际 200、脚本期待 201 而停止；该接口更新已有行且返回 Unit，不创建记录。本轮只把这两处冒烟期待修正为 200，并在 OptionalDeploymentFixtureTest 对照真实生成的控制器验证 Raise 不标 CREATED、FavoriteNote 创建仍标 CREATED。没有为断言修改生产 HTTP 契约；后续断言仍需重新部署验证，不能将 #138 或本批记为 CI 通过。

**RED→GREEN：**最初新参数无法解析；精确名字列表、角色字段和默认字段改名按真实源回写检验。最终包括非法组合、重复／未知列表元素的精确位置、两个实体的同名字段与两个枚举的同名成员、路径不被改名、作者自定义 feature、文本／列表的 add／update／回读、旧 Crud 产物与手写展开等价。旧测试的 LF 字符串替换假设在夹具入口统一换行，另保留真正 CRLF 源编辑的精确文本回归；不放宽文件安全读源。

以下 13 类指定方法的最新 XML 合计 **89 tests / 0 failures / 0 errors / 0 skipped**，不累计重跑次数：

| 测试 | 项数 |
| --- | --- |
| FeatureGrammarTest | 5 |
| TypedReferenceSiteContractTest | 18 |
| DraftEditTest／SourceEditorTest | 8／16 |
| CrudAccessContractTest | 12 |
| CrudFeatureTest／FeatureUseTest／UserFeatureTest | 5／5／5 |
| RenameTest | 8 |
| ProjectVisibilityTest | 仅 featureArgumentsRequireTheirOwnersImportWithoutFileExport、scalarQuerySourcesRequireExplicitImportsWithoutFileExport，2 |
| OptionalDeploymentFixtureTest／TopicSamplesTest | 1／1 |
| LanguageReferenceExamplesTest | 3 |

实际命令（Windows、Java 21.0.11／Maven 3.9.14，单构建，Maven JVM 512m／测试 fork 384m）：

```powershell
$env:MAVEN_OPTS = '-Xmx512m'
mvn -B -o -pl sir-toolchain-application -am '-Dtest=CrudAccessContractTest,CrudFeatureTest,FeatureGrammarTest,FeatureUseTest,UserFeatureTest,ProjectVisibilityTest#featureArgumentsRequireTheirOwnersImportWithoutFileExport+scalarQuerySourcesRequireExplicitImportsWithoutFileExport,TypedReferenceSiteContractTest,OptionalDeploymentFixtureTest,TopicSamplesTest,SourceEditorTest,DraftEditTest,RenameTest,LanguageReferenceExamplesTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
# 随后补充错误列表元素位置断言，只重跑该受影响类（12 项）；其余报告不重跑。
mvn -B -o -pl sir-toolchain-application -am '-Dtest=CrudAccessContractTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
```

报告在各模块 `target/surefire-reports/TEST-*.xml`，并封存到根目录 `target/g9-verification/`（含本批文件 SHA-256 清单与 local-result.json）。AFT Java/JDTLS 初始化失败，诊断 PARTIAL，以实际 Maven 编译与测试为准；未测覆盖率，不声明 80%。一次扩大回归中，原 ProjectVisibilityTest 的 4 个物理文件导出方法仍被 Windows 的 `SIR-APP-SOURCES-READ-001` 阻断；不作为产品回归失败或成功替代证据。这些方法保留在 Linux CI，纯语义正反例另行通过。

**CI 待验收：**新增 CrudAccessBusinessConformanceIT 复用现有 BusinessSliceHarness，verify.yml 业务 IT 已加 `kcg.crud-conformance.enabled=true`，按现有单构建顺序执行，不在本机启动。覆盖真实认证的 401／403、角色先于行加载、三个写操作、字段夹带不改库、公开读仍应用 scope、角色不能越过 owner、Version PATCH 与冲突，并独立 JDBC 查库与既有进程／schema 清理、证据报告。此 IT、八题生成工程离线编译、全量门、完整部署及本批同源码 CI 均 **NOT_RUN**；基线 #138 的通过不能外推到本批。未启动应用或容器，无 UI 改动，不需浏览器／UI 测试；负责人已授权阶段性成果直接提交，本次未推送；同源码 CI 仍待验收。

<a id="ci-139-fixture-fix"></a>
## 10. CI #139：G9 真库测试请求契约修复

失败源码 `f82cb94d01be6754ce56e37ff1bbc835ccf433f9`；[CI #139](https://github.com/HoloNova/Software-IR/actions/runs/39525420836)。`Frozen full gate` 与 `Completion full gate` 均通过；8 个业务 IT 中新增的 `CrudAccessBusinessConformanceIT` 失败，其余 7 个通过。后续独立验证、CLI 和 standalone deployment 被跳过，不得据此确认 #138 的部署修复已验收。该次 run 已上传 `conformance-evidence`、`surefire-reports`，G9 报告为 35 个检查通过、8 个失败。

**根因及 RED。** 真实生成工程编译通过。现有目标在进入工作流之前拒绝未知 JSON 属性：管理员创建请求夹带已排除的 `stock`，实际返回 `400 INVALID_REQUEST`，测试却期待 201，继而使用空 id 测试更新／删除；两项权限检查夹带已排除的 `changes.state`，实际返回 400，尚未进入应返回 403／404 的授权路径。owner 的干净 PATCH 已在该 run 返回 200，版本仅递增一次，陈旧版本返回 409。上述 CI HTTP／数据库日志是本次 RED，不把 Windows 环境跳过的 IT 当作复现通过。

**修复范围。** 仅调整 IT：正常创建／更新使用合法 body；单列夹带 `stock` 的 create／update 及夹带 `state` 的 PATCH，按既有严格解码协议期待 400，并检查数据库整行保持不变；匿名／错角色／非 owner 检查使用合法 PATCH，避免请求格式错误遮住授权检查。所有正常写入仍要求原成功状态；没有放宽为“任意 2xx／4xx”，没有修改生产解码、权限、默认值或事务规则。

**本机检查。**

```powershell
$env:MAVEN_OPTS='-Xmx512m'
mvn -B -o -pl sir-toolchain-application -am '-Dtest=CrudAccessContractTest,OptionalDeploymentFixtureTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
```

结果：13 项定向测试通过（12 + 1，0 failure/error/skip）；修改后的 IT 随模块测试源码编译通过。AFT Java/JDTLS 初始化失败，静态诊断为未知，以本次 javac 编译为准。不把这 13 项内存／生成契约测试称为真实 HTTP／MySQL GREEN。

**仍待 CI。** 修复源码的 `CrudAccessBusinessConformanceIT`、双全量门、独立验证和部署门均待推送后执行。本机未启动 MySQL、生成应用或执行业务 IT；不更新八题完成数。后续完整结果必须绑定修复 commit SHA 与对应 run。


<a id="ci-140-accepted"></a>
## 11. CI #140：G9 与历史 CI 修复闭环

源码 `484a8a792c3ec6a7b9f5a7b7171b009e981a8ce3`；[CI #140](https://github.com/HoloNova/Software-IR/actions/runs/39527408701) 已 completed / success。`gates-and-business-it` 与 `standalone-deployment` 均通过，实际步骤包括双全量门、业务 IT、独立验证、CLI，以及冷部署、HTTP 与持久化验收。前述 #136～#139 的已知阻塞至此闭环；不能用该 SHA 的结果验收后续 G10。

已核对产物索引：`conformance-evidence`（12314706243）、`surefire-reports`（12314751145）、`deployment-evidence`（12315206741）、`backend-source-delivery`（12314847105）、`standalone-cli`（12314716218）。本次没有重新下载报告计数，不据 job 成功推算新的需求覆盖数量。

<a id="g10-money-local"></a>
## 12. G10：精确金额、写入校验与 DECIMAL 交付（初次实现定向）

后续 `a54c351` 的 #141 在架构门和 DTO 旧契约失败，当前修复及未验收范围见[第 13 节](#ci-141-fix)；本节保留初次实现时的执行证据。

负责人已确认默认 `precision(19,2)`、允许同名规则覆写、接受无损尾随零且拒绝隐式舍入。[设计 12 第 20 节](../design/12-feature-semantics-and-semantic-types.md#g10-money-proposal)记录原因与边界；[语言参考](../SIR_LANGUAGE_REFERENCE.md#2-类型)是作者侧规则。Money 不隐含非负、币种或汇率，旧整数分不自动换算，现存 precision／scale 变化不自动迁移。

**实现。** Core 验证规则参数、适用类型、覆盖和默认值；Lowering 形成正式 DecimalShape／候选与相对写计划。生成 DTO 与候选校验共用精确判定，Optional 的限定类名注解可编译；普通更新也检查最终目标字段，BigDecimal 取负正确生成。PATCH 的继承 precision 来自实体，其他继承业务约束和显式 PATCH precision 仍拒绝，不改变旧 null／版本协议。语义类型仍全项目可见，实体仍需 import。

DECIMAL 合同行是原 11 列加 precision／scale 两列，非金额合同不变；物理上限先检查，再由列计划验证并规范默认值，避免极端 scale 展开大字符串。现场精度、标度、默认值需核验。原子金额保持 SQL delta 与原守卫、加载行、非 version 限制；检查 delta scale 与同一 Spring 事务绑定的 MyBatis 连接严格模式，最终溢出由数据库拒绝并回滚，不改写为 guard 错误，不 SET GLOBAL。普通非版本持久化的全行／NULL 策略未重新设计。

**RED→GREEN。** 新精度语义／默认值及源往返先补失败测试；真实生成 DTO／服务发现并修复 Optional<BigDecimal> 类型注解位置，金额取负和较宽输入写窄字段均执行验证。完整夹具发现 Money 默认 precision 与旧 PATCH 约束禁令的冲突，补正反例后仅跳过继承 precision，保留 Quantity 等旧反例。最后直接构造列计划的非精确默认值测试先 RED，再把规范化收口到列计划；十亿位精度带默认值现在先按物理上限拒绝，未在旧危险路径上做大分配复现。PATCH 运行代理补齐既有 selectByIdForUpdate／updateIfVersionMatches，不改生产锁或版本协议。

最新 XML 封存到本机 `target/g10-verification/`，15 类合计 **105 tests / 0 failures / 0 errors / 0 skipped**，不累计重跑次数：

| 测试 | 项数 |
| --- | --- |
| MoneyPrecisionSemanticsTest | 24 |
| AtomicCounterSemanticsTest／SemanticTypeSemanticsTest | 8／10 |
| SirDeclarationWriterRoundTripTest／WriteSliceSemanticsTest | 2／29 |
| DeploymentPlannerTest／SpringBootLoweredIrValidatorTest | 6／1 |
| SchemaEvolutionPlannerTest | 最新仅新增两个金额迁移方法，2 |
| MoneyRuntimeTest | 7 |
| MoneyDeliveryTest／MoneyAtomicContractTest | 5／1 |
| GeneratorAtomicCounterContractTest | 3 |
| MoneyToolchainContractTest | 3 |
| TopicSamplesTest／LanguageReferenceExamplesTest | 1／3 |

早前 SchemaEvolutionPlannerTest 的 15 个既有方法已定向通过，最终最新 XML 只保留两项新方法，不再加 15。真实运行时覆盖校验器、DTO、Optional、PATCH required-null／清空、普通计算越界拒写、取负及事务连接；数据库 mapper／JDBC 使用代理，**不把它称为真实 MySQL**。八题通过内存编译／生成／交付规划，market／shop／ledger 已改 Money；规模由 TopicSamplesTest 输出，不构建生成工程。

执行记录节选（Windows、Java 21、Maven 离线单构建；Maven 堆 512m，测试 fork 384m）：

```powershell
$env:MAVEN_OPTS='-Xmx512m'
mvn -B -o -pl sir-generator-spring-boot -am '-Dtest=MoneyRuntimeTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
mvn -B -o -pl sir-toolchain-application -am '-Dtest=DeploymentPlannerTest,MoneyDeliveryTest,SchemaEvolutionPlannerTest#decimalPrecisionAndScaleChangesAreNeverAutomaticWidenings+aNewRequiredDecimalColumnStillNeedsAnExactDefault,TopicSamplesTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
```

AFT Java/JDTLS 初始化退出，诊断 PARTIAL／unknown，以实际 javac 与定向执行为准；未测覆盖率，不声明 80%。Python 冒烟脚本只做语法检查；差异与新文件均自审，不使用浏览器，无 UI 改动。本机未启动 MySQL、生成应用或容器。

**已接入但尚未验收。** `MoneyBusinessConformanceIT` 复用 BusinessSliceHarness，接入 verify.yml 的串行业务 IT 与 `kcg.money-conformance.enabled=true`。覆盖真实 JSON 尾随零／指数／精度错误、必填 null、PATCH absent／null／版本冲突、原子金额并发、guard 不成立、溢出回滚，以及非严格池连接拒写；由独立 JDBC 核对行和值。部署 v3 增加金额和默认值，不增加迁移版本；独立部署烟测核对 DECIMAL precision／scale／默认值，并篡改精度确认公开 start 拒绝，再恢复，沿用既有清理与脱敏证据。

本批真实 MySQL／HTTP、并发／非严格模式 IT、八题生成工程 Maven 编译、双全量门及完整部署均 **NOT_RUN**，留给本批 commit + push 后的 CI。必须以该 SHA、run URL 和上传的 conformance-evidence／surefire-reports／deployment-evidence 为准，不能沿用 #140。阶段提交按负责人授权直接推送；84 条需求账本仍为 64 完整／5 折扣／15 尚未写出，不将新原语直接换算为需求通过。


<a id="ci-141-fix"></a>
## 13. CI #141：精度辅助类型归属与 DTO 契约回归修复

本修复已推送 `303a139`；#142 双全量门通过，后续金额 IT 的环境绑定失败与修复见[第 14 节](#ci-142-session-fix)。本节保留该次修复的本机证据。

失败提交：`a54c351fec7ca5770635f698195689218d84e79d`，[verify #141](https://github.com/HoloNova/Software-IR/actions/runs/39557106745)。冻结门在 semantic 模块停止，完成门、业务 IT、独立验证和部署被跳过。另查允许失败的预热门及上传的 Surefire XML，共确认两个失败用例；不能只看 `--log-failed` 而漏掉预热门中的后续模块。

- `SoftwareSchemaCompletenessTest.everyRecordInTheModelPackagesIsReachableFromTheRoot`：新增 `semantic.type.DecimalPrecision` 是计算精度规则的辅助 record，不属于模型根图，却进入了模型包扫描。将其移到已有 `semantic.feature` 包，更新全部调用；精度事实仍留在 `NormalizedConstraint`，不改变模型、算法，不给架构门加豁免。
- `GeneratorEntityDtoContractTest.dtoRendersEveryValidationConstraintInLoweredOrderOnItsOwningField`：旧断言要求 `@DecimalMax` 后立即接字段，遗漏 G10 的必填 `@NotNull`。现同时核对降级约束序列、完整 `@ExactDecimal`／min／max／`@NotNull` 注解块、导入及各注解仅出现一次；生成器与生产校验不变。

**RED→GREEN。** 本机先运行原两个测试类，13 项中恰有上述两项断言失败、0 errors，XML 封存 `target/ci-141-fix/red/`。RED 临时使用 `maven.test.failure.ignore=true`，让依赖模块失败后仍执行 DTO 测试，按 XML 判断失败而不是把 Maven 的成功退出误认为通过；不修改任何 POM 或 CI 设置。移动后只清除旧包下那一个遗留的 `target/classes/.../DecimalPrecision.class`，避免增量构建扫描到过期类，CI 的 clean 构建不需要此步骤。

随后取消 failure-ignore，七类最新报告共 **65 tests / 0 failures / 0 errors / 0 skipped**，封存到 `target/ci-141-fix/green/`，汇总 `target/ci-141-fix/local-result.json`：

| 测试 | 项数 |
| --- | --- |
| SoftwareSchemaCompletenessTest | 8 |
| MoneyPrecisionSemanticsTest | 24 |
| SemanticTypeSemanticsTest | 10 |
| DeploymentPlannerTest | 6 |
| GeneratorEntityDtoContractTest | 5 |
| MoneyDeliveryTest | 5 |
| MoneyRuntimeTest | 7 |

实际命令（Windows、Java 21、离线单构建，不用 `-T`）：

```powershell
$env:MAVEN_OPTS='-Xmx512m'
mvn -B -o -pl sir-generator-spring-boot -am '-Dtest=SoftwareSchemaCompletenessTest,GeneratorEntityDtoContractTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dmaven.test.failure.ignore=true' '-DargLine=-Xmx384m' test
mvn -B -o -pl sir-generator-spring-boot -am '-Dtest=SoftwareSchemaCompletenessTest,GeneratorEntityDtoContractTest,MoneyPrecisionSemanticsTest,SemanticTypeSemanticsTest,DeploymentPlannerTest,MoneyDeliveryTest,MoneyRuntimeTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
```

此前第 12 节的 105 项没有选中上述两个既有契约，属于回归选择遗漏；不把旧数量与本批数量累加。Java/JDTLS 初始化仍失败，AFT 为 PARTIAL，实际 javac 与定向执行通过；覆盖率未测，不声明 80%。本批无 UI 改动，不启动应用、MySQL 或容器。差异自审与 `git diff --check` 通过；全量门、金额真库／HTTP、并发／非严格模式及部署仍需修复提交的同 SHA CI，不能用 #140 或本机结果代替。按负责人授权，本修复阶段完成后 commit + push 触发新 CI。


<a id="ci-142-session-fix"></a>
## 14. CI #142：非严格模式验收的环境绑定修复

后续 `1d13c54` 的 #143 已通过双全量门与完整 Money IT（57 检查通过）；独立部署另有夹具生命周期失败，见[第 15 节](#ci-143-drift-lifecycle-fix)。

失败提交：`303a13943bde22b00733099d7c5d20ef326fdf5c`，[verify #142](https://github.com/HoloNova/Software-IR/actions/runs/39581448890)。冻结／完成双全量门均 success；业务 IT 共 9 项、1 failure、0 errors、0 skipped，唯一失败是 `MoneyBusinessConformanceIT.moneyAgainstRealMysql`。独立验证、CLI 和部署被跳过，不算通过。正式报告产物为 `surefire-reports`（12338831530）与 `conformance-evidence`（12338536666），本机下载副本在 `target/g10-ci-142-reports/`、`target/g10-ci-142-evidence/`。

Money 报告已有 **53 checks passed / 1 failed**：真实 DTO／Optional、PATCH null／版本、并发精确增量、最终溢出与事务回滚均执行通过。失败是切到非严格 SQL 模式之后重启应用未就绪；第二轮构建成功，但启动输出显示：

```text
Property: spring.datasource.hikari.connection-init-sql
Origin: System Environment Property "SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL"
Reason: java.lang.IllegalStateException: The configuration of the pool is sealed once started.
```

**原因与修复。** [Spring Boot 环境变量协议](https://github.com/spring-projects/spring-boot/blob/v3.5.3/spring-boot-project/spring-boot-docs/src/docs/antora/modules/reference/pages/features/external-config.adoc)要求点换下划线、删去连字符并大写。旧键把 `connection-init-sql` 错写为 `CONNECTION_INIT_SQL`，使 Binder 尝试遍历 `connection.init.sql`，访问懒连接属性。改为 `SPRING_DATASOURCE_HIKARI_CONNECTIONINITSQL`，仍在每条真实 Hikari 连接创建后执行固定的 `SET SESSION sql_mode=''`，不 SET GLOBAL，不使用探针连接替代写事务连接。实际环境映射提成不可变 map，普通轮次不加任何覆盖；IT 报告同步真实键名。金额生产代码、严格模式拒写和回滚断言均未改，非严格验收仍完整保留。

**本机 RED→GREEN。** `MoneySessionConfigurationTest` 使用与目标相同的 Spring Boot 3.5.3 Binder 和 `SystemEnvironmentPropertySource`，直接消费 harness 的实际 map。3 个用例验证初始化 SQL 正确绑定且不访问连接、普通轮次配置不变、错误键确实触发懒连接 getter。getter 是抛错的测试探针，不建立真实池或数据库。新增的 Boot core 仅为 test-scope，不进入产品运行时或生成项目依赖。

首次探针把属性源命名为自定义名字，Boot 未选择 OS 环境映射，控制反例失败；这次运行不是有效 RED。核对 v3.5.3 官方 `SpringConfigurationPropertySource.isSystemEnvironmentPropertySource` 后，改用实际标准名 `systemEnvironment`，原 harness 映射产生 **3 tests / 1 failure / 0 errors**，失败点为读取 `spring.datasource.hikari.connection`。修正键名后，两类最新 XML 合计 **5 tests / 0 failures / 0 errors / 0 skipped**：配置回归 3 项，`ConformanceJvmLimitsTest` 2 项。有效 RED 与 GREEN 报告封存在 `target/ci-142-fix/{red,green}/`，不累加重复运行。

```powershell
$env:MAVEN_OPTS='-Xmx512m'
mvn -B -o -pl sir-toolchain-application -am '-Dtest=MoneySessionConfigurationTest,ConformanceJvmLimitsTest' '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx384m' test
```

AFT Java/JDTLS 仍初始化失败，诊断 PARTIAL，以实际 javac 与定向结果为准；覆盖率未测，不声明 80%。本机未启动应用、MySQL、连接池或容器，不运行全量门；无 UI 改动。差异自审后按授权提交推送本修复，G6 设计草案不混入。本修复提交的真实非严格连接拒写／回滚、完整 Money IT、独立验证和部署仍待同 SHA CI；#142 已通过的部分不代表整条金额验收闭环，也不提前上调八题计数。

<a id="ci-143-drift-lifecycle-fix"></a>
## 15. CI #143：部署精度漂移夹具的生命周期修复

失败 SHA `1d13c5495434753a0284d372224fd51ee51e2747`，[verify #143](https://github.com/HoloNova/Software-IR/actions/runs/39588013132)。`gates-and-business-it` 全部通过：双全量门、业务 IT、独立验证和 standalone CLI 均 success。已下载 `conformance-evidence` 并核对 `money-1a15e273225-48156/money-report.txt`，**57 checks passed / 0 failed**，包括每条应用连接的非严格模式覆盖、实际写入拒绝和同事务其他行回滚，前一项 Binder 修复已得到真库验证。不能将这一部分成功写成整条 CI 成功。

**新的失败及直接证据。** `standalone-deployment` 的基础部署、HTTP、持久化、权限、契约、业务和更新已通过；`dataUpdate` 失败，`decimalShape`、`freshChain` 尚未完成。`deployment-evidence/64-process.log` 显示应用与数据库均被停止；`65-process.log` 为 `invalid container name or ID: value is empty`。`deployment-evidence/report.json` 的堆栈指向漂移测试第一次 ALTER TABLE。该夹具先调用公开 stop（按产品协议停 app 和 db），再用只查询运行容器的 docker ps 查数据库，因此获得空 ID。此前产品导出的金额 precision／scale／默认值查询已执行，但漂移拒绝与恢复尚不能记为通过。本机证据副本在 `target/ci-143-analysis/`。

**修复边界。** 仅在 CI 夹具增加按 Compose 项目与 app 服务标签定位的 `stop_application`，验证恰有一个运行应用容器，再停掉它，保留数据库供受控漂移 SQL 使用。仍通过公开 start 验证两次精度／标度拒绝并在恢复后重启，未删除断言、放宽预期退出码或使用替代 schema。产品 stop 继续停两个服务，金额、事务、部署和漂移校验的生产逻辑均未改变。没有混入正在实现的 G6 工作区代码。

**本机 RED→GREEN。** `test_deployment_smoke.py` 从真实脚本提取 SQL 辅助函数和漂移语句段，在生命周期模型上执行，不复制一段改好后的流程作为测试。旧段调用 stop 后数据库关闭，第一条 SQL 恰因空容器 ID 失败；修复后验证只停写应用、四次 ALTER、两次 start 以 3 拒绝、恢复后 start 以 0 成功，以及应用容器缺失／多实例时明确失败且不执行 SQL。两项 unittest 方法通过（含缺失／多实例两个子例），Python 语法及差异检查通过。回归已加入部署 job，在真实部署之前执行。

```powershell
python -m unittest discover -s .github/scripts -p test_deployment_smoke.py -v
python -m py_compile .github/scripts/deployment-smoke.py .github/scripts/test_deployment_smoke.py
```

生命周期模型不是实际 Docker／MySQL GREEN；本机不启动服务，不重跑全量 Java 构建。AFT 可分析这三个文件，但仍报原有 `expected=None` 的两处 Pyright 推断错误，本次未改其运行协议；以 Python 语法检查和实际 unittest 为定向证据，覆盖率未测。独立部署漂移拒绝／恢复与新实例完整迁移链仍待修复提交的同 SHA CI；此前已经通过的门也不直接外推到修复 SHA。无 UI 改动，八题完成数不变。本次按阶段授权单独 commit + push 触发 CI。


<a id="ci-144-dependency-fix"></a>
## 16. CI #144：合同测试运行器依赖预热缺口

失败 SHA `024be760bb9c90db9e170178efa2af394a9e9732`，[verify #144](https://github.com/HoloNova/Software-IR/actions/runs/39593858819)。双全量门、业务 IT、独立验证与 CLI 均 success；独立部署在初次 `contract-test` 以 5 失败，尚未进入金额精度漂移复验。下载的 `deployment-evidence/07-process.log` 显示 `surefire-junit-platform:3.5.3` 的 POM 尚未缓存，向 Maven Central 下载时报 `Network is unreachable`，并非合同断言、金额运算或数据库失败。副本保存在 `target/ci-investigation/144/`。

构建阶段原来使用 `maven.test.skip=true`，没有解析 Surefire 动态选择的 provider／launcher。合同容器接入 Compose 网络，不是模板显式 `--network=none`；本轮访问依赖仓库不可达，不能推广成所有测试容器必定禁网。修复在原本允许下载依赖的构建阶段显式选择生成的 HttpContractIT，使用 JUnit 5.12.2 官方 `junit.platform.execution.dryRun.enabled=true`，只发现测试并预热运行器，不执行生命周期或 HTTP 请求。真正的合同测试显式关闭 dry-run，并使用 `mvn -o` 和同一个 `/m2`，不把临时下载作为验收前提，也不将 dry-run 的跳过计为通过。

已核对 JUnit 官方 r5.12.2 的 [LauncherConstants](https://github.com/junit-team/junit-framework/blob/r5.12.2/junit-platform-launcher/src/main/java/org/junit/platform/launcher/LauncherConstants.java) 和 [EngineExecutionOrchestrator](https://github.com/junit-team/junit-framework/blob/r5.12.2/junit-platform-launcher/src/main/java/org/junit/platform/launcher/core/EngineExecutionOrchestrator.java)，固定源码 blob 分别为 `a2af5d187a581591398b74dbe334765cfb58e2a8`、`aedf22950cc1a25473193e63104c8ef7748301e4`。外部 acceptance 是普通 Maven 工程，不能假设它使用 JUnit 5；未在构建时运行它、未修改其现有执行命令及确认一次性实例协议。

本机最终 **8 项通过、0 失败／错误／跳过**：DeploymentDependencyPrimingTest 3 项验证生成合同运行器的预热、外部验收不成为构建副作用、真正合同的离线与显式关闭 dry-run；MoneyDeliveryTest 5 项回归精度、默认值与迁移限制。预热缺失和离线合同断言已先取得 RED。另从实际编译后的模板导出 build／contract／acceptance 三份脚本，`sh -n` 均通过。没有启动 Docker、MySQL、应用，也没有构建生成工程。AFT 的 Java LSP 初始化失败，诊断为 PARTIAL；以实际 Maven 编译与定向结果为依据，不宣称 LSP 已干净。

```powershell
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=DeploymentDependencyPrimingTest,MoneyDeliveryTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

Maven 本身限制 384 MiB／2 个可见处理器；测试 fork 限制 512 MiB／2 个可见处理器。定向在含未提交 G6 的工作区执行；本修复单独提交，不把本机结果当成独立修复 SHA 的完整证据。真实依赖预热、离线合同与部署漂移闭环均等待后续同 SHA CI，未提前验收 G10。


<a id="g6-multiplication-local"></a>
## 17. G6：精确乘法、算术输入计划与按数量购买（本机定向）

按负责人“继续下一阶段”的指令实施[设计第 21 节](../design/12-feature-semantics-and-semantic-types.md#g6-multiplication-proposal)。Parser／AST 增加独立乘法优先级；整数用 Math.multiplyExact、Decimal 用无 MathContext 的 multiply，整数到 Decimal 是显式目标节点。既有加减、比较及整数宽度、Optional 拒绝、查询列／any 算术拒绝与原子写边界不放宽。Generator 只消费有来源的转换及能力级必需值计划；分页仍可缺省，不全局给共享 DTO 数字加 NotNull，PATCH changes 保留 absent／null。`input.ref.id` 的必需值检查按实际 DTO 属性 `refId` 报错，不拿 SIR 字段名冒充 HTTP 字段。

**RED→GREEN。** 新语法、类型和声明往返先失败再贯通；真实生成 DTO／服务先复现缺失整数的拆箱 NPE，再验证结构化 INVALID_REQUEST／NotNull。畸形转换和缺失、重复、乱序、错误 owner／属性／路径计划均由独立 IR Validator 拒绝。Ref.id 边界也先复现 SIR-LOWER-IR-001，再验证正确字段错误与合法整数值。SourceEditor 验证 add／update／rename、回读的表达式树和绑定保持；没有只对生成字符串做“看起来正确”的判断。

28 类各自最新 Surefire XML 去重合计 **223 tests，0 failures／errors／skips**。主批原为 221 项，最后为 Ref.id 增加 2 项并定向复测受影响类；不把重复执行累加。

| 范围 | 类数 | 最新报告 tests |
| --- | ---: | ---: |
| 语义、整数宽度、G7／G10、Schema、结构／引用与声明往返 | 9 | 104 |
| 乘法降级、原子计数、既有算术与独立 IR 硬化 | 5 | 40 |
| 源编辑与旧编辑回归 | 2 | 18 |
| 真实生成 DTO／服务、分页／路径、Optional、金额与乘法 delta | 9 | 54 |
| 工具链合同、八题内存生成／交付规划、全部语言参考示例 | 3 | 7 |
| 合计 | 28 | 223 |

真实 Java 通过 javac 编译全部生成的 .java 文件，以正式目标依赖运行，不起 Spring Boot 或数据库。覆盖优先级／结合树、左右混合、Int32／Int64 溢出、负数／零、尾随零、只对最终值检查精度、缺失／null、短路分支、纯分页与共享输入、路径注入和 PATCH 边界。独立乘法 delta 仍生成 setDecrBy 并保留严格事务连接和目标 scale 检查，没有增加 SET column = column * ?。

shop 现在让同一 quantity 进入库存守卫、原子扣减、订单总额和明细；库存先落库，订单 persist 生成主键后再创建明细，修正旧样例提前捕获 null 外键的问题，不改通用引用语义。新增 MultiplicationBusinessConformanceIT 已编译并接入 verify.yml，覆盖非法数量不写库、精确总额、库存 SQL 之后的精度失败回滚、12 请求按总购买数量防超卖、明细关联与金额一致。**本机未执行该 IT**，也未执行任何全量门、MySQL／HTTP、生成工程 Maven 构建或部署；这些均待同 SHA CI。

八题新规模由 TopicSamplesTest 纯内存重新测量；只有 shop 金额一条 ✗ 转为“已写出待真库”的 △，总账为 **64／6／14**，没有增加完整条数。library 罚款仍缺日期差；多商品、购物车、取消退库存、循环、聚合和 G11 都未扩范围。无 UI 修改、无浏览器验收。

本机证据与源码 SHA256 在 `target/g6-verification/`；最新 XML 含更新时间，31 个生产／测试／夹具／workflow 文件已封存哈希。AFT Java LSP 初始化失败，诊断 PARTIAL，不当作干净的编译证据；实际 Maven 编译和上述定向通过。未测覆盖率，不声称达到某个比例。Maven JVM 限制 384 MiB／2 个可见处理器，测试 fork 512 MiB／2 个可见处理器，串行执行。

```powershell
$tests = 'MultiplicationSemanticsTest,ArithmeticSemanticsTest,IntegerWidthSemanticsTest,MoneyPrecisionSemanticsTest,OptionalValueSemanticsTest,SoftwareSchemaCompletenessTest,SirDeclarationWriterRoundTripTest,StructureViewCoverageTest,TypedReferenceSiteContractTest,MultiplicationLoweringTest,ArithmeticLoweringTest,LoweredIrHardeningTest,SpringBootLoweredIrValidatorTest,AtomicCounterLoweringTest,MultiplicationEditingTest,SourceEditorTest,MultiplicationRuntimeTest,ArithmeticInputRuntimeTest,GeneratorArithmeticContractTest,GeneratorEntityDtoContractTest,GeneratorQuerySliceContractTest,GeneratorOptionalValueContractTest,OptionalPayloadRuntimeTest,MoneyRuntimeTest,MoneyAtomicContractTest,MultiplicationToolchainContractTest,TopicSamplesTest,LanguageReferenceExamplesTest'
mvn -B -q -o -pl sir-toolchain-application -am test "-Dtest=$tests" '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2' '-DforkCount=1' '-DreuseForks=true'
```

#144 的合同依赖修复已独立提交并推送 `b8f2cf0`，8 项定向不计入本节 223 项。G6 按阶段授权另行 commit + push，依赖修复的验证也以最终源码 CI 为准，不能将旧全量门或 dry-run 作为本批验收。


<a id="ci-146-drift-protocol-fix"></a>
## 18. CI #146：精度漂移验收误依赖内部异常文本

失败提交 `85bfc8f9e7da3cf25c28e905766601a7c1f4b861`，[verify #146](https://github.com/HoloNova/Software-IR/actions/runs/39611291980)。双全量门、业务 IT、独立验证和 CLI 均 success；下载的 conformance-evidence 中 Money **57 checks passed / 0 failed**，Multiplication **37 checks passed / 0 failed**，含缺省／null 数量拒写、计算后精度失败回滚、整数溢出、12 请求竞争扣数量与明细外键一致性。G6 的通用能力有该 SHA 的真库证据，但不据此宣称整条 CI 成功，八题需求账本本轮不重判。

`deployment-evidence/report.json` 的 deployment／HTTP／persistence／privileges／Swagger／contract／business／update 均 PASSED，故 `b8f2cf0` 的合同运行器预热与离线真实合同已生效。失败在 dataUpdate 的第一条金额漂移断言：`66-process.log` 显示 11 条 Flyway 迁移校验成功，随后以 3 拒绝：

```text
KCG_DB_BLOCKED: DRIFT: the live schema is not the V11 contract; nothing was changed
```

烟测却期待 `decimal precision/scale mismatch`。实际调用链是 `classify -> matches -> verify`：verify 内部检测精度差异并抛异常，matches 将不匹配返回 false，classify 按既有公开协议输出 DRIFT。因此不能把烟测当作产品没有检测漂移，也不能为测试改掉公开的分类协议。旧生命周期模型恰好返回了虚构的内部文字，漏检这个接口失配。证据副本在 `target/ci-investigation/146/`。

**修复仅限验收与测试。** 烟测仍要求 start 以 3 拒绝，并核对公开 `KCG_DB_BLOCKED: DRIFT` 与无修改声明；新增检查确认应用没有运行，information_schema 中被篡改的 precision／scale 保持原样、未被自动修复。仍分别篡改非空带默认值的金额 precision 与可空无默认值的 scale，恢复后再次启动，未删除独立精度断言或放宽拒绝码。生产校验、Money 算术与迁移代码均未改。

**本机 RED→GREEN 和定向。** 将生命周期模型换成 CI 的真实公开输出后，旧脚本在同一断言失败。修复后 Python **5 项通过**（另含子例）：完整漂移／恢复流程；应用缺失或多实例拒绝；无关错误、内部文字或缺少无修改声明不能冒充正确 DRIFT；拒绝后仍启动应用不能通过；自动修复精度不能通过。Python 语法检查通过。

新增 DecimalSchemaVerifierRuntimeTest 从实际导出的 Migrate.java **原样截取** validColumnShape、verify、matches 及其唯一键表声明，用 JDK 编译执行；只模拟 JDBC 返回的 metadata，不重写判断或 SQL，不增加 Flyway 依赖，不启动工具进程。3 项验证精确默认值和可空列的正常快照通过，以及仅 precision 或 scale 改变时的专门拒绝、matches 返回 false。它不覆盖 Flyway 或完整 Migrate.main；公开分类的证据来自上述真实 CI 日志。连同 MoneyDeliveryTest 5 项和 DeploymentDependencyPrimingTest 3 项，Java 最新 XML 共 **11 项通过、0 failures／errors／skips**，本轮合计 **16 项定向**，不累加重复运行。

```powershell
python -m unittest discover -s .github/scripts -p test_deployment_smoke.py -v
python -m py_compile .github/scripts/deployment-smoke.py .github/scripts/test_deployment_smoke.py
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=DecimalSchemaVerifierRuntimeTest,MoneyDeliveryTest,DeploymentDependencyPrimingTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2' '-DforkCount=1'
```

Maven JVM 384 MiB／2 个可见处理器，测试 fork 512 MiB／2 个可见处理器。未跑全量、生成工程 Maven 构建、Docker、MySQL 或 HTTP，未启动应用。AFT Java LSP 初始化失败，诊断 PARTIAL；Python 两处 expected=None 的旧类型推断诊断未改，以实际编译和定向为据。覆盖率未测，无 UI 改动。漂移拒绝后的精度恢复和新实例 V1..V11 重放仍待修复源码 CI，不提前验收 G10 部署。按阶段授权独立提交推送，不与 G11 设计混合。


<a id="ci-147-accepted"></a>
## 19. CI #147：G10／G6 与部署精度漂移验收闭环

受测提交 **`997794c4524ba726c9e864d2826d060093b66a89`**，[verify #147](https://github.com/HoloNova/Software-IR/actions/runs/39700702739)，2026-10-22 完成，两个 job 全部 success。已读取步骤状态并下载 deployment-evidence、conformance-evidence 与 surefire-reports，不仅依据运行总状态。证据本机副本位于 `target/ci-investigation/147/`，权威产物仍为该运行 artifacts。

| 范围 | 同 SHA 实际证据 |
| --- | --- |
| 双全量门 | Frozen full gate、Completion full gate 均 success |
| 业务、独立验证、CLI | Business conformance ITs、Independent verification IT、Standalone CLI smoke 均 success |
| G10 Money | `conformance-evidence/money-1a16118365c-22747/money-report.txt`：57 checks passed、0 failed；实际应用连接非严格模式拒写、PATCH、并发金额与回滚均包含 |
| G6 乘法 | `conformance-evidence/multiplication-1a161193de8-17641/multiplication-report.txt`：37 checks passed、0 failed；精确乘积、非法数量拒写、整数溢出、数量扣库并发、精度失败回滚及明细一致性均包含 |
| 八题生成工程 | `surefire-reports/sir-toolchain-application/target/surefire-reports/` 中 TopicSamplesOfflineCompilationTest：1 test、0 failures/errors/skips；其测试遍历八题，不误写成8个JUnit用例。TopicSamplesTest 同样1项通过 |
| 独立部署 | `deployment-evidence/report.json` 绑定上述 SHA／run URL，deployment、httpSmoke、persistence、privileges、swagger、contract、missingVolume、business、update、dataUpdate、decimalShape、freshChain、cleanup 全部 PASSED |
| 随包网络与独立业务 | 同报告合同13 PASSED／0 FAILED／7 NOT_VERIFIED；双Token独立业务26 PASSED／0 FAILED。NOT_VERIFIED 不计入通过 |

#141／#142 的模型包、DTO断言、连接环境绑定修复，以及 #143／#144／#146 的生命周期、合同依赖预热和漂移公开协议修复，均由该 SHA 的完整执行闭环。这里验收的是已有受控场景：不扩称任意 MySQL 环境、任意金额迁移或生产就绪。部署报告仍明确排除 interruptedMigration 与 schemaDriftOtherThanDecimalShape；Money 不自动迁移旧整数分或已有精度，G6 不自动升级整数宽度、不支持 Optional 算术、SQL列乘法或原子列乘法。

本轮只将八题账本中 shop 的“金额＝单价×数量”由待验收 △ 改为 ✓：参考样例已经使用该表达式、八题生成工程构建通过，独立真库场景验证同一金额／数量／库存及明细组合。其余折扣项和未写出项不自动升级。总账现为 **84条：65完整（77%）、5带折扣（6%）、14未写出（17%）**；多商品、退库存、逾期天数、聚合与计算视图仍未因此实现。

没有重跑本机构建、测试或服务。纯文档收尾只做差异与链接检查；该验收基线不覆盖后续 G11 的新源码。下一阶段先形成计算视图方案，核心实现待负责人介入确认。


<a id="g11-computed-view-local"></a>
## 20. G11：来源行计算视图字段（本机定向）

负责人确认[设计 12 第 22 节](../design/12-feature-semantics-and-semantic-types.md#g11-computed-view-proposal)后实现。基线上一次完整验收仍是 `997794c` 的 #147；`d6f03e7` 的 #148 只覆盖文档提交，两者都不覆盖本批源码，因此本节只报本机定向结果。

**通过数（本机 27 类 183 项，0 失败／错误／跳过）**：语义 17＋结构门与草稿 43＋源写回 2；源编辑 7；降级与 IR 硬化 49；生成 46；应用层 7。含新增 `ComputedViewSemanticsTest`、`ComputedViewEditingTest`、`ComputedViewLoweringTest`、`ComputedViewRuntimeTest`、`ComputedViewToolchainContractTest` 与 G6/G7/G10 旧回归、`LanguageReferenceExamplesTest`。

**已证明**：计算字段只读来源行；普通返回、分页和嵌套一对一／一对多共用同一取值实现（`renderProjectionValue`）；精确金额不按存储精度舍入；`item.x` 与输出同名互不干扰；类型／枚举／跨文件成员沿正式绑定改名；首次新增计算与普通字段转计算均可写回；构造出的畸形 IR（错行、跨视图、错属性、错类型、`Unit`、字符串冒充整数）被独立校验拒绝；物理 schema 与非计算字段的生成文件不变。

**兼容性对照**：以 `git show HEAD:` 取出上一基线 `WorkflowRenderer`，与当前渲染器分别生成八题（去掉本批 `= …` 字段后），83／91／69／117／89／80／66／105 个文件逐字节相同。此对照只覆盖“无计算字段时不变”，不作为 G11 功能通过证据。

**未执行／不计通过**：真实 MySQL＋HTTP（包括新增的原子选课与满额后 `remaining`／`full` 断言，已接入 `MultiplicationBusinessConformanceIT` 与 `relation` IT 的既有夹具；`course` 表加入同一 DDL／seed）、八题生成工程构建、双全量门、独立部署均未在本机运行，交同源码 CI。`ProjectVisibilityTest` 有 4 项在本机因 Windows 不支持安全目录相对读取而报 `SIR-APP-SOURCES-READ-001`，未放宽生产读取边界、不折成通过，交 Linux CI 执行。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test `
  '-Dtest=ComputedViewSemanticsTest,ComputedViewEditingTest,ComputedViewLoweringTest,ComputedViewRuntimeTest,ComputedViewToolchainContractTest,LanguageReferenceExamplesTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

结果汇总按类去重读取各模块最新 `TEST-*.xml`，副本与索引在 `target/g11-verification/`；渲染器兼容对照脚本为 `target/G11BaselineCompare.java`。本节的账本数字（当时 65／5／14）已被同源码 #150 验收取代：“显示剩余名额”转✓，总账 66／5／13，见[第 21 节](#ci-150-accepted)；library 可借数量是跨行聚合，仍不因本行计算完成。


<a id="ci-150-accepted"></a>
## 21. CI #150：G11 计算视图真库与部署验收闭环

受测提交 **`4a1d8c8c2f28fd0d5678f65bfd4494cf163d8321`**，[verify #150](https://github.com/HoloNova/Software-IR/actions/runs/39724155367)，两个 job 全部 success，19 个步骤无失败。已下载 `conformance-evidence`、`deployment-evidence`、`surefire-reports` 核对，不只依据运行总状态；本机副本在 `target/ci-investigation/150/`。

| 范围 | 同 SHA 实际证据 |
| --- | --- |
| 双全量门 | Frozen full gate、Completion full gate 均 success |
| 关系场景（计算字段主证据） | `relation-slice-report.txt`：40 checks 通过、0 失败。`doubledCapacity` 对根行分別为 60／40（不同行、不同页），嵌套 `label` 逐行取各自 student，`active` 按枚举逐行 true／false；读语句仅为 `SELECT id,code,name,capacity FROM course` 加既有批量关联读取，没有虚构列、没有逐行查询 |
| 乘法场景 | `multiplication-report.txt`：56 checks 通过、0 失败。8 个并发选课仅 `[200,200,200,409,…]` 三次成功，`course=[1|3|3, 2|1|1]`；满额响应 `{capacity=3, enrolled=3, remaining=0, full=true}`，预满行 `{remaining=0, enrolled=1, full=true}`；拒课后计数未变 |
| 其余真库场景 | money 57、write 61、crud-access 47、query 40、guard 24、bearer 35、dual-token 34、token-slice 33，均 0 失败 |
| 八题生成工程 | `surefire-reports` 中 TopicSamplesOfflineCompilationTest 与 TopicSamplesTest 各 1 项通过、0 失败／跳过 |
| 独立部署 | `deployment-evidence/report.json` 的 `commit` 即上述 SHA；deployment、httpSmoke、persistence、privileges、swagger、contract、missingVolume、business、update、dataUpdate、decimalShape、freshChain、cleanup 全部 PASSED；合同 13 PASSED／0 FAILED／7 NOT_VERIFIED，业务 26 PASSED／0 FAILED |

#149 的三项失败已由 `4a1d8c8` 修正：课程视图补上 `capacity`，课程行改用按表读取的 `expectCounters`，不再借用只认夹具主表的 `expectRow`。原子选课在 #149 就已通过，说明那三项是测试假设错误，不是计算逻辑缺陷。

据此把 course “显示剩余名额”由 ✗ 改 ✓，账本更新为 **84 条：66 完整（79%）、5 带折扣（6%）、13 未写出（15%）**。library 可借数量仍需跨行计数、罚款仍缺日期差，均未升级；G5／G8 未获扩范围授权。本机未重跑构建、测试或服务；该基线不覆盖后续 G2 业务组合的新源码。


<a id="g2-locking-local"></a>
## 22. G2 第一批：显式行锁与区间预约（待同源码 CI）

基线 `359a45a82f8d8896c7e41c47b33cc506835524c3` 的 [#152](https://github.com/HoloNova/Software-IR/actions/runs/39729460418) success 已核对；本批不能沿用该结果宣称新锁能力已通过真库。设计第 23 节同步纠正旧草案：课程冲突需学生锁，先修课集合中的 `required` 尚无取得／遍历路径，均不是已实现业务。

**RED → GREEN：**新增 ExplicitLockSemanticsTest 六项先因 `for update` 尚无法解析而失败；修复后均通过。测试误用的 service 包名、exists 所调用的 selectObjs 与 MyBatis lambda 元数据已对齐既有产物／接口，没有为测试改变生产 SQL。

| 实际定向类 | tests | 范围 |
| --- | ---: | --- |
| ExplicitLockSemanticsTest | 7 | atomic identity 合法；readonly／query、非 atomic、非主键拒绝 |
| ExplicitLockEditingTest | 1 | 普通 Update 命令加锁／解锁；实体改名后保留绑定与锁 |
| ExplicitLockLoweringTest | 8 | 明确锁与 READ_COMMITTED；Scope 保留事实；独立 Core／目标 IR 反例，含缓存计划缺失 |
| ExplicitLockRuntimeTest | 2 | 真实生成 Java 编译执行，锁在查询与插入之前；占用拒写；实际 YAML 绑定 MybatisPlusProperties 得到 STATEMENT；非版本及版本实体均执行；Mapper 与普通能力版本相同 |
| ExplicitLockToolchainContractTest | 2 | 预约样例与实际 IT 源码内存贯通；确认先读、再锁、同查询再读的真实路径 |
| WriteSliceSemanticsTest | 29 | 旧 PATCH／版本业务 |
| SoftwareSchemaCompletenessTest | 8 | 唯一结构完整性门 |
| StructureViewSensitivityTest | 23 | 现有结构变化可见性 |
| SirDeclarationWriterRoundTripTest | 2 | 多份源码声明往返，包含显式锁夹具 |
| WriteSliceLoweringTest | 21 | 原版本条件写入、自动锁与目标计划 |
| LoweredIrHardeningTest | 19 | 旧目标 IR 硬化 |
| GeneratorEntityArtifactInvarianceTest | 4 | 实体 Mapper 不依赖能力集合 |
| GeneratorWriteSliceContractTest | 17 | 原写入生成合同 |
| MultiplicationToolchainContractTest | 3 | 扩展后的真实 IT 源码及旧数量购买 |
| TopicSamplesTest | 1 | 八题内存编译／生成／交付规划；非生成工程构建 |
| **合计** | **147** | **0 failures／errors／skips** |

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=ExplicitLockSemanticsTest,ExplicitLockEditingTest,ExplicitLockLoweringTest,ExplicitLockRuntimeTest,ExplicitLockToolchainContractTest,WriteSliceSemanticsTest,SoftwareSchemaCompletenessTest,StructureViewSensitivityTest,SirDeclarationWriterRoundTripTest,WriteSliceLoweringTest,LoweredIrHardeningTest,GeneratorEntityArtifactInvarianceTest,GeneratorWriteSliceContractTest,MultiplicationToolchainContractTest,TopicSamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

计数从本次各类最新 Surefire XML 去重取得；副本及索引封存于 `target/g2-lock-verification/`。AFT Java LSP 初始化失败，诊断为 PARTIAL；不把它记成“无编译错误”，编译与验证依据实际 Maven 结果。没有启动 MySQL、应用或浏览器，没有本机全量门／生成工程全构建，也没有新覆盖率测量，不能将定向通过换算成 80% 覆盖率。

**真库 NOT_RUN（本机）：**复用现有 MultiplicationBusinessConformanceIT 应用，IntervalBookingChecks 增加 8 并发重叠区间、相邻／包含／相交、空／逆序区间、不同房间、缺失父行及拒写检查。另由控制连接锁 Room，等 performance_schema.data_lock_waits 证明 HTTP 请求确实先读后等待，再提交冲突预约；请求应在得到父锁后重新查询并返回 409，且库中只有控制夹具插入的一行。这同时检验 READ_COMMITTED 与 STATEMENT 缓存，不能靠普通并发跑快慢碰运气。IT 只编译，未执行，等待本批同 SHA CI。

**CI 触发：**verify 的 push.paths-ignore 增加 `docs/**` 与 `**/*.md`，纯文档 push 不创建 CI 运行，workflow_dispatch 仍可手动使用；混合源码／文档提交正常触发。已有 YAML 解析器核验配置通过，未制造纯文档提交来测试过滤器。以后文档优先随对应代码提交。本批账本保持 66／5／13；library 限额、课程冲突／先修课及排队均未完成。


<a id="ci-153-reference-identity-fix"></a>
## 23. CI #153：引用值误以整个实体绑定 SQL（已修复，待新 CI）

失败 SHA `1997408e4a563a9fad58d48c7d51d1cd88cbc7b8`，[verify #153](https://github.com/HoloNova/Software-IR/actions/runs/39744030964)。Frozen／Completion 双全量门均 success，Business conformance ITs 失败；独立验证、CLI、部署均 skipped。唯一失败用例是 MultiplicationBusinessConformanceIT：61 项检查通过、11 项失败。原有金额／乘法／名额逻辑通过，新增预约请求返回 500；不能据此确认锁等待及重叠拒绝已经生效。

**真实错误，而非夹具期待错误：**`spring.stdout.log` 显示 `SELECT id FROM booking WHERE room_id = ? … LIMIT 1` 设置第一个参数时抛 `java.io.NotSerializableException: example.multiplication.domain.Room`。`item.room == room` 中右侧变量是已加载的实体对象；旧 Lowering 把它原样留在普通比较中，关联参数／再次 load 的 asIdentity 又只改类型标签，不生成 getter，因此整个 Room 被交给 MyBatis。等待锁测试在第一次查询就退出，未走到等待，不是已证明的并发缺陷或环境波动。下载证据保存在 `target/ci-investigation/153/`。

**本次修复：**集中在 SpringBootModelLowerer 的主键取值计划。需要 identity 的 load、引用相等／不等及关联外部参数统一复用 referencedIdentity：loaded／created 行用正式身份字段的 MemberExpression，已是主键载体的 input 引用／actor 不重复 getId。Optional 引用保留可空结构与输入 Optional 解包；PresentComparison 使用降级后的真实物理类型，避免 Long 参数与 Ref 标签不一致。没有 Generator 特判，没有 Serializable 补丁，也未把 SIR 改成手写 room.id 来绕过合法引用比较。

**RED → GREEN：**原 ExplicitLockRuntimeTest 增加查询 Wrapper 参数检查后，两种版本形态均复现“期待 1L，实际 Room 对象”。ReferenceQueryRuntimeTest 还复现 `load Room by room` 生成的 `selectById(Room)` 无法编译。修复后实际生成服务编译执行，并用真实 MyBatis DefaultParameterHandler 与 PreparedStatement 探针检查参数：exists／count／find、相关 any、普通／Optional 引用输入、nullable 列与 loaded row 比较均只绑定主键或 SQL NULL；再次 load 也使用 7L。扩展测试最初误写 Unit 作为 find 输出、fails 顺序及 Optional 引用 setter，均对齐既有 Page／输入协议，未为测试改变这些规则。

本机 **21 类 89 项，0 failures／errors／skips**：ReferenceIdentityLoweringTest 2、ExplicitLockLoweringTest 8、ScalarQueryLoweringTest 5、RelatedQueryParametersTest 2、OptionalValueLoweringTest 2、ActorRowLoweringTest 2、KeyedLoadLoweringTest 3、GuardedUpdateLoweringTest 3、ComputedViewLoweringTest 15、ExplicitLockRuntimeTest 2、ReferenceQueryRuntimeTest 1、OptionalPayloadRuntimeTest 3、GeneratorOptionalValueContractTest 13、GeneratorScalarQueryContractTest 2、GeneratorGuardedUpdateContractTest 4、GeneratorKeyedLoadContractTest 5、GeneratorCredentialContractTest 4、ComputedViewRuntimeTest 7、GeneratorUuidStorageContractTest 3、ExplicitLockToolchainContractTest 2、TopicSamplesTest 1。计数以最新 XML 为准，副本与索引在 `target/ci-153-verification/`。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=ReferenceIdentityLoweringTest,ExplicitLockLoweringTest,ScalarQueryLoweringTest,RelatedQueryParametersTest,OptionalValueLoweringTest,ActorRowLoweringTest,KeyedLoadLoweringTest,GuardedUpdateLoweringTest,ComputedViewLoweringTest,ExplicitLockRuntimeTest,ReferenceQueryRuntimeTest,OptionalPayloadRuntimeTest,GeneratorOptionalValueContractTest,GeneratorScalarQueryContractTest,GeneratorGuardedUpdateContractTest,GeneratorKeyedLoadContractTest,GeneratorCredentialContractTest,ComputedViewRuntimeTest,GeneratorUuidStorageContractTest,ExplicitLockToolchainContractTest,TopicSamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

AFT Java LSP 初始化仍失败，诊断 PARTIAL；以实际 Maven 编译及测试为依据。无新增依赖，未启动数据库、应用或浏览器，未在本机重复全量／生成工程构建；无覆盖率测量。所有预约并发、区间及 performance_schema 锁屏障断言保留不变，等待修复 SHA 的 CI。账本继续保持 66／5／13；此修复与对应文档同批提交，无纯文档 CI。


<a id="ci-runtime-optimization"></a>
## 24. CI 时长优化：取消重复执行，不删除覆盖（主验证已测，部署被取消）

负责人要求缩短 CI。先从 GitHub 步骤时间与成功报告定位，不按“从未失败”等同“不会失败”删除测试。现状基线如下；时长不含等待其他运行释放并发组的排队时间。

| 成功运行 | 主验证 job | 在线预热 | Frozen 全量 | Completion 全量 | 业务 IT | 独立验证 IT | 部署 job／冷部署步骤 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| [#150](https://github.com/HoloNova/Software-IR/actions/runs/39724155367) | 12:55 | 3:11 | 3:05 | 3:04 | 1:43 | 0:51 | 10:26／10:09 |
| [#152](https://github.com/HoloNova/Software-IR/actions/runs/39729460418) | 13:24 | 3:23 | 3:13 | 3:04 | 1:45 | 0:52 | 13:01／12:44 |

这两个 job 串行，因此原运行约 23:21／26:25。同一源码的全量测试体在在线预热、Frozen、Completion 各执行一次，成功路径重复三遍。#150 最终报告中，WorkspaceVersionApplicationTest 39.199s、GeneratedProjectOfflineCompilationTest 31.501s、TopicSamplesOfflineCompilationTest 29.117s；这些含真实工作区链路与生成工程编译，不是可以凭稳定性删掉的“无用断言”。#153 的主键绑定缺陷还说明真库验收与静态／编译合同并不重复。

**本批只改执行编排，不改测试列表或业务代码：**

1. Reactor 在线预热保留 `clean verify` 的实际编译、插件和动态 Surefire provider／launcher 解析，传 JUnit 5.12.2 的 `junit.platform.execution.dryRun.enabled=true`，不执行测试体／生命周期。它的跳过报告不算通过证据；生成工程依赖仍由原在线 QuerySliceBusinessConformanceIT 真实预热。不能用 `maven.test.skip=true` 冒充完整 provider 预热。
2. Frozen 离线全量门保留全部测试，明确 `dryRun.enabled=false`，失败不容忍，是正常路径唯一的 Reactor 全量测试执行。没有新增跳过项。
3. Completion 仅 `failure() && steps.frozen.outcome == 'failure'` 时执行，用 `maven.test.failure.ignore=true` 收集后续模块诊断；不会将 Frozen 失败改成 success。补跑前上传 `frozen-failure-surefire-reports` 保留首次失败 XML，避免 clean／重跑覆盖首发证据。原最终 surefire-reports 与业务证据 artifact 保留。
4. 业务真库、独立验证、CLI、离线生成工程与冷部署链全部保留；并发组仍 `cancel-in-progress: false`，不擅自取消尚在验收的源码运行。纯文档过滤与 workflow_dispatch 不变。CLI jar 来自 Frozen 成功构建，不再依赖成功路径中多余的 Completion。

**本机验证：**已有 YAML 解析器核验结构、显式的真实测试模式、诊断触发条件、先上传首次失败再清理的顺序、不可容忍 Frozen 失败与原文档过滤。未为这种可逆配置改动添加镜像实现的仓库单元测试。另建 `target/ci-dry-run-probe` 单类隔离 Maven 探针，用与 Reactor 相同 JUnit 5.12.2／Surefire 3.5.6 验证 dry-run 与正式执行：测试生命周期写 marker，测试体故意失败；dry-run XML 为 1 test／1 skipped／0 failures 且 marker 不存在，关闭 dry-run 后为 1 test／0 skipped／1 failure，Maven 退出 1 且 marker 出现。后者是预期的反向控制，不是仓库回归失败。该探针不用应用、数据库或网络；Maven 256 MiB／2 个处理器，fork 256 MiB／2 个处理器。未本机跑 Reactor 全量或生成工程全构建。

基线步骤时间保存在 `target/ci-investigation/{150,152}-timing.json`；探针两份原始 XML 在 `target/ci-dry-run-probe/{dry-run-result,real-run-result}.xml`。本地只证明配置与 dry-run 机制，不宣称已验证 Actions 上的完整失败分支或冷依赖环境。

**预期而非实测：**正常路径直接移除约 3:04 的重复 Completion，在线预热另减少测试体耗时，合计目标约 5～6 分钟；总时长仍受网络／容器启动波动影响，需新 CI 对比。冷部署本身仍约 10～13 分钟，未在本批冒险改交付协议或删漂移／权限／迁移验收。后续可进一步评估将独立部署与业务 IT 并行，以及 Maven／容器缓存；须先验证依赖、缓存隔离和证据归属，不把这些优化记成已完成。


<a id="ci-154-accepted"></a>
## 25. #154 区间预约验收与 #155 主验证提速实测

`bf14d905b2edce5b67bc0ddf39ed189be2c10aa1` 的 [verify #154](https://github.com/HoloNova/Software-IR/actions/runs/39749656260) 两个 job 及实际门均 success。已下载 conformance-evidence，`multiplication-1a162d5e95d-5444/multiplication-report.txt` 明确 `verdict=PASSED`、72 checks passed／0 failed；8 个重叠预约恰一成功，锁等待屏障在 performance_schema 中真实出现，等锁后重新查询拒绝已提交冲突。报告 SIR SHA-256 为 `d404f6581ad775240346ad6b55715dcaa5ad7675adbd6866bf9e2ade560ead36`。首轮 #153 的 Room 对象绑定缺陷因此闭环；本地证据副本位于 `target/ci-investigation/154/`，不依赖 #155 的部署结论。需求账本仅据此把预约重叠一条由△转✓，不认为所有八题业务都已经真实验收。

`57b0ee588b99af34ad01da98c41d09d991bacba6` 的 [verify #155](https://github.com/HoloNova/Software-IR/actions/runs/39751981905) 最终 **cancelled**：主验证 success，独立部署 cancelled，不能写成整轮 success。它与 #154 的业务源码相同，差异是 CI 编排及配套文档；主验证实测如下：

| 步骤 | #154 旧编排 | #155 新编排 |
| --- | ---: | ---: |
| 在线 Reactor 预热 | 4:51（真实执行测试） | 0:27（dry-run） |
| Frozen 正式全量 | 4:49 | 3:37 |
| Completion | 4:52（成功路径重复） | SKIPPED（按预期） |
| 主验证 job 合计 | 19:51 | 8:18 |

主验证观测缩短 11:33；其中预热与重复 Completion 的变化符合优化目的，Frozen 本身也有 runner／缓存波动，不能把全部收益当作可重复的净优化基准。#155 的业务 IT、独立验证及 CLI 等主 job 步骤仍真实执行成功，乘法／预约仍 72 项通过。部署在运行 6:33 时被取消，没有完整结果，**不比较整轮时长、不将取消算提速**。首次失败上传及 Completion 失败分支在本轮未触发。原始 job／step JSON 在 `target/ci-investigation/{154-timing,155-final-timing}.json`；后续并行／缓存优化尚未实施。

<a id="g2-library-local"></a>
## 26. G2 第二批：借阅上限与借／还一致性（待新 CI）

本批不改 Parser、Semantic、Lowering 或 Generator 的生产代码，只组合已有的身份行锁、READ_COMMITTED、STATEMENT 缓存、条件增减与状态守卫。实际 `topics/library/project.sir` 新增 User.activeLoans 默认 0；Borrow 先锁读者、用 `< 5` 的数据库条件占名额，再守卫修改 Copy 并插入 Loan；GiveBack 固定 User → Loan → Copy 的锁顺序，验证归属及未归还状态，计数大于零才减一，随后标记归还并释放副本。晚发生的副本状态失败必须连前面的计数和 Loan 更新一起回滚；Renew 不修改名额。旧数据需业务回填，不能将 DEFAULT 0 当作历史借阅迁移；方案与可执行 SQL 模板见设计第 23.6 节。

**RED：**LibraryBorrowingRuntimeTest 直接从仓库实际 library 题目读取源码，生成 Java 并编译；首项契约因缺少读者锁／计数条件写入而失败。随后只改 SIR。新增 fails 排序错误由原语义规则拒绝，已排序，不放宽规则；新 HTTP 合同误要求显式 HttpStatus.OK，已改为验证原 PostMapping／默认 200，不改生产状态码。

**本机最终 7 类、31 项，0 failures／errors／skips：**LibraryBorrowingRuntimeTest 8、LibraryBorrowingToolchainContractTest 3、ExplicitLockRuntimeTest 2、ReferenceQueryRuntimeTest 1、GeneratorEntityArtifactInvarianceTest 4、TopicSamplesTest 1、CrudAccessContractTest 12。新增旧库超限用例后仅补验该类，其余已通过范围没有重复运行；计数按各类最后一次完整报告去重。原始 XML 与索引封存于 `target/library-borrowing-verification/`。

- 真实生成服务：4→5、满额拒借、首次归还减一及重放不减、外人归还／未回填计数拒写、借与还的晚失败传播、续借不占额；历史真实回填为 8 本时可还到 7 本但不能再借。此层直接调用服务而非 Spring 代理，不将内存代理当作数据库锁或回滚证据。
- 工具链：系统计数不出现在 RegisterInput，新账号服务赋默认 0；旧 HTTP 路径及 201／默认 200 不变，物理 INT／NOT NULL／DEFAULT 0 与引用表完整；八题仅内存生成和交付规划。library 为 300 行 SIR／3354 行生成 Java／16 个接口，其余七题规模不变。
- AFT Java LSP 初始化失败为 PARTIAL；以实际 Maven 编译／执行为依据。没有本机应用、MySQL、浏览器、全量门或生成工程全构建；没有新覆盖率测量，不宣称 80% 覆盖率。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=LibraryBorrowingRuntimeTest,LibraryBorrowingToolchainContractTest,ExplicitLockRuntimeTest,ReferenceQueryRuntimeTest,GeneratorEntityArtifactInvarianceTest,TopicSamplesTest,CrudAccessContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

**真实 HTTP／MySQL：NOT_RUN（本机）。**新增 LibraryBorrowingBusinessConformanceIT 用实际题目源而非复制近似业务，复用已有隔离 schema／构建／进程／观察连接 harness，由 verify 的业务阶段启用。本机只编译 IT。检查 8 借争最后一额、8 还同一 Loan 恰一成功、归还后再借、旧归还不能释放新借阅的副本、同读者借还两种有效串行结果、不同读者争同一副本、晚失败整事务回滚、缺省回填计数拒写及续借不占额；独立 JDBC 每轮比较 activeLoans 与未归还 Loan 数，最终不超 5。夹具错误 SQL 必须真正失败，不能误作环境 NOT_RUN；仅 SQLState 08 的连接不可达按既有基础设施口径处理。

此次新增一个实际 library 应用验收，而不是再建一套 harness／服务；原有业务测试全部保留，沿用 #155 的一次正式全量与失败才补诊断编排。账本只把新借阅上限记为待真库的△，不提前转✓；结合 #154 已验收的预约，当前 **67 完整／5 带折扣／12 尚未写出**。剩余课程冲突、任意先修课集合、排队、日期差、循环／聚合不随本批宣称完成。配套文档随源码提交，不另触发纯文档 CI。


<a id="ci-156-error-contract-fix"></a>
## 27. #156：借阅 IT 错误码预期未按声明名保留大小写

失败源码 `152db3346e0271a6a2db0e06a18c4153edef0ccd`，[verify #156](https://github.com/HoloNova/Software-IR/actions/runs/39761800367)。正式 Frozen 全量门通过，成功路径 Completion 按预期跳过；11 个业务 IT 中其他 10 个通过，LibraryBorrowingBusinessConformanceIT 失败。后续独立验证、CLI 和部署被跳过，不记整轮通过。

**根因是测试预期而非借还生产行为：**实际 library 声明 `error BorrowLimitExceeded http 409;`，生成异常的 code 保留原名 `BorrowLimitExceeded`；新 IT 却期待通用大写下划线 `BORROW_LIMIT_EXCEEDED`。Forbidden、AlreadyReturned、CopyNotAvailable、LoanCounterMismatch、NotRenewable 同类失配。语言参考的错误声明一节及 ExceptionRenderer 已明确 code 来自声明名，不能因为框架的 INVALID_REQUEST 使用大写就改变业务错误协议。

下载报告 `library-borrowing-1a163246826-51037/library-borrowing-report.txt` 显示 **60 checks passed／22 failed**，22 项全部是错误码比较；HTTP 状态均符合预期。8 借争最后名额恰一成功、8 还恰一成功、重放旧归还、同读者借还竞争、不同读者争同一本、晚失败回滚、异常计数拒写和续借均有真实数据库证据通过。只据此确认这些检查，不将整份 FAILED 报告转为 PASSED。原始日志、conformance-evidence 与 surefire-reports 在 `target/ci-investigation/156/`，UTF-16 原始日志另存 UTF-8 供检索，未改内容。

**修复：**IT 的固定错误预期集中到类型化 Failure，所有错误检查仍比较精确状态与 code，不从响应反推预期、不忽略大小写、不删除或跳过任一并发／回滚检查。新增 LibraryBorrowingErrorContractTest 直接编译实际 `topics/library/project.sir`，逐项将这六种固定预期与正式 NormalizedError 名称／状态、实际生成的异常构造值核对。真实生成服务的原负例也补验异常公开的 `code()`，不只看异常类名。library SIR、Parser／Semantic／Lowering／Generator 生产代码、HTTP 状态、SQL、锁及工作流均未改。

**RED → GREEN：**先保留原六种错误值，将它们统一供 IT 与新契约使用；六项契约全部以 `IT expects an undeclared error code` 失败。随后只修正六个 code 常量。最终 **3 类 17 项，0 failures／errors／skips**：LibraryBorrowingErrorContractTest 6、LibraryBorrowingRuntimeTest 8、LibraryBorrowingToolchainContractTest 3。原始 RED XML、最终各类 XML、源哈希索引位于 `target/ci-156-verification/`。没有重跑其余已通过范围，没有启动 MySQL、应用或浏览器，没有本机全量构建。AFT Java LSP 初始化失败，结果 PARTIAL；编译与定向结论以实际 Maven 为依据，没有新覆盖率统计。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=LibraryBorrowingErrorContractTest,LibraryBorrowingRuntimeTest,LibraryBorrowingToolchainContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

工作区另有未提交的课程阶段测试，本次定向未选择它们，修复提交也不包含它们。当前需求账本保持 **67／5／12**，新借阅仍为待验收△；完整 IT 和部署须修复 SHA 的 CI，未复跑前不宣称通过。按负责人要求推送后不等待或轮询 CI 结果，由负责人通知；配套记录随修复代码提交，不单独触发文档 CI。


<a id="g2-course-local"></a>
## 28. G2 第三批：课程冲突、全部直接先修与座位事务（待同源码 CI）

基线 `9d31a82a24a738c8c635ea511cb44e114f501c8d`。负责人要求继续、不等待或查询上一轮 CI，结果由负责人通知；本轮没有读取新运行状态。业务实现只在实际 `topics/course/project.sir`，未改 Parser／Semantic／Lowering／Generator 生产代码；为什么以 Course 为根、为何冻结发布及选择 User 锁，见[设计 23.7](../design/12-feature-semantics-and-semantic-types.md#g2-course-combinations)。

**RED → GREEN：**CourseSelectionRuntimeTest 在修改题目前实际编译执行并失败，原 Enroll 没有学生锁、显式课程锁或冲突／先修查询。修改后同目标转绿；过程中测试误期待源码字符串 NOT EXISTS，实际生成的是 wrapper.not，运行时 SQL 才成为 NOT(EXISTS)，已按真实协议分别核对。新 IT 的请求路径曾误写为 /enrollments，本机自审发现后修正为原 /courses/{id}/enroll，加入消费端常量到实际控制器的契约，不改生产路由。

| 最新定向报告 | tests | 真实证明的范围 |
| --- | ---: | --- |
| CourseQueryCompositionTest | 2 | 两个同级相关子查询可表达任意直接先修；实际生成 Java 编译执行，MyBatis DefaultParameterHandler 验证 selected／actor 只绑定 Long |
| CourseSelectionRuntimeTest | 14 | 实际题目所有 Java 编译；锁／查询／写入顺序，草稿／重复／冲突／缺先修／满额拒写；归属与零计数；重复退课；晚插入异常传播；四个数字缺省的真实 NotNull 字段路径 |
| CourseSelectionContractTest | 16 | 13 种 IT 固定业务错误与实际声明／生成异常；原路由和201创建、公开分页默认；管理员发布边界；实际交付表／DDL及 observer 列顺序 |
| TopicSamplesTest | 1 | 八题内存编译、生成、交付规划；非生成工程全构建 |
| ExplicitLockToolchainContractTest | 2 | 旧预约及锁等待场景源码内存规划 |
| MultiplicationToolchainContractTest | 3 | 原数量购买／原子库存／已有课程计数夹具 |
| **合计** | **38** | **0 failures／errors／skips** |

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=CourseSelectionRuntimeTest,CourseQueryCompositionTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=CourseSelectionContractTest,TopicSamplesTest,ExplicitLockToolchainContractTest,MultiplicationToolchainContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

以上是实际执行过的定向范围；契约／运行时在修正新测试后又分别仅复测受影响类，计数按各类最终 XML 去重，不把重复运行相加。RED XML、最终6类XML和索引保存在 `target/course-verification/`。AFT Java LSP 初始化失败，状态 PARTIAL，编译依据实际 Maven 结果；没有新覆盖率测量，不将38项通过换算成80%覆盖。没有本机全量门、生成工程Maven构建、数据库、HTTP应用进程或浏览器；真实服务是在测试JVM中编译加载并直接调用，不冒称事务回滚已验收。

**真库 NOT_RUN（本机）：**新增 CourseSelectionBusinessConformanceIT，直接编译实际 course 题目，沿用已存在的隔离实例／日志／凭据／超时／清理 harness。工作流在原 Business conformance ITs 中显式选择它并启用 `kcg.course-conformance.enabled`，无额外 job、不删原验收。测试包括：

- 缺少、部分、同数量但不同集合、四门直接先修；空要求集合照常允许；管理员追加已通过记录后可选，普通用户不能伪造。
- 每周半开节次的重叠拒绝与端点相邻成功，不同星期独立；发布前管理员可增删要求，发布后冻结；未知课程、草稿、重复选课及外人退课拒写。
- 四个数值 missing／null 各自400且必须带实际 NotNull path，避免把坏JSON返回400当作通过；范围、空／逆序区间拒写，最后有效节次可建草稿。
- 同一用户并发选择重叠课程恰一成功；八人抢三席、八次退同一记录只释放一次；所有 course.enrolled 与真实 Enrollment 独立观察对账。
- 控制连接先锁 User，通过 performance_schema.data_lock_waits 证明 HTTP 在等学生锁，再提交一条冲突课程；请求拿锁后必须重查并拒绝。
- 明确标注的 owned-schema 测试触发器只制造晚插入唯一冲突；临时外键只制造晚删除失败，分别核对增座／减座与记录整体回滚。控制夹具最后恢复，非产品测试钩子；还检查错误旧计数不下溢、不静默修复。

该 IT 仅通过编译，以上结果都等待本批源码的 CI，尚无 run URL／artifact，不能使用早期G11课程计数的报告替代本题真库证据。旧库的真实课表、发布、先修、通过记录和座位账本不能自动猜测回填；交付拒绝无默认增加必填课表列。范围未增加多时段／多学期、递归先修、成绩撤销、排队、循环、聚合或日期差。

当前源码 course 为 **384 行SIR／3379 行生成Java／14个显式接口**。两条新表达按待真库△记账，完整数仍67；当前 **67／7／10**。配套文档随本批代码提交，不单独触发文档CI；推送后不轮询或等待结果。


<a id="ci-158-readiness-fix"></a>
## 29. CI #158：课程 IT 就绪地址不属于实际接口

失败 SHA `7c0017adc75b37f4462f6126fb9ff0bda78d1fc2`，[verify #158](https://github.com/HoloNova/Software-IR/actions/runs/39771833507)。正式 Frozen 全量门通过、Completion 按预期跳过，失败仅在新 CourseSelectionBusinessConformanceIT。生成工程已 `mvn clean verify` 成功，进程仍活着，但就绪请求 `/api/courses/10` 持续返回 HTTP 404。course 题目只公开搜索 `/api/courses`，没有按 id 的公开详情接口；本次不能误诊为应用启动崩溃或课程运算错误。报告的7项通过均为编译／构建／清理等基础检查，**课程业务、并发与回滚尚未执行**。独立验证、CLI、部署均因上游失败跳过，仍未验收。原始日志、报告及 XML 在 `target/ci-investigation/158/`。

修复仅改 IT：就绪地址改为已存在的公开 GET `/api/courses?keyword=TARGET&page=1&size=1`，保持原 harness 要求成功响应的判定，不接受404作为READY、不加重试时长、不新增生产详情路由。FIXTURE 在同测试包可读，使契约检查引用同一个实际地址而非另一份常量。新增两项先取得 RED：其一对照实际 DeploymentPlan.Api.endpoints，要求路径对应 GET、匿名可用且为 QUERY 绑定；其二对照 SearchCourses 的正式输入类型，检查查询参数集合和有效分页。随后修改地址取得 GREEN。

| 最新定向类 | tests | 结果 |
| --- | ---: | --- |
| CourseSelectionContractTest | 18 | 含两项就绪契约、声明错误、路由状态、数据库合同与发布冻结 |
| CourseSelectionRuntimeTest | 14 | 实际课程样例生成 Java 的锁顺序、错误、座位与缺省数值检查 |
| CourseQueryCompositionTest | 2 | 同级先修关联与真实 MyBatis 参数绑定 |
| **合计** | **34** | **0 failures／errors／skips** |

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=CourseSelectionContractTest,CourseSelectionRuntimeTest,CourseQueryCompositionTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

本机未启动应用、MySQL或浏览器，未执行真库IT或全量门；AFT Java LSP 初始化失败为 PARTIAL，以 Maven 实际编译及最新 XML 为依据，不宣称覆盖率。RED／GREEN 与源码清单封存于 `target/ci-158-verification/`。排队可行性探针及第23.8节草案在工作区另行保留，不计入34项、不混入本修复。课程两条仍标待真库△，账本不因修探针提前上调。

同时核实前次借阅错误码修复 `9d31a82` 的 [#157](https://github.com/HoloNova/Software-IR/actions/runs/39765191920) conclusion=success；#158 的借阅 IT 亦通过。与本次尚未执行的课程业务分开记录，不借用借阅结果证明课程通过。修复将单独提交推送，新的完整验收仍由负责人通知，不主动查询或等待。


<a id="g2-reservation-local"></a>
## 30. G2 第四批：实际书目预约与队首主动领取（待同源码CI）

以课程就绪修复 `7d13a2dcaf10df999f4c5b0af7d9d829a31c2975` 为基线；负责人继续后实施设计23.8的本人扫码领取，不是还书自动分配。没有查询或等待新CI。实际 topics/library 新增待办实体和四个操作，用更早同书id的exists判FIFO；普通Borrow、GiveBack、AddCopy也纳入Book锁。未增加first、条件分支、定时器、语法或编译器生产分支。

**有效RED：**LibraryReservationContractTest 两项在旧实际样例下编译运行，分别因缺ReserveBook等能力、Borrow无共同Book锁而失败；报告在 `target/reservation-verification/red/`。随后修改实际SIR。新增运行时探针最初未接受Boolean JDBC值，导致合法的returned=false参数被探针误拒；只修探针，不改生产参数或查询。补验不可借副本、更新参数与返回的BORROWED状态后转绿。遵守同一任务只提交一次的仓库约定，RED/GREEN报告随本批封存，不制造失败checkpoint提交。

| 最新定向类 | tests | 保障 |
| --- | ---: | --- |
| LibraryReservationRuntimeTest | 19 | 实际生成Java编译执行；队首／外人／满额／错书／重放；同书更早预约检查不带owner过滤；真实DefaultParameterHandler验证查询与更新参数为JDBC标量；入队条件、本人／馆员取消锁顺序、私有分页、晚删除错误向外传播 |
| LibraryBorrowingRuntimeTest | 8 | 原借／还／重复／越权／续借和旧超限计数；仅代理适配共同Book锁，旧断言保留 |
| ReservationHeadCompositionTest | 3 | 初始同书前序检查、非本人拒绝和真实Long绑定；不是业务IT替代 |
| GeneratorEntityArtifactInvarianceTest | 4 | 实体Mapper不因能力集合变化 |
| LibraryReservationContractTest | 14 | 两项原RED转绿，10种IT固定错误码与实际声明／异常一致；实际路由、鉴权、就绪地址和全局scope／私有列表边界 |
| LibraryBorrowingToolchainContractTest | 3 | 原接口／计数、真实交付表和扩展后的DDL |
| LibraryBorrowingErrorContractTest | 6 | 原借还IT的固定声明错误名／状态不漂移 |
| TopicSamplesTest | 1 | 八题内存编译、生成、交付规划；非生成工程构建 |
| **合计** | **58** | **0 failures／errors／skips** |

按各类最新Surefire XML去重，不累计先前重复执行。实际分批完成：旧借还8项先通过；修布尔探针后跑新服务与原合同；IT扩展后跑应用合同／八题；最后只补新服务、初始探针和Mapper不变性。成功命令如下，未为收口再重复全套：

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=LibraryReservationRuntimeTest,LibraryReservationContractTest,LibraryBorrowingToolchainContractTest,LibraryBorrowingErrorContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=LibraryReservationContractTest,LibraryBorrowingToolchainContractTest,LibraryBorrowingErrorContractTest,TopicSamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=LibraryReservationRuntimeTest,ReservationHeadCompositionTest,GeneratorEntityArtifactInvarianceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

**本机NOT_RUN：**扩展同一个LibraryBorrowingBusinessConformanceIT，先保留原借阅全套检查，再运行LibraryReservationChecks；没有新增应用／MySQL构建。包括无副本／全LOST／已借同书的拒绝、入队不占名额、私有分页与伪造owner拒绝、严格FIFO防普通借书插队、还书不自动分配、满额不跳队、取消提升、多／新增副本、8并发重复入队与领取、取消／领取竞争、不同书目、同连接可观察锁等待后的重新判队首。另用外键阻止最后预约删除、触发器使借阅插入失败，要求四表完整快照一致，再证明原预约仍可成功领取。测试控制SQL明确标为fixture，不增加生产测试钩子。所有新IT已由testCompile编译，本机未执行；真实回滚与完整部署只能看后续同SHA CI。

新接口路径和就绪地址均对照实际DeploymentPlan，不复用#158不存在的详情探针；10种错误预期保留SIR大小写，不重复#156的错误码假设。既有User／Copy／Loan账本检查仍执行，新队列检查额外逐行核对计数与物理副本当前持有人。

本机没有启动服务／数据库／浏览器或跑全量门；AFT Java LSP初始化失败为PARTIAL，以Maven实际编译和XML为依据，未测覆盖率。证据与源码哈希在 `target/reservation-verification/`。library规模443行SIR／4086行Java／20个接口；预约只从✗转待真库△，总数 **67／8／9**，不把未通知的CI认作通过。新增空队列表且须维护窗口同时切换全部写入口，不猜历史队列、不让旧版本Borrow绕过新Book锁；原activeLoans须真实回填，满额／弃领不自动跳过。通知、过期、续借限制及自动替下一读者借阅均未扩范围。文档随本批代码提交，不单独触发文档CI。


<a id="g8-decimal-sum-local"></a>
## 31. G8 首批：条件 Decimal 求和（已实现，待同源码 CI）

负责人确认设计24后，以 `ddcc3fd31d40886f5af0e5e7ff4d9cebca3f444a` 为基线实施。本批未查询或等待 CI；课程就绪和预约的旧 CI 仍由负责人通知，不推断其通过。方案、实现、技术探针与本记录同批提交，不制造纯文档 CI。

**实际交付：**新增独立 `sum Entity.field where predicate as result;`；只允许 readonly query 和直接 Decimal／Optional Decimal 列。SUM_ENTITY／SUM_FIELD 正式绑定、类型、secret拒绝、scope、结构、SourceEditor和来源图全部贯通；目标计划包含物理列／固定投影／selectObjs／单条聚合SELECT／空集零／当前Statement告警策略，非 Decimal 载体或错误行数明确失败。总额不套单笔 Money 精度，不经double，不作隐式舍入。`ExactAggregate` 支持及图谱角色按需生成，实体Mapper不变，包含SUM的工程计划STATEMENT本地缓存。

**RED → GREEN 及自审发现：**新语义先因语法缺失失败，目标层再因缺Sum计划失败。真实编译暴露 totalAggregateQuery 及实际谓词lambda q0/q1 与合法变量名冲突：用支持方法承接查询，不声明该临时局部；SUM专用lambda使用SIR不可声明的 `$` 前缀，旧查询命名／文本不变。真实MyBatis ParamMap在缺ew时抛BindingException的反例先RED，再改为先containsKey，仅对明确标记查询检查告警；普通查询不获新政策。独立IR的错误item归属、非布尔谓词亦先RED后补拒绝。新Money夹具将scope写在顶层的解析错误只修夹具，按实体内语法加软删除scope；直接把含类型声明的原始Core交给实现校验器的测试也改为正式TargetLowering入口，不改产品契约。

| 最新报告组 | 类数 | tests | 范围 |
| --- | ---: | ---: | --- |
| Semantic／Schema／typed refs／源往返 | 7 | 73 | DecimalSumSemanticsTest 12、原Scalar 8、完整性8、typed18、覆盖1、敏感性24、往返2 |
| SourceEditor | 2 | 4 | SUM普通增删改、LF／CRLF精确改名3，原Scalar编辑1 |
| Lowering／独立IR | 4 | 42 | SUM16（含14种损坏计划及secret Core）、Scalar5、旧硬化19、G7可选值2 |
| 真实生成服务／执行支持与旧合同 | 14 | 60 | SUM5、告警6、实际ledger4、Money IT附加探针编译1；前期映射2；旧Scalar2、Query8、Mapper4、Money7、原子金额2、连接绑定3、Optional DTO3、算术输入6、计算视图7 |
| 应用与八题内存生成 | 3 | 8 | SUM4：实际来源图完整构建、真实IT路由、多源import及改名；旧Money3；八题1 |
| **合计** | **30** | **187** | **0 failures／errors／skips** |

计数来自各类本次最新 XML，封存在 `target/g8-verification/surefire/`，索引 `local-result.json`，源码哈希 `source-manifest.json`。报告收集发现最终选择器误写 OptionalValuesLoweringTest（实际类为单数 OptionalValueLoweringTest），只补跑这2项后再核对30类，不把未执行选择器算通过。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=DecimalSumSemanticsTest,ScalarQuerySemanticsTest,SoftwareSchemaCompletenessTest,TypedReferenceSiteContractTest,StructureViewCoverageTest,StructureViewSensitivityTest,SirDeclarationWriterRoundTripTest,DecimalSumEditingTest,ScalarQueryEditingTest,DecimalSumLoweringTest,ScalarQueryLoweringTest,LoweredIrHardeningTest,OptionalValueLoweringTest,DecimalAggregateQueryFeasibilityTest,DecimalSumRuntimeTest,ExactAggregateWarningRuntimeTest,LedgerSumRuntimeTest,MoneyAggregateFixtureCompilationTest,GeneratorScalarQueryContractTest,GeneratorQuerySliceContractTest,GeneratorEntityArtifactInvarianceTest,MoneyRuntimeTest,MoneyAtomicContractTest,MoneySessionConfigurationTest,OptionalPayloadRuntimeTest,ArithmeticInputRuntimeTest,ComputedViewRuntimeTest,DecimalSumToolchainContractTest,MoneyToolchainContractTest,TopicSamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

以上为可复现的正确选择器；实际仅补跑单数Optional类，没有为形成此命令再重复全套。Maven限制384MiB、测试fork512MiB，各2个可见处理器，无`-T`。AFT Java LSP初始化失败为PARTIAL，编译结论取实际Maven，不宣称LSP干净或覆盖率达到80%。另一次ToolchainProjectGraphIntegrationTest有10项在Windows的安全目录相对读取入口受阻，未进入图谱业务验证、未计入187通过数；安全边界未降低，交Linux CI。新的SUM图谱用真实SourceSnapshot内存路径走ProjectGraphBuilder完整构建通过。

**ledger实际样例：**新增本人 `[since,until)` 总额和指定本人分类总额，使用Optional时间与G7拒绝缺省／反序；读取分类后验证owner，外人分类返回原CategoryNotFound，不将整个实体绑定为SQL值。4项实际生成服务测试确认精确大额、actor／时段条件、分类主键与拒绝路径；不等同于真实HTTP。两个接口都是记录金额之和，不依据可变分类方向净额计算，不伪称自然月／预算协议。规模195行SIR／3949行Java／23个显式接口；八题本机只内存生成与交付规划，不构建生成工程。账本仍 **67／8／9**，月度分组／预算、AVG、结果DTO、G5及日期差仍未完成。

**真实MySQL／HTTP：本机NOT_RUN。** 扩展原 MoneyBusinessConformanceIT，同一次应用构建／启动、同一schema；不增加新的业务IT应用或重活作业。保留旧金额／PATCH／并发／严格连接用例。新增Optional Measurement、静态软删除scope、owner条件、空集／全空／正负／大额（超过单笔19位）／尾随零、相关any重复标签不重复求和、请求值防SQL拼接、字面匹配转义以及读不改表。

另把 `ExactAggregateWarningFixture.java.txt` 只复制到该隔离生成目录，记录独立源码副本和SHA；它不是生成模板、SIR或正常交付文件。固定 `CAST(SUM(amount) AS DECIMAL(3,2))` 先用普通Wrapper证明服务器实际返回9.99饱和值，再用标记Wrapper要求HTTP500且不暴露SQL／表名，随后合法合计仍成功。严格与非严格会话均执行；不靠另开连接的SHOW WARNINGS，不吞告警，不把代理链或编译成功当真库通过。真实生成的Money全源码和附加控制器已在本机编译，但数据库与HTTP只待同SHA CI。没有启动本机应用／MySQL／浏览器，没有跑全量门或生成工程全构建；本批没有UI改动。


<a id="ci-162-sum-reference-fix"></a>
## 32. CI #162：SUM 参考示例没有遵守文档块契约

失败源码 **`93804e1b3427388e583bfcd7c127708cb049bda2`**，[verify #162](https://github.com/HoloNova/Software-IR/actions/runs/39883793969)。Frozen full gate 失败；首发 `frozen-failure-surefire-reports` 和后续 `surefire-reports` 各 218 份 XML 都只有 LanguageReferenceExamplesTest 的 everyAcceptedExampleCompilesThroughGeneration 一项失败：`sir @line 1491: PARSE / SIR-PARSE-001 … type @1:1`。本机原始副本在 `target/ci-investigation/162/`。业务 IT 未执行，独立部署被跳过，不能凭其它单测通过宣称 SUM 真库已验收。

原因是第 5.10 节把以 `type` 开头的声明片段标成 `sir`（该标签要求完整根文档），还漏了片段依赖的 User／Entry。上一轮 187 项定向没有选中现有 LanguageReferenceExamplesTest，遗漏了这条文档生成门。修复将其正确标为仍被该门执行的 `sir-declarations`，补齐实体和 Optional Money 测量列，保持 actor owner 筛选。不改成普通 text、不加白名单，也不改变 Parser 或 SUM 生产实现。

**RED → GREEN：**先原样复现 CI 的一项 PARSE 失败；再增加 SUM 专用契约，同一坏示例令两项测试失败。修复后全部正例仍经过完整生成，反例仍按声明诊断码拒绝。新增契约读取实际文档，不复制独立夹具；检查它仍在可执行块集合中、产生一项只读 SUM、BigDecimal 结果、单条 selectObjs、空集零、拒绝告警，以及 owner 成员／当前 item／actor 的正式符号绑定，不能把示例换成无隔离或非聚合查询后仍判绿。

五类最新报告去重共 **23 tests，0 failures／errors／skips**：LanguageReferenceExamplesTest 4、DecimalSumToolchainContractTest 4、DecimalSumRuntimeTest 5、LedgerSumRuntimeTest 4、ExactAggregateWarningRuntimeTest 6。实际 SUM／ledger Java 编译执行、当前 Statement 告警与 ParamMap 回归保留。最初命令的告警类误写为不存在的 ExactAggregateRuntimeTest；XML 清点发现后只补跑正确的 ExactAggregateWarningRuntimeTest，不把漏选类当通过、不重复累加。RED、GREEN 与源码哈希封存于 `target/ci-162-verification/`。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=LanguageReferenceExamplesTest,DecimalSumToolchainContractTest,DecimalSumRuntimeTest,LedgerSumRuntimeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=ExactAggregateWarningRuntimeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

首条展示实际执行到的四类；上述正确命令未为汇报再次运行。Maven 384MiB／fork 512MiB，各 2 个可见处理器。AFT Java LSP 初始化失败为 PARTIAL，编译证据以 Maven 为准；未测覆盖率。没有本机全量门、生成工程 Maven 构建、服务／数据库或浏览器。测试与修复文档同批提交，CI 不为纯文档单独触发。完整金额 IT（含 SUM 真库告警）与部署待修复 SHA CI；推送后不查询或等待结果，由负责人通知，随即继续分组方案。账本仍 67／8／9。


<a id="ci-163-evidence-dir-fix"></a>
## 33. CI #163：证据目录未创建，且中止的运行仍报 PASSED

失败源码 **`93f9a7a713bf9547230a7427c822f3382664ef66`**，[verify #163](https://github.com/HoloNova/Software-IR/actions/runs/39886437029)。正式 Frozen full gate 成功，LanguageReferenceExamplesTest 4 项通过，#162 的修复有效。业务 IT 12 项里只有 MoneyBusinessConformanceIT 出错：

```text
java.nio.file.NoSuchFileException: …/ci-evidence/money-1a1667c6fee-25773/ExactAggregateWarningFixture.java.txt
  at DecimalSumChecks.installWarningFixture(DecimalSumChecks.java:26)
  at MoneyBusinessConformanceIT.execute(MoneyBusinessConformanceIT.java:39)
```

根因是写入方假设目录已存在：`BusinessSliceHarness` 构造时只记录 `evidenceParent/runToken`，该目录要到 `buildAndLocateJar → runMavenBuild` 里写 Maven 日志时才被创建，而告警探针副本在构建**之前**安装。因此探针资源本身没有缺失，应用既未构建也未启动，SUM 及告警断言一项都没执行；独立验证、CLI 与部署被跳过。

同一次运行还暴露证据报告不可信：`money-report.txt` 写着 `verdict=PASSED`、`checksPassed=4 checksFailed=0`，同时 `generatedProjectBuild=NOT_RUN`、`httpReadiness=NOT_RUN`。原因是该 IT 把 `writeReport()` 放在 `finally`，而未经检查的异常只向 JUnit 传播，没有进入 harness 的 `failure` 字段；`passed()` 只看 `failure` 与 `failedChecks`，于是中止的运行被写成通过，失败工作目录也会按“通过”删除。JUnit 本身正确报了 error，所以 CI 没有误绿。230 份正式 XML 中失败套件恰好 1 个（Money，1 error）。

**RED → GREEN：**新增 BusinessSliceHarnessEvidenceTest，三条都用真实实现而不是复制其逻辑。第一条在**不预先创建**的临时父目录上调用实际安装方法，先复现与 CI 同型的 `NoSuchFileException`（报告存 `target/ci-163-verification/red/`），修复后断言副本落在 `evidenceRoot()` 内且内容与资源一致；第二条让验证体在首次检查前抛错，断言异常原样抛出、`passed()` 为假、`verdict()` 为 FAILED、报告含该错误；第三条保证正常完成的运行仍判 PASSED，避免守卫把所有运行一律判失败。修复只改测试侧：写入方先创建自己的证据目录；harness 增加 `verify(Body)`，在把异常原样抛给 JUnit 之前记入同一失败通道；Money 与 Multiplication 两个“报告写在 finally”的 IT 改经它执行。没有吞异常、没有跳过 IT、没有移动探针安装顺序（那会让探针漏编进 jar），也没有放宽任何生产校验。

六类最新报告 **14 tests，0 failures／errors／skips**：BusinessSliceHarnessEvidenceTest 3、DecimalSumToolchainContractTest 4、LanguageReferenceExamplesTest 4、MoneyAggregateFixtureCompilationTest 1，以及 Money／Multiplication 两个 IT 各 1。**后两个在本机是 NOT_RUN（无参考库），只在编译与前置分支层面通过，不能记作真库验收。**

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=BusinessSliceHarnessEvidenceTest,MoneyBusinessConformanceIT,MultiplicationBusinessConformanceIT,MoneyAggregateFixtureCompilationTest,DecimalSumToolchainContractTest,LanguageReferenceExamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

Maven 384MiB／fork 512MiB，各 2 个可见处理器；AFT Java LSP 初始化失败记为 PARTIAL，编译以 Maven 为准；未测覆盖率。没有启动 MySQL、应用或浏览器，没有本机全量门与生成工程构建。RED、GREEN 与源码哈希封存在 `target/ci-163-verification/`。G8 条件 SUM 的真库部分（含严格／非严格会话的当前语句告警对照）仍待修复 SHA 的 CI；本轮不查询或等待结果，由负责人通知。


<a id="g8-grouped-sum-local"></a>
## 34. G8 第二批：引用单键精确分组分页（待同源码 CI）

基线 **`0bebfcdf8fcfb5219d40e53a8d026b0416cd9a46`**，负责人已通知该修复 CI 通过并确认[设计25](../design/12-feature-semantics-and-semantic-types.md#g8-grouped-sum-proposal)。本批不重新查询旧CI、不把旧结果当作新分组已验收；新真库和部署须覆盖本批实际提交。

**交付：**`sum Entity.amount by reference where predicate Page input.page,input.size else Error as result;`，只读query，固定Page<SumGroup<Ref<Entity>>>输出。仅直接非Optional／secret的Ref键及Decimal／Optional Decimal测量；键和测量实体／字段／结果类型正式绑定。空集零组、全空金额组为0，键固定升序、不补无账目分类；外层total为组数，越界页保留组数。COUNT DISTINCT与selectMaps共享一次筛选求值、显式只读RR事务／STATEMENT缓存，每条语句自己的告警均拒绝，无部分页。没有新增存储列、实体Mapper方法或通用JOIN。

实施时发现草案的any反方向不是既有能力。本批补齐“相关行身份＝根行直接Ref外键”的正式关联；支持交换等号两边，错误目标、非身份及无关联条件仍拒绝。新Core／目标载体也拒绝存储或input位置、secret／Optional键、伪造列／投影／Mapper／隔离／支持产物／分页，不能靠生成器临时防御兜底。17种目标计划损坏＋3种直接构造Core反例保留。原query／count／sum与正向any回归不放宽。

**RED → GREEN：**先取得新语法／类型／源往返和缺少目标计划的RED，再贯通各层。scope＋本人分类夹具先报不受支持的反向关联，而不是删掉归属筛选；新增合法／错误关联方向用例。真实生成类编译执行Long／UUID键、精确值／分页／错误载体、一次筛选求值；q0/q1/entryMapper和实体ExactAggregate／PageResponse不能与支持代码冲突。新增命名测试误用了保留字item，Maven因此在Parser处拒绝；已改测试为合法q1，不改变保留字或旧PageResponse的getTotal/getRecords接口。

最终按测试类去重 **27类236项，0 failures／errors／skips**：

| 模块 | suites | tests | 实际范围 |
| --- | ---: | ---: | --- |
| semantic | 8 | 109 | GroupedSum 19、RelationSlice 17、ScalarQuery 8、QuerySlice 28、Schema完整性8、结构覆盖1／敏感性26、声明往返2 |
| change | 2 | 6 | 分组与原单值SUM的增删改、实体／字段／类型及跨文件精确改名 |
| lowering | 3 | 56 | 分组21、单值SUM16、原IR硬化19 |
| generator | 8 | 47 | 分组13、单值SUM5、Statement告警6、真实探针编译与屏障1、实体不变性4、查询8／关联6、ledger4 |
| application | 6 | 18 | 分组合约3、精确HTTP断言2、冷目录证据4、旧SUM工具链4、语言示例4、八题内存生成1 |
| **合计** | **27** | **236** | **不累计先前重跑或三项早期依赖探针** |

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=GroupedSumSemanticsTest,RelationSliceSemanticsTest,ScalarQuerySemanticsTest,QuerySliceSemanticsTest,SoftwareSchemaCompletenessTest,StructureViewCoverageTest,StructureViewSensitivityTest,SirDeclarationWriterRoundTripTest,GroupedSumEditingTest,DecimalSumEditingTest,GroupedSumLoweringTest,DecimalSumLoweringTest,LoweredIrHardeningTest,GroupedSumRuntimeTest,DecimalSumRuntimeTest,ExactAggregateWarningRuntimeTest,MoneyAggregateFixtureCompilationTest,GeneratorEntityArtifactInvarianceTest,GeneratorQuerySliceContractTest,GeneratorRelationSliceContractTest,LedgerSumRuntimeTest,GroupedSumContractTest,GroupedSumChecksTest,BusinessSliceHarnessEvidenceTest,DecimalSumToolchainContractTest,LanguageReferenceExamplesTest,TopicSamplesTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

这批命令最初被上述item夹具错误阻断在Generator；修正后只补跑GroupedSumRuntimeTest及6类未完成应用测试，不重复已通过项。源XML及实际最后执行的类去重封存在 `target/g8-group-verification/green/`、索引local-result.json。早期3项依赖探针归档 `target/g8-group-investigation/DecimalGroupQueryFeasibilityTest.java`，实际实现测试已覆盖它们的契约，不再纳入正式CI增加重复执行。新夹具五处副本以源码哈希比对，避免当前批次分叉。

**真实IT NOT_RUN（本机）：**复用MoneyBusinessConformanceIT同一生成应用／构建。GroupedSumFixture只装入隔离生成目录，两个真实探针源码一起编译；计数完成后的可观察屏障通过实际编译拦截器执行验证，确认先有count结果、后等控制连接提交、再读页。新Money检查覆盖空集／全空组、相同名称不同身份、scope／本人分类、注入字符／字面匹配、混合正负大额、页界和两条语句各自的告警；严格／非严格会话均走真实Connector/J。快照并发与数据库告警仍须CI，不能用本地代理称为真库通过。生成代码及探针用javac实编，但没有本机生成工程Maven构建。

**资源／诊断：**Maven384MiB、fork512MiB，均2个可见处理器；一次仅一个定向构建。AFT Java LSP初始化失败为PARTIAL，编译以实际Maven为据；覆盖率未测量。本机无应用／数据库／浏览器和全量门；旧Windows安全目录门保留Linux CI，未通过放宽读取规则消除环境限制。Jackson2.19.4仅test-scope，以BigDecimal树读取真实HTTP金额，不引入生产依赖；第一方精确数值契约来源在g8-group-investigation。SQL列／别名／分页均来自校验后的计划或整数，用户值绑定，不拼请求字符串。

ledger的当前切片为本人显式半开区间／本人分类分页总额，220行SIR／4106行Java／24项能力。自然月／时区、预算历史、AVG／舍入、多键／零组、循环和UI仍未实现；账本67／8／9不变。配套文档与代码同批提交，正常触发同源码CI；不为纯文档造CI，不查询或等待结果，负责人通知。


<a id="ci-165-group-reference-coverage-fix"></a>
## 35. CI #165：新增分组键角色未进入全角色与跨文件契约夹具

失败源码 **`e2704365289018542fd5205d9aa624bd668b5f39`**，[verify #165](https://github.com/HoloNova/Software-IR/actions/runs/39912848967)。冻结门止于Semantic：TypedReferenceSiteContractTest.everyReferenceRoleIsCoveredByAtLeastOneBinding期待全部ReferenceRole，实际集合唯独缺GROUP_KEY_FIELD。后续Completion diagnostics（允许失败收集，步骤success不等于测试通过）继续执行，另发现ProjectVisibilityTest.everyImportSensitiveReferenceRoleHasARealPositiveAndExactMissingImportRefusal同样缺这一角色。独立解析首发226份与后续225份Surefire XML，首发恰1项失败，后续恰2项失败，均无测试error；没有把两个阶段的同一失败累计成3项。业务IT、独立验证、CLI及独立部署均被跳过，新的分组快照／SQL告警仍未验收。完整日志及原XML在 `target/ci-investigation/165/`。

根因是上批增加了GROUP_KEY_FIELD及独立分组测试，却没有同步两处枚举穷尽契约的真实源码。不是生产Resolve丢绑定，也不是应把新角色划成“仅本地”；上批236项定向漏选了这两道门。修复只改测试夹具／测试自己的AST遍历：

- 正式绑定共享源码增加以Account.owner分组的只读分页能力，所有原有18条绑定不变量一起检查该语句。新夹具还揭出allReferenceSiteAstNodeIdsMapBackToOriginalAst的手写遍历未认识AstSumGroupTypeRef／AstGroupedSumStep，已补全类型内Ref、测量、键、谓词、分页和错误引用，保留“每个绑定必须对应原AST”的断言。
- 跨文件测试的私有fixture副本增加Enrollment.measurement默认Money列及按现有student外键分组的能力；原始共享multi-course资源不改，不使其它计数／digest测试漂移。原有全角色导出正例与逐角色删import反例会自然覆盖GROUP_KEY_FIELD；没有改EnumSet.allOf断言或local排除名单。
- 新增无文件导出的同源契约，确认键绑定指向Enrollment.student，而非类型目标Student或其它同名字段；删掉准确的Enrollment import后，所有受影响位置各有且仅有一条SIR-SOURCE-VISIBILITY-001，包含分组键的精确SourceSpan，无Type／Validate级联，分析不改源树。

**RED → GREEN：**先运行原18项TypedReferenceSite契约与新增无导出跨文件契约：19项中恰2项失败（缺角色／没有分组键），XML存 `target/ci-165-verification/red/`。该诊断轮临时用maven.test.failure.ignore=true让Reactor收集两处失败，绝不以退出0当通过；修复后已去掉。新增共享分组夹具暴露AST遍历缺口的RED另封存red-ast/，随后补齐而不是跳过该审计。

最终去重 **6类53项，0 failures／errors／skips**：TypedReferenceSiteContractTest18、ProjectVisibilityTest的3个无导出方法、GroupedSumSemanticsTest19、ResolveOnceArchitectureTest7、GroupedSumEditingTest3、GroupedSumContractTest3。GREEN及实际方法索引在 `target/ci-165-verification/green/`／local-result.json；源码哈希随实际修复SHA绑定。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=TypedReferenceSiteContractTest,ProjectVisibilityTest#groupKeyRequiresItsOwnersImportBeforeAnyFileExport+scalarQuerySourcesRequireExplicitImportsWithoutFileExport+featureArgumentsRequireTheirOwnersImportWithoutFileExport,GroupedSumSemanticsTest,ResolveOnceArchitectureTest,GroupedSumEditingTest,GroupedSumContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

**边界：**ProjectVisibilityTest其余4个文件导出依赖方法（包括#165中的原全角色可见性门）本机NOT_RUN，仍由Linux CI实际执行；没有降低Windows不支持的安全目录相对读取要求，不能把3方法通过写成完整7方法通过。无本机全量门、应用／MySQL／浏览器或生成工程Maven构建，未测覆盖率。Maven384MiB／fork512MiB，各2个可见处理器；AFT Java LSP初始化失败为PARTIAL，编译依据真实Maven。生产规则／生成器／语言表面／异常协议均未改。修复测试与配套文档单批提交，推送后继续月度／预算范围核对，不查询或等待新CI，负责人通知结果；不提前上调67／8／9。


<a id="ci-166-fixture-mapper-cycle-fix"></a>
## 36. CI #166：分组验收夹具提前解析Mapper造成初始化环

失败源码 **`a6965315f1816731ce460c2c5647059f4b35ff30`**，[verify #166](https://github.com/HoloNova/Software-IR/actions/runs/39915698926)。正式Frozen full gate成功，原始XML确认TypedReferenceSiteContractTest18项、完整ProjectVisibilityTest7项均通过，上轮#165修复有效。Completion diagnostics按成功路径跳过。业务IT共12项仅MoneyBusinessConformanceIT失败；独立解析全部237份Surefire XML，恰1 failure、0 errors。其余11项业务IT通过，独立验证／CLI／部署被跳过，不算验收。

Money生成工程mvn clean verify通过，但Spring启动后退出，httpReadiness=NOT_RUN、7项前置／清理检查通过、1项就绪失败；分组SQL／快照／告警还没执行。`target/ci-investigation/166/conformance-evidence/money-1a1679b7fa4-64005/logs/spring.stdout.log` 的完整原因链是：MybatisPlusAutoConfiguration构造器收集Interceptor[] → GroupedSumFixture构造器需要SummaryEntryMapper → Mapper需要SqlSessionFactory → 工厂仍在创建，最终BeanCurrentlyInCreationException。不是路由404、依赖缺失、上轮契约没修好或数据库警告被放过。

根因在**测试专用夹具**：它既是RestController又实现Interceptor；此前的实际编译／手工构造屏障测试绕过了Spring装配，不能发现这个初始化依赖环。生产ExactAggregate本身不依赖Mapper，不需修改。第一方MyBatis-Plus v3.5.12自动配置源码第124—143行确认构造期间立即解析拦截器；Spring6.2.8 ObjectProvider合同确认通过提供者延后取得目标，固定版本源码及哈希留在 `target/ci-166-investigation/`。

**RED → GREEN：**扩展原MoneyAggregateFixtureCompilationTest，不复制示意夹具：编译真实money.sir和两份实际安装源码，用真实MybatisPlusAutoConfiguration、MapperFactoryBean、SqlSessionFactory及两个拦截器注册纯AnnotationConfigApplicationContext，显式禁止循环引用；DataSource若有访问即失败，不启动SpringApplication、HTTP、连接池或数据库。修复前原样得到BeanCurrentlyInCreationException而非编译／假数据源错误，XML封存在 `target/ci-166-verification/red/`。这不是全量生成工程Maven构建。

修复只把GroupedSumFixture构造参数／字段换为ObjectProvider<SummaryEntryMapper>，到合法告警HTTP方法执行时才getObject，屏障／固定SQL／路由和所有原断言保持不变。容器回归确认SQL工厂在循环引用禁用时完成，构造拦截器不实例化其惰性Mapper，原ExactAggregate和屏障确实被加入工厂；随后运行真实编译后的计数完成屏障，并确认工厂完成后能取得真实Mapper。测试上下文关闭，静态上下文桥惰性注册以免污染其它测试；未替换实际自动配置、MapperFactoryBean或拦截器收集路径。没有开启allow-circular-references、删插件、跳过探针、改生产Bean或改变事务／缓存／错误协议，也没有新依赖。

六类最新报告去重 **29 tests，0 failures／errors／skips**：MoneyAggregateFixtureCompilationTest1、ExactAggregateWarningRuntimeTest6、GroupedSumRuntimeTest13、BusinessSliceHarnessEvidenceTest4、GroupedSumContractTest3、GroupedSumChecksTest2。覆盖装配／延迟Mapper、真实屏障、当前Statement告警及普通ParamMap不受影响、Long／UUID结果、命名边界、冷目录安装和精确HTTP断言。GREEN和实际方法索引在 `target/ci-166-verification/green/`、local-result.json，源码清单绑定修复SHA。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=MoneyAggregateFixtureCompilationTest,ExactAggregateWarningRuntimeTest,GroupedSumRuntimeTest,BusinessSliceHarnessEvidenceTest,GroupedSumContractTest,GroupedSumChecksTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

没有本机全量门、生成工程Maven构建、应用／MySQL／浏览器，未测覆盖率；Maven384MiB、fork512MiB，各2个可见处理器。AFT Java LSP初始化失败为PARTIAL，以实际Maven编译及XML为据。纯容器装配通过不是HTTP／真库通过，Money的严格／非严格会话、count后提交新组的同快照检查及部署仍待修复SHA CI。月度／预算设计26草案不混入本批；修复测试与状态文档单批提交推送，不查／等新运行，负责人通知结果，账本67／8／9不变。


<a id="ci-167-grouped-read-budget-fix"></a>
## 37. CI #167：分组SQL预算把驱动会话探测算成业务读取

失败源码 **`74bd393d641d8bcf4776ceb30ee7a661773e6f70`**，[verify #167](https://github.com/HoloNova/Software-IR/actions/runs/39921191961)。Frozen full gate 成功，#165／#166 的修复都有效；独立解析 237 份原始 XML 只有 `MoneyBusinessConformanceIT` **1 failure / 0 errors**，其余 11 项业务 IT 通过，独立验证／CLI／部署因前序失败被跳过，仍不算验收。

Money 的生成工程 `mvn clean verify` 通过、应用就绪，真实分组业务已执行：分组金额、`total` 为组数、分页与越界、精确大额、本人／分类隔离、计数完成后提交新组仍读旧快照、严格与非严格会话下 count／data 两条语句各自的告警拒绝全部 PASS。唯一失败是新增的读取预算断言：

```text
FAIL group page executes exactly count plus current page
  (Trace[selectCount=7, selectDigests=[SELECT @@SESSION . `transaction_read_only`, …])
```

`GroupedSumChecks` 直接断言 `observed.selectCount()==2`，而 `MysqlRuntimeTrace.digestCounts()` 按 schema 收集**所有** SELECT digest；Connector/J 的连接初始化与只读探测本身也是 SELECT，因此 7 = 2 条业务读取 + 5 条会话探测。这是验收度量写错，不是生产多发查询；旧 `RelationSliceBusinessConformanceIT` 早已区分业务表读取与会话探针，新增分组用例没有复用这条策略。

按原始文件字节逐条提取这 7 条（不依赖读工具的长行显示）确认服务端把 `DISTINCT` 规范化成 **`DISTINCTROW`**，同时保留完整会话探测投影。该真实记录固化为 `src/test/resources/conformance/mysql/ci-167-grouped-read-digests.json`；报告文件本身未截断，上一轮“证据被裁剪”的判断已纠正。

**RED → GREEN：**先新建 `GroupedSumSqlBudgetTest`，28 项中 13 项失败，分别对应驱动探测误计数、两次计数／两次分页／只有探测被误放行、以及完整证据缺失；XML 存 `target/ci-167-verification/red/`。随后把 #167 的真实 7 条 digest 固化为回归，又单独取得 1 项 RED（`DISTINCTROW` 被当成不可识别语句），存 `red-server-digest/`，证明手写样例通过不足以代表真实服务端格式。

修复只动验收代码：

- 新增 `GroupedSumReadBudget` 按角色分类每条 digest：业务计数（`COUNT(DISTINCT …)` 且无 GROUP BY／ORDER BY／LIMIT）、业务分页（固定投影、`GROUP BY` 键、键升序、带 LIMIT／OFFSET）、会话探测（整个投影只能是已登记的系统变量，不得出现表、子查询、UNION 或字符串字面量）。要求**业务计数恰 1 条、业务分页恰 1 条、不可识别语句 0 条**，且 `selectCount` 必须等于实际捕获条数，防止 summary 与 log 不一致时侥幸通过。
- 把完整 `selectDigests`、`rawSelects`、事务计数及分类结果写入 `grouped-sum-query-trace.json` 并在报告里指明，失败时不再只有一行拼接文本；证据写失败会经既有 `verify()` 通道让运行判失败，不静默丢弃。
- 移除已修复写法的 `SELECT @@SESSION.transaction_isolation` 特例放行：只有登记表中的变量才算探测，未知 `@@` 变量、带表／子查询／UNION 的探测形状一律拒绝。

没有把期望值改成 7、没有放宽为 `<=7`、没有删断言或靠预热掩盖，也未改生产聚合、事务或告警逻辑。上一轮 #166 的 `ObjectProvider` 延迟解析和计数后屏障保持原样。

最终 7 类最新报告去重 **59 tests，0 failures／errors／skips**：GroupedSumSqlBudgetTest 30（含真实 #167 digest 与证据完整性）、MoneyAggregateFixtureCompilationTest 1、ExactAggregateWarningRuntimeTest 6、GroupedSumRuntimeTest 13、BusinessSliceHarnessEvidenceTest 4、GroupedSumContractTest 3、GroupedSumChecksTest 2。GREEN 与逐类方法索引在 `target/ci-167-verification/green/`、`local-result.json`。

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=GroupedSumSqlBudgetTest,MoneyAggregateFixtureCompilationTest,ExactAggregateWarningRuntimeTest,GroupedSumRuntimeTest,BusinessSliceHarnessEvidenceTest,GroupedSumContractTest,GroupedSumChecksTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
```

**边界：**本机没有全量门、生成工程 Maven 构建、应用、MySQL 或浏览器，未测覆盖率；真实读取预算、同快照与告警仍须修复 SHA 的 CI 实际执行，本机 59 项不能替代真库验收。AFT Java LSP 初始化失败记为 PARTIAL，编译以实际 Maven 与 XML 为据。月度／预算设计 26 草案仍未提交、不混入本批；修复测试与状态文档单批提交推送，不查询或等待结果，由负责人通知；账本仍 67／8／9。


<a id="ci-168-g2-g8-accepted"></a>
## 38. CI #168：G8 单值／分组与 G2 课程／借阅／预约验收闭环

验收源码 **`87cad8b7aaf176295ff08003b70192e4de1e1377`**，[verify #168](https://github.com/HoloNova/Software-IR/actions/runs/39927438145)。负责人通知通过后只做一次证据核验，不等待／轮询新运行。Frozen full gate、Business conformance ITs、Independent verification IT、Standalone CLI smoke、standalone-deployment 及实际冷部署／HTTP／持久化步骤全部 success；Completion diagnostics 按正常成功路径跳过，不需要重复执行全量门。

下载并保留实际产物：`surefire-reports`、`conformance-evidence`、`deployment-evidence`，本机副本在 `target/ci-investigation/168/`。240 份原始 Surefire XML 独立复算 **0 failures／errors**；TypedReferenceSiteContractTest 18、完整 ProjectVisibilityTest 7、LanguageReferenceExamplesTest 4、GroupedSumSqlBudgetTest 30 均通过，TopicSamplesOfflineCompilationTest 1 项通过，包含八题生成工程离线编译。这里的一项聚合 IT 是 JUnit 测试方法数，不能把它冒充下表逐项业务检查数；同批纯文档不重跑本机测试。

| 实际证据（conformance-evidence 内） | 业务 checks | 核实的行为 |
| --- | ---: | --- |
| `money-1a167f8ad5c-41865/money-report.txt` | **150 PASS／0 FAIL** | 单值SUM、空／全空、正负大额、scope／绑定；分组组数与稳定分页、空页／越界；count后另一事务提交新组、当前响应仍为旧快照；严格／非严格会话的当前Statement告警拒绝，失败不污染后续请求 |
| `library-borrowing-1a167f9ab80-29214/library-borrowing-report.txt` | **203 PASS／0 FAIL** | 读者最后名额竞争、首次／重复归还与计数一致；FIFO、普通借书不得插队、满额不跳过、本人／馆员取消、多副本并发；真实Book锁等待、领取／取消竞争、最后一步删除失败的四表回滚 |
| `course-selection-1a167f85f74-41055/course-selection-report.txt` | **151 PASS／0 FAIL** | 学生锁防同时进入冲突课程、半开节次区间；任意直接先修集合与可信完成记录、发布后冻结；真实等学生锁后重查冲突、选／退晚失败回滚 |
| `multiplication-1a167f965e7-1362/multiplication-report.txt` | **72 PASS／0 FAIL** | 既有精确乘法／数量、名额与预约锁组合回归 |

上述报告均写 `generatedProjectBuild=PASSED` 和 `httpReadiness=READY`，不是环境前置分支 NOT_RUN。运行的 SIR digest 与验收当时的源按LF规范化后逐字节核对：library `fb9d99762b792fcc970937e51fcedc0745d5f42d4fcacdf73d0ff91fbbd500d7`、course `5acc49c8745946ebc5683b8cfca781fa608e48400fdaee83e1f970940d1c12a6`、money `41e252b3b679b677ad9002f415b09b5d7a24de2e5d82df709016ea2794f294b4`。实际题目源码与Money通用夹具的身份区分保留，不把Money覆盖等同于ledger全部HTTP业务已验收。

**#167预算修复的真实结论：**`money-…/grouped-sum-query-trace.json` 包含 countReads=1、pageReads=1、sessionProbes=1、unexpectedReads=0、selectCount=3、readOnlyTransactions=1。服务端COUNT仍为DISTINCTROW规范形。通过的是“一次业务计数＋一次业务分页，其余只能明确会话探测”的原约束，绝不是总SELECT必须7或2；连接池冷／热影响探测次数，不影响业务预算。原始语句与分类完整保留。

**部署：**`deployment-evidence/report.json` 的 commit 与 runUrl 精确绑定本SHA及本运行。deployment、httpSmoke、persistence、privileges、swagger、contract、missingVolume、business、update、dataUpdate、freshChain、cleanup、decimalShape 全为 PASSED，覆盖精度漂移拒绝及恢复和新实例迁移链。其 `notRunByContract` 明列 interruptedMigration、schemaDriftOtherThanDecimalShape；这两项不在本次合同内，不把它们写成通过。

**结论／账本：**G8条件Decimal SUM及引用单键分组分页验收闭环；G2实际library／course的借阅最多5本、预约已借出图书（严格FIFO本人扫码领取，不自动分配）、课程时间冲突、全部直接先修四项由待真库△转✓。84条账本由 **67／8／9 → 71／4／9**（完整／带折扣／未写出）。没有新增需求或抬高G9逐题保护、商城多商品／退库存的完成度。该轮验收时自然月、预算提示、AVG、多键／零组、日期差罚款和G5仍未实现；分组不能冒充自然月。验收记录当时与设计26草案保留本地，现随第39节对应代码合并提交，不为纯文档单独触发CI。


<a id="g8-calendar-month-local"></a>
## 39. G8第三批：用户时区自然月与指定当前预算（已实现，待同源码CI）

已验收基线 **`87cad8b7aaf176295ff08003b70192e4de1e1377`**（第38节）。负责人已确认每位用户自己的时区＋指定budgetId的当前规则，不采用UTC月或每期预算表；[设计26](../design/12-feature-semantics-and-semantic-types.md#g8-calendar-month-scope)与语言参考5.12已同步。#168不包含新月报，不能挪用为本批真库证据。下列本机结果来自分轮、受影响定向测试；只在新增变更／明确失败或未执行时补跑，不再次跑全量。

**交付：**`timeZone`无参数文本约束，目标JDK区域数据库校验UTC／带斜线区域，DTO／default／最终写入候选一致。ledger本人偏好GET／POST `/api/me/time-zone`，不接受他人ID，旧用户未设置就拒绝月报，不回退UTC。`month input.month in account.timeZone as since,until else BadPeriod;`按JDK分别求两个月首当地最早有效时刻，得到两个UTC Moment。四位年月、缺值／null、无效区域及两个端点的DATETIME(6)范围都走声明错误；只读RR、零SQL本地步骤复用SUM／分组，不增加第二套统计逻辑。预算与分类都检查本人归属、严格total > 当前monthlyLimit，等额不超；修改偏好／账目／上限会改变历史重查，不冻结历史或新增期间存储。

**先RED再实现：**时区规则与新语法、源往返、目标计划、实际生成月界／ledger服务先失败后转绿。未定义表达式已有诊断时，Normalize新节点曾NPE，现保留原诊断并安全归一化；不放开非法引用。独立Core及目标IR拒绝错误输出／定义归属、参数类型、secret、未声明错误、非只读快照、篡改边界／SQL预算及缺失校验／UTC绑定支持。MONTH_ERROR同步全引用角色夹具和AST遍历，不重演#165；新跨文件方法精确核对移除实际owner import后的SourceSpan。测试自己的Snapshot helper原先只读固定FILES，加入contracts模块后须也把该模块送入解析，不修改产品源集合规则。

**JDBC实质缺口：**旧Timestamp路径把1000年UTC字段转成999-12-27，1500年也有混合日历偏移；该RED封存于 `target/g8-month-verification/jdbc-red/`。包含month的工程按明确计划生成UTC Instant TypeHandler：JDBC4.2 LocalDateTime字段读写，不用Timestamp或JVM默认时区；实际MyBatis-Plus自动装配收集该组件。无month软件不增此代码，不能在IR中删掉标记却称支持目标日期。6项实际生成处理器用例覆盖1000／1500／1582、现代微秒、9999、ResultSet／CallableStatement及NULL，验证真实JDBC方法和参数。该6项在UTC／Pacific/Honolulu两个JVM执行各通过，报告独立保存在jdbc-utc／jdbc-honolulu，**按类去重仍只计6项**；代理协议不被称作Connector/J真库成功。

**最终最新XML按类去重：33类247 tests，0 failures／errors／skips。**

| 模块 | 实际测试类范围 | suites | tests |
| --- | --- | ---: | ---: |
| semantic | CalendarMonth10、TimeZone16、全角色18、Schema完整8、结构覆盖1／敏感28、往返2 | 7 | 83 |
| change | CalendarMonthEditing增／删／改／精确改名 | 1 | 3 |
| lowering | CalendarMonth14、旧DecimalSum16／GroupedSum21 | 3 | 51 |
| generator | 月界26、JDBC6、ledger月报5、时区2／PATCH1、旧DTO5／可选3／Money7／分组13／ledgerSUM4／Mapper4、真实探针装配编译1、架构3／边界6 | 14 | 86 |
| application | CalendarLedger合同4、冷目录证据5、旧分组合约3／HTTP断言2、SUM工具链4、语言示例4、ProjectVisibility仅新无导出方法1、八题内存生成1 | 8 | 24 |
| **合计** | **不把旧重跑、JDK先期探针或双时区重复执行累计** | **33** | **247** |

索引 `target/g8-month-verification/local-result.json` 记录每类实际方法及报告时间；green目录封存最新原始XML，RED按time-zone／calendar／lowering／ledger／jdbc分目录保留。源码哈希另存source-manifest，提交后绑定实际SHA。ProjectVisibility这里只执行 `monthErrorRequiresItsActualOwnersImportBeforeAnyFileExport`，不能把它冒称全类或Linux文件导出门通过；该完整门仍由CI执行，不降低Windows安全读取要求。

本批还纠正了新测试的三类假设：PATCH必须有version且字段Optional匹配，空changes仍会执行原版本条件写（不改变时区）；公开错误码是BadPeriod等SIR名而非Java的BadPeriodException；历史写入走既有Crud `/api/entries`的201及详情GET，不存在另一个CalendarInsert控制器。均改测试遵守原协议，没有放宽业务或输入边界。语言参考新增sir-declarations仍经过完整生成门，不以普通文本绕过#162类问题。

最近真实执行的收尾命令（仅对应子集，247项总数以分轮最新XML为据；不为凑一条命令重跑）：

```powershell
$env:MAVEN_OPTS='-Xmx384m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=CalendarLedgerContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=GeneratorArchitectureRegressionTest,GeneratorProductionBoundaryTest,DecimalSumLoweringTest,GroupedSumLoweringTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2'
mvn -B -q -o -pl sir-toolchain-application -am test '-Dtest=CalendarUtcJdbcRuntimeTest,MoneyAggregateFixtureCompilationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -Duser.timezone=UTC'
mvn -B -q -o -pl sir-generator-spring-boot -am test '-Dtest=CalendarUtcJdbcRuntimeTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xmx512m -XX:ActiveProcessorCount=2 -Duser.timezone=Pacific/Honolulu'
```

**真实HTTP／MySQL NOT_RUN：**沿用MoneyBusinessConformanceIT同一生成工程及既有构建／重启轮次（原严格启动和非严格重启都会构建，并非只构建一次），纳入与实际ledger逐声明AST一致且必须存在的User／偏好／分类／预算／月报声明。CalendarSnapshotFixture与旧告警／分组两个探针一起真实编译和装配，不在本机启动Web或连接数据库；User查询完成后可观察屏障也由编译后的拦截器验证，不在Mapper构造时解析依赖。CI将验证：

- 纽约月首含／下月首不含及微秒，空集，1000下界、1500年通过实际Crud写读再按月查、9999最后完整月；非法月份／未设置／非法时区及伪造请求zone／userId。
- 可信Bearer本人偏好、预算及分类归属，等额不超、当前上限变更改变历史重查；不隐式按分类方向做净支出。
- User已读后暂停、另一个HTTP请求提交时区，再放行：在途月报用原偏好，下一请求用新偏好，不靠压力测试碰巧交错。
- 旧严格UTC环境及原非严格重启的+05:30实际会话／Pacific/Honolulu JVM；通过真实DataSource读取time_zone、sql_mode、UTC差秒数和JVM区域，两个模式均核对实际偏差（0／19800），不凭环境变量存在冒称覆盖。旧每语句告警、SQL预算、分组计数后屏障保留，无新增应用构建。

**资源／边界：**Maven384MiB／fork512MiB，各2个可见处理器，一次仅一构建。AFT Java LSP初始化失败为PARTIAL，不能据此宣称无诊断；编译依据实际Maven。覆盖率NOT_MEASURED；本机全量、完整Linux文件导出、生成工程Maven构建、数据库／应用及部署均NOT_RUN。没有UI改动或浏览器／UI单测。旧账号新可空time_zone列需应用schema变更并主动设置，不猜默认、不自动重写旧历史日期。

TopicSamplesTest实际内存输出ledger **281行SIR／4609行Java／28项能力**。月报／预算两条从✗转已实现待CI△，账本 **71／6／7**，不是新验收✓；AVG／多键／零组、日期差、G5和图表／导出未做。#168验收文档与本批代码同一提交推送，不独立造文档CI，不查询／等待新运行，由负责人通知结果。
