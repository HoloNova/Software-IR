# 可独立部署的后端交付包与真实 HTTP 验收

- 日期：2026-10-17。
- 状态：**步骤①②③ PASSED**。负责人已确认方案并授权进入实现；步骤①由verify #94、步骤②（合同见第12节）由verify #95/#96验收；步骤③（合同见第13节）由verify #97/#98验收。负责人随后已授权提交、推送并取得CI验收；镜像发布仍未授权。
- 实现起点：云端 `main` / `cf44450`。信任既有云端成果，不为规划重复执行验证。
- 依据：[MAIN](../../MAIN.md)、[当前能力](../PROJECT_STATUS.md)、[ADR-026](../architecture/ADR-026-layered-semantic-command-architecture.md)、[ADR-027](../architecture/ADR-027-git-as-version-history.md)。
- 方案文本不是已交付或通过证据；步骤①实现范围、26项定向结果、CI #93阻塞与#94验收见[报告](../DEPLOYMENT_DELIVERY_REPORT.md)。

## 1. 目标与用户流程

交付一套**无需手工建表、配置数据库连接或逐个启动服务的后端工程**，附带接口文档与可独立执行的网络测试。业务前端不属于这一阶段，Swagger UI也不冒充业务前端。

```text
合法且目标支持的SIR + 可选的独立需求验收包
  → 完整源码、建表/迁移脚本、部署配方、接口合同测试
  → 导出独立交付包
  → 自动取得镜像与构建依赖、构建产物
  → 准备运行配置和数据库、初始化/迁移、启动应用
  → 通过真实HTTP运行合同及业务测试
  → 服务地址、Swagger地址、分阶段结果与证据
```

部署使用已经生成的源码，不在目标机器重新编译SIR，也不需要安装KCG-Code。首次构建需要公共镜像及依赖网络；完全离线分发不在本阶段。

“能首次部署”“能完成网络业务验收”“能保留数据更新”是三个完成门，不互相抵扣。

## 2. 已核对的起点与缺口

| 能力 | 当前证据与本阶段处理 |
| --- | --- |
| 业务源码与构建配置 | 现有Lowering/Generator已生成，继续复用 |
| 单文件CLI、通用增删改、Git工程更新 | 已交付，不重做，也不恢复旧Bundle/CURRENT/事务日志 |
| 独立验证编排 | CandidateVerificationApplication已有static/build/start/business四级和VerificationPorts，继续复用；外部端口缺失仍是NOT_RUN |
| 构建/启动适配器 | execution包已有MavenProjectBuilder和SpringBootApplicationHost；容器适配由本阶段补齐，不假称它们已经支持Compose |
| 真实HTTP业务测试 | 双Token等IT存在于工具链测试目录，使用固定SIR与手工DDL；尚不是用户可随包执行的测试产品 |
| 生成工程测试文件、Swagger、DDL及部署文件 | GenerationEngine当前无这些输出，本阶段新增 |

代码落点：`sir-lowering-spring-boot`、`sir-generator-spring-boot`、`sir-toolchain-application`、薄CLI入口以及`.github/workflows/verify.yml`。现有`VerificationPorts.Oracle`只接收运行地址、证据目录和预算，不获得候选源码，这是独立业务判题的边界。

## 3. 环境选择与执行位置

### 3.1 负责人明确约束

**当前开发机不进行部署或业务测试。** 验收使用CI，或负责人后续明确切换的机器；不能因为本机已经安装Docker就拉镜像、构建镜像、启动数据库或执行业务IT。

当前机器只用于代码开发及按AGENTS约束安排的轻量定向检查；整套部署与网络验收不在本机复跑。缺少有效CI/目标环境时对应门记NOT_RUN。

### 3.2 首轮运行方案

- 目标宿主只需Docker Engine与Compose；JDK、Maven和MySQL在容器中，不修改宿主Java/Maven/MySQL安装。
- 首轮支持资格以CI的Linux x86_64为准，后续机器使用同一份包；Windows、macOS、arm64不在没有证据时宣称支持。
- 运行配置明确指定实例、地址/端口、持久数据位置、资源上限。构建预算和运行预算分开，不从开发机余量推导发布默认值。
- 首次交付包括源码及Docker构建配方，CI验证冷构建路径。预构建镜像与发布渠道可后续接入，不自动公开发布或推送镜像。
- 数据库仅供该实例使用；普通停止不删卷，测试使用独立实例，不对已部署业务数据运行破坏性测试。

