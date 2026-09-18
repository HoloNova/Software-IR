## Q7 CLI 产品边界覆盖（2026-09-18）

| 组 | 已覆盖 | 未覆盖/边界 |
|---|---|---|
| 命令面 | `--help`/`-h`、`--version`/`-V`、`context`、`plan` 的退出码 0 与输出内容 | — |
| 未发布命令 | `generate`/`register`/`apply`/`recover` 四重证据（退出码 2、错误码、help 不含、无副作用） | 四个命令**未发布**，不构成本地生命周期资格 |
| 用法错误 | 无参数、未知命令、缺必填项、相对路径 → 退出码 2 + `outcome=USAGE_ERROR` | 选项值缺失等更细的解析分支由既有工作流测试间接覆盖 |
| canonical JSON | 跨进程字节相同、首字段固定、单行 + 单换行；用法错误同为单行 JSON | 未做 JSON schema 级校验（只做字节与结构断言） |
| 内部失败 | 注入崩溃 → 退出码 70 + `KCG-CLI-INTERNAL-001` + 不泄漏内部消息 | — |
| 生产边界 | 常量池闸门三条规则 0 违规 + 灵敏度探针 | `io/kcg/cli/mvp/**` 为显式例外（非命令路径，命令路径不可达它有断言） |
| 发布物 | — | **thin JAR / 发行包 `NOT_RUN`**（属独立发布任务） |

# KCG-Code 测试覆盖与缺口清单

> 更新日期：2026-09-18（§1、§2、§3 的测试数来自 2026-09-18 Linux 完成形式离线 Reactor、Q1 定向闸门和 Q2 Project Graph 定向闸门，见资格文档 1.3、1.4）
> 用途：记录当前可执行测试、明确缺口和后续验收输入；不以历史测试数量作为完成目标

## 1. 当前覆盖摘要

| 模块 | 当前直接测试 | 覆盖判断 | 优先级 |
|---|---:|---|---|
| Parser | 43 | 核心语法、AST、诊断和确定性有直接覆盖 | 维护 |
| Semantic | 103 | Resolve/Type/Validate/Normalize 与 typed reference-site 有系统覆盖 | 维护 |
| Lowering API + Spring | 36 | API、Profile、边界、确定性和 hardening 有直接覆盖 | 维护 |
| Generator | 38 | canonical 输出、主要 Renderer、跨环境字节确定性和完整生成工程离线编译均有直接契约，并有生产 class 静态边界闸门 | 维护 |
| Project Graph | 72 | 四类边、规则矩阵、canonical 序列化/加载/摘要往返、只读边界闸门与不可信字节版本/来源类型/不可编码标量守卫均有直接契约；`REFERENCES` 与增量能力不在范围 | 维护 |
| Change | 22 | 有 API 与架构测试，操作族/closure/失败矩阵不足 | P1 |
| Application | 133（0 fail）+ 5 skip | 核心路径部分覆盖，conformance 整包排除；Q3 已修复 5 项软链接断言失败 | P0 |
| CLI | 18 | 5 项 hardening + 13 项 Change 工作流（Q3 前整类类级跳过） | P1 |

## 2. Generator 缺口

Q1A 已建立的直接证据：

- `campus-market.sir` 的完整、有序文件路径契约，覆盖 POM、Application、Enum、Entity、Mapper、DTO、Exception、Service 和 Controller。
- `unit-output.sir` 与 `compound-find.sir` 的最小输出外形。
- 生成路径唯一、内容非空且以 LF 结束、project/declaration ownership metadata 和 LoweredNodeId 唯一。
- 同一个 Lowered model 连续两次生成时，路径、顺序、内容和 metadata 完全相等。

Q1B1 已建立的直接证据：

- `pom.xml` 直接验证 Lowered project 坐标、Java 21、冻结依赖版本与 scope、依赖顺序、Spring Boot Maven plugin、LF 和文件结尾。
- `Application.java` 直接验证 Lowered package、类名、mapper scan package 和 main entry point。
- Enum 直接验证 package、Lowered Java 类型名、完整成员集合和成员顺序。
- Mapper 直接验证 owner Entity import、`BaseMapper<实体>` 绑定以及空方法体。
- Exception 直接验证 Lowered `*Exception` 名、`BAD_REQUEST`、`RuntimeException` 和无参 `super()` 构造外形。
- 五类测试首次真正执行即通过，未触发生产 Renderer 修改。

Q1B2 已建立的直接证据：

