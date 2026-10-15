# KCG-Code 文档导航

## 先读这些

1. [`../AGENTS.md`](../AGENTS.md)：工程规则与测试资源约束。
2. [`../MAIN.md`](../MAIN.md)：项目主体说明、权威层级、不可破坏的架构规则。
3. [`PROJECT_STATUS.md`](PROJECT_STATUS.md)：现在能做什么、验证到什么程度、已知限制。
4. [`qualification/README.md`](qualification/README.md)：怎么验证、证据口径、当前证据。

## 现行文档

| 文档 | 回答什么问题 |
| --- | --- |
| [`KCG-Code_系统架构与实现指南.md`](KCG-Code_%E7%B3%BB%E7%BB%9F%E6%9E%B6%E6%9E%84%E4%B8%8E%E5%AE%9E%E7%8E%B0%E6%8C%87%E5%8D%97.md) | 不看代码也能理解：每一层为什么存在、版本怎么保存 |
| [`PROJECT_OWNER_GUIDE.md`](PROJECT_OWNER_GUIDE.md) | 负责人怎么交办任务、验收、授权 Git 操作 |
| [`SIR_BATCH_CHECK.md`](SIR_BATCH_CHECK.md) | `kcg check` 的 JSONL 协议、预算与退出码 |
| [`CLI使用说明`](CLI_WORKFLOW.md) | 首次生成、只读结构查询、指令JSON、候选编辑与版本应用 |
| [`CLI阶段报告`](CLI_WORKFLOW_REPORT.md) | 实现、定向证据、CI 证据与单文件包 |
| [`入口阶段方案`](design/09-public-cli-workflow.md) | 已验收（CI #72 通过）；范围及完成门 |
| [`机制与语义的边界（草稿）`](design/10-semantic-boundary-and-mechanisms.md) | 双 Token 这类机制怎样拆到语义层；探针读数、S2 各问答案与实现依赖顺序 |
| [`CLAUDE_EXECUTION_POLICY.md`](CLAUDE_EXECUTION_POLICY.md) | 执行者是 Claude 时的提交、测试与资源约定 |
| [`architecture/`](architecture/) | Accepted ADR：Resolve-once（001）、Application 所有权（003）、项目文档与源快照（021）、批量校验（025）、分层与指令修改（026）、Git 版本历史（027） |
| [`design/`](design/README.md) | 目标产品设计（课程后端、数据库演进、Docker 交付、Web 平台）；不证明已实现 |
| [`qualification/`](qualification/) | 验证说明、SLM 评测总设计 |

## 历史材料

[`history/`](history/README.md) 保存已完成或退役的工作单、迁移计划与报告、旧 ADR、日期化 spec、旧资格报告和早期上下文。内容原样保留并标了状态，用于追溯“为什么当时这样做”，不描述当前实现。

## 使用规则

- “应该做成什么”：看 `design/`。
- “现在已经能做什么”：看代码与可执行测试，以及 `PROJECT_STATUS.md`。
- “为什么这样决定”：看 `architecture/`；更早的决定看 `history/`。
- 有冲突时按 `MAIN.md` 第 0 节的权威层级裁决；历史文档中的“当前”“下一步”和测试数字只代表写作当时。
