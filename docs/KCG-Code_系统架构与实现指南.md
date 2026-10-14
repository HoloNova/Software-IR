# KCG-Code 系统架构与实现指南

> 更新：2026-10-14。本文讲**现有实现**：每一层为什么存在、一个软件怎么变成工程、修改怎么发生、版本怎么保存。目标产品设计见 [design](design/README.md)，架构决定见 [ADR](architecture/)。

## 1. 不看代码也能理解的工作流

首次：SIR 描述软件 → 工具理解并检查 → 生成 Spring Boot 项目源码 → 按需要拉依赖、构建、启动和验证。

后续：增加／删除／更新指令 → 修改已有软件草稿 → 写出新的 SIR → 同一套检查与生成 → 对照磁盘上的工程得到计划 → 应用 → 用 Git 保存两个版本 → 按需要构建、验证。

SIR 是软件描述，生成的 Java 是实现。修改指令不是 Java 补丁；依赖下载和运行验证不是 Parser 的一部分。

## 2. 六层分别做什么

| 层 | 用一句话解释 | 模块 |
| --- | --- | --- |
| 源与解析 | 看懂源文件结构，保留真实位置 | `sir-parser`（源读取与编排在 Application） |
| 软件语义 | 知道数据、功能、流程和约束，检查软件是否合法；提供唯一的类型化软件结构 | `sir-semantic` |
| 通用修改 | 只做增加、删除、更新，不理解课程／订单等业务 | `sir-change` |
| 目标实现 | 决定怎样用 Spring Boot、SQL 实现软件 | `sir-lowering-api` / `sir-lowering-spring-boot` |
| 文件渲染 | 根据实现方案输出源码和构建文件 | `sir-generator-spring-boot` |
| 工程应用 | 编排编译、写文件、比较、验证和版本发布 | `sir-toolchain-application` |

`sir-project-graph` 是生成物的来源地图（每个生成文件能追到哪个声明、哪个源文件），不是另一个理解业务的层。CLI 只处理参数和输出。分层不等于拆成微服务，也不意味着增加 Maven 模块。

## 3. 一个软件怎么由一个或多个文件组成

SIR 只有一种文档形式：`sir 0.2` 根文档，加可选的声明片段文件。根文档用 `sources { }` 显式列出成员文件，用 `imports { }` 声明本文件要用到的外部声明；各文件分别解析，导入限制当前文件能引用哪些外部声明。语义层收集全部声明，做**一次**正式名称解析，再做类型、校验和规范化。结果是一个 software，而不是每个文件一个工程，也不是文本粘接成一个假文件。

一个软件写在一个文件里，就是没有片段的根文档，走的是同一条路径（源快照里只有一个成员）。文件归属和 SourceSpan 一直保留，错误能指到实际文件。编译和生成是完整的，不是局部增量。

## 4. 修改怎么发生

```text
增删改指令 ──► 类型化软件草稿 ──► 写回 SIR 文本（只重写变化的声明）──► 正式分析复核
```

语义层提供唯一的软件结构定义（`SoftwareSchema`）：明确每个内容的身份、属性、子内容、引用和顺序。Change 通过它编辑和比较，只有 Add / Delete / Update 三种固定操作，不为每个新功能加专用操作。草稿与已验证模型分开：修改后写出新 SIR 再重新做正式分析取得新的绑定，不会拿一份规范化对象改完继续沿用旧引用。

新增普通业务主要组合已有能力；新增真正的语言能力时修改 Parser／Semantic／Target，Change 不需要跟着补投影。

## 5. 版本怎么保存（ADR-027）

系统不保存历史。**当前版本就是磁盘上的 SIR 源码，历史交给 Git。**

- **规划（只读）**：把当前 SIR 和候选 SIR 各完整编译一遍。要求磁盘上整个生成工程等于“当前 SIR 的编译结果”——受管文件缺失、被改、或工具链升级造成差异，都一律要求重新生成，系统不区分手改和升级。通过后比较两份结果得到新建／更新／删除的文件和变化的源文件，并给出 `planId`。
- **应用**：要求 `git`。先保存当前版本，再写生成文件（新建与更新、删除、空目录），核对整个生成工程，再写 SIR 源码（SIR 最后改，因为它定义“当前版本”），回读核对，最后保存新版本。快照只含涉及的文件，不推送。
- **断了就重跑**：每一步先看磁盘，已经是新内容就跳过，既不是旧内容也不是新内容就停下。系统不自动恢复；用户重跑同一应用，或用 Git 回到第一次快照。
- **首次生成**更简单：写入一个尚不存在的输出根，先写暂存目录再一次原子移动，失败就清理，不留半成品。

这样做的原因：自己保存版本、历史和事务日志，曾经要上万行代码、版本数量上限和“换代”机制，而版本管理本来就是 Git 的工作。

## 6. 文件、源、运行状态不能混为一件事

| 材料／结果 | 含义 |
| --- | --- |
| SIR 源快照 | 可重新打开的软件输入 |
| 已验证语义 | 工具从指定源理解出的软件事实 |
| Lowered IR | 目标实现的全部生成决策 |
| 生成文件清单 | 源码与构建声明的具体字节（其摘要参与版本身份） |
| BUILD／START／BUSINESS 证据 | 在指定环境构建、就绪、业务判分的实际结果 |

生成 pom 属于生成；下载依赖属于构建。静态合法不保证构建成功，启动成功不保证满足需求。独立验证（静态／构建／启动／业务）各自出结果，应用前由验证门检查证据是否可接受。数据库初始化／迁移和生产部署是未交付的独立能力，测试用的表结构不能当产品迁移。

## 7. 需要查实现时，从这些入口看

| 问题 | 代码位置 |
| --- | --- |
| SIR 语法入口 | `sir-parser/src/main/java/io/kcg/sir/api/SirParser.java` |
| 多文件收集／导入／统一名称绑定 | `sir-semantic/.../internal/ProjectSourceResolver.java`、`ResolvePass.java` |
| 完整语义结果 | `sir-semantic/.../api/NormalizedSemanticModel.java` |
| 软件结构定义 | `sir-semantic/.../schema/SoftwareSchema.java` |
| 指令编辑与写回 | `sir-change/.../edit/SourceEditor.java` |
| Spring Boot 实现入口 | `sir-lowering-spring-boot/.../api/SpringBootTargetLowering.java` |
| 纯文件渲染入口 | `sir-generator-spring-boot/.../api/SpringBootGenerator.java` |
| 共享的编译步骤 | `sir-toolchain-application/.../internal/CompilationStages.java`、`ProjectCompilation.java` |
| 首次生成与文件事务 | `.../api/ToolchainApplication.java`、`internal/FileTransaction.java` |
| 版本规划与应用 | `.../api/WorkspaceVersionApplication.java`、`internal/workspace/WorkspacePlanning.java`、`WorkspaceWriter.java` |
| Git 端口与适配器 | `.../api/VersionControl.java`、`execution/GitVersionControl.java` |
| 验证门 | `.../api/VerificationGate.java`、`VerifiedWorkspaceApplication.java` |

## 8. 文档如何分工

[MAIN](../MAIN.md) 和 [AGENTS](../AGENTS.md) 管工程规则；[ADR](architecture/) 管架构裁决；[设计](design/README.md) 管目标产品；[项目状态](PROJECT_STATUS.md) 和[验证说明](qualification/README.md)说明已实现与已验证；[history](history/README.md) 只用于追溯。
