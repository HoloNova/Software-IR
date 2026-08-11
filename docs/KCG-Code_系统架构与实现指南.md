# KCG-Code 系统架构与实现指南

> 更新日期：2026-08-11
> 适用版本：当前 `main` 的 v0.1 九模块工程

## 1. 用一句话理解 KCG-Code

KCG-Code 把 Agent 提出的软件意图先变成可检查的 Software IR，再通过确定性编译管线把它转换为目标工程、Project Graph 和受保护的变更计划。

Agent 可以生成或修改 SIR，但不能绕过 Parser、Semantic、Lowering 和 Application 直接决定最终 Java 工程状态。

## 2. 权威文档怎么读

- `AGENTS.md`：仓库工作规则和不可破坏边界。
- Accepted ADR：冻结架构决策。
- 当前生产代码和实际执行测试：现有行为证据。
- `docs/PROJECT_STATUS.md`：当前能力边界。
- `docs/qualification/CURRENT_QUALIFICATION.md`：当前运行证据。
- 日期化 spec：当时设计背景，不负责今天的状态。

如果不同层发生冲突，不从旧报告选择一个“看起来更完整”的版本；先核对当前代码、测试和 Accepted ADR，再显式裁决。

## 3. 总体架构

```text
需求 / 项目上下文
  -> Agent 或 SLM 产生 Software IR
  -> Parser / Immutable AST
  -> Resolve / Type / Validate / Normalize
  -> Normalized Semantic Model
  -> Target Profile + Target Lowering
  -> independent Lowered IR
  -> deterministic Generator
  -> immutable GeneratedFile set
  -> Application preflight + Project Symbol Graph
  -> explicit output root + immutable manifest + graph
```

已有工程的变更路径独立于首次生成路径：

```text
CURRENT Bundle + output + candidate SIR
  -> read-only context and target catalog
  -> Change IR planning
  -> fresh candidate/context/output validation
  -> UPDATE / CREATE / DELETE transaction
  -> next Bundle + CURRENT publish
  -> explicit recovery if a journal remains
```

长期系统仍包含 Extension、Constraint Pack/VM、其他 Target、Java 反向解析和更强增量能力，但它们不是当前已发布范围。

## 4. 九个 Maven 模块

### 4.1 `sir-parser`

负责“输入写了什么”：

- ANTLR4 Grammar。
- 严格 UTF-8 输入。
- 不可变 AST。
- `SourceSpan`、稳定 `AstNodeId`。
- Lexer/Parser 结构化诊断。

Parser 不判断符号是否存在、表达式类型或 Spring 映射。

### 4.2 `sir-semantic`

负责“输入意味着什么”：

```text
ResolvePass -> ResolvedContext
TypePass -> TypedContext
ValidatePass -> ValidatedContext
NormalizePass -> NormalizedSemanticModel
```

当前包含：

- SymbolTable、SymbolId、作用域和顺序可见性。
- typed reference-site：每个真实引用点拥有 AstNodeId、ReferenceRole 和唯一 binding。
- Primitive、Declared、Optional、List、Ref 类型系统。
- Workflow、Constraint、Create/Update/Persist、actor/output/fails 验证。
- canonical declarations、steps 和 expressions。

### 4.3 `sir-lowering-api`

定义 Target 无关的 Lowering 契约：

- Lowering result 和诊断。
- `LoweredNodeId`、`LoweredOrigin`。
- Lowered IR 版本和 Validator 接口。

这里不出现 Spring、HTTP、MyBatis-Plus、文件或模板概念。

### 4.4 `sir-lowering-spring-boot`

把 Normalized model 转成强类型 `SpringBootLoweredModel`。当前 Target Profile 固定：

- Java 21
- Spring Boot 3.5.3
- MyBatis-Plus 3.5.12
- MySQL
- Maven
- REST

Lowered IR 决定 Artifact、Java type、DTO、transport、actor identity、workflow、持久化和响应外形。Generator 不再补猜这些决策。

### 4.5 `sir-generator-spring-boot`

纯函数式渲染 Lowered IR，返回不可变内存 `GeneratedFile` 集合。当前 renderer 覆盖：

- `pom.xml`
- Application
- Enum / Entity / Mapper
- DTO / Exception
- Service / Controller

Generator 不访问磁盘，不读取 AST/SIR/SymbolTable，不处理冲突、回滚或 Bundle。

### 4.6 `sir-project-graph`

建立只读、不可变、确定性的 Project Symbol Graph。当前代码包含：

- 节点、边、provenance 和 validator。
- canonical document、encoder/decoder、loader 和 digest。
- DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE 等当前关系。

当前直接模块测试仍不足，因此不能把实现文件数量当作资格证据，也不能宣称完整 `REFERENCES` 图或图服务已发布。

### 4.7 `sir-change`

实现 Change IR v0.1-v0.6 typed planning：

- 修改 capability workflow。
- 添加/删除 capability。
- 修改 input field constraint。
- 修改未引用 input field type。
- 修改 actorless readonly exposure。
- scope、impact、closure、Artifact/File change plan。

Change target 必须依赖稳定身份，不使用名称模糊匹配。

### 4.8 `sir-toolchain-application`

Application 是所有工程状态与文件系统写入的唯一权威：

- SIR read/compile/generate/Graph orchestration。
- 显式输出根、路径和冲突 preflight。
- Bundle、CURRENT、LOCK、Journal。
- registration、context inspection、planning。
- UPDATE、CREATE、DELETE Apply。
- staging、backup、commit、publish、rollback 和显式恢复。

