# KCG-Code 剩余工作路线图

> 更新日期：2026-08-11
> 读者：项目负责人和后续执行 Agent

## 先说结论

项目主干已经具备九模块 Maven 工程、主要生产实现和可重复的默认离线构建。剩余工作是把当前实现补成能够被直接证明、长期维护和安全发布的工程。

不要一次把全部任务交给一个 Agent。每个阶段都应独立实现、测试、复核和提交。

本文件只负责长期顺序。当前真正允许执行的唯一小任务见 [`ACTIVE_WORK.md`](ACTIVE_WORK.md)；项目负责人如何派活和验收见 [`../PROJECT_OWNER_GUIDE.md`](../PROJECT_OWNER_GUIDE.md)。

版本快照规则：Q1A、Q1B1 等小版本经确认后只创建本地 Git commit；Q1、Q2 等大版本全部闭合并经确认后，把累计快照普通推送到 `origin`。无阻断或待裁决方向时，聊天中只请求确认继续，详细证据留在工作单和资格文档。

## 已完成的治理工作

- 建立 `AGENTS.md`、Accepted ADR、当前状态、资格报告和 `.memory` 的明确权威分工。
- 冻结 `CURRENT=B0` 向后补偿、`CURRENT=B1` 向前验证/清理的唯一方向。
- 完成当前状态、资格、覆盖清单和路线图的单一入口收敛。
- 当前 Git `main` 已连接远端并拥有可回退基线。

## 阶段 1：Generator 系统测试

### 当前拆分与进度

- [x] Q1A：完整、有序输出文件契约与 ownership metadata（见 [`completed/Q1A-generator-output-contract.md`](completed/Q1A-generator-output-contract.md)）。
- [x] Q1B1：POM、Application、Enum、Mapper、Exception 内容契约（见 [`completed/Q1B1-generator-simple-artifact-contract.md`](completed/Q1B1-generator-simple-artifact-contract.md)）。
- [x] Q1B2：Entity 与 DTO 类型/验证约束契约（见 [`completed/Q1B2-generator-entity-dto-contract.md`](completed/Q1B2-generator-entity-dto-contract.md)）。
- [ ] Q1B3A：Service 与 Workflow 行为契约。
- [ ] Q1B3B：Controller、actor identity transport 与 response 行为契约。
- [ ] Q1C：Locale、工作目录、换行、转义和 UTF-8 字节确定性矩阵。
- [ ] Q1D：完整生成工程冻结依赖下的真实离线编译。

### 目标

证明 Generator 能稳定、完整地把 `SpringBootLoweredModel` 渲染成预期工程，而不是只证明几个辅助类能工作。

### 任务

1. 为 POM、Application、Enum、Entity、Mapper、DTO、Exception、Service、Controller 建立直接测试。
2. 覆盖 Unit response、actor identity、复合 Find、Create/Update/Persist。
3. 验证相同输入重复运行、不同 Locale 和不同工作目录下路径、顺序和 UTF-8 字节一致。
4. 验证 Generator 不读取 AST、SIR、SymbolTable 或磁盘。
5. 建立一个完整 campus-market canonical fixture，并验证生成工程在冻结依赖下离线编译。

### 完成门

- 所有主要 renderer 都有行为证据。
- 完整文件集合和字节确定性通过。
- 至少一个生成工程真实离线编译。

## 阶段 2：Project Graph 直接测试

### 目标

让 Project Graph 从“Application 会间接经过”变成拥有独立、可维护的契约测试。

### 任务

1. 直接测试 DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE。
2. 覆盖稳定 ID、顺序、重复、悬空边、不可变集合和确定性。
3. 覆盖 canonical encode/decode/load/digest。
4. 验证 Graph 不访问文件系统、不重新按名称解析。
5. 增加 Parser → Semantic → Lowering → Generator → Graph 集成链。

### 完成门

- Graph 模块不再是 0 项直接测试。
- canonical document 与 digest 能跨重复运行稳定。
- Graph failure 在 Application 写盘前终止。

## 阶段 3：Change fixtures 与操作族测试

### 目标

补齐当前 Change planner、Application 和 CLI 共同依赖的 base/candidate SIR 集合，消除 assumption skip。

### 任务

