# KCG-Code 当前项目状态

> 更新：2026-10-14。状态：正常开发中的工程，不宣称生产就绪。本文说明**现在能做什么**；验证口径与证据在[验证说明](qualification/README.md)；已完成或退役的材料在 [history](history/README.md)。

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
| `sir-toolchain-application` | 编译、首次生成、基于 Git 的版本规划与应用、独立验证、静态校验与 JSONL 批量 |
| `kcg-cli` | `check`、只读 `version-plan`、`version-apply`，canonical JSON 输出 |

## 3. 当前能力

### 语言与编译

- 只有一种文档形式：`sir 0.2` 根文档（`metadata`、`target`、`sources { }`、`imports { }`、`declarations { }`）加可选的声明片段文件。一个软件写在一个文件里，就是没有片段的根文档。最多 128 个文件、每个 1 MiB、合计 8 MiB。
- 编译阶段：Parser → Resolve（名称只解析一次）→ Type → Validate → Normalize → Spring Boot 降级 → 确定性生成。名称解析出错后分析仍会继续，类型与校验错误一并报告（只有身份冲突会提前停止）。
- 声明按（种类，名称）排序，生成的文件顺序因此是规范的，同一输入总得到逐字节相同的输出。
- 业务语言已覆盖：查询切片（`view` 响应投影、`Page<T>` 分页、显式 `order by`、字面匹配 `containsLiteral`）、写入切片（`versioned` 并发字段、声明式错误状态码、`persist … else` 条件持久化、`input … patch of` 局部更新、`.present` 存在性、结构化错误信封）、关系切片（关联存在性过滤与批量关联读取）。这些在真实 MySQL 8.4 + HTTP 的场景 IT 中验证过（见验证说明）。产品侧 DELETE、路由模板仍未实现。

### 首次生成

`ToolchainApplication.execute(sourceRoot, entry, outputRoot)`：读入源码（NOFOLLOW、有界）、编译、构建来源图，写入一个**尚不存在**的输出根——先写同卷暂存目录，再一次原子移动；任何失败都清掉暂存目录和本次创建的父目录。输出根已存在时拒绝，改动已有工程是版本变更的事。

### 增删改指令编辑

`SourceEditor` 把 `CommandBatch`（增加／删除／更新三种固定指令）应用到类型化草稿，只重写变化的声明、写回 SIR 文本，并用正式分析复核。保持强类型模型，不为新增功能补专用操作。

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

- 最近核验的 CI：`verify` #58（`07f29f6`）通过。之后的统一清理提交尚未经 CI，待负责人通知后记录。
- 清理后工作树的本机定向单元测试共 830 项、0 失败（各模块数字见[验证说明](qualification/README.md)）；不含需要 MySQL 的 IT，也不是全量闸门。
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

- 命令行 `generate`、`register`、`apply`、`recover` 完整生命周期；独立发行包（fat JAR）。
- 数据库初始化与迁移、Docker／Compose 交付、Web 平台、模块市场、跨项目缓存。
- Redis Extension、第二个目标、Constraint VM、Java 反向解析、GUI/daemon、多用户。
- SLM 评测：总设计见 [SLM 评测计划](qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)，尚未启动。
- Windows 平台证据；生产、安全认证、性能、高可用资格均未声明。

## 7. 文档分工

- 本文件：当前能力、验证状态、限制。
- [验证说明](qualification/README.md)：验证方式、证据口径、当前证据。
- [`architecture/`](architecture/)：Accepted ADR，冻结的架构决定。
- [`design/`](design/)：目标产品设计，不证明已实现。
- [`history/`](history/README.md)：已完成或退役的计划、报告、旧上下文，只用于追溯。
