# KCG-Code 全局工程上下文

本文件定义仓库级工作规则和不可破坏的架构边界。新对话、新 Agent 或上下文不确定时，修改代码前必须完整读取本文件；同一对话连续推进且已经读取过未变化的本文件时，不要求机械重读，但仍必须读取最新活动工作单和本轮新涉及的代码、测试与设计材料。

## 0. 权威层级与冲突处理

不同文档只负责一种事实，避免多个“当前状态”互相覆盖：

1. 用户当前指令与本文件负责工作授权、范围和工程强约束。
2. Accepted ADR 负责已经冻结的架构意图和跨模块契约。
3. 当前生产代码与实际执行的测试负责证明现有行为；被排除、跳过或未运行的测试不构成行为证据。
4. `docs/PROJECT_STATUS.md` 和 `docs/qualification/CURRENT_QUALIFICATION.md` 负责当前能力与资格状态。
5. `docs/design/` 主设计文档集与 `docs/roadmap/README.md` 负责目标产品契约、验收场景和 G0–G7 阶段方向；它们是**后续功能方向的主入口**（导航见 `docs/README.md`），但不证明任何能力当前已经实现。
6. `.memory/` 是提炼后的长期上下文；日期化 spec、LOG 和 Git 历史只作为背景证据，不是当前状态权威。

如果 Accepted ADR 与当前代码或可执行测试冲突，必须先停止普通功能开发并显式裁决，不得用旧报告静默覆盖代码，也不得把当前偶然行为自动升格为新架构。

问题分两类，不使用同一把尺子：

- **“应该实现什么、下一步做什么”**：以主设计和 G0–G7 阶段门为主入口。
- **“现在已经实现什么”**：以当前源码、实际执行的测试、`PROJECT_STATUS.md` 和资格文档为准。

两类文档冲突时按上面 1–6 的层级裁决，不得用任一方的表述覆盖另一方。目标契约尚未实现时只能记为“尚未实现”，不得因为设计已经写出、或某个模块已经存在同名代码而记为完成。

阶段编号有两套，职责不同，不得混用：

- **G0–G7**（`docs/roadmap/README.md`、`docs/design/07-validation-and-direction-roadmap.md`）负责产品方向、阶段进入条件与阶段完成门。
- **Q 系列**（`docs/roadmap/completed/` 与本文件点名的工作单编号）是仓库中实际执行的工作单编号，执行授权只来自 `docs/roadmap/ACTIVE_WORK.md`。

同一个 G 阶段可以包含多张 Q 工作单；Q 工作单不得跳过当前 G 阶段的完成门。G 阶段描述、设计文档正文和其中附带的 Agent Prompt 都不构成修改生产代码的授权，实际授权仍只来自 `docs/roadmap/ACTIVE_WORK.md`。

上下文加载分为两种路径。

### 冷启动：新对话、新 Agent 或上下文不确定

不依赖旧聊天或任何 Agent 私人记忆，按以下最低顺序完整加载：

1. `AGENTS.md`
2. `MAIN.md`（项目主体说明）
3. `docs/PROJECT_OWNER_GUIDE.md`
4. `docs/PROJECT_STATUS.md`
5. `docs/roadmap/ACTIVE_WORK.md`
6. `docs/qualification/CURRENT_QUALIFICATION.md`
7. `docs/design/README.md` 与 `docs/roadmap/README.md`（目标设计与当前 G 阶段方向）
8. 当前工作单直接相关的 ADR、代码和测试

### 连续续作：同一对话且上下文完整

1. 每次开始新工作单或恢复执行时，都重新读取最新的 `docs/roadmap/ACTIVE_WORK.md`，并运行 `git status --short`。
2. 已在本次连续对话中完整读取、此后又没有变化的 `AGENTS.md`、负责人手册、项目状态和资格文档不必重复读取。
3. 用 `git status` / `git diff` 确认权威文档是否在期间发生变化；只重读变化的权威文档，以及本轮首次涉及或尚未加载的 ADR、代码和测试。
4. 对话发生上下文压缩时，可以使用保留下来的会话摘要继续；只有摘要缺少本轮必要事实、结论可能已经过期或自己无法确认时，才补读相应文件。
5. 无法确定上下文是否完整时，按冷启动路径处理。

