# 独立部署交付：步骤①实现报告

- 日期：2026-10-17；状态：**BLOCKED_CI / HANDOFF_READY**。代码已推送，首轮CI部署成功但HTTP验收异常，步骤①未关闭；负责人要求转交云端，见[接手说明](DEPLOYMENT_CLOUD_HANDOFF.md)。
- 授权：负责人已允许开始实现已确认的[方案11](design/11-portable-deployment-and-http-acceptance.md)。本轮先交付步骤①，步骤②Swagger/随包测试、步骤③保留数据更新尚未实施。
- 基线：`cf44450`；受测提交：`a7cba11022c04e6a07cb47d5070bde39a06b0adf`；[verify #93](https://github.com/HoloNova/Software-IR/actions/runs/39002409782)。本次交接仅更新文档，受测代码不变。
- 使用合同：[部署包v1](DEPLOYMENT_DELIVERY.md)。

## 1. 交付

1. `kcg package`与typed `DeploymentPackageApplication`：复用有界读源和正式编译，把普通应用、源快照、部署/迁移源码和摘要一次发布到新目录。没有Docker/网络/Git副作用，不改原generate。
2. `SpringBootDeploymentPlanner`与`DeploymentPlan`：复用原目标的表列名/主键和正式引用身份，规划物理schema、实际存储、唯一和关系约束、运行依赖及镜像选择。无损映射尚不具备的情况明确拒绝。
3. `SpringBootDeploymentGenerator`：纯渲染源码、Flyway V1、物理合同、Docker配方、Compose和随包部署入口；实际执行由目标机的独立工具完成，不把编译器塞进运行镜像。
4. 部署入口：包摘要与实例/环境绑定、持久配置、受限串行构建、明确初始化、真实TCP/HTTP就绪、停止/重启、卷丢失拒绝。迁移账户与应用账户分离，不注入测试种子。
5. CI配置：新增独立部署job和烟测脚本，测试公开导出包及随包入口，上传源码包、脱敏报告和日志。脚本本机只做语法检查，已在CI实际执行并遇到第3节所述阻塞。

没有改变Parser、Semantic、通用Change及原Git版本应用合同。部署输出使用独立package入口；目前不支持对部署包app使用原version-plan/apply更新。不同包不能接管既有v1状态，持续更新留给步骤③。

## 2. 定向验证

本机只做源导出、配置逻辑、CLI/生成器边界与原CLI流程回归；没有拉镜像、启动Docker服务/数据库/业务应用、构建生成工程或执行网络业务测试。

| 类 | run / fail / error / skip（该类最新XML） |
| --- | --- |
| DeploymentPlannerTest | 4 / 0 / 0 / 0 |
| DeploymentPackageApplicationTest | 4 / 0 / 0 / 0 |
| KcgPackageCommandTest | 1 / 0 / 0 / 0 |
| CliProductBoundaryTest | 5 / 0 / 0 / 0 |
| CliProductionBoundaryTest | 3 / 0 / 0 / 0 |
| KcgWorkflowCommandsTest | 3 / 0 / 0 / 0 |
| GeneratorProductionBoundaryTest | 6 / 0 / 0 / 0 |
| **合计** | **26 / 0 / 0 / 0** |

7类最新Surefire XML来自lowering、generator、application和CLI的`target/surefire-reports/`，不是一次全量运行，也不混入其他历史XML。

覆盖：UUID与引用主键类型一致、secret摘要容量、完整唯一键、未支持类型/身份适配器拒绝；导出确定性、源原字节、已有/重叠输出根；单独编译执行的临时配置helper重复使用密码与环境ID、拒绝缺失密码和改包；用拒绝执行的Docker替身证明状态祖先symlink在任何镜像取得前就被拒绝且不改包；真实CLI导出/参数；原CLI多源编辑/Git临时工程流程；生成器纯渲染和CLI无文件I/O边界。

边界回归初次发现`src/main/java/io/...`路径字面量被既有扫描器匹配为`java/io/`依赖；独立随包工具改为`kcg.delivery`包，未放宽门禁或删测试，复测通过。最后补全物理核验及状态目录祖先symlink拒绝，复测生成器边界6项、导出/配置逻辑4项通过；它仍不证明随包SQL或容器能实际运行。

本机命令使用单构建、离线仓库、限定测试：

```sh
MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m' \
systemd-run --scope -q -p MemoryMax=1536M -p CPUQuota=125% -- \
  mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl kcg-cli -am \
  -Dtest=DeploymentPlannerTest,DeploymentPackageApplicationTest,KcgPackageCommandTest,CliProductBoundaryTest,CliProductionBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m' test
```

后续仅选需要的`GeneratorProductionBoundaryTest,DeploymentPackageApplicationTest,KcgWorkflowCommandsTest`；最终模板与路径变更只复测生成器边界和导出/配置逻辑；末次物理预算修正只复测DeploymentPlannerTest 4项通过，不重复其他已通过范围。执行前实测2 vCPU、3875 MiB总内存、2302 MiB可用；scope上限1536 MiB/125%，Maven和测试fork各384 MiB堆/192 MiB元空间，配置helper与CLI小子进程另有限制，无Maven -T。结束没有遗留java/mvn/mysqld进程。

CI Python脚本通过AST语法检查，workflow经本地已有PyYAML解析；没有执行该脚本或安装额外依赖。文档/差异另做检查。

## 3. CI实际结果（verify #93）

| 门 | 状态 |
| --- | --- |
| 上述本机轻量定向测试与边界回归 | PASSED |
| 新CLI fat JAR、两条全量门、原业务IT及独立验证IT | PASSED |
| 容器镜像取得、实际Maven构建和工具依赖（本样例） | PASSED |
| 公开包首次建库、Flyway与真实应用启动 | PASSED |
| HTTP烟测 | 执行中JSON解析异常，未完成；artifact原字段为NOT_RUN |
| DML账户无DDL权限、重启/重复部署保留数据配置 | NOT_RUN |
| 丢失卷拒绝 | NOT_RUN |
| CI拥有资源清理 | PASSED |
| Swagger、随包JUnit网络套件、版本迁移 | 未实施（后续步骤） |

新job `standalone-deployment`消费同SHA主job的`standalone-cli`，使用独立MySQL实例，无测试夹具DDL；固定验收数据仅通过HTTP创建。产物`backend-source-delivery`与`deployment-evidence`不包含运行状态或密码。本run主job全绿，部署job失败，workflow总结果failure。[deployment-evidence](https://github.com/HoloNova/Software-IR/actions/runs/39002409782/artifacts/12090216090)含report.json、export.json、构建与容器日志；[backend-source-delivery](https://github.com/HoloNova/Software-IR/actions/runs/39002409782/artifacts/12090102304)保留实际源包，另有standalone-cli、surefire-reports与conformance-evidence。

报告失败为`Expecting value: line 1 column 1 (char 0)`。日志证明冷构建、Flyway及应用/数据库readiness已完成；脚本之后对HTTP结果无条件解析JSON，而生成的鉴权401允许空体，存在明确协议不匹配。当前没有失败URL/响应体证据，触发请求待确认，不能将推断当复现。云端先完成请求级定位与脚本/实际故障修复，再取得修复提交同SHA CI，具体步骤见[接手说明](DEPLOYMENT_CLOUD_HANDOFF.md)。

## 4. 已知首轮边界

仅Linux x86_64同机Docker daemon；需要公共依赖网络；无源包恶意篡改签名认证、生产安全/容量资格或全平台资格。唯一文本要明确长度且≤768；Decimal精度/scale未解决；外部actor无credential实现拒绝；物理标识符、行预算和实际服务器保留字有明确限制。

数据卷/配置丢失、未知绑定、结构漂移、DDL部分失败保留现场，不自动清库或repair。数据库迁移不是可随Git回滚的文件变更。当前仅同包重复部署及重启，不允许不同包升级；长期G阶段未因此全部关闭。
