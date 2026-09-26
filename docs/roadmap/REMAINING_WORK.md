# KCG-Code 剩余工作路线图

> 更新：G0/G1 已验收归档（G1 基线 CI 775/0/0/5）；G2 Q16 已验收归档，提交 `b2f5436` 的 GitHub CI 双闸门各 **837/0/0/5**、四个业务 IT **40/61/36/63** 全 `PASSED`。`ACTIVE_WORK.md` 当前 **IDLE**；原课程全局替换连带 Input 声明改名会被 `PATH-004` 明确拒绝，混合事务与组合改名均须另立单。
> 读者：项目负责人和后续执行 Agent
> 定位：本文件的阶段 1–7 是 **G0：现有链路资格收口**内部的 Q 系列执行顺序。产品方向、阶段进入条件与阶段完成门见 [`README.md`](README.md) 和 [`../design/README.md`](../design/README.md)；两者不替代本文件的执行顺序。

## 先说结论

项目主干已经具备九子模块 Maven 工程（父工程 + 九子模块）、主要生产实现和可重复的默认离线构建。剩余工作是把当前实现补成能够被直接证明、长期维护和安全发布的工程。

不要一次把全部任务交给一个 Agent。每个阶段都应独立实现、测试、复核和提交。

本文件只负责长期顺序。当前真正允许执行的唯一小任务见 [`ACTIVE_WORK.md`](ACTIVE_WORK.md)；项目负责人如何派活和验收见 [`../PROJECT_OWNER_GUIDE.md`](../PROJECT_OWNER_GUIDE.md)。

版本快照规则：由项目负责人决定何时提交或推送。G0 实现快照已提交为 `24eec6d`；G1 的 Q9/Q10 实现快照已提交为 `92c505c`（消息为 `pause`）。无阻断或待裁决方向时，聊天中只请求确认继续，详细证据留在工作单和资格文档。

## G1 增量序列（G0 之后，不属于上面的阶段 1–7）

G1 的完成门是「BIZ-01..06 的基础业务与反例 + GEN-01/02，并在真实 MySQL 中验证 HTTP 和数据结果」。该阶段按增量拆成多张 Q 工作单；每张都必须端到端，不做横向铺语言。

