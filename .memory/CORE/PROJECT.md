---
created: 2026-07-16
updated: 2026-07-18
importance: critical
confidence: confirmed
source: user_decision
status: active
---

# 项目身份

## 项目目标

KCG-Code 是面向 Coding Agent 的 Software IR 编译与确定性代码生成工具链。模型负责把需求整理成可检查的 SIR，工具链负责解析、语义验证、目标 Lowering 和确定性生成，使工程约束成为机器可执行事实。

项目不应被简化为普通 CRUD DSL、脚手架系统或“大模型直接生成 Java”。长期系统由 Software IR、Project Symbol Graph、Constraint VM、Extension、Target Profile、Constraint Pack、Lowered IR 和确定性 Generator 协同组成。

## 背景

项目发起者主要借助 Coding Agent 开发，希望用 SIR 作为 Agent 与真实工程之间的严格契约和“公共黑板”：统一记录依赖、类型、符号、变量、作用域和架构约束，减少同一概念被多种语义表达、跨步骤漂移或被模型任意改写的问题。

## 目标用户

- 需要让 Coding Agent 在明确约束下生成或修改工程的开发者；
- 后续生成或修改 SIR 的 SLM/Agent；
- 维护 Target Profile、Constraint Pack、Lowering 和 Generator 的工程开发者。

## 当前定位

项目处于 v0.1 工程闭环与 Project Symbol Graph v0.1 完成阶段。Parser、不可变 AST、符号解析、类型检查、验证、规范化、Target 无关 Lowering API、最小 Spring Boot Target Lowering、纯函数式确定性 Generator、安全工程应用层，以及从编译/生成产物构建的只读、不可变、确定性 PSG 均已完成。应用层在文件事务成功后发布 `ExecutionManifest` 与 `ProjectGraph`；Generator 仍不接触磁盘。可部署运行时、框架 Extension、Change SIR、Constraint VM 与 Java 反向解析尚未实现。

## 推荐下一任务

先设计并冻结 **typed reference-site 契约**：在现有 `referenceBindings` 之外，为每个引用点建立稳定的 reference-site `AstNodeId` 与引用角色，使未来 `REFERENCES` 边具有完整 provenance。该设计完成后，按依赖顺序评估 Graph 跨次 canonical 序列化/复用与最小 Change SIR；不同时实现 Java 反向解析、图数据库、写补丁、Redis、算法、前端或 Constraint VM。

## 协作方式

文档和 Agent 输出应使用清晰中文解释编译器概念，不假设项目发起者已有编译器或多语言工程经验；但不能因为易读性而牺牲语义严谨性或跨模块边界。
