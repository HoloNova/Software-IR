# Q17 G2 第二切片——单声明改名的原子文件事务（已完成）

- 状态：**`DONE`**（负责人 2026-10-03 确认验收；2026-10-04 完成文档归档）。2026-09-26 批准开工，P1–P7 实测后实现，2026-09-28 获授权提交/推送并由 CI 验证。实现、证据与订正见 §4、§14、§15；本次验收收口不含新的提交/推送授权。
- 所属阶段：**G2：持久身份与受控模块**。本单只交付“单文件、单声明、已验证 `RenamePlan` 的一次原子应用 + 故障恢复 + 盘面等于候选从零生成”。**本单不关闭 G2**，也不完成 LANG-01..05（见 §12）。
- 前置：Q16 已验收归档（[`Q16-persistent-identity-and-read-only-rename-plan.md`](Q16-persistent-identity-and-read-only-rename-plan.md)，受测源码 `b2f5436a0c98cba69467d211b19053237ba5edf6`，CI run `36234304027`）。起点是 Q16 已验证的单声明只读 `RenamePlan` / `RenamePlanner.verify`；**`sir-change` 本单零改动**。
- 起点源码：`main` @ `b40b7ab`（Q16 归档提交，工作树干净）。
- 方向来源：[`../README.md`](../README.md) G2 行与阶段总览、[`../REMAINING_WORK.md`](../REMAINING_WORK.md) G2 增量序列、[`../../design/07-validation-and-direction-roadmap.md`](../../design/07-validation-and-direction-roadmap.md) §4（LANG-01）与 §8（G2 行）。
- 只读先决核查：本次立案前的只读勘察报告（子代理产物，未入库、未跑构建、未改仓库）与执行锚点 `/tmp/q17-execution-plan.md`。本单“已核对事实”只引用该报告的【实测】条目，**仅作施工起点**；所有【OPEN】条目必须先做 §4 的探针实测再冻结，不得当成已成立的前提。

## 1. 用一件真实事情说明问题

旧机制里 `SearchCourseEnrollments` 决定声明名、生成类名、文件路径和按名称派生的 `SymbolId`；Q16 加 `@id` 后身份已不再随名字变化，但文件路径仍由声明名决定。用户把名字改掉之后：

- Q16 已能产出一份**只读**的类型化改名计划：真实 fixture `valid/rename-course-search.sir` 上实测为 **2 条撤销 + 2 条建立 + 0 条更新**（旧 Controller/Service 撤销，新 Controller/Service 建立）。
- 但**没有任何应用入口**：今天的 `REPLACE_EXISTING` 生成只写本次清单、不清理旧路径，于是新旧两套 Controller/路由会同时留在盘面；`ChangeApplyRequest` 与 `ChangeExecutionApplication` 的既有方法都不接受 `RenamePlan`。
- 现有三套文件事务日志各只承载一个族：`TransactionJournal`（V1，仅 UPDATE，且反向恢复要求 B0/B1 键集完全相同）、`CreateTransactionJournal`（V2，仅 CREATE）、`DeleteTransactionJournal`（V3，仅 DELETE）。把“撤销 + 建立 + 更新”塞进任一族都会破坏其冻结语义。

所以本单要回答的是：**怎样把一份已验证的改名计划，变成一次“要么全成、要么按 CURRENT 方向安全补偿”的原子文件事务，并让成功后的盘面逐字节等于候选从零生成的结果。**

## 2. 已核对的事实（施工起点；均为直接读码，不是规划假设）

| # | 事实 | 位置 |
| --- | --- | --- |
| F1 | `RenamePlan` 是记录，携带 `basedOn`、`subject`、三集合（`updates`/`withdrawals`/`establishments`）、两侧图规范摘要与源字节摘要、`planDigest`；构造器强制排序、互斥与摘要格式。 | `sir-change/.../api/RenamePlan.java:23-53` |
| F2 | `RenamePlanner` 公开面只有 `plan`/`verify`，**没有 apply**；计划不含候选字节，只含逐条 `byteCount/sha256Hex`。 | `RenamePlanner.java`；`RenameFileUpdate/Withdrawal/Establishment` |
| F3 | Q16 冻结的“不可 apply”断言按**参数类型**判定：`ChangeApplyRequest` 的记录组件与 `ChangeExecutionApplication` 的公开方法参数都不得是 `RenamePlan`。 | `sir-toolchain-application/src/test/java/io/kcg/sir/application/RenamePlanVerticalTest.java:353-362` |
| F4 | apply 的既有前置序列：根校验 → 取锁 → 复校验 → 读 CURRENT → `JournalGate.inspect(currentId)` 必须开放 → `expectedBaselineId == CURRENT` → `outputRoot == bundle.boundOutputRoot` → `basedOn == bundle.toBaseRevision()`。 | `ChangeExecutionApplication.java:1425-1513` |
| F5 | 恢复方向只由 CURRENT 决定：CURRENT=B0 走反向补偿，CURRENT=B1 走“校验盘面 + 清证据”的前向恢复。 | `ChangeRecoveryEngine.java:100-117`、`595-619` |
| F6 | V1 反向恢复的 `computeUpdateDelta` 要求 B0/B1 键集完全相同（纯 UPDATE），这是“混合变更不能落在 V1”的硬证据。 | `ChangeRecoveryEngine.java:189-193`、`546-593` |
| F7 | `JournalGate` 按 MAGIC 前缀分派（`:182-219`），`ActiveJournal` 是 sealed 三变体（`:294`），`JournalFamily` 是三值枚举（`:427-431`）；活跃日志阻塞一切写路径。新增族需要同时改这四处。 | `JournalGate.java` |
| F8 | V3 用“同卷硬链接 + 链接同一性证明”作备份，并有完整硬链接状态机；`StateRootPathGuard` 已强制 stateRoot/outputRoot 同 FileStore 且互不包含，硬链接因此合法。 | `DeleteTransactionJournal.java:674-682`；`ChangeDeleteTransaction.java`；`StateRootPathGuard.java:78-98` |
| F9 | 三套盘面前置检查已就绪：更新→`protect`（必须存在且等于基线字节）、建立→`protectAdditions`（必须不存在）、撤销→`protectDeletions`（必须存在且等于基线字节），都做 NOFOLLOW 符号链接与段级检查。但它们现只接受 `FileChange/FileAddition/FileDeletion`。 | `PlanProtector.java:24-275`；`FileChange.java:8-16`；`RenameFile*` |
| F10 | 故障矩阵的方向不变量已经写成可复用断言：注入点必须真的到达；CURRENT 不消失；成功不留 `CURRENT.new`；CURRENT=B0 ⇒ 树=B0 或显式 `RecoveryRequired`；已发布 B1 不得回退旧字节；成功即 B1 已发布。 | `ChangeFaultMatrixSupport.java:265-316` |
| F11 | 注入点清单集中在 `ApplyHooks`（V1/V2/V3 三族方法族），故障矩阵测试用“钩子表长度断言”防止矩阵静默缩小。 | `ApplyHooks.java:7-142`；`DeleteFaultMatrixTest.java:139-151` |
| F12 | 生成期 `FileTransaction` 只写不删，且不参与 Bundle/CURRENT，**不能**当作改名 apply。 | Q16 先决核查 P3 结论（`completed/Q16-persistent-identity-and-read-only-rename-plan.md`） |
| F13 | 真实计划的 `updates` 在现有唯一真实 fixture 上为空；“是否有真实管道证据能产生非空 `updates`”**未证明**。 | `RenamePlanVerticalTest.java:135-155` |
| F14 | `RenameFile*` 的 `ownerSymbol` 是 `Optional`，而 V2/V3 的负载构造器要求清单条目的 `ownerSymbol` 非空且与条目一致；“真实计划条目 ownerSymbol 是否恒非空”**未证明**。 | `RenameFileEstablishment.java:14-36`；`CreateFilePayload.java:28-32`；`DeleteFilePayload.java:24-28` |

标为**未证明**的条目（F13、F14 以及 §4 的 P3/P5）必须在开工时先实测。本单不假设它们成立。

## 3. 本单交付（已批准边界）

