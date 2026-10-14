# KCG-Code 全局工程上下文

本文件定义项目目标、仓库规则和架构边界；修改前按第 0 节加载上下文，不重复维护能力进度与测试计数。

## 0. 权威层级与冲突处理

不同文档只负责一种事实，避免多个“当前状态”互相覆盖：

1. 用户当前指令与本文件负责工作授权、范围和工程强约束。
2. Accepted ADR（`docs/architecture/`）负责已经冻结的架构意图和跨模块契约。
3. 当前生产代码与实际执行的测试负责证明现有行为；被排除、跳过或未运行的测试不构成行为证据。
4. `docs/PROJECT_STATUS.md` 与 `docs/qualification/README.md` 负责当前能力、验证口径和证据。
5. `docs/design/` 是目标产品设计，描述“应该做成什么”，不证明任何能力已经实现。
6. `docs/history/` 是已完成或退役的计划、报告和旧上下文，只用于追溯，不参与当前裁决。

如果 Accepted ADR 与当前代码或可执行测试冲突，必须先停止普通功能开发并显式裁决，不得用旧报告静默覆盖代码，也不得把当前偶然行为自动升格为新架构。

**目标不等于现状：**“应该做什么”看设计，“已经能做什么”看代码、实测和状态文档；不能因设计或同名代码存在就记为完成。

### 冷启动：新对话、新 Agent 或上下文不确定

不依赖旧聊天或私人记忆，修改前按顺序读取：

1. `AGENTS.md`
2. `MAIN.md`（本文件）
3. `docs/PROJECT_STATUS.md`
4. `docs/qualification/README.md`
5. 本次任务直接相关的 ADR、设计、代码和测试

同一对话续作时只补读变化的文档，并运行 `git status --short`。

## 1. 项目目标

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。目标不是普通 CRUD DSL，也不是让模型绕过 IR 直接生成 Java。

### 1.1 价值定位与开发判断

研究目标：在受支持的后端业务内，验证“微调 SLM + SIR”能否接近 LLM 直接开发的业务成功率，并降低推理或人工修复成本；不预先宣称有效。SIR 复用工程实现，不消除业务复杂性，也不替代通用语言。

**职责分工：**SIR 表达数据、关系、行为和可检查的约束；公开的语言／Profile 契约明确默认行为；Lowering 选择具体实现并写入 Lowered IR；Generator 只渲染。不得因“自动优化”改变权限、原子性、冲突或一致性语义。

| 场景 | 值得保留的抽象 | 不应走的方向 |
|---|---|---|
| 局部修改 | PATCH 缺席保持、null 清空、有值替换；版本匹配后才写入 | 普通可空字段混淆三态；先查版本再无条件覆盖 |
| 关联查询（设计例，非已实现声明） | “存在满足条件的关联记录”，由 Lowering 选择受支持的 SQL 实现 | 只写“性能高”就承诺优化；读全表后在 Java 中分页 |
| 名额／库存（设计例） | 复用条件写入及明确的事务规则，并验证各自业务不变量 | 按 Course、Inventory 名称在编译器里硬编码；把单行 version 当成跨实体原子性 |

新增能力先交代**场景、复用语义、组合限制、目标实现、正反例**。优先组合已有能力；逐题加关键字或表达比通用语言更绕时，应重审抽象。支持原语不等于支持任意组合；不支持就诊断，不静默降级、不用任意 Java／SQL 绕过检查，扩展按登记的契约和负责人的明确授权执行。

模型做业务选择，工具维护机械身份与协议元数据（设计原则，非持久身份工具已实现声明）。确定性只保证同输入同输出，静态合法不保证符合需求；业务验收依据原始需求独立制定。性能按明确的数据规模、负载与指标实测，不由编译成功推导。

### 1.2 基座选型与投入原则

优先使用 **Java 21 核心 + Spring Boot 生成目标**：ANTLR、静态类型建模、JVM 诊断与目标生态匹配。仅在实测瓶颈或交付约束明确时评审迁移，不因语言更新或更底层而重写。

外围建议：Python 做训练／数据／实验，TypeScript 做网页交互；不复制核心语义和生成规则。三层语言不必相同，此建议不授权新增服务、第二 Target 或重写。

