# KCG-Code 当前项目状态

> 更新日期：2026-09-21（**G0 完成门已关闭**：Q1–Q8 全部完成并归档；G1 第一张工作单 Q9 已完成、验收并归档（全量 620/0/0/5，真实 MySQL/HTTP 场景 40 项断言 0 失败）；第二张工作单 Q10（Course 写侧）实现与验证完成，状态 `AWAITING_ACCEPTANCE`：全量 **702/0/0/5**，真实 MySQL/HTTP 写侧场景 **61 项断言 0 失败**（含并发同版本竞态），Q9 场景同树回归 40 项 0 失败；第 3、5 节能力结论沿用日期化运行记录）
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
- CLI 的只读 context inspection 与 plan。

### 尚未作为当前产品能力发布

- CLI `generate`、`register`、`apply`、`recover` 完整本地生命周期。
- Docker / 迁移 / Web / 完整交付等 G1+ 能力尚未作为当前产品能力发布；外部 MySQL conformance 的资格结果见第 4 节。
- fat JAR 或独立 CLI 发行包；当前验证入口是 Maven exec。
- Redis Extension、第二 Target、Constraint VM、Java 反向解析、GUI/daemon、多用户和新的增量编译系统。

## 4. 当前资格状态

最近一次实际执行的离线 Reactor 记录（2026-09-18 Linux，完成形式）：

```bash
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

十个 Reactor 模块全部完成，Surefire 合计 **702 run / 0 fail / 0 error / 5 skip**（Q9 后记录合计 620；Q10 的实现与测试把合计推进到 702，见资格报告第 2 节的重测口径订正）。**冻结形式与完成形式均为 BUILD SUCCESS**。剩余 5 项 skip 全为 `PathSecurityReviewTest` 的 Windows junction 用例。精确模块数字、skip、POM 排除和未运行范围只以 `docs/qualification/CURRENT_QUALIFICATION.md` 的最近一次实际记录为准。

当前结论：

- 默认离线 Reactor：Linux 2026-09-21 冻结与完成两种形式均 BUILD SUCCESS，十模块完成、合计 **702/0/0/5**（Q10 后；剩余 5 skip 为 Windows junction）。Windows 2026-08-11 那次 `0 failed` 不能证明软链接拒绝路径可用（当次用例未真实执行），该证据现由 Linux 提供。
- G1 首切片（Q9，2026-09-18）：单文件 `Course` 分页查询在真实 MySQL 8.4.11 + HTTP 上 40 项业务断言全通过（含字面 `%`/`_` 的灵敏度对照、越界页、4 种非法分页 400、行指纹不变）；生成工程离线编译通过。schema 为测试 fixture（`schemaSource=TEST_FIXTURE_DDL`），**产品 INITIALIZE/UPDATE 仍未实现**；该 IT 为 opt-in，默认构建不跑。
- Generator 生产边界闸门（Q1，2026-09-18）：生产 census 37 个 class 禁止引用 0 违规、公开入口只为 `generate(SpringBootLoweredModel)`；未修改生产代码。
- 软链接拒绝路径（Q3，2026-09-18）：5 项失败已定性并修复——4 项为诊断消息拼写（`link in raw chain` → `symlink or reparse point in raw chain`），1 项为 `PathGuard` 叶子链诊断遮蔽（现只检查严格祖先链，叶子符号链接恢复报 `CONFLICT-001/002`）。新增 FAIL_IF_EXISTS 侧守卫测试，保证“不再检查叶子”不被错实现为“删掉叶子拒绝”。
- Change fixtures（Q3，2026-09-18）：新增 13 个 fixture，`ChangePlanningApplicationTest` 6 项与 `KcgCliWorkflowTest` 13 项从 skip 变为真实执行并通过。
- Project Graph 直接模块测试：已从 0 项补齐到 72 项全绿（Q2，2026-09-18，已验收归档）：四类边、规则矩阵、canonical 往返、只读边界闸门与不可信字节守卫；Q2 阶段全量完成形式为 451/5/0/11，当前总数为 554/0/0/5。
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

当前 G 阶段是 **G1：单文件课程业务切片**（阶段定义见 `docs/roadmap/README.md`）。**G0 完成门已闭合**（见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节），Q1–Q8 已完成并归档；**G1 的第一张工作单 Q9（Course 单实体分页查询端到端）已完成、验收并归档**（全量 554 → 620；真实 MySQL/HTTP 场景 40 项断言 0 失败）。当前工作单为 **Q10（Course 写侧：Create + Get + PATCH 三态 + version 冲突 + 结构化字段错误）**，`docs/roadmap/ACTIVE_WORK.md` 状态为 `AWAITING_ACCEPTANCE`：负责人 2026-09-21 批准 D0–D16 全部推荐项，实现与验证已完成（702/0/0/5、写侧场景 61 项断言 0 失败），等待验收。Q11（关联过滤）与 Q12（路由模板）尚未立项。

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
