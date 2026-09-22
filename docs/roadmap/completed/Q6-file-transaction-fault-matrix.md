# 已完成工作单：Q6 文件事务故障矩阵与恢复方向证据

- 状态：`DONE`（项目负责人 2026-09-18 确认通过）
- 归档：2026-09-18 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 验收记录：负责人 2026-09-18 对执行结果回复“确认”，即验收通过。验收依据：
  1. UPDATE（apply）/ CREATE / DELETE 三条路径具备**同一套**中断点故障矩阵，方向语义一致；
  2. **64 项新测试全绿**（APPLY 21、CREATE 21、DELETE 18、恢复/卷 4），计数取自 Surefire XML；
  3. 两个全量闸门均 **BUILD SUCCESS**，合计 **542 run / 0 fail / 0 error / 5 skip**（478 → 542，增量恰为 64）；
  4. 模糊状态 fail-closed（未知 CURRENT、缺 CURRENT 均被拒且证据不变）；DELETE 硬链接约束有直接证据；
  5. **零生产代码改动**：规格中的 D1(a) 经实证不需要（UPDATE 族由 apply 事务承载，注入面已完整）。
- 所属阶段：路线图阶段 5（事务故障矩阵）；属 **G0：现有链路资格收口**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 5、`AGENTS.md` 第 3 节（ADR-020 唯一解释）
- 前置工作：Q1–Q5 已完成并归档（[`completed/`](completed/)）
- 授权来源：项目负责人 2026-09-18 复核通过 D1–D6 后回复“可以”，并指示“完成 Q6 所有工作在向我汇报，有决策优先统一向我说明”
- 残余缺口（已知并记录）：跨卷情形只证明到“注册阶段拒绝”；进入删除阶段后链接不可用的分支在本机不可构造（需绕过前置校验才可能出现）
- 版本快照：按负责人 2026-09-18 决定，仍**暂不提交 Git**

# 当前工作单：Q6 文件事务故障矩阵与恢复方向证据

- 状态：`DONE`（见上方归档头）
- 所属阶段：路线图阶段 5（事务故障矩阵）；属 **G0：现有链路资格收口**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 5、`AGENTS.md` 第 3 节（ADR-020 唯一解释）、`docs/qualification/CURRENT_QUALIFICATION.md` 第 6 节
- 前置工作：Q1–Q5 已完成并归档（[`completed/`](completed/)）
- 授权状态：本文件写出后仍需负责人复核 D1–D6；复核通过才转为 `IN_PROGRESS`
- 版本快照：按负责人 2026-09-18 决定，仍**暂不提交 Git**，与 Q1–Q5 合并到 G1 完成后统一提交

## 目标

为 UPDATE、CREATE、DELETE 三条事务路径建立**同一套**跨 CURRENT 线性化点的故障矩阵，用**真实注入的失败**（而不是直接构造中间状态）证明：

1. 每个中断点的终态分类明确：向后补偿 / 向前完成 / `RECOVERY_REQUIRED`；
2. `CURRENT=B0` 只允许向后补偿，`CURRENT=B1` 只允许向前验证与清理；证据缺失或身份不确定必须 fail closed 且保留证据；
3. DELETE 的硬链接备份约束不被 copy/move 回退绕过；
4. 每条 `RECOVERY_REQUIRED` 都能通过显式 `recover` 走到确定终态，且重复恢复幂等。

## 勘察结论（2026-09-18，均直接读代码得到）

