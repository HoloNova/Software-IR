# KCG-Code 全局工程上下文

本文件是仓库级强约束。任何 Agent 在修改代码前必须先读本文件，再读与任务直接相关的设计文档和测试。若代码、旧报告与本文件冲突，以当前代码、当前测试和本文件为准；不要根据历史报告恢复已被替代的路线。

## 0. 项目记忆加载

新会话先读 `.memory/INDEX.md` 和 `.memory/CORE/PROJECT.md`，再按任务从 CORE、DESIGN、ACTIVE 渐进式加载相关文件；不要默认加载 LOG 或 ARCHIVE，也不要保存原始对话。`.memory/` 是提炼后的长期项目知识库，若它与当前代码、当前测试或本文件冲突，仍以当前代码、当前测试和本文件为准。

## 0. 项目记忆加载

新会话先读 `.memory/INDEX.md` 和 `.memory/CORE/PROJECT.md`，再按任务从 CORE、DESIGN、ACTIVE 渐进式加载相关文件；不要默认加载 LOG 或 ARCHIVE，也不要保存原始对话。`.memory/` 是提炼后的长期项目知识库，若它与当前代码、当前测试或本文件冲突，仍以当前代码、当前测试和本文件为准。

## 1. 项目目标

KCG-Code 是面向 Coding Agent 的 Software IR 编译与确定性代码生成工具链。目标不是普通 CRUD DSL，也不是让大模型绕过 IR 直接生成 Java。长期系统由 Software IR、Project Symbol Graph、Constraint VM、Target Profile、Lowered IR 和确定性 Generator 协同组成。

当前 v0.1 已完成从语言前端到安全工程应用和 Project Symbol Graph 的闭环：

```text
SIR Source -> ANTLR4 Parser -> Immutable AST
           -> Resolve -> Type -> Validate -> Normalize
           -> NormalizedSemanticModel
           -> Spring Boot Target Lowering
           -> SpringBootLoweredModel
           -> Deterministic Generator
           -> in-memory GeneratedFile set
           -> Toolchain Application
           -> Project Symbol Graph build/validate
           -> explicit project root + immutable execution manifest + graph
```

Generator 继续保持纯函数；`sir-toolchain-application` 负责统一编排、路径/冲突预检、只读 Graph 构建、同卷 staging、原子发布、回滚和结构化结果。Project Symbol Graph v0.1 已实现为只读、不可变、确定性追踪图。Redis Extension、Change SIR、Java 反向解析和 Constraint VM 尚未实现。

## 2. 不可破坏的架构规则

1. `sir-parser` 只负责词法、语法、AST、SourceSpan、稳定 AstNodeId 和解析诊断，不包含 Spring 或业务框架语义。
2. `sir-semantic` 只依赖 `sir-parser`，不得依赖 Spring Boot、MyBatis-Plus、Redis 或具体 Generator。
3. 名称只在 `ResolvePass` 中解析一次。后续 Pass 必须消费 `referenceBindings` 和 SymbolTable，不得重新按文本名称查找符号。
4. 每个 `AstNameRef` 都是正式 AST 节点，必须有稳定、结构化、与 Locale/随机数/时间无关的 AstNodeId。
5. SymbolId 是项目公共黑板中的稳定语义身份。重复 SymbolId 必须立即失败，不得静默覆盖。
6. `TypePass` 只负责类型推导和类型兼容；`ValidatePass` 负责跨节点/Workflow/Constraint 规则；符号是否存在由 `ResolvePass` 负责。同一问题不得由多个 Pass 重复诊断。
7. `NormalizePass` 不修改 AST、不解析名称，只把已验证快照转换为 canonical model。合法结果不得包含 `sir://unknown`。
8. 非法 SIR 必须返回结构化 `SemanticAnalysis.Failure`，不得因普通用户输入抛出 NullPointerException 等运行时异常。
9. Core IR 表达目标无关语义；Spring、MyBatis-Plus、Redis、算法和前端契约以后通过 Extension、Target Profile、Constraint Pack 与 Lowered IR 接入，不能污染 Core Semantic IR。
10. 不嵌入任意 Java 源码作为常规逃生口。若未来确需 escape hatch，必须隔离、显式标记、不可参与确定性语义保证。
11. Lowered IR 拥有所有生成决策权；Generator 只能消费 `SpringBootLoweredModel`，不得读取 AST、Normalized Model、SymbolTable 或 SIR。
12. `TransportPlan.responseRepresentation` 决定响应外形并必须与 output type 一致；actor identity transport 不得访问非 identity 成员；Find 的 AND/OR/NOT 必须保留表达式树分组。

## 3. 当前模块

### `sir-parser`

