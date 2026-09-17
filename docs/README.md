# KCG-Code 文档导航

这是项目文档的总入口。后续功能方向以主设计文档为主，历史文档保留用于追溯，不作为当前实现或当前工作授权的依据。

## 开始工作时优先阅读

1. [`../AGENTS.md`](../AGENTS.md)：项目规则、权威层级和不可破坏的架构边界。
2. [`roadmap/README.md`](roadmap/README.md)：面向项目负责人的阶段控制和 Agent Prompt。
3. [`design/README.md`](design/README.md)：主设计正文。
4. [`roadmap/ACTIVE_WORK.md`](roadmap/ACTIVE_WORK.md)：当前唯一允许执行的工作单。
5. [`design/implementation-baseline.md`](design/implementation-baseline.md)：当前源码与目标设计的实现对照。

## 当前有效文档

### 架构和治理

- [`architecture/`](architecture/)：已接受的 ADR。涉及架构边界时优先看这里。
- [`PROJECT_OWNER_GUIDE.md`](PROJECT_OWNER_GUIDE.md)：项目负责人交接、派工和验收规则。
- [`KCG-Code_系统架构与实现指南.md`](KCG-Code_系统架构与实现指南.md)：当前 v0.1 工程的架构说明。

### 当前状态和资格

- [`PROJECT_STATUS.md`](PROJECT_STATUS.md)：当前能力边界。
- [`qualification/CURRENT_QUALIFICATION.md`](qualification/CURRENT_QUALIFICATION.md)：当前实际运行证据和资格状态。
- [`qualification/TEST_COVERAGE_INVENTORY.md`](qualification/TEST_COVERAGE_INVENTORY.md)：测试覆盖与资格缺口。

### 当前执行路线

- [`roadmap/ACTIVE_WORK.md`](roadmap/ACTIVE_WORK.md)：当前唯一执行入口。
- [`roadmap/REMAINING_WORK.md`](roadmap/REMAINING_WORK.md)：长期路线和后续工作顺序。
- [`roadmap/README.md`](roadmap/README.md)：G0–G7 阶段控制和 Agent Prompt。
- [`roadmap/completed/`](roadmap/completed/)：已完成 Q 工作单的验收记录。

### 当前目标设计

- [`design/`](design/)：后续主要使用的目标设计、共享契约、实现基线和验证路线。
- [`design/implementation-baseline.md`](design/implementation-baseline.md)：判断“当前已经实现什么”的首选对照入口。

## 历史和背景材料

以下内容继续保留，但不再作为后续功能开发的主入口：

- [`design/2026-09-08-coursework-framework.md`](design/2026-09-08-coursework-framework.md)：主设计形成前的课程框架总览，保留用于背景追溯。
- [`superpowers/specs/`](superpowers/specs/)：之前各阶段执行前形成的设计、验证和实现记录，用于追溯当时的决策与证据。
- [`roadmap/completed/`](roadmap/completed/)：已经完成的 Q 系列工作单，用于追溯具体实现和验收过程。

这些文件不应被删除或随意改写。若历史内容与当前代码、Accepted ADR 或主设计不一致，以当前权威层级为准：用户当前指令、Accepted ADR、当前源码与测试、当前状态/资格文档、主设计，最后才是历史材料。

## 文档使用规则

- “应该实现什么”：看 `design/`。
- “现在允许做什么”：看 `roadmap/ACTIVE_WORK.md`。
- “现在已经实现什么”：看当前源码、实际测试和 `implementation-baseline.md`。
- “为什么过去这样做”：看 ADR、`roadmap/completed/` 和 `superpowers/specs/`。
- G0–G7 是主设计的产品/实现阶段；Q1、Q1A 等是仓库工作单，不能混用。
- 历史文档中的“当前”“下一步”和测试数字，只代表文档形成时的状态。

## 为什么保留历史路径

部分已完成工作单、ADR 和资格记录引用了旧 spec 的原路径。当前采用“导航归类而不搬动历史文件”的整理方式，避免破坏历史链接；后续新工作直接从本页和主设计入口开始。
