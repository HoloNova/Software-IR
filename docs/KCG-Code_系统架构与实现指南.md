# KCG-Code 系统架构与实现指南

> 更新：2026-10-13。本文区分**现有实现**和**已确认的新方向**；详细目标方案见[设计08](design/08-layered-command-architecture.md)，方向决策见[ADR-026](architecture/ADR-026-layered-semantic-command-architecture.md)。不把方案当已实现能力。

## 1. 不看代码也能理解的工作流

首次：SIR描述软件 → 工具理解并检查 → 生成Spring Boot项目源码 → 按需要拉依赖、构建、启动和验证。

后续目标：增删改指令 → 修改已有软件草稿 → 使用同一套检查与生成 → 保存新的SIR和工程版本 → 按需要构建、验证。

SIR是软件描述，生成Java是实现。修改指令不是Java补丁；依赖下载和运行验证不是Parser的一部分。当前系统仍主要由调用方提供完整候选SIR，通用指令式编辑尚未实现。

## 2. 六层分别做什么

| 层 | 用一句话解释 | 主要现有模块 |
| --- | --- | --- |
| 源与解析 | 看懂源文件结构，保留真实位置 | `sir-parser`，源读取编排在Application |
| 软件语义 | 知道数据、功能、流程和约束，检查软件是否合法 | `sir-semantic` |
| 通用修改（目标） | 只做增加、删除、更新，不理解课程/订单等业务 | `sir-change`需迁移，当前仍是六类专用规划 |
| 目标实现 | 决定怎样用Spring Boot、SQL实现软件 | `sir-lowering-api` / `sir-lowering-spring-boot` |
| 文件渲染 | 根据实现方案输出源码和构建文件 | `sir-generator-spring-boot` |
| 工程应用 | 保存版本、更新受管文件、发布和恢复 | `sir-toolchain-application` |

Project Graph是生成来源地图，由`sir-project-graph`提供；不是另一个理解业务的层。CLI只处理参数和输出。Application编排入口串起公开接口；独立runner负责下载依赖、Maven、数据库/应用进程及业务证据。

分层不等于拆成微服务或立即增加Maven模块。当前九模块优先保留。

## 3. 多源怎样成为一个软件

入口文件显式列出成员，各文件分别解析；导入限制当前文件能引用哪些外部声明。语义层收集完整声明，做一次正式Resolve，再做Type/Validate/Normalize。结果是一个software，而不是每个文件生成一个工程，也不是文本粘接成一个假文件。

文件归属和SourceSpan仍保留，错误能指到实际文件。后半段复用同一套Spring Boot Lowering和Generator。当前多源仍完整分析/生成，不是局部增量编译。

## 4. 当前Change怎样工作

```text
保存的基线源 + 完整候选SIR + 指定操作
  → 两侧完整编译
  → 检查变化是否符合旧操作范围
  → 按来源追踪并比较完整文件结果
  → 只读计划
  → 执行时重新核验
  → 文件事务 + 保存候选源 + 发布CURRENT
```

工程基线就是已保存并认可的版本起点：源字节、图、受管清单和生成来源证据。候选SIR是希望得到的软件，不是Change IR。Change请求描述这次变化及其基线。

“改了什么”和“更新哪些文件”不相同：修改查询条件与修改接口地址都可能落在同一Controller/Service里，但业务意义不同。SemanticDelta和FileDelta在新架构中分别说明这两件事。

当前单源有六类专用操作及独立受限改名；多源只支持单能力工作流UPDATE。完整CLI写生命周期未发布。实际资格见[状态](PROJECT_STATUS.md)和[资格报告](qualification/CURRENT_QUALIFICATION.md)。

## 5. 为什么重新设计Change

当前Change自己维护SemanticProjection，又分别为workflow、输入约束等写规划；新增语言结构常需补它，组合新增/修改还受单项和纯文件族限制。

新方向是Semantic提供唯一SoftwareSchema：明确每个内容的身份、属性、子内容、引用和顺序；来源、推导类型等单独分类。Change通过统一结构接口编辑和比较，固定Add/Delete/Update，不加逐功能操作或插件。

保留强类型业务模型与现有Type/Validate。可编辑草稿不冒充已验证模型；修改后写出新SIR，重新正式分析取得新binding。不能改一份Normalized对象后继续沿用旧引用。

