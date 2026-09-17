# 第四步：数据库新建、保留数据更新与恢复

日期：2026-09-08。状态：推荐设计草案。依赖[共享契约](00-shared-contracts.md)与[编译模型](03-compiler-and-generated-backend.md)，由[本地部署设计](05-source-package-and-docker-deployment.md)调用。

本设计以专用本地 MySQL/InnoDB、单个业务应用、停写升级为首轮环境。平台产出迁移定义，随包执行器在用户本地核验和执行。没有访问真实数据库，也没有完成迁移故障测试。

**实现边界：**本章 INITIALIZE/UPDATE、MigrationDefinition、数据库 history/漂移核验与恢复均为尚未实现的目标闭环。当前生产 Application 的 CREATE/UPDATE/DELETE 操作工程文件；conformance 中的建库/SQL 辅助代码是测试设施，不能算迁移产品。G3 依赖 G2 的持久身份与物理映射，通过 DB-01..11 后才具备数据库更新资格；G4 再将其封装到 Docker 交付。[实现依据](implementation-baseline.md)

文件 Bundle/CURRENT、不可变源码 Release、数据库 schema/history、部署收据是四组独立事实。文件 CURRENT=B1 不授权执行 SQL，不证明迁移完成；数据库 DDL 已提交后也不得通过文件 CURRENT 回退假装数据库回滚。本章 UPDATE 均指数据库更新，不包含业务记录更新或文件 UPDATE。

## 1. 保留数据的承诺

UPDATE 保留原有行身份、行数、关系及受保护字段值。受支持操作只允许添加结构、兼容放宽、通过数据检查的新增约束，以及明确声明的有限回填。不得靠 DROP、TRUNCATE、DELETE、覆盖非空旧值或有损转换达到目标模型。

受控回填只覆盖新列或声明允许处理的 null 槽位，不是开放任意数据清洗。业务接口中显式授权的记录删除不属于 schema 更新，但迁移期间必须停止业务写入。

发现旧数据不满足新约束时返回阻断，不自动删除重复行、不把异常值改成默认值。备份不能使默认保留数据策略失效；物理表列删除、改名和复杂拆合表需要未来独立能力。

## 2. 新建与更新入口

| 条件 | INITIALIZE | UPDATE |
| --- | --- | --- |
| 目标来源 | 用户明确创建的新本地实例，已有 schema 必须为空且归属可证明 | 明确的 projectId + environmentId 及旧部署绑定 |
| 数据卷 | 新建前检查不存在冲突实例/卷 | 所有持久卷必须已经存在，不允许自动创建 |
| 发布基线 | 不需要已部署 releaseId | 必须处于包的 acceptedBaseReleases，或已精确部署该包 |
| schema history | 从空库执行冻结完整历史 | 必须匹配已登记的历史前缀及旧 schema |
| 重复执行 | 同一次初始化已成功且证据一致时返回既有结果 | 同版本同摘要已部署且现场一致时返回已完成 |
| 缺失/矛盾证据 | 拒绝接管非空未知库 | BLOCKED 或 RECOVERY_REQUIRED，不转成 INITIALIZE |

首轮 acceptedBaseReleases 只包含直接父发布。代码变化但 schemaVersion 不变的更新仍需核验 projectId、旧 releaseId、历史和结构；它可以没有新增迁移，不能因此忽略部署身份。

新安装最新版重放所有已发布迁移。平台不给不同新用户生成内容不同的 V1，已发布脚本和回填定义永不回写。

## 3. 三种快照与物理映射

旧模型来自父发布的 Lowered schema，候选模型来自本次 Lowering。每个表、列和约束关联稳定 SymbolId，物理名称从父发布继承；新增对象才按固定命名规则分配名称。

现场快照来自数据库内省，包含受管理的表列、类型、可空性、默认值、索引、外键、字符规则和引擎。它用于验证旧模型，不用于反向猜出新 SIR。数据行、AUTO_INCREMENT 当前计数、更新时间和优化器统计不参与结构指纹。

首轮管理专用 schema，不接受未知业务表、触发器或存储过程混入。Flyway history 和部署元数据表是明确的系统对象，单独校验其契约，不混入业务 schemaDigest。这样可识别手工 ALTER、额外级联行为及环境偏差。

结构指纹由规范化的类型化快照计算，不直接 hash SHOW CREATE TABLE 的格式字符串。索引和外键以完整定义核验；同名不等于同一约束。

## 4. 迁移规划规则