CLI、Generator 和 Change planner 都不能直接制造或修改上述持久状态。

### 4.9 `kcg-cli`

当前发布的是 `KCG-CLI-CHANGE-PLANNING-V1`：

- `context`
- `plan`
- `--help`
- `--version`

CLI 只负责严格参数、typed Application request 和 canonical JSON。Proposed ADR-019 描述 `generate/register/apply/recover` 的完整本地生命周期，但这些命令不是当前已发布能力。

## 5. Resolve once 与身份链

如果 Resolve 已确认源码中的 `User` 指向符号 A，而后续阶段再按字符串搜索一次，就可能找到同名符号 B。这样同一份 SIR 会在不同阶段拥有不同含义。

因此当前身份链为：

```text
SourceSpan -> AstNodeId -> SymbolId -> LoweredNodeId / ArtifactId -> relative file path
```

- SourceSpan 定位文本。
- AstNodeId 标识结构节点或引用 site。
- SymbolId 标识稳定语义实体。
- LoweredNodeId/ArtifactId 标识 Target construction。
- relative file path 标识最终受管理 Artifact。

Type、Validate、Normalize、Lowering、Generator 和 Graph 必须消费已经建立的身份和 binding，不重新按名称恢复语义。

## 6. Lowered IR 为什么拥有生成决策权

如果 Generator 根据 AST 或名称临时决定 Controller、DTO、response shape 或 actor identity，它会成为第二个语义阶段，导致不同模板得到不同业务含义。

当前边界是：

1. Semantic 决定目标无关语义。
2. Target Lowering 决定 Spring Boot construction model。
3. Lowered IR Validator 检查内部一致性。
4. Generator 只排序、转义和渲染。

其他 Target 可以拥有自己的 Lowered IR，但不能把框架字段倒灌进 Core。

## 7. Project Graph 与 Change 的关系

Project Graph 提供 semantic declaration、lowered artifact 和 generated file 的 provenance。Change planner 使用稳定目标、scope、impact 和 closure 描述要改变的内容。

当前不要把两者夸大为自动修改任意现有 Java 项目：

- 没有完整 Java 反向解析。
- 没有图数据库或持续索引服务。
- 没有通用用户手写区域合并器。
- 当前 `REFERENCES` 扩展和更强增量协议仍在路线图之外。

实际 Apply 只能触及经过当前 Bundle、plan、fresh validation 和 transaction proof 授权的文件。

## 8. CURRENT 与显式恢复

`CURRENT` 是基线发布的唯一线性化点。Accepted ADR-020 规定：

- `CURRENT=B0`：B1 尚未发布，只允许向后补偿到 B0。
- `CURRENT=B1`：B1 已发布，只允许向前验证和清理。
- CURRENT 不匹配、Bundle/Journal 缺失、路径或物理身份不确定时 fail closed。
- 不观察磁盘内容猜方向，不隐式启动恢复。

这套方向同时适用于 UPDATE、CREATE 和 DELETE。

DELETE backup 继续使用同卷 hard link；每次 `Files.isSameFile` 前必须通过 `NOFOLLOW_LINKS` 重新证明两端都是 regular file。权限、I/O、symlink、junction、类型或身份异常都不能当作“文件不存在”。

## 9. 确定性与安全措施

- 稳定、结构化 ID。
- 不可变公开集合。
- 明确排序和 `Locale.ROOT`。
- canonical UTF-8 与 SHA-256。
- 绝对、归一化输出根。
- Windows 非法字符、保留名、trailing dot/space 和大小写重复拒绝。
- symlink、junction/reparse point 和目标链检查。
- preflight 后在 commit 时重检。
- `FAIL_IF_EXISTS` 默认零覆盖。
- 同卷 staging/atomic move 或有 Journal 的逐文件事务。
- 无法证明一致性时返回结构化 Failure/`RECOVERY_REQUIRED`。

这些是实现边界，不是性能、规模、生产安全或全平台宣传。

## 10. 如何修改代码

1. 从 `docs/PROJECT_STATUS.md` 确认功能是否已实现。
2. 阅读任务对应 Accepted ADR；若只有 Proposed ADR，不把它当成实现授权。
3. 沿真实入口复现问题，先写最小失败测试。
4. 修根因和阶段边界，不在最终层吞异常或加字符串 fallback。
5. 修改引用时同步检查 AstNodeId、ReferenceRole、binding、Normalized model 和确定性。
6. 修改 Target 决策时先改 Lowered IR/validator，再改 renderer。
7. 修改文件事务时覆盖 CURRENT、Bundle、Journal、物理身份和故障注入。
8. 用当次测试输出更新资格报告，不复制旧数字。

## 11. 当前资格与下一步

当前默认离线 Reactor 最近运行通过；精确数字只见 `docs/qualification/CURRENT_QUALIFICATION.md`。conformance 包仍被 POM 排除，Generator 后续语义/环境/编译资格和 Project Graph 直接测试仍不足，Change fixtures 仍导致部分测试跳过。

因此当前可以说默认离线构建通过，不能说完整 conformance、完整本地 MVP 或生产资格通过。

- 精确状态：`docs/qualification/CURRENT_QUALIFICATION.md`
- 测试缺口：`docs/qualification/TEST_COVERAGE_INVENTORY.md`
- 执行顺序：`docs/roadmap/REMAINING_WORK.md`
