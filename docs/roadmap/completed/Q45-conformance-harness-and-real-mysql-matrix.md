# 已完成工作单：Q4+Q5 Conformance harness 恢复与真实 MySQL 矩阵（已合并）

- 状态：`DONE`（项目负责人 2026-09-18 确认通过）
- 归档：2026-09-18 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 验收记录：负责人 2026-09-18 对执行结果回复“确认”，即验收通过。验收依据：
  1. `sir-toolchain-application` conformance 包编译错误 **56 → 0**，两个 POM 包级排除全部删除；
  2. 默认构建两个闸门均 **BUILD SUCCESS**，合计 **478 run / 0 fail / 0 error / 5 skip**；
  3. 真实 MySQL 8.4.11 参考环境上 **`Terminal result: Qualified`**，五个场景全部 `passed=true`，连续两次运行可复现，运行后 workParent 为空且 schema 不存在；
  4. 真跑中暴露并修复的 8 处实现缺陷与 3 处 work-root 清理缺陷均有运行证据支撑；
  5. 报告语义自相矛盾（提前写 `terminal=`）已修正。
- 所属阶段：路线图阶段 4（Application conformance harness）+ 原阶段 5 的矩阵验证；属 **G0：现有链路资格收口**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 4、`docs/qualification/TEST_COVERAGE_INVENTORY.md` 第 5 节、`docs/qualification/CURRENT_QUALIFICATION.md` 第 6 节第 1 项
- 前置工作：Q1、Q2、Q3 已完成并归档（[`completed/`](completed/)）
- 授权来源：项目负责人 2026-09-18 裁定 **Q4 与 Q5 合并为一张工作单**（原 D6 推荐项），并确认容器化 MySQL 可作为资格记录的参考环境元组
- 版本快照：按负责人 2026-09-18 决定**暂不提交 Git**，与 Q1–Q3 合并到 G1 完成后统一提交

# 当前唯一工作单：Q4+Q5 Conformance harness 恢复与真实 MySQL 验证

- 状态：`DONE`（见上方归档头）
- 所属阶段：路线图阶段 4（Application conformance harness）；属 **G0：现有链路资格收口**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 4、`docs/qualification/TEST_COVERAGE_INVENTORY.md` 第 5 节、`docs/qualification/CURRENT_QUALIFICATION.md` 第 6 节第 1 项
- 前置工作：Q1、Q2、Q3 已完成并归档（[`completed/`](completed/)）
- 授权来源：项目负责人 2026-09-18 指示“允许直接安装未安装的服务” + “Q4 与 Q5 合并成一张工作单”
- 版本快照：按负责人 2026-09-18 决定**暂不提交 Git**，与 Q1–Q3 合并到 G1 完成后统一提交

## 目标

1. `io.kcg.sir.application.conformance` 重新参加 **testCompile** 且**零编译错误**，从而使 POM 的 compiler 排除可以被删除。
2. 保留**显式 opt-in** 语义：未启用时不连库、不建目录、不写证据，并如实报告 `NOT_RUN`；启用且环境完整时必须产出真实的终态结果（`QUALIFIED` 或 `FAILED`），不得以任何方式伪造。
3. 对着**真实 MySQL 参考环境**跑出终态结果，并按事实登记（不预设必须是 QUALIFIED）。

## 起点证据（2026-09-18，Linux，全部来自真实命令）

### 编译缺口：**56 个不同错误 / 13 个文件**（2026-09-18 复测，已修正早前的错误数字）

**方法学教训（两次都要记住）**：

1. **javac 对含语法错误的文件不再报告符号解析错误**。早期“缺口只有 4 处”的结论因此错得很大。
2. **javac 默认 `-Xmaxerrs 100`**。早前报告的“100 个错误”正好等于这个上限，是**被截断的普查**，不是真值。必须显式提高上限后再统计。

本轮已修的根因（均有直接证据）：

