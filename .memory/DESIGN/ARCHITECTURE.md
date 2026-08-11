---
created: 2026-07-16
updated: 2026-07-16
importance: high
confidence: confirmed
source: document
status: active
---

# 架构文档

## 系统概述

KCG-Code 把自然语言与最终工程之间拆为可验证的编译管线。SLM/Agent 可以生成 SIR，但语法、名称、类型、约束和目标映射都由确定性程序校验。当前实现覆盖语言前端、核心语义以及第一条最小 Spring Boot Target Lowering。

## 长期架构

```text
需求与项目上下文
  -> SLM / Agent 生成或修改 SIR
  -> Core Semantic IR + Extension
  -> Project Symbol Graph + Constraint VM/Constraint Pack
  -> Target Profile 驱动 Target Lowering
  -> 独立 Lowered IR
  -> Deterministic Generator
  -> 代码、配置、依赖、迁移脚本和使用指南
```

职责划分：

- **Software IR：** 表达经过约束的软件意图与核心语义。
- **Project Symbol Graph：** 以稳定 SymbolId/AstNodeId 记录声明、引用、依赖、Artifact ownership 和修改影响范围。
- **Constraint VM：** 执行可组合的静态架构与业务约束；Constraint Pack 提供具体规则集合。
- **Extension：** 扩展 Core 未涵盖但仍需被类型检查和验证的能力，如缓存资源或接口契约。
- **Target Profile：** 冻结目标技术栈、版本、组件选择和生成策略。
- **Lowered IR：** 已绑定目标技术但仍与模板表现形式解耦的施工模型。
- **Generator：** 对 Lowered IR 做稳定排序和纯渲染，不重新理解业务语义。

## 当前实现管线

```text
SIR Source
  -> ANTLR4 Parser
  -> Immutable AST
  -> ResolvePass -> ResolvedContext
  -> TypePass -> TypedContext
  -> ValidatePass -> ValidatedContext
  -> NormalizePass
  -> Success(NormalizedSemanticModel) / Failure(Diagnostics)
  -> TargetLowering<SpringBootLoweredModel>
  -> Success(SpringBootLoweredModel) / Failure(LoweringDiagnostics)
```

### sir-parser

- **职责：** 识别“写了什么”，生成不可变 AST、SourceSpan、稳定 AstNodeId 和解析诊断。
- **公共入口：** `io.kcg.sir.api`。
- **边界：** 不判断声明是否存在、表达式类型或框架实现。

### sir-semantic

- **职责：** 判断“它意味着什么”，建立符号黑板、绑定引用、推导类型、验证跨节点规则并生成 canonical model。
- **公共入口：** `io.kcg.sir.semantic.api.SirSemanticAnalyzer`。
- **依赖：** 仅 `sir-parser`。

### sir-lowering-api

- **职责：** 定义 `NormalizedSemanticModel -> LoweringAnalysis` 契约、独立诊断、Lowered IR 版本、稳定节点身份、来源追踪和 Validator 接口。
- **边界：** 不包含 Spring、HTTP、MyBatis-Plus、文件或模板概念。
- **依赖：** 仅 `sir-semantic`。

### sir-lowering-spring-boot

- **职责：** 固定 Java 21 + Spring Boot 3.5.3 + MyBatis-Plus 3.5.12 + MySQL + Maven + REST Profile；产生强类型 `SpringBootLoweredModel`。
- **Lowered 节点：** Enum、Entity、Input、Error、Capability、Artifact ownership、7 种 Workflow step、10 种 expression、Java type 与 validation constraint。
- **边界：** 不生成源码或构建文件，不依赖 Spring 运行库；只按 SymbolId 消费已有绑定，不按名称重新解析。

## 语义阶段边界

- **ResolvePass：** 唯一按名称查找的阶段；建立 SymbolTable、`referenceBindings`、`typeRefTypes`、作用域和顺序可见性。
- **TypePass：** 消费绑定，推导表达式类型并检查赋值兼容；不处理符号是否存在。
- **ValidatePass：** 检查 readonly/atomic、Return、Create/Update/Persist、Error fails 和 Constraint 等跨节点规则。
- **NormalizePass：** 把已验证快照转换为 Normalized declarations/workflows/steps/expressions，消除括号等表面差异，不重新解析名称。

## 身份与追踪

`SourceSpan -> AstNodeId -> SymbolId` 构成完整追踪链：SourceSpan 定位源码，AstNodeId 标识某一次结构/引用，SymbolId 标识跨阶段稳定的语义实体。

## 尚未实现的部署与运行时

当前已有 Spring Boot 施工模型，但不存在 Generator 或可部署运行时，也没有实际 Spring Boot、数据库、Redis 或前端依赖。下一阶段只能从 Lowered IR 纯渲染代码；现阶段不得把 Lowering 描述成已生成或运行应用。
