# 下一阶段方案：打通首次生成与指令编辑的公开入口

- 日期：2026-10-14。
- 状态：**AWAITING_ACCEPTANCE**。负责人已确认方案并授权整阶段实现（2026-10-14）；实现和定向验证完成，提交与推送未授权。
- 实现起点：云端 `main` / `ad64a39`。按负责人要求信任云端成果，不重复复核或运行已完成阶段的测试。
- 依据：[MAIN](../../MAIN.md)、[现状](../PROJECT_STATUS.md)、[ADR-026](../architecture/ADR-026-layered-semantic-command-architecture.md)、[ADR-027](../architecture/ADR-027-git-as-version-history.md)。

## 1. 这一阶段为什么存在

分层、通用增删改、源写回、Git版本应用及独立验证已完成，不重新实现R1–R5。现在的缺口是**已有核心能力还不能全部从公开入口使用**：

| 已有能力 | 当前入口 | 缺少什么 |
| --- | --- | --- |
| 首次生成完整工程 | `ToolchainApplication.execute(ToolchainRequest)` | CLI首次生成命令 |
| 指令式增加/删除/更新 | `CommandBatch`、`SourceEditor.edit(SourceSnapshot, CommandBatch)` | 外部指令协议、CLI与候选源目录输出 |
| 编辑所需的软件结构 | `SoftwareSchema`、`SoftwareDraft`、`Selector` | 不写Java即可获得基线摘要、可编辑结构与选择路径 |
| 静态校验、版本预览和应用 | `check`、`version-plan`、`version-apply` | 已有，沿用，不另建更新入口 |
| 构建、启动与业务验证 | 独立Application入口与执行端口 | 保持独立，本阶段不增加一键运行/业务判分平台 |

目标：人或模型拿到SIR和一份指令文件，就能生成、编辑、预览并应用，不再先编写Java调用程序。命令行是薄接口，不成为另一套编译器或修改引擎。

## 2. 用户完整流程

```text
准备sir 0.2源目录
  → generate：首次生成Spring Boot源码
  → inspect：读取软件结构及本轮编辑的基线摘要
  → 准备add/delete/update指令JSON
  → edit：生成独立候选SIR目录，不修改原SIR或工程
  → version-plan：比较当前与候选，查看文件变化和planId
  → version-apply：明确应用，原SIR与工程更新，Git保存前后版本
```

用户也可以手写候选SIR，跳过inspect/edit，继续使用现有version-plan/apply。两条输入路径共享同一编译与版本应用能力。

生成源码与下载依赖分开。本阶段命令不自动启动Maven、数据库或应用；真实构建/业务验收仍通过已有独立验证入口与CI执行。

## 3. 推荐入口及职责

以下入口已按本方案实现；本轮定向证据在第10节，CI和负责人验收仍单独记录。

| 命令 | 输入 | 输出及副作用 |
| --- | --- | --- |
| `generate --source-root … --entry … --output-root …` | 原SIR目录、相对入口、尚不存在的输出根 | 调用已有首次生成；只写新工程根，不Git提交、不联网、不改源 |
| `inspect --source-root … --entry …` | 当前SIR目录 | 只读JSON：指令协议、softwareName、完整源集合SHA-256、schema节点/槽位及当前可编辑结构；不写磁盘 |
| `edit --source-root … --entry … --candidate-root … < commands.json` | 当前源、stdin中的单个指令JSON对象、尚不存在的候选根 | 调用已有SourceEditor；成功后只保存完整候选源集合及编辑报告；不生成Java、不改当前版本、不Git提交 |
| `version-plan` / `version-apply` | 现有参数与候选目录 | 沿用当前合同与计划身份，不直接把edit结果冒充已批准的工程更新 |

路径沿用现有绝对、归一化根与相对SourceId约定。候选根不得与源根重叠，已存在时拒绝；候选落盘采用Application的暂存/发布职责，失败不留下半份可被误用的候选。CLI不得自行实现文件写入、名称绑定或Git操作。