- `OwnedRunDirectory.java` **整个 package 声明与 import 块缺失**：文件从 `public final class OwnedRunDirectory …` 开始，因此在**默认包**中编译，产生歧义的 `duplicate class` 与 36 个符号错误。已补 `package` + 13 个 import（其中 `FileAlreadyExistsException` 必须来自 `java.nio.file` 而非 `java.io`，另需 `AccessDeniedException`）。
- `StrongFileIdentity.java` 用的是 `WinBase.FILE_FLAG_BACKUP_SEMANTICS` / `FILE_FLAG_OPEN_REPARSE_POINT`，但这两个常量在 **JNA 5.17.0 中声明于 `WinNT`**（已用解包 class 文件核实：`FOUND in: com/sun/jna/platform/win32/WinNT.class`）。已改为 `WinNT.FILE_FLAG_*`。
- `SpringBootTargetConformanceIT` 缺 9 个 import（`Path`、`IOException`、`BasicFileAttributes`、`MessageDigest`、`NoSuchAlgorithmException`、`LinkOption`、`Files`、`FileAlreadyExistsException`、`NoSuchFileException`）。

修完上述三项后，**剩余 56 个错误全部是真正缺失的类型与方法**（符号 → 出现文件）：

| 缺失符号 | 类型 | 出现位置 |
|---|---|---|
| `Result`、`MaterializationEvidence`、`FileDigest`、`Delta`、`PlanFamily` | 类型 | `ManifestTransactionVerifier` |
| `fail`、`expectedFamilyFor`、`verifyCurrentPointsToB1`、`verifyOnDiskFile`、`verifyTransactionGateClean`、`writeEvidence`、`rawConnection` | 方法 | `ManifestTransactionVerifier`（`rawConnection` 另见 `MysqlSchemaInventory`） |
| `RuntimeOps`、`ScenarioEndpoint`、`JdbcVersionInspection`、`HarnessJdbcVersion` | 类型 | `RealRuntimeOps` |
| `sanitizedMessage`（三个重载）、`failScenario`、`findBuiltJar` | 方法 | `RealRuntimeOps` |
| `DiskTree` | 类型 | `EvidenceSecretScanner` |
| `containsBytes`、`hasNoPendingStreams`、`reproveRoot`、`directoryPathSet`、`finalizedAndReportPathSet`、`collectRegularFiles` | 方法 | `EvidenceSecretScanner`（`containsBytes` 另见 `MysqlControlSession`） |
| `ControlSession`、`MysqlControlConfiguration` | 类型 | `ControlSessionFactory`、`GuardHook`、`RealRuntimeOps` |
| `isConnectionOriginal`、`connectionIdUnchanged`、`lockHeldByThisConnection`、`close` | 方法 | `ControlOwnershipGuard`、`MysqlControlSession` |
| `MysqlRuntimeJdbcUrl` | 类型 | `ChildEnvironmentBuilder`（另见 IT 的 P0-C2 代码） |
| `validateTableIdentifier` | 方法 | `MysqlSqlConstructionTest`（应属于 `SchemaSqlRenderer`） |
| `awaitFsutilFileId` | 方法（测试 helper） | `StrongFileIdentityTest` |

### 新发现：包内至少有 3 个可运行的单元测试类被 surefire 排除隐藏

`ConformanceFixtureSqlTest`、`MysqlSqlConstructionTest`、`StrongFileIdentityTest` 均匹配 surefire 默认 include 模式，因此包级 surefire 排除会**隐藏真实单元测试**。**D2 修正**：编译通过后应同时删除 surefire 包级排除（`SpringBootTargetConformanceIT` 因 `IT` 结尾天然不在默认 include 内，保持 opt-in），并以实际运行证据验证两类行为。

另：`ConformanceFixtureSqlTest` 依赖资源 `/conformance/mysql/campus-market-ddl.sql`，而 `src/test/resources/conformance/` **不存在**——该 fixture 必须随 harness 一起补齐（或如实登记为缺口）。

### 开发工具：不带副作用的编译探针

`/root/kcg-conformance/probe-conformance-compile.sh`（仓库外）临时移除 POM 的 `testExcludes` 并加 `-Xmaxerrs 5000`，编译后**用 trap 无条件恢复 POM**，因此**仓库的 POM 与构建始终保持绿色**，而进度仍可度量。

### 本轮已完成 / 待完成

**里程碑（2026-09-18）：conformance 包已重新参加编译与运行。**