`.memory/INDEX.md` 和 `.memory/CORE/PROJECT.md` 可以用于加速理解，但不是继续项目的必要条件。不要默认加载 `.memory/LOG`、`.memory/ARCHIVE` 或全部日期化设计文档。

## 1. 项目目标

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。目标不是普通 CRUD DSL，也不是让模型绕过 IR 直接生成 Java。

当前主闭环：

```text
SIR Source -> ANTLR4 Parser -> Immutable AST
           -> Resolve -> Type -> Validate -> Normalize
           -> NormalizedSemanticModel
           -> Spring Boot Target Lowering
           -> SpringBootLoweredModel
           -> Deterministic Generator
           -> in-memory GeneratedFile set
           -> Toolchain Application + Project Symbol Graph
           -> explicit project root + immutable manifest + graph
```

当前变更闭环：

```text
CURRENT Bundle + output + candidate SIR
  -> read-only context and target catalog
  -> Change SIR planning
  -> fresh Apply validation
  -> UPDATE / CREATE / DELETE transaction
  -> next immutable Bundle + CURRENT publish
  -> explicit transaction recovery when required
```

## 2. 不可破坏的架构规则

1. `sir-parser` 只负责词法、语法、AST、SourceSpan、稳定 AstNodeId 和解析诊断，不包含 Target 或业务框架语义。
2. `sir-semantic` 只依赖 `sir-parser`，不得依赖 Spring Boot、MyBatis-Plus、Redis 或具体 Generator。
3. 名称只在 `ResolvePass` 中解析一次。后续 Pass 消费正式 binding 和 SymbolTable，不得重新按文本名称恢复语义。
4. 每个真实引用点必须有稳定、结构化、与 Locale、时间和随机数无关的 AstNodeId、ReferenceRole 和唯一 Resolve binding。
5. SymbolId 是跨阶段稳定语义身份；重复 SymbolId 必须立即失败，不得静默覆盖。
6. `TypePass` 只负责类型推导和兼容；`ValidatePass` 负责跨节点、Workflow 和 Constraint 规则；符号存在性只归 `ResolvePass`。
7. `NormalizePass` 不修改 AST、不解析名称，只把已验证快照转换为 canonical model。合法结果不得包含 `sir://unknown`。
8. 非法 SIR 返回结构化 Failure，不得因普通用户输入逃逸为 NPE 等运行时异常。
9. Core IR 表达目标无关语义；Spring、MyBatis-Plus、Redis、算法和前端契约通过 Extension、Target Profile、Constraint Pack 与 Lowered IR 接入。
10. 不嵌入任意 Java 源码作为常规逃生口。
11. Lowered IR 拥有所有生成决策权；Generator 只能消费 `SpringBootLoweredModel`，不得读取 AST、Normalized Model、SymbolTable 或 SIR。
12. Generator 保持纯函数且不接触磁盘。路径、冲突、Bundle、CURRENT、LOCK、Journal、事务和恢复只归 Application 层。
13. Project Symbol Graph 不重新解析名称、不访问文件系统；当前图能力不得被文档夸大为完整引用图、图数据库或增量索引。
14. CLI 只能把严格参数转换为 typed Application request 并渲染 canonical JSON，不得直接读写 Bundle、CURRENT、LOCK 或 Journal。

## 3. CURRENT 与事务恢复方向

ADR-020 已接受并冻结以下唯一解释：