| 工作单 | 范围 | 对应完成门 | 状态 |
|---|---|---|---|
| Q9 | Course 单实体 + 分页/投影/排序/字面量过滤查询端到端 | BIZ-05（查询与分页部分）、GEN-01/02 | **已完成并归档**：全量 554 → 620（差量 66），真实 MySQL + HTTP 场景 40 项断言 0 失败；见 [`completed/Q9-query-slice-course-pagination.md`](completed/Q9-query-slice-course-pagination.md) 与资格报告 1.9 |
| Q10 | Course 写侧：Create、Get、PATCH 三态、version 冲突、结构化字段错误 | BIZ-01、BIZ-02、BIZ-03、BIZ-04 | **已完成、验收并归档**（2026-09-23）：全量 620 → **702/0/0/5**，真实 MySQL + HTTP 场景 61 项断言 0 失败（含并发竞态），Q9 场景同树回归 40 项 0 失败；见 [`completed/Q10-course-write-slice.md`](completed/Q10-course-write-slice.md) 与资格报告 1.10 |
| Q11 | 关联过滤（存在性语义）、根分页不重复、关联批量读取与 SQL 次数预算 | BIZ-06 | **已完成、验收并归档**（2026-09-23）：全量 702 → **749/0/0/5**，真实 MySQL + HTTP 关联场景 **36 项断言 0 失败**（`total` 按根算、根不重复、ENG101 反例、根过滤≠投影过滤、每关联一条批量读取、语句数 4/4/2/0、一次请求一个只读事务），Q9/Q10 同树回归 40/40 与 61/61；见 [`completed/Q11-relation-filter-and-batch-reads.md`](completed/Q11-relation-filter-and-batch-reads.md) 与资格报告 1.11 |
| Q15 | 实体级片段与能力集合解耦（`MapperRenderer` 的乐观锁辅助方法改为按实体声明发射） | G1 行"继续生成 SIR/修改 SIR"、Q13 的 BLOCK-2 解除 | **`DONE`**（2026-09-23 验收归档，见 [`completed/Q15-entity-artifact-decoupling.md`](completed/Q15-entity-artifact-decoupling.md)；3 项不变性探针 + Q13 删除轮与"增量 == 从零"实测通过）：原方案与 D0–D3 见 [`ACTIVE_WORK.md`](ACTIVE_WORK.md) |
| Q14 | 变更层语义投影与 Q9–Q11 语言面对齐（`SemanticProjection` 补 `.present`/`any(...)`/`Page<...>` 与五处盲区） | G1 行"继续生成 SIR/修改 SIR"、Q13 的 BLOCK-1 解除 | **`DONE`**（2026-09-23 验收归档，见 [`completed/Q14-change-layer-projection-parity.md`](completed/Q14-change-layer-projection-parity.md)）；原方案与 D0–D4 见 [`ACTIVE_WORK.md`](ACTIVE_WORK.md) |
| Q13 | G1 变更闭环：在课程切片上做 SIR 变更（加能力 / 改约束 / 删能力）并验证行为变化与"增量结果 == 从零生成结果" | BIZ-01..06 的变更后回归、GEN-01/02、G1 行"继续生成 SIR/修改 SIR" | **`DONE`**（2026-09-23 验收归档（CI run `35854153833`：两条闸门 BUILD SUCCESS、合计 775 / 0 / 0 / 5、四个业务场景 IT 全 `PASSED`）；两次阻断均已解除：BLOCK-1 由 Q14、**BLOCK-2 由 Q15**——删除版本化写能力会改动幸存文件 `CourseMapper.java` 而被 `SIR-CHANGE-IMPACT-202` 拒绝，已由 **Q15** 按 R1 解除；本单自身的 C5 已修正；变更闭环 IT 63 项检查 0 失败、`PASSED`；门 9/10 由 GitHub CI 跑完并通过）；P1 结论、实施期订正 C1–C6、BLOCK-1/2 全文见 [`Q13-g1-change-loop.md`](Q13-g1-change-loop.md) |
| Q12 | 路由模板（`@PathVariable` 绑定）与资源式路由命名 | 无（设计 §10 未冻结路由形态） | 未立项（Q10 的 D2/D16 登记） |

执行授权仍只来自 `ACTIVE_WORK.md`；上表只是顺序说明，不构成开工授权。

## G2 增量序列（G1 完成后）

