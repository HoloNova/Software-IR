# 验证说明

本文件说明**怎样验证**、**证据怎么写**，以及目前有哪些证据。能力边界看 [项目状态](../PROJECT_STATUS.md)。2026-10-14 之前累积的资格报告和覆盖清单已移到 [history/qualification](../history/qualification/)，其中的数字和已删除机制的描述只代表当时。

## 1. 验证分三层

| 层 | 做什么 | 在哪里跑 |
| --- | --- | --- |
| 定向测试 | 单模块、`-Dtest=...`，覆盖本次改动影响的类 | 本机（受限资源） |
| 全量闸门 | `mvn -o clean verify`，冻结形式与完成形式（`-Dmaven.test.failure.ignore=true`）各一次 | GitHub CI |
| 业务／独立验证 IT | 真实 MySQL 8.4 + 生成工程构建／启动 + HTTP 断言 | GitHub CI |

本机资源很小（见 [AGENTS.md](../../AGENTS.md)「测试资源约束」），所以重活交给 CI；Claude 会话环境的约定见 [CLAUDE_EXECUTION_POLICY](../CLAUDE_EXECUTION_POLICY.md)。同一批改动只在 CI 上验证一次，本机不重复跑全量。

## 2. CI 工作流（`.github/workflows/verify.yml`）

每次 push 触发，一个 job 内依次：在线预热 Maven 仓库 → 预热生成工程依赖 → 冻结全量闸门 → 完成全量闸门 → 三个业务场景 IT（查询／写入／关系，真实 MySQL 8.4）→ 独立验证 IT → 上传证据。

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

**最近一次已核验的 CI 运行：** [verify #88](https://github.com/HoloNova/Software-IR/actions/runs/38901782044)，受测提交 `fe40836`（`main`，feat(cli): kcg scaffold dual-token），success。冻结与完成两条全量闸门、业务场景 IT（含新的 `DualTokenScaffoldBusinessConformanceIT`：用脚手架输出作夹具，逐条验证双 Token 七条行为，含过期的访问令牌与 6 路同时刷新的竞争）、独立验证 IT、Standalone CLI smoke 均成功；核对时读了业务场景 IT 的日志，确认新 IT 运行并全部通过。

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