| 事件 | 结果 |
|---|---|
| 编译错误 | **56 → 0**（`test-compile exit=0`） |
| POM 两个包级排除 | **已删除**（compiler `testExcludes` + surefire `excludes`，D2 修正） |
| 新增 fixture | `src/test/resources/conformance/mysql/campus-market-ddl.sql`（goods 表 + `idx_goods_seller`，无 `USE`/`CREATE DATABASE`/`DROP`） |
| 隐藏测试 | `MysqlSqlConstructionTest` 9、`ConformanceFixtureSqlTest` 1、`SpringBootTargetConformanceITTest` 2、`StrongFileIdentityTest` 1 — **13/0/0/0 全绿** |
| 闸门①（冻结全量 `-o clean verify`） | **BUILD SUCCESS，10/10 模块**，合计 **478 run / 0 fail / 0 error / 5 skip**（原 465/0/0/5，+13 正好是上述 4 个类的 13 项） |

### 两个隐藏测试给出了精确规格（已按规格实现）

- `SpringBootTargetConformanceITTest.invalidRuntimeEndpointReturnsNotRunBeforeSuiteConstruction`：当控制 URL 选择了一个 schema（`jdbc:mysql://localhost:3306/other_schema`）时，`runFullConformanceSuite()` 必须**不抛异常**而返回 `NotRun(PRECONDITION, "RUNTIME_JDBC_URL_INVALID")`。已实现：先做运行库端点前置校验，失败即 fail closed 为 NOT_RUN。
- `…harnessDriverVersionLookupNeverOpensFallbackJdbcConnection`：IT 必须有私有静态 `resolveHarnessJdbcDriverVersion(String)`，它**绝不建立任何 JDBC 连接**（`connectCalls==0`）、仍会调用 `acceptsURL`（≥1 次）、无法确定时返回 `"unknown"`。已实现：经 `DriverManager.getDriver`（只调 `acceptsURL`）+ 驱动包 `Implementation-Version`，否则 `major.minor`，否则 `unknown`。

### 另一处重要成果：`JdbcVersionInspection` 无需新建类

发现 `TargetDependencyInspector`（已存在，130 行）就是它的实现（`inspect(loweredModel, pomPath, harnessDriverVersion)` + `versionsAgree()`）。已改为**复用它**并保留草稿的“写证据 + 失败即报”契约；harness 驱动版本内联从控制连接元数据读取。**未新增重复类**。

### 验证方法学教训（第三次）

`mvn -Dtest='A+B'`（加号分隔）会**静默地一个测试也不跑**（配合 `-Dsurefire.failIfNoSpecifiedTests=false` 时返回 BUILD SUCCESS）。必须用逗号分隔。我本轮一度因此拿到一个“假绿”，已在发现后改用逗号重跑（13/0/0/0 才是真凭据）。已复核：Q1–Q3 已验收的数字均来自真实跑数，不受影响。

### 真跑结果（2026-09-18，MySQL 8.4.11 容器）——**QUALIFIED**

**终态：`QUALIFIED`，五个场景全部 `passed=true`，且连续两次运行均可复现。**

```
scenario IG-ACTOR      passed=true
scenario IG-READONLY   passed=true
scenario APPLY-UPDATE  passed=true
scenario APPLY-CREATE  passed=true
scenario APPLY-DELETE  passed=true
terminal result: Qualified
```

运行后核验：workParent 为空、`kcg_conf_run` schema 不存在（清理真实发生）；证据树含每场景的 maven/spring 日志、`target-dependency-inspection.txt`、`ig-manifest-verified.txt` / `apply-transaction-verified.txt` / `closure-external-verified.txt` / `delete-absence-verified.txt`，外加 `schema-inventory.txt` 与封存后重扫的 `report.txt`。