1. **一个显式应用入口**：`ChangeExecutionApplication.applyRename(RenameApplyRequest)`。复用现有五段前置（根校验、锁、CURRENT 绑定、Journal 门、Bundle 读取），不新建第二个 application 类或第二套锁/门。
2. **一次原子混合文件事务**：在同一事务里完成幸存文件的更新、旧受管文件撤销、新受管文件建立，然后发布 B1 Bundle 与 CURRENT；所有文件变更都在 CURRENT 发布**之前**完成（沿用“发布即已完成”的不变量，使前向恢复退化为校验 + 清理）。
3. **故障恢复**：CURRENT=B0 时按逐条 kind 反向补偿；CURRENT=B1 时校验 B1 清单并清证据；方向不确定或盘面与证据不符一律 fail closed。**外部文件绝不被误删/误覆盖**。
4. **盘面一致性**：成功后的输出树逐相对路径、逐字节等于“同候选 SIR 从零生成”的盘面（构造性保证：入口只接受候选 SIR，由工具链自己重编译生成）。
5. **计划绑定校验**：把 Q16 的“图级全工程覆盖”落到 Bundle 清单级，拒绝多报/漏报/类别错/摘要不符/owner 缺失的计划条目。
6. **未受管文件保护与路径守卫**：建立路径被未受管文件占用、旧路径被外部改写、符号链接/目录占位等情形在执行前拒绝，且盘面零变化。
7. **测试矩阵与故障矩阵**（含灵敏度探针），以及 Q16“不可 apply”断言语义的显式登记。

## 4. 开工前先决核查 P1–P7（必须先实测并回填本单，再改生产代码；**P1–P7 已实测回填，见 §4.1**）

探针允许新增**定向测试类或临时探针**，但不得修改生产代码；任何临时探针在回填结论后删除。

| # | 待测问题 | 探针 | 结论影响 |
| --- | --- | --- | --- |
| P1 | **合法真实计划是否可能出现非空 `updates`**（OPEN-1）？ | 取带 actor 的 fixture（如 `valid/campus-market.sir`），加 `@id` 后改名能力，走 Q16 规划路径并打印 `plan.updates()` | 决定 UPDATE 腿能否有真实管道证据。若所探查真实 fixture 均为空，不能断言所有合法计划恒为空；推荐保留三集合承诺，用受约束合成图/Bundle 加故障矩阵验证 UPDATE 腿，真实管道用两集合验证。若合成证据也无法成立，再停轮提请负责人裁决范围。 |
| P2 | **真实计划条目 `ownerSymbol` 是否恒非空**（OPEN-2）？ | 对真实计划全部条目断言 `ownerSymbol().isPresent()` | 决定能否复用 V2/V3 负载类型，还是负载层必须支持空 owner；若可能为空，必须在 apply 前置给显式诊断而不是让构造器抛异常 |
| P3 | **`ATOMIC_MOVE + REPLACE_EXISTING` 更新腿是否可靠、外部并发写的检查如何等价改写**（OPEN-2a）？ | 同 FileStore 上的小型定向用例：先建硬链接备份，再原子替换 target，验证替换原子性与备份仍指向旧 inode | 决定更新腿用“硬链接备份 + 原子替换”还是 V1 的“移走 + 提交”；同时确定“备份后 target 未被外部改写”的等价检查写法 |
| P4 | **逐 kind 的落盘顺序**（OPEN-4）：撤销与更新谁先谁后，是否需要“先建后删”规避路径冲突？ | 设计期推导 + 实现期逐个注入点观察中间态 | 直接影响故障方向表与恢复分支数 |
| P5 | **日志绑定**（OPEN-3）：V4 可不可以只靠 B0/B1 清单重建类别？ | 反推：`withdraw=仅在B0`、`establish=仅在B1`、`update=两侧同路径不同摘要`；再问“本事务是哪个计划”能否重建 | 类别可交叉校验；“计划身份”不可重建 ⇒ 建议日志头绑 `planDigest`（D6）；是否采纳需在本单冻结 |
| P6 | **两族回滚方法的进入条件是否被完整对照**（先决核查报告自记残留风险）？ | 逐条通读 `ChangeDeleteTransaction`（约 1208 行）与 `ChangeCreateTransaction`（约 1218 行）的每个回滚分支，列出“外部文件不得误删”的边界条件 | 决定 V4 反向补偿每个分支的进入条件；漏一条就会引入删错用户文件的风险 |
| P7 | **`JournalGate` 对新增族的严格性与阻塞语义**：未知 MAGIC、乱序、重复索引、非法状态迁移、不安全相对路径必须解析失败；活跃 V4 日志必须阻塞一切写路径 | 按 V1/V3 既有解析测试风格新增对应用例；并验证 `JournalGate.inspect` 对 V4 活跃日志的阻塞 | 决定日志族新增是否安全；解析宽松会使损坏证据被当成合法事务 |

## 4.1 开工前实测（2026-09-26，P1–P7 已回填；本机定向，未改生产代码）

- **手段**：新增定向测试类 `sir-toolchain-application/src/test/java/io/kcg/sir/application/Q17PreflightProbeTest.java`（7 例，全通过）；一次性证据转储探针 `Q17PreflightEvidenceDumpTest` 在记录完数据后已删除；另跑既有定向类 `RecoveryStateMachineTest`（9/0/0/0，含 `journalFileStateTransitionsAreStrict`）与 `PathSecurityReviewTest`（12 run / 0 fail / 5 skip，skip 均为 Windows junction 用例）。命令（本机、离线、包 `systemd-run --scope -p MemoryMax=2G -p CPUQuota=150%`、无 Maven `-T`）：

  ```bash
  mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
    -Dtest='Q17PreflightProbeTest,RecoveryStateMachineTest,PathSecurityReviewTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```

  结果：`Tests run: 28, Failures: 0, Errors: 0, Skipped: 5`，BUILD SUCCESS（不跑全量闸门/业务 IT）。**本次实测零生产代码改动**，新增仅 1 个测试文件。
- **边界**：P1–P4 为运行期实测；P5 为代码反推；P6/P7 为逐分支读码 + 既有测试基线。**没有为尚未实现的 V4 编写任何测试。**

### P1 结论：真实管道不可能产生非空 `updates`（结构性，不是“样本为空”）

| 观测 | 数据 |
| --- | --- |
| `valid/rename-course-search.sir`（`SearchCourseEnrollments`→`SearchEnrollments`） | `Planned`；**`updates=[]`**；withdrawals=2；establishments=2 |
| 撤销 2 条 | `src/main/java/com/example/rename/api/SearchCourseEnrollmentsController.java`（855B/`e339ff81…`）、`src/main/java/com/example/rename/application/SearchCourseEnrollmentsService.java`（849B/`e7fb55a7…`） |
| 建立 2 条 | `.../api/SearchEnrollmentsController.java`（806B/`fb33dfcd…`）、`.../application/SearchEnrollmentsService.java`（831B/`4407c270…`） |
| `valid/campus-market.sir` 的 actor 能力改名（`PublishGoods`→`PublishItems`，保留 `@id("publish-goods")`） | **`Rejected`**：`SIR-RENAME-PATH-004` @ `src/main/java/com/example/campusmarket/Application.java`（“受管文件在候选修订中内容替换，但计划完全没有覆盖该路径”） |

结构性理由：`ClosureComputer.compute` 只保留目标声明的 `SERVICE`/`CONTROLLER` artifact（`ClosureComputer.java:62-83`），而这两个文件的相对路径由能力名派生。改名要求 `baseName != candidateName`，于是两侧闭包在**同一相对路径**上不可能相交 ⇒ CAPABILITY 改名的 `updates` 恒为空。任何“路径不变、内容变”的受管文件（如 actor 路由写进路径固定的 `Application.java`，`ApplicationRenderer.java:29-37,155`）都不在闭包内，规划器以 `SIR-RENAME-PATH-004` 拒绝，而不会记成 UPDATE。

**影响**：真实管道只覆盖**撤销 + 建立两集合**；UPDATE 腿在本单 Q16 规划规则下**没有也不可能有**真实管道证据。按 §4 P1 已批准分支：保留三集合承诺，UPDATE 腿只用**受约束合成计划 + 构造的 B0/B1 Bundle**配故障矩阵验证，并在完成门证据里区分“真实管道两集合”与“合成证据三集合”。**不缩范围、不改 Q16 计划语义、无需新裁决。**

### P2 结论：真实计划条目 `ownerSymbol` 恒非空；合成计划可合法为空

4 条真实条目（2 撤销 + 2 建立）`ownerSymbol` 全部为 `Optional[sir://RenameCourseSearch/declared/capability/search-course-enrollments]`，且恰等于 `plan.subject().declarationSymbol()`；`updates` 为空故无更新条目可测。理由：闭包只含该能力自身的 SERVICE/CONTROLLER，其图 provenance 的 ownerSymbol 即该能力符号。

**影响**：真实单能力计划可复用 `CreateFilePayload`/`DeleteFilePayload`（要求条目 ownerSymbol 非空且与 B0/B1 条目一致，`CreateFilePayload.java:28-32`、`DeleteFilePayload.java:24-28`）；但 `RenameFile*` 的 ownerSymbol 是 `Optional`，合成/手构计划可合法带 `Optional.empty()`。**apply 前置必须对空 owner 给显式诊断码（fail closed）**，不得让负载构造器抛 `IllegalArgumentException` 后被折算成既有 `SIR-APP-CHANGE-BIND-005`。

### P3 结论：`硬链接备份 + ATOMIC_MOVE/REPLACE_EXISTING` 可用；外部写检测 = “inode 同一 + B0 摘要复验”

