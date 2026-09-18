# KCG-Code 项目负责人操作与交接手册

> 这份文档是写给项目负责人的。你不需要记住以前和哪个 Agent 聊过什么，也不需要依赖任何 Agent 的私人记忆。只要仓库和这些文档还在，就能继续往下做。

## 先说最简单的用法

以后你准备继续项目时，只做四件事：

1. 打开 [`roadmap/ACTIVE_WORK.md`](roadmap/ACTIVE_WORK.md)，看当前唯一工作单。
2. 新开对话就使用下面的“新对话/新 Agent 开工提示词”；同一对话继续就使用“同一对话续作提示词”。
3. 等它按工作单完成后，用本手册的验收清单检查。
4. 验收通过后把旧工作单移入 `roadmap/completed/`，立即用下一张 `READY` 工作单替换 `ACTIVE_WORK.md`；随后按你的授权提交 Git。

不要一次把整个路线图交给一个 Agent。路线图是排队表，`ACTIVE_WORK.md` 才是本轮授权执行的任务。

## 这几个文件分别管什么

| 文件 | 你什么时候看 | 它负责回答什么 |
|---|---|---|
| [`README.md`](README.md) | 不知道该看哪份文档时 | 全部文档的导航入口和两类问题（该做什么 / 已实现什么）的分工 |
| [`PROJECT_OWNER_GUIDE.md`](PROJECT_OWNER_GUIDE.md) | 不知道怎么继续时 | 你该怎么派活、验收和换阶段 |
| [`roadmap/ACTIVE_WORK.md`](roadmap/ACTIVE_WORK.md) | 每次开工前 | 这一次只做什么、做到什么算完成 |
| [`roadmap/completed/`](roadmap/completed/) | 需要追溯已经完成的工作时 | 已验收工作单及其实际证据；不参与当前任务裁决 |
| [`PROJECT_STATUS.md`](PROJECT_STATUS.md) | 想知道项目现在能做什么时 | 当前真实能力和未发布边界 |
| [`qualification/CURRENT_QUALIFICATION.md`](qualification/CURRENT_QUALIFICATION.md) | 想知道哪些结论真的跑过时 | 测试、skip、exclude、BLOCKED 和 NOT_RUN |
| [`roadmap/REMAINING_WORK.md`](roadmap/REMAINING_WORK.md) | 想看完整后续顺序时 | 长期阶段顺序和各阶段完成门 |
| [`roadmap/README.md`](roadmap/README.md) | 以主设计控制后续方向时 | G0–G7 阶段入口和交给 Agent 的 Prompt |
| [`design/`](design/) | 需要确认目标设计时 | 主设计的目标契约和实现边界，不替代当前工作单 |
| [`../AGENTS.md`](../AGENTS.md) | 任何人准备改代码前 | 不可破坏规则、权威层级和统一验收命令 |
| [`architecture/`](architecture/) | 工作单点名某个 ADR 时 | 已冻结的架构决定和理由 |

这套分工很重要：

- 当前能力只看 `PROJECT_STATUS.md`。
- 当前验证结果只看 `CURRENT_QUALIFICATION.md`。
- 当前唯一任务只看 `ACTIVE_WORK.md`。
- 已完成证据只在 `roadmap/completed/` 追溯，不得继续留在 `ACTIVE_WORK.md`。
- 长期顺序只看 `REMAINING_WORK.md`。
- 主设计的 G 阶段只负责方向和阶段完成门；实际执行仍以 `ACTIVE_WORK.md` 为准。
- 不要从旧聊天、日期化报告或某个 Agent 的口头总结恢复“当前状态”。

## 阶段总览与派发方式

主设计把工作分成 **G0–G7 共八个阶段**，方向与完成门见 [`roadmap/README.md`](roadmap/README.md)。派发前先记住一条：**派发的单位永远是一张 Q 工作单，不是一整个 G 阶段。**

- G 阶段回答“这一步要证明什么、什么时候算完成”。
- Q 工作单回答“这一次只改什么、跑什么命令、交出什么证据”。
- 一个 G 阶段通常拆成多张 Q 工作单，按 [`roadmap/REMAINING_WORK.md`](roadmap/REMAINING_WORK.md) 排队。

### 八个阶段