| 操作 | 首轮支持条件 | 输出步骤与拒绝理由 |
| --- | --- | --- |
| 新表 | 新稳定身份、无物理名冲突 | 创建表；关系依赖在适当阶段建立 |
| 新可空列 | 无同名冲突，类型受支持 | AddNullableColumn |
| 新必填列 | 有明确有界回填定义 | 可空新增 -> 回填 -> ValidateNoNull -> SetNotNull |
| 放宽可空 | 不改变已有值 | DropNotNull |
| 同类类型扩大 | Profile 验证过的容量包含关系 | WidenColumn；跨类型及精度损失拒绝 |
| 新普通索引 | 列存在，类型和长度受支持 | AddIndex；锁超时为执行失败，不删数据重试 |
| 新唯一约束 | 精确比较规则下无重复且语义受支持 | 检查后 AddUnique；首轮唯一键成员均要求非空 |
| 新外键 | 无孤儿记录，目标键与基数兼容 | 检查后 AddForeignKey，默认 RESTRICT |
| 修改新记录默认值 | 不回写旧记录 | ChangeDefault |
| 收紧非空/范围约束 | 旧数据全部符合，或允许的 null 回填已明确 | 前检、回填或约束步骤；不能任意修旧值 |
| 逻辑改名 | 保留 SymbolId 和旧物理名 | 更新目标代码/API 映射，无物理 Rename |
| 删除、物理改名、有损转换 | 不支持 | 规划阶段失败，不发出对应 SQL |

首轮回填表达式限定为类型兼容的常量或从同一行已有字段直接取值，不执行任意函数、跨表查询或外部请求。不满足时要求把新字段保持可空或另行设计变更，不能由 Agent 填任意 SQL。

业务规则收紧而没有 DDL 的版本也需要对受影响旧记录检查。首轮只支持可编译为有限行内检查的兼容性条件；无法判定的变更不能标记为“旧数据兼容已验证”。

## 5. MigrationDefinition v1

顶层包含 formatVersion、projectId、fromSchemaVersion、toSchemaVersion、前后结构指纹、dataPolicy、migrationChainDigest 和有序 steps。无 schema 变化也保留明确的前后契约。

每一步至少包含：

| 字段 | 内容 |
| --- | --- |
| stepId / migrationVersion | 稳定步骤身份与历史中的唯一顺序 |
| kind | 受支持操作枚举，不接受自由 SQL 操作名 |
| affectedSymbols / physicalTargets | 稳定语义身份与精确表列映射 |
| sqlPath / sqlDigest | Generator 输出的冻结脚本引用与字节摘要 |
| beforeConditions / afterConditions | 类型化结构/数据检查，不是任意脚本 |
| preservationScope | 被保护的旧行键、字段与允许回填的范围 |
| executionClass | SINGLE_DDL、TRANSACTIONAL_DML 或 READ_ONLY_CHECK |
| retryClass | VERIFY_THEN_SKIP、TRANSACTIONAL_RETRY 或 MANUAL_RECOVERY_ONLY |

DDL 原则上一个原子结构语句对应一个迁移单元；只读检查不伪装成 Flyway 版本；回填 DML 自身使用事务并检查影响范围。枚举 retryClass 只是提示允许的判定，不授权忽略 history 或直接重跑。

执行器核验类型化操作与预期脚本渲染结果的一致性，拒绝声明为 AddColumn 却携带 DROP 的输入。它能按固定规则验证已有 SQL，但不根据现场自行发明新迁移。校验规则与平台验证使用相同协议和测试向量，源码随交付包提供。

## 6. history、元数据与执行证据

Flyway 按版本与校验和管理已应用迁移，已发布迁移只追加新版本。[Flyway 版本迁移](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html) 本设计不把 Flyway history 当成完整的项目身份或数据漂移证据。

建议数据库拥有一份独立的部署元数据：projectId、environmentId、schemaVersion、schemaDigest、migrationChainDigest、dbStatus、activeAttemptId。它由最小执行器维护，业务账户只读必要的启动验证字段，不拥有 DDL 权限。

另在独立本地状态卷保存执行意图、冻结计划、每步前后摘要与结果。它不与用户下载目录绑定，也不会随一次性容器删除。元数据协议格式与业务 schema 版本分开，首轮固定协议 v1，不在业务更新中自动升级协议。

执行记录采用先持久化意图、再执行、再持久化结果的顺序。需要持久化屏障的记录不能只留在进程缓冲中；本地写入应有原子发布、校验与重启可读测试。记录保存数据摘要与计数，不保存密码或完整业务行。

## 7. UPDATE 的执行顺序

1. 部署入口取得该环境独占协调权，检查包摘要、旧部署实例与卷存在。先构建新镜像，构建失败保持旧应用运行。
2. 停止管理范围内的旧应用写入。首轮只承诺专用实例且没有外部写入者；连接到外部共享库不属于默认自动路径。
3. 执行器取得数据库迁移锁，重新读取 projectId、environmentId、history、结构及 dbStatus。
4. 验证旧 releaseId 在 acceptedBaseReleases，历史及结构完全匹配，全部可提前验证的数据条件通过。
5. 持久化本次计划与受保护数据的基线摘要，登记 activeAttemptId，置 dbStatus=MIGRATING。
6. 每步执行前核验该步前置条件和脚本摘要；通过 Flyway 或受控检查执行步骤，随后核验后置条件并记录结果。
7. 全部步骤成功后核验完整目标 schema、完整 history 和数据保留不变量。
8. 事务性发布数据库元数据为目标 schemaVersion、READY，写成功收据。
9. 部署入口启动目标应用并检查健康状态；应用成功后单独发布本地已部署 releaseId。