`inspect`不是恢复旧context/专用操作目录：它只展示语义层已经定义的通用结构。当前选择器按槽位路径、集合元素名称或索引选取；基线绑定和批内顺序沿用现有代码，不重新宣称永久nodeKey或按相似性识别改名。

## 4. 外部指令协议

首轮采用**JSON文件经stdin输入**，不另造一门业务编辑语言；edit读取一个JSON对象，不复用check的JSONL批量样本协议。命令文件由调用方打开，CLI无需新增文件读取能力。三个动词固定为 `add`、`delete`、`update`；JSON只编解码现有类型，不识别课程、订单或某类工作流。

请求对应当前 `CommandBatch`，外层版本冻结为 `KCG-CLI-COMMANDS-V1`，新命令结果为 `KCG-CLI-WORKFLOW-V1`。原check/version协议不变。

预算冻结：一个UTF-8 JSON对象最多8 MiB；单字符串最多1 MiB UTF-8；JSON最多64层、100000个值；每批1–1000条指令；选择器最多64步，index/position为非负32位整数。候选继承源预算128文件、1 MiB/文件、合计8 MiB。exit 0成功、2用法/外部JSON错误、3编辑拒绝或Application/I/O失败、70意外内部错误；edit不使用version-apply的中断码4。

- `protocolVersion`：外层 `KCG-CLI-COMMANDS-V1`；
- `protocol`：现有 `sir-command/1`；
- `softwareName`、`baselineSha256`：由inspect取得，摘要绑定整个源集合，不只绑定入口文件；
- `commands`：非空、有序，整批成功或失败；
- 选择器：`steps`数组，每步有`slot`，可带`name`或`index`，二者互斥；
- add：`container`、`value`，可带`position`；delete：`target`，可带`expectedOld`；update：`target`、`value`，可带`expectedOld`。

值只映射现有四种 `StructValue`：

| JSON形状（拟定） | 核心类型 |
| --- | --- |
| `{ "type": "text", "value": "…" }` | `Text`，标量/引用采用现有canonical文本，不另推导 |
| `{ "type": "none" }` | `None`，只表示空可选值 |
| `{ "type": "child", "kind": "…", "slots": { … } }` | `Child`，节点及槽位合法性取自SoftwareSchema |
| `{ "type": "seq", "elements": [ … ] }` | `Seq`，保留序列含义，不任意排序 |

schema拥有槽位类别，调用方不能把派生或身份字段标成可编辑事实。不可凭JSON输入加载Java类；未知节点/槽位、缺失必需值或值形状错误须结构化拒绝。新增语义节点不应要求CLI编解码器补一份具体声明/表达式名单。

外层JSON协议与核心 `sir-command/1`分别版本化，报告明确两者；不能把新增外层字段悄悄当核心升级。重复键、未知字段、非法UTF-8、超大文件/批次/深度等在入口拒绝，预算按本节冻结。已有check与version命令的协议和退出码保持不变，新命令单独定义结果与退出码并写明副作用。

CLI暴露的是确定性的结构化指令，不是自然语言理解。自然语言→指令仍由人/模型完成；后续可优化编写体验，不在此阶段重新设计SIR。

## 5. Application与模块落点

| 位置 | 本阶段改动 | 不应增加的逻辑 |
| --- | --- | --- |
| `sir-semantic/schema` | 复用结构定义、草稿和值；必要时补通用只读访问API | 外部CLI参数、磁盘、Git |
| `sir-change/edit` | 复用Command、Selector、SourceEditor和原拒绝理由 | 业务专用指令、第二套合法性检查 |
| `sir-toolchain-application` | 增加只读inspect及编辑候选编排：受控读源、调用编辑、保存候选、返回typed报告 | 复制Resolve/Type/Validate、绕过原版本应用 |
| `kcg-cli` | 严格参数、JSON编解码、canonical输出；generate直接调用现有入口 | 自行读写工程、Git、Maven/应用进程管理 |

不新增Maven模块、不改九模块主结构，不恢复Bundle/CURRENT/日志。新增读取/候选发布应组合现有Application文件能力，但不能直接把“生成Java工程”的首次生成器用于SIR写出。

