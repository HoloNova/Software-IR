# KCG-Code 当前项目状态

> 更新：2026-10-24。状态：正常开发中的工程，不宣称生产就绪。本文说明**现在能做什么**；验证口径与证据在[验证说明](qualification/README.md)；已完成或退役的材料在 [history](history/README.md)。

## 1. 定位

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。Agent 或小模型负责提出可检查的 SIR；确定性程序负责解析、绑定、类型、约束、降级、生成、来源图、文件写入和变更计划。它不是普通 CRUD DSL，也不允许模型绕过 IR 直接决定最终 Java 工程结构。

## 2. 当前实现

父 Maven Reactor 含九个业务子模块：

| 模块 | 当前职责 |
| --- | --- |
| `sir-parser` | `sir 0.3` 根文档与声明片段两个入口、不可变 AST、SourceSpan、稳定 AstNodeId、解析诊断 |
| `sir-semantic` | Resolve/Type/Validate/Normalize、typed reference-site binding、Normalized model；`schema` 包为类型化软件结构 |
| `sir-lowering-api` | 目标无关的降级契约、来源、版本、诊断和 Validator |
| `sir-lowering-spring-boot` | Java 21/Spring Boot/MyBatis-Plus/MySQL/Maven/REST 降级模型与校验 |
| `sir-generator-spring-boot` | 只消费降级模型的确定性内存文件生成 |
| `sir-project-graph` | 来源图的构建、校验、canonical 序列化与加载 |
| `sir-change` | 增删改指令（`Command`/`CommandBatch`）的编辑与写回（`SourceEditor`） |
| `sir-toolchain-application` | 编译、首次生成、只读结构查询/候选源编辑、独立交付包导出、基于 Git 的版本规划与应用、独立验证、静态校验与 JSONL 批量 |
| `kcg-cli` | `generate`、交付导出 `package`（步骤①已由CI #94验收）、只读 `inspect`、候选 `edit`、`check`、只读 `version-plan`、`version-apply`，canonical JSON 输出 |

## 3. 当前能力

### 语言与编译

