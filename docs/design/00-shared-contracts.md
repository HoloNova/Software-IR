# 共享契约：身份、版本、产物与执行状态

日期：2026-09-08。状态：推荐设计草案。返回[文档入口](README.md)。

本文件定义各详细设计共同使用的概念。以下类型与字段是推荐接口，不是当前仓库 API。命名固定到足以编写下一步接口设计；没有把任意名称相似性当成语义身份。

**实现边界：**当前已有单源编译身份、Lowered 模型、GeneratedFile 和本地文件 Bundle/CURRENT；本表的持久 declarationId、多文件 SourceSnapshot、Migration/Release/Deployment 对象为目标扩展。当前 SymbolId 含名称，不能套用下面的目标构成；差异与证据见[实现对照](implementation-baseline.md)。目标阶段统一为 G0–G7，见 07。

## 1. 身份与版本

| 名称 | 含义与生成时点 | 是否随显示名称变化 |
| --- | --- | --- |
| projectId | 网页创建项目时确定并持久化的项目身份 | 否 |
| draftRevision | 每次保存草稿成功后递增的修订号 | 仅内容保存时递增 |
| sourceSnapshotId | 一个不可变 SIR 文件集及模块锁定信息的摘要身份 | 内容输入变化时改变 |
| moduleDefinitionId / moduleVersion | 受控模块定义和不可变版本 | 不以模块标题判断 |
| moduleInstanceId | 模块在某个项目中的实例身份 | 否 |
| declarationId | SIR 声明持久化的局部身份 | 否 |
| SymbolId | projectId、moduleInstanceId、declarationId 的结构化组合 | 否 |
| releaseId | 项目发布的单调序号，例如 r0001；失败的预留号可以空缺 | 否 |
| parentReleaseId | 新包明确继承的唯一已发布版本；首版为空 | 否 |
| schemaVersion | 每个发布声明的目标数据库结构版本，例如 s0001 | 无结构变化可保持不变 |
| migrationVersion | 冻结迁移单元的全序版本；可有多个单元组成一个 schemaVersion | 否 |
| environmentId | 首次本地部署产生并持久化的实例身份 | 否；两次独立部署必须不同 |
| deploymentAttemptId | 一次本地部署/更新/恢复尝试的身份 | 新尝试改变，重复投递保留 |

身份中的分量使用受约束 ASCII 标识，结构化序列化，不能用未经转义的字符串拼接造成碰撞。身份首次分配属于编辑或运行操作，可以产生新 ID；相同冻结输入的编译与生成不分配随机身份。用户可见中文标题单独存储。

AstNodeId / ReferenceRole 属于编译期引用定位，详见 02。SourceSpan 必须对应当前源快照。释放版本、数据库版本、运行镜像摘要和 Docker 卷名不能互相替代。

## 2. 五类正式对象

| 对象 | 产生方 -> 消费方 | 必须包含 |
| --- | --- | --- |
| SourceSnapshot | 平台 -> 编译器 | 源文件字节及路径、项目身份、固定模块版本、目标 Profile 引用 |
| SpringBootLoweredModel | Lowering -> Generator | 所有 Java、SQL、API、依赖、部署源码与输出路径的渲染决策 |
| MigrationDefinition | 目标迁移规划 -> 最终 Lowering 装配；渲染随包交给部署执行器 | 前后 schema、允许操作、冻结步骤、条件、保留数据策略；Generator 仍只接收最终 Lowered |
| ReleaseManifest | 平台导出 -> 用户部署入口 | 发布与父版本、支持基线、文件摘要、Profile、迁移定义引用、执行器协议 |
| DatabaseMigrationPlan | 本地执行器 -> 本次迁移 | 发布摘要、environmentId、实际旧基线、现场检查摘要、待执行步骤、尝试身份 |

MigrationDefinition 不含本地密码、主机路径或环境观测。DatabaseMigrationPlan 在本地完成现场核验后形成，不写回平台编译输入。

## 3. ReleaseManifest v1

| 字段 | 契约 |
| --- | --- |
| formatVersion | 固定为本格式版本；不认识的主版本拒绝执行 |
| projectId / releaseId / parentReleaseId | 项目及版本关系；首轮发布主线不分叉 |
| sourceSnapshotId | 本包对应的冻结 SIR 输入 |
| targetProfileId / targetProfileDigest | 经过资格验证的精确依赖与映射契约 |
| generatorVersion / deploymentProtocolVersion | 生成版本与最小执行器协议 |
| schemaVersion / schemaDigest | 本包业务启动所需的管理范围内数据库结构 |
| acceptedBaseReleases | 首轮首版为空；后续只列直接父发布版本 |
| allowedModes | INITIALIZE、UPDATE；首版只需要 INITIALIZE |
| migrationDefinitionPath / migrationDefinitionDigest | 冻结迁移声明的精确引用 |
| migrationChainDigest | 从起始迁移到本版本的有序历史摘要 |
| payloadFiles | 按规范路径排序的 path、size、sha256、fileMode 列表 |

文件清单包含业务源码、构建文件、部署执行器源码、SIR、测试、迁移、说明与启动脚本。payloadFiles 排除 ReleaseManifest 自身，避免自引用摘要；其余可执行输入不得遗漏。生成后的本地状态、凭据、target 目录和日志不属于下载包清单。

manifestDigest 由规范化 Manifest 字节计算，存放在平台发布记录及本地收据中，不写进自身。ZIP 摘要由完整打包后计算，独立记录，不再塞回 ZIP。内容摘要证明完整性，不单独证明发布者身份；不把 sha256 当成签名。源码允许用户导出后自行开发，但本工具的受验证升级承诺仅适用于摘要匹配的发布输入。