- Entity 直接验证 package、类名、表名、字段和访问器顺序，以及 AUTO/UUID identity 对应的 `IdType.AUTO` / `IdType.ASSIGN_UUID`。
- Entity 类型矩阵覆盖 Boolean、Int32、Int64、Decimal、String、Uuid、Date、DateTime、Optional、List 和声明 Enum；`Ref<Entity>` 只使用 Lowered IR 已决定的 identity 存储类型、`*Id` Java 字段名和 `*_id` 列名。
- Entity 即使来源字段带约束，也不擅自获得请求 DTO 的 Jakarta Validation 注解。
- DTO 直接验证字段顺序、boxed Java 类型、声明类型 import、容器类型和 getter/setter。
- DTO 直接验证 `notBlank`、`email`、`length`、`min`、`max` 的注解、import、参数文本、顺序和字段归属。
- 真实 Parser → Semantic → Lowering → Generator 路径发现并修复负数 constraint argument 在 Normalize 阶段丢失的问题；Semantic 回归测试证明 `min(-2)` / `max(-1)` 的 unary 参数完整保留，Generator fixture 证明 `-10.50` 能到达 `@DecimalMin("-10.50")`。
- 五项 Generator 契约全部通过；未修改 Entity、DTO 或 Type Renderer。

Q1B3A 已建立的直接证据：

- Service 直接验证 package、`@Service`、`@Transactional` / `readOnly = true`、actor/input 方法参数和 Unit/Value/Entity/Optional/List 返回类型。
- Mapper 字段、构造器参数和赋值按首次 workflow 使用顺序去重；同一 Entity 经 Load、Persist、Find 只注入一个 Mapper。
- Create、Load、Update、Persist、Validate、Find、Return 的关键 Java 语句保持 workflow 顺序；INSERT 与 UPDATE 使用各自 Lowered persistence action。
- 复合 Find 直接冻结 AND/OR/NOT 的嵌套 consumer 分组、item getter 和 input 参数绑定，不只检查方法名存在。
- 四项新契约首次到达真实生成结果时有三项因测试预期使用未 Lowering 的异常名、过宽计数和错误 consumer 编号而失败；修正断言后全部通过，未修改 Service、Workflow、Expression 或 ResponseType Renderer。

Q1B3B 已建立的直接证据：

- Controller 直接验证 package、类名、`@RestController`、`/api/<kebab-name>` route、POST/GET mapping 和 Service 构造器注入。
- actor capability 直接验证 `@RequestAttribute("actorId") Long actorId` 来自 Lowered `ActorBinding`，并在 input 之前原样传给 Service，不访问 actor 非 identity 成员。
- POST constrained input 生成 `@Valid @RequestBody`；GET unconstrained input 生成 `@ModelAttribute` 且不生成 `@Valid`；无 input 时不生成三类 request 参数注解。
- Unit response 只调用 Service、不返回 void 调用；Value、Entity、List、Optional response 均以 Lowered 类型返回 Service 结果。
- 四项新契约首轮为 4 run、3 failure、0 error、0 skip；三项失败都来自测试 route 预期遗漏 Lowering 已冻结的 `/api` 前缀。对照 Lowerer 和既有 integration test 修正断言后全部通过，未修改 Controller 或 ResponseType Renderer。

Q1C 已建立的直接证据：

- test-only probe 在两个不同物理工作目录启动独立 JVM，分别使用 `en-US` + `ISO-8859-1` 和 `tr-TR` + `UTF-8`；完整文件路径、顺序、内容、artifact owner、可选 SymbolId 经长度前缀 canonical SHA-256 后完全一致。
- `campus-market.sir` 通过显式 UTF-8 从 classpath 读取；每个生成文件均无 CR、以单个 LF 结束，并通过显式 UTF-8 encode/decode round-trip。
- Java string escaping 直接覆盖引号、反斜杠、tab、LF、CR 和 U+0001；XML escaping 覆盖引号、`&`、单引号、`<`、`>`。
- 首轮 4 项测试中，独立 JVM 矩阵、LF/UTF-8 和 XML escaping 通过；Java escaping 因 U+0001 被原样写入源码而得到 1 个有效 failure。
- `StringEscape` 现在把未专门处理的 ISO control character 渲染为固定小写四位 Unicode escape；定向和完整 Generator reactor 随后全部通过。

Q1D 已建立的直接证据：

