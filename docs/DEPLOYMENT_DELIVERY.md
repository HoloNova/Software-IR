# 后端独立部署包 v1（步骤①②③已验收）

步骤①已由[verify #94](https://github.com/HoloNova/Software-IR/actions/runs/39019809176)在`30f50bd`上验收：公开导出、冷构建、建库、HTTP调用、账号权限、重启/重复部署保留数据和丢失卷拒绝全部通过，见[报告第5节](DEPLOYMENT_DELIVERY_REPORT.md)。它是本样例在CI Linux x86_64上的证据，不是生产或全平台资格。步骤②的Swagger、随包合同测试（第7节）由[verify #95](https://github.com/HoloNova/Software-IR/actions/runs/39021298934)、独立业务验收包（第8节）由[verify #96](https://github.com/HoloNova/Software-IR/actions/runs/39021834946)在本样例上验收，见[报告第7节](DEPLOYMENT_DELIVERY_REPORT.md)；保留数据的同实例更新（第9节）由[verify #97](https://github.com/HoloNova/Software-IR/actions/runs/39025709647)、[verify #98](https://github.com/HoloNova/Software-IR/actions/runs/39026265442)验收，见[报告第8节](DEPLOYMENT_DELIVERY_REPORT.md)。

## 1. 导出与部署分开

使用包含本轮改动的新构建CLI：

```sh
kcg package --source-root /absolute/path/to/sir \
  --entry project.sir --output-root /absolute/path/to/delivery \
  [--acceptance /absolute/path/to/acceptance-suite]
```

package只写新的交付目录，不调用Docker、联网、构建、启动、Git；失败不留下半份交付包，已有输出根拒绝覆盖。成功结果是`KCG-CLI-WORKFLOW-V1 / PACKAGE_EXPORTED`，包括packageId、完整源摘要和目标平台。

交付目录包含普通Maven应用`app/`、源快照`sir/`、部署配方和独立迁移工具源码`deploy/`、说明`docs/`及逐文件摘要。原generate的输出不变；package内的应用POM额外包含Actuator，运行profile提供健康检测和连接配置。**交付包的app不是原version-plan/apply的开发工程目标**；已部署实例只能用新交付包的`update`动作更新（第9节），不能把交付目录冒充普通generate输出进行应用。

## 2. 在CI或后续指定机器执行

首轮目标为同机Linux x86_64 Docker daemon、Docker Engine及支持`up --wait`的Compose（2.20+）。宿主不需要JDK/Maven/MySQL/KCG。首次部署需要访问公共镜像和Maven仓库，目前无完全离线分发或预构建镜像发布。

```sh
cd /absolute/path/to/delivery
sh deploy/deploy.sh deploy --instance demo \
  --state-root /absolute/path/to/demo-state --port 18080
sh deploy/deploy.sh status --instance demo --state-root /absolute/path/to/demo-state
sh deploy/deploy.sh stop --instance demo --state-root /absolute/path/to/demo-state
sh deploy/deploy.sh start --instance demo --state-root /absolute/path/to/demo-state
```

部署完成才显示`http://127.0.0.1:18080`；具体业务路由由SIR决定，接口文档见第7节。端口仅发布到宿主回环地址，数据库不发布宿主端口。

当前开发机不得执行这些部署命令或业务测试；这里只运行轻量源导出/参数/边界/配置逻辑测试。部署与真实HTTP验收交给CI或负责人后续指定机器。

## 3. 首次部署顺序

1. 检查Docker及架构，校验交付文件摘要；创建或复用该实例的持久配置。摘要只证明包内一致性，不是对恶意包的签名认证。
2. 获取该实例在宿主及Docker daemon中的部署协调资源。并发调用明确拒绝；遗留锁不凭超时自动接管。
3. 在受限Maven容器中依次构建应用和Flyway工具，不启动数据库；构建只写临时目录、实例缓存和运行状态目录，不写交付包。
4. 构建运行镜像；首次明确创建带实例/环境/包绑定的数据卷。已有卷必须匹配，已初始化实例缺卷不得新建空替代。
5. 启动MySQL，等真正的TCP查询就绪。独立迁移进程初始化专用schema，并核验表、列、引擎、比较规则、主键/唯一/外键、默认值及无外部触发器等v1合同。
6. 迁移账户为应用授予业务表DML和Flyway history只读权限；应用不拿迁移密码或DDL权限。
7. 启动应用，等真实HTTP readiness（包含数据库健康）通过；再显示地址。

stop不删卷、配置、密码或镜像。start要求卷/镜像存在，只核验已有schema等于本包合同链末版后启动应用，从不迁移或重新建库。重复同包deploy同样只核验，复用配置、数据与Flyway历史，不注入演示数据或重置账号。

运行状态目录必须在包外；包含随机密码、环境身份和构建产物，不放Git或上传CI证据。不要删除状态后尝试接管旧卷；环境绑定不同会拒绝。除`update`外，所有动作都要求状态绑定的就是本包。迁移中断自动修复、清库/repair不在任何路径。

## 4. 物理支持边界

表/列/主键和引用目标从现有Lowered模型及正式SymbolId取得，不按Java源码猜测。选择以DeploymentPlan显式表达，再由Generator纯渲染。

| SIR语义类型 | v1物理选择 |
| --- | --- |
| Count、Quantity、PageNo、PageSize / Id、Serial、Version / Flag | INT / BIGINT / BOOLEAN |
| Token | CHAR(36)，匹配现有UUID处理器 |
| Day / Moment | DATE / DATETIME(6)，连接与会话UTC |
| Text、Name、Code、Remark、Email | 有长度上限且不超过768时VARCHAR，否则LONGTEXT；业务约束仍由原生成代码检查 |
| Secret | VARCHAR(255)，存摘要，不套用输入明文长度 |
| Money | 暂不支持（没有精度与小数位的物理合同） |
| 枚举 | 按目标枚举成员长度规划VARCHAR |
| Ref | 目标主键的实际存储类型，外键RESTRICT |
| 可空字段 | 保留NULL；非空字段NOT NULL |
| 字段 `default` | 常量与枚举成员写成列的 DEFAULT（记入合同第 11 列，迁移时按 information_schema 核对）；`now()` 与 LONGTEXT 列没有数据库默认值 |

表为InnoDB，文本使用`utf8mb4_0900_bin`完整值比较。unique只建立完整值唯一键，不建前缀唯一索引。唯一文本缺少不超过768的明确长度、Decimal缺少精度/scale契约、外部actor未提供credential实现、超出标识符/行预算等情况在导出时返回`SIR-DEPLOY-UNSUPPORTED-001`。普通generate仍遵循原合同。

与Java映射不兼容的MySQL保留字在独立执行器的服务器核验中拒绝，不让健康检查成功掩盖业务SQL不可用。schema漂移或迁移失败保留现场，不自行repair或改旧数据。

## 5. 依赖与资源

构建基底`maven:3.9.9-eclipse-temurin-21`，运行基底`eclipse-temurin:21.0.7_6-jre-jammy`，MySQL `8.4.5`；工具使用Flyway core/mysql `11.8.2`和Connector/J `9.3.0`。CI #94已在本部署样例中完成镜像取得、应用/工具构建、迁移、启动与第6节全部门；不等于全部业务或生产支持矩阵，也未锁定镜像摘要。新增Flyway/Actuator/springdoc依赖只出现在交付源码，不加入工具链运行依赖。

默认构建容器与测试容器1536 MiB/1.5 CPU，Maven堆512 MiB、元空间256 MiB；运行MySQL1024 MiB、应用768 MiB、一次性迁移384 MiB，分别设置CPU预算和JVM参数。构建、初始化、应用启动按顺序进行。部署入口支持`KCG_BUILD_MEMORY/CPUS`、`KCG_DB_MEMORY/CPUS`、`KCG_APP_MEMORY/CPUS`、`KCG_MIGRATE_MEMORY`和`KCG_TEST_MEMORY/CPUS`，按CI/目标机预算调整，不按当前开发机余量承诺容量。

## 6. CI门

verify新增`standalone-deployment` job，消费同SHA全量门产出的CLI，而不是在新job重复构建整个工具链。公开package导出后，CI只使用包中的部署入口准备数据库，没有手工夹具DDL。

`.github/scripts/deployment-smoke.py`检查首次部署、真实注册/读取/登录/受保护写入、只读核验、应用不能DDL、按文档server与路径发出的Swagger式真实请求、经公开入口的`contract-test`、停止/启动持久化、重复同包部署保持一份Flyway历史及原密码、丢失数据卷后start拒绝。它与合同测试都不替代双Token完整需求判题。

产物：`standalone-cli`、`backend-source-delivery`、`deployment-evidence`（export结果、commit/run绑定报告、脱敏日志、`reports/contract`下的合同报告与Surefire报告）。`report.json`另记`swagger`、`contract`分项、合同检查计数及`NOT_VERIFIED`清单。不上传状态或数据卷。CI #93因验收脚本对空体401解析JSON而失败；修复后的#94全部通过，分项与artifact见[报告](DEPLOYMENT_DELIVERY_REPORT.md)。

## 7. 接口文档与合同测试（步骤②）

交付应用带springdoc 2.8.9。部署后Swagger UI在`http://127.0.0.1:<port>/swagger-ui/index.html`，OpenAPI JSON在`/v3/api-docs`。文档从实际Controller/DTO得到；生成的`KcgOpenApiConfiguration`只补充两类Controller表达不了的信息：认证接口的Bearer要求（在“Authorize”中填访问令牌）及空体401，以及每个接口在导出时由目标模型声明的错误信封响应（请求体接口的400 `INVALID_REQUEST`与SIR声明错误）。Swagger可发真实请求，但它是手动调试工具，不是判题器。

```sh
sh deploy/deploy.sh contract-test --instance demo --state-root /absolute/path/to/demo-state
```

`contract-test`要求实例在运行；在受限Maven容器内经Compose网络请求`http://app:8080`，运行生成的`app/src/test/java/<基础包>/contract/HttpContractIT.java`。它只发出会在业务代码前被拒绝的请求，不写数据，可对任一实例运行：Swagger UI与OpenAPI可达、文档中的操作/认证/错误响应与导出时的接口表一致、认证接口对无凭据/畸形/未签发令牌回401、非认证请求体接口对畸形JSON/未知属性/`notBlank`/`length`违例回400错误信封。成功调用、声明业务错误和认证后的校验需要业务数据，记为`NOT_VERIFIED`，合同通过不等于业务验收。

结果在`<state>/reports/contract/`：`contract-report.tsv`逐项给出PASSED/FAILED/NOT_VERIFIED及原因，另有Surefire报告。成功输出`KCG_CONTRACT_PASSED`，失败以退出码5结束。普通`mvn test`不选中该类，也不需要运行中的服务。

## 8. 独立业务验收包（步骤②）

业务是否满足需求由独立验收包判定，不由合同测试或Swagger代替。验收包是一个普通Maven工程：根目录有`pom.xml`，`mvn test`即套件，接收系统属性`kcg.baseUrl`、`kcg.jdbcUrl`、`kcg.jdbcUser`、`kcg.jdbcPasswordFile`与报告目录`kcg.reportDir`。它只依据固定需求、服务地址和该实例自己的数据库核验，不读SIR或`app/`。

`kcg package --acceptance <绝对目录>`把它逐字节复制到交付包`acceptance/`并列入摘要，不编译、不解释。只接受普通UTF-8文本文件：符号链接、构建产物等非文本、以点开头或含特殊字符的文件名、超过1000个文件/单文件1 MiB/总计8 MiB、缺少根`pom.xml`，以及与源或输出根重叠的目录，都以`SIR-APP-PACKAGE-ACCEPTANCE-001`在发布前拒绝。

```sh
sh deploy/deploy.sh acceptance-test --instance demo-acc --state-root /absolute/path/to/demo-acc-state \
  --confirm-disposable demo-acc
```

套件会写业务数据，所以必须在一次性实例上运行，并用`--confirm-disposable`重复实例名；不重复、名字不符或把该选项用于其他动作，都在任何Docker调用前以退出码2拒绝。包内没有验收包时输出`KCG_ACCEPTANCE_NOT_RUN`并以退出码4结束，不冒充通过。测试容器与`contract-test`同样受限、经Compose网络请求`http://app:8080`，只挂载`acceptance/`和该实例的数据库root口令文件。结果在`<state>/reports/acceptance/`（`acceptance-report.tsv`逐条需求及Surefire报告）；成功输出`KCG_ACCEPTANCE_PASSED`，失败以退出码5结束。

仓库内的双Token套件位于`sir-toolchain-application/src/test/resources/deployment/dual-token-acceptance/`，由`DualTokenBusinessConformanceIT`的D0–D7整理而来：成对令牌及有效期、错密码与未知账号同响应、访问令牌认证且刷新令牌不能认证、过期拒绝、刷新轮换、重放拒绝、6路并发刷新恰好一次成功、登出后刷新失效；每次状态变化经独立数据库连接核验。D3为模拟过期直接改写该令牌的过期时间，所用物理表名属于该需求夹具。账号名带随机后缀，可在同一实例重复运行。CI在同包的第二个实例上运行它，结果见`report.json`的`business`分项。

## 9. 保留数据的同实例更新（步骤③）

合同见[方案11第13节](design/11-portable-deployment-and-http-acceptance.md)。先用实例当前运行的交付包作基础导出新包，再在目标机器上update：

```sh
kcg package --source-root /absolute/path/to/new-sir --entry project.sir \
  --output-root /absolute/path/to/delivery-v2 --base-package /absolute/path/to/delivery-v1
sh deploy/deploy.sh update --instance demo --state-root /absolute/path/to/demo-state   # 在delivery-v2目录中执行
```

导出时，基础包按Bootstrap同样的规则校验摘要（`SIR-APP-PACKAGE-BASE-001/002/003`）；它的`deploy/migrations/V1..Vb`与`deploy/contracts/`逐字节继承。迁移由变更层驱动（[设计12第12节](design/12-feature-semantics-and-semantic-types.md)）：用当前工具链重新编译基础包`sir/`里的SIR（入口记在包根的`sir-entry`），渲染出的结构清单必须与基础包的`schema.tsv`逐字节相同，否则以`SIR-APP-PACKAGE-BASE-004`拒绝；变更层对比两版模型得出增删改，Lowering按语义身份把新增/删除的实体和字段落到表和列上，保留下来的列再逐项比较物理属性。没有`sir-entry`的旧格式包不能作为基础（`SIR-APP-PACKAGE-BASE-003`）。本轮支持九类（schema不变、改名、放宽、改默认值、新表、可空新列、带默认值的必填新列，以及需要确认备份的删列、删表）：schema不变（不新增版本）、带`@id`的字段改名（列跟着改名，`V<v>__rename_column_<表>_<旧列>.sql`，排在其他新版本之前，并记入随包继承的`deploy/renames.tsv`，迁移工具据此在新列名下核对旧数据）、放宽的列（`V<v>__widen_column_<表>_<列>.sql`，`ALTER TABLE … MODIFY COLUMN`只重述类型、可空和默认值，唯一键与外键留在原处；放宽只有这几种：32位整数变64位、有长度的文本变更长或变无长度（无长度的文本不能有默认值和唯一键）、必填变可空；已有值一个都不变，MySQL可能复制整张表，所以不用`ALGORITHM`子句；主键、自增列不放宽，引用列只能必填变可空，类型不变）、只改了默认值的列（`V<v>__change_default_<表>_<列>.sql`，`ALTER COLUMN … SET DEFAULT`或`DROP DEFAULT`，排在改名之后；旧行保持原值，迁移工具照常核对）、新表（外键只指向已有表、更早的新表或自身）、已有表上可空且非唯一、无引用的新列、已有表上带数据库默认值的非唯一、无引用的必填新列（旧行取默认值，迁移工具核对每个旧行都是这个值）。同一个列有几项变化时按改名、放宽、改默认值的顺序各占一个版本，每个版本都按上一个版本留下的列来写。每个新版本一个文件、一条DDL：改名、放宽、改默认值之后，先`V<v>__create_table_<表>.sql`，再`V<v>__add_column_<表>_<列>.sql`（`ALGORITHM=INSTANT`），最后是删除，并写入新版本前的合同`deploy/contracts/V<v>.tsv`。

**删表、删列要先确认备份。** 源码里不再有的表或列（实体或字段被删掉），即支持的最后两类（删列、删表），只在导出时带`--confirm-backup yes`才会变成迁移：`V<v>__drop_column_<表>_<列>.sql`（`ALTER TABLE … DROP COLUMN`），再是`V<v>__drop_table_<表>.sql`（`DROP TABLE`，引用别的待删表的先删）。不带时以`SIR-DEPLOY-UPDATE-BACKUP-001`逐条列出并拒绝，不留输出。确认只是声明：工具不检查是否真的备份过，`update`本身也不备份。结果JSON的`dataLoss`列出会丢数据的表与列。迁移工具对这些列和表不比对数据，对其余的表与列照常逐行摘要比对，并输出`KCG_DATA_DROPPED`。主键列和引用别的表的列（要先按名字删外键约束，合同里没有这个名字）无论是否确认都不删，以`PRIMARY_COLUMN_REMOVED`、`REFERENCE_COLUMN_REMOVED`拒绝；整张表可以删。不带`@id`的字段改名，身份随名字变化，表现为删列加新列，同样需要确认，新列还须可空或带默认值。已有表上联合唯一的增、删、改（`UNIQUE_KEY_CHANGED`：旧数据可能已经重复，联合唯一只能随新建表一起建）、主键列改名、其它的列属性变化（缩小长度、可空变必填、唯一、引用、类型变化）、新增必填/唯一/引用列、新表外键成环、仅大小写不同的名字、MySQL镜像变化，仍逐条以`SIR-DEPLOY-UPDATE-UNSUPPORTED-001`列出并拒绝，不留输出。结构合同`schema.tsv`与`contracts/V<v>.tsv`里，表的列行之后可以有该表的联合唯一行（`UNIQUE`、表名、索引名`kcg_uq_<序号>`、按索引顺序的列，制表符分隔），建表时写成`UNIQUE KEY`；迁移工具核对活库的复合唯一索引与这些行一致，没有联合唯一的软件合同逐字节不变。结果JSON含`basePackageId`、`schemaVersion`、`changes`（变更层得出的业务层面变化，每条一行）、`steps`与`dataLoss`。新增表或列如果变更层没有报告对应的实体或字段，或迁移链终点不等于新清单，记为工具链内部错误`SIR-DEPLOY-UPDATE-SELFCHECK-001`。只收紧业务规则而无DDL的变更不对旧行核验：物理数据保留，旧行的业务规则兼容性NOT_VERIFIED。

`update`用新交付包更新一个已初始化、正在使用的实例，并保留其数据。顺序：先构建镜像（失败退出4，数据库与旧应用都未动）；在旧应用仍提供服务时，迁移工具只读判定活库在本包合同链上的位置；停止旧应用；只应用缺失的版本，迁移前后对旧schema的全部表与列按主键做摘要比对，新列在旧行上须为NULL（带默认值的为该默认值）、新表须为空；被删除的列和表不比对；原子改写`state/package.id`；启动新应用。已绑定本包时为`KCG_UPDATE_CURRENT`，不构建也不迁移。

| 退出码 | 含义 |
| --- | --- |
| 0 | `KCG_UPDATED <旧>-><新>`或`KCG_UPDATE_CURRENT` |
| 3 | `KCG_BLOCKED`/`KCG_UPDATE_BLOCKED`：拒绝，数据库未改动；已停止的旧应用会被重新启动，并报告`application=RESTARTED|RESTART_FAILED` |
| 4 | `KCG_UPDATE_BUILD_FAILED`：构建失败，数据库与旧应用未动 |
| 5 | `KCG_DB_RECOVERY_REQUIRED`：数据库可能已变，需要人工；不clean、不repair、不回滚，应用保持停止，绑定仍为旧包 |
| 6 | `KCG_APP_START_FAILED`：数据库已为本包版本、绑定已更新，新应用未就绪；可重试`start`或`update` |

迁移工具对活库的判定按顺序：无history且无业务表为空库；有业务表而无history为UNMANAGED_SCHEMA；有失败记录为FAILED_MIGRATION（5）；已应用版本高于本包为AHEAD（3，较旧的包不能运行较新的库）；版本不连续或校验和不符为DIVERGED（3）；活库等于第k版合同为AT(k)；等于第k+1版合同而未记录为INTERRUPTED（5）；其余为DRIFT（3）。重复执行只由绑定、活库判定和包内合同链决定。schema不变的包在实例间切换不执行SQL。更新前不做备份，恢复需人工。

不以基础包导出、而是独立导出的包会重新渲染V1；只要schema不同，V1校验和就与实例历史不符，以DIVERGED拒绝。CI不手工改SQL制造中断或漂移，INTERRUPTED/DRIFT的运行侧判定只有规则与代码审阅，没有真实库证据（记NOT_RUN）。