本机文件系统实测（同一临时目录，天然同 FileStore）：

- `Files.createLink(backup,target)` 后 `isSameFile(target,backup)` 为真、`backup` 读到 B0 字节；
- `Files.move(staged,target,ATOMIC_MOVE,REPLACE_EXISTING)` 完成替换：target=候选字节、staged 消失、`isSameFile(target,backup)` 变假、`isSameFile(backup,旧inode见证链接)` 仍为真且 `backup` 仍为 B0 字节（可原样回滚）；
- 外部**替换**（换 inode）由 `isSameFile` 检出；外部**原地覆写**（同 inode）`isSameFile` 仍为真，但备份同 inode 因而读到新字节 ⇒ 由“备份的 B0 字节数 + 摘要复验”检出。故 V1“备份后 target 必须不存在”（`ChangeApplyTransaction.java:358-361`）在硬链接方案下的等价写法是：**写前复验 `isSameFile(target,backup)` 且备份/B0 摘要一致**；
- 不带 `REPLACE_EXISTING` 的 `ATOMIC_MOVE` 本次观测为“直接替换”；探针只断言“要么原子替换、要么失败且 target 仍为 B0 字节”，即**失败可检测、无静默损坏**。

**影响**：D2 成立——更新腿用“硬链接备份 + `ATOMIC_MOVE, REPLACE_EXISTING`”，无需退回 V1 的“移走 + 提交”，从而消除 V1 的路径短暂缺失窗口。同 FileStore 前提由 `StateRootPathGuard.validate(...,true)` 强制（`StateRootPathGuard.java:78-98`）。实现须**显式传 `REPLACE_EXISTING`**。

### P4 结论：单能力改名的计划路径互不相交也不嵌套 ⇒ 顺序自由

真实计划 4 条路径两两不相等，且任一都不是另一个的祖先（多对多 `Path.startsWith` 检查）；撤销与建立的**父目录集合完全相同**（`src/main/java/com/example/rename/api` 与 `.../application`），且这两个目录在 B0 树中已存在。故不存在“先建后删”才能规避的路径冲突，也不需要像 V2 那样先建目录。

**影响**：V4 逐 kind 落盘顺序不受路径冲突约束；顺序表由故障方向不变量（§2 F10）与“全部文件变更先于 CURRENT 发布”决定，而非冲突规避。跨包改名（目录变化、需建目录）不在 Q17 范围。

### P5 结论：类别可重建，计划身份不可重建 ⇒ 日志头绑 `planDigest`

以 B0/B1 清单重建四分区可行且与规划器同口径：`withdraw=仅B0`、`establish=仅B1`、`update=两侧同路径但摘要不同`（`RenamePlannerCore.changeOf:544-556`）。但“本事务属于哪份计划/哪个 subject”无法由 B0/B1 清单推出；恢复入口只接 `RecoveryHandle`，不接计划（`ChangeExecutionApplication.java:1361-1417`；`ChangeRecoveryEngine.java:84-119` 分派 V2/V3 引擎，二者只读日志 + B0/B1 Bundle）。

**影响（D6 落地）**：V4 日志头写 `planDigest`（建议并带 subject 符号）；恢复**不得依赖**该字段（须仍只凭日志 + B0/B1 可恢复）；prepare 时断言“由清单重建的类别分区 == 计划三集合”。逐条目状态与摘要仍必须落日志（不能只靠清单重建）。

### P6 结论：V2/V3 回滚进入条件与“外部文件不得误删/误覆盖”边界（逐分支）

文件态迁移表：V2 `CreateTransactionJournal.java:708-736`（`PREPARING→CREATE_INTENT_DURABLE→CREATED_DURABLE→ROLLBACK_DELETE_INTENT_DURABLE→ROLLED_BACK_DURABLE`，且 `CREATE_INTENT_DURABLE→ROLLBACK_DELETE_INTENT_DURABLE` 直达）；V3 `DeleteTransactionJournal.java:674-732`（`PREPARING→BACKUP_LINK_INTENT_DURABLE→BACKUP_LINKED_DURABLE→DELETE_INTENT_DURABLE→{DELETED_DURABLE | RESTORE_LINK_INTENT_DURABLE}→RESTORED_DURABLE→ROLLED_BACK_DURABLE`）。总体态两族同集且要求 ordinal 严格递增（`CreateTransactionJournal.java:591-595`、`DeleteTransactionJournal.java:734-745`）。

内联回滚分支（`ChangeCreateTransaction.rollback/rollbackFile:656-838`；`ChangeDeleteTransaction.rollback/rollbackFile:667-950`）与各自恢复引擎（`ChangeCreateRecoveryEngine.java:289-426`；`ChangeDeleteRecoveryEngine.java:209-648`）逐态一一对应，完整表见验收/证据文档。核心边界：

1. **NOFOLLOW + regular-file 双端证明先于 `Files.isSameFile`**（`ChangeDeleteTransaction.java:424-431,432-504`）——否则换入的符号链接会让外部文件被当成备份。
2. **V2 建立腿**：target 不存在 ⇒ `forceRollbackWithoutDeletion`；target 存在必须是“staged 同一文件”**且**字节==B1 摘要，二者任一不满足即报错不删（`ChangeCreateTransaction.java:757-838`；`ChangeCreateRecoveryEngine.java:319-407`）。
3. **V3 撤销腿**：`DELETED` 态下 target 仍存在 ⇒ **直接报错、绝不覆盖/删除**（`ChangeDeleteTransaction.java:890-896`；`ChangeDeleteRecoveryEngine.java:430-442`）；target 缺失才从备份 `createLink` 复原。
4. **TOCTOU 收窄**：写完 durable intent 后、执行破坏性动作前，重新校验同一性与摘要（`ChangeCreateTransaction.java:800-808`；V3 恢复 `restoreTargetFromBackup` 内同样先证后链）。
5. **字节数 + SHA-256 复验**为“外部文件不得误删”的唯一判据（`ChangeDeleteTransaction.verifyFileMatchesB0:965-980`、`ChangeCreateTransaction.java:775-787`）。
6. **CURRENT.new 只有内容逐字节等于 `<b1>\n` 才允许删除**（`ChangeDeleteRecoveryEngine.java:772-822`）。
7. **对 V4 更新腿的关键差异**：V2/V3 都**没有“用旧字节覆盖已存在 target”的分支**；V1 更新腿才有（先证 target==候选摘要，再删 target，再 `move(backup,target,ATOMIC_MOVE)`，`ChangeApplyTransaction.java:588-672`）。⇒ V4 更新腿回滚必须采用 V1 的**证明**（盘面 == 本事务写入的候选摘要），但备份用 D2 的硬链接；“DELETED 态 target 存在即失败”的 V3 规则**不迁移**到更新腿（更新腿 target 本就应存在）。

**影响**：D1 的状态机形态可冻结为“V3 硬链接状态名 + 一条更新专用替换/回滚分支”；D4 的反向补偿规则在更新腿必须以“候选摘要证明”为进入条件（与 §5 D4 现有表述一致）。

### P7 结论：`JournalGate` 解析/阻塞用例清单

**门层**（`JournalGate.java`）：transactions 目录缺失 ⇒ 开放（`:33-35`）；目录是符号链接/非目录/读取失败 ⇒ `SIR-APP-CHANGE-RECOVERY-002`（`:37-56`）；子项符号链接/非目录 ⇒ 错误（`:84-87`）；空目录 ⇒ 删除、不算错误（`:98-103`）；有物料无 `journal` ⇒ 错误（`:106-108`）；`journal` 是符号链接/非普通文件 ⇒ 错误（`readHeaderBytes:221-229`）；MAGIC 按 V3→V2→V1 分派（`:191-214`）；**未知 MAGIC ⇒ 错误**（`:215-217`）；族内解析失败 ⇒ 透出族码（`:193-214`）；B0/B1 与 CURRENT 不符 ⇒ 错误且不算活跃（`:112-130`）；单个活跃日志 ⇒ `SIR-APP-CHANGE-RECOVERY-001`（`:154-171`）；多个 ⇒ `-002`（`:173-179`）；`isOpen()` = errors 为空（`:422-424`）。

**解析层严格性**（V2 `CreateTransactionJournal.parseBytes:175-…`；V3 `DeleteTransactionJournal.parse:…`；V1 同形）：magic 过短/不符；CR；非法 UTF-8；结尾必须 LF；空行；头乱序/重复/出现在初始段之后；family 字面量不符；负计数；行格式错；**不安全相对路径**（`TransactionJournal.validateRelativePath:461-538`：前导 `/`、盘符、反斜杠、换行、NUL、空段、`.`、`..`、段尾点/空格、控制字符、`<>:"|?*`、Windows 保留名）；索引乱序；初始态必须 `PREPARING`；重复路径；文件路径非字典序；未注册索引的迁移；路径不一致；重复迁移；`isValidTransition` 之外的迁移；未知行；首个 overallState 必须 `PREPARING`；overallState 早于计数/记录；overallState 非单调。V3 头另有 `b0ManifestDigest`/`b1ManifestDigest`/`stagingRef`/`backupRootRef`/`backupLayout`/`candidateBaselineRef`（`DeleteTransactionJournal.java:331-451`）。