**真跑共暴露并修复 8 处实现缺陷**（全部由运行证据驱动，不是猜的）：

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `CONTROL_OWNERSHIP_NOT_PROVED_SCHEMA_ABSENCE` | 守卫在取锁之前调用，而它验证的正是锁 | 先取锁再守卫 |
| 2 | `No database selected`(1046) | `Connection.setCatalog` 在 MySQL 8.4 只会置位驱动侧 catalog，不保证发 `USE` | marker 表用 schema 限定名；fixture DDL 用一次显式 `USE`（schema 名经 `SchemaName` 校验 + 反引号） |
| 3 | IG-READONLY readiness 等不到 200 | `ScenarioEndpoint` 期望 `/api/search-goods`，而 fixture 的能力名是 `ListAvailableGoods` | 按负责人裁定 (a) 改 fixture：能力改名为 `SearchGoods` 并加 `title` 输入 |
| 4 | APPLY-CREATE `SIR-CHANGE-SCOPE-101: candidate must add exactly one new declaration; got 2` | 我加在 candidate 的 input 是第二个新增声明 | input 下沉到 base，candidate 只新增能力 |
| 5 | `SIR-CHANGE-SCOPE-101: existing declaration SymbolId order changed` | base 与 candidate 的声明顺序不一致 | candidate 由 base 文本派生，保证顺序一致 |
| 6 | APPLY-CREATE 期望 200 得 404 | minimal base 缺 `PublishGoods` | 重建 minimal/candidate 为 actor + price + seller 形态（与 `campus-market.sir` 同构） |
| 7 | 500 `Field 'price' doesn't have a default value` | 共享 DDL 的 NOT NULL 列与 actorless/无 price 的实体不兼容 | 改为让所有会插入的场景都提供 price 与 seller（DDL 保持 NOT NULL，满足 fixture 守卫测试 `seller_id BIGINT NOT NULL`） |
| 8 | fixture DDL 语法错误 near 'a…' | 注释块里含分号，先按 `;` 切分会把注释残片当 SQL | 先剥 `--` 注释再切分语句 |

**work-root 清理的三处缺陷**（一度导致 `WORK_ROOT_CLEANUP_NOT_PROVED`，物理上却已清空）：
① 子 Maven 构建在已注册目录里写入未注册产物，注册目录因此删不掉；
② 目录身份用 `mtime/size` 这类随子项变化的不稳定信号；
③ 未注册内容的清理顺序在注册项删除之后。
修复为：先清未注册内容（跳过注册路径）→ 再按身份校验删注册项 → 释放 marker → 删除根目录（根目录身份只比 fileKey，不比时间戳，并保留"根必须为空"的兜底校验）。

**报告语义修正**：`report.txt` 原先在证据定稿前就写 `terminal=`，会出现"报告写 Failed、实际 QUALIFIED"的自相矛盾；现改为 `terminal=determined-after-evidence-finalization` + `scenario-outcomes-recorded=5/5`，不再提前断言终态。

**运行命令**见 1.6；凭据只在仓库外 `/root/kcg-conformance/env.sh`（chmod 600）。

### 剩余工作（唯一一大块）

### 本轮进展（编排实现，2026-09-18）

| 项 | 状态 |
|---|---|
| `ScenarioMaterialization` 扩展（b0Receipt/b0Manifest/outcome/loweredModel） | ✅ 完成，5 个 `materialize*` 已填入；loweredModel 经 `SirCompilation.CompilationSnapshot.loweredModel()`（不再新写 lowering） |
| `EvidenceWriter` 所有权生命周期 | ✅ `create(root, inventory)`、`openStream` 建文件即 `recordPending` + 关闭即 `finalize`、`publishReport`(REPORT_TEMP+finalize)、`sealReport`(unsealed→sealed) |
| `EvidenceDirectory` 链证明 | ✅ `attachRootProof` 后 `reproveRoot()` 走 `EvidenceRootProof.reprove()`（含祖先链），无证明时才回退属性检查 |
| `ConformanceSuite.orchestrate` | ✅ 全阶段实现（+708 行）：证据根→控制连接/身份→锁→缺席→CREATE→MARKER+DDL→workRoot→逐场景→清理→库存→DROP→缺席证明→释放锁→workRoot 清理→扫描→发布+封存报告+重扫→终态 |
| `ConformanceStateMachine` 契约修正 | ⚠️ **需负责人知悉**：原规则只允许相邻前进，无法表达“同一状态重复处理多个场景”，而 Javadoc 自己说 self-recheck 就是为此。已新增两条允许：①从场景块内重入 `MATERIALIZE`（下一个场景）；②从场景块内跳到清理起点 `MYSQL_LOCK_RECHECK`（失败仍必须走清理）。**其他阶段仍严格相邻，不跳步** |
| 编译 | ✅ `test-compile exit=0` |
| 模块测试 | ✅ **146/0/0/5**（无回归） |

