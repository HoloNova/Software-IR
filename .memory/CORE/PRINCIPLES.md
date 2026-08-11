---
created: 2026-07-16
updated: 2026-07-16
importance: critical
confidence: confirmed
source: user_decision
status: active
---

# 设计原则

## 原则 1：先表达意图，再绑定目标实现

Core IR 只表达目标无关的业务与软件语义；Spring Boot、MyBatis-Plus、Redis、算法实现和前端框架细节属于 Extension、Target Profile、Constraint Pack、Lowered IR 或 Generator。

## 原则 2：同一语义只有一种规范表达

SIR v0.1 优先使用冻结、严格的语法和 canonical semantic model，避免通过宽松语法制造同义写法。合法输入在 Normalize 后必须得到稳定、唯一、可供 Lowering 消费的语义形态。

## 原则 3：名称只解析一次

所有名称只在 Resolve 阶段解析，并通过 `AstNodeId -> SymbolId` 固化引用。Type、Validate、Normalize、未来 Lowering 与 Generator 都消费绑定结果，不重新按文本名称猜测含义。

## 原则 4：确定性来自可验证管线

确定性不是“模型每次写出相似代码”，而是稳定 ID、不可变快照、确定顺序、结构化诊断、独立 Lowered IR 和纯渲染 Generator 共同保证的属性。

## 原则 5：局部修改必须绑定稳定身份

当前 Change planning 使用稳定身份计算 scope、impact 和 closure；实际 Apply 必须重新验证 candidate、baseline、输出和物理文件身份。后续扩展仍应只修改已证明属于计划的 Artifact，不得按名称模糊匹配或整份重生成后无条件覆盖工程。

## 非协商条款

- 不绕过 Software IR 让模型直接决定最终 Java 工程结构。
- 不把任意 Java 源码作为常规 SIR 逃生口。
- 不让 Target 框架语义污染 Core Semantic IR。
- 不用未经测试的性能、规模或准确率数字作事实宣传；只能明确标记为目标或假设。