| 阶段 | 一句话目标 | 进入条件 | 完成时你应该能看到的证据 | 当前状态 |
|---|---|---|---|---|
| G0 | 收口现有单文件编译、Lowering、Generator、Project Graph、Change 与文件事务的资格 | 现在即可执行 | 标准 clean 构建、生成工程编译、边界与确定性测试、文件事务恢复证据 | 当前，Q1 待复核 |
| G1 | 单文件课程业务切片：先一个实体 + 一个查询跑通 | G0 关闭 | 真实 MySQL 与 HTTP 下的 BIZ-01..06 及反例 | 未开始 |
| G2 | 持久身份与受控多文件模块 | G1 关闭 | LANG-01..05：改名、移动、重复 ID、引用闭包、旧 Bundle 兼容 | 未开始 |
| G3 | 数据库生命周期：INITIALIZE/UPDATE、历史、漂移、恢复 | G2 关闭 | DB-01..11；连续两次更新保留数据；DDL 中断可判定 | 未开始 |
| G4 | 完整源码包与 Docker 独立交付 | G3 关闭 | GEN-03/04、DEP-01..08；仅有 Docker 的机器能构建并部署 | 未开始 |
| G5 | 完整课程业务：登录、角色、归属、选课、名额、事务 | G4 关闭 | BIZ-07..13；真实并发不超额、失败回滚 | 未开始 |
| G6 | 网页平台与可靠发布 | G5 关闭 | WEB/JOB/PUB 场景；网页到本地部署闭环 | 未开始 |
| G7 | 换第二道题验证工具链 | G6 关闭 | 第二题首次生成 + 两轮修改 + 数据保留 + 人工介入记录 | 未开始 |

注意两套编号容易混淆：设计里的 **G0–G7** 是产品阶段；`REMAINING_WORK.md` 里的**阶段 1–6** 全部属于 G0 内部，阶段 7（最终资格）就是 G0 的关闭动作。G1 之后就属于新的产品能力，不再沿用那张清单。

### G0 内部的工作单序列（建议顺序）

下表第 2–6 项是本文给出的排队建议，仓库里还没有对应的已提交工作单；每张工作单派发前都要按当时证据重写，编号和边界都可以调整。

| 顺序 | Q 工作单 | 范围 | 现状 |
|---|---|---|---|
| 1 | Q1 Generator 系统测试总验收（Q1A–Q1D 已完成） | 生产 Generator 边界闸门 + Q1 汇总 | `SPEC_REVIEW`，等复核 |
| 2 | Q2 Project Graph 直接测试 | Graph 从 0 项直接测试到契约测试 | 未开始 |
| 3 | Q3 Change fixtures 与操作族测试 | 补齐 base/candidate SIR，消除 assumption skip | 未开始 |
| 4 | Q4 Application conformance harness | 46 个文件恢复 testCompile，外部运行显式 opt-in | 未开始 |
| 5 | Q5 事务故障矩阵 | UPDATE/CREATE/DELETE 全中断点 | 未开始 |
| 6 | Q6 CLI 产品边界 | 维持只读 context/plan，或按 ADR-019 发布 | 未开始 |

Q 编号沿用仓库既有习惯（已完成的 Q1A–Q1D 就是这种命名），G1 起继续往后编；若要换编号方案，先改 `ACTIVE_WORK.md` 再派发。

### 每次派发只做三个动作

1. **换工作单**：把验收通过的旧工作单移进 `roadmap/completed/`，写一张新的 `ACTIVE_WORK.md`（目标、允许修改范围、完成门、验证命令、报告位置）。
2. **改状态**：`READY` → `IN_PROGRESS`。
3. **贴提示词**：新对话用“新对话 / 新 Agent 开工提示词”，同一对话用“同一对话续作提示词”；两者都只授权 `ACTIVE_WORK.md` 里的那一张工作单。

[`roadmap/README.md`](roadmap/README.md) 里的 G 阶段 Prompt 不要单独当任务派出去：它是阶段背景，正确用法是和当前 Q 工作单一并给出，并写明“只做当前工作单”。

### 接到交付后先看四件事

1. 改动是否只落在工作单允许的范围；
2. 命令是否真的跑过，run/fail/error/skip/exclude 分别是多少；
3. 完成门是否逐条有对应证据；
4. 没有失败或待裁决事项时，是否只发了一句确认请求。

## 同一对话续作提示词

如果还是原来的对话，而且这个 Agent 已经读过项目规则，就不用让它把所有全局文档再读一遍。可以直接复制下面这段：

```text
继续当前 KCG-Code 对话，开始或恢复 docs/roadmap/ACTIVE_WORK.md 中的唯一工作单。

先重新读取最新 ACTIVE_WORK.md，并运行 git status --short。已经在本次连续对话中完整读取且此后未变化的 AGENTS.md、PROJECT_OWNER_GUIDE.md、PROJECT_STATUS.md 和 CURRENT_QUALIFICATION.md 不要机械重读。

先检查这些权威文档在上次读取后是否发生变化；只重读变化的文档，以及当前工作单新点名、尚未加载的 ADR、代码和测试。若对话发生过上下文压缩，可以依据现有摘要继续；摘要缺少必要事实或你无法确认上下文是否仍然可靠时，改走新对话的完整冷启动流程。

本轮仍然只执行 ACTIVE_WORK.md，保留已有工作区改动，按其中测试和验收门完成。按本手册的版本快照规则执行本地提交和大版本普通推送，不得 force-push 或改写历史。
```