**仍待完成**：IT 侧 `ConformanceSuiteContext` 装配（`ConformanceEnvironment` 21 参 + `SecretCatalog` + redactor/scanner + `MysqlControlSession` 打开 + child env + Maven/Java 路径）；然后对着 MySQL 8.4.11 真跑并如实登记终态；最后全量①②闸门 + 文档同步。

**已定位一个必须先解决的设计缺口（新增）**：`ScenarioMaterialization` 只携带 `scenario / outputRoot / stateRoot / b1Receipt / b0Graph / outputManifest(Object)`，而 `orchestrate` 实际需要：
- `ManifestTransactionVerifier.verify(...)` 的输入：`MaterializationEvidence`（IG 需 `ExecutionManifest`；APPLY 需 **B0 receipt + B0 `ExecutionManifest` + B1 receipt + B1 `ChangeOutputManifest` + `ChangeApplyOutcome`**）；
- `RuntimeOps.inspectTargetDependency(...)` 的 `Optional<SpringBootLoweredModel>`。

这些值在 `materialize*` 方法内部**已存在**（来自 `ToolchainResult.Success` / `ChangeApplyResult.Applied`）但未被暴露。因此下一步需**扩展 `ScenarioMaterialization`**（测试侧 record，允许修改）并在五个 `materialize*` 中填入，而不是在编排层重新推导。

1. 扩展 `ScenarioMaterialization`（b0Manifest、outcome、loweredModel，依场景取 Optional）；
2. **真实编排**：`ConformanceSuite.orchestrate` 的全部阶段（前置校验 → SCHEMA_LOCK → 缺席证明 → CREATE → MARKER+LOCK → fixture DDL → 库存 → 逐场景（materialize→verifier→build→Spring→HTTP→DB）→ 清理 → DROP → DROP 后缺席证明 → 终态），以及 IT 的 `ConformanceSuiteContext`（17 个组件）装配（`ConformanceEnvironment` 21 参、`SecretCatalog`、`StreamingSecretRedactor`、`ChildEnvironmentBuilder`、Maven/Java 可执行文件路径与离线仓库）；
3. **对着真 MySQL 8.4.11 容器跑矩阵**，如实记录终态（QUALIFIED / FAILED / NOT_RUN）；
4. 全量①②闸门复跑 + 文档同步 + 状态 → `AWAITING_ACCEPTANCE`。

**QUALIFIED 的硬门（由 `ConformanceRun.terminalResult()` 固定，已核实）**：五个场景按**规范序**全部通过 + 无失败 + schema DROP 与缺席证明 + 锁释放 + workRoot 清理 + 证据扫描干净 + 证据最终发布；任何一项缺失即 FAILED（或边界前的 NOT_RUN）。

**已完成（本轮）**：