## 4. 源码、发布与本地状态相互独立

| 状态系统 | 负责事实 | 明确不负责 |
| --- | --- | --- |
| 平台草稿 | 最新编辑内容与 draftRevision | 已发布内容、用户数据库状态 |
| 文件 Bundle / CURRENT | 当前本地工具链工程快照与文件事务发布方向；目标平台通过 Application 复用 | 数据库迁移、业务部署或平台 Release 可见性 |
| 平台 Release | 不可变下载包、声明与平台验证证据 | 用户是否下载或运行成功 |
| 本地环境绑定 | projectId、environmentId、卷、凭据引用和部署历史 | 新业务语义推导 |
| 数据库基线 | 项目/环境身份、schemaVersion、history 和执行阶段 | 应用进程已健康 |
| DeploymentReceipt | 本次部署各步骤结果、实际镜像摘要、数据库版本 | 所有业务都正确、其他机器也成功 |

CURRENT=B0 只向后补偿到 B0；CURRENT=B1 只向前验证清理；其他或缺证情况失败关闭。该规则用于 Application 文件事务，包括当前本地场景；不重命名为数据库事务方向。文件 UPDATE/CREATE/DELETE 与数据库 INITIALIZE/UPDATE 使用不同请求、计划、锁与状态。Release 发布不代表任何 environmentId 已迁移；数据库已迁移也不代表应用健康。

## 5. 统一状态词汇

### 5.1 平台生成任务

jobStatus：QUEUED、RUNNING、SUCCEEDED、FAILED、CANCELLED。stage：COMPILE、LOWER、GENERATE、VERIFY、PACKAGE、PUBLISH。重试用 attemptNumber 与 leaseToken 区分，不制造第二个同名发布。

网页只有 Release 发布完成后显示“可下载”。任务验证成功但发布竞争失败仍为 FAILED，错误 HEAD_MOVED，不覆盖别人已发布的版本。平台执行状态详见 06。

### 5.2 本地数据库

dbStatus：UNINITIALIZED、READY、MIGRATING、DB_RECOVERY_REQUIRED。READY 必须伴随已验证的 schemaVersion 与 history；单有状态字符串没有证明力。DB_RECOVERY_REQUIRED 保留证据并阻止普通迁移和应用启动。具体转移由 04 定义。

### 5.3 本地部署尝试

deploymentStatus：PREPARING、BUILDING、CHECKING、MIGRATING、STARTING、DEPLOYED、BLOCKED、FAILED、RECOVERY_REQUIRED。

BLOCKED 表示前置条件不成立、尚未执行数据库修改；FAILED 表示已明确失败且无不确定数据库状态；RECOVERY_REQUIRED 表示迁移或发布结果需要显式裁定。READY 数据库 + FAILED 应用启动是合法组合，不能因此自动倒退数据库。

进程退出码与状态分离：建议 0 成功、2 输入错误、3 前置条件阻断、4 构建/依赖失败、5 需恢复、6 启动或健康检查失败、7 非预期内部故障。用户可读说明与结构化结果同时保留，敏感信息不进入平台日志。

## 6. 错误与诊断

编译 Diagnostic：code、stage、severity、message、sourceSnapshotId、sourcePath、span、可选 symbolId 和 relatedLocations。重复重试不改变同一错误的语义身份；排序按源路径、起点、阶段、code 固定。

平台错误与部署错误使用各自 code 命名空间，例如 JOB.HEAD_MOVED、DEPLOY.TARGET_MISSING、DB.BASELINE_MISMATCH、DB.DRIFT、DB.RECOVERY_REQUIRED。不能在平台“验证失败”和用户本地“数据库不匹配”之间共用一个模糊错误码。

业务 HTTP 错误：code、message、fieldErrors、traceId。与 SIR 编译错误分开；错误不包含原始 SQL、密码或完整数据行。业务接口的默认 HTTP 状态由 02 定义。

## 7. 确定性与版本冻结

所有协议文件 UTF-8、无 BOM、LF 换行。JSON 对象字段按 ASCII schema 字段名排序；集合有语义顺序时保留，无语义顺序时按稳定身份排序。禁止重复键、非有限数值和未声明字段；字符串业务值不被悄悄改成另一种 Unicode 表示。

精确小数在协议中使用规定的十进制字符串，整数不使用浮点中间态。路径使用相对 POSIX 表示，拒绝绝对路径、..、Windows 保留名与大小写碰撞。压缩包入口只允许普通文件和目录，不夹带链接。

确定性生成条件为同一 SourceSnapshot、模块锁、Profile、基线历史、生成器/模板版本和预留 releaseId。执行时间、镜像拉取耗时、local environmentId 与凭据不进入 Generator。ZIP 元数据采用固定策略；运行收据的实际时间单独保存。

## 8. 跨文档必须同时成立的条件

1. KCG-Code 只在平台侧；下载包包含运行与迁移所需源码，宿主只依赖 Docker/Compose。
2. 模板只消费最终 Lowered；现场检查不会倒流到 Semantic 或 Generator。
3. UPDATE 只处理发布声明接受的基线，绝不从目标丢失推断新建。
4. 不可变历史追加；首次安装最新版重放完整历史，不重写 V1。
5. 缓存不跳过权限、版本竞争、文件摘要或数据库现场检查。
6. 推荐方案与当前实现隔离；文档中的成功标准是未来验收要求。