这里的关键不是“同一个聊天窗口”这几个字，而是上下文是否完整、权威文件是否未变化。如果拿不准，就按冷启动处理。

## 新对话 / 新 Agent 开工提示词

新开对话、换 Agent，或者旧对话上下文已经不可靠时，下面这段可以原样复制。它不依赖旧对话：

```text
你现在接手 C:\Users\zdw00\Desktop\Software IR 中的 KCG-Code 项目。

不要假设你知道任何旧聊天、旧 Agent 记忆或历史结论。先按顺序完整读取：
1. AGENTS.md
2. docs/PROJECT_OWNER_GUIDE.md
3. docs/PROJECT_STATUS.md
4. docs/roadmap/ACTIVE_WORK.md
5. docs/qualification/CURRENT_QUALIFICATION.md
6. ACTIVE_WORK 点名的 ADR、代码和测试

本轮只执行 docs/roadmap/ACTIVE_WORK.md 这一张工作单，不要顺手进入下一阶段，也不要展开 Redis、第二 Target、Constraint VM、GUI 等远期方向。

开始修改前：
- 先运行 git status --short，保留已有工作区改动；禁止 reset、clean、restore、checkout 或 stash 覆盖别人的工作。
- 用当前代码和可执行测试核对工作单，不要把旧报告当成现状。
- 先简要复述本轮目标、范围、明确不做的内容和验收门。

执行时：
- 修改行为前先写最小失败测试，再修根因。
- 只处理工作单范围内的直接问题；需要扩大架构或权限时先停下来报告。
- skip、被 POM 排除和未运行的内容不能算通过。
- 不得删除或弱化测试来制造绿色结果。

完成时必须实际运行工作单里的定向测试，以及：
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short

把修改、命令、run/fail/error/skip/exclude、未运行内容和风险边界写入工作单及资格文档。如果没有失败、阻断或需要我裁决的方向，最后只简短说明已完成并请求我确认继续，不要重复粘贴长报告。按本手册的版本快照规则执行本地提交和大版本普通推送。
```

如果交给的是人类开发者，也让他按同样顺序读文件即可。上面的限制并不是只给 AI 用的，它实际上就是项目的开发流程。

## 你怎么验收一张工作单

不要只问“做完了吗”。按下面顺序看：

### 1. 先看范围有没有跑偏

- 修改是否只落在 `ACTIVE_WORK.md` 允许的模块和目标里？
- 有没有顺手加入新的产品方向、依赖或重构？
- 有没有碰 `.claude/`、`.trae/`、`.codex/`、`.agents/` 等受保护内容？
- 有没有用 reset、restore、clean 或批量格式化覆盖已有工作？

出现范围外修改时，先不要合并。让执行者解释为什么必须改；不能证明是本工作单的直接依赖，就拆成以后单独的工作单。

### 2. 再看证据，不看形容词

一个合格交付至少要告诉你：

- 新增或修改了哪些测试；
- 哪条命令真的执行了；
- run、failure、error、skip 分别是多少；
- 哪些测试被 POM 排除；
- 外部 MySQL、第二卷或其他环境测试有没有真正运行；
- 如果没运行，是 `SKIPPED`、`BLOCKED` 还是 `NOT_RUN`。

“应该可以”“理论上没问题”“大部分通过”都不能当验收结论。

### 3. 看完成门是否逐条闭合

`ACTIVE_WORK.md` 有明确完成门。每一条都要有代码、测试输出或文件链接对应。少一条，这张工作单就是未完成，不要因为 Agent 上下文快用完了就提前进入下一阶段。

### 4. 最后创建版本快照

每张小版本工作单验收后形成一个普通本地 Git 提交。提交前至少确认：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

如果全量构建受 Windows 沙箱权限影响，必须在允许访问工作区输出目录的环境中用同一命令复跑。只有权限外复跑成功，才能把第一次 `Access is denied` 归类为环境问题。

提交信息用一句话说明结果，例如：

```text
test(generator): establish canonical output contract baseline
```

Q1A、Q1B1、Q1B2 这类小版本只提交到本地，不推送。Q1、Q2 这类大版本全部完成并经你确认后，把累计本地提交以普通 push 推送到当前 `origin`。任何情况下都不自动 force-push、rebase 或改写历史。