**现有基线**：`RecoveryStateMachineTest`（9 例）覆盖方向契约、幂等、CURRENT 不匹配 fail-closed 与迁移严格性；`PathSecurityReviewTest` 覆盖路径段强度（5 例 Windows-only skip）。**已识别缺口**：本模块内**没有任何测试直接构造/驱动 `JournalGate.inspect`**（源码检索：测试目录无 `new JournalGate`/`.inspect(` 命中；唯一的“活跃日志阻塞”测试在 `kcg-cli` 的 `KcgCliWorkflowTest:198-204`）。⇒ 实现 V4 时需新增门层用例（未知 MAGIC、乱序、重复索引、非法迁移、不安全路径、活跃 V4 阻塞），并可一并补 V1/V2/V3 门层回归。这是实现期测试范围，不改变本单完成门。

### 4.2 第 2 步（计划绑定与预检路径守卫）实现证据（2026-09-26，seam A）

本步只做 D6 的清单级绑定与 D3 的计划条目盘面前置，**不新增公开入口、不触碰 V1/V2/V3、`JournalGate`、恢复引擎与任何公开 apply API**。生产改动集中在 `sir-toolchain-application` 两个文件：

| 文件 | 改动 |
| --- | --- |
| `internal/state/RenamePlanBindingVerifier.java` | **新增**。以 B0/B1 清单重建“仅 B0→撤销、仅 B1→建立、两侧同路径内容异→更新、两侧内容同→未变”四分区，要求分区恰等于计划三集合；逐条双向校验 artifactId/sha/owner/字节数；`Optional.empty()` 的条目 owner 在进事务前拒绝 |
| `internal/PlanProtector.java` | **新增**计划条目三形态 `protectUpdates`/`protectWithdrawals`/`protectEstablishments`（共用私有实现；既有三形态一行未改） |

诊断码（新前缀 `SIR-APP-RENAME-BIND-00N`；开工前检索全仓库确认无冲突）：

| 码 | 含义 |
| --- | --- |
| `SIR-APP-RENAME-BIND-001` | 计划条目 owner symbol 缺失（**冻结码**，P2 要求的显式拒绝） |
| `SIR-APP-RENAME-BIND-002` | 清单结构不可信（B0/B1 出现重复 relativePath） |
| `SIR-APP-RENAME-BIND-003` | 计划条目在应持有它的清单侧没有条目 |
| `SIR-APP-RENAME-BIND-004` | 身份不一致（artifactId，或 owner symbol，或清单侧 owner 缺失） |
| `SIR-APP-RENAME-BIND-005` | 内容不一致（字节数或 SHA-256） |
| `SIR-APP-RENAME-BIND-006` | 清单分区不等于计划三集合（漏报/多报/类别错） |
| `SIR-APP-RENAME-BIND-007` | 内容未变的受管路径在 B0/B1 之间身份或内容漂移 |

绑定诊断均为 `ChangeExecutionStage.PROTECT` 的 ERROR，单路径类诊断带 affected path；`PlanProtector` 的计划条目形态沿用既有三形态的上报口径（`ExecutionStage.GRAPH`，调用方仍映射为 PROTECT）与既有 `SIR-APP-CHANGE-PROTECT-105…112` 码，不新造并行码族。

测试（新增 2 类 + 1 个共用真实 fixture 支持类，`26/0/0/0` 全通过）：

| 类 | 例数 | 覆盖 |
| --- | --- | --- |
| `RenamePlanBindingVerifierTest` | 15 | 真实计划 + 真实 B0/B1 清单绑定成功（并断言 touchedPaths、清单侧非空唯一性、两棵树零变化）；001×3（更新/撤销/建立条目空 owner）、002、003、004×2、005、006×4（漏报/多报/未命名内容变/未命名新路径）、007、合成三集合成功例 |
| `RenamePlanEntryProtectionTest` | 11 | 真实计划条目对真实基线树全部通过（非空转：逐条比对盘面摘要与 establishment 缺失）；对候选树反向全拒绝；更新形态用真实 base 对与**不同的候选摘要**驱动：基线盘面通过、被外部写成恰好候选字节仍→109、改长度→108；被改写→109、被删除→107、目录占位→107、符号链接→106（撤销/建立/父目录链）、未受管文件占位→111、目录占位→111 |
| `RenamePreflightTestSupport` | — | 真实 fixture：`ToolchainApplication` 落盘 + `SirCompiler` 内存编译（断言两侧图摘要相同）+ `BaselineBuilder.buildManifest` 造清单（与 `BaselineBuilder.build` 同源），并逐条断言清单 == 盘面字节 |

拒绝路径的每例都断言：诊断码、阶段、受影响路径，以及**盘面零变化**（前后逐文件摘要快照相等）。

命令（本机、离线、定向，包 `systemd-run --scope -p MemoryMax=2G -p CPUQuota=150%`，无 Maven `-T`）：

```bash
mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
  -Dtest='RenamePlanBindingVerifierTest,RenamePlanEntryProtectionTest' -Dsurefire.failIfNoSpecifiedTests=false
# Tests run: 26, Failures: 0, Errors: 0, Skipped: 0 -> BUILD SUCCESS
```

零回归（同一包内，PlanProtector 改动面）：

```bash
mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
  -Dtest='RenamePlanVerticalTest,ApplyFaultMatrixTest,CreateFaultMatrixTest,DeleteFaultMatrixTest,RecoveryStateMachineTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
# Tests run: 78, Failures: 0, Errors: 0, Skipped: 0 -> BUILD SUCCESS
```

**独立复审与补强（2026-09-26）**：复审发现原更新守卫测试的 base/candidate 摘要相等，交换二者仍会假绿；已改为先生成与基线不同的候选字节及真实摘要，断言基线通过、盘面被外部写成**恰好候选字节**仍以 109 拒绝，长度改变以 108 拒绝。仅重跑受影响的 `RenamePlanEntryProtectionTest`：11/0/0/0，`systemd-run --scope -p MemoryMax=1500M -p CPUQuota=125%`（本机 2 核、当次 available 1855 MiB）。复审还确认 V4 的建立腿**必须使用排他创建**（如 V2 `Files.createLink(target, staged)`），不能依赖预检后 `ATOMIC_MOVE` 不带覆盖参数；本机 P3 实测它可直接替换外部文件。更新腿在原子替换前还须重新证明 target 的 B0 身份与摘要。两项须有故障/外部竞争测试，否则不通过完成门。

**本步的显式边界**：UPDATE 腿只有合成证据（P1）；`planDigest`/subject 身份绑定、`basedOn`↔CURRENT 校验属 V4 日志头与入口（D6 后半、D0），不在本步；`applyRename`、V4 日志族、`JournalGate` 分派、事务主体、恢复、故障矩阵、盘面逐字节一致性均为后续步骤；本步不产生任何公开 API 变化，`RenamePlanVerticalTest:353-362` 的按类型“不可 apply”断言在本步仍然成立（`ChangeApplyRequest` 与 `ChangeExecutionApplication` 公开方法均不接受 `RenamePlan`）。

### 4.3 第 3 步（V4 journal 与 JournalGate；seam B1）实现证据（2026-09-26）

本步只实现 D1/D6 的 V4 日志格式、严格解析与门层识别/阻塞，并为恢复引擎加入显式 fail-closed 分支以满足 sealed variant 的穷尽分派。后续复审补强 overallState/file-state 阶段兼容、header 验证与路径编码。**未实现任何事务主体、V4 apply 入口或真实 V4 恢复**；V1/V2/V3 格式和分支未改。

| 文件 | 改动 |
| --- | --- |
| `internal/state/MixedTransactionJournal.java` | V4 `MIXED` 格式；构造/解析验证 transactionId、B0/B1 IDs、manifest/plan digests、规范化绝对单行 output root、非空单行 subject。路径采用 canonical UTF-8 Base64URL（无 padding）`path64=`，保留合法含 ` kind=` / ` state=` 的路径并消除歧义。SYNC 创建/追加；解析严格校验行/header、路径、索引、逐 kind 和 overall 状态迁移及 overall/file 阶段兼容；terminal 后拒绝事件 |
| `internal/state/JournalGate.java` | 仅新增 V4 magic dispatch、sealed `V4Mixed`、`V4_MIXED` family；V1/V2/V3 分支保留 |
| `internal/state/ChangeRecoveryEngine.java` | V4 被识别后明确返回 `RecoveryRequired`，不执行文件操作 |
| `internal/state/MixedTransactionJournalTest.java` | 9 例：正常 writer/readback；真实序列化字节突变测试跳级、PREPARING 文件到 COMMITTED、published 后 rollback/file event、未完成文件的 COMPLETED/ROLLED_BACK、terminal 后事件；append 同样拒绝不兼容状态；delimiter-bearing 合法路径往返与损坏编码拒绝；terminal residual JournalGate 阻塞；V1/V2/V3 dispatch 回归 |