- `GeneratedProjectOfflineCompilationTest` 直接使用 `GeneratorTestSupport.generateSuccess("valid/campus-market.sir")` 的 11 个当前输出文件，在 JUnit 临时目录按相对路径和显式 UTF-8 物化，并逐文件读回比对。
- 独立 Maven 子进程以生成工程自己的 `pom.xml`、Java 21、`--offline`、显式 `D:\maven-repo` 和 `-DskipTests compile` 运行；没有下载依赖、启动 Spring Boot 或连接数据库。
- 子进程退出码为 0；Application、Entity、DTO、Mapper、Service 和 Controller 的代表性 `.class` 文件均真实存在。
- 首次真正到达生成工程编译即通过，未触发任何生产 Renderer、POM、Lowered IR 或依赖版本修改。
- 全量离线 Reactor 中 Generator 为 32 run、0 failure、0 error、0 skip；全工程为 373 run、0 failure、0 error、10 skip。

Q1 生产边界闸门建立的直接证据（2026-09-18，`GeneratorProductionBoundaryTest` 6 项）：

- test-only constant pool reader 解析 `target/classes` 下全部 37 个生产 class 的 `CONSTANT_Utf8`，并把每条项分类为 `CLASS_NAME` / `STRING_LITERAL` / `OTHER`；非法 magic、截断数据和未知 tag 立即失败并报告 class 文件与偏移。
- 禁止引用扫描 0 违规：生产 class 不引用 `io/kcg/sir/{ast,api,internal,source}`、`io/kcg/sir/semantic/{api,model}`、`SymbolTable`、`java/nio/file/`、`java/io/`、`java/lang/System`、`java/time/`、随机源或 `java/util/UUID`；唯一放行项是精确的 `io/kcg/sir/semantic/symbol/SymbolId`（`GeneratedFile` ownership metadata）。
- 公开入口反射：`public final SpringBootGenerator` 只有 `generate(SpringBootLoweredModel)` 返回 `GenerationResult`，只有一个无参构造器，声明表面不含编译器输入或文件系统类型。
- 闸门灵敏度由 test-only 违规探针与临时 census 双重证明：JDK 规则和 Parser/Semantic 规则都会真实报出违规，报告按 class → rule → pool index 确定性排序，排除空扫描与“永远返回空集合”的假绿。
- 该闸门建立于既有正确行为之上，首轮就是 0 违规；测试自身的 census key 常量错误曾被修正，但那是测试侧缺陷，不是生产 RED。

仍无直接测试的 Generator 行为（不得记为本阶段已覆盖）：运行时行为、反射逃逸、生成 Java 文本正确性，以及不在已批准禁止集合内的 `io/kcg/sir/semantic/{context,type,internal}`。

## 3. Project Graph 覆盖与缺口

2026-09-18（Q2）已建立的直接证据（`sir-project-graph` 67 项，详见资格文档 1.4）：

- 模块内手写 fixture 构造 35 节点 / 34 边的 campus-market 形状图，不依赖 Application 模块。
- DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE 四类边的成功侧与违规侧：端点种类、自环、悬空、重复身份、arity 限制。
- `NODE-001`～`NODE-004`、`EDGE-001`～`EDGE-014`、`PATH-001`、`PATH-002`、`ORDER-001`、`ORDER-002`、`VERSION-001`、`PROVENANCE-002`～`PROVENANCE-007` 的精确诊断码断言。
- canonical 序列化 → 加载 → 再序列化字节相等；摘要与 canonical bytes 对输入顺序无关，重复构建字节相等。
- 13 类畸形/非规范文档 fail closed，返回结构化诊断而不抛运行时异常。
- 只读边界：生产 census 69 个 class 的常量池扫描 0 违规；`java/io` 与 `System` 成员按复核清单逐项放行；`io/kcg/sir/*` 引用按允许清单校验；加载器只有 `load(byte[])` 与 `load(CanonicalProjectGraphDocument)`。
- 不可信字节上的守卫（追加 5 项）：文档 `provenance.kind` 谎报 → `COMPAT-010` fail closed；文档声明 `graphVersion=V0_2` → `COMPAT-002` fail closed；编码器收到非 V1 格式版本 → `COMPAT-001` fail closed；不可编码 Unicode 标量 → `SERIALIZE-001` fail closed；版本枚举单值绊线。

仍缺直接覆盖、不得记为已覆盖：

