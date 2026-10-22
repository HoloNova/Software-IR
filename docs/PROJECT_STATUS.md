# KCG-Code 当前项目状态

> 更新：2026-10-22。状态：正常开发中的工程，不宣称生产就绪。本文说明**现在能做什么**；验证口径与证据在[验证说明](qualification/README.md)；已完成或退役的材料在 [history](history/README.md)。

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
- 选题试写（2026-10-20，[报告](qualification/TOPIC_TRIALS.md)）：用 SIR 写了八个典型毕设后端（二手交易、图书借阅、选课、博客、任务看板、商城、会议室预约、记账），84 条需求里 79% 完整写出、6% 带折扣写出、15% 尚未写出（补上联合唯一、唯一冲突报错、原子计数、G7、G2/G4、G6 与 G11 后；金额计算与剩余名额两条已由 #147／#150 真库与生成工程证据转为完整）。缺口集中在多行业务的并发与关联组合、列表循环、分组聚合与更完整的功能组合；另发现语言检查放过、但生成的 Java 编不过的写法（D1 沿引用加载、D2 一行带嵌套视图、D3 可选值比较），已修，八个题目的生成工程由 CI 逐个离线编译。

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

- 当前验收基线：`4a1d8c8c2f28fd0d5678f65bfd4494cf163d8321` 的 [CI #150](https://github.com/HoloNova/Software-IR/actions/runs/39724155367) 全部门 success，覆盖 G11 计算视图真库断言、G10／G6 回归、双全量门、业务 IT、独立验证、CLI 与独立部署；[证据索引第 21 节](qualification/README.md#ci-150-accepted)，G10／G6 的验收点仍看[第 19 节](qualification/README.md#ci-147-accepted)。该基线不覆盖后续 G2 源码。
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

## 7. 当前阶段（2026-10-22）

**语言升级已实施：**负责人 2026-10-17 确认把 SIR 做成 Java 之上的更高层语言——一句声明给出一个功能，作者写语义而不写机器类型，用户可以定义自己的类型和功能，数据库迁移由变更层驱动。[设计 12](design/12-feature-semantics-and-semantic-types.md) 第 9 节的五步已全部实施，结果与留到下一阶段的事项见其第 13 节。迁移由变更层驱动之后，已上线的表支持列改名（带 `@id`）、加可空列、加带默认值的必填列和新建表，这些都由 CI 部署冒烟在真实 MySQL 上核对。

**之后的语义补充（2026-10-18～20）：**负责人确认先补语义、暂不做小模型评测，依次实施了内置 `Crud`、授权（403 与读当前用户字段）、路由与路径参数、通用改名命令，均已由 CI 通过（见第 4 节）。随后负责人确认并实施 G7 可选值比较、空值判断与显式筛选（`33f960b` 已推送，推送时按要求未核验 CI）；再确认并实现 G2/G4 查询原语（`00e3ce9` 已推送，推送时未核验 CI）。后续核验发现 #136 的真实 DTO Optional 取值错误、#137 的旧非法夹具和新增引用角色覆盖缺口；修复工作区 58 项定向通过，负责人随后授权随本提交推送并继续工作；同源码 CI 验收尚未完成。证据与未执行项见[验证说明第 8 节](qualification/README.md#ci-136-137-fix)。CI 修复已推送为 `ee6ddeb`；#138 的全量门通过、部署更新接口错误期待 201 而实际 200，本轮已改正断言，不能把整次 CI 记为通过。负责人确认后，[G9 Crud](design/12-feature-semantics-and-semantic-types.md#g9-crud) 已在工作区实现角色写权限、字段排除、公开读与多段路径，并贯通通用参数值和精确改名；library／market 已采用。89 项定向通过，真库 IT 已接入 CI，`f82cb94` 已推送；#139 双全量门通过，新增 G9 真库 IT 因把夹带排除字段的请求当作正常写入／权限请求而失败，后续部署被跳过。本轮分开合法请求与字段篡改请求，未改生产规则；13 项相关定向通过，完整 IT 仍待修复源码 CI。证据见[第 10 节](qualification/README.md#ci-139-fixture-fix)，八题完成数不提前上调。证据见[第 9 节](qualification/README.md#g9-crud-local)。SLM 评测的模型与样本数仍未定。

**CI 时长优化（待新 CI 对比）：**正常路径改为在线 dry-run 预热＋一次离线全量门，Completion 仅在 Frozen 失败后补齐诊断，首发失败报告单独保留；测试列表、真库、独立验证及部署链均不删除。#152 实测约 26:25，其中重复 Completion 3:04，目标合计节省约 5～6 分钟，未提前当成实测收益。详见[验证第 24 节](qualification/README.md#ci-runtime-optimization)。

**业务最新阶段（G2 第一批，待 CI）：**显式 `load … for update` 已贯通语义、源编辑、Scope、Lowering 与真实生成服务；计划 READ_COMMITTED 及 MyBatis STATEMENT 缓存，保留旧版本自动锁与实体 Mapper 不变性。booking 已改为半开区间并锁 Room 后检查重叠；15 类 147 项本机定向通过。先读后等锁的真库屏障检查复用原乘法 IT，本机未运行，见[验证第 22 节](qualification/README.md#g2-locking-local)。首轮 #153 双全量门通过，预约 IT 因把 Room 对象绑定到 room_id 而失败；已补明确主键投影及真实 MyBatis 参数回归，21 类 89 项定向通过，修复同 SHA CI 待验收，见[验证第 23 节](qualification/README.md#ci-153-reference-identity-fix)。账本仍 66／5／13，不提前升级需求。后续是 library 限额与借／还一致性；课程冲突需学生锁，任意先修课集合尚需反关联方案，不把旧草案中的未绑定变量当成实现。排队另案。纯文档 push 已配置忽略，文档优先与对应代码同批提交。

**上一阶段验收：**G11 计算视图已实现并由 `4a1d8c8` 的 #150 验收：关系真库 40 项、乘法真库 56 项（含 8 并发选课仅 3 成功与满额后 remaining／full）、双全量门、八题生成工程与独立部署均通过，详见[验证第21节](qualification/README.md#ci-150-accepted)。#149 的三项失败已确认是课程行的测试假设错误（借用只认主表的 expectRow、视图未声明 capacity），已修正。当前账本 66／5／13。下阶段回到 G2 业务组合（借阅上限与排队、课程时间冲突与先修课、任意时间段防重叠），G5／G8 需先确认范围。不扩大到循环、聚合或日期差。

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
