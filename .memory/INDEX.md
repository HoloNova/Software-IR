# 项目记忆索引

> **灾后首要入口（2026-08-10）：** 先读 [REBUILD_CONTEXT_2026-08-10.md](REBUILD_CONTEXT_2026-08-10.md)。该文件汇总磁盘损坏后的恢复来源、较新项目状态、关键安全不变量、历史提交元数据和重建顺序。下方旧记录可能停留在更早阶段或包含重复内容。

最后更新: 2026-07-16

## CORE — 项目核心

- [PROJECT.md](CORE/PROJECT.md) — 项目目标、定位与协作背景
- [PRINCIPLES.md](CORE/PRINCIPLES.md) — 长期设计原则
- [CONSTRAINTS.md](CORE/CONSTRAINTS.md) — 不可破坏的工程约束

## DESIGN — 设计文档

- [ARCHITECTURE.md](DESIGN/ARCHITECTURE.md) — 长期架构、当前管线与模块职责
- [DECISIONS.md](DESIGN/DECISIONS.md) — 已确认的关键技术决策
- [RESEARCH.md](DESIGN/RESEARCH.md) — 尚待研究的问题
- [EXPERIMENTS.md](DESIGN/EXPERIMENTS.md) — 已验证的构建与语义基线

- [STAGE_E_QUALIFICATION_LESSONS.md](DESIGN/STAGE_E_QUALIFICATION_LESSONS.md) — Stage E qualification completion gate, evidence rules, Windows notes, and review lessons

- [STAGE_E_QUALIFICATION_LESSONS.md](DESIGN/STAGE_E_QUALIFICATION_LESSONS.md) — Stage E qualification completion gate, evidence rules, Windows notes, and review lessons

## ACTIVE — 当前状态

- [TODO.md](ACTIVE/TODO.md) — 下一阶段待办与范围边界
- [DEVELOPMENT.md](ACTIVE/DEVELOPMENT.md) — 当前实现进度和 Git 基线
- [BUGS.md](ACTIVE/BUGS.md) — 当前缺陷与环境注意事项

## LOG — 对话记录

- [2026-07-16-sir-v0.1-stage-summary.md](LOG/CONVERSATIONS/2026-07-16-sir-v0.1-stage-summary.md) — 从架构讨论到第三轮验收的提炼记录

## ARCHIVE — 归档

- [README.md](ARCHIVE/README.md) — 归档规则；当前暂无归档条目

## 阶段状态（2026-07-30）

Stage E 已在声明的本地 MySQL reference-environment tuple 上完成并接受：默认 Reactor 通过，两个全新的 opt-in 外部资格运行均为 `QUALIFIED`。资格不泛化为生产或所有环境；Stage F 仅可进入单一最小目标的设计评估，尚未获准实施。

## 阶段状态（2026-07-30）

Stage E 已在声明的本地 MySQL reference-environment tuple 上完成并接受：默认 Reactor 通过，两个全新的 opt-in 外部资格运行均为 `QUALIFIED`。资格不泛化为生产或所有环境；Stage F 仅可进入单一最小目标的设计评估，尚未获准实施。

## 快速摘要

KCG-Code 是面向 Coding Agent 的 Software IR 编译与确定性代码生成工具链，不是普通 CRUD DSL，也不允许模型绕过 IR 直接生成 Java。当前已形成 `sir-parser -> sir-semantic -> sir-lowering-api -> sir-lowering-spring-boot` 的 v0.1 最小闭环，输出为可独立验证的 `SpringBootLoweredModel`，尚未生成文件。阶段测试为 parser 41 + semantic 78 + lowering-api 4 + spring-lowering 12 = 135 项通过。下一阶段应实现只读取 Lowered IR 的最小确定性 Generator；Redis、算法、前端联调和 Change SIR 暂不并行展开。