- `SIR-GRAPH-PROVENANCE-001`（公开构造器编译期强制 + 解码器 `COMPAT-010` 双重堵死，仅有反射层面的类型保证证据，可达替身 `COMPAT-010` 已直接覆盖）。
- `SIR-GRAPH-VERSION-002` 与序列化器的 `SIR-GRAPH-COMPAT-001`（枚举单值，公开 API 不可达；绊线测试会在新增版本值时失败）。
- 本轮未发现其他**可达**而未覆盖的码：Q2 范围内所有可触发的诊断码都有直接断言。
- `REFERENCES` 边、Graph service、Snapshot V2、增量索引。
- 运行时行为与反射逃逸；边界闸门只证明静态引用。
- Application 级“Graph failure 在写盘前发生”与真实集成链证据仍来自既有 `sir-toolchain-application` 测试，不是本模块新增。

本阶段不增加 `REFERENCES`、Graph service、Snapshot V2 或新的持久化协议。

## 4. Change 与 fixture 缺口

**状态：2026-09-18 Q3 已补齐（见 `docs/roadmap/ACTIVE_WORK.md` 与资格文档 1.5）。** 原先阻断两个测试类的资源已建立，位置为 `kcg-cli/src/test/resources/io/kcg/cli/`（12 个）与 `sir-toolchain-application/src/test/resources/valid/campus-market-candidate.sir`：

| fixture | 精确差异 | 对应 ChangeOperation / target |
|---|---|---|
| `campus-market-candidate.sir` | base 仅第 55 行：`validate input.price >= 0.01` → `> 0.0` | `ModifyCapabilityWorkflow`；`CAPABILITY_WORKFLOW` PublishGoods |
| `campus-market-candidate-modify-input-field-constraints.sir` | `PublishGoodsInput.title` 加 `length(1, 80)` | `ModifyInputFieldConstraints`；`INPUT_FIELD` PublishGoodsInput/title |
| `campus-market-candidate-add-capability.sir` | 新增 actorless `SearchGoods` | `AddCapability`（V0_2）；`CAPABILITY_WORKFLOW`/`CAPABILITY_DECLARATION` SearchGoods |
| `campus-market-two-capabilities.sir` | actorless `PublishGoods` + `SearchGoods` | 作为 v0.3 base |
| `campus-market-two-capabilities-remove-publish-goods.sir` | 仅删除 `PublishGoods` 能力块 | `RemoveCapability`（V0_3）；`CAPABILITY_WORKFLOW` PublishGoods |
| `v05-base.sir` / `v05-candidate.sir` | `PublishGoodsInput.unusedTag` `String` → `Int64`（该字段无任何 workflow 引用） | `ModifyUnreferencedInputFieldType`（V0_5）；`INPUT_FIELD` PublishGoodsInput/unusedTag |
| `campus-market-actorless-readonly-base.sir` / `-candidate.sir` | `expose query` → `expose command` | `ModifyActorlessReadonlyCapabilityExposure`（V0_6）；`CAPABILITY_DECLARATION` |
| `campus-market-minimal.sir` / `-add-search-goods.sir` | 单实体 + actorless readonly 查询；candidate 新增 `SearchGoods` | `AddCapability`（V0_2） |

`v01-c.sir` 至 `v06-c.sir`、`inc-c.sir`、`sc-a/b.sir` **不是独立文件**：它们是 `KcgCliWorkflowTest` 用 `writeSir(本地名 ← 共享资源)` 产生的临时副本名，不需要单独提供。

已建立的证据：`ChangePlanningApplicationTest` 9/0/0/0（原 3 run/6 skip），`KcgCliWorkflowTest` 13/0/0/0（原整类 13 skip）。

仍属缺口（本工作单未做）：

- Change 操作族的**跨版本组合矩阵**（每个操作 × 错误版本/错误 target/scope/impact/closure 的完整负例集）。
- 完整的 `SIR-CHANGE-*` 诊断矩阵。
- 两个能力都带 actor 时移除其一（会重写 `Application.java`，`IMPACT-202`）的专门用例尚未建立。

今后新增 fixture 时必须继续遵守：

1. 为每个 base/candidate 对说明精确差异和目标 ChangeOperation。
2. 使用当前 SIR/Change IR 契约设计，不根据占位名称猜测未定义语义。
3. 覆盖成功、错误版本、错误 target、scope、impact、closure、重复和冲突。
4. 使用 SymbolId/AstNodeId 精确定位，不引入名称模糊匹配。

## Q6 故障矩阵覆盖（2026-09-18）