1. 按 Change IR v0.1-v0.6 为每个操作建立最小 base/candidate 对。
2. 覆盖 workflow 修改、添加/删除 capability、字段约束、未引用字段类型和 actorless readonly exposure。
3. 覆盖版本、target、scope、impact、closure、冲突和 canonical 表达。
4. 恢复 `ChangePlanningApplicationTest` 当前跳过的 6 项。
5. 让 `KcgCliWorkflowTest` 不再整类跳过。

### 完成门

- 每个 fixture 的精确差异可审查。
- Application 和 CLI 不再因资源缺失跳过。
- Change v0.1-v0.6 都有成功与失败路径。

## 阶段 4：Application conformance harness

### 目标

使现有 conformance 包重新参加 testCompile，并重新建立安全、显式 opt-in 的外部 MySQL 资格入口。

### 任务

1. 根据当前契约重新完成 suite、scenario、fixture 和 evidence 编排。
2. 先让全部 46 个文件通过 testCompile。
3. 删除 POM 对整个 conformance 包的 compiler/Surefire 排除。
4. 默认 `clean verify` 不启动外部环境；外部运行必须显式 opt-in。
5. 保留凭据脱敏、证据目录 ownership、schema 清理、双 JDBC advisory lock、进程退出和 fail-closed。
6. 为 harness 自身的失败路径建立直接测试。

### 完成门

- 默认构建不再通过硬排除制造绿色。
- 无外部环境时为 `NOT_RUN`，不是 `QUALIFIED`。
- 显式外部运行的每个副作用都拥有 cleanup 和 sealed evidence。

## 阶段 5：事务故障矩阵

### 目标

为 UPDATE、CREATE、DELETE 建立完整、跨 CURRENT 线性化点的故障注入证据。

### 任务

1. 覆盖 prepare、backup/staging、文件提交、Bundle 发布、CURRENT 发布、Journal close 和 cleanup。
2. 每个中断点明确结果：向后补偿、向前完成或 `RECOVERY_REQUIRED`。
3. 证明 B0 永不向前猜测，B1 永不恢复旧字节。
4. 验证 hard link、NOFOLLOW_LINKS、junction/reparse point、same-volume 和物理身份。
5. 补第二卷/挂载点环境测试或明确记录 `NOT_RUN`。

### 完成门

- 三类事务都拥有相同方向语义和端到端故障矩阵。
- 模糊状态全部 fail closed。
- 无 copy/move fallback 绕过 DELETE hard-link 约束。

## 阶段 6：CLI 产品边界

### 推荐选择

在阶段 3 至 5 完成前，保持当前只读 `context` / `plan` CLI。前置条件闭合后，再评估接受并实现 ADR-019 的完整本地生命周期。

### 如果发布完整生命周期

- 六个命令拥有严格参数、help、退出码和 canonical JSON 测试。
- CLI 只调用 typed Application API。
- Apply 重新读取并绑定 fresh candidate/context/output evidence。
- Maven exec 可运行完整 generate → register → context/plan → apply → recover。
- thin JAR/发行包作为独立发布任务处理，不混入业务资格。

## 阶段 7：最终资格

完成前述阶段后执行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

并分别记录：

- 默认离线 Reactor；
- Windows 与其他平台条件；
- 外部 MySQL conformance；
- CLI 冒烟与完整生命周期；
- 跳过、排除、BLOCKED 和 NOT_RUN；
- 仍未覆盖的平台或发行方式。

## 每个 Agent 的证据记录格式

每个阶段只交付一个有边界的任务，并在工作单/资格文档中记录：

1. 修改文件和生产行为变化。
2. 新增或变更的契约。
3. 实际执行命令。
4. run/fail/error/skip/exclude。
5. 未完成和未运行内容。
6. 是否触及 Bundle、CURRENT、Journal、路径或外部凭据边界。

上述内容默认不在聊天里重复。没有失败、阻断或需要项目负责人裁决的方向时，只发简短的完成与确认请求。

## 暂不并行展开

- Redis Extension
- 第二 Target
- Constraint VM
- PSG `REFERENCES` 扩展
- Snapshot V2
- Change IR v0.7
- GUI、daemon、多用户
- Java 反向解析
- 新的增量编译系统

这些方向可以进入未来路线图，但不能与当前资格基础同时展开。
