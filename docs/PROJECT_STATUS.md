# KCG-Code 当前项目状态

> 更新：2026-10-15。状态：正常开发中的工程，不宣称生产就绪。本文说明**现在能做什么**；验证口径与证据在[验证说明](qualification/README.md)；已完成或退役的材料在 [history](history/README.md)。

## 1. 定位

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。Agent 或小模型负责提出可检查的 SIR；确定性程序负责解析、绑定、类型、约束、降级、生成、来源图、文件写入和变更计划。它不是普通 CRUD DSL，也不允许模型绕过 IR 直接决定最终 Java 工程结构。

## 2. 当前实现

父 Maven Reactor 含九个业务子模块：

| 模块 | 当前职责 |
| --- | --- |
| `sir-parser` | `sir 0.2` 根文档与声明片段两个入口、不可变 AST、SourceSpan、稳定 AstNodeId、解析诊断 |
| `sir-semantic` | Resolve/Type/Validate/Normalize、typed reference-site binding、Normalized model；`schema` 包为类型化软件结构 |
| `sir-lowering-api` | 目标无关的降级契约、来源、版本、诊断和 Validator |
| `sir-lowering-spring-boot` | Java 21/Spring Boot/MyBatis-Plus/MySQL/Maven/REST 降级模型与校验 |
| `sir-generator-spring-boot` | 只消费降级模型的确定性内存文件生成 |
| `sir-project-graph` | 来源图的构建、校验、canonical 序列化与加载 |
| `sir-change` | 增删改指令（`Command`/`CommandBatch`）的编辑与写回（`SourceEditor`） |
| `sir-toolchain-application` | 编译、首次生成、只读结构查询/候选源编辑、基于 Git 的版本规划与应用、独立验证、静态校验与 JSONL 批量 |
| `kcg-cli` | `generate`、只读 `inspect`、候选 `edit`、`check`、只读 `version-plan`、`version-apply`，canonical JSON 输出 |

## 3. 当前能力

### 语言与编译

