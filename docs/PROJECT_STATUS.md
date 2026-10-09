# KCG-Code 当前项目状态

> 更新：2026-10-08。**G0/G1完成，G2的Q16–Q20已验收归档，G2未关闭。** [Q20](roadmap/completed/Q20-multi-source-workflow-read-only-plan.md)受测e404208，同SHA [CI run37764055905](https://github.com/HoloNova/Software-IR/actions/runs/37764055905)双门各995/0/0/5、四IT40/61/36/63通过，两类artifact已核对；XML999含四IT，Q19 969→995增26。本机186/新26；Accepted ADR-023，已交付多源context/只读单工作流UPDATE计划，不含实际应用。[当前活动单](roadmap/ACTIVE_WORK.md)为 **Q21 / AWAITING_CI**：D0–D8实现/本机定向完成，去重162项/新54项通过；真实根/片段连续更新、150应用与85恢复中断点通过，Proposed ADR-024。双门/四旧IT/新增多源IT均NOT_RUN。历史G1=775/Q16=837/Q17=893/Q18=936/Q19=969见资格§1.13–1.18；旧输出/六V1图golden保持。多源实际UPDATE已实现待CI/验收；其他操作/改名、nodeKey/模块实例、完整身份兼容与G3未交付；连带Input改名仍PATH-004。本轮按Q21范围实施，不提交/推送，不跑本机全量门/业务IT。
> 状态：正常开发中的 v0.1 工程；G0 资格已完成，完整产品资格尚未完成

## 1. 项目定位

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。Agent 或 SLM 负责提出可检查的 SIR；确定性程序负责解析、绑定、类型、约束、Lowering、生成、Project Graph、文件事务和变更计划。

它不是普通 CRUD DSL，也不允许模型绕过 IR 直接决定最终 Java 工程结构。

## 2. 当前实现

父 Maven Reactor 包含九个业务子模块（父工程与九个子模块共十个 Reactor 模块）：

| 模块 | 当前职责 |
|---|---|
| `sir-parser` | ANTLR4 Grammar、不可变 AST、SourceSpan、稳定 AstNodeId、解析诊断 |
| `sir-semantic` | Resolve/Type/Validate/Normalize、typed reference-site binding、Normalized model |
| `sir-lowering-api` | Target 无关 Lowering 契约、来源、版本、诊断和 Validator |
| `sir-lowering-spring-boot` | Java 21/Spring Boot/MyBatis-Plus/MySQL/Maven/REST Lowered IR |
| `sir-generator-spring-boot` | 只消费 Lowered IR 的确定性内存文件生成 |
| `sir-project-graph` | Project Graph 构建、校验、canonical serialization/load |
| `sir-change` | Change IR v0.1-v0.6 typed planning、scope、impact 和 closure |
| `sir-toolchain-application` | 编译/生成/Graph、Bundle、CURRENT、文件事务、Apply 和显式恢复 |
| `kcg-cli` | 当前公开只读 `context` / `plan` 命令和 canonical JSON |

## 3. 当前公开能力边界

### 已实现

- SIR v0.1 Parser、Semantic、Spring Boot Lowering 与确定性 Generator。
- 单文件查询切片（G1 首切片，2026-09-18）：`view` 响应投影、`Page<T>` 分页、显式 `order by`、字面匹配 `containsLiteral` 已贯穿 Parser → Semantic → Lowering → Generator，并在真实 MySQL 8.4.11 + HTTP 上端到端验证（分页/排序/投影/字面 `%` 与 `_` 对照/越界页/非法分页 400，共 40 项断言）。
- 单文件写侧切片（G1 第二切片，2026-09-21）：`versioned` 并发字段、声明式错误状态码、`persist … else` 条件持久化、`input … patch of` 局部更新载荷、`.present` 存在性表达式与统一结构化错误信封已贯穿全链，并在真实 MySQL 8.4.11 + HTTP 上验证创建/读取/局部更新/显式清空/陈旧版本 409/空变更集 400/未知字段 400/并发竞态（恰好一成功一 409、版本只 +1），共 61 项断言；产品侧 DELETE、路由模板、关联读取与 `EXISTS` 过滤仍未实现。
- 显式输出根上的路径/冲突预检、Graph 构建、同卷 staging、原子发布和结构化失败。
- Project Graph 的只读模型、canonical encoder/decoder 和 validator。
- Change IR v0.1-v0.6 planner。
- Application-owned Bundle、CURRENT、UPDATE/CREATE/DELETE transaction 和显式恢复 API。
- Q16 能力/实体字段 `@id` 与独立单声明只读改名计划；Q17 唯一 `applyRename(RenameApplyRequest)` 入口、V4 混合文件事务、CURRENT=B0/B1 定向恢复与受管文件保护。真实单能力改名已验证，组合改名、多源增量变更/跨文件移动应用、字段数据库迁移和 CLI 接线未完成。
- CLI 的只读 context inspection 与 plan。
- **Q18 新增，2026-10-05 已验收归档**：0.2 显式源清单/导入、逐文件解析与一次 Resolve、完整 SourceSnapshot、V0_2/V2 Graph，以及 `executeProject` 到新输出根的首次生成。仅同一 software 的分片；最多 128 文件、1 MiB/文件、8 MiB 合计。旧 0.1/V1 与 Change/Rename 单源边界保留；Q18本单不交付多源基线/apply、nodeKey/模块实例。实测与限额合同见资格报告 §1.16 和 ADR-021。
- **Q19新增，2026-10-05已验收归档**：独立`ProjectBaselineApplication.register/inspect`保存完整源原字节、V2图及受管清单；原sourceRoot不可用时可独立重开核验，无单源revision或多源Change/Rename/apply。四成员Bundle/指针身份及15点发布矩阵合同见资格报告§1.17、Accepted ADR-022。

- **Q20已验收归档（2026-10-08）**：独立`ProjectChangePlanningApplication.context/plan/verify`读回Q19保存字节与候选快照，完整重编译后返回单能力工作流只读UPDATE计划；独立revision/目标目录/摘要域，共享旧工作流决策，成功/拒绝/NoChanges均零写盘。定向186、新26、同SHA双门995/四IT通过；不交付实际应用/其他操作/改名，见资格§1.18、Accepted ADR-023。

### 已实现、尚待同SHA CI与验收

- **Q21 / AWAITING_CI**：`ProjectChangeExecutionApplication.apply/recover`同锁重编译/完整重新规划，UPDATE后保存全部候选源再发布CURRENT；受限发布关系/终态receipt可重开与连续更新。回返原内容可复用基线ID，不建第二head；硬链接pins拒绝同字节外部替换，终态恢复证明保留并计预算。定向162/新54，150应用点（64缺证据保留拒绝、56回滚、30提交清理）与85恢复点通过。新多源业务IT已编译、本机未运行；双门/五IT均NOT_RUN，见资格§1.19、Proposed ADR-024。旧族和CLI不扩展，其他操作/源移动/改名仍不支持。

### 尚未作为当前产品能力发布

- CLI `generate`、`register`、`apply`、`recover` 完整本地生命周期。
- Docker / 迁移 / Web / 完整交付等 G1+ 能力尚未作为当前产品能力发布；外部 MySQL conformance 的资格结果见第 4 节。
- fat JAR 或独立 CLI 发行包；当前验证入口是 Maven exec。
- Redis Extension、第二 Target、Constraint VM、Java 反向解析、GUI/daemon、多用户和新的增量编译系统。

## 4. 当前资格状态

最近一次实际执行的离线 Reactor 记录（GitHub CI run `37764055905`，受测 SHA `e404208`，完成形式；Q20已验收；Q21 AWAITING_CI）：

```bash
mvn -B -o clean verify -Dmaven.test.failure.ignore=true -Dmaven.repo.local="$MAVEN_REPO"
```

十个 Reactor 模块全部完成，**冻结形式与完成形式均 BUILD SUCCESS，各 995 run / 0 fail / 0 error / 5 skip**。Q19 969 → Q20 995（+26，全部Application），5 skip仅既有Windows junction。上传XML999含另跑四IT，不当作闸门计数；四IT40/61/36/63及两类artifact已核对。数字/边界见`docs/qualification/CURRENT_QUALIFICATION.md` §1.18、§2；不证明待审Q21。

**Q19本机定向结果**：Application27类去重199/0/0/0（含新增33及15点故障矩阵；非全量Reactor）。Q18历史指定类346/0/0/0仍独立记录。重活仅CI执行，四业务报告40/61/36/63全PASSED、生成工程构建exit 0；历史数字不混为本单新增，Q20本机定向186/0/0/0（新26），同SHA CI双门995/0/0/5、四IT通过；Q21仅设计、所有运行门NOT_RUN。

当前结论：

- 默认离线 Reactor：Linux CI run 37764055905 双门均 BUILD SUCCESS，十模块完成、各 **995/0/0/5**（Q20后；Q21 AWAITING_CI；5 skip仅Windows junction）。Windows 2026-08-11 那次 `0 failed` 不能证明软链接拒绝路径可用（当次用例未真实执行），该证据现由 Linux 提供。
- G1 首切片（Q9，2026-09-18）：单文件 `Course` 分页查询在真实 MySQL 8.4.11 + HTTP 上 40 项业务断言全通过（含字面 `%`/`_` 的灵敏度对照、越界页、4 种非法分页 400、行指纹不变）；生成工程离线编译通过。schema 为测试 fixture（`schemaSource=TEST_FIXTURE_DDL`），**产品 INITIALIZE/UPDATE 仍未实现**；该 IT 为 opt-in，默认构建不跑。
- G1 第二切片（Q10，2026-09-21 验证 / 2026-09-23 验收并归档）：Course 写侧在真实 MySQL 8.4.11 + HTTP 上 61 项业务断言全通过（创建 201 与声明投影、PATCH 三态、陈旧版本 409、并发同版本恰好一成功一 409 且版本只 +1、结构化字段错误 400 且不写入、每次失败后整行指纹比对）；schema 仍为测试 fixture（`schemaSource=TEST_FIXTURE_DDL`），该 IT 为 opt-in，默认构建不跑。
- G1 第三切片（Q11，2026-09-23 验证并验收归档，见资格报告 1.11）：关联过滤（存在性语义）与关联读取（批量、预算）在真实 MySQL 8.4.11 + HTTP 上 36 项业务断言全通过——`total` 按根实体算且每个根只出现一次、只被两条不同报名分别满足条件的课程不入选、根过滤不等于投影过滤、每个关联一条批量读取（三十条关联行语句数不变）、一次请求恰为一个只读事务、越界页 2 条读取、非法页 400 且 0 读取；语句计数取自 `performance_schema` 摘要增量、原始 SQL 取自 general log（均经 control 账号）。schema 仍为测试 fixture，该 IT 为 opt-in，默认构建不跑；Q9/Q10 场景在同树回归（40/40、61/61）。
- Generator 生产边界闸门（Q1，2026-09-18）：生产 census 37 个 class 禁止引用 0 违规、公开入口只为 `generate(SpringBootLoweredModel)`；未修改生产代码。
- 软链接拒绝路径（Q3，2026-09-18）：5 项失败已定性并修复——4 项为诊断消息拼写（`link in raw chain` → `symlink or reparse point in raw chain`），1 项为 `PathGuard` 叶子链诊断遮蔽（现只检查严格祖先链，叶子符号链接恢复报 `CONFLICT-001/002`）。新增 FAIL_IF_EXISTS 侧守卫测试，保证“不再检查叶子”不被错实现为“删掉叶子拒绝”。
- Change fixtures（Q3，2026-09-18）：新增 13 个 fixture，`ChangePlanningApplicationTest` 6 项与 `KcgCliWorkflowTest` 13 项从 skip 变为真实执行并通过。
- Project Graph 直接模块测试：已从 0 项补齐到 72 项全绿（Q2，2026-09-18，已验收归档）：四类边、规则矩阵、canonical 往返、只读边界闸门与不可信字节守卫；Q2 阶段全量完成形式为 451/5/0/11，后续 G0 收口为 554/0/0/5；最新Q19全量见本节开头。
- 外部 MySQL conformance：**QUALIFIED**（MySQL 8.4.11 参考环境，五场景全通过、两次可复现；G0 完成门见 `CURRENT_QUALIFICATION.md` 第 0 节）。
- 完整本地 MVP：`NOT_RUN`。
- 生产、安全认证、性能、HA 和全平台资格：未声明。

精确模块数字与阻断原因见 `docs/qualification/CURRENT_QUALIFICATION.md`。

### 证据冲突（已于 2026-09-18 G0 关闭动作中裁决）

`docs/design/implementation-baseline.md`（2026-09-08，源码基点 `5bba6ea`）记录标准命令 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify` 失败于 `sir-parser:testCompile`（报告找不到 `io.kcg.sir.api`、`io.kcg.sir.ast` 等包，后续模块未验证）；而本文件与 `CURRENT_QUALIFICATION.md`（2026-08-11）记录同一命令为 `BUILD SUCCESS`。两者使用同一命令与同一源码基点、不同日期和不同环境。在该冲突被一次当次 `clean verify` 复跑裁决之前：

**裁决**：以 2026-09-18 Linux 全新仓库的复跑为准——标准命令在冻结与完成两种形式下均 BUILD SUCCESS，未复现 `sir-parser:testCompile` 失败。`implementation-baseline.md` 保留原始快照并加入指向资格文档的裁决补记；两侧互相指向，不再存在竞争的当前结论。

## 5. 已冻结的高风险契约

- 名称只在 Resolve 阶段解析一次。
- Lowered IR 拥有生成决策权，Generator 只做纯渲染。
- Application 是 Bundle、CURRENT、LOCK、Journal 和文件系统写入的唯一权威。
- `CURRENT=B0` 只允许向后补偿；`CURRENT=B1` 只允许向前验证和清理。
- 方向、路径、物理身份或补偿证据不确定时 fail closed。
- DELETE backup 只使用同卷 hard link，不增加 copy/move/replace fallback。
- context/plan 只读，不构成 Apply 或写盘授权。

事务方向的正式决策见 `docs/architecture/ADR-020-current-baseline-transaction-direction.md`。

## 6. 当前优先工作

当前 G 阶段是 **G2：持久身份与受控模块**（阶段定义见 `docs/roadmap/README.md`）；G2 尚未通过完成门。**G0 完成门已闭合**（见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节），Q1–Q8 已完成并归档；**G1 的课程业务切片均已完成、验收并归档**——Q9（Course 单实体分页查询端到端：全量 554 → 620，真实 MySQL/HTTP 场景 40 项断言 0 失败，2026-09-21 验收）、Q10（Course 写侧：Create + Get + PATCH 三态 + version 冲突 + 结构化字段错误：全量 620 → 702/0/0/5，写侧场景 61 项断言 0 失败，2026-09-23 验收）与 Q11（关联过滤与关联读取：全量 702 → **749/0/0/5**，关联场景 36 项断言 0 失败，Q9/Q10 同树回归 40/40 与 61/61，2026-09-23 验收）。`docs/roadmap/ACTIVE_WORK.md` **当时是 Q13（G1 变更闭环）的工作单**，状态 **`DONE`**（2026-09-23 验收归档；CI run `35854153833`：两条全量闸门 BUILD SUCCESS、合计 775 / 0 / 0 / 5、四个业务场景 IT 全 `PASSED`）——Q13 已归档，当前工作单见 [`roadmap/ACTIVE_WORK.md`](roadmap/ACTIVE_WORK.md)（当前Q21 SPEC_REVIEW，多源文件应用/发布/恢复推荐待确认；Q20已归档，同SHA双门995/0/0/5、四IT通过，本机186/新26；Q19已归档：本机199及f3d9ef7同SHA双门969/0/0/5、四IT通过，不抵扣Q20验证）。它曾被两个缺陷阻断、又分别由两张独立小单解除：**Q14** 补齐变更层语义投影（`.present`/`any(...)`/`Page<...>` + 五处盲区 + 语言面闸门）、**Q15** 让实体级片段（`CourseMapper` 的乐观锁辅助方法）只依赖实体声明。现在 Q13 的链 R1→R2→R3a→R3b→R4 全部 apply 成功、"增量应用结果 == 从零生成结果"逐字节成立；真实环境变更闭环 IT 也通过（63 项检查 0 失败）：同一份数据下放宽过滤后 ART101 出现（total 3→4）、收紧后的名字长度在 HTTP 上 400 且点名 `name`、删掉两个写能力后写路由 404 而非法分页仍得 400 `InvalidPage`、新增能力的新路由与旧路由同时可用。**Q14（变更层语义投影对齐）已完成、验收并归档**（2026-09-23 实施完成；全量闸门按批量口径留到提交前一次运行）：Q13 的开工实测发现 `sir-change` 的 `SemanticProjection` 不处理 Q10 的 `.present`、Q11 的 `any(...)` 与 Q9 的 `Page<...>`，含这些语法的源无法做变更计划（抛 `IllegalStateException`），另有五处静默盲区，因此 **Q13（G1 变更闭环）暂停**（当时状态 `BLOCKED`，现已归档于 [`roadmap/completed/Q13-g1-change-loop.md`](roadmap/completed/Q13-g1-change-loop.md)），先补这张对齐单，完成后回到 Q13 继续。Q14 已完成并验收：`SemanticProjection` 补上 `.present`/`any(...)`/`Page<...>` 与五处盲区，11 项投影测试（含语言面闸门与灵敏度探针）与 6 项行为测试全绿；Q13 的 R1/R2 两轮随之可以计划并 apply，但**删除版本化写能力**仍被 **BLOCK-2** 挡住（删能力会改动幸存文件 `CourseMapper.java` 的乐观锁辅助方法，`SIR-CHANGE-IMPACT-202` 拒绝）；该阻断随后由 Q15 解除，不是当前待裁决项。Q12（路由模板）未立项，独立项见 `docs/roadmap/REMAINING_WORK.md`。

**阶段 1–6 的六张工作单全部完成并归档**（`docs/roadmap/completed/`）：Q1 Generator 生产边界、Q2 Project Graph 直接契约（0→72）、Q3 Change fixtures 与软链接收口、Q4+Q5 conformance 恢复与真实 MySQL 矩阵（QUALIFIED）、Q6 三路径故障矩阵（64 项，零生产改动）、Q7 CLI 只读边界（12 项）。以下为逐条历史记录：

1. ~~为 Generator 建立系统行为测试和生成工程编译验收。~~ 已完成（Q1，2026-09-18 验收并归档；已包含在 G0 实现提交 `24eec6d`）。
2. ~~为 Project Graph 建立直接模块测试。~~ 已完成（Q2，2026-09-18 验收并归档，72 项全绿；已包含在 G0 实现提交 `24eec6d`）。
3. ~~补齐 Change base/candidate fixtures，消除 assumption skip。~~ **已完成并验收**（Q3，2026-09-18，该阶段全量 465/0/0/5；当前总数为 554/0/0/5）：13 个 fixture，两个测试类共 19 项从 skip 变为真实执行并通过。仍未做：Change 操作族跨版本组合矩阵与完整 `SIR-CHANGE-*` 失败矩阵。
4. ~~恢复 conformance harness 并对着真实 MySQL 验证。~~ **已完成**（Q4+Q5 已合并执行并归档：编译错误 56 → 0，两个 POM 排除已删除，真实 MySQL 8.4.11 上五场景 QUALIFIED；原记录：实测基线 56 个编译错误，精确符号清单见 `completed/Q45-conformance-harness-and-real-mysql-matrix.md`；参考 MySQL 环境已就绪）
5. ~~补齐 UPDATE/CREATE/DELETE 故障注入矩阵。~~ **已完成**（Q6，2026-09-18 验收并归档：三路径同一套中断点矩阵，64 项新测试全绿，零生产代码改动）。
6. ~~决定是否发布 ADR-019 的完整 CLI 生命周期。~~ **已决定**：Q7 按形态 A 冻结并证明当前只读边界（四个写命令仍未发布）；是否发布完整生命周期留待 G1+。

详细任务和完成条件见 `docs/roadmap/REMAINING_WORK.md`。G0 之后的 G1–G7 产品方向与阶段完成门见 `docs/roadmap/README.md` 和 `docs/design/README.md`；它们不改变本文件的能力结论，也不解除未发布边界。

## 7. 文档状态规则

- `docs/PROJECT_OWNER_GUIDE.md` 是项目负责人的操作入口。
- `docs/design/README.md` 与 `docs/design/implementation-baseline.md` 是目标产品设计和当次实现校准的入口；它们描述的是目标与当时边界，不证明当前能力。
- `docs/roadmap/README.md` 是 G0–G7 阶段方向与阶段 Prompt 的入口；G0–G7 负责方向，Q 系列工作单负责执行授权。
- `docs/roadmap/ACTIVE_WORK.md` 是当前工作单文件（或“无已授权工作单”占位）；已完成工作单归档在 `docs/roadmap/completed/`。
- 本文件是当前能力入口。
- `docs/qualification/` 只记录实际运行证据和覆盖缺口。
- Accepted ADR 记录冻结契约。
- `docs/superpowers/specs/` 下的日期化文件记录当时设计背景，其中“当前”“下一阶段”和历史运行数字不自动代表今天的状态。
- `.memory/LOG` 与 Git 历史用于追溯，不参与当前状态裁决。