你的“确认”“认可”“继续”同时表示：当前工作单验收通过、允许切换下一张工作单，并授权执行对应级别的快照动作。若工作区存在未验收、任务外或受保护内容，执行者必须先从快照范围中排除。

## 汇报默认保持简短

详细证据必须保存在 `ACTIVE_WORK.md`、completed 工作单和资格文档中，保证新 Agent 能独立接手；但不需要把同一份证据在聊天里再写一遍。

如果没有明显错误、环境阻断或需要你决定的架构/产品方向，执行者完成小版本后只需告诉你“已完成，等待确认继续”。只有以下情况才展开说明：

- 测试或构建失败；
- 需要扩大工作单范围；
- 出现两个会改变产品含义的可选方向；
- 本地提交或大版本推送失败；
- 发现未验收、任务外或受保护改动无法安全划分。

## 一张工作单的正常生命周期

`ACTIVE_WORK.md` 只使用以下状态：

- `SPEC_REVIEW`：方案已经写清但尚未批准；等待项目负责人复核，复核通过前不得修改生产代码。
- `READY`：已经写清楚，可以派给执行者。
- `IN_PROGRESS`：有人正在做，其他人不要并行修改同一范围。
- `BLOCKED`：确实缺权限、环境、输入或架构裁决；必须写清阻断证据。
- `AWAITING_ACCEPTANCE`：实现者完成了，等你或复核者验收。
- `DONE`：完成门已经被实际证据闭合。

推荐操作：

1. 需要你先定方案的工作单停在第 `SPEC_REVIEW`；你说“方案可以”或“按这个做”之后才进入下一步。
2. 开工时把状态从 `READY`（或刚获批准的 `SPEC_REVIEW`）改为 `IN_PROGRESS`。
3. 执行者完成后改为 `AWAITING_ACCEPTANCE`，同时填写证据。
4. 你明确说“确认”“认可”“可以继续”或指出本轮已经完成，就视为验收；如果你提出缺陷，则保持原工作单继续修正。
5. 验收后把状态改为 `DONE`，移入 `roadmap/completed/`。
6. 根据 `REMAINING_WORK.md` 立即写下一张更小的 `READY` 工作单，成为新的 `ACTIVE_WORK.md`。
7. 小版本在本次确认后创建本地提交；大版本在全部子项完成并确认后普通推送 `origin`。用户确认已经是这两类对应动作的明确授权。

任何时候都只能有一个 `ACTIVE_WORK.md`。已验收的工作不能继续占着活动入口，completed 目录里的文件也不能被当成当前授权。

## 如果中途换 Agent

先让原执行者在 `ACTIVE_WORK.md` 的“交接记录”里写四件事：

1. 已经修改的文件；
2. 已经运行的命令和结果；
3. 当前第一个失败点；
4. 下一步建议做什么，以及哪些猜测尚未验证。

新 Agent 仍然从本手册的标准开工提示词开始。不要只把旧 Agent 最后一段聊天复制给它，因为聊天里经常缺少代码版本、测试状态和未提交修改。

## 如果 Agent 说文档互相冲突

按这个顺序处理：

1. 当前用户指令和 `AGENTS.md` 决定授权与不可破坏规则。
2. Accepted ADR 决定冻结契约。
3. 当前生产代码和实际执行的测试说明现在真实发生什么。
4. `PROJECT_STATUS.md` 和 `CURRENT_QUALIFICATION.md` 负责更新当前结论。
5. 日期化 spec、旧报告、`.memory/LOG` 和聊天只做背景材料。

如果 Accepted ADR 与代码或可执行测试发生冲突，先停下普通开发，单独建立“契约裁决”工作单。不要让 Agent 私下选一个自己喜欢的版本继续做。

## 什么时候可以进入完整 CLI 或新功能

现在不要直接跳到完整 CLI、Redis 或其他新方向。先按路线图完成基础资格工作：

1. Generator 系统测试；
2. Project Graph 直接测试；
3. Change fixtures；
4. conformance harness；
5. 事务故障矩阵；
6. 再裁决 CLI 完整生命周期。

当前阶段内部也要继续拆小。例如 Generator 阶段会分成输出契约、renderer 行为、确定性矩阵和生成工程离线编译，而不是一次全部交给一个 Agent。

上面这六项对应 G0 内部的阶段 1–6（见上一章“阶段总览与派发方式”）；G0 之后的 G1–G7 产品方向见 [`roadmap/README.md`](roadmap/README.md)，不经 G0 完成门不展开。

## 你真正需要记住的一句话

不知道下一步做什么时，不要问旧 Agent 的记忆；打开 [`roadmap/ACTIVE_WORK.md`](roadmap/ACTIVE_WORK.md)，把其中唯一任务按本手册交出去。