- `CURRENT=B0`：新基线尚未发布，只允许向后补偿，恢复到 B0。
- `CURRENT=B1`：新基线已经发布，只允许向前验证和清理，不得恢复已提交的旧状态。
- CURRENT 不等于 Journal 声明的 B0 或 B1、证据缺失或物理身份不确定时，必须 fail closed 并保留事务证据。
- 恢复必须显式触发；不得通过观察文件“看起来更像哪一版”来猜方向。

该规则适用于 UPDATE、CREATE 和 DELETE，不得在单个事务实现中重新定义。

## 4. 当前模块

### `sir-parser`

ANTLR4 Grammar、严格 UTF-8、不可变 AST、SourceSpan、稳定 AstNodeId 和解析诊断。

### `sir-semantic`

`ResolvePass -> TypePass -> ValidatePass -> NormalizePass`，包含 typed reference-site binding，输出 `NormalizedSemanticModel` 或结构化 Failure。

### `sir-lowering-api` / `sir-lowering-spring-boot`

从 Normalized model 产生独立、不可变、可验证的 `SpringBootLoweredModel`。当前 Profile 为 Java 21 + Spring Boot 3.5.3 + MyBatis-Plus 3.5.12 + MySQL + Maven + REST。

### `sir-generator-spring-boot`

纯渲染 Lowered IR，生成不可变内存文件集合，包括 Maven、Application、Enum、Entity、Mapper、DTO、Exception、Service 和 Controller。

### `sir-project-graph`

只读、不可变、确定性的 Project Symbol Graph；当前实现包含 canonical serialization/load/validation 代码，但直接模块测试仍需补齐。

### `sir-change`

实现 Change IR v0.1-v0.6 的 typed planning API、作用域/影响分析、closure 和文件/Artifact change plan。不得用名称模糊匹配替代稳定身份。

### `sir-toolchain-application`

统一编排编译、生成、Graph、文件事务、Bundle、CURRENT、Change context/plan、UPDATE/CREATE/DELETE Apply 和显式恢复。它是工程状态唯一写入权威。

### `kcg-cli`

当前公开命令边界是只读 `context` 和 `plan`。`generate`、`register`、`apply`、`recover` 属于 ADR-019 提议的完整本地生命周期，尚未作为当前 CLI 命令发布。

## 5. 修改与验收协议

1. 修改生产行为前先写能复现问题的最小测试，再修根因。
2. 新增引用语法时同步补齐 AstNameRef ID、ReferenceRole、Resolve binding、Normalized 表达和确定性测试。
3. 新增诊断时明确唯一所属阶段，并验证错误码、数量、SourceSpan；需要时验证 RelatedLocation。
4. 公共集合保持不可变且顺序确定；大小写转换必须使用 `Locale.ROOT`。
5. 高风险文件系统路径必须验证绝对根、归一化包含关系、Windows 路径规则、大小写重复、symlink/junction/reparse point 和提交时 TOCTOU。
6. 用户工作期间新增的代码注释属于受保护内容；除非用户明确要求，不得删除、覆盖、重排或通过批量格式化改写。
7. 发现任务范围外改动时保留原状，不使用 `git restore`、checkout、reset、clean 或 stash 覆盖用户工作区。
8. 测试数量必须来自当次 Surefire XML/命令输出。历史数字、跳过项、POM 排除项和外部资格不得混写成“全部通过”。
9. 详细实现证据写入当前工作单和资格文档；如果没有失败、阻断或需要项目负责人裁决的方向，面向用户的阶段报告保持简短，只请求确认是否继续。
10. 版本快照按以下规则执行：
   - Q1A、Q1B1、Q1B2 这类小版本在完成门闭合且用户确认后，把已验收、非受保护的当前项目状态形成一次普通本地 Git commit，不推送。
   - Q1、Q2 这类大版本在全部子工作单完成且用户确认后，先确保本地快照完整，再把当前分支以普通 push 推送到已配置的 `origin`。
   - 用户说“确认”“继续”或同义表达，即授权完成当前验收、切换下一张工作单并执行本条对应的本地提交或大版本普通推送；不授权 force-push、rebase、历史改写或凭据留存。
   - 如果快照范围含有未验收、任务外或受保护内容，必须排除；如果普通提交/推送失败，只简要报告阻断证据。
