# 项目记忆索引

> 最后更新：2026-08-11
> 作用：为新会话提供精炼上下文；当前状态仍以 `AGENTS.md` 和 `docs/PROJECT_STATUS.md` 为入口

项目可以在不加载本目录的情况下继续。面向项目负责人的独立路由是 `docs/PROJECT_OWNER_GUIDE.md`，当前唯一工作单是 `docs/roadmap/ACTIVE_WORK.md`。

## 首要入口

1. [PROJECT_CONTEXT.md](PROJECT_CONTEXT.md) — 项目身份、当前闭环、权威分工和工作边界
2. [CORE/PROJECT.md](CORE/PROJECT.md) — 产品目标与模块概览
3. [ACTIVE/DEVELOPMENT.md](ACTIVE/DEVELOPMENT.md) — 当前实现与资格状态
4. [ACTIVE/TODO.md](ACTIVE/TODO.md) — 当前优先工作
5. [ACTIVE/BUGS.md](ACTIVE/BUGS.md) — 已知缺口和环境注意事项

## CORE — 长期不变量

- [PROJECT.md](CORE/PROJECT.md) — WHAT / WHY
- [PRINCIPLES.md](CORE/PRINCIPLES.md) — 长期设计原则
- [CONSTRAINTS.md](CORE/CONSTRAINTS.md) — 不可破坏边界

## DESIGN — 架构与决策摘要

- [ARCHITECTURE.md](DESIGN/ARCHITECTURE.md) — 当前九模块架构
- [DECISIONS.md](DESIGN/DECISIONS.md) — 已接受关键决策摘要
- [RESEARCH.md](DESIGN/RESEARCH.md) — 待研究主题
- [EXPERIMENTS.md](DESIGN/EXPERIMENTS.md) — 日期化运行证据
- [STAGE_E_QUALIFICATION_LESSONS.md](DESIGN/STAGE_E_QUALIFICATION_LESSONS.md) — 外部资格方法论历史记录

## LOG / ARCHIVE

`LOG/` 和 `ARCHIVE/` 仅用于追溯，不默认加载，也不作为当前状态权威。

## 当前摘要

KCG-Code 已包含 Parser、Semantic、Lowering、Generator、Project Graph、Change、Application 和 CLI 九模块闭环。默认离线 Reactor 最近运行通过，精确数字见当前资格报告；conformance 包仍被 POM 排除，完整外部资格和 CLI 写生命周期均未完成。

当前唯一任务以 `docs/roadmap/ACTIVE_WORK.md` 为准；长期顺序见 `docs/roadmap/REMAINING_WORK.md`。
