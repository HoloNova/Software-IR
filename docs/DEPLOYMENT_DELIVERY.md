# 后端独立部署包 v1（步骤①待CI验收，步骤②实施中）

实现已推送`a7cba11`，[CI #93](https://github.com/HoloNova/Software-IR/actions/runs/39002409782)已完成Docker构建、建库和启动，但HTTP验收脚本对空体401做JSON解析而失败，后续门未运行。脚本修复`30f50bd`的同SHA CI尚待结果，步骤①尚未验收，见[云端接手说明](DEPLOYMENT_CLOUD_HANDOFF.md)。不要把部分成功当成完整部署资格。步骤②的Swagger与随包合同测试已接入（第7节），独立业务验收包仍在实施；保留数据更新属于步骤③。

## 1. 导出与部署分开

使用包含本轮改动的新构建CLI：

```sh
kcg package --source-root /absolute/path/to/sir \
  --entry project.sir --output-root /absolute/path/to/delivery
```

package只写新的交付目录，不调用Docker、联网、构建、启动、Git；失败不留下半份交付包，已有输出根拒绝覆盖。成功结果是`KCG-CLI-WORKFLOW-V1 / PACKAGE_EXPORTED`，包括packageId、完整源摘要和目标平台。

交付目录包含普通Maven应用`app/`、源快照`sir/`、部署配方和独立迁移工具源码`deploy/`、说明`docs/`及逐文件摘要。原generate的输出不变；package内的应用POM额外包含Actuator，运行profile提供健康检测和连接配置。**交付包的app不是原version-plan/apply的开发工程目标**；目前不支持对已部署实例更新，也不能把交付目录冒充普通generate输出进行应用。

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

stop不删卷、配置、密码或镜像。start要求卷/镜像存在，验证已有schema后才启动应用，不重新建库。重复同包deploy复用配置、数据与Flyway V1，不注入演示数据或重置账号。

运行状态目录必须在包外；包含随机密码、环境身份和构建产物，不放Git或上传CI证据。不要删除状态后尝试接管旧卷；环境绑定不同会拒绝。改包后的更新、迁移中断自动修复、清库/repair不在v1路径。

## 4. 物理支持边界

表/列/主键和引用目标从现有Lowered模型及正式SymbolId取得，不按Java源码猜测。选择以DeploymentPlan显式表达，再由Generator纯渲染。

| SIR存储类型 | v1物理选择 |
| --- | --- |
| Int32 / Int64 / Boolean | INT / BIGINT / BOOLEAN |
| Uuid | CHAR(36)，匹配现有UUID处理器 |
| Date / DateTime | DATE / DATETIME(6)，连接与会话UTC |
| 普通String | 有上限且不超过768时VARCHAR，否则LONGTEXT；业务约束仍由原生成代码检查 |
| secret String | VARCHAR(255)，存摘要，不套用输入明文长度 |
| 枚举 | 按目标枚举成员长度规划VARCHAR |
| Ref | 目标主键的实际存储类型，外键RESTRICT |
| 可空字段 | 保留NULL；非空字段NOT NULL |

表为InnoDB，文本使用`utf8mb4_0900_bin`完整值比较。unique只建立完整值唯一键，不建前缀唯一索引。唯一文本缺少不超过768的明确长度、Decimal缺少精度/scale契约、外部actor未提供credential实现、超出标识符/行预算等情况在导出时返回`SIR-DEPLOY-UNSUPPORTED-001`。普通generate仍遵循原合同。

与Java映射不兼容的MySQL保留字在独立执行器的服务器核验中拒绝，不让健康检查成功掩盖业务SQL不可用。schema漂移或迁移失败保留现场，不自行repair或改旧数据。

## 5. 依赖与资源

构建基底`maven:3.9.9-eclipse-temurin-21`，运行基底`eclipse-temurin:21.0.7_6-jre-jammy`，MySQL `8.4.5`；工具使用Flyway core/mysql `11.8.2`和Connector/J `9.3.0`。CI #93已在本部署样例中完成镜像取得、应用/工具构建、迁移与启动；不等于全部业务或生产支持矩阵，HTTP门仍有阻塞，也未锁定镜像摘要。新增Flyway/Actuator/springdoc依赖只出现在交付源码，不加入工具链运行依赖。

默认构建容器与测试容器1536 MiB/1.5 CPU，Maven堆512 MiB、元空间256 MiB；运行MySQL1024 MiB、应用768 MiB、一次性迁移384 MiB，分别设置CPU预算和JVM参数。构建、初始化、应用启动按顺序进行。部署入口支持`KCG_BUILD_MEMORY/CPUS`、`KCG_DB_MEMORY/CPUS`、`KCG_APP_MEMORY/CPUS`、`KCG_MIGRATE_MEMORY`和`KCG_TEST_MEMORY/CPUS`，按CI/目标机预算调整，不按当前开发机余量承诺容量。

## 6. CI门

verify新增`standalone-deployment` job，消费同SHA全量门产出的CLI，而不是在新job重复构建整个工具链。公开package导出后，CI只使用包中的部署入口准备数据库，没有手工夹具DDL。

`.github/scripts/deployment-smoke.py`检查首次部署、真实注册/读取/登录/受保护写入、只读核验、应用不能DDL、按文档server与路径发出的Swagger式真实请求、经公开入口的`contract-test`、停止/启动持久化、重复同包部署保持一份Flyway历史及原密码、丢失数据卷后start拒绝。它与合同测试都不替代双Token完整需求判题。

产物：`standalone-cli`、`backend-source-delivery`、`deployment-evidence`（export结果、commit/run绑定报告、脱敏日志、`reports/contract`下的合同报告与Surefire报告）。`report.json`另记`swagger`、`contract`分项、合同检查计数及`NOT_VERIFIED`清单。不上传状态或数据卷。已授权并执行CI #93；总结果failure、分项与artifact见[报告](DEPLOYMENT_DELIVERY_REPORT.md)，云端须修复后补齐同SHA证据。

## 7. 接口文档与合同测试（步骤②）

交付应用带springdoc 2.8.9。部署后Swagger UI在`http://127.0.0.1:<port>/swagger-ui/index.html`，OpenAPI JSON在`/v3/api-docs`。文档从实际Controller/DTO得到；生成的`KcgOpenApiConfiguration`只补充两类Controller表达不了的信息：认证接口的Bearer要求（在“Authorize”中填访问令牌）及空体401，以及每个接口在导出时由目标模型声明的错误信封响应（请求体接口的400 `INVALID_REQUEST`与SIR声明错误）。Swagger可发真实请求，但它是手动调试工具，不是判题器。

```sh
sh deploy/deploy.sh contract-test --instance demo --state-root /absolute/path/to/demo-state
```

`contract-test`要求实例在运行；在受限Maven容器内经Compose网络请求`http://app:8080`，运行生成的`app/src/test/java/<基础包>/contract/HttpContractIT.java`。它只发出会在业务代码前被拒绝的请求，不写数据，可对任一实例运行：Swagger UI与OpenAPI可达、文档中的操作/认证/错误响应与导出时的接口表一致、认证接口对无凭据/畸形/未签发令牌回401、非认证请求体接口对畸形JSON/未知属性/`notBlank`/`length`违例回400错误信封。成功调用、声明业务错误和认证后的校验需要业务数据，记为`NOT_VERIFIED`，合同通过不等于业务验收。

结果在`<state>/reports/contract/`：`contract-report.tsv`逐项给出PASSED/FAILED/NOT_VERIFIED及原因，另有Surefire报告。成功输出`KCG_CONTRACT_PASSED`，失败以退出码5结束。普通`mvn test`不选中该类，也不需要运行中的服务。