| 文件 | 内容 |
|---|---|
| `ControlSession.java`（新） | 控制会话接口，方法集**完全从调用点推导**（身份/告警锁/schema 生命周期/owner marker/所有权检查/rawConnection/close） |
| `MysqlControlConfiguration.java`（新） | `record(jdbcUrl, username, password)` + 非空校验 |
| `MysqlControlSession.java` | **补全被截断的实现**（+241 行）：`GET_LOCK`/`IS_USED_LOCK`/`RELEASE_LOCK`（锁名来自 `AdvisoryLockKey.derive`，NULL 一律当作未获取）、`recheckLockAndIdentity`（四条件不全即抛 `CONTROL_OWNERSHIP_LOST`）、`schemaExists`（参数化 `INFORMATION_SCHEMA`）、CREATE/DROP DATABASE（经 `SchemaSqlRenderer`）、owner marker 表 `kcg_conformance_owner`（经 **JDBC catalog API** 切库，**从不发 `USE` SQL**）、`CONNECTION_ID()` 不变、`close()` |
| `MysqlObserver.java` | 新增 `validateTableIdentifier`（语义由**既有测试** `MysqlSqlConstructionTest` 完全确定：接受 1–64 字符 `[A-Za-z_]` 开头，拒绝空/超长/连字符/数字开头/空白/反引号/引号/分号/注释序列；null → NPE），并在 `queryRows`/`countRows` 拼接前**实际调用**它 |
| `MysqlRuntimeJdbcUrl.java`（新） | P0-C2 严格契约：仅从控制 JDBC URL 解析 host/port（默认 3306）并绑定已验证 `SchemaName`；拒绝内嵌凭据（`@`）、查询串/fragment、URL 内选库、空 host/port、非数字/超范围端口、未方括号 IPv6；`rendered()` 永不含凭据 |
| `EvidenceDirectory.java` | 新增 `reproveRoot()`（扫描前后各重证一次根为真实目录） |
| `EvidenceOwnershipInventory.java` | 新增 `hasNoPendingStreams()`、`directoryPathSet()`、`finalizedAndReportPathSet()`（推导规则已写入各方法 javadoc：目录视图由已注册文件的祖先推导，因为 inventory 只注册文件） |
| `DiskTree.java`（新） | 目录/文件的 NOFOLLOW 统计载体 |
| `EvidenceSecretScanner.java` | **补全被截断的递归遍历**并实现 `collectRegularFiles`（扁平 NOFOLLOW）与 `containsBytes`；symlink/特殊对象一律记为 files，因此在协调阶段表现为“未知对象”而报 dirty |

**进度轨迹**：56 → 44（控制会话）→ 40（扫描）→ 37（导入）→ 26（verifier 家族）→ 15（`RuntimeOps`+助手）→ 16 → **14**（`ScenarioEndpoint` 归位 + readiness POST/GET）。每次均为 `-Xmaxerrs 5000` 的完整普查。

### 重要发现：`ScenarioEndpoint` 早已实现，只是放错了文件

`SpringBootTargetConformanceIT` 第 355–516 行**完整实现**了 `ScenarioEndpoint`（真实路由 `/api/publish-goods`、`/api/search-goods`，每场景的 method/URL/body/期望状态码/DB 表 `goods` 与期望行增量），而 `RealRuntimeOps` 引用的是 `ConformanceSuite.ScenarioEndpoint`。已把它**原样搬入 `ConformanceSuite`**（作为嵌套 `static final class`）并删除 IT 中的重复副本（IT 对它无其它引用，已确认）。**因此这条路径无需编造：路由与期望值全部来自既有代码**，且 `/api/publish-goods` 与 `SpringBootLoweringIntegrationTest:51` 的断言一致。

### 第二处修正：`ScenarioEndpoint` 不是顶层类型（已纠正早前记录）

### 剩余缺口（已精确清单化）

| # | 缺口 | 证据/形状 |
|---|---|---|
| a | `MavenProjectRunner.cleanVerify(Path, boolean, OutputStream, OutputStream)` | 原始日志 `RealRuntimeOps.java:[67]` 确认是元数不匹配（现有只有 2 参） |
| b | `SpringApplicationProcess.start(Path, int, OutputStream, OutputStream)` | 同上，`RealRuntimeOps.java:[98]` |
| c | `StrongFileIdentity.awaitFsutilFileId(Process, int, int) throws IOException` | **既有测试** `StrongFileIdentityTest:23` 已钉住签名与异常消息 `STRONG_IDENTITY_UNAVAILABLE: fsutil timed out`，并断言超时时先请求优雅终止 |
| d | `JdbcVersionInspection`（含嵌套 `Result(passed, messageKey, detail)` 与 `inspect(loweredModel, sourceKind, candidateSha, b1BaselineId, pomPath, harnessVersion, evidenceWriter, scenarioName)`） | `RealRuntimeOps:40-51` |
| e | `HarnessJdbcVersion.readFromConnection(Connection)` | `RealRuntimeOps:34` |

之后：两处**真实编排**、`/conformance/mysql/campus-market-ddl.sql` fixture、删除两个排除、直接测试、对着真 MySQL 跑矩阵。