| 项 | 已覆盖 | 未覆盖/边界 |
|---|---|---|
| APPLY（UPDATE）事务 | 18 个可注入点（事务目录→journal→暂存→bundle→备份→提交→发布→CURRENT→清理）+ 2 个跟进用例（补偿回滚、清理故障暴露）+ 覆盖率守卫 | — |
| CREATE 事务 | 15 个正向点 + 4 个目录点（专用 fixture）+ 补偿分支用例 | — |
| DELETE 事务 | 16 个正向点 + 4 个回滚点（按两条回滚分支参数化） | — |
| 方向语义 | 三族一致：B0 段全补偿、B1 前窗口 `RecoveryRequired`、B1 后不复旧字节 | — |
| 模糊态 | 未知 CURRENT、缺 CURRENT → recovery 拒绝且证据不变 | CURRENT 与 journal 声明不一致的其余组合仍由 `RecoveryStateMachineTest` 以构造状态覆盖 |
| DELETE 硬链接 | hook 内观测 `isSameFile`/`nlink>=2`/同 store | 跨卷仅证明到"注册阶段拒绝"；进入删除阶段的链接失败路径不可构造 |
| 平台 | Linux 单文件系统（overlay） | Windows junction 5 项按设计 skip |

## 5. Application conformance 缺口

`sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance` 当前有 46 个文件，但整个包被 compiler 和 Surefire 排除。

已知不完整点至少包括：

- `ConformanceSuite.java` 的 suite 编排尾部不完整。
- `SpringBootTargetConformanceIT.java` 的场景执行闭包不完整。
- `EvidenceWriter.java` 不是可用实现。
- `MysqlControlSession`、`ManifestTransactionVerifier`、`EvidenceSecretScanner`、`RealRuntimeOps` 等包含占位或缺失方法尾部。

移除 POM 排除前必须先达到：

1. 46 个文件全部参加 testCompile。
2. 默认构建不要求真实 MySQL、凭据或开放端口。
3. 外部资格只通过显式 opt-in 启动。
4. 凭据脱敏、owned path、schema cleanup、advisory lock、进程退出和 fail-closed 规则有直接测试。
5. 没有外部环境时报告 `NOT_RUN`，运行开始后的失败报告 `FAILED`，不得降级成 skip。

## 6. 文件事务与显式恢复缺口

当前已有路径非法字符、Windows 保留名、junction/reparse point、manifest 重复路径和部分 DELETE B0/B1 状态测试。

仍需直接覆盖：

- UPDATE、CREATE、DELETE 在 CURRENT 发布前后的完整故障注入矩阵。
- Bundle 发布前后、Journal seal/close 前后、staging/backup 清理阶段。
- `CURRENT=B0` 只向后补偿；`CURRENT=B1` 只向前验证/清理。
- CURRENT、Bundle、Journal 或物理文件身份不匹配时 fail closed。
- 第二卷或卷挂载点下 same-volume 与 `isOther` 行为。
- 不能补偿时保留足够事务证据并返回 `RECOVERY_REQUIRED`。

## 7. CLI 缺口

当前正式 CLI 只包含 `context` 和 `plan`。`KcgCliWorkflowTest` 的 13 项（Q3 起真实执行）已覆盖这两个命令的：v0.1–v0.6 操作族、`NO_CHANGES`、版本与操作不兼容、stale candidate、错误 outputRoot、未知 target key、活动 journal → exit 4、context JSON 确定性。

如果后续接受 ADR-019 的完整本地生命周期，需要独立覆盖：

- `generate`、`register`、`context`、`plan`、`apply`、`recover` 参数和 help。
- canonical JSON、退出码、NoChanges、rolled back、recovery required。
- candidate digest/context/output drift。
- exact recovery handle 与 cross-JVM lock contention。
- CLI 不直接访问 Bundle、CURRENT、LOCK、Journal。
- 完整 generate → register → context/plan → apply → recover 流程。

## 8. 资格更新规则

- 测试数来自当次 Surefire XML，不从旧报告复制。
- 被 POM 排除的包不计为通过。
- assumption/class-level skip 单独列出；**类级 `@BeforeAll` assumption 会使方法不计入 Surefire 的 run/skip 统计**（`KcgCliWorkflowTest` 曾因此看起来只有 5 项测试），因此应当用逐测试 assumption。
- 外部资格只有真实运行成功才写 `QUALIFIED`。
- 新测试以当前契约为准，不要求复刻历史类名或凑齐历史数字。