规划时只读检查到本机Ubuntu 22.04/x86_64、2 vCPU、3875 MiB内存、Docker/Compose可用，磁盘余7.7 GiB且无镜像缓存；没有拉镜像或启动服务。这些是环境观察，不是发布资格或目标机器资源合同。

## 4. 分层职责

| 层 | 本阶段新增职责 | 不承担 |
| --- | --- | --- |
| 软件语义 | 继续提供实体、字段、约束、关系及行为的正式含义 | Docker、SQL方言、部署路径、测试进程 |
| Spring Boot目标实现 | 统一规划物理schema、Java映射、运行依赖、部署和接口合同所需元数据 | 现场执行SQL、启动Docker或从需求猜业务断言 |
| 文件生成 | 纯渲染SQL、配置、容器配方、合同测试等已规划文件 | 文件I/O、镜像下载、访问数据库 |
| 工程应用/执行适配 | 导出包、运行部署、配置和持久状态、迁移、就绪检测、HTTP验收及报告 | 再做一套语义分析或业务专用Change操作 |
| CLI/随包入口 | 参数与typed请求映射、显示结果 | 解析SIR后手工拼业务SQL、复制业务规则 |

通用Change仍只有add/delete/update；新增产物接入现有文件差异/受管文件机制，不给Change增加部署、登录或测试专用操作。

工程源版本仍由磁盘SIR与Git管理。数据库的迁移历史、Docker实例状态和运行配置是外部环境事实，不能替代工程版本，也不能用Git回退冒充数据库回滚。任何涉及Accepted ADR契约的调整须在实施前明确记录和裁决，不能照搬旧部署文档中的版本状态系统。

## 5. 交付包与运行状态

建议采用外层交付包，内部`app/`保持普通Maven工程布局：

```text
delivery/
  app/
    pom.xml
    src/main/java/...
    src/main/resources/...
    src/test/java/.../HttpContractIT.java
  deploy/
    Dockerfile
    compose.yaml
    deploy.sh
    ...数据库初始化/迁移产物及执行入口...
  acceptance/                 # 独立需求测试包（提供时携带）
  sir/                        # 源快照，用于追溯，不在部署时重新编译
  docs/
    README.md
    testing.md
  ...产物摘要、目标与依赖版本信息...
```

这里只冻结职责与内容，入口参数、执行器打包和精确文件名在实施开始时形成接口合同；不把这些示意路径描述成现在可执行的命令。

配置/密码在首次部署生成并持久保存；重启复用，不放进镜像或版本库。运行配置、日志、数据卷与测试结果不写入受管生成源码目录，避免使原version-plan/apply误判工程漂移。源码导出和产物记录由Application组织，Generator不接触真实配置。

部署入口需要提供首次部署、启动、停止、状态、更新及网络测试的明确行为；默认不自动执行有写入的业务验收。网络业务验收在专用隔离实例中运行，手动接口调试通过Swagger完成。

## 6. 数据库：从空库初始化到受限兼容更新

### 6.1 首次初始化

不复用测试夹具DDL当作生产生成器。由目标实现层提供**显式物理schema**，供SQL和Java映射共同消费，覆盖表/列名、主键生成、实际存储类型、长度/精度、可空、唯一及关系约束。

必须核对当前Profile的UUID存储、Int32/Int64范围、时间类型、枚举、secret摘要实际长度和比较规则，不能从Java字段类型反推DDL，也不能把输入密码长度当成摘要列长度。

采用Flyway记录版本脚本与校验和。迁移由部署入口在启动应用前单独执行，不让业务应用每次启动自动建表或改表。数据库连接成功不等于schema已经准备好，应用启动前核验目标结构和迁移状态。

重复部署同一版本应核验并复用已初始化结果，不覆盖旧数据或重置账号。必要业务初始数据与测试种子分开；演示数据不得进入普通部署路径。

