# 第五步：完整源码包与一键 Docker 部署

日期：2026-09-08。状态：推荐设计草案。使用[共享契约](00-shared-contracts.md)与[数据库执行规则](04-database-evolution.md)。下列路径、服务和命令入口是交付设计，不是已经生成的可执行工具。

## 1. 部署必须满足的条件

**实现边界：**当前 Generator 已输出 Maven 与 Java 源文件，属于源码交付基础的部分实现；下面的完整 ZIP、ReleaseManifest、bootstrap、deployment-runner、入口脚本和 Docker/Compose 部署闭环尚未实现。G4 以 GEN-03、DEP-01..08 验证包的独立性，依赖 G3 的真实数据库迁移证据；G6 才把已验证的包接到网页下载。[实现依据](implementation-baseline.md)

Lowering 确定依赖与容器配方，Generator 纯渲染，导出服务计算清单并打包；部署入口实际调用 Docker，容器内构建工具取得依赖，deployment-runner 负责数据库操作。生成 pom.xml 不等于依赖已经安装，生成源码文件也不等于一键部署已完成。

用户只安装并运行 Docker/Compose。解压下载包后，Windows 使用系统自带命令环境，macOS/Linux 使用系统 shell；不要求宿主 JDK、Maven、MySQL、Python、Node、jq 或 KCG-Code。

初次构建会下载声明的公共基础镜像和 Maven 依赖。用户没有平台账号登录态、平台已停机，仍能从完整源码构建和运行已下载包。首轮默认本地课程演示；完全离线镜像包、公网证书和服务器运维属于后续部署配置。

## 2. 下载包文件组织

```text
coursework-backend/
  pom.xml
  mvnw / mvnw.cmd
  .mvn/wrapper/
  app/
    pom.xml
    src/main/java/
    src/main/resources/
    src/test/
  deployment-runner/
    pom.xml
    src/main/java/
    src/main/resources/
    src/test/
  sir/                          # 固定源快照及模块锁
  deploy/
    bootstrap/                  # 无 Maven 插件的最小包校验/引导源码
    Dockerfile
    compose.yaml
    profile.json
    release-manifest.json
    migration-definition.json
    migrations/                 # 完整、不可变的有序历史
  docs/
    openapi.json
    api-examples.md
    errors.md
  deploy.cmd / deploy.sh
  update.cmd / update.sh
  start.cmd / start.sh
  stop.cmd / stop.sh
  status.cmd / status.sh
  recover.cmd / recover.sh
  README.md
```

所有首轮必要执行器逻辑随源码提供，依赖公开可取得并锁定版本的库，不把完整 KCG 编译器偷偷作为私有二进制依赖。文件清单规则由共享契约的 ReleaseManifest 定义，包内不含真实密钥、本地 environmentId、数据库行或缓存目录。

开发与部署生成物均可阅读，包仍是普通 Maven Spring Boot 工程。用户自行改源码后可以自行开发，但受管理更新先检查发布输入摘要，不能默默覆盖用户修改。

## 3. 容器与本地入口分工

| 组件 | 职责 | 不拥有的权限 |
| --- | --- | --- |
| 宿主入口脚本 | 检查 Docker、选择本地实例、调用 build/Compose、读取退出结果 | 解析 SIR、生成 SQL、修改业务数据库 |
| build 阶段 | 使用固定 JDK/Maven 构建 app 和部署执行器 | 用户业务库凭据、平台账号 |
| db 服务 | MySQL 数据服务与专用空 schema 的首次准备 | 平台发布、SIR 语义判断 |
| deploy-runner 工具服务 | 校验包/目标/数据，初始化、迁移、恢复和写收据 | 任意 SQL 生成、Docker socket、平台 Bundle/CURRENT |
| app 服务 | 业务 HTTP 与 DML、schema 启动核验 | schema DDL、迁移 history 写入、部署管理凭据 |

宿主脚本只调用固定模板生成的命令与受约束参数，正确处理空格、中文和特殊字符路径。不要拼接用户 SIR 内容作为 shell 命令。JSON 解析、哈希和密钥生成由容器内自带的 Java 辅助入口完成，不额外要求宿主工具。

包核验不能依赖已经完成业务 Maven 构建的 deployment-runner。推荐先在固定通用 JDK 容器中用 javac 构建并执行随包 bootstrap 源码，它不使用 Maven 插件或业务依赖，只完成清单格式、摘要和初始引导校验；通过后才构建 app 与完整 runner。需要的解析辅助代码同样随包提供。bootstrap 基底也必须公开可取得并由交付 Profile 锁定，不能变成平台私有安装前提。包内自带摘要只证明一致性，不把这一步描述成对任意恶意下载包的签名认证。

Windows 入口不修改系统全局执行策略；若使用系统 PowerShell 辅助，只依赖已验证的系统功能。POSIX 文档同时提供 sh deploy.sh，避免解压工具丢失可执行位造成无法启动。

## 4. 持久状态布局