先验证收益，再扩大基础设施：已有切片可立项早期模型实验，无需等完整 Web／迁移平台，但不替代 G 阶段门。口径见[大创验证与使用价值](docs/design/07-validation-and-direction-roadmap.md#10-大创验证与使用价值)。

### 1.3 当前链路

首次生成：

```text
SIR 源码 -> ANTLR4 Parser -> 不可变 AST
         -> Resolve -> Type -> Validate -> Normalize
         -> NormalizedSemanticModel
         -> Spring Boot Target Lowering -> SpringBootLoweredModel
         -> 确定性 Generator -> 内存中的 GeneratedFile 集合
         -> Toolchain Application + Project Symbol Graph
         -> 写入一个尚不存在的输出根（暂存目录 + 一次原子移动）
```

后续变更（ADR-027：当前版本是磁盘上的 SIR，历史是 Git）：

```text
磁盘上的 SIR + 候选 SIR（增删改指令编辑出来，或手写）
  -> 两边各完整编译，并要求整个生成工程等于“当前 SIR 的编译结果”
  -> 计划（新建／更新／删除生成文件，变化的源文件）
  -> 可选：应用前独立验证（静态／构建／启动／业务）
  -> 需要 Git：保存当前版本 -> 写生成文件 -> 写 SIR 源码 -> 保存新版本
  -> 中途停下时重跑同一应用；要回退就用 Git
```

### 1.4 分层与指令修改（ADR-026，已实现）

首次由 SIR 理解软件并生成源码；后续用增加／删除／更新三种固定指令编辑软件；拉依赖、构建、启动和业务验证是独立环节。详细方案见[设计08](docs/design/08-layered-command-architecture.md)与 [ADR-026](docs/architecture/ADR-026-layered-semantic-command-architecture.md)。

Semantic 拥有唯一的类型化软件结构定义（`SoftwareSchema`）；Change 只处理固定三种机械操作与结构差异，不为新增功能补专用操作。可编辑草稿与已验证模型分开，修改后写出新 SIR 并重新做正式分析，不沿用旧 binding。业务合法性归 Semantic、目标支持与物理实现归 Lowering、文件差异与版本发布归 Application。

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
12. Generator 保持纯函数且不接触磁盘。路径、冲突、文件写入、Git 调用和版本发布只归 Application 层。
13. Project Symbol Graph 不重新解析名称、不访问文件系统；当前图能力不得被文档夸大为完整引用图、图数据库或增量索引。
14. CLI 只能把严格参数转换为 typed Application request 并渲染 canonical JSON，不得自己读写工程文件或调用 Git。

## 3. 版本、历史与恢复（ADR-027）

- **当前版本的权威是磁盘上的 SIR 源码；历史的权威是 Git。** 系统不保存版本历史、发布关系、回执或事务日志。
- **生成的工程不允许手改。** 受管文件与“当前 SIR 的编译结果”不一致，一律报告为需要重新生成；工具链升级造成的差异同样处理，不区分手改与升级。
- **应用需要 Git。** `git` 不可用、仓库根不是 Git 顶层、保存当前版本失败，都在改动前拒绝。快照只含两个版本涉及的 SIR 源码和受管生成文件，不推送。
- **中断后重跑，不自动恢复。** 应用对半途状态宽容：磁盘文件已经等于候选版本的字节，就视为已应用。停下时返回“需要重跑”，用户重跑同一应用，或用 Git 回到第一次快照。
- 第一次生成只写不存在的输出根；已存在的输出根由版本变更处理。

## 4. 当前模块

### `sir-parser`

ANTLR4 语法（`sir 0.2` 根文档与声明片段两个入口）、严格 UTF-8、不可变 AST、SourceSpan、稳定 AstNodeId 和解析诊断。

### `sir-semantic`

`ResolvePass -> TypePass -> ValidatePass -> NormalizePass`，包含 typed reference-site binding，输出 `NormalizedSemanticModel` 或结构化 Failure；入口只有 `analyzeProject`（一个软件放在一个文件里，就是没有片段的项目）。`schema` 包是类型化软件结构（`SoftwareSchema`/`SoftwareDraft`），供增删改指令使用。

### `sir-lowering-api` / `sir-lowering-spring-boot`

从 Normalized model 产生独立、不可变、可验证的 `SpringBootLoweredModel`。Profile：Java 21 + Spring Boot 3.5.3 + MyBatis-Plus 3.5.12 + MySQL + Maven + REST。

### `sir-generator-spring-boot`

纯渲染 Lowered IR，生成不可变内存文件集合：Maven、Application、Enum、Entity、Mapper、DTO、Exception、Service 和 Controller。

### `sir-project-graph`

只读、不可变、确定性的 Project Symbol Graph，带 canonical serialization/load/validation；总是携带源清单（每个节点能指到具体源文件）。不重新解析名称、不访问文件系统。

### `sir-change`

增删改指令（`Command`/`CommandBatch`）的编辑：把指令应用到类型化软件草稿，只重写变化的声明并写回 SIR 文本，再用正式分析复核。

### `sir-toolchain-application`

统一编排：编译（`ToolchainApplication` 首次生成）、基于 Git 的版本变更（`WorkspaceVersionApplication`：规划与应用）、独立验证（`CandidateVerificationApplication`/`VerifiedWorkspaceApplication`，静态／构建／启动／业务四级）、`SirValidationApplication`（纯内存静态校验与有界 JSONL 批量）。Git、Maven 构建、应用进程通过端口接入（`execution` 包是适配器）。

### `kcg-cli`

公开命令是 `check`、只读的 `version-plan` 和会改工程的 `version-apply`（要求 git；协议 `KCG-CLI-VERSION-CHANGE-V1`）。`check` 从 stdin 接收 JSONL（每行一个 `sir 0.2` 文档），按请求阶段校验并输出 canonical 结果；使用与边界见 [SIR 批量校验](docs/SIR_BATCH_CHECK.md)。`generate`、`register`、`apply`、`recover` 不是公开命令。

## 5. 修改与验收协议

1. 修改生产行为前先写能复现问题的最小测试，再修根因。
2. 新增引用语法时同步补齐 AstNameRef ID、ReferenceRole、Resolve binding、Normalized 表达和确定性测试。
3. 新增诊断时明确唯一所属阶段，并验证错误码、数量、SourceSpan；需要时验证 RelatedLocation。
4. 公共集合保持不可变且顺序确定；大小写转换必须使用 `Locale.ROOT`。
5. 高风险文件系统路径必须验证绝对根、归一化包含关系、Windows 路径规则、大小写重复、symlink/junction/reparse point 和提交时 TOCTOU。
6. 用户工作期间新增的代码注释属于受保护内容；除非用户明确要求，不得删除、覆盖、重排或通过批量格式化改写。
7. 发现任务范围外改动时保留原状，不使用 `git restore`、checkout、reset、clean 或 stash 覆盖用户工作区。
8. 测试数量必须来自当次 Surefire XML/命令输出。历史数字、跳过项、POM 排除项和外部资格不得混写成“全部通过”。
9. 详细实现证据写入项目状态、验证说明或对应报告；如果没有失败、阻断或需要项目负责人裁决的方向，面向用户的阶段报告保持简短，只请求确认是否继续。
10. 版本快照按以下规则执行：
   - 本地提交、推送和历史操作必须由负责人当时明确授权；任务验收、完成汇报或“继续”不自动授予Git操作，也不自动启动下一阶段。
   - 提交范围只包含本轮已审阅、获授权内容；既有任务外/未跟踪材料保留，不用全量add混入。
   - 不自动force-push、rebase、reset、checkout、restore、clean或stash；提交/推送失败时报告实际阻断，不改写历史解决。
11. 完成任务要求的定向验证；全量验证按 `AGENTS.md`“测试资源约束”优先安排到 GitHub CI（公共仓库、托管 runner；仓库 workflow 建立前，确需全量时按该节的本机上限执行）。纯文档修改只做文档／差异检查；已有同源码、同验证范围的有效证据不因收尾或提交重复运行。本机收尾执行 `git diff --check` 与 `git status --short`。

以下为全量验证的命令基准，不是每次收尾默认执行清单；在实际执行环境中附加经核验的内存／并发限制：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify          # Windows 参考路径
mvn -Dmaven.repo.local=/root/.m2/repository -o clean verify     # Linux 参考路径
```

仓库路径按执行环境选择（CI 可使用自身缓存路径）。全新仓库先在线 priming，含生成工程依赖，再使用 `-o`；过程见 `docs/qualification/README.md`。

最新验证数据与缺口只记录在 `docs/PROJECT_STATUS.md` 与 `docs/qualification/README.md`，不在本文件冻结测试数量。

## 6. 当前工作边界

不在当前范围、未经负责人明确授权不得展开：Redis、第二 Target、Constraint VM、Java 反向解析、GUI/daemon、多用户、新的增量编译系统、数据库迁移、Docker 交付包、Web 平台。目标设计可以提前阅读，“可提前准备”不授权提前开工。

## 7. 文档入口

- 现状与已知限制：`docs/PROJECT_STATUS.md`；验证口径与证据：`docs/qualification/README.md`。
- 总导航：`docs/README.md`；项目介绍：`README.md`；架构说明：`docs/KCG-Code_系统架构与实现指南.md`。
- 架构裁决：`docs/architecture/`（ADR-001 Resolve-once、ADR-003 Application ownership、ADR-021 项目文档与源快照、ADR-025 批量校验、ADR-026 分层与指令修改、ADR-027 Git 版本历史）。
- 目标设计：`docs/design/`。
- Claude 会话的执行约定：`docs/CLAUDE_EXECUTION_POLICY.md`。
- 已完成或退役的材料：`docs/history/`。
