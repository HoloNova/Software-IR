---
created: 2026-07-16
updated: 2026-08-11
importance: critical
confidence: confirmed
source: current_project
status: active
---

# 项目身份

## WHAT

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。

## WHY

SIR 作为 Agent 与真实工程之间的严格契约和公共黑板，统一表达依赖、类型、符号、作用域、约束和变更目标。Agent 可以提出意图，确定性程序负责验证、Lowering、生成、影响分析和受保护写入。

## 目标用户

- 需要让 Coding Agent 在明确约束下生成或修改工程的开发者。
- 生成或修改 SIR 的 SLM/Agent。
- 维护 Target Profile、Constraint Pack、Lowering、Generator 和 Change contract 的工程开发者。

## 当前产品边界

- 已实现：Parser、Semantic、typed reference-site、Spring Boot Lowering、Generator、Project Graph、Change IR v0.1-v0.6、Application transactions、只读 CLI planning。
- 未发布：CLI 完整写生命周期、完整外部 conformance、独立发行包。
- 未来方向：Redis/其他 Extension、第二 Target、Constraint VM、Java 反向解析和更强增量能力。

当前状态与资格分别见 `docs/PROJECT_STATUS.md` 和 `docs/qualification/CURRENT_QUALIFICATION.md`。
