# KCG-Code 测试覆盖与缺口清单

> 更新日期：2026-08-11
> 用途：记录当前可执行测试、明确缺口和后续验收输入；不以历史测试数量作为完成目标

## 1. 当前覆盖摘要

| 模块 | 当前直接测试 | 覆盖判断 | 优先级 |
|---|---:|---|---|
| Parser | 43 | 核心语法、AST、诊断和确定性有直接覆盖 | 维护 |
| Semantic | 103 | Resolve/Type/Validate/Normalize 与 typed reference-site 有系统覆盖 | 维护 |
| Lowering API + Spring | 36 | API、Profile、边界、确定性和 hardening 有直接覆盖 | 维护 |
| Generator | 23 | 已有 canonical 输出、简单 Artifact、Entity/DTO 及 Service/Workflow 直接契约；Controller transport、环境确定性与离线编译仍不足 | P0 |
| Project Graph | 0 | 只有 Application 间接经过，缺直接契约测试 | P0 |
| Change | 22 | 有 API 与架构测试，操作族/closure/失败矩阵不足 | P1 |
| Application | 132 + 10 skip | 核心路径部分覆盖，conformance 整包排除 | P0 |
| CLI | 5，另 13 方法类级 skip | hardening 有覆盖，完整工作流未执行 | P1 |

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

仍必须新增的行为矩阵：

- Controller 的 HTTP 映射、actor identity transport、input binding、response delegation 与 Service 调用。
- 不同默认 Locale 下输出一致。
- import 排序、换行、字符串转义和大小写稳定。
- Generator 只消费 Lowered IR，不访问 AST、SIR、SymbolTable 或磁盘。
- 至少一个完整生成工程在冻结依赖下离线编译。

## 3. Project Graph 缺口

当前必须新增的直接测试：

- DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE 四类当前边。
- 节点/边 ID、顺序、去重、缺失端点和重复身份拒绝。
- 公开集合不可变。
- canonical serialization、load、digest 与不同 Locale/工作目录下的确定性。
- Graph builder 不访问文件系统，不按名称重新解析。
- Application Graph failure 在写盘前发生并返回 `NO_CHANGES`。
- 一条 Parser → Semantic → Lowering → Generator → Graph 的真实集成链。

本阶段不增加 `REFERENCES`、Graph service、Snapshot V2 或新的持久化协议。

## 4. Change 与 fixture 缺口

当前缺少或没有可审查完整内容的 base/candidate 资源包括：

- `campus-market-candidate.sir`
- `campus-market-minimal.sir`
- `campus-market-two-capabilities.sir`
- `v01-c.sir` 至 `v06-c.sir`
- CREATE、DELETE、workflow 修改、字段约束和未引用字段类型变更对应 candidate

这些资源同时阻断 `ChangePlanningApplicationTest` 和 `KcgCliWorkflowTest`。

新增 fixture 时必须：

1. 为每个 base/candidate 对说明精确差异和目标 ChangeOperation。
2. 使用当前 SIR/Change IR 契约设计，不根据占位名称猜测未定义语义。
3. 覆盖成功、错误版本、错误 target、scope、impact、closure、重复和冲突。
4. 使用 SymbolId/AstNodeId 精确定位，不引入名称模糊匹配。

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

当前正式 CLI 只包含 `context` 和 `plan`。如果后续接受 ADR-019 的完整本地生命周期，需要独立覆盖：

- `generate`、`register`、`context`、`plan`、`apply`、`recover` 参数和 help。
- canonical JSON、退出码、NoChanges、rolled back、recovery required。
- candidate digest/context/output drift。
- exact recovery handle 与 cross-JVM lock contention。
- CLI 不直接访问 Bundle、CURRENT、LOCK、Journal。
- 完整 generate → register → context/plan → apply → recover 流程。

## 8. 资格更新规则

- 测试数来自当次 Surefire XML，不从旧报告复制。
- 被 POM 排除的包不计为通过。
- assumption/class-level skip 单独列出。
- 外部资格只有真实运行成功才写 `QUALIFIED`。
- 新测试以当前契约为准，不要求复刻历史类名或凑齐历史数字。