SourceEditor的现有限制原样呈现：只编辑声明；新增声明落入口文件；不编辑header/imports，不自动移动声明；BOM及会丢注释的重写会拒绝。外部接口应返回原理由，不用文本替换绕过限制。本阶段不把这些限制逐个扩成新功能。

## 6. 实施顺序与交付

负责人确认本方案后，可一次授权以下整个阶段，内部步骤由执行者完成，阶段结束统一汇报：

1. **冻结入口合同**：JSON样例、预算、schema输出、错误/退出码及副作用；说明inspect输出怎样直接用于构造三种命令。
2. **接入Application与CLI**：复用首次生成、增加只读结构查询和候选源编辑编排，保护现有check/version合同。
3. **完成用户流程**：用已有课程、商品fixture及一份多源fixture串起全过程，补最小使用说明与可复制命令。
4. **验证与报告**：本机只跑受影响定向测试；重活由CI承担，授权提交/推送后才绑定新源码证据。无Git授权时交付代码与定向结果，CI门记NOT_RUN，不擅自提交触发。

实施已由负责人明确授权，不预估未经实测的工期。

## 7. 验收要求

- 不写Java调用程序即可生成一个新工程；输出与同源调用已有ToolchainApplication的结果逐字节一致；输出根已有时不覆盖。
- 通过inspect取得准确源集合摘要和结构；单批新增输入/能力、删除声明、更新已有声明，只有三个通用动词。
- edit成功保存全部候选源，未改文件保持原字节；候选能独立重新解析，语义与指令结果一致。原源目录和生成工程始终未动。
- 课程与商品复用同一外部协议；多源声明编辑按既有限制工作，不为具体业务新增命令或codec分支。
- 候选可被现有version-plan读取，应用后源码/生成结果等于候选从零生成，Git范围仍只含相关源与受管文件。
- 陈旧基线、错误路径/结构、最终悬空引用、受保护注释、候选根已存在或写入失败分别得到明确结果；失败不修改原版本，不留下成功候选。
- 用CLI边界测试证明没有绕开Application调用文件系统/Git；无新增自动构建、自动启动、自动推送。
- 代码阶段的新测试数只从实际结果记录；沿用云端旧CI作为起点，不冒称它验证新增入口。

## 8. 不在本阶段做，以及之后的顺序

本阶段不做数据库初始化/迁移、Docker/Web、更多业务语法、并发锁/恢复体系重写、构建缓存、真实SLM样本评测。它是入口交付，不是再次做核心架构重构。

入口闭环之后优先评审独立可执行包和小规模模型实验接入；fat JAR目前未交付，先确认打包依赖与离线仓库再立项。数据库演进仍作为独立产品阶段，不因CLI可用就承诺任意字段变更可保留数据库数据。

## 9. 规划轮记录

按负责人2026-10-14指令完成云端main同步；旧本地目录及暂存/未跟踪内容完整保留在工作目录旁的备份目录，没有把旧代码合并进云端版本。仅新增本方案并补文档导航/下一阶段候选，不更改生产代码、不测试/复核云端、不提交或推送。该规划轮在等待确认时结束；随后负责人明确确认并授权实施，见第10节。

## 10. 实施完成记录（2026-10-14）

generate/inspect/edit与typed Application编排已实现；节点及槽位映射来自现有SoftwareSchema，沿用原SourceEditor的批顺序、基线、拒绝理由和语义回读。候选以源字节暂存/原子发布，不改原源或Java工程；原version-plan/apply合同未变。

定向验证7类最新XML合计29项、0失败/错误/跳过；真实CLI完成多源新增输入/能力、删除、更新、候选输出与Git应用，结果等于候选从零生成；商品复用同一协议。失败路径和CLI边界均有定向证据。详见[阶段报告](../CLI_WORKFLOW_REPORT.md)与[使用说明](../CLI_WORKFLOW.md)。

当前状态AWAITING_ACCEPTANCE；未提交/推送，新源码的全量CI与真实业务IT为NOT_RUN。云端旧CI只作起点，不扩大资格。不自动启动下个阶段。