- 只有一种文档形式：`sir 0.2` 根文档（`metadata`、`target`、`sources { }`、`imports { }`、`declarations { }`）加可选的声明片段文件。一个软件写在一个文件里，就是没有片段的根文档。最多 128 个文件、每个 1 MiB、合计 8 MiB。
- 编译阶段：Parser → Resolve（名称只解析一次）→ Type → Validate → Normalize → Spring Boot 降级 → 确定性生成。名称解析出错后分析仍会继续，类型与校验错误一并报告（只有身份冲突会提前停止）。
- 声明按（种类，名称）排序，生成的文件顺序因此是规范的，同一输入总得到逐字节相同的输出。
- 业务语言已覆盖：查询切片（`view` 响应投影、`Page<T>` 分页、显式 `order by`、字面匹配 `containsLiteral`）、唯一字段与按唯一字段加载（`unique`、`load … by 字段:`，CI [#76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131) 通过）、时间平移与数值加减（`now() + 15 minutes`、`a - 1`，CI [#77](https://github.com/HoloNova/Software-IR/actions/runs/38712747591) 通过）、带守卫的状态流转（`update … when … else …`，CI [#78](https://github.com/HoloNova/Software-IR/actions/runs/38715357765) 通过，含真实 MySQL 并发 IT）、写入切片（`versioned` 并发字段、声明式错误状态码、`persist … else` 条件持久化、`input … patch of` 局部更新、`.present` 存在性、结构化错误信封）、关系切片（关联存在性过滤与批量关联读取）。这些在真实 MySQL 8.4 + HTTP 的场景 IT 中验证过（见验证说明）。产品侧 DELETE、路由模板仍未实现。

### 首次生成

`ToolchainApplication.execute(sourceRoot, entry, outputRoot)`：读入源码（NOFOLLOW、有界）、编译、构建来源图，写入一个**尚不存在**的输出根——先写同卷暂存目录，再一次原子移动；任何失败都清掉暂存目录和本次创建的父目录。输出根已存在时拒绝，改动已有工程是版本变更的事。

### 增删改指令编辑

`SourceEditor` 把 `CommandBatch`（增加／删除／更新三种固定指令）应用到类型化草稿，只重写变化的声明、写回 SIR 文本，并用正式分析复核。保持强类型模型，不为新增功能补专用操作。

公开入口已验收（2026-10-15，提交 `1c80c73`，CI [verify #72](https://github.com/HoloNova/Software-IR/actions/runs/38671123332) 通过）：`generate`调用原首次生成，`inspect`只读schema/draft与源集合摘要，`edit`读取JSON增删改批次并保存完整候选源目录，再接原version-plan/apply。原源与生成工程在edit期间不变；CLI不直接访问文件系统、不构建/启动。详见[CLI使用说明](CLI_WORKFLOW.md)。

命令行可以打成单文件 `kcg-cli/target/kcg.jar` 直接 `java -jar` 运行（`mvn -B -pl kcg-cli -am -Dmaven.test.skip=true package`）。整条链运行时只依赖 antlr4-runtime；包只重新打包已有类，不新增模块。本机已验证它与类路径启动的完整流程输出一致；CI 冒烟步骤已通过（[verify #73](https://github.com/HoloNova/Software-IR/actions/runs/38677243253)，`3ebe5f1`），见[阶段报告第4节](CLI_WORKFLOW_REPORT.md)。

### 基于 Git 的版本变更（ADR-027）

- 规划（只读）：把当前 SIR 与候选 SIR 各完整编译一遍，要求磁盘上的**整个**生成工程等于当前 SIR 的编译结果，否则报告需要重新生成；给出新建／更新／删除的文件、变化的源文件和 `planId`。
- 应用：需要 `git`。顺序是保存当前版本（第一次快照）→ 写生成文件 → 核对 → 写 SIR 源码 → 回读核对 → 保存新版本（第二次快照）。快照只含涉及的 SIR 与生成文件，不推送。
- 可重跑：中途停下不自动恢复；磁盘文件已等于候选版本的字节就视为已应用，重跑同一应用即可完成；也可用 Git 回到第一次快照。
- 命令行：`version-plan`（只读）、`version-apply`（唯一会改工程的命令，按 `planId` 应用，中途停下退出码 4）。

### 独立验证

静态／构建／启动／业务四级各自独立出结果，不互相替代；`VerifiedWorkspaceApplication` 在应用前要求证据通过（`VerificationGate`）。构建和应用进程通过端口接入（Maven 构建器、Spring Boot 应用宿主）。

### 批量静态校验

`kcg check < samples.jsonl` 与 `SirValidationApplication`：单 JVM 串行校验 JSONL 样本（每个是一个 `sir 0.2` 文档），可指定停在 PARSE／SEMANTIC／LOWERING／GENERATION，保留原始诊断，纯内存、不写文件。通过只说明所请求阶段通过，见 [SIR 批量校验](SIR_BATCH_CHECK.md)。

## 4. 验证状态

- 最近核验的 CI：`verify` [#88](https://github.com/HoloNova/Software-IR/actions/runs/38901782044)（`fe40836`，`kcg scaffold dual-token`，`main`）通过，日志确认双 Token 脚手架 IT 全部 PASS；此前 [#85](https://github.com/HoloNova/Software-IR/actions/runs/38897336941)（`70dc6cc`，Bearer 凭据认证，`main`）通过，日志确认新 IT 的认证场景全部 PASS；此前 [#82](https://github.com/HoloNova/Software-IR/actions/runs/38890915687)（`de563f6`，`secret` 字段与 `matches`，`main`）通过，日志确认注册／登录场景通过；此前 #81（401 状态码）、[#79](https://github.com/HoloNova/Software-IR/actions/runs/38884618786)（`6f244fa`，真实库核验的修复与 `TokenDeskBusinessConformanceIT`，`main`）通过，日志确认新 IT 运行并通过；此前 [#78](https://github.com/HoloNova/Software-IR/actions/runs/38715357765)（`aa1ad70`，守卫更新）、[#77](https://github.com/HoloNova/Software-IR/actions/runs/38712747591)（`1f947db`，时间平移与数值加减）通过：冻结与完成两条全量闸门、业务场景 IT、独立验证 IT、单文件包冒烟均 success，见[验证说明](qualification/README.md)。此前 [#76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131)（`058c9d6`）、[#73](https://github.com/HoloNova/Software-IR/actions/runs/38677243253)、[#72](https://github.com/HoloNova/Software-IR/actions/runs/38671123332) 同样通过。核对时只读了运行与步骤状态。
- 清理后工作树的本机定向单元测试共 830 项、0 失败（各模块数字见[验证说明](qualification/README.md)）；不含需要 MySQL 的 IT，也不是全量闸门。
- 公开CLI入口的本机定向验证（提交前）：29项、0失败/错误/跳过，来自7个类各自最新Surefire XML；新命令用真实CLI子进程及临时Git工程串起多源编辑/应用，结果与候选从零生成逐字节相同，详见[阶段报告](CLI_WORKFLOW_REPORT.md)。#72 的测试数字未在此抄录，需要时以该运行页面的报告和 artifact 为准。
- 单文件包：本机对比 `java -jar` 与类路径启动的完整流程，除 Git 提交 ID（含提交时间）外逐字节一致；CI 冒烟步骤在 #73 通过，见[阶段报告第4节](CLI_WORKFLOW_REPORT.md)。
- 被跳过、未运行的内容一律记为 `SKIPPED`／`NOT_RUN`，不折算成通过。

## 5. 已知限制

1. **生成的工程不允许手改。** 受管文件与当前 SIR 的编译结果不一致一律拒绝应用；工具链升级造成的差异同样处理（需要整体重新生成）。旧工具链多生成、新工具链不再生成的文件会残留，靠 `git status` 发现。
2. **没有跨进程锁。** 两个应用同时作用于一个工程由调用方避免；Git 自己的索引锁只保护快照步骤。
3. **同时改多个源文件时，停在移动之间可能留下新旧混合的源码。** 窗口很小；若混合出来的源码彼此不兼容，重跑会在读取或编译时失败，此时用 Git 回到第一次快照。
4. **Git 钩子照常生效。** 提交钩子拒绝时，保存当前版本失败则应用在改动前停止；保存新版本失败只给警告。
5. **SIR 目录与生成目录必须是仓库内互不包含的两个目录，且在同一文件系统**（原子移动需要）。
6. 编译与生成是完整的，不是局部增量编译。
7. 证据范围：只有受控样本，没有真实 SLM 输出集；进程树终止与日志脱敏有边界（见 [R5 报告](history/roadmap/R5-independent-verification-report.md) 第 4–5 节）。

## 6. 尚未作为产品能力发布

- 预构建的发行物与发布渠道、公开构建/运行入口（单文件包能由 `mvn package` 在本地产出，但没有版本化发布）；旧 `register`、`apply`、`recover` 命令不恢复，现有版本应用入口为version-apply。
- 数据库初始化与迁移、Docker／Compose 交付、Web 平台、模块市场、跨项目缓存。
- Redis Extension、第二个目标、Constraint VM、Java 反向解析、GUI/daemon、多用户。
- SLM 评测：总设计见 [SLM 评测计划](qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)，尚未启动。
- Windows 平台证据；生产、安全认证、性能、高可用资格均未声明。

## 7. 当前阶段（2026-10-15）

公开CLI流程已验收，不重开已完成R1–R5，不改变Git版本合同。下面的 S1/S2 是负责人 2026-10-15 确认的阶段划分。

- S1（已完成，`3ebe5f1`，CI #73 通过）：把 #72 的证据写入文档，命令行打成可直接运行的单文件包。
- S2（双Token扩展性探针，实现链全部完成）：用双 Token 作为对语言扩展性的探针。结论：缺口都是通用能力，逐个补上之后，双 Token 七条行为都能用语言本身写出，并在真实 MySQL 上验证——唯一字段与按唯一字段加载、时间平移与数值加减、守卫更新、错误状态码 401、`secret` 字段与 `matches`、`credential` 子句，最后是工具链里的脚手架 `kcg scaffold dual-token`（没有做成语言内的展开，理由见设计 10 第 27 节）。各步骤、实测改动面、CI 证据见[设计 10](design/10-semantic-boundary-and-mechanisms.md)第 16–27 节。第 5 问（小模型实验的接入）搁置：它依赖的 `STEP0_EXPERIMENT_HANDBOOK.md` 找不到，已改为起草一道提案题，见[SLM 第一道题提案](qualification/SLM_FIRST_TOPIC_DUAL_TOKEN_PROPOSAL.md)，负责人 2026-10-16 已采用这道题；模型与样本数未定，尚未评测任何模型。
- 实现链第 1 步（字段 `unique` 与按唯一字段 `load`）：负责人 2026-10-15 授权，提交 `058c9d6`，CI [#76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131) 通过。本机定向测试与 11 个现有夹具新旧生成逐字节相同见[设计 10 第 17 节](design/10-semantic-boundary-and-mechanisms.md)；真实 MySQL 业务场景未编写。
- 实现链第 7 步（`kcg scaffold dual-token`，脚手架）：提交 `fe40836`，CI [#88](https://github.com/HoloNova/Software-IR/actions/runs/38901782044) 通过，真实库 IT 逐条验证七条行为并含 6 路同时刷新的竞争（恰有一个成功）。详见[设计 10 第 27 节](design/10-semantic-boundary-and-mechanisms.md)与[CLI 说明第 5 节](CLI_WORKFLOW.md)。
- 实现链第 6 步（`credential` 子句：登录签发的令牌与 `requires authenticated` 连接）：提交 `55898e7`、`70dc6cc`，CI [#85](https://github.com/HoloNova/Software-IR/actions/runs/38897336941) 通过（#84 因 IT 自己的期望写错而失败，已修正，见设计 10 第 26.8 节）；真实库 IT 的 Bearer 认证场景通过。双 Token 七条行为至此在语言里都能写出，只剩把组合升格为内建修饰（展开）。详见[设计 10 第 26 节](design/10-semantic-boundary-and-mechanisms.md)。
- 实现链第 4 步（错误状态码 401）：提交 `e3b74e3`，CI [#81](https://github.com/HoloNova/Software-IR/actions/runs/38886724501) 通过。第 5 步（`secret` 字段与 `matches`，口令摘要）：提交 `de563f6`，CI [#82](https://github.com/HoloNova/Software-IR/actions/runs/38890915687) 通过，真实库 IT 的注册／登录场景通过。详见[设计 10 第 24、25 节](design/10-semantic-boundary-and-mechanisms.md)。
- 实现链第 3 步（守卫更新）：提交 `aa1ad70`，CI [#78](https://github.com/HoloNova/Software-IR/actions/runs/38715357765) 通过，真实 MySQL 并发 IT 通过。详见[设计 10 第 22 节](design/10-semantic-boundary-and-mechanisms.md)。
- 真实库核验（2026-10-16，提交 `6f244fa`，CI [#79](https://github.com/HoloNova/Software-IR/actions/runs/38884618786) 通过）：`Uuid` 主键在真实库里会 500，已修复；`Int64` 与整数字面量不能比较／加减，已修复；超出范围的整数字面量、`find` 里左右颠倒的比较原来要到生成或编译才失败，现在在静态阶段拒绝；守卫更新依赖驱动按“匹配行数”计数的默认值，是部署约定。详见[设计 10 第 23 节](design/10-semantic-boundary-and-mechanisms.md)。
- 实现链第 2 步（时间平移与数值加减）：提交 `1f947db`，CI [#77](https://github.com/HoloNova/Software-IR/actions/runs/38712747591) 通过；真实 MySQL 的有效期场景未编写。详见[设计 10 第 19 节](design/10-semantic-boundary-and-mechanisms.md)。

## 8. 文档分工

- 本文件：当前能力、验证状态、限制。
- [验证说明](qualification/README.md)：验证方式、证据口径、当前证据。
- [`architecture/`](architecture/)：Accepted ADR，冻结的架构决定。
- [`design/`](design/)：目标产品设计，不证明已实现。
- [`history/`](history/README.md)：已完成或退役的计划、报告、旧上下文，只用于追溯。