在第 5 步之前失败通常是 BLOCKED；迁移开始后不得把未知状态降级成普通重试。锁持有期间仍需检查真正的目标身份；锁不能替代外部写入隔离。

## 8. 数据保留验证

对受影响旧表，以不可变主键排序流式计算受保护字段的类型化摘要，记录行数与主键集合摘要。值编码包含字段身份、类型、null 标记和精确值，避免把 null、空串、0 或不同小数精度混为一谈。

新列不进入旧字段摘要；显式允许 null 回填的槽位单独记录满足条件的键集合及预期结果，不允许对该集合之外写值。校验关系边和旧字段；不能仅凭行数不变宣布保留数据成功。

课程规模默认对相关表完整检查，失败时停止部署并保留现场。后续若数据量大到需要分批校验，应单独设计快照和停写策略，不能用抽样替代保留数据承诺。

检查数据不满足约束与检测到迁移实际破坏数据是不同错误：前者 BLOCKED，后者 DB_RECOVERY_REQUIRED，并标记该迁移/Profile 不应继续分发，等待修复与重新验证。

## 9. 部分失败与恢复矩阵

MySQL atomic DDL 不等于多条 DDL 可以包进一个可回滚事务。[MySQL atomic DDL](https://dev.mysql.com/doc/refman/8.4/en/atomic-ddl.html) 恢复必须按证据逐步判定，不能拿平台 CURRENT 或文件时间猜测数据库版本。

| 故障点 | 可证明的状态 | 允许处理 |
| --- | --- | --- |
| 前检失败、未写迁移意图 | 旧 schema/history 不变 | BLOCKED，说明原因；旧版是否重启需重新确认旧契约 |
| 意图已写、无 SQL 执行 | 数据库与旧基线精确一致且无在途语句 | 显式 recover 关闭本次尝试或按原计划继续 |
| 一步成功且 history/后检完整 | 已完成冻结前缀 | 显式 recover 验证并跳过已完成步骤，从下一步继续 |
| 回填失败且事务回滚可证明 | 该步数据未改变，但可能有失败 history | history 干净且前提一致才允许受控重试；否则需恢复 |
| DDL 已提交但 history/结果不完整 | 物理结构与执行证据不一致 | DB_RECOVERY_REQUIRED；首轮不自动 repair history |
| 全部 history 成功，最终元数据未发布 | 完整目标 schema、数据及 history 可验证 | 显式 recover 完成目标基线发布，不重跑 SQL |
| 数据库 READY，应用启动失败 | 数据库更新成功 | deploymentStatus=FAILED；保留新数据库，诊断应用；不回滚数据库 |
| 目标身份错误、未知结构、记录损坏 | 无法建立可信基线 | 停止，保留所有证据；不进行方向猜测 |

Flyway 记录失败或有缺失校验和时，不自动 clean、repair、改 history 或替换旧脚本。首轮恢复能力可以停在有证据的阻断；不能声称任意 DDL 失败都能自动修复。需要修历史的恢复另立契约，完成故障测试后才能开放。

恢复也要重新获取环境锁和数据库锁，确认没有执行器仍在运行。原尝试结束证据不明时，不能两个执行器并行补做同一步。恢复产生新 deploymentAttemptId 并引用旧尝试，不覆盖旧日志。

## 10. 初始化中断

初始化同样记录环境身份与尝试。出现系统表已创建、业务表部分创建时，后续入口只能沿原 INITIALIZE 尝试恢复，不能因为业务基线尚未 READY 就当成一个新的空库。

既无本地身份记录又存在业务表时，不自动 baseline 或接管。新包初始化到最新版需要验证完整迁移链的顺序、约束和最终状态，不能以空库没有旧数据为由跳过历史完整性检查。

## 11. 执行接口

建议最小执行器提供 check、initialize、update、status、recover 五类 typed request，包含 releaseManifest 路径/摘要、环境身份与操作意图。密码从挂载的本地 secrets 读取，不从 SIR 或命令行明文读取。

check 返回兼容性与计划摘要，不做结构写入；update 对检查结果进行新鲜度复核，不信任长时间前的计划。runId 和环境身份在整个请求中保持一致，重复调用按收据和现场判定，不靠“上次退出码是 0”推断完成。

计划和收据的未知格式版本直接拒绝。用户错误输出可读原因、目标标识和下一步建议；技术细节存入本地可导出的脱敏诊断，不自动上传业务数据。

## 12. 本步验证矩阵

必须覆盖：空库首建、重复首建、连续两次 UPDATE、代码-only 更新、新增列与回填、重复/孤儿数据、null 与 Unicode/Decimal 边界、结构漂移、错误项目/实例、缺失卷、脚本改写、DDL 部分提交、最终元数据发布中断和应用启动失败。

每个用例记录前后 schema、history、元数据状态、旧记录字段摘要及执行结果。故障注入位置来自执行协议，而不是只模拟一个笼统 SQLException。测试必须使用 Profile 指定的真实 MySQL，并验证任何恢复都未走清库路径。

完成门是支持矩阵内的迁移可验证，矩阵外的迁移被明确阻断，以及不可确定的失败保留现场。它不是“所有 DDL 都能自动回滚”。