1. **生产已具备完整注入面**：`io.kcg.sir.application.internal.state.ApplyHooks` 提供 **48 个 hook**，覆盖 staging、事务目录、journal 创建/强制落盘、备份、提交、Bundle 发布（`afterBundlePublish`）、CURRENT 写入与原子替换（`beforeCurrentNewWrite`/`afterCurrentNewWrite`/`afterCurrentAtomicMove`）、发布后重读、回滚步、清理，以及 CREATE / DELETE 专属的链接、删除与回滚回调。
2. **注入面可经公开 API 使用**：`ChangeExecutionApplication` 的构造器接收 `ApplyHooks`（`api/ChangeExecutionApplication.java:101/110/121`），因此 **CREATE / APPLY / DELETE 三条路径的矩阵无需改生产代码**。
3. **该注入面目前几乎未被使用**：全仓库只有 `FileTransactionFaultInjectionTest` 与 `ApplicationArchitectureTest` 提到 hooks，实际被触发的只有 **4 个**（`beforeStagingWrite`、`beforeCommitFile`、`afterBackupBeforeCommit`、`beforeRollbackStep`），且都是文件事务层的 `TransactionHooks`，**不是** `ApplyHooks`。
4. **恢复方向已有基础但都是"构造状态"而非"真实失败"**：`RecoveryStateMachineTest` 9 项覆盖 B0 回滚/从备份链接恢复、DELETE 意图两种分支、B1 向前不恢复已删文件、CURRENT 既非 B0 也非 B1 被拒、缺 CURRENT 被拒、重复恢复幂等、journal 状态转移严格性——但它直接**构造**中间状态，没有先让真实事务中断。
5. **UPDATE 路径没有注入缝**：`ToolchainApplication` 的公开构造器不接 hooks，只有两个包内构造器接收 `Function` 步骤（`api/ToolchainApplication.java:32/36/40`）。`FileTransaction` 的 hooks 接口 `TransactionHooks` 是包内可见（`io.kcg.sir.application.internal`），测试可在同包内直接驱动文件事务层。
6. **硬链接/同卷**：`RecoveryStateMachineTest:163` 已用 `Files.createLink` 建备份链接并断言回滚后移除、必要时从备份链接恢复；DELETE 的硬链接约束在 `ChangeDeleteTransaction` 中实现，但**没有**测试断言"硬链接不可用时必须 fail closed 而不是 copy/move 回退"。
7. **平台门控先例**：`PathSecurityReviewTest` 用 `@EnabledOnOs(LINUX/MAC)` / `WINDOWS` 区分平台（当前 Linux 上 5 项 Windows junction 用例按设计 skip）。
8. **显式恢复入口**：`api/ChangeExecutionApplication.recover(ChangeRecoveryRequest)` + `RecoveryHandle(Optional<String> transactionId)`、`RecoveryHandle.any()`，返回 sealed 的 `ChangeRecoveryResult`。

## 任务

1. **矩阵骨架**：按 `路径 × 中断点` 参数化，每个用例断言四类不变式——终态分类、CURRENT 值与方向、输出根字节（B0/B1 内容）、事务证据（journal/备份/CURRENT.new）与残留物。
2. **APPLY / CREATE / DELETE 三路径**：用 `ApplyHooks` 在每一个会改变磁盘或事务证据的 hook 处各注入一次失败，逐点断言分类；分类为 `RECOVERY_REQUIRED` 的用例继续调 `recover` 并断言终态。
3. **UPDATE 路径**：见 D1 的两种方案。
4. **方向语义**：对每个注入点同时覆盖 `CURRENT=B0` 与 `CURRENT=B1` 两种线性化位置（B1 位置由注入点自然决定；需要时用 hook 断言 CURRENT 的实际值），显式断言 B0 绝不向前、B1 绝不恢复旧字节。
5. **fail closed**：CURRENT 与 journal 声明不一致、CURRENT 缺失、物理身份不确定三类模糊态必须被拒且保留事务证据（不得删除证据、不得猜测方向）。
6. **DELETE 硬链接约束**：断言备份是硬链接（同卷、`Files.isSameFile`）且硬链接不可用时**失败**而不是回退到 copy/move。
7. **平台与卷**：junction/reparse 保持 Windows-only（Linux 上如实 skip）；第二卷/挂载点见 D3。
8. **证据与文档**：把矩阵覆盖表（路径 × 中断点 × 期望分类 × 实得）写入本工作单；测试数量取自当次 Surefire。

## 需要负责人裁定的事项（推荐项已标注）

- **D1（关键）UPDATE 路径的注入缝**：
  - **(a) 推荐**：授权在 `ToolchainApplication` 增加一个**包内**构造器重载接收 hooks（与既有 `ToolchainApplication(Function, Function)` 同模式，公开构造器语义不变）。理由：路线图阶段 5 的完成门要求"三类事务拥有相同方向语义和端到端故障矩阵"，没有缝就无法满足；改动面仅一个包内构造器 + 一个字段。
  - (b) 不改生产代码：UPDATE 只在文件事务层（`TransactionHooks`）与恢复层覆盖，并在文档里登记"UPDATE 的 Bundle/CURRENT 发布点无注入面、未覆盖"。