### 6.2 更新

先支持schema不变的代码更新，再扩展新增表、新增可空列等明确兼容操作。每项新增迁移能力都有前置数据检查与真实库保留数据证据；无证据的操作在规划时拒绝，不能以自动生成任意ALTER代替支持矩阵。

首轮不自动删除列、缩小类型、猜改名或修复不满足约束的旧值。已存在的物理映射在更新时须可靠继承；无法证明身份/映射对应时阻断，不靠名字相似度推断。

构建新应用失败时不停止旧应用；改变数据库前停止受管理业务写入。SQL中途失败保留现场和日志，不自动clean/repair、不清库重建。数据库已更新而应用未启动成功时单独报告，不通过回退Git假称数据库恢复。

支持矩阵和失败判定在对应步骤开始时形成具体合同；不承诺完成任意数据库迁移或全量G2/G3/G4产品门。

## 7. Swagger与随包测试

### 7.1 接口文档及手动调试

接入Springdoc，从实际生成的Controller/DTO得到OpenAPI与Swagger UI，不维护第二套路由清单。依赖版本在目标Spring Boot 3.5.x组合下锁定并验证。接口描述应覆盖输入、输出、参数约束、错误信封、分页/PATCH和受支持的认证方式。

Swagger负责接口展示和手动发真实HTTP请求；它本身不是自动业务判题器。接口文档可达、健康检查成功都不能代替业务通过。

### 7.2 自动合同测试

随工程输出普通JUnit与Java HttpClient测试文件，通过显式测试profile运行；默认Maven单元测试不要求已启动服务。以部署实例的baseUrl为输入，从应用进程外部经真实HTTP检查给定样例的状态码、响应结构、参数校验及错误格式，不使用MockMvc、直接调用Service或H2冒充网络/数据库验收。

测试样例必须区分可自动生成的标量边界与需要业务数据/账号的输入；缺少数据时明确报告未验证，不制造一个不存在的记录再把404当业务成功。合同测试只证明接口符合已声明结构，不证明需求正确。

### 7.3 独立业务验收

首轮将DualTokenBusinessConformanceIT中的需求断言整理为独立可运行套件，至少逐条验证登录、账号不存在与错密码同响应、访问令牌认证、过期、刷新轮换、重放、并发单次成功和登出。

业务套件只获得部署地址、固定需求、测试数据与必要的隔离库核验连接，不读取候选SIR来决定期望。现有测试中的固定DDL不能成为新部署建表路径：验收实例必须先通过产品部署流程建表，套件只准备需求数据并核验结果。

其他软件可附带自己的需求验收包。没有业务测试包时business门记NOT_RUN，不能用合同测试全绿替代。HTTP请求发往真实应用容器，使用真实MySQL；CI无须开放公网，跨进程/容器的HTTP依然是真实网络测试。后续机器可以对同一套件指定实际服务地址。

## 8. 三步交付及完成门

| 步骤 | 实施内容 | 完成门 |
| --- | --- | --- |
| ① 首次部署闭环 | 物理schema与DDL、Flyway空库初始化、配置/持久卷、Docker配方、包导出与部署就绪 | CI仅依靠Docker/Compose从包冷构建；自动建表和连接，启动后经HTTP调用；重复部署/重启不清数据或重置配置 |
| ② 接口文档与网络验收 | Springdoc、Swagger、随包合同测试、独立双Token套件及报告 | Swagger能发真实请求；合同测试与双Token业务/并发断言通过；报告分别记录构建/启动/合同/业务及缺失项 |
| ③ 保留数据更新 | schema不变的应用更新、经验证的受限兼容迁移、数据核验和失败分类 | 同一实例从第一版升第二版，旧主键/旧字段值/关系保持；重复执行可判定；不支持迁移与中断结果明确，不走清库路径 |

各步复用同一交付包合同，不能CI里走手工SQL、切换机器后再让用户补步骤。每步开始先冻结其参数、产物、支持矩阵和失败结果，按本文已确认边界完成详细设计；具体字段名和辅助实现不要求负责人逐行决策。源码提交/推送仍须独立授权。

## 9. CI与证据