**仍属“非纯推导”的部分**：`JdbcVersionInspection` 的校验规则（它要比对生成工程 pom 中的 target 依赖版本与 harness 连接器版本）——我会先读 `TargetDependencyInspector`（已存在，130 行）再看是否需要新逻辑，尽量复用而不是新写。

**待处理的设计不一致（已在草稿中发现）**：`GuardHook.evaluate(ControlSession, …)` 取接口，而 `ControlOwnershipGuard.requireAtBoundary(MysqlControlSession, …)` 取具体类，因此真实 `GuardHook` 实现无法将二者对接。拟在编排阶段把 `requireAtBoundary` 的形参改为 `ControlSession`（方法体只调用接口已有方法，语义不变），到时在“执行记录”里说明。同理，被截断的草稿里用的 `ctx.workRoot()/ctx.evidenceRoot()/ctx.evidenceWriter()` 访问器在不可变 record 上不存在且与 ADR-017 “根由 orchestrate 在正确阶段创建”矛盾；真实编排将把这些运行期对象保存在 `orchestrate` 的局部状态里并以参数传给 helper，**不修改 context 的组件集**。

- 当前状态：**包已恢复编译与运行**，两个 POM 排除均已删除；默认构建 478 run / 0 fail / 0 error / 5 skip，外部矩阵在 MySQL 8.4.11 上产出 QUALIFIED。
- 历史度量：恢复过程中以探针的**错误数下降**为度量（56 → 37 → 15 → 0）。

### 参考环境（已就绪并验证）

| 项 | 值 |
|---|---|
| 服务器 | `mysql:8.4` 容器 `kcg-conformance-mysql`，数据卷 `kcg-conformance-mysql-data` |
| 版本 / UUID | **8.4.11** / `c8295431-b30f-11f1-85f5-82329a62fe01`（随卷持久） |
| 端口 / schema | 宿主 `33306` → 容器 `3306` / `kcg_conf_run` |
| 控制凭据 | `kcg_conf_control`：已验证 `@@server_uuid`、`VERSION()`、`CURRENT_USER()`、**advisory lock**（`GET_LOCK`/`RELEASE_LOCK` 返回 1） |
| 运行凭据 | `kcg_conf_runtime`：仅 `SELECT/INSERT/UPDATE/DELETE`；`CREATE TABLE` 已被拒（`ERROR 1142`） |
| 目录 / 变量 | `/root/kcg-conformance/{work,evidence}`、`/root/kcg-conformance/env.sh`（仓库外，凭据不入库） |
| 提供脚本 | `/root/kcg-conformance/provision-mysql.sh`（幂等） |

必须真 MySQL 的理由：`MysqlControlSession` 执行 `SELECT @@server_uuid, ...`，而 `@@server_uuid` 是 MySQL 专有变量。

环境陷阱（已记录）：`mysqladmin ping` 在 MySQL 8 中**认证失败也返回成功**，不能用作就绪门；必须用“能通过认证执行 `SELECT 1`”。

### 既有的两处截断与 P0-C2

- `ConformanceSuite.orchestrate` 单方法体被截断，且它引用的全部 private helper 都不存在。
- `SpringBootTargetConformanceIT.runFullConformanceSuite` 被截断，并引用两个不存在的类型；它承载的 **P0-C2** 规则（运行库 JDBC URL 只由已验证的 `SchemaName` + 控制端点渲染，不允许调用方提供运行库 URL，也不允许回退用控制 URL 作运行库数据源）必须随 harness 一起实现并测试。
- 草稿已保存在仓库外 `/root/kcg-conformance/Q5-partial-drafts/`。

## 实现顺序（编译驱动，每一步都留下可验证结果）

