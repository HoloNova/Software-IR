---
created: 2026-07-16
updated: 2026-08-11
importance: high
confidence: confirmed
source: current_code_and_tests
status: active
---

# 开发进展

## 当前阶段

九模块 v0.1 工程已建立 Git `main` 基线并跟踪远端。当前工作重点是补齐可执行资格证据，而不是扩展新的产品方向。

## 已实现

- Parser、Semantic、typed reference-site、Lowering、Generator 主闭环。
- Project Graph build/validate/canonical serialization/load 主实现。
- Change IR v0.1-v0.6 planning。
- Application 编译/生成/Graph、Bundle、CURRENT、UPDATE/CREATE/DELETE transaction 和显式恢复。
- 路径、Windows 名称、symlink/junction/reparse point、冲突、同卷发布和结构化失败边界。
- CLI `context` / `plan`。
- Accepted ADR-020 冻结 B0/B1 事务方向。

## 当前资格

- 标准离线 Reactor：最近运行通过；精确数字见 `docs/qualification/CURRENT_QUALIFICATION.md`。
- conformance：POM 硬排除，`NOT_RUN/BLOCKED`。
- 完整 CLI 生命周期：未发布，`NOT_RUN`。
- Generator：已建立 canonical 输出契约基线，后续语义/环境/编译资格仍不足；Project Graph：直接测试不足。

## Git

- 当前分支：`main`。
- 远端：`git@github.com:HoloNova/Software-IR.git`。
- 受保护本地目录不进入版本控制。

完整状态见 `docs/PROJECT_STATUS.md`。
