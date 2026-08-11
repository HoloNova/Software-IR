---
created: 2026-07-16
updated: 2026-07-16
importance: critical
confidence: confirmed
source: document
status: active
---

# 约束条件

## 模块与依赖约束

- `sir-parser` 只负责 ANTLR4 词法/语法、AST、SourceSpan、稳定 AstNodeId 和解析诊断。
- `sir-semantic` 只依赖 `sir-parser`，不得依赖 Spring Boot、MyBatis-Plus、Redis 或具体 Generator。
- Lowering 必须以 `NormalizedSemanticModel` 为输入并产生独立 Lowered IR；Generator 只能渲染 Lowered IR，不能读取 AST。
- Core Semantic Model 不得出现 Spring 注解、MyBatis API、Redis 命令或 React/Vue 结构。

## 语义与确定性约束

- 每个 `AstNameRef` 是正式 AST 节点，并拥有稳定、结构化、与 Locale、时间和随机数无关的 AstNodeId。
- SymbolId 是项目公共黑板中的稳定语义身份；任何重复 SymbolId 必须立即失败。
- 后续 Pass 只消费 `referenceBindings` 和 SymbolTable，不重新按名称解析。
- `TypePass` 负责类型推导/兼容，`ValidatePass` 负责跨节点、Workflow 和 Constraint 规则，符号存在性只归 `ResolvePass`；同一问题不得多 Pass 重复诊断。
- `NormalizePass` 不修改 AST、不解析名称；合法结果不得包含 `sir://unknown`。
- 非法 SIR 返回结构化 `SemanticAnalysis.Failure`，普通用户错误不得逃逸为 NPE 等运行时异常。
- 公开集合保持不可变、迭代顺序确定；大小写转换使用 `Locale.ROOT`。

## 已冻结的 v0.1 规则

- Entity 在字段、actor、output 等位置通过 `Ref<Entity>` 使用；裸 Entity、`Optional<Entity>`、`List<Entity>` 非法。
- Workflow 局部变量按步骤顺序可见；Find 的 `item` 仅在该谓词内可见。
- 无分支 Workflow 恰好一个 Return，且 Return 必须是最后一步。
- Create 绑定所有非 Optional 字段，且不能绑定 generated identity。
- Update/Persist 目标为 `Ref<Entity>`，Update 不得修改 identity。
- `notBlank`、`email`、`length` 只适用于 String；`min`、`max` 只适用于 Int32/Int64/Decimal。
- `min/max` 接受可带负号的数字常量；`length(min,max)` 接受非负整数且 `min <= max`。
- Normalized Capability 的 `fails` 保存 Error SymbolId。

## 修改与验收协议

- 先添加能复现问题的最小测试，再修根因。
- 新增引用语法时同步补齐 AstNameRef ID、Resolve 绑定、Normalized 表达和确定性测试。
- 新增诊断时验证唯一所属 Pass、错误码、精确数量、SourceSpan，必要时验证 RelatedLocation。
- 完成前运行 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify`、`git diff --check` 和 `git status --short`。
- 当前测试下限为 parser 41、semantic 78、合计 119；减少必须说明原因。
- 不提交或覆盖用户本地 `.claude/`。