11. 完成前执行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify          # Windows 工作机
mvn -Dmaven.repo.local=/root/.m2/repository -o clean verify     # Linux 工作机
git diff --check
git status --short
```

离线仓库路径按机器选择，两条命令等价；全新机器必须先在线 priming 一次本地仓库（含生成工程依赖），之后才能在 `-o` 下运行，过程见 `docs/qualification/CURRENT_QUALIFICATION.md` 的 1.2。

最新资格数据及缺口只记录在 `docs/qualification/CURRENT_QUALIFICATION.md`，不在本文件冻结测试数量。

## 6. 当前工作边界

当前 G 阶段是 **G0：现有链路资格收口**（见 `docs/roadmap/README.md`）。`docs/roadmap/REMAINING_WORK.md` 的阶段 1–7 是 G0 内部的 Q 系列排队表；本轮唯一授权工作仍由 `docs/roadmap/ACTIVE_WORK.md` 定义。优先顺序是 Generator 测试、PSG 测试、Change fixtures、conformance harness、事务故障矩阵和 CLI 产品边界。

在这些基础资格缺口收口、G0 完成门闭合前，不并行展开 Redis、第二 Target、Constraint VM、PSG `REFERENCES` 扩展、Snapshot V2、Change IR v0.7、GUI/daemon、多用户、Java 反向解析或新的增量编译系统。

同理，主设计中 G1–G7 的目标能力（单文件课程业务切片、持久身份与受控多文件、数据库生命周期、完整源码包与 Docker 交付、完整课程业务、网页平台与可靠发布、独立换题试用）在各自上一个 G 阶段完成门闭合并写出对应的 Q 工作单之前，不得展开实现。设计中“某项实现可提前准备”的表述不构成提前开工的授权。

## 7. 文档入口

- 文档总入口与导航：`docs/README.md`
- 项目介绍：`README.md`
- 项目负责人操作手册：`docs/PROJECT_OWNER_GUIDE.md`
- 当前唯一工作单：`docs/roadmap/ACTIVE_WORK.md`
- 当前状态：`docs/PROJECT_STATUS.md`
- 当前架构：`docs/KCG-Code_系统架构与实现指南.md`
- 当前资格：`docs/qualification/CURRENT_QUALIFICATION.md`
- 测试覆盖清单：`docs/qualification/TEST_COVERAGE_INVENTORY.md`
- 剩余路线图（G0 内部 Q 系列顺序）：`docs/roadmap/REMAINING_WORK.md`
- 主设计入口（目标产品与新阶段方向）：`docs/design/README.md`
- 现有实现与目标能力的当次校准：`docs/design/implementation-baseline.md`
- 阶段控制与阶段 Prompt（G0–G7）：`docs/roadmap/README.md`
- Resolve-once：`docs/architecture/ADR-001-resolve-once-and-bind-by-node-id.md`
- Application ownership：`docs/architecture/ADR-003-toolchain-application-owns-project-application.md`
- 本地 MVP 提议：`docs/architecture/ADR-019-local-software-ir-mvp-delivery-contract.md`
- CURRENT 事务方向：`docs/architecture/ADR-020-current-baseline-transaction-direction.md`

日期化 spec 记录当时设计背景；若其状态描述与当前入口冲突，以本文件的权威分工处理。

`docs/README.md` 是全部文档的导航入口。`docs/design/` 是目标设计的唯一入口：需要判断“应该实现什么”“下一步做什么”“某能力是否属于目标范围”“阶段完成门是什么”时以它和 `docs/roadmap/README.md` 为准。需要判断“现在实际能做什么”时以代码、可执行测试、`docs/PROJECT_STATUS.md` 和资格文档为准。