- 只有一种文档形式：`sir 0.3` 根文档（`metadata`、`target`、`sources { }`、`imports { }`、`declarations { }`）加可选的声明片段文件。一个软件写在一个文件里，就是没有片段的根文档。最多 128 个文件、每个 1 MiB、合计 8 MiB。
- 编译阶段：Parser → Resolve（名称只解析一次）→ Type → Validate → Normalize → Spring Boot 降级 → 确定性生成。名称解析出错后分析仍会继续，类型与校验错误一并报告（只有身份冲突会提前停止）。
- 声明按（种类，名称）排序，生成的文件顺序因此是规范的，同一输入总得到逐字节相同的输出。
- 业务语言已覆盖：查询切片（`view` 响应投影、`Page<T>` 分页、显式 `order by`、字面匹配 `containsLiteral`）、唯一字段与按唯一字段加载（`unique`、`load … by 字段:`，CI [#76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131) 通过）、时间平移与数值加减（`now() + 15 minutes`、`a - 1`，CI [#77](https://github.com/HoloNova/Software-IR/actions/runs/38712747591) 通过）、带守卫的状态流转（`update … when … else …`，CI [#78](https://github.com/HoloNova/Software-IR/actions/runs/38715357765) 通过，含真实 MySQL 并发 IT）、写入切片（`versioned` 并发字段、声明式错误状态码、`persist … else` 条件持久化、`input … patch of` 局部更新、`.present` 存在性、结构化错误信封）、关系切片（关联存在性过滤与批量关联读取）。这些在真实 MySQL 8.4 + HTTP 的场景 IT 中验证过（见验证说明）。
- 语言升级（2026-10-18，[设计 12](design/12-feature-semantics-and-semantic-types.md) 第 13 节）：字段只写语义类型（`Name`、`Count`、`Moment`、`Secret` 等，自带规则），由目标决定 Java 类型和数据库列；`type` 自定义语义类型；`use 功能 { 参数 }` 一句展开成一组声明（自带 `DualTokenLogin`），`feature` 自定义功能；`delete` 步骤；字段 `default`（同时是数据库列默认值）；实体 `scope`（默认查询范围，如软删除）；`extend` 给已有实体加字段（功能借它带上所需字段）。写法见[语言参考](SIR_LANGUAGE_REFERENCE.md)。
- 语义补充（2026-10-18～20）：内置 `use Crud { entity: X; }` 一句给出增删改查，`owner` 让每一行只属于一个账号，`path` 给出 REST 路径；错误可以声明 403，工作流可以读当前用户的字段（`actor.role`），授权就是以 `actor` 为一边的普通检查；`expose … [put|delete] at "/notes/{id}"` 自定 HTTP 方法和带路径参数的路径；已上线的列可以改默认值。部署第二版用 `Crud`（含 `owner`、`path`）管理笔记和标签，CI 冒烟在真实 MySQL 上核对 401／403／404 与 REST 路径。
- 已上线系统的结构变更（2026-10-20，[设计 12 第 14 节](design/12-feature-semantics-and-semantic-types.md)）：除加列、改名、改默认值外，可以放宽（整数变宽、文本变长或去掉长度、必填变可空），可以删实体、字段，但打包时要写 `--confirm-backup yes`（声明已备份，工具不检查）。本机测试通过；真实 MySQL 上的验证由新增的部署第三版冒烟承担，待提交后的 CI。删掉引用别的表的列、主键列仍不支持。
- 唯一约束（2026-10-20，[设计 12 第 15 节](design/12-feature-semantics-and-semantic-types.md)）：`persist 变量 else 错误` 能说明新建的行撞上唯一约束时报哪个错误（原来是 500）；实体可写 `unique (a, b);` 声明联合唯一，建表时成为复合唯一索引，保留数据的更新里只能随新建表创建。
- 选题试写（[报告](qualification/TOPIC_TRIALS.md)）：八个典型后端的 84 条需求，当前 76 条完整写出、1 条带折扣、7 条尚未写出（90.5%／1.2%／8.3%）。带折扣的是商城的单商品下单；写不出的主要是列表与批量处理、跨行数量、日期差和图表导出。缺陷 D1 到 D5 都已修，八个题目的生成工程由 CI 逐个离线编译。

- G7 可选值（2026-10-20，已实现；后续 #140 已回归验收）：`x is none`／`x is present` 判断空值，兼容类型可比较；任一侧为空普通比较均为 false（含 `!=`），`not` 仍普通取反。显式组合空值判断可写可选筛选，不自动省略条件，PATCH `.present` 不变。tasks、market 已更新，Java 真值表与题目内存生成已验证；八题生成工程离线编译和真实 MySQL／HTTP 为 NOT_RUN。Windows 部署导出测试因现有安全读源能力缺失失败，未放宽边界。方案与限制见[设计 12 第 17 节](design/12-feature-semantics-and-semantic-types.md)，定向证据见[验证说明](qualification/README.md)。

- G2/G4 查询（2026-10-20，已实现；后续 #140 已回归验收）：工作流 `exists`／`count` 显式返回 Boolean／Int64，每步一条 SQL，复用实体 scope 与 G7 空值规则；关联 any 可绑定请求、actor 和已加载值，不拼 SQL。博客恢复标签关系筛选，任务用成员关系与 exists 组合授权。定向 25 类 192 项通过；不宣称查询后写入具备并发限额或范围锁。实现与边界见[设计 12 第 18 节](design/12-feature-semantics-and-semantic-types.md)，真实 MySQL／HTTP 与八题生成工程编译尚未运行。

### 首次生成

`ToolchainApplication.execute(sourceRoot, entry, outputRoot)`：读入源码（NOFOLLOW、有界）、编译、构建来源图，写入一个**尚不存在**的输出根——先写同卷暂存目录，再一次原子移动；任何失败都清掉暂存目录和本次创建的父目录。输出根已存在时拒绝，改动已有工程是版本变更的事。

### 增删改指令编辑

`SourceEditor` 把 `CommandBatch`（增加／删除／更新三种固定指令，另有按身份一并改引用的改名 `rename`）应用到类型化草稿，只重写变化的声明、写回 SIR 文本，并用正式分析复核。保持强类型模型，不为新增功能补专用操作。

公开入口已验收（2026-10-15，提交 `1c80c73`，CI [verify #72](https://github.com/HoloNova/Software-IR/actions/runs/38671123332) 通过）：`generate`调用原首次生成，`inspect`只读schema/draft与源集合摘要，`edit`读取JSON增删改批次并保存完整候选源目录，再接原version-plan/apply。原源与生成工程在edit期间不变；CLI不直接访问文件系统、不构建/启动。详见[CLI使用说明](CLI_WORKFLOW.md)。

命令行可以打成单文件 `kcg-cli/target/kcg.jar` 直接 `java -jar` 运行（`mvn -B -pl kcg-cli -am -Dmaven.test.skip=true package`）。整条链运行时只依赖 antlr4-runtime；包只重新打包已有类，不新增模块。本机已验证它与类路径启动的完整流程输出一致；CI 冒烟步骤已通过（[verify #73](https://github.com/HoloNova/Software-IR/actions/runs/38677243253)，`3ebe5f1`），见[阶段报告第4节](CLI_WORKFLOW_REPORT.md)。

### 独立部署交付（步骤①②③已验收）

`kcg package`只导出完整源码、物理schema/Flyway脚本、Docker/Compose配方和独立执行工具，不在导出时启动服务。随包入口负责目标机上的初始化、构建、启动和同包重启。26项本机轻量定向检查通过，没有本机部署/业务测试。实现提交`a7cba11`的CI [#93](https://github.com/HoloNova/Software-IR/actions/runs/39002409782)原全量门及业务IT全绿，新包实际构建、Flyway建库和应用启动成功，但HTTP烟测JSON解析异常。云端确认是验收脚本对空体401做JSON解析，修复`30f50bd`后同SHA的[verify #94](https://github.com/HoloNova/Software-IR/actions/runs/39019809176)全部success：部署、HTTP、账号权限、重启/重复部署保留数据、丢失卷拒绝和清理均PASSED，步骤①验收。首版拒绝无界唯一文本、未定义精度的Decimal及缺外部身份实现等情形；普通generate不变。使用与限制见[部署包v1](DEPLOYMENT_DELIVERY.md)，证据见[步骤①报告](DEPLOYMENT_DELIVERY_REPORT.md)。步骤②的Swagger、随包合同测试与独立业务验收包由CI #95/#96验收（合同13/0/7 NOT_VERIFIED，双Token业务26/0）；步骤③（合同见方案11第13节）的`update`动作与`--base-package`追加迁移由CI #97/#98在本样例上验收：schema不变更新、新表与可空新列的保留数据升级、较旧包拒绝及新实例全链重放均通过；只支持这三类变化，中断与漂移无真实库证据，不能标称完整部署产品已发布。

### 基于 Git 的版本变更（ADR-027）

- 规划（只读）：把当前 SIR 与候选 SIR 各完整编译一遍，要求磁盘上的**整个**生成工程等于当前 SIR 的编译结果，否则报告需要重新生成；给出新建／更新／删除的文件、变化的源文件和 `planId`。
- 应用：需要 `git`。顺序是保存当前版本（第一次快照）→ 写生成文件 → 核对 → 写 SIR 源码 → 回读核对 → 保存新版本（第二次快照）。快照只含涉及的 SIR 与生成文件，不推送。
- 可重跑：中途停下不自动恢复；磁盘文件已等于候选版本的字节就视为已应用，重跑同一应用即可完成；也可用 Git 回到第一次快照。
- 命令行：`version-plan`（只读）、`version-apply`（唯一会改工程的命令，按 `planId` 应用，中途停下退出码 4）。

### 独立验证

静态／构建／启动／业务四级各自独立出结果，不互相替代；`VerifiedWorkspaceApplication` 在应用前要求证据通过（`VerificationGate`）。构建和应用进程通过端口接入（Maven 构建器、Spring Boot 应用宿主）。

### 批量静态校验

`kcg check < samples.jsonl` 与 `SirValidationApplication`：单 JVM 串行校验 JSONL 样本（每个是一个 `sir 0.3` 文档），可指定停在 PARSE／SEMANTIC／LOWERING／GENERATION，保留原始诊断，纯内存、不写文件。通过只说明所请求阶段通过，见 [SIR 批量校验](SIR_BATCH_CHECK.md)。

## 4. 验证状态

- 当前完整验收基线：`bf14d905b2edce5b67bc0ddf39ed189be2c10aa1` 的 [CI #154](https://github.com/HoloNova/Software-IR/actions/runs/39749656260) 全部门 success，显式锁与预约屏障已验收；[证据第 25 节](qualification/README.md#ci-154-accepted)。#155 主验证成功、部署被取消，不计整轮通过；本批 library 源码尚需新 CI。
- G10：Money 的精度、PATCH、原子金额与实际事务连接严格模式已验收；同 SHA 金额真库57项、独立部署精度／scale漂移拒绝、恢复和新实例迁移链均通过。不自动迁移旧整数分或已有金额精度。
- G6：精确乘法与能力级必需值计划已验收，乘法真库 37 项（#147）、后扩展至 56 项（#150）通过，包含并发数量扣库、计算后精度拒写回滚和明细一致性；八题生成工程离线编译通过。需求账本已由后续 #150 更新为 66／5／13（金额一条与剩余名额一条转完整）。

- 历史核验：`b56ce16`（改名）的[verify #124](https://github.com/HoloNova/Software-IR/actions/runs/39362618814)通过；此前路由 `b5d6c25` 的 [#122](https://github.com/HoloNova/Software-IR/actions/runs/39361622833)、归属与授权冒烟 `5c7b1fb` 的 [#121](https://github.com/HoloNova/Software-IR/actions/runs/39116237645) 通过（#123 因 #124 的推送被取消，其改动由 #124 覆盖）。
- 部署交付：`a7cba11` 的 [verify #93](https://github.com/HoloNova/Software-IR/actions/runs/39002409782) 曾因验收脚本失败，之后由 #94～#98 验收，见[部署报告](DEPLOYMENT_DELIVERY_REPORT.md)。
- 此前业务能力 CI：`verify` [#88](https://github.com/HoloNova/Software-IR/actions/runs/38901782044)（`fe40836`，`kcg scaffold dual-token`，`main`）通过，日志确认双 Token 脚手架 IT 全部 PASS；此前 [#85](https://github.com/HoloNova/Software-IR/actions/runs/38897336941)（`70dc6cc`，Bearer 凭据认证，`main`）通过，日志确认新 IT 的认证场景全部 PASS；此前 [#82](https://github.com/HoloNova/Software-IR/actions/runs/38890915687)（`de563f6`，`secret` 字段与 `matches`，`main`）通过，日志确认注册／登录场景通过；此前 #81（401 状态码）、[#79](https://github.com/HoloNova/Software-IR/actions/runs/38884618786)（`6f244fa`，真实库核验的修复与 `TokenDeskBusinessConformanceIT`，`main`）通过，日志确认新 IT 运行并通过；此前 [#78](https://github.com/HoloNova/Software-IR/actions/runs/38715357765)（`aa1ad70`，守卫更新）、[#77](https://github.com/HoloNova/Software-IR/actions/runs/38712747591)（`1f947db`，时间平移与数值加减）通过：冻结与完成两条全量闸门、业务场景 IT、独立验证 IT、单文件包冒烟均 success，见[验证说明](qualification/README.md)。此前 [#76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131)（`058c9d6`）、[#73](https://github.com/HoloNova/Software-IR/actions/runs/38677243253)、[#72](https://github.com/HoloNova/Software-IR/actions/runs/38671123332) 同样通过。核对时只读了运行与步骤状态。
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
- 数据库初始化和Docker／Compose交付的步骤①已由CI #94在本样例上验收；步骤②的Swagger、随包合同测试与独立业务验收包已由CI #95/#96验收；保留数据迁移、Web平台、模块市场、跨项目缓存仍未发布。
- Redis Extension、第二个目标、Constraint VM、Java 反向解析、GUI/daemon、多用户。
- SLM 评测：总设计见 [SLM 评测计划](qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)，尚未启动。
- Windows 平台证据；生产、安全认证、性能、高可用资格均未声明。

## 7. 当前阶段（2026-10-24）

**语言升级已实施：**负责人 2026-10-17 确认把 SIR 做成 Java 之上的更高层语言——一句声明给出一个功能，作者写语义而不写机器类型，用户可以定义自己的类型和功能，数据库迁移由变更层驱动。[设计 12](design/12-feature-semantics-and-semantic-types.md) 第 9 节的五步已全部实施，结果与留到下一阶段的事项见其第 13 节。迁移由变更层驱动之后，已上线的表支持列改名（带 `@id`）、加可空列、加带默认值的必填列和新建表，这些都由 CI 部署冒烟在真实 MySQL 上核对。

**之后的语义补充（2026-10-18～20）：**负责人确认先补语义、暂不做小模型评测，依次实施了内置 `Crud`、授权（403 与读当前用户字段）、路由与路径参数、通用改名命令，均已由 CI 通过（见第 4 节）。随后负责人确认并实施 G7 可选值比较、空值判断与显式筛选（`33f960b` 已推送，推送时按要求未核验 CI）；再确认并实现 G2/G4 查询原语（`00e3ce9` 已推送，推送时未核验 CI）。后续核验发现 #136 的真实 DTO Optional 取值错误、#137 的旧非法夹具和新增引用角色覆盖缺口；修复工作区 58 项定向通过，负责人随后授权随本提交推送并继续工作；同源码 CI 验收尚未完成。证据与未执行项见[验证说明第 8 节](qualification/README.md#ci-136-137-fix)。CI 修复已推送为 `ee6ddeb`；#138 的全量门通过、部署更新接口错误期待 201 而实际 200，本轮已改正断言，不能把整次 CI 记为通过。负责人确认后，[G9 Crud](design/12-feature-semantics-and-semantic-types.md#g9-crud) 已在工作区实现角色写权限、字段排除、公开读与多段路径，并贯通通用参数值和精确改名；library／market 已采用。89 项定向通过，真库 IT 已接入 CI，`f82cb94` 已推送；#139 双全量门通过，新增 G9 真库 IT 因把夹带排除字段的请求当作正常写入／权限请求而失败，后续部署被跳过。本轮分开合法请求与字段篡改请求，未改生产规则；13 项相关定向通过，完整 IT 仍待修复源码 CI。证据见[第 10 节](qualification/README.md#ci-139-fixture-fix)，八题完成数不提前上调。证据见[第 9 节](qualification/README.md#g9-crud-local)。SLM 评测的模型与样本数仍未定。

**CI 提速实测：**#155 主验证 8:18（同业务源码 #154 为 19:51），在线预热 0:27、Frozen 3:37，Completion 按预期跳过；真实业务／独立验证／CLI 保留。部署被取消，不能比较整轮时长，也不将全部观测改善归因为编排。详见[验证第 25 节](qualification/README.md#ci-154-accepted)。

**#174修复（待新同源码CI）：**`0b79ee5` 在预热编译失败：新增RelatedCounts未接图谱工厂两处穷尽switch，本机旧class掩盖源码缺口，原125项不能证明全源码可编译。已补独立RELATED_COUNTS角色、路径与现有枚举消费者，并在无目录导出的实际library／组合及market／ledger上验证节点、边、文件哈希与序列化。自审同时修正分页验收预算：原分页已有总数＋根行2读，另加1次副本聚合；不是根行1读。实际MyBatis SQL与共享夹具对齐，预算仍拒缺失／重复／额外查询，完整证据落盘。本轮8类62项通过，主源码与测试重新编译；生产计数／SQL／事务未改，详见[验证47](qualification/README.md#ci-174-related-count-graph-fix)。#174业务与部署未运行，新真库结果待负责人通知，账本76／2／6不变。

**上一实现（图书可借数量 count 视图与 Crud readView，待同源码CI）：**library 的目录详情、分页与检索现在直接返回 `availableCopies`：只数 `AVAILABLE` 副本，`LOST` 与借出不计，无副本为 0；不新增存储列，不改借还协议和写权限。计数按当前页一次分组查询（空页不调子 Mapper），同一只读快照内完成；它是“现在书架上几本”，**不是借得到的保证**。当轮报告 18 类 125 项定向通过（编译证据局限见上文）（含 19 项 count 契约、4 类拒绝、源往返、真实生成服务、文档门与库夹具重建）；实施中修正了四个先以 RED 暴露的缺陷：含 count 时字段改名抛 `IllegalStateException`、全角色／结构／跨文件门未登记新角色、IR 计划残留外层根行引用、以及缺乏可执行示例。实际 library 变为 452 行 SIR／4240 行 Java／20 项能力；真库 HTTP／MySQL 与完整导出门本机 NOT_RUN，见[验证46](qualification/README.md#library-related-count-local)。本条只转为待 CI 的 △，账本76／2／6，AVG、日期差罚款、请求列表与累计仍未做。

**上一实现（G9逐题写保护，已由#172验收）：**market系统字段／卖家归属与library馆员目录策略已有，本批补真实生成执行及原library IT的HTTP／数据库快照检查，不新增G9语法或应用构建。发现Optional<Name>的DTO元素和最终候选都误把null当NotBlank违规，已按目标Optional事实修正：缺省／null合法、有值仍拒空白，非可空Name仍必填；标准约束码及消息模板、长度／精度／时区、PATCH版本写保留。SQL自审另用实际Mapper复现condition裸列名，按MySQL8.4.5保留字事实仅在实体SQL注解中转义；物理列／JSON不改名，普通标识符不变。14类127项定向通过，旧借还／预约不删，实际路由／错误码／external环境及拒绝未知JSON字段已核对，见[验证44](qualification/README.md#g9-topics-optional-name-local)。两项G9后由#172转✓（当时账本76／1／7）；library源当时为443行，本片新增 count／readView 后为452行、Java4240行。文档与#171验收记录随代码提交，不查／等新CI。

**当前验收基线（#172，91fc78e）：**[同SHA运行](https://github.com/HoloNova/Software-IR/actions/runs/40112734269)的Frozen、业务IT、独立验证、CLI与部署均通过，library247项业务检查全部通过（含market系统字段保护和library馆员目录两题及原借还／预约回归），源码／DDL／seed摘要一致；两项G9由△转✓，当时账本76／1／7，详见[验证44](qualification/README.md#ci-172-g9-accepted)。本片在此基础上新增 count 视图与 readView，不借用该结果验收新代码。

**历史基线（#171，a2540d0）：**[同SHA运行](https://github.com/HoloNova/Software-IR/actions/runs/40073882068)的Frozen、业务IT、独立验证、CLI与部署均通过，259份XML无失败／错误。原乘法IT128项检查覆盖实际shop取消、100／101界、重复／Pay竞争、父锁等待、非严格上限及第二商品晚失败完整回滚，原72项保留；SIR／DDL／seed摘要核对一致，取消由△转✓，账本74／3／7，详见[验证43](qualification/README.md#ci-171-cancellation-accepted)。新G9组合源码与库DDL另需本批CI，不借旧结果验收新代码。

**G5第一片实现历史（已由#171验收）：**[设计27](design/12-feature-semantics-and-semantic-types.md#g5-bounded-child-iteration-proposal)已按确认实现有界服务器each和实际shop取消返库：最多100行、超限先拒绝且不执行体，父订单状态守卫及商品/id升序锁；SQL整数增量、候选前上限检查和整笔事务保留。21类202项定向通过；Core／目标IR作用域逃逸及计划损坏独立拒绝，源编辑／正式引用／文档门贯通。8题无each基线775个生成文件与旧渲染器逐字节一致。原乘法IT组合正式改名的实际shop，保留原检查，追加并发／锁等待／非严格上限及晚失败回滚，无新增应用构建；本机只编译，不把代理异常传播当事务回滚证明，见[验证42](qualification/README.md#g5-bounded-each-local)。shop327行SIR／2975行Java／12项能力；取消仍记待CI的△，账本73／4／7不变。请求列表、多商品结算、累计金额和购物车未做；文档与#170验收记录随实现提交，不单独触发CI，不查询新CI。

**此前验收基线（#170，e9f38dd）：**[同SHA运行](https://github.com/HoloNova/Software-IR/actions/runs/40052208682)的Frozen门、全部业务IT、独立验证、CLI与部署全部success；251份XML／1708项无失败、错误或跳过，Money306项业务检查通过，含两种真实会话／JVM配置、月界微秒、历史日期CRUD、用户偏好RR屏障和指定当前预算。源码／DDL／seed摘要与当时资源一致，月报／预算两项转✓，账本 **73／4／7**，见[验证41](qualification/README.md#ci-170-calendar-accepted)。自然月用本人时区、预算用当前规则，不新增期间历史；AVG、G5、日期差及图表／导出不因此视为完成。验收文档留待下一对应源码提交，不单独触发CI。

**历史阻塞修复（#169，已由#170闭环）：**Money加入认证月报后漏配external模式，生成工程构建通过但Actor启动守卫正确拒绝，月报真库未执行。现已在实际IT创建路径补齐external，并验证真实子进程Spring环境、生成身份守卫／凭证适配器／MyBatis装配及匿名401；7类21项定向通过，未改生产代码或弱化鉴权，见[验证40](qualification/README.md#ci-169-actor-mode-fix)。单批提交推送后由负责人通知CI，不查询或等待。当时账本为71／6／7，现按#170转为73／4／7。

**用户时区月报／当前预算实现记录（已由#170验收）：**已按[设计26](design/12-feature-semantics-and-semantic-types.md#g8-calendar-month-scope)实现无参数timeZone约束、可信本人偏好及month双UTC边界；只读RR复用现有SUM／分组。指定budgetId和分类都检查本人归属，严格大于当前上限才提示，不冻结历史。旧用户未设偏好明确拒绝；新增可空列须应用schema变更。生成目标按计划启用UTC JDBC4.2适配，避免Timestamp混合日历及默认时区影响1000／1500等日期，无month工程不变。33类247项定向通过；真实Money IT复用构建，加入月界微秒／历史CRUD／非UTC实际会话及偏好修改屏障，本机NOT_RUN。ledger281／4609／28，实现当时账本71／6／7、现验收后73／4／7；详见[验证39](qualification/README.md#g8-calendar-month-local)。#168记录与配套文档随本批代码提交，不额外触发文档CI，不查询／等待新CI。

**上一验收基线（#168，87cad8b）：**[同SHA运行](https://github.com/HoloNova/Software-IR/actions/runs/39927438145)的Frozen门、全部业务IT、独立验证、CLI与部署全部success，240份XML无失败／错误；金额／SUM／分组150、library203、course151、乘法／预约72项业务检查均通过。实际源码digest匹配；分组预算JSON确认业务计数1／分页1／会话探测1／未知0，快照屏障及严格／非严格告警通过。G8单值与分组验收闭环；借阅限额、FIFO主动领取预约、课程冲突及任意直接先修4条转完整，账本更新为 **71／4／9**，详见[验证38](qualification/README.md#ci-168-g2-g8-accepted)。该轮尚无自然月／预算，71／4／9是当时验收数；之后按确认口径实现两项，现已用#170新SQL证据验收，不挪用旧证据。未新增每期预算表。

**上一轮修复（#167，已由#168验证通过）：**74bd393的Frozen全量门通过，#165／#166修复均有效；237份XML中仅MoneyBusinessConformanceIT 1失败。真实分组业务已全部执行并通过（金额、组数、分页、越界、隔离、计数后提交新组仍读旧快照、严格／非严格两条语句告警），唯一失败是新增读取预算：`selectCount`按schema收集所有SELECT，7条含5条Connector/J会话探测。已按角色分类（业务计数恰1／业务分页恰1／不可识别0，且summary条数与日志一致），并把#167真实digest（服务端DISTINCTROW规范形）固化为回归；完整digest、raw语句与分类写入grouped-sum-query-trace.json，写失败即判失败。未改期望值、未放宽或删断言，生产聚合／事务／告警未动。7类59项定向通过，真实SQL预算与快照仍待同源码CI，见[验证37](qualification/README.md#ci-167-grouped-read-budget-fix)。月度设计26草案不混入，不查询或等待CI。

**上一轮修复（#166，已由#167验证通过）：**a696531的Frozen全量门及TypedReferenceSite18项／完整ProjectVisibility7项均通过；唯一业务失败在Money启动时的测试夹具依赖环：MyBatis工厂收集分组拦截器，后者提前取得Mapper，再回到未完成的工厂。已让夹具仅在执行告警请求时解析Mapper，并用实际生成源码、真实MyBatis自动配置／MapperFactoryBean、禁止循环引用的无数据库容器先RED再GREEN；6类29项定向通过，告警与计数后屏障保留，不改生产实现或开放循环引用，见[验证36](qualification/README.md#ci-166-fixture-mapper-cycle-fix)。Money分组SQL／快照未运行到，独立验证／CLI／部署跳过，仍须修复SHA CI；月度设计26草案不混入提交，不查询或等待CI。

**上一轮修复（#165，已由#166验证通过）：**e270436的冻结门发现GROUP_KEY_FIELD未进入TypedReferenceSite全角色夹具，完成诊断另发现跨文件角色覆盖同样遗漏；业务IT／独立验证／CLI／部署均跳过。已补真实分组源码、测试AST遍历及无导出精确缺import反例，不改生产实现、不缩减角色集合，6类53项定向通过，见[验证35](qualification/README.md#ci-165-group-reference-coverage-fix)。完整文件导出契约仍须Linux CI，本机3个无导出方法不冒充全类通过。修复随配套文档提交推送后继续月度／预算范围核对，不查询或等待新CI。

**G8引用分组分页（实现记录；已由#168验收）：**[设计25](design/12-feature-semantics-and-semantic-types.md#g8-grouped-sum-proposal)已按确认实施：非Optional Ref单键、精确Decimal SUM、Page<SumGroup<Ref<Entity>>>固定key/total与稳定键顺序。COUNT DISTINCT及当前页共两次读取，复用一次筛选求值，明确只读REPEATABLE_READ及每语句告警／STATEMENT缓存；补齐any身份到根行外键的正式关联方向，不是通用JOIN。ledger增加本人区间／本人分类分页汇总（220行SIR／4106行Java／24项能力），不新增存储列、Mapper方法或应用构建。27类236项定向通过，两个CI探针实编并验证计数后屏障；真实HTTP／SQL快照和告警待原Money IT验证，本机NOT_RUN，见[验证34](qualification/README.md#g8-grouped-sum-local)。自然月／时区、预算历史、AVG／多键／零组未实现，账本仍67／8／9。配套文档随实现提交，不查询CI。

**上一轮修复（#163，0bebfcd，负责人已通知通过）：**Money IT 在构建前写告警探针副本，但证据目录要到构建写日志时才创建，故 #163 以 `NoSuchFileException` 中止，SUM 真库与告警断言未执行；同一运行的 `money-report.txt` 还把中止写成 PASSED。已让写入方创建证据目录，并给 harness 加异常记录守卫，14 项定向通过，未改生产逻辑，见[验证33](qualification/README.md#ci-163-evidence-dir-fix)。修复随测试与文档单批提交推送后继续后续工作，不等待新 CI，结果由负责人通知。

**上一轮修复（#162）：**SUM参考示例把声明片段标成完整sir且漏实体，LanguageReferenceExamplesTest因此在PARSE阶段失败；业务IT和部署未验收。已补完整可执行声明片段及正式绑定／精确SUM计划契约，23项定向通过，Parser／SUM生产逻辑未改，见[验证32](qualification/README.md#ci-162-sum-reference-fix)。修复随测试单批提交推送后继续分组方案，不等待新CI，结果由负责人通知。

**上一实现阶段（G8首批，历史本机验证）：**[条件Decimal求和](design/12-feature-semantics-and-semantic-types.md#g8-decimal-sum-proposal)已贯通语法／正式绑定／scope／源编辑／目标计划与图谱。空集／全空为0，总额不套单笔Money精度；当前Statement告警明确拒绝，普通查询不变，实体Mapper不变。ledger增加本人区间／本人分类总额（195行SIR／3949行Java／23个显式接口）；30类187项定向通过。实际ledger生成服务已编译执行，隔离CAST探针仅编译；真库复用原Money IT，无新增应用。真实SQL告警及所有IT本机NOT_RUN，结果由负责人通知，不查询CI。当时尚未包含分组；本批已推进上述分组切片，平均、自然月／预算和G5仍另案，账本不变。见[验证31](qualification/README.md#g8-decimal-sum-local)。

**G2第四批书目预约（实现记录；已由#168验收）：**实际library已实现书目FIFO、本人扫码领取、本人／馆员取消及私有列表。普通借书、归还、添加副本和排队共同遵守Book锁；入队不占借阅名额，满额不跳过，领取在一笔事务内增名额／占副本／建Loan／删预约。没有新增语法或编译器生产分支。8类58项定向通过；原library IT复用同一应用，新增并发、取消竞争、可观察锁等待及晚失败四表回滚检查，仅编译、本机未运行，见[验证30](qualification/README.md#g2-reservation-local)。预约从未写出转待真库△，当前 **67／8／9**，完整数不升。没有查询或等待新CI。升级须新增空队列表并同时停用旧写入口，避免新旧协议混跑；不自动分配、过期或跳过。后续循环／聚合、日期差等仍需另案范围。

**G2第三批课程组合（实现记录；已由#168验收）：**实际 course 题目已用现有学生锁、周课表区间和两个同级 any 表达时间冲突与任意直接先修集合，不增加语法／编译器生产分支。管理员维护草稿先修、发布后冻结，成绩仅可信追加；选／退固定学生锁并保留座位条件增减。6 类 38 项定向通过，实际题目真库IT已编译接CI，尚未本机执行，见[验证第28节](qualification/README.md#g2-course-local)。课表、先修和历史通过不自动猜测迁移。两条只从未写出转待真库△，完整数仍67，当前 **67／7／10**。#158 正式全量门及课程生成工程构建通过，但新IT错误地探测不存在的 `/api/courses/10`，持续404，课程业务未执行、部署被跳过；现只修正为公开搜索探针并补实际端点／输入契约，3类34项定向通过，见[验证29](qualification/README.md#ci-158-readiness-fix)。新真库仍待修复SHA，不等待或轮询CI，负责人通知结果。

**上一业务批（G2 第二批，#157 运行成功）：**library 的 5 本上限已用读者锁＋条件增减实现；还书固定 User → Loan → Copy 锁顺序，首次归还减一，重复／越权／计数异常拒写，晚失败要求整事务回滚，续借不加名额。7 类 31 项本机定向通过；新真库 IT 直接使用实际 library 样例，未本机执行，见[验证第 26 节](qualification/README.md#g2-library-local)。旧库须先回填真实计数，不能把 DEFAULT 0 当迁移。没有增加语言／生成器分支。#156 正式全量门通过，借阅报告 60 项通过、22 项错误码断言失配；已只修正测试对声明名大小写的预期，3 类 17 项定向通过，后续修复SHA `9d31a82` 的#157已核实success，#158借阅IT亦通过；账本另行按证据归档，见[验证第 27 节](qualification/README.md#ci-156-error-contract-fix)及[第29节](qualification/README.md#ci-158-readiness-fix)。课程组合现见第三批；排队已由第四批组合现有前序exists实现，不再认定主动领取必缺首行原语。

**上一阶段验收：**#154 已使 G2 显式锁／半开区间预约闭环，乘法与预约报告 72 项通过，包含先读后等锁、等待后重新检查；G10／G6／G11 与完整部署回归通过。账本仅将已验收的预约重叠转✓，新借阅上限暂记待验收△，该次验收后的账本为 **67／5／12**；本批两项待真库表达见上文，不提前宣称新业务真库通过。不扩大到循环、聚合或日期差。

## 7.1 上一阶段（2026-10-17）

公开CLI流程已验收，不重开已完成R1–R5，不改变Git版本合同。

**当前优先方向：**负责人已确认[可独立部署的后端交付包与真实HTTP验收方案](design/11-portable-deployment-and-http-acceptance.md)，按“首次自动部署 → Swagger与随包网络测试 → 保留数据更新”三步推进。负责人随后已授权进入实现；步骤①CI #93因验收脚本阻塞，云端修复后由CI #94验收；步骤②由CI #95/#96、步骤③由CI #97/#98验收，详见[报告](DEPLOYMENT_DELIVERY_REPORT.md)。当前开发机不进行部署或业务测试，验收在CI或负责人后续指定的机器执行；不按当前开发机资源设定发布环境。既有SLM题目保留，但未启动模型评测；尚未通过的部署和测试输出不能记为已验收。

下面的 S1/S2 是负责人 2026-10-15 确认、随后完成的阶段划分与历史进展。

- S1（已完成，`3ebe5f1`，CI #73 通过）：把 #72 的证据写入文档，命令行打成可直接运行的单文件包。
- S2（双Token扩展性探针，实现链全部完成）：用双 Token 作为对语言扩展性的探针。结论：缺口都是通用能力，逐个补上之后，双 Token 七条行为都能用语言本身写出，并在真实 MySQL 上验证——唯一字段与按唯一字段加载、时间平移与数值加减、守卫更新、错误状态码 401、`secret` 字段与 `matches`、`credential` 子句，最后是工具链里的脚手架 `kcg scaffold dual-token`（没有做成语言内的展开，理由见设计 10 第 27 节）。各步骤、实测改动面、CI 证据见[设计 10](design/10-semantic-boundary-and-mechanisms.md)第 16–27 节。第 5 问（小模型实验的接入）搁置：它依赖的 `STEP0_EXPERIMENT_HANDBOOK.md` 找不到，已改为起草一道提案题，见[SLM 第一道题提案](qualification/SLM_FIRST_TOPIC_DUAL_TOKEN_PROPOSAL.md)，负责人 2026-10-16 已采用这道题；模型与样本数未定，尚未评测任何模型。
- SIR 语言参考（2026-10-17，[`SIR_LANGUAGE_REFERENCE.md`](SIR_LANGUAGE_REFERENCE.md)）：给写 SIR 的人和模型用。文中每个示例都由 `LanguageReferenceExamplesTest` 检查——完整文档和 `declarations` 片段必须一路通过到生成，故意写错的示例必须被拒绝且带指定的诊断码，文档与语言不一致时测试会失败。本机定向运行 3 项通过；CI 证据待提交后补。写这份文档时发现并修正了文档自己的几处错误（如用 `item` 当变量名，它是保留字），语言没有改动。
- 实现链第 1 步（字段 `unique` 与按唯一字段 `load`）：负责人 2026-10-15 授权，提交 `058c9d6`，CI [#76](https://github.com/HoloNova/Software-IR/actions/runs/38710339131) 通过。本机定向测试与 11 个现有夹具新旧生成逐字节相同见[设计 10 第 17 节](design/10-semantic-boundary-and-mechanisms.md)；真实 MySQL 业务场景未编写。
- 实现链第 7 步（`kcg scaffold dual-token`，脚手架）：提交 `fe40836`，CI [#88](https://github.com/HoloNova/Software-IR/actions/runs/38901782044) 通过，真实库 IT 逐条验证七条行为并含 6 路同时刷新的竞争（恰有一个成功）。详见[设计 10 第 27 节](design/10-semantic-boundary-and-mechanisms.md)。该命令已于 2026-10-20 删除，由语言内的 `use DualTokenLogin` 取代；真实库 IT 改名为 `DualTokenBusinessConformanceIT`，夹具改为手写的 `dual-token-written.sir`（与 `use DualTokenLogin` 的生成结果逐字节相同）。
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
