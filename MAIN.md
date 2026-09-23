# KCG-Code 全局工程上下文

本文件定义项目目标、仓库规则和架构边界；修改前按第 0 节加载上下文，不重复维护能力进度与测试计数。

## 0. 权威层级与冲突处理

不同文档只负责一种事实，避免多个“当前状态”互相覆盖：

1. 用户当前指令与本文件负责工作授权、范围和工程强约束。
2. Accepted ADR 负责已经冻结的架构意图和跨模块契约。
3. 当前生产代码与实际执行的测试负责证明现有行为；被排除、跳过或未运行的测试不构成行为证据。
4. `docs/PROJECT_STATUS.md` 和 `docs/qualification/CURRENT_QUALIFICATION.md` 负责当前能力与资格状态。
5. `docs/design/` 主设计文档集与 `docs/roadmap/README.md` 负责目标产品契约、验收场景和 G0–G7 阶段方向；它们是**后续功能方向的主入口**（导航见 `docs/README.md`），但不证明任何能力当前已经实现。
6. `.memory/` 是提炼后的长期上下文；日期化 spec、LOG 和 Git 历史只作为背景证据，不是当前状态权威。

如果 Accepted ADR 与当前代码或可执行测试冲突，必须先停止普通功能开发并显式裁决，不得用旧报告静默覆盖代码，也不得把当前偶然行为自动升格为新架构。

**目标不等于现状：**“应该做什么”看主设计，“已经能做什么”看代码、实测和状态文档；不能因设计或同名代码存在就记为完成，冲突按上述层级裁决。

**G 定方向，Q 定执行：**G0–G7 定义阶段依赖和完成门，一个 G 阶段可含多张 Q 工作单。只有 `docs/roadmap/ACTIVE_WORK.md` 定义当前工作单授权；设计、阶段 Prompt 均不授权开工，Q 工作单也不得跳过 G 阶段门。

### 冷启动：新对话、新 Agent 或上下文不确定

不依赖旧聊天或私人记忆，修改前按顺序完整读取：

1. `AGENTS.md`
2. `MAIN.md`（项目主体说明）
3. `docs/PROJECT_OWNER_GUIDE.md`
4. `docs/PROJECT_STATUS.md`
5. `docs/roadmap/ACTIVE_WORK.md`
6. `docs/qualification/CURRENT_QUALIFICATION.md`
7. `docs/design/README.md` 与 `docs/roadmap/README.md`（目标设计与当前 G 阶段方向）
8. 当前工作单直接相关的 ADR、代码和测试

### 连续续作：同一对话且上下文完整

每次开始或恢复工作都重读 `ACTIVE_WORK.md` 并运行 `git status --short`；用 `git diff` 确认文档变化，只补读变化的权威文档和本轮新涉及的 ADR、代码、测试。已完整读取且未变化的材料不重复加载。

上下文压缩后可沿用完整摘要；缺失或可能过期时补读，无法判断则冷启动。`.memory/INDEX.md`、`.memory/CORE/PROJECT.md` 仅作辅助，不默认加载 LOG、ARCHIVE 或全部日期化设计。

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

新增能力先交代**场景、复用语义、组合限制、目标实现、正反例**。优先组合已有能力；逐题加关键字或表达比通用语言更绕时，应重审抽象。支持原语不等于支持任意组合；不支持就诊断，不静默降级、不用任意 Java／SQL 绕过检查，扩展按登记契约与工作单执行。

模型做业务选择，工具维护机械身份与协议元数据（设计原则，非持久身份工具已实现声明）。确定性只保证同输入同输出，静态合法不保证符合需求；业务验收依据原始需求独立制定。性能按明确的数据规模、负载与指标实测，不由编译成功推导。

### 1.2 基座选型与投入原则

优先使用 **Java 21 核心 + Spring Boot 生成目标**：ANTLR、静态类型建模、JVM 诊断与目标生态匹配。仅在实测瓶颈或交付约束明确时评审迁移，不因语言更新或更底层而重写。

外围建议：Python 做训练／数据／实验，TypeScript 做网页交互；不复制核心语义和生成规则。三层语言不必相同，此建议不授权新增服务、第二 Target 或重写。

先验证收益，再扩大基础设施：已有切片可立项早期模型实验，无需等完整 Web／迁移平台，但不替代 G 阶段门。口径见[大创验证与使用价值](docs/design/07-validation-and-direction-roadmap.md#10-大创验证与使用价值)。

### 1.3 当前链路

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

只读、不可变、确定性的 Project Symbol Graph，包含 canonical serialization/load/validation；直接模块测试资格见 `docs/qualification/CURRENT_QUALIFICATION.md`，不在本文件重复维护进度。

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
11. 完成工作单要求的定向验证；全量验证按 `AGENTS.md`“测试资源约束”优先安排到 GitHub CI（公共仓库、托管 runner；仓库 workflow 建立前，确需全量时按该节的本机上限执行）。纯文档修改只做文档／差异检查；已有同源码、同验证范围的有效证据不因收尾或提交重复运行。本机收尾执行 `git diff --check` 与 `git status --short`。

以下为全量验证的命令基准，不是每次收尾默认执行清单；在实际执行环境中附加经核验的内存／并发限制：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify          # Windows 参考路径
mvn -Dmaven.repo.local=/root/.m2/repository -o clean verify     # Linux 参考路径
```

仓库路径按执行环境选择（CI 可使用自身缓存路径）。全新仓库先在线 priming，含生成工程依赖，再使用 `-o`；过程见 `docs/qualification/CURRENT_QUALIFICATION.md` 的 1.2。

最新资格数据及缺口只记录在 `docs/qualification/CURRENT_QUALIFICATION.md`，不在本文件冻结测试数量。

## 6. 当前工作边界

当前阶段、资格和工作授权按第 0 节取值；`REMAINING_WORK.md` 的阶段 1–7 是 G0 历史排队表，不自动成为当前待办。

G0 关闭不授权展开 Redis、第二 Target、Constraint VM、PSG `REFERENCES`、Snapshot V2、Change IR v0.7、GUI/daemon、多用户、Java 反向解析或新增量编译系统。G1–G7 后续能力也必须在前阶段门关闭并有 Q 工作单后实施；“可提前准备”不授权提前开工。

## 7. 文档入口

第 0 节已列必读入口；其他材料按需查阅：

- 总导航：`docs/README.md`；项目介绍：`README.md`。
- 架构说明：`docs/KCG-Code_系统架构与实现指南.md`。
- 覆盖清单：`docs/qualification/TEST_COVERAGE_INVENTORY.md`。
- G0 排队表：`docs/roadmap/REMAINING_WORK.md`；日期化实现校准：`docs/design/implementation-baseline.md`（不替代当前资格）。
- Resolve-once：`docs/architecture/ADR-001-resolve-once-and-bind-by-node-id.md`。
- Application ownership：`docs/architecture/ADR-003-toolchain-application-owns-project-application.md`。
- 本地 MVP 提议：`docs/architecture/ADR-019-local-software-ir-mvp-delivery-contract.md`。
- CURRENT 恢复方向：`docs/architecture/ADR-020-current-baseline-transaction-direction.md`。