Overall 状态图：前向只允许 `PREPARING→PREPARED→COMMITTING→FILES_COMMITTED→PUBLISHING→BASELINE_PUBLISHED→CLEANUP_PENDING→COMPLETED`；反向允许 `PREPARING|PREPARED|COMMITTING|FILES_COMMITTED|PUBLISHING→ROLLING_BACK→ROLLED_BACK`。`BASELINE_PUBLISHED` 后不允许反向迁移，因为 V1/V2/V3 在 CURRENT 原子切到 B1 后才记录该态；`PUBLISHING→ROLLING_BACK` 仍允许 CURRENT 尚为 B0 的发布失败补偿。

Writer append 与 parser 共用 overall/file 兼容约束：PREPARING/PREPARED 仅允许所有文件 PREPARING；COMMITTING 允许按 kind 正向推进但不允许 rollback 状态；FILES_COMMITTED、PUBLISHING、BASELINE_PUBLISHED、CLEANUP_PENDING、COMPLETED 要求 UPDATE/WITHDRAW/ESTABLISH 分别处于 REPLACED_DURABLE/DELETED_DURABLE/CREATED_DURABLE；ROLLING_BACK 容纳已触碰条目的中间态并接受补偿迁移；ROLLED_BACK 仅允许未触碰的 PREPARING 或已补偿的 ROLLED_BACK_DURABLE。终态后禁止任何文件或 overall 事件。

`path64` 是未发布 V4 格式的明确 wire-format 决定：UTF-8 字节的 canonical Base64URL 无 padding，解析校验 canonical 形式并严格 UTF-8 解码，避免合法路径与 `kind=` / `state=` 分隔符冲突。JournalGate 仍将 terminal residual journal 判为 active 并阻塞写路径；本修正针对不可能的日志历史，不声称发现门层绕过。

本次 reviewer correction 只运行受影响的定向类（本机 2 vCPU、执行前 available 约 1.3 GiB；离线 Maven，无 `-T`、无并发；systemd 限额 `MemoryMax=1100M`、`CPUQuota=125%`）：

```bash
systemd-run --scope -q -p MemoryMax=1100M -p CPUQuota=125% -- \
  mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
  -Dtest=MixedTransactionJournalTest -Dsurefire.failIfNoSpecifiedTests=false
# MixedTransactionJournalTest: 9/0/0/0; BUILD SUCCESS
```

focused 类首轮暴露了 fixture 在 PREPARING 写 file event，以及成功 fixture 未提交所有变更就标记完成的问题；已调整到显式 phase/file 约束后再运行最终验证。上一轮 header 单行验证的字符字面量编译错误也已修复。`git diff --check` 通过。未运行全量构建/业务 IT；未提交或推送。

**边界与后续**：日志/gate 切片不代表混合事务可执行或恢复；V4 recovery 仍 fail closed。后续事务实现必须逐分支证明 file-state 实际顺序、establishment 排他创建、update 替换前 B0 identity/digest 复验。

### 4.4 第 4/5/7 步（事务主体、恢复、故障矩阵；seam B2/B3）实现证据（2026-09-28）

本段实现内部事务主体、`CURRENT` 定向恢复与故障矩阵。**仍未公开 `applyRename` 或 CLI**；普通 `apply` 不接受改名计划（第 6/8/9 步随 seam C 单独收口）。V1/V2/V3 与 `sir-change` 未改。

| 文件 | 改动 |
| --- | --- |
| `internal/state/MixedTransactionCore.java` | 由 B0/B1 清单重建三集合；预检（CURRENT==B0、输出根双向绑定、候选字节与 B1 清单逐条一致、建立路径未被占用、其余路径 B0 身份、stateRoot 与 outputRoot 同 FileStore）；prepare（事务目录、V4 日志、staging、暂存 B1 Bundle）；`COMMITTING` 先逐文件记录并建立硬链接备份，再按 kind 落盘：UPDATE 在原子替换前复验备份身份与 B0 摘要、置 `REPLACE_INTENT_DURABLE` 后 `ATOMIC_MOVE + REPLACE_EXISTING`；WITHDRAW 复验后删除；ESTABLISH 用排他 `createLink`（P3 实测 `ATOMIC_MOVE` 可能替换占位者，故不用于建立）；随后 `FILES_COMMITTED` → `PUBLISHING`（暂存 B1 Bundle → `CURRENT.new` → CURRENT → 重读校验）→ `BASELINE_PUBLISHED` → `CLEANUP_PENDING` → `COMPLETED` → 清理事务目录 |
| `internal/state/MixedTransactionRecoveryEngine.java` | `CURRENT=B0` ⇒ 记 `ROLLING_BACK` 后逐 kind 反向补偿（建立：摘要 + 与 staging 同一身份才删；撤销：硬链接复原；更新：证明 target 为本事务 B1 字节后把备份移回），再 `verifyOutput(B0)`、仅删除内容恰为 B1 的 `CURRENT.new`、清理；`CURRENT=B1` ⇒ 校验 B1 清单 + 删除本事务 `CURRENT.new` + 清理；两侧都要求重复恢复幂等 |
| `internal/state/ChangeRecoveryEngine.java` | V4 分派到上述恢复引擎；V1/V2/V3 分支不变 |
| `internal/state/MixedTransactionCoreTest.java` | 10 例：三 kind 成功路径 + 发布顺序 + 备份保真、候选字节不符时零变更、`FILES_COMMITTED` 前中断、`CURRENT=B1` 清理、外部占位者不被覆盖、故障矩阵、双向外部文件拒删 |

**清理的安全前提**（本段补强的关键不变量）：清理前逐个校验事务目录 —— 日志身份与 B0/B1 摘要一致、文件集恰等于本事务期望集（逐条摘要）、目录集受限、无符号链接；出现任何未知或被改写文件即以 `RecoveryRequired` 停止且不删除任何东西（外部字节逐字节保留）。失败一律保留 V4 日志供恢复；成功则移除事务目录，`JournalGate` 随之重新开放。

**故障矩阵**（每例都断言：输出树逐字节等于 B0 清单、无多余文件、`CURRENT=B0`、事务目录已清理、二次恢复幂等）：`BACKUP_LINK_INTENT`（UPDATE/WITHDRAW）、`BACKUP_LINKED`、`DELETE_INTENT`、`REPLACE_INTENT`、`REPLACED`、`DELETED`、`CREATE_INTENT`、`CREATED`、提交完毕未发布、B1 Bundle 已暂存未写 `CURRENT.new`、`CURRENT.new` 已写未切 CURRENT；另加 `CURRENT=B1` 的“已发布未清理”方向，以及外部文件拒删（B0 侧三类目录、B1 侧备份目录）。

本机定向（2 vCPU；离线 Maven、无 `-T`）：

```bash
systemd-run --scope -q -p MemoryMax=1100M -p CPUQuota=125% -- \
  mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
  -Dtest=MixedTransactionCoreTest,MixedTransactionJournalTest -Dsurefire.failIfNoSpecifiedTests=false
# MixedTransactionCoreTest: 10/0/0/0; MixedTransactionJournalTest: 9/0/0/0; BUILD SUCCESS
```

**实施期订正**

- **C1（测试侧顺序错误，非产品缺陷）**：故障注入用例曾在 `COMMITTING` 下追加 `ROLLBACK_REPLACE_INTENT_DURABLE`，被 V4 格式正确拒绝（回滚文件状态只允许出现在 `ROLLING_BACK`）。按真实序列先记 `ROLLING_BACK` 后修正，**未放宽日志状态机**。
- **C2（工具链中断，如实登记）**：2026-09-26 夜间的 seam B2/B3 子代理运行在写出结果前被宿主回收（run 记录消失，完成通知未送达），会话空置约 1 天 16 小时；其已落盘的改动经父代理复核后保留并由后续 seam 继续，**不按“已完成”计**。
- **C3（残留覆盖缺口）**：外部文件拒删测试覆盖“未知文件”（B0 侧三类目录、B1 侧备份目录），未逐目录分别覆盖“已存在但字节被改写”的文件；两者走同一校验分支，余下组合由 seam C 的复审补齐或显式登记。

### 4.5 第 6/8/9 步与矩阵钉桩（seam C/C2）实现证据（2026-09-28）

本段接入公开入口并补齐端到端证据与矩阵钉桩；**Q17 的全部实现与定向验证到此完成**。当时全量闸门与业务 IT 尚待提交后 CI 验证；现已在同一 SHA 全绿，见 §14。