| 工作单 | 范围 | 状态 |
| --- | --- | --- |
| Q16 | 单文件持久身份、旧身份不明时拒绝改名、独立只读**单声明**改名计划；全工程受管文件差异未覆盖时拒绝；不执行文件应用或数据库迁移 | **`DONE`**（2026-09-26 验收归档；CI [run `36234304027`](https://github.com/HoloNova/Software-IR/actions/runs/36234304027) 双闸门各 837/0/0/5，四个 IT 全 `PASSED`），见 [`completed/Q16-persistent-identity-and-read-only-rename-plan.md`](completed/Q16-persistent-identity-and-read-only-rename-plan.md) |
| Q17 候选（无开工授权） | **单声明**混合路径变更的一次原子事务、CURRENT/Journal 恢复、旧受管文件清理、应用盘面与候选从零生成逐字节一致；日志 V4 只是待核方案 | 待单独工作单审定后立项；Q16 的只读计划不抵扣本门 |
| 后续独立项（未立项） | 能力与关联 Input 声明**一同改名**：需冻结 Input 身份与多 subject 计划；原课程全局替换当前只会被 `PATH-004` 拒绝，不由 Q17 的文件事务自动解决 | 尚未授权；不能把 Q16 的单声明计划当作三文件改名可应用 |
| 后续单 | 多文件源清单、import/binding、模块实例、nodeKey 与剩余 LANG 门禁 | 尚未立项；Q16/Q17 不抵扣 G2 整阶段完成门 |

## 已登记的独立项（不属于任何工作单的完成门）

- 错误信封码名与设计 02（`REQUEST_INVALID`/`UNKNOWN_FIELD`）的逐字对齐——Q10 验收裁决（2026-09-23）为保留现状（信封级 `INVALID_REQUEST` + Bean Validation 字段码），后续独立处理。
- `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/SpringBootModelLowerer.java` 仍是 CFR 反编译文本，是否重写为手写源码（Q10 的 C8）。重写会把审阅边界扩大到整文件，需另立工作单。
- 非分页 find 的关联读取（Q11 的 C5：spring-boot 目标当前要求带关联投影的 find 必须分页）；关联投影深度 3 层及以上；预算“按投影使用点计数”是否改为“按关系计划去重”的替代口径（Q11 的 C4）。
- `in`/`isNull` 等过滤算子、when-present 可选过滤、按关联字段排序、关联集合自身分页（Q11 禁止范围，需真实需求）。

## 已完成的治理工作

- 建立 `AGENTS.md`、Accepted ADR、当前状态、资格报告和 `.memory` 的明确权威分工。
- 冻结 `CURRENT=B0` 向后补偿、`CURRENT=B1` 向前验证/清理的唯一方向。
- 完成当前状态、资格、覆盖清单和路线图的单一入口收敛。
- 当前 Git `main` 已连接远端并拥有可回退基线。

## 阶段 1：Generator 系统测试

阶段归属：G0（现有链路资格收口）的第 1 项。G0 还包含阶段 2–6（PSG、Change fixtures、conformance、事务故障矩阵、CLI 产品边界），全部完成后 G0 才具备关闭条件。

### 当前拆分与进度

- [x] Q1A：完整、有序输出文件契约与 ownership metadata（见 [`completed/Q1A-generator-output-contract.md`](completed/Q1A-generator-output-contract.md)）。
- [x] Q1B1：POM、Application、Enum、Mapper、Exception 内容契约（见 [`completed/Q1B1-generator-simple-artifact-contract.md`](completed/Q1B1-generator-simple-artifact-contract.md)）。
- [x] Q1B2：Entity 与 DTO 类型/验证约束契约（见 [`completed/Q1B2-generator-entity-dto-contract.md`](completed/Q1B2-generator-entity-dto-contract.md)）。
- [x] Q1B3A：Service 与 Workflow 行为契约（见 [`completed/Q1B3A-generator-service-workflow-contract.md`](completed/Q1B3A-generator-service-workflow-contract.md)）。
- [x] Q1B3B：Controller、actor identity transport 与 response 行为契约（见 [`completed/Q1B3B-generator-controller-transport-contract.md`](completed/Q1B3B-generator-controller-transport-contract.md)）。
- [x] Q1C：Locale、工作目录、换行、转义和 UTF-8 字节确定性矩阵（见 [`completed/Q1C-generator-determinism-matrix.md`](completed/Q1C-generator-determinism-matrix.md)）。
- [x] Q1D：完整生成工程冻结依赖下的真实离线编译（见 [`completed/Q1D-generated-project-offline-compilation.md`](completed/Q1D-generated-project-offline-compilation.md)）。
- [x] Q1：生产字节码边界闸门与 Q1 汇总验收（已验收归档，见 [`completed/Q1-generator-production-boundary-and-acceptance.md`](completed/Q1-generator-production-boundary-and-acceptance.md)）：定向 6 run / 0 fail；全量完成形式 379 run / 5 fail（全部为已登记的 `sir-toolchain-application` 软链接断言）/ 0 error / 11 skip；生产 class 禁止引用 0 违规，未修改生产代码。证据见资格文档 1.3。项目负责人 2026-09-18 确认通过，但决定**暂不提交 Git**（初定 G1 完成后统一提交）。

### 目标

证明 Generator 能稳定、完整地把 `SpringBootLoweredModel` 渲染成预期工程，而不是只证明几个辅助类能工作。

### 任务

1. 为 POM、Application、Enum、Entity、Mapper、DTO、Exception、Service、Controller 建立直接测试。
2. 覆盖 Unit response、actor identity、复合 Find、Create/Update/Persist。
3. 验证相同输入重复运行、不同 Locale 和不同工作目录下路径、顺序和 UTF-8 字节一致。
4. 验证 Generator 不读取 AST、SIR、SymbolTable 或磁盘（Q1 已建立静态字节码边界证据：生产 census 37 个 class / 0 违规 / 公开入口反射，见资格文档 1.3）。
5. 建立一个完整 campus-market canonical fixture，并验证生成工程在冻结依赖下离线编译。

### 完成门

- 所有主要 renderer 都有行为证据。
- 完整文件集合和字节确定性通过。
- 至少一个生成工程真实离线编译。

## 阶段 2：Project Graph 直接测试

阶段归属：G0（现有链路资格收口）内部的阶段 2。

### 当前拆分与进度

- [x] Q2：`sir-project-graph` 直接模块契约测试（已验收归档，见 [`completed/Q2-project-graph-direct-module-contracts.md`](completed/Q2-project-graph-direct-module-contracts.md)）：模块由 0 → 72 项直接测试全绿（契约 9、规则矩阵 31、canonicalization 6、serialization 20、只读边界 6）；全量完成形式 451/5/0/11，除已登记的 5 项外无新增 fail/error/skip；未修改生产代码、未新增依赖。项目负责人 2026-09-18 确认通过，暂不提交 Git。

现有间接证据（不重复实现，仍归 `sir-toolchain-application`）：`ToolchainProjectGraphIntegrationTest` 已用真实链路覆盖四类边与摘要稳定性，`ToolchainGraphStageFailureTest` 已覆盖 Graph 失败在写盘前终止；Q2 补齐的是模块内直接契约。

### 目标

让 Project Graph 从“Application 会间接经过”变成拥有独立、可维护的契约测试。

### 任务

1. 直接测试 DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE。
2. 覆盖稳定 ID、顺序、重复、悬空边、不可变集合和确定性。
3. 覆盖 canonical encode/decode/load/digest。
4. 验证 Graph 不访问文件系统、不重新按名称解析。
5. 增加 Parser → Semantic → Lowering → Generator → Graph 集成链。

### 完成门

- Graph 模块不再是 0 项直接测试。
- canonical document 与 digest 能跨重复运行稳定。
- Graph failure 在 Application 写盘前终止。

## 阶段 3：Change fixtures 与操作族测试

### 当前拆分与进度

- [x] Q3：Change fixtures 与操作族测试 + 软链接失败收口（已验收归档，见 [`completed/Q3-change-fixtures-and-symlink-closure.md`](completed/Q3-change-fixtures-and-symlink-closure.md)）：新增 13 个 fixture；`ChangePlanningApplicationTest` 9/0/0/0、`KcgCliWorkflowTest` 13/0/0/0；5 项软链接失败修复（2 处生产诊断改动）；全量冻结与完成两种形式均 BUILD SUCCESS，合计 **465 run / 0 fail / 0 error / 5 skip**。仍未做：操作族跨版本组合矩阵与完整 `SIR-CHANGE-*` 失败矩阵。

### 目标

补齐当前 Change planner、Application 和 CLI 共同依赖的 base/candidate SIR 集合，消除 assumption skip。

### 任务

1. ~~按 Change IR v0.1-v0.6 为每个操作建立最小 base/candidate 对。~~ 已完成。
2. ~~覆盖 workflow 修改、添加/删除 capability、字段约束、未引用字段类型和 actorless readonly exposure。~~ 已完成。
3. 覆盖版本、target、scope、impact、closure、冲突和 canonical 表达——**部分**：现有测试覆盖版本/操作不兼容、stale、未知 target key、错误 outputRoot、活动 journal 与 `NO_CHANGES`；完整跨版本组合矩阵与 `SIR-CHANGE-*` 诊断矩阵仍未建立。
4. ~~恢复 `ChangePlanningApplicationTest` 当前跳过的 6 项。~~ 已完成（9/0/0/0）。
5. ~~让 `KcgCliWorkflowTest` 不再整类跳过。~~ 已完成（13/0/0/0，并改为逐测试 assumption）。

### 完成门

- 每个 fixture 的精确差异可审查。
- Application 和 CLI 不再因资源缺失跳过。
- Change v0.1-v0.6 都有成功与失败路径。

## 阶段 4：Application conformance harness（已完成并归档）

### 当前拆分与进度

- [x] **Q4+Q5（合并，已验收归档）**：conformance 包恢复编译与运行，并在真实 MySQL 8.4.11 上产出 **QUALIFIED**。
  **2026-09-18 实证修正（重要）**：早期两次估计都不准——“缺口只有 4 处”来自**语法错误抑制符号错误**；“100 个错误”来自 **javac 默认 `-Xmaxerrs 100` 的截断**。在提高上限并修掉三个根因（`OwnedRunDirectory` 缺 package/import、`StrongFileIdentity` 用错 JNA 类、IT 缺 9 个 import）后，**实测基线为 56 个不同错误 / 13 个文件**，全部是真正缺失的类型与方法（精确符号→文件对照表见 `docs/roadmap/completed/Q45-conformance-harness-and-real-mysql-matrix.md`）。
  另发现：包内至少有 3 个可运行单元测试类（`ConformanceFixtureSqlTest`、`MysqlSqlConstructionTest`、`StrongFileIdentityTest`）当时被 surefire 包级排除隐藏；`ConformanceFixtureSqlTest` 依赖的 `/conformance/mysql/campus-market-ddl.sql` 当时尚不存在，均已在 Q4+Q5 中处理。
  因负责人 2026-09-18 裁定 **Q4 与 Q5 合并为一张工作单**（“Q4+Q5 Conformance harness 恢复与真实 MySQL 验证”），Q4 单独立项已取消。
- [x] Q5 的参考环境与矩阵：**已执行**（连续两次 QUALIFIED，五场景全通过；运行后 workParent 为空、schema 不存在）。参考环境**已就绪**：`mysql:8.4` 容器（版本 8.4.11、`@@server_uuid` 稳定）+ 控制凭据（已验证 advisory lock）+ 无 CREATE/DROP 的运行凭据（已验证 CREATE 被拒）；提供脚本 `/root/kcg-conformance/provision-mysql.sh` 与 `env.sh`（均在仓库外，凭据不入库）。上述 12 个类型与 14 个方法、两处被截断的编排、`EvidenceWriter` 的写入面、以及 P0-C2（运行库 URL 只由已验证 SchemaName + 控制端点渲染）均已在 Q4+Q5 中补齐并测试。
   已裁定（负责人 2026-09-18）：容器化 MySQL 可作为资格记录的参考环境元组；结论只对该元组成立。上述缺失类型、编排、写入面和 P0-C2 均已在 Q4+Q5 中补齐并由真实矩阵验证。

### 目标（已达成）

conformance 包重新参加 testCompile 与运行（编译错误 56 → 0，两个 POM 排除已删除），并在真实 MySQL 上重新建立安全、显式 opt-in 的外部资格入口：**该阶段历史计数为 478 run / 0 fail / 0 error / 5 skip**，当前总计为 554/0/0/5，外部矩阵 QUALIFIED。

残余缺口：包内并非每个辅助类都有独立单元测试（见 `TEST_COVERAGE_INVENTORY.md`）；结论只在单一参考环境元组上验证过。

### 任务

1. 根据当前契约重新完成 suite、scenario、fixture 和 evidence 编排。
2. 先让全部 46 个文件通过 testCompile。
3. 删除 POM 对整个 conformance 包的 compiler/Surefire 排除。
4. 默认 `clean verify` 不启动外部环境；外部运行必须显式 opt-in。
5. 保留凭据脱敏、证据目录 ownership、schema 清理、双 JDBC advisory lock、进程退出和 fail-closed。
6. 为 harness 自身的失败路径建立直接测试。

### 完成门

- 默认构建不再通过硬排除制造绿色。
- 无外部环境时为 `NOT_RUN`，不是 `QUALIFIED`。
- 显式外部运行的每个副作用都拥有 cleanup 和 sealed evidence。

## 阶段 5：事务故障矩阵（已完成并归档）

### 目标

为 UPDATE、CREATE、DELETE 建立完整、跨 CURRENT 线性化点的故障注入证据。

### 任务

1. 覆盖 prepare、backup/staging、文件提交、Bundle 发布、CURRENT 发布、Journal close 和 cleanup。
2. 每个中断点明确结果：向后补偿、向前完成或 `RECOVERY_REQUIRED`。
3. 证明 B0 永不向前猜测，B1 永不恢复旧字节。
4. 验证 hard link、NOFOLLOW_LINKS、junction/reparse point、same-volume 和物理身份。
5. 补第二卷/挂载点环境测试或明确记录 `NOT_RUN`。

### 完成门

- 三类事务都拥有相同方向语义和端到端故障矩阵。
- 模糊状态全部 fail closed。
- 无 copy/move fallback 绕过 DELETE hard-link 约束。

## 阶段 6：CLI 产品边界（已完成并归档；形态 A）

### 推荐选择

阶段 3 至 5 已完成；Q7 已选择形态 A，冻结并证明当前只读 `context` / `plan` CLI。ADR-019 的完整本地生命周期仍是 G1+ 之后的独立决策和工作单。

### 如果发布完整生命周期

- 六个命令拥有严格参数、help、退出码和 canonical JSON 测试。
- CLI 只调用 typed Application API。
- Apply 重新读取并绑定 fresh candidate/context/output evidence。
- Maven exec 可运行完整 generate → register → context/plan → apply → recover。
- thin JAR/发行包作为独立发布任务处理，不混入业务资格。

## 阶段 7：最终资格（Q8 已完成并归档；G0 关闭动作）

Q8 已按两条离线 Reactor 闸门、外部 MySQL 矩阵、平台条件、CLI 边界、skip/exclude/NOT_RUN 登记和残余缺口清单完成 G0 关闭。当前唯一资格结论见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节。

G0 关闭结果：默认构建 554/0/0/5、外部 MySQL 8.4.11 conformance `QUALIFIED`；Windows 侧、thin JAR/发行包、完整 CLI 写生命周期和第二卷/挂载点仍按资格报告登记为未运行或未覆盖。下一阶段是 G1，必须另立 Q 工作单。

## 每个 Agent 的证据记录格式

每个阶段只交付一个有边界的任务，并在工作单/资格文档中记录：

1. 修改文件和生产行为变化。
2. 新增或变更的契约。
3. 实际执行命令。
4. run/fail/error/skip/exclude。
5. 未完成和未运行内容。
6. 是否触及 Bundle、CURRENT、Journal、路径或外部凭据边界。

上述内容默认不在聊天里重复。没有失败、阻断或需要项目负责人裁决的方向时，只发简短的完成与确认请求。

## 暂不并行展开

- Redis Extension
- 第二 Target
- Constraint VM
- PSG `REFERENCES` 扩展
- Snapshot V2
- Change IR v0.7
- GUI、daemon、多用户
- Java 反向解析
- 新的增量编译系统

这些方向可以进入未来路线图，但不能与当前资格基础同时展开。