1. **补 5 个 JDK import**（`Path`/`IOException`/`BasicFileAttributes`/`FileChannel`/`FileLock` 及各文件其余 import），使错误数从 100 显著下降；每轮以 `mvn -o -pl sir-toolchain-application -am test-compile` 的真实错误数为进度度量。
2. **补 12 个缺失类型与 14 个缺失方法**，形状**只从调用点推导**（字段访问、方法签名、`assertEquals` 期望），不发明调用方未使用的功能。`duplicate class: OwnedRunDirectory` 与 `MysqlControlSession.close()` 在同一阶段处理。
3. **补 2 个 JNA 常量**（Windows 重解析点标志），与既有 `WindowsFileIdInfo` 的平台守卫一致。
4. **完成两处被截断的编排**：`ConformanceSuite.orchestrate` 的 `MYSQL_SCHEMA_LOCK..CLEANUP` 全阶段、`SpringBootTargetConformanceIT.runFullConformanceSuite` 的环境装配与终态断言，含 P0-C2 的运行库 URL 渲染。
5. **补 `EvidenceWriter` 的写入面**（仅调用方实际使用的部分）。
6. **删除 POM 的 compiler `testExcludes`**；surefire 的包级排除**保留**（`SpringBootTargetConformanceIT` 以 `IT` 结尾，默认不在 surefire include 模式内），并写明显式运行入口命令。
7. **直接测试**（不依赖外部环境的部分）：`EvidenceWriter`、`ConformanceStateMachine` 转移、`StreamingSecretRedactor` 流式脱敏、`EvidenceSecretScanner` 判定、`SchemaName.parse` 拒绝、`OwnedPathInventory`/`StrongFileIdentity` 归属证明、P0-C2 的 `MysqlRuntimeJdbcUrl` 严格契约（含拒绝用例）、`close()` 与 advisory lock 释放语义。
8. **对着真实 MySQL 运行一次完整矩阵**（按 `env.sh` 的元组），把终态结果、场景结果与证据路径按事实记录；若结果为 FAILED，如实记录 `primaryFailure`/`additionalFailures` 与原因，不得修饰为通过。
9. **定性失败注入**：至少一条“schema 已存在”与一条“UUID 不匹配”的负例，证明 fail-closed 与“运行开始后的失败报告 FAILED、不降级为 skip”。

## 完成闸

- `io.kcg.sir.application.conformance` 参加 testCompile 且**零编译错误**；POM 的 compiler 排除已删除。
- 全量离线闸门①（冻结 `-o clean verify`）与②（完成形式）均 BUILD SUCCESS；相对 Q3 的 **465 run / 0 fail / 0 error / 5 skip** 无新增 fail/error；run 数增量全部来自本单新增的直接测试（逐条归因）。
- 未启用时：`NOT_RUN` 有直接证据且**零副作用**（不连库、不建目录、不写证据）。
- 启用且环境完整时：有真实运行的终态结果与**完整、可复核的原始输出**（终端输出 + 证据目录路径）。
- P0-C2 的严格 URL 契约有直接测试（含拒绝用例）；`MysqlControlSession.close()` 的锁释放有直接证据。
- 资格文档第 5 节与覆盖清单第 5 节按事实改写；**只有**在真实运行产出 `QUALIFIED` 时才可写 `QUALIFIED`，否则如实写 `FAILED` 及其原因，并保留 `NOT_RUN`（无参考环境时）的语义区分。
- 不新增依赖（test 作用域的 `mysql-connector-j`、`jna-platform` 已在），不新增 POM exclude，不删除任何既有断言。
- 完成后状态改为 `AWAITING_ACCEPTANCE`；按负责人决定归档时**不单独提交 Git**。

## 待裁定（不阻塞开工，但影响最终文档口径）

容器化 MySQL（`mysql:8.4` 容器 + 宿主 overlayfs 数据目录）是否算作**可用于资格记录的参考环境元组**。若不算，则本单的真实运行只能登记为“本机 harness 实证”，资格文档第 5 节维持 `NOT_RUN`（原因：无被认可的参考环境）。

## 本工作单明确不做

- 不改 `SpringBootTargetConformanceIT` 的类名/测试框架，不引入 maven-failsafe-plugin，不改构建结构（除删除 compiler `testExcludes`）。
- 不扩大 conformance 的场景集，不新增 scenario。
- 不碰 Bundle/CURRENT/LOCK/Journal 的事务故障注入矩阵、Change 跨版本组合矩阵、`REFERENCES` 边、Snapshot V2、Redis、第二 Target。
- 不把“包能编译”表述为“conformance 通过”。