| 文件 | 改动 |
| --- | --- |
| `api/RenameApplyRequest.java`（新） | `stateRoot`、`outputRoot`、`expectedBaselineId`、`candidateSirFile`、`declarationSymbol`、可选 `expectedCandidateSirSha256Hex`（同 `ChangeApplyRequest` 的小写 hex 校验与 digest-bound 工厂）。**调用方不提供计划**：入口自己编译两版并驱动 Q16 `RenamePlanner` |
| `api/RenameApplyResult.java`（新） | sealed：`Applied`（B0/B1 回执、计划触碰路径、`transactionCleaned`）、`NoChanges`、`Failure`、`RecoveryRequired` |
| `api/ChangeExecutionApplication.applyRename(...)` | 复用五段既有纪律：`StateRootPathGuard` → `StateRootLock` → 重校验 → 读 CURRENT → `JournalGate` 门（阻塞即 `RecoveryRequired`，不继续）→ 基线/输出根绑定；候选字节等于基线字节时在写任何东西前返回 `NoChanges`；然后 Q16 计划 + `RenamePlanBindingVerifier` + `PlanProtector` 三套前置 → `MixedTransactionCore`。失败带 `RecoveryHandle` 返回 `RecoveryRequired`，保留日志供恢复 |
| `internal/state/MixedTransactionCore.java` | 仅放宽类/入口/结果记录可见性以跨包调用，**事务算法未改**（硬链接备份 + B0 复验 + 排他建立等不变量原样保留） |
| `test/.../RenameApplyEndToEndTest.java`（新） | 4 例：真实管线成功路径（含与候选从零生成的逐字节比对、旧路径消失/新路径出现、未触碰文件不变、`CURRENT=B1`、无 `CURRENT.new`、事务目录清空、门禁重开、重复执行 `NoChanges` 且零抖动）；原课程例子越界改名拒绝（`SIR-RENAME-PATH-004`）；阻塞日志返回 `RecoveryRequired`；外部占位与基线不匹配拒绝——四类拒绝都断言盘面零变化 |
| `test/.../RenamePlanVerticalTest.java` | Q16 旧“按类型不可 apply”断言替换为正反契约：`applyRename` 是唯一改名入口、`RenamePlan` 非 `ChangeOperation`/`ChangePlan`、两个请求记录均不承载计划、其它公开方法不接受计划、普通 `apply` 不接受计划 |
| `test/.../MixedTransactionCoreTest.java` | 矩阵钉桩：`CRASH_SCENARIOS` 9 条（逐条断言崩溃像的 overall/file 状态与“其余文件仍 PREPARING”）、`INJECTED_CRASH_POINTS` 4 个钩子、外部产物 `3 树 × 2 变体（未知/已改写）× 2 方向` 共 12 例拒删 |

本机定向与受影响模块（离线 Maven、无 `-T`、systemd 限额）：

```bash
# 端到端与契约
-Dtest='RenameApplyEndToEndTest,RenamePlanVerticalTest'   # 4/0/0/0 + 9/0/0/0
# 事务与日志
-Dtest=MixedTransactionCoreTest,MixedTransactionJournalTest   # 10/0/0/0 + 9/0/0/0
# 受影响模块全量（application 及其上游）
mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test
# SIR Toolchain Application: 287/0/0/5，BUILD SUCCESS（1:54）
```

**实施期订正**

- **C4（口径澄清）**：CURRENT=B0 方向的拒删测试中，恢复在遇到冒犯产物前**可能已合法追加回滚进度**到日志，因此“零字节变化”的比较覆盖事务目录中除 `journal` 外的全部路径/字节，日志另以“仍存在”断言；要求日志逐字节不变会与既有恢复行为冲突。
- **C3 已闭环**：外部产物拒删补上“已存在但字节被改写”的变体，覆盖 `backup`/`staging`/`candidate-baseline` 三树与两个方向。

**遗留**：多 subject（能力与 Input 同时改名）需先冻结 Input 持久身份，不在本单。两条全量闸门 + 四个业务 IT 的 CI 证据已补齐（§14）。

## 5. 决策表 D0–D8（负责人批准推荐项；P1–P7 实测后冻结执行口径）

| # | 推荐 | 为什么 / 备选与条件 |
| --- | --- | --- |
| D0 | **新增一个公开请求记录 `RenameApplyRequest` + 一个公开方法 `ChangeExecutionApplication.applyRename(RenameApplyRequest)`**；不新建第二个 application 类 | 锁、根校验、Journal 门、Bundle 读取、CURRENT 绑定这五段已在 `acquireAndValidate` 里完整（F4），复制它们会产生第二套锁/门实现（违反“组合已有能力”）。参数改用记录类型会让 Q16 的按类型断言（F3）**假绿**，不能拿它通过作为安全证据。必须以新的正反测试替代：只有 `applyRename` 显式消费计划，UPDATE/CREATE/DELETE 入口仍不接受 `RenamePlan`，并验证其它入口的参数包装也不得暗中承载改名计划。Q16 文档中的“完全不可 apply”自 Q17 成功后才改成此契约，需在验收记录显式登记。 原建议请求携带 `RenamePlan`；实际交付改为 `declarationSymbol`，由入口自己编译并规划，两个请求记录均不携带计划。`expectedBaselineId` 与内部计划的 `basedOn` 均绑定 CURRENT；此差异在 §15 C5 显式登记 |
| D1 | **新增一个日志族**，不复用/不改造 V1/V2/V3 | F6（V1 要求键集相同）与 V2/V3 的“纯建立/纯删除”增量校验器使三族语义互斥；混合变更无法落在任一族内。新族需要同时新增 MAGIC、解析器、sealed 变体、族枚举常量、恢复分派分支（F7 的四处机械改动）。**P4/P6 实测后裁决采用独立 V4 日志族**，按逐 kind 转移复用 V2 建立态与 V3 硬链接撤销态，再加更新替换/回滚专用态；不修改 V1/V2/V3 的冻结格式。**P4/P6 实测证据（§4.1）**：计划路径互不相交也不嵌套（顺序自由），V2=纯建立、V3=硬链接备份/删除/复原且逐态迁移严格，故推荐 V4 逐 kind 照抄 V3 的 `BACKUP_LINK_INTENT_DURABLE`/`BACKUP_LINKED_DURABLE`/`DELETE_INTENT_DURABLE`/`DELETED_DURABLE`/`RESTORE_LINK_INTENT_DURABLE`/`RESTORED_DURABLE`/`ROLLED_BACK_DURABLE`，另加“更新替换/更新回滚”专用态；全新统一状态机不再有证据优势。该形态已冻结；具体逐态转换必须由严格解析与故障注入测试逐一证明 |
| D2 | **撤销与更新都用硬链接备份** | F8 已证明同卷硬链接备份合法且已有状态机；撤销 ≈ 链接备份 + 删 target；更新 ≈ 链接备份 + 原子替换（消除 V1“路径短暂缺失”窗口）。**依赖 P3 结论**；**P3 已实测（§4.1）**：`ATOMIC_MOVE, REPLACE_EXISTING` 原子替换成功、备份仍指向旧 inode、失败可检测且无静默损坏，故**采纳本推荐、不退回 V1**；实现须显式传 `REPLACE_EXISTING`，且“备份后 target 未被外部改写”的等价检查固定为“`Files.isSameFile(target,backup)` + 备份 B0 字节数/摘要复验” |
| D3 | **三套盘面前置全部复用**（更新→`protect`，建立→`protectAdditions`，撤销→`protectDeletions`） | F9 的三套检查已就绪，只是不接受计划条目。**建议给 `PlanProtector` 增三个接受计划条目的重载**（等价逻辑，代码量极小），**不**把计划条目伪装成变更计划条目（那会引入 `Optional → 非空` 的静默假设并混两条 API 家族）。首选三个接收计划条目的重载；若类型约束实测阻挡再停轮回报，不把计划条目伪装为旧 ChangePlan 文件条目 |
| D4 | **沿用既有恢复方向契约**：CURRENT=B0 ⇒ 反向补偿；CURRENT=B1 ⇒ 前向校验 + 清理 | 与 F5 一致，避免第二套方向语义。关键不变量：**所有文件变更（含撤销与建立）在 `publishCandidateBundle/CURRENT` 之前完成**。反向补偿必须保留 V1/V3 既有规则：只有盘面字节等于**本事务写入的候选摘要**才允许删除，否则 `RecoveryRequired`。**P6 实测（§4.1）补充**：V2/V3 都没有“用旧字节覆盖已存在 target”的分支，更新腿回滚须借 V1 的“候选摘要证明 + 覆盖”模型（`ChangeApplyTransaction.java:588-672`），且 V3 的“`DELETED` 态 target 存在即 fail closed”**不迁移**到更新腿 |
| D5 | **交付差异（§15 C6）**：新增 `RenameApplyResult`；恢复请求/结果、handle、baseline 回执、清单与阶段继续复用既有类型 | 原推荐不新增回执家族，实际新增 Applied/NoChanges/Failure/RecoveryRequired 四变体：成功结果带触碰路径与清理完成标志，NoChanges 带 `RenameNoChangeReason`；普通 `ChangeApplyResult`/`ChangeApplyOutcome` 未改。本差异随已报告的公开 API 一并验收，不能叙述为完全按原推荐实现 |
| D6 | **新增 `RenamePlanBindingVerifier`**，并建议把 `planDigest` 写进日志头 | 以 B0/B1 清单为准重建“撤销/更新/建立/未变”四分区，要求：分区恰等于计划三集合、撤销/更新的基线侧与 B0 条目一致、更新/建立的候选侧与 B1 条目一致、未变路径不得漂移（照 `DeleteManifestDeltaVerifier:45-53` 的做法）。这是把 Q16 图级覆盖落到清单级覆盖的桥；先冻结空 owner 的显式拒绝码 `SIR-APP-RENAME-BIND-001`，其他清单不匹配使用同一新前缀的明确子码；实施前查现有代码确保不冲突、诊断含阶段/受影响路径，不得由负载构造器异常兜底。日志头带 `planDigest` 是为了防止把**另一个计划**的证据当成本事务的证据（P5）。**P5 已实测（§4.1）**：类别确可由 B0/B1 清单重建（`RenamePlannerCore.changeOf:544-556`），但“本事务属于哪份计划/哪个 subject”不可重建，且恢复入口不接计划（`ChangeExecutionApplication.java:1361-1417`）⇒ 采纳“日志头绑 `planDigest`（并建议带 subject 符号）”，同时要求恢复仍须只凭日志 + B0/B1 可完成、prepare 时断言“重建分区 == 计划三集合” |
| D7 | **计划候选字节由 applyRename 自己重编译候选得到，不接受用户提供的候选目录** | 这样“成功盘面 == 候选从零生成”是**构造性保证**而非事后比对；计划里的候选摘要逐条与生成字节核对，B1 清单由 `BaselineBuilder.buildManifest` 从生成文件构造，形成双重绑定。接受用户候选目录会把该不变量降级为对既有目录的信任 |
| D8 | **明确不在 Q17 内**：能力与 Input 声明同时改名的多 subject 计划（需先冻结 Input 持久身份）；实体字段改名/物理列继承/DDL（G3）；放宽 `ChangePlan` 单族不变量；多文件源清单与 import/binding；CLI 接线；自动提交/推送 | 见 §10 禁止范围；这些都不是本单的完成门，也不能由本单的事务自动补足 |