| 存储 | 包含内容 | 生命周期 |
| --- | --- | --- |
| dbDataVolume | MySQL 数据文件 | 跨源码目录、镜像和发布版本保留 |
| deployStateVolume | 环境身份、冻结执行计划、历史收据、管理凭据和恢复证据 | 只由部署流程维护，正常 stop/update 不删除 |
| appConfigVolume | 业务账户连接配置及最小运行信息 | app 只读挂载，不包含管理密码 |
| 构建缓存 | Maven 构件和构建层 | 可丢弃，不能混入业务数据或凭据 |

每个实例由 projectId + environmentId 绑定。Docker 资源名使用受约束 ID，不由下载目录或显示标题推导。Docker 标签用于发现候选资源，卷内身份与数据库内身份用于交叉核验；标签相同本身不证明可以接管。

运行 Compose 将持久卷声明为 external 并指向绑定的实际名称。Docker 对不存在的 external volume 报错，不会像普通声明那样自动创建。[Compose volumes](https://docs.docker.com/reference/compose-file/volumes/) 首次 deploy 入口明确创建新卷，update/start 入口没有创建缺失卷的权限。

同项目单一实例可自动定位；多个实例要求一次选择；只发现部分卷、矛盾身份或损坏 state 时停止。把新包解压到新目录，不需要复制旧 .env 或改卷名。

## 5. 首次引导与凭据

首次部署先构建工具镜像，再由引导入口分配 environmentId 和本地随机凭据。先登记初始化意图，再创建卷与配置；中断后沿相同尝试恢复，不能重复生成另一套密码接管原数据库。

MySQL 官方镜像支持首次初始化配置与从文件读取密码；已有数据目录不会因再次设置初始化环境变量而重新初始化。[MySQL 官方镜像](https://hub.docker.com/_/mysql) 因此初始化参数只用于新的空实例，不能作为更新或密码轮换机制。

首轮区分数据库管理/迁移账户与业务 DML 账户。业务账户不拿默认初始化用户的全部 schema 权限；引导步骤显式授予所需 DML 和必要元数据只读权限。数据库端口默认不向宿主公开，app 端口默认绑定回环地址。

真实密码通过文件挂载或已验证的配置树方式提供，不放入 build args、镜像层、下载包或平台日志。业务应用只读取自己的运行配置，不能读取 deployState 中的管理凭据。[Spring Boot 外部配置](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html)

初始业务管理员只在明确的新建流程中创建一次。密码在本地产生、数据库只保存散列，通过本地受控结果显示给用户；重启或 UPDATE 不重置。演示课程与学生数据不在普通启动时注入。

## 6. 构建配方与独立性

Dockerfile 使用固定版本/摘要的构建基底和运行基底，分别构建 app 与 deployment-runner 的完整可运行产物。最终业务运行镜像不携带 Maven 或 SIR 编译器。

先复制 Wrapper、POM、插件配置和依赖锁定输入，再准备依赖，之后复制源码构建。Maven 缓存挂载复用已下载构件；构建上下文排除本地状态、凭据、target、日志和无关文件。[Docker 构建缓存](https://docs.docker.com/build/cache/optimize/)

源码包必须能在冷缓存中成功构建。平台可以同时提供预构建镜像，但镜像摘要与源码发布记录关联；构建回退不得要求重新联系平台获得私有组件。架构支持列表由 Profile 固定，未验证的 CPU/操作系统组合明确拒绝而非宣称全平台。

首轮资格先验证 Windows + Docker Linux 容器的 amd64 路径；POSIX 入口在一个 Linux amd64 环境验证。macOS/arm64 作为发布支持矩阵的后续项目，未经验证不标称已支持。

## 7. 并发部署协调

同一实例最多一个改变部署状态的尝试。推荐用 Docker 中具有唯一环境名的协调占位容器获取排他权，标签记录 attemptId 与所有者 token；创建同名资源冲突则返回忙，不覆盖它。

新建流程在分配实例前先对 projectId 的创建动作协调，再转入实例协调，防止双击两次在未发现旧实例时同时新建。多实例创建须明确选择新的实例操作。

正常结束只释放本次准确身份的协调资源。脚本中断后不凭超时或 PID 自动删除占位；recover 先确认原部署执行容器、数据库活动和锁状态，并明确结束旧尝试，才转移协调权。占位不是数据库锁，迁移阶段还需 04 的数据库锁。

本设计不让 deploy-runner 挂载 Docker socket；宿主入口负责容器动作，runner 负责数据库与本地状态。具体协调命令在实现时需故障注入验证，不把 Docker 标签当成所有跨进程互斥的证明。

## 8. deploy 首次部署顺序

1. 检查 Docker/Compose 可用、Profile 支持平台和必要端口。
2. 校验包格式与清单；取得项目创建协调权，确认用户意图是新部署。
3. 构建镜像；失败返回构建诊断，不创建业务表。
4. 登记新实例与尝试，创建持久卷，生成一次性本地配置；重复调用复用同一次已登记结果。
5. 启动 db，等待真正的可连接健康状态；初始化超时保留状态供后续检查。
6. 显式调用 deploy-runner 的 initialize，执行 04 的核验和完整历史。
7. 数据库 READY 后初始化必要业务管理员，再启动 app。
8. app 核验项目/实例/schema 契约并通过健康检查；写 DeploymentReceipt 和已部署 releaseId。
9. 显示服务地址、接口文档位置与初始账号信息，释放本次协调资源。

数据库就绪不同于容器进程存在，Compose 需使用健康条件。[Compose 启动顺序](https://docs.docker.com/compose/how-tos/startup-order/) 工具服务放在独立 profile 并由入口显式调用；普通 compose up 不自动启动迁移。[Compose profiles](https://docs.docker.com/compose/how-tos/profiles/)

app 普通启动遇到 schema 不匹配或 DB_RECOVERY_REQUIRED 时拒绝启动，不自动改库。用户绕过脚本直接 compose up 不得因此触发初始化或清库。

## 9. update 更新顺序

1. 从新包读取 projectId 和 acceptedBaseReleases，发现并选定已有 environmentId。
2. 检查所有卷存在，状态、数据库身份与清单匹配，取得实例协调权。
3. 构建新版本镜像，保留旧应用运行；构建失败退出，不停旧服务。
4. 记录待部署 releaseId 与镜像摘要，停止旧 app，确认没有该实例业务写入者。
5. 显式调用 runner update，重新校验数据条件，执行受支持的迁移。
6. 数据库 READY 后启动新版 app；健康检查通过再登记本地已部署 releaseId。
7. 成功后保留旧收据与必要旧镜像引用；清理构建临时文件不触碰数据卷。

不存在旧实例、数据卷或父版本时不新建。已部署相同 releaseId 但文件摘要不同为冲突；相同版本同摘要且现场核验通过才返回已完成。

应用启动失败而数据库已更新时保留新数据库，部署标记 FAILED；重试只进行已确定的启动阶段并重新核验目标。不要自动运行旧应用或降级数据库。若迁移中断，进入 RECOVERY_REQUIRED，处理方式以 04 为准。

## 10. 收据与恢复入口

DeploymentReceipt 包含 attemptId、操作模式、projectId、environmentId、源/目标 releaseId、包摘要、实际镜像摘要、数据库版本/历史摘要、各阶段结果、错误码及证据引用。真实密码和业务行不进入收据。

recover 只消费本地已有尝试与当前包支持的协议，明确输出哪些步骤已完成、哪些需验证，以及是否可以继续。未知执行器协议、history 不一致、旧尝试仍活动时返回阻断，不替换旧状态文件。

首次初始化时 db 已 READY 但管理员尚未创建的中断，依据独立引导标记完成剩余一步；不得重跑迁移或重置已存在账号密码。若管理员创建结果不明，检查唯一身份与引导标记后判定，证据不足时停止。

## 11. start、stop 与本地状态保护

start 仅使用已部署 releaseId 的配置/镜像绑定并检查 schema；不能拿任意下载目录里的较新源码冒充当前版本。stop 停应用或实例服务但保留命名卷、凭据和收据。

默认路径不使用 compose down --volumes、volume prune 或全局 Docker 清理。Docker 的 --volumes 会改变删除范围，因此不能与普通停止混用。[Compose down](https://docs.docker.com/reference/cli/docker/compose/down/)

包重新解压、项目标题变化和镜像重建不会改变 environmentId。用户从外部删除卷后，status 明确报告 TARGET_MISSING，start/update 停止，禁止用新空卷造成“看似数据消失”的启动成功。

## 12. 失败分类与用户反馈

| 情况 | 对用户说明 | 是否已触及业务数据 |
| --- | --- | --- |
| Docker 未启动或 Compose 不受支持 | 指出主机前提和可执行检查 | 否 |
| 公共镜像/依赖下载失败 | 区分网络、仓库和验证失败 | 否 |
| 包摘要、项目/实例或基线不匹配 | 指出冲突对象与需使用的版本 | 否 |
| 本地端口冲突 | 提供另选端口的单次输入 | 否 |
| 数据检查阻断 | 说明约束与受影响计数，避免输出完整敏感行 | 否 |
| 部分迁移失败 | 明确需恢复，保留证据位置 | 可能有已提交的兼容结构步骤 |
| app 健康检查失败 | 区分数据库已成功与应用失败 | 数据库可能已完成升级 |

诊断可由用户主动导出并上传；默认运行不回传本地库状态、凭据或数据。网页“可下载”不显示为“你本地已部署”。

## 13. 本步验收

干净机器只准备 Docker/Compose，平台断开但公共依赖网络可达，下载包能完成构建、首建、登录和接口调用。必须包含冷缓存、热缓存、中文/空格目录、解压后执行位丢失、端口冲突及重复双击。

更新用新目录中的 r0002 包升级 r0001，保持原数据、凭据和 environmentId；缺任一卷、换项目、改包内容、跳版本均被正确阻断。两个并发入口不能同时迁移。

故障验收覆盖构建失败仍运行旧版、迁移中断、初始账号引导中断、数据库成功但 app 失败、脚本退出后恢复证据仍可读取。只有这些通过后，才可以对外使用“一键部署并支持保留数据更新”的描述。