- **D2 矩阵组织**：推荐每个路径一个测试类 + 内层参数化（`@ParameterizedTest` over hook 点），便于失败定位；不推荐把三路径塞进一个巨型参数化用例。
- **D3 第二卷/挂载点**：推荐写一个**自跳过**的 opt-in 用例——能 `mount -t tmpfs` 时真实验证"跨卷硬链接失败 → fail closed"，不能时如实记录 `NOT_RUN` 并说明原因（本机是 root，tmpfs 大概率可用，但属于环境变更）。若你希望完全不碰挂载，就只做 `NOT_RUN` 记录。
- **D4 "无 copy/move 回退"的判定方式**：推荐用**双证据**——`Files.isSameFile(backup, target)` 为真 + 两条路径的 `Files.getFileStore(...).equals(...)` 为真；并断言跨卷时得到结构化 Failure 而非静默回退。
- **D5 生产修复**：只修被本单测试**直接证明**的缺陷，最小化改动并单独在报告里列出（沿用 Q3 先例）。
- **D6 验收命令**：沿用既有的定向命令 + 两条全量命令（冻结形式 `-o clean verify`、完成形式忽略失败），并保持 conformance 外部矩阵的 opt-in 语义不变。

## 完成门

- UPDATE / CREATE / DELETE 三路径拥有同一套中断点矩阵，每点分类明确且有断言。
- 模糊状态全部 fail closed，且证据保留（测试断言证据仍在）。
- 无 copy/move 回退绕过 DELETE 硬链接约束。
- `sir-toolchain-application` 模块 0 fail / 0 error；两条全量闸门 BUILD SUCCESS，计数与基线（478 / 0 / 0 / 5）的差异逐条归因。
- 文档同步：本工作单 + `CURRENT_QUALIFICATION.md`（新增一节）+ `TEST_COVERAGE_INVENTORY.md` + `REMAINING_WORK.md` 阶段 5。

## 验证命令（沿用基线）

```bash
# 定向（按需替换测试类）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-toolchain-application -am \
  -Dtest=ChangeFaultMatrixTest -Dsurefire.failIfNoSpecifiedTests=false test

# ① 冻结形式（失败即停）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
# ② 完成形式（忽略失败，使 Reactor 走到 kcg-cli）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true

git diff --check
git status --short
```

## 交接记录

### 结果：三路径故障矩阵完成，64 项新测试全绿，**零生产代码改动**

| 指标 | 值 |
|---|---|
| 新增测试 | **64**（APPLY 21 + CREATE 21 + DELETE 18 + 恢复/卷 4） |
| `sir-toolchain-application` | **210 run / 0 fail / 0 error / 5 skip**（Q6 前 146） |
| 闸门①（冻结 `-o clean verify`） | **BUILD SUCCESS**，9 模块 |
| 闸门②（完成形式） | **BUILD SUCCESS** |
| 全量合计 | **542 run / 0 fail / 0 error / 5 skip**（478 → 542，增量 = Q6 的 64，逐条可归因） |
| 生产代码 | **0 改动**（见下方 D1(a) 更正） |

### 三路径方向语义**完全一致**（完成门）

| 中断区间 | APPLY（UPDATE） | CREATE | DELETE |
|---|---|---|---|
| 事务目录创建 | Failure `COMMIT-001`，树=B0 | Failure，树=B0 | Failure，树=B0 |
| journal / 暂存 / bundle stage / 强制落盘 | RecoveryRequired，树=B0 | RecoveryRequired，树=B0 | RecoveryRequired，树=B0 |
| 备份 / 提交 / 链接 / 删除等正向步骤 | Failure `COMMIT-002/003`，树=B0 | Failure，树=B0 | Failure，树=B0 |
| `afterBundlePublish`…`afterCurrentNewWrite` | RecoveryRequired，**CURRENT 仍=B0、树≠B0** | 同 | 同 |
| `afterCurrentAtomicMove`、`afterPublishReread` | RecoveryRequired，**CURRENT=B1、旧字节不回归** | 同 | 同 |
| `beforeCleanup` | Applied + `CLEANUP` 诊断 + 证据保留 | 同 | 同 |