- Grammar：`sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4`
- 公共 API：`io.kcg.sir.api`
- AST：`io.kcg.sir.ast`
- Parser 实现：`io.kcg.sir.internal.DefaultSirParser`
- 当前语法只允许 v0.1 冻结形式，不要通过宽松解析制造同义语法。

### `sir-semantic`

- 入口：`io.kcg.sir.semantic.api.SirSemanticAnalyzer`
- 管线：`ResolvePass -> TypePass -> ValidatePass -> NormalizePass`
- 阶段快照：`ResolvedContext`、`TypedContext`、`ValidatedContext`
- 公共结果：`SemanticAnalysis`、`NormalizedSemanticModel`
- 符号系统：`SymbolTable`、`SymbolId`、`Symbol`
- 类型系统：Primitive、Declared、Optional、List、Ref
- canonical model：`io.kcg.sir.semantic.model.Normalized*`

### `sir-lowering-api` / `sir-lowering-spring-boot`

- 输入：`NormalizedSemanticModel`
- 输出：独立、不可变、可验证的 `SpringBootLoweredModel`
- Profile：Java 21 + Spring Boot 3.5.3 + MyBatis-Plus 3.5.12 + MySQL + Maven + REST
- ADR-002：`ProjectArtifact`、`ActorBinding`、`TransportPlan`、`FindStep.itemVariable`、`PersistStep.action`、Input Ref identity transport

### `sir-generator-spring-boot`

- 入口：`SpringBootGenerator.generate(SpringBootLoweredModel)`
- 输出：不可变内存 `GeneratedFile` 集合或结构化 `GenerationResult.Failure`
- 生成：`pom.xml`、`Application.java`、Enum/Entity/Mapper/DTO/Exception/Service/Controller
- 边界：不接触磁盘，不修补或猜测 Lowered IR 缺失的业务语义

### `sir-toolchain-application`

- 入口：`io.kcg.sir.application.api.ToolchainApplication.execute(ToolchainRequest)`
- 编排：严格 UTF-8 读取 -> Parser -> Semantic -> Spring Boot Lowering -> Generator -> Preflight -> Project Graph -> File Transaction
- 输出：不可变 `ToolchainResult.Success/Failure`、诊断、带 SHA-256 的应用清单和成功后发布的 `ProjectGraph`
- 策略：默认 `FAIL_IF_EXISTS`；显式 `REPLACE_EXISTING` 只替换普通文件
- 安全：绝对归一化输出根、Windows 路径规则、大小写重复、符号链接链、提交时冲突重检、备份回滚
- 边界：只消费公开 API；不把文件系统概念放入 Core、Lowering API 或 Generator

### `sir-project-graph`

- 输入：现有 `NormalizedSemanticModel`、`SpringBootLoweredModel`、`GeneratedFile` 与图级 `SourceId`
- 输出：目标中立、只读、不可变、确定性的 `ProjectGraph` 或结构化 `ProjectGraphAnalysis.Failure`
- 边界：不重新按名称解析、不解析身份字符串、不访问文件系统；v0.1 仅有 DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE，不含 REFERENCES、持久化、增量写入或 Change SIR
- 集成：Application GRAPH 阶段在 PREFLIGHT 后、WRITE 前；Failure 为 `NO_CHANGES`，Spring Boot 映射只位于 internal adapter

## 4. 已冻结的关键语义

- Entity 在字段、actor、output 等位置必须按规则通过 `Ref<Entity>` 使用；裸 Entity、`Optional<Entity>`、`List<Entity>` 非法。
- Workflow 局部变量按步骤顺序可见；Find 的 `item` 只在该 Find 谓词内可见。
- 无分支 v0.1 Workflow 必须恰好一个 Return，且 Return 是最后一步。
- Create 必须绑定所有非 Optional 字段，不能绑定 generated identity。
- Update/Persist 目标必须是 `Ref<Entity>`；Update 不得修改 identity。
- `notBlank`、`email`、`length` 只适用于 String；`min`、`max` 只适用于 Int32/Int64/Decimal。
- `min/max` 参数是可带负号的数字常量；`length(min,max)` 参数必须是非负整数且 `min <= max`。
- Normalized Capability 的 `fails` 保存 Error SymbolId，不保存待重新解析的名称字符串。

## 5. 修改与验收协议

1. 先写能复现问题的最小测试，再改实现。
2. 优先修根因和阶段边界，不在最终阶段通过吞异常掩盖问题。
3. 新增引用语法时，同时补齐 AstNameRef ID、Resolve 绑定、Normalized 表达和确定性测试。
4. 新增诊断时明确唯一所属 Pass，并验证错误码、精确数量、SourceSpan；需要时验证 RelatedLocation。
5. 公共集合保持不可变且顺序确定；大小写转换必须使用 `Locale.ROOT`。
6. 用户在工作期间新增的代码注释属于受保护内容：除非用户明确要求，不得删除、覆盖、重排或以“清理”为由改写这些注释；不得使用 `git restore`、checkout 或批量格式化覆盖用户工作区。发现任务范围外的改动时，保留原状、从暂存/提交范围排除，并向架构主 Agent 报告。
7. 完成前执行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