新增普通业务应主要组合现有能力；新增真正语言能力时修改Parser/Semantic/Target，Change不再跟着补投影。通用协议或bug仍可能维护内核，不许诺永不改动。

## 6. 文件、源、运行状态不能混为一件事

| 材料/结果 | 含义 |
| --- | --- |
| SIR源快照 | 可重新打开的软件输入 |
| 已验证语义 | 工具从指定源理解出的软件事实 |
| Lowered IR | 目标实现的全部生成决策 |
| 生成文件清单 | 源码与构建声明的具体字节 |
| Bundle/CURRENT | 工具链工程版本及提交方向 |
| BUILD/START/BUSINESS证据 | 在指定环境构建、就绪、业务判分的实际结果 |

生成pom属于生成；下载依赖属于构建。静态合法不保证构建成功，启动成功不保证满足需求。数据库初始化/迁移和生产部署仍是未交付的独立能力；测试DDL不能当产品迁移。

## 7. CURRENT与恢复继续保留

CURRENT是工程版本提交点，不是描述每个文件当时内容的实时清单：

- CURRENT=B0：新版本未发布，只向后补偿。
- CURRENT=B1：新版本已发布，只向前核验和清理。
- 来源或方向不明确：保留材料并拒绝猜测；恢复显式触发。

Application唯一拥有Bundle/CURRENT/锁/日志和文件写入。新架构需要混合文件及source-only发布，但现有UPDATE-only/改名协议不会因方案文档被静默放宽。安全句柄、硬链接锚、归属核验和有界历史是复用起点，需经实际探针证明。

## 8. 需要查实现时，从这些入口看

不用先读完大文件，按问题选一个入口即可。

| 问题 | 已核对的代码位置 |
| --- | --- |
| SIR语法入口 | `sir-parser/src/main/java/io/kcg/sir/api/SirParser.java` |
| 多源收集/导入/统一名称绑定 | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ProjectSourceResolver.java` |
| 正式名称绑定 | `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/ResolvePass.java` |
| 当前完整语义结果 | `sir-semantic/src/main/java/io/kcg/sir/semantic/api/NormalizedSemanticModel.java` |
| Spring Boot实现入口 | `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/api/SpringBootTargetLowering.java` |
| 纯文件渲染入口 | `sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/api/SpringBootGenerator.java` |
| 当前重复语言投影 | `sir-change/src/main/java/io/kcg/sir/change/internal/SemanticProjection.java` |
| 当前专用操作与规划 | `sir-change/src/main/java/io/kcg/sir/change/api/ChangeOperation.java`、`internal/PlannerCore.java` |
| 已共享的静态编译步骤 | `sir-toolchain-application/src/main/java/io/kcg/sir/application/internal/CompilationStages.java` |
| 多源编译编排 | 同模块`internal/ProjectCompilation.java` |
| 多源实际应用/恢复 | 同模块`api/ProjectChangeExecutionApplication.java`、`internal/projectupdate/ProjectUpdateTransaction.java` |

以上路径说明现有代码；SoftwareSchema、CommandBatch和通用源writer等新接口目前仅在方案中。

## 9. 接下来如何迁移

[迁移路线](roadmap/LAYERED_ARCHITECTURE_MIGRATION.md)依次为：统一语义结构 → 指令编辑和源往返 → 统一编译与只读完整计划 → 版本应用与恢复 → 独立构建/业务验收。

当前Q23和Q22后续推进暂停；本轮只更新文档并获明确提交/推送授权。所有代码阶段未授权，不自动恢复旧组合原型，也不启动数据库/Web。现有代码和证据保留，阶段验收依靠具体源码与当次结果，不以文档接受抵扣。

## 10. 文档如何分工

[MAIN](../MAIN.md)和[AGENTS](../AGENTS.md)管工程规则；[ADR](architecture/)管架构裁决；[设计08](design/08-layered-command-architecture.md)管本次详细目标；[状态](PROJECT_STATUS.md)和[资格](qualification/CURRENT_QUALIFICATION.md)说明已实现/已验证；[ACTIVE_WORK](roadmap/ACTIVE_WORK.md)管当前授权。日期化旧材料只用于追溯。