- 部署/网络业务重活只在CI或负责人后续指定的机器执行；新Compose适配必须显式限制容器资源，不能只给Maven JVM设置Xmx。
- 串行执行重活，分别约束构建、MySQL、应用和测试进程，结束核对资源释放；不复用或清理不属于本次测试的卷/容器。
- CI应测试真实导出包与公开入口，而不是绕过产品链路的测试专用部署脚本。冷构建、失败路径、重复启动、数据持久化和版本更新分别留证据。
- 报告绑定commit SHA、CI run URL及上传artifact，记录包/目标、各阶段结果、脱敏日志与请求断言；未运行记NOT_RUN。
- 方案确认时没有运行新增测试；后续CI #93已覆盖步骤①部分链路并发现HTTP验收阻塞，分项见报告。既有#88只证明此前双Token实现，不覆盖新增部署能力。

## 10. 实施记录（2026-10-17）

步骤①代码已接入：独立package导出、显式物理schema、Flyway初始化工具、Docker/Compose配方、随包部署入口及独立CI烟测。原generate和Change/Git合同未改。新增入口合同与首轮支持限制见[部署包v1](../DEPLOYMENT_DELIVERY.md)。

本机26项定向检查通过，没有执行Docker/数据库/业务应用。随后获授权提交/推送`a7cba11`并执行CI #93：原主job全绿，公开导出、容器构建、Flyway和应用启动通过；HTTP烟测在JSON解析处失败，数据持久化/权限/丢失卷验收未到达。步骤①及长期G门不能关闭。负责人要求在此节点交云端继续（[接手说明](../DEPLOYMENT_CLOUD_HANDOFF.md)）。云端确认阻塞来自验收脚本对空体401的JSON解析，修复`30f50bd`后同SHA的[verify #94](https://github.com/HoloNova/Software-IR/actions/runs/39019809176)两个job全部success，第8节步骤①完成门关闭；长期G门不因此关闭。

## 11. 范围与旧文档关系

本方案具体化[旧数据库设计04](04-database-evolution.md)与[旧部署设计05](05-source-package-and-docker-deployment.md)在当前架构下的推进顺序，不照搬旧Bundle/CURRENT、复杂发布状态或首轮Windows资格。旧G阶段的完整验收矩阵仍是长期目标，不因本阶段完成自动全部关闭。

不做业务前端、Web平台、第二Target、Redis、多租户、Kubernetes、任意SQL扩展、任意迁移、全平台支持或完全离线镜像分发。对现有外部身份适配等必需依赖，部署前检查是否具备，不能为启动成功悄悄降级为固定身份。

模型评测题目保留，但当前优先推进部署与网络验收；本方案确认不等于启动模型评测。后续资格和实现进度只更新PROJECT_STATUS与对应证据报告，不把计划改写成已实现事实。

## 12. 步骤②接口合同（2026-10-17冻结）

负责人要求不等CI #94结果、继续推进，故步骤②与步骤①的同SHA验收并行实施；步骤①仍以CI证据关闭，步骤②不抵扣它。以下冻结参数、产物与失败结果，只在第7节已确认边界内取舍。

**接口文档。** 交付包应用POM额外加入`org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9`（以Spring Boot 3.5.0构建，与目标3.5.x同线），只进交付源码，不进普通generate或工具链。OpenAPI JSON在`/v3/api-docs`，Swagger UI在`/swagger-ui/index.html`，均为springdoc默认路径，内容从实际Controller/DTO得到。生成的`KcgOpenApiConfiguration`只补充认证传输：声明名为`bearer`的HTTP Bearer方案，并对处理方法带`@RequestAttribute`身份参数的接口加安全要求和“401、空响应体”说明，同时从参数表隐去该内部属性。它按实际处理方法判定，不维护第二份路由表。

**随包合同测试。** 生成`app/src/test/java/<基础包>/contract/HttpContractIT.java`：JUnit 5 + JDK HttpClient + Jackson，均来自既有`spring-boot-starter-test`/web依赖。类名以`IT`结尾，Surefire默认不选中，普通`mvn test`不需要服务；只在显式`-Dtest=HttpContractIT -Dkcg.baseUrl=…`时运行，缺少baseUrl即失败而不是跳过。用例由DeploymentPlan中的接口表（方法、路由、是否认证、输入绑定、文本规则）渲染，只发不写数据的请求：