每个用例断言 5 条不变式：V1 注入点必须**真的到达**（否则用例空洞）、V2 CURRENT 永不消失且成功不留 `CURRENT.new`、V3 CURRENT=B0 时树必须=B0 除非显式 `RecoveryRequired`、V4 CURRENT=B1 时树必须≠B0、V5 成功即 B1 已发布。

### 新增测试件（全部在 `sir-toolchain-application/src/test/java/io/kcg/sir/application/api/`）

| 文件 | 作用 |
|---|---|
| `ChangeFaultMatrixSupport.java` | 三族 fixture 准备（真实管线编译 + 注册 B0 + 按族构造 ChangeSet）、注入 hooks 跑一次 apply、共享不变式断言与矩阵行输出 |
| `FailingApplyHooks.java` | **由 `ApplyHooks` 源码机械生成**的 44 个 override：记录到达过的 hook，只在指定 hook 抛错（接口新增 hook 不更新它即编译失败） |
| `ApplyFaultMatrixTest.java` / `CreateFaultMatrixTest.java` / `DeleteFaultMatrixTest.java` | 三族矩阵 + 各自的补偿分支用例 + 覆盖率守卫 |
| `FaultMatrixRecoveryAndVolumeTest.java` | 硬链接证据、模糊 CURRENT/缺 CURRENT 的 fail-closed、跨卷拒绝 |
| `fault-matrix-dirs-{base,candidate}.sir`（fixtures） | 让 CREATE 的目录点为**可达**（基线无任何 capability） |

### 四项发现（均已记录，无需生产改动）

1. **D1(a) 更正——UPDATE 路径本来就有完整注入面**。规格里假设"UPDATE 路径没有 hook 缝，需要新增包内构造器"。实证：三个族指的是 Change IR 的操作族，**UPDATE 族由 apply 事务承载**，其 `ApplyHooks` 注入面已完整并经公开构造器（包内可见）可用。因此 **Q6 未修改任何生产代码**。
2. **条件性 hook 需要专门的 fixture 才可达**。CREATE 的四个目录点（`afterCreateDirectoryPlanComputed`、`beforeCreateDirectoryIntent`、`afterCreateDirectoryCreate`、`afterCreateDirectoryComplete`）在默认 fixture 下**永不触发**——实测 `plannedDirectories=[]`，因为基线已生成全部目录。为此新增一对 fixture（基线**没有任何 capability**，候选新增一个），使目录计划非空，四个点转为可达（`CREATE-DIRS` 行）。把它们留在默认矩阵里会产生空洞用例。
3. **补偿深度取决于失败位置**。DELETE 的回滚只有两条分支：在 `beforeDeleteIntent` 注入（备份已建、目标未删）才走到 `afterBackupDelete`；在 `afterDeleteFileComplete` 注入（文件已删）才走到 `afterRestoreLinkCreate`/`afterDeleteRollbackComplete`。因此补偿用例按分支参数化；单点注入无法覆盖全部回滚 hook。
4. **跨文件系统布局在注册阶段即被拒绝**。DELETE 的备份是同卷硬链接，跨卷时无法建立。实测：把 state root 与 output root 放在不同 file store（`/dev/shm` 与 `/tmp`）时，**注册阶段**即返回结构化 Failure 且**不修改已生成的输出**——因此在删除阶段"链接失败后 copy 回退"的路径不可达。这是 D4 双证据的一半；另一半由**在 hook 内观测**的硬链接证据给出：`Files.isSameFile(backup, target)=true`、`nlink>=2`、同一 file store（副本不可能满足 nlink≥2）。观测必须在事务进行中完成，因为成功清理会删除备份。

**模糊态 fail-closed（真实失败 + 人为破坏指针）**：先真实中断（`afterBundlePublish`）得到 `RecoveryRequired` 与证据，再把 CURRENT 改成未知 baseline、或直接删除 CURRENT，**两种情况下 recovery 都拒绝**且事务证据**逐文件不变**（用例逐条比对证据文件计数）。

**明确的残余缺口**：跨卷情形只在"注册即拒绝"这一层面被证明；真正进入删除阶段后遇到链接不可用的路径，在本机不可构造（注册会先拒绝），只有在禁用该前置校验的布局下才可能出现——已如实记录，未声称覆盖。