## 6. 实施步骤（**获批准后**按序；每步都以定向测试收口）

0. **P1–P7 实测** → 回填本单，冻结日志族形态、更新腿技术与计划绑定字段；若 P1 未找到真实非空 UPDATE，用受约束合成图/Bundle 的有效计划证据验证该腿，明确区分真实管道与合成证据；若此路线无法成立，停轮报负责人，不得自行缩范围或以手工字符串冒称真实产物。
1. **现状钉桩（RED 前置）**：真实 fixture 的 `RenamePlan` 三集合与盘面摘要一致、`updates` 为空（沿用 `RenamePlanVerticalTest`，不改）；未受管文件占用建立路径 → 拒绝且盘面不变；旧路径被外部改写 → 拒绝。
2. **计划绑定与守卫**：`RenamePlanBindingVerifier`（D6）+ `PlanProtector` 的计划条目入口（D3），先把拒绝路径跑通。**（seam A 已实现并定向验证，见 §4.2。）**
3. **日志族**：MAGIC、严格解析、状态机、`JournalGate` 分派与阻塞（D1、P7）。
4. **事务主体**：prepare（事务目录、日志、staging、暂存 B1 Bundle）→ commit（**先逐文件建立硬链接备份并记录，再执行任何撤销/更新**；V4 的 PREPARING/PREPARED 阶段只接受文件初态，备份状态必须在 COMMITTING 阶段记录；逐 kind 落盘，建立路径用排他创建、更新在原子替换前重证 B0 身份与摘要；任何外部文件不得被覆盖）→ publish（Bundle → `CURRENT.new` → CURRENT → 重读校验）→ cleanup。
5. **恢复**：逐条 kind 的反向补偿 + 前向校验清理（D4，按 P6 的边界清单逐个分支核对）。
6. **接线**：`ChangeExecutionApplication.applyRename`（D0）+ 回执（D5）。
7. **故障矩阵**：覆盖全部前向注入点各注入一次 + 回滚专用分支；断言 F10 的六条方向不变式与“注入点必须真的到达”。
8. **成功盘面一致性**：逐相对路径、逐字节等于候选从零生成；事务目录已清理、`CURRENT.new` 不存在。
9. **Q16 断言语义登记**：替换 `RenamePlanVerticalTest:353-362` 原有按类型的不可 apply 断言为新的正反合同测试：`applyRename` 是唯一显式改名入口，普通 apply 无法通过包装请求或参数类型接受 `RenamePlan`；在本单登记 Q16 的旧断言不再适用，待 Q17 验收时再在 Q16 归档件加交叉注记。

## 7. 测试矩阵与灵敏度要求

1. **成功路径**：`CURRENT=B1`、旧路径不存在、新路径存在、`readTree(outputRoot)` 逐字节等于从零生成、回执清单 == B1 清单、事务目录已清理。
2. **计划绑定拒绝**：多报/漏报/类别错/摘要错/owner 缺失 → 事务前拒绝、盘面零变化、CURRENT 不变。
3. **未受管文件保护**：建立路径被未受管文件占用、旧路径被外部改写、符号链接/目录占位 → 拒绝且盘面不变（**灵敏度探针：必须用真实违规字节驱动拒绝路径返回非空诊断，不能只断言“不抛异常”**）。
4. **故障矩阵**：每个前向注入点注入一次；钩子表长度用断言钉住（照 F11），保证矩阵不会静默缩小；每条断言 F10 的方向不变式。
5. **回滚专用分支**：分别以“备份链接已建但 target 未删”“target 已删未恢复”“更新已替换”为入口，验证补偿把树恢复到逐字节 B0 且 `CURRENT=B0`。
6. **前向恢复与幂等**：CURRENT=B1 但事务目录残留 → 校验 B1 清单并清证据；重复 `recover` 幂等。
7. **恢复 fail-closed**：盘面被外部改动（建立路径字节 ≠ 候选摘要）→ `RecoveryRequired`，绝不删除外部文件。
8. **日志解析严格性**：未知 MAGIC、乱序、重复索引、非法状态迁移、不安全相对路径 → 解析失败；活跃 V4 日志阻塞写路径。
9. **零回归边界**：V1/V2/V3 及其既有测试**零改动**；`sir-change` 零改动；六类 `ChangeOperation` 仍为 6、`ChangePlan` 仍拒混合族。

## 8. 验证命令与证据口径

本机（2 vCPU / 3 GB）**只跑受影响的定向测试**，按步骤小批推进；不跑全量闸门、不跑业务 IT，不加 Maven `-T` 并行：

```bash
# 单步冒烟（每完成一小步都先跑这个量级；把 CLASS 换成该步新增的测试类）
mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
  -Dtest=CLASS -Dsurefire.failIfNoSpecifiedTests=false

# 阶段收口：Q17 自身 + 三族故障矩阵与 Q16 计划回归（仍然只跑列出的类）
mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test \
  -Dtest='RenameApply*Test,RenamePlanVerticalTest,ApplyFaultMatrixTest,CreateFaultMatrixTest,DeleteFaultMatrixTest,RecoveryStateMachineTest' \
  -Dsurefire.failIfNoSpecifiedTests=false

# 确保 sir-change 与 Q16 计划零回归
mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-change -am test
```

- **两条全量闸门 + 四个业务场景 IT 只在获授权提交/推送后由 GitHub CI（`.github/workflows/verify.yml`）对同一棵待验收源码跑一次**，报告必须给出 **commit SHA + run URL + artifact（`surefire-reports`、`conformance-evidence`）**；本机不重复跑（`AGENTS.md`「测试资源约束」）。
- **不为触发 CI 自动提交或推送**；提交/推送需负责人显式授权。
- CI 或参考环境不可得时，对应门记 **`NOT_RUN`**，并写清缺失原因与影响；**不得**以“本机跑了一遍定向测试”冒称全量通过。
- 本机确需重活时必须包在 `systemd-run --scope -p MemoryMax=2G -p CPUQuota=150% -- …` 内，且不与其它重活并发。

## 9. 设计原则对照（`AGENTS.md` 的三问）