| 检查 | 适用 | 期望 |
| --- | --- | --- |
| Swagger UI | 全局 | 200，`text/html` |
| OpenAPI清单 | 全局 | 文档中的“方法 路由”集合与接口表相等；认证接口且只有认证接口带`bearer`要求 |
| 无凭据/畸形凭据/不存在的令牌 | 认证接口 | 401 |
| 畸形JSON、未知属性 | 非认证的请求体接口 | 400，信封`code=INVALID_REQUEST`；未知属性出现在`fields[].path` |
| `notBlank`、`length(min,max)` | 非认证、非PATCH请求体的String字段 | 400，`fields`含该字段及`NotBlank`/`Size`；长度上限超过4096不生成超长样例 |
| 成功响应、声明错误、认证后的校验 | 每个接口 | 需要业务数据或账号，记为JUnit跳过，消息以`NOT_VERIFIED:`开头 |

**独立业务验收包。** 形式为一个自带`pom.xml`的普通Maven工程，其`mvn test`即业务套件，接收`kcg.baseUrl`、`kcg.jdbcUrl`、`kcg.jdbcUser`、`kcg.jdbcPasswordFile`和报告目录`kcg.reportDir`。`kcg package`新增可选`--acceptance <绝对目录>`，把该目录的普通UTF-8文本文件逐字节复制到交付包`acceptance/`并纳入摘要；不编译、不解释它，拒绝符号链接、非文本（如构建产物）、以点开头或含特殊字符的文件名、超过1000个文件/单文件1 MiB/总计8 MiB，以及缺少根`pom.xml`；拒绝发生在发布前，不留半份交付包。双Token套件放在`sir-toolchain-application/src/test/resources/deployment/dual-token-acceptance/`，由`DualTokenBusinessConformanceIT`的D0–D7断言整理而来；它只依据固定需求与物理表名核验，不读SIR或`app/`。

**部署入口。** `deploy.sh`新增两个动作，都要求实例已部署且在运行，测试进程在受限Maven容器中经Compose网络访问`http://app:8080`，即跨容器真实HTTP：

- `contract-test`：只读，可对任一实例运行，挂载`app/`，结果写入`<state>/reports/contract/`，成功输出`KCG_CONTRACT_PASSED`。
- `acceptance-test --confirm-disposable <实例名>`：会写业务数据，必须重复输入实例名确认是一次性实例；只挂载`acceptance/`与数据库root口令文件，结果写入`<state>/reports/acceptance/`，成功输出`KCG_ACCEPTANCE_PASSED`。包内没有验收包时输出`KCG_ACCEPTANCE_NOT_RUN`并以退出码4结束，不冒充通过。

**CI证据。** CI导出时带上双Token验收包。`deployment-smoke.py`在第一实例的HTTP烟测与权限核验后做Swagger式真实请求并运行`contract-test`；第一实例的持久化与丢失卷门结束后，在同包的独立第二实例上部署并运行`acceptance-test`，最后清理两个实例。`report.json`新增`swagger`、`contract`、`business`分项、两类检查计数、`NOT_VERIFIED`与失败清单；某门异常时记为FAILED，其后的门保持NOT_RUN；Surefire报告随`deployment-evidence`上传。完成门仍是第8节步骤②：Swagger能发真实请求、合同与双Token业务/并发断言通过、报告分阶段记录。