当前验收基线是 parser 43、semantic 78、lowering-api 4、spring-lowering 32、spring-generator 47、project-graph 94、toolchain-application 94，共 392 项测试，0 failure、0 error；4 项 Windows `PathGuard` 条件跳过为已知环境限制。测试数增加是允许的，减少必须说明原因。

## 6. 下一阶段建议边界

推荐下一阶段只推进 **typed reference-site 契约（PSG 的下一前置基础）**：在既有 `referenceBindings` 之外，为每个真正引用建立稳定的 reference-site `AstNodeId`、引用角色和唯一 Resolve 绑定。`referenceBindings` 当前混合真实引用、声明/identity、actor/input 与 workflow step 结果，`findItemBindings` 又按 Find step 保存 item 声明，二者不能直接作为未来 `REFERENCES` 的完整 provenance。现有 `AstNameRef` 和结构化 ID 已足够，本阶段不得修改 Grammar 或 ANTLR 配置。

该阶段必须同时收紧“名称只在 Resolve 解析一次”的既有边界：将 TypePass、ValidatePass、NormalizePass 中的 `byName` / `lookupInScope` 和基于 SymbolId 文本恢复语义的路径列为显式修复验收门，不得把它们当作顺手重构。测试必须覆盖全部引用角色、每个合法 site 恰一绑定、非法 site 仅由 Resolve 诊断、角色与目标 SymbolKind 相容、不可变性和确定性。typed reference-site 契约审核通过前，不增加 PSG `REFERENCES` 边，不实现 Graph 持久化、Change SIR、Java 反向解析或实际补丁。

Redis、算法和前后端联调的推荐接入点：

- Redis：Extension + Capability/Resource 语义 + Target Profile lowering；
- 算法：受类型检查的 Core/Extension 操作或受约束的外部能力契约；
- 前端联调：接口契约 Extension/Target Profile，不把 React/Vue 细节放入 Core；
- 项目修改：未来 Change SIR 以 SymbolId/AstNodeId 定位，不以名称模糊匹配，不通过整份 SIR 重生成覆盖用户代码。

后续顺序：typed reference-site 契约 ->（再评估）Graph canonical form 跨次序列化/加载校验 -> 最小 Change SIR。Redis、算法、前端契约、完整 Change SIR、Java 反向解析、图数据库、Constraint VM 和任意源码逃生口不得并行展开，也不要让新 Target 的模板读取 AST/Normalized Model，或在 Core Semantic Model 中加入 Spring、Redis、MyBatis、React/Vue 细节。

## 7. 文档入口

- 项目总纲：`KCG-Code_项目总纲与技术上下文.md`
- 当前架构说明：`docs/KCG-Code_系统架构与实现指南.md`
- 引用绑定 ADR：`docs/architecture/ADR-001-resolve-once-and-bind-by-node-id.md`
- Lowered IR 决策权 ADR：`docs/architecture/ADR-002-lowered-ir-owns-generation-decisions.md`
- SIR 最小语言：`docs/superpowers/specs/2026-07-13-sir-v0.1-minimal-language-design.md`
- Parser/AST 设计：`docs/superpowers/specs/2026-07-13-sir-v0.1-parser-ast-design.md`
- Semantic Model 设计：`docs/superpowers/specs/2026-07-14-sir-v0.1-semantic-model-design.md`
- 第三轮最终复核：`docs/superpowers/specs/2026-07-14-sir-v0.1-semantic-final-verification.md`
- 第三轮最终复核：`docs/superpowers/specs/2026-07-14-sir-v0.1-semantic-final-verification.md`
- Spring Boot Lowering：`docs/superpowers/specs/2026-07-16-sir-v0.1-lowering-design.md`
- 最小确定性 Generator：`docs/superpowers/specs/2026-07-16-sir-v0.1-generator-design.md`
- 工具链工程应用层：`docs/superpowers/specs/2026-07-17-toolchain-application-design.md`
- 工程应用职责 ADR：`docs/architecture/ADR-003-toolchain-application-owns-project-application.md`
- Project Symbol Graph ADR：`docs/architecture/ADR-004-project-symbol-graph-v0.1.md`
- Project Symbol Graph 设计：`docs/superpowers/specs/2026-07-17-project-symbol-graph-v0.1-design.md`

不要提交或覆盖用户本地的 `.claude/` 内容；它当前不属于本轮版本化工程文件。