1. **是否主要靠组合已有能力？** 是：锁/根校验/Journal 门/Bundle/CURRENT 全部复用（F4），盘面前置复用（F9），恢复方向复用（F5），计划与验证复用 Q16 公开 API。新增只有“计划条目 ⇄ 盘面”的绑定与一个文件事务族。
2. **普通需求在 SIR 中是否变得绕口？** 否：Q17 **不新增任何 SIR 表面语法**（Q16 的 `@id` 已足够）。用户可见操作仍是“改完名 → 编译候选 → 一次 applyRename”。
3. **修复一类问题能否让一类业务共同受益？** 是：把“混合路径文件的原子替换 + 恢复”做成通用事务原语后，将来的字段改名、跨文件移动、多文件源清单可以复用同一族，而不是每个需求各写一套。

## 10. 允许 / 禁止范围

- **允许**：`sir-toolchain-application` 内新增请求记录、事务/日志/恢复/校验类与对新日志族的 `JournalGate` 分派分支；`PlanProtector` 的计划条目重载；`ChangeExecutionApplication.applyRename` 一处公开入口；相应的定向测试与故障矩阵；本单与状态镜像的文档更新。
- **禁止**：能力与 Input 声明**同时改名**的多 subject 计划（需先冻结 Input 持久身份，另立单）；实体字段运行时改名、物理列继承或任何 DDL/G3 内容；多文件源清单、import/binding、模块实例、nodeKey、跨文件移动；放宽六类 `ChangePlan` 的单族不变量；修改 V1/V2/V3 的既有日志行为或恢复语义；改 `sir-change`；CLI 接线；未受管文件清理；任何自动 `git commit`/`push`/`checkout`/`restore`/`reset`。

## 11. 完成门

- 单文件、单声明、已验证 `RenamePlan` 的一次原子应用有直接证据：成功路径 `CURRENT=B1`、旧受管文件不留存、盘面逐字节等于候选从零生成、回执与 B1 清单一致、事务目录已清理。
- 混合三集合（至少真实可达的两集合 + P1 裁决所确定的范围）在同一事务内完成；所有文件变更在 CURRENT 发布之前完成。
- 故障矩阵覆盖全部前向注入点与回滚专用分支，方向不变式（F10）全部成立，注入点真的到达，矩阵长度被断言钉住。
- 未受管文件在任何阶段都不被删除或覆盖；方向、路径或补偿证据不确定时一律 fail closed。
- V1/V2/V3 与 `sir-change` 零行为改动；Q16 断言语义被显式替代并登记。
- 本机定向测试通过；两条全量闸门 + 四个业务 IT 在获授权提交后由 CI 对同一 SHA 跑一次并有 artifact；不可得则记 `NOT_RUN`。
- 纯文档阶段只做 `git diff --check` 与 `git status --short`。

## 12. 与 G2 的关系（本单**不**关闭 G2）

G2 的完成门是“重命名、移动、重复 ID、引用闭包和旧身份兼容有直接证据”，对应 LANG-01..05（设计 07 §4）：LANG-01 要求“**跨文件移动/重命名**并保留 ID”。Q17 只覆盖**单文件、单声明**的改名落盘与恢复，因此：

- **仍未完成**：多文件源清单、import/binding、模块实例、nodeKey、跨文件移动、引用闭包与旧身份兼容的完整证据；能力与 Input 的组合改名。
- **已登记独立项（延续，不属本单）**：错误信封码名与设计 02 的逐字对齐（Q10 裁决保留现状）；`SpringBootModelLowerer.java` 的 CFR 反编译文本是否重写；非分页 find 的关联读取、关联深度 3 层及以上、预算口径替代方案；`in`/`isNull`、when-present 可选过滤、按关联字段排序、关联集合自身分页；变更影响模型是否允许操作改动幸存文件（例如 `Application.java` 的 actor 传输片段）；实体字段改名后的物理列名继承与 G3 数据库迁移。
- **Q17 通过不表示用户可以安全执行组合改名**，也不表示 G2 阶段完成。

## 13. 后续候选（本单立案后仍保留，均不构成本单授权）

1. 能力与 Input 同时改名：需先冻结 Input 持久身份与多 subject 计划，不能由 Q17 的文件事务自动补足。
2. G2 其余门禁：多文件源清单、import/binding、模块实例、nodeKey、跨文件移动与旧身份兼容的完整证据。
3. **Q12 路由模板**与 **BIZ-07 起的报名业务**：均未立项，不占用 G2 当前授权。

后续立案先按 [`../README.md`](../README.md) 的阶段门与 [`../REMAINING_WORK.md`](../REMAINING_WORK.md) 的候选核对事实；候选与本节清单都不替代授权。

## 14. 验收记录

- **负责人确认**：2026-10-03（确认 Q17 验收）；2026-10-04 完成文档收口。验收仅限本单的单文件、单能力声明改名应用，不关闭 G2。
- **受测提交**：`ab2ce09decce7b3f157ea1a52f1cc54bff5e4d7b`；[GitHub CI run `36404651840`](https://github.com/HoloNova/Software-IR/actions/runs/36404651840)，2026-09-28 完成，conclusion=`success`。产物 `surefire-reports`、`conformance-evidence` 已下载核对。
- **两条全量闸门**：冻结形式与完成形式各 **893 run / 0 fail / 0 error / 5 skip**，均 `BUILD SUCCESS`。按每条闸门的 Maven 日志汇总：parser 84、semantic 184、lowering-api 4、lowering-spring-boot 86、generator 77、project-graph 72、change 69、application 287、CLI 30；5 skip 仍是 Windows junction 用例，无新增 skip。
- **新增量**：Q16 为 837，本单 +56，全部来自 application：`Q17PreflightProbeTest` 7、`RenamePlanBindingVerifierTest` 15、`RenamePlanEntryProtectionTest` 11、`MixedTransactionJournalTest` 9、`MixedTransactionCoreTest` 10、`RenameApplyEndToEndTest` 4；`RenamePlanVerticalTest` 的 9 项仅替换契约、不增加计数。
- **产物计数与闸门计数分离**：上传 XML 合计 **897/0/0/5**（application 291），包括后续单独运行的四个业务 IT；不能把它当作全量闸门的 893 或本单新增量。
- **四个真实 MySQL + HTTP 回归**：Q9 查询 40/0、Q10 写侧 61/0、Q11 关联 36/0、Q13 变更闭环 63/0，全部 `verdict=PASSED`。每份报告都含 `schemaName=kcg_conf_run`、锁持有、`schemaSource=TEST_FIXTURE_DDL` 与 `generatedProjectBuild=PASSED (exit 0)`；查询预热另有一份 40/0 报告，不重复计为第五个场景。
- **Q17 专项**：真实 SIR 单能力改名，落盘逐路径/摘要等于候选从零生成；旧 Service/Controller 消失、CURRENT=B1、事务清理、重复 NoChanges；越界改名/占位/陈旧基线/阻塞日志拒绝且盘面不变。三集合合成事务、9 条崩溃像、4 个注入钩子、12 例外部产物拒删与双向恢复幂等均由同 SHA 测试覆盖（§4.4–4.5）。
- **边界**：真实 Q16 能力计划只产生撤销+建立；UPDATE 腿为合成证据。组合改名、跨文件移动、CLI、数据库迁移和 G2 其余 LANG 门仍未完成。
- **收口动作**：本单移入 `completed/`，`ACTIVE_WORK.md` 回到 IDLE，同步资格、覆盖与状态文档；不改生产代码、不重跑测试、不自动提交/推送。

## 15. 裁决与订正记录

- D0–D8 以实施期 P1–P7 实测口径及下列公开 API 差异验收；不放宽 `ChangePlan` 的单族语义或 Q16 的单 subject 覆盖规则。
- **C1–C4 接受**：测试回滚顺序笔误已纠正而未放宽状态机；子代理中断不算交付；外部已改写文件覆盖已补齐；B0 拒删时日志可合法追加进度，外部产物字节不得变化。
- **C5（D0 请求形态差异）接受并登记**：请求不携带计划，入口以声明符号与候选源重新规划、验证并绑定 CURRENT。这不是增加多声明范围，也不是由调用方塞入任意候选工程。
- **C6（D5 回执形态差异）接受并登记**：公开 `RenameApplyResult` 为独立四变体；复用既有恢复/基线类型，普通 apply 类型不变。§4.5 已报告实际 API，本次验收不将其冒称为原建议的完全复用。
- **C7（验收汇总数字错误）纠正**：先前会话将闸门报成 878、将 Q17 增量报成 +103（后又误报 +41），并误报 lowering 67/application 291。以本次直接核对 CI 原始日志与 XML 为准：双闸门各 893，lowering 86/application 287，Q17 +56；上传 XML 897 则含四个业务 IT。CI 全绿结论不变，不改测试或排除项。
- Q16 仅计划不可 apply 的历史契约由 Q17 唯一 `applyRename` 入口的正反合同替代；Q16 归档证据不改写。后续候选均未获实施授权。