**验收记录。** `fee2a3b`的[verify #95](https://github.com/HoloNova/Software-IR/actions/runs/39021298934)与`ef324d6`的[verify #96](https://github.com/HoloNova/Software-IR/actions/runs/39021834946)两个job均success：`swagger`、`contract`、`business`及步骤①各分项PASSED，合同检查13 PASSED / 0 FAILED / 7 NOT_VERIFIED，双Token需求26 / 0。第8节步骤②完成门关闭，分项与artifact见[报告第7节](../DEPLOYMENT_DELIVERY_REPORT.md)。

## 13. 步骤③合同：保留数据的同实例更新（2026-10-17冻结）

由三份独立方案经评审综合而来（以“物理计划差异”为底，嫁接最小增量与安全优先方案的执行细节）。分两片实施：A片运行侧（`update`动作、迁移链判定、数据摘要），B片导出侧（`--base-package`、差异规划、追加迁移文件）。

**依据。** 旧侧以旧交付包冻结的`deploy/migrations/*`、`deploy/contracts/*`、`deploy/schema.tsv`和MySQL镜像为准，不重新编译旧包`sir/`（包内未记录入口，且工具链升级后重渲染字节必然不同，违背ADR-027）。实例侧以活库的`flyway_schema_history`、`information_schema`和`state/package.id`为准。包不记录base id或谱系，不新增状态文件；Change、Git与SIR语法不变。差异键是精确物理名；物理名只由当前名字推导，改名一律表现为删除并拒绝，不猜测。

**导出（B片）。** `kcg package … [--base-package <旧交付包>]`。无该选项时输出与现在逐字节相同。旧包须为绝对规范、不与源/输出/验收包重叠、无符号链接的目录，按Bootstrap规则校验摘要，迁移为连续的`V1..Vb`，`contracts/V<i>.tsv`对应`i<b`，`schema.tsv`为`Vb`合同。新SIR照常编译成DeploymentPlan，由纯函数比对旧合同与目标合同：

| 类别 | 本轮 |
| --- | --- |
| UNCHANGED：表/列集合及全部属性相同 | 支持，不新增版本 |
| CREATE_TABLE：新表名（外键只指向旧表、更早新表或自身） | 支持 |
| ADD_NULLABLE_COLUMN：旧表上可空、非主键、非自增、非唯一、无引用的新列 | 支持 |
| 删表、删列（含任何改名、Ref可空性翻转）、列属性变化（含加宽长度、可空性、唯一、引用、枚举成员变长、VARCHAR↔LONGTEXT）、新增必填/唯一/引用列、新表成环、名字仅大小写冲突、MySQL镜像变化 | 以`SIR-DEPLOY-UPDATE-UNSUPPORTED-001`逐条列出并拒绝，不留输出 |

新增版本每个文件只含一条DDL：先按拓扑与目标顺序`V<v>__create_table_<t>.sql`（外键内联，命名`kcg_v<v>_fk_<i>`，与V1的`kcg_fk_<n>`不冲突），再按（表，列）顺序`V<v>__add_column_<t>_<c>.sql`（`ALTER TABLE … ADD COLUMN … NULL, ALGORITHM=INSTANT`）。旧包迁移与合同逐字节复制，`contracts/V<b>.tsv`取旧`schema.tsv`，每个新版本前的合同写入`contracts/`，`schema.tsv`为最终合同；合同链逐行合并须等于目标合同，否则`SIR-DEPLOY-UPDATE-SELFCHECK-001`。已发布的迁移只追加，不重渲染；新实例从V1重放全链。结果JSON增加`basePackageId`、`schemaVersion`与`steps`。只收紧业务规则而无DDL的变更（notBlank、长度下限等）不对旧行核验，报告写“物理数据保留；旧行的业务规则兼容性NOT_VERIFIED”。

**迁移工具（A片）。** `migrate.jar initialize|verify|check|update`。合同链`S_0=∅`、`S_i`为`contracts/V<i>.tsv`、`S_n`为`schema.tsv`；按序判定（先中先得）：无history表且无业务表→EMPTY，有业务表→BLOCKED UNMANAGED_SCHEMA；存在失败记录→RECOVERY FAILED_MIGRATION；已应用最高版本>n→BLOCKED AHEAD；已应用集合不是`{1..k}`或校验和不符→BLOCKED DIVERGED；活库等于`S_k`→AT(k)；`k<n`且活库等于`S_{k+1}`→RECOVERY INTERRUPTED；否则BLOCKED DRIFT。`initialize`接受EMPTY或AT(k)并迁移；`verify`只接受AT(n)；`check`只读报告`KCG_DB_STATE V<k> package=V<n>`；`update`接受AT(k≥1)并迁移。迁移前按主键流式摘要`S_k`全部表与列（区分NULL）并计行数，逐版本`target=v`迁移并核验`S_v`，再摘要须相等，新增列在所有旧行上为NULL，新表为空，否则RECOVERY DATA_CHANGED。退出码3=`KCG_DB_BLOCKED`（未改动），5=`KCG_DB_RECOVERY_REQUIRED`；不打印数据值或摘要。

**部署入口（A片）。** 新增`update --instance N --state-root S [--port P]`；`P`须等于状态中的端口。Bootstrap接收动作参数：`update`时状态须已存在且不写入，`package.id`可为任意合法id；其他动作仍要求同包，提示改用update；Bootstrap拒绝统一为退出码3 `KCG_BLOCKED`。`check_volume`只比对`kcg.instance`与随机的`kcg.environment`（绑定强度不变），不可改的`kcg.package`标签降为“创建者”并由status显示。migrate以只读方式挂载包的`deploy/`；db改为挂载状态目录中的`accounts.sh`（缺失时从包复制），使数据库容器不再依赖某个包目录。`deploy`在已初始化实例上只`verify`，`start`只`verify`，均不迁移。

update顺序及失败结果：加锁与前置检查（2/3）；卷与`initialized`须存在（3）；已绑定本包→按start路径`KCG_UPDATE_CURRENT`（0，应用失败6）；构建与镜像（失败4，`KCG_UPDATE_BUILD_FAILED`，数据库与旧应用未动）；db未运行则启动，运行中的db不重建；`check`只读（失败3/5，旧应用仍运行）；记录并停止旧应用容器；`update`（3：未改动，重启旧容器并报告`application=RESTARTED|RESTART_FAILED`；其他非零：5 `KCG_DB_RECOVERY_REQUIRED`，应用保持停止，不clean/repair/回滚）；原子改写`state/package.id`；启动新应用（失败6 `KCG_APP_START_FAILED`，数据库已为`V<n>`，绑定已更新，可重试start或update）；成功输出`KCG_UPDATED <旧>-><新> … migrations=V<b+1>..V<n>|none`。

**重复执行。** 只由（`state/package.id`，活库判定，包内合同链）决定：同包且AT(n)→CURRENT；异包且AT(n)→不执行SQL，改绑并启动（覆盖schema不变更新与改绑前崩溃）；AT(k<n)→只应用`V<k+1>..V<n>`；FAILED_MIGRATION/INTERRUPTED→每次（含start）均为5，恢复需人工；AHEAD（较旧的包）→3。

**取舍（负责人2026-10-17确认）。** 准入以活库谱系为准，不在包内记录base id：允许跨版本和在schema相同的包间切换，代价是无法识别来自其他项目但schema相同的包。`kcg.package`标签降为创建者。本轮矩阵仅上表三类。无DDL的业务规则收紧放行并标NOT_VERIFIED。更新前不备份，报告写明“未备份，恢复需人工”。CI不手工改SQL制造中断或漂移，INTERRUPTED/DRIFT门本轮记NOT_RUN，由单元测试覆盖判定规则。恢复动作、旧镜像与`artifacts/`清理不在步骤③。

**完成门与CI。** 同包v1部署并经HTTP写入数据→导出schema不变的新包并`update`（无SQL，改绑）→导出含新表与新可空列的v2包并`update`（逐版本迁移，摘要一致，旧主键/字段/关系保持，新功能可经HTTP使用）→重复`update`为CURRENT→用较旧的包`update`被拒绝（AHEAD/3）→新实例直接部署v2包时从V1重放全链。原步骤①②门不回退。

**验收记录。** A片`bf14b8d`的[verify #97](https://github.com/HoloNova/Software-IR/actions/runs/39025709647)与B片`656bb06`的[verify #98](https://github.com/HoloNova/Software-IR/actions/runs/39026265442)两个job均success；#98中`update`、`dataUpdate`、`freshChain`及步骤①②各分项PASSED，`interruptedMigration`、`schemaDrift`按取舍记NOT_RUN。本节完成门关闭，分项与artifact见[报告第8节](../DEPLOYMENT_DELIVERY_REPORT.md)。长期G阶段的完整迁移矩阵不因此关闭。
