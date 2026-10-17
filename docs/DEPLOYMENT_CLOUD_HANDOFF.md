# 独立部署步骤①：云端接手说明

- 日期：2026-10-17；收尾节点：**实现已推送、CI首轮结果已归档，步骤①未验收，交云端继续排障**。
- 接手结果（同日）：第2节判断属实，云端只修验收脚本（`30f50bd`），同SHA的[verify #94](https://github.com/HoloNova/Software-IR/actions/runs/39019809176)全部success，**步骤①已关闭**；分项与artifact见[报告第5节](DEPLOYMENT_DELIVERY_REPORT.md)。以下保留交接时的原文作为记录。
- 实现提交：`a7cba11022c04e6a07cb47d5070bde39a06b0adf`，已在`origin/main`。本次交接提交只改文档，不改变受测代码，使用`[skip ci]`避免重复重型验证。
- 接手依据：[方案11](design/11-portable-deployment-and-http-acceptance.md)、[交付合同](DEPLOYMENT_DELIVERY.md)、[实现与证据报告](DEPLOYMENT_DELIVERY_REPORT.md)。负责人要求转交云端，本机不继续修代码或展开步骤②③。

## 1. 已完成到哪里

实现了`kcg package`、显式MySQL物理规划、Flyway V1、独立Docker/Compose部署工具和CI烟测。普通generate、通用增删改与Git版本合同未改。此前26项本机定向检查通过，没有在本机部署或跑业务测试。

CI [verify #93](https://github.com/HoloNova/Software-IR/actions/runs/39002409782)实际测试上述实现提交：

| 范围 | 实际结果 |
| --- | --- |
| 原主job：两条全量门、业务IT、独立验证IT、单文件CLI烟测 | 全部success |
| 公开CLI导出交付包 | success |
| 包内应用与迁移工具的容器冷构建 | 两次`BUILD SUCCESS` |
| MySQL初始化、Flyway V1、物理核验、应用启动及readiness | success；日志有`KCG_DATABASE_READY`、`KCG_DEPLOYED`，应用日志确认Hikari连接成功 |
| 后续HTTP烟测 | 执行过程中JSON解析异常，整门未通过；artifact仍标`httpSmoke: NOT_RUN`，不能理解成没有发过请求 |
| 账户权限、重启/重复部署保留数据、丢失卷拒绝 | NOT_RUN，失败后未到达 |
| 本次CI拥有资源的清理 | PASSED |
| 总workflow | failure，**不能关闭步骤①** |

## 2. 云端第一项工作：确认并修正HTTP验收阻塞

直接证据：`deployment-evidence/report.json`记录`Expecting value: line 1 column 1 (char 0)`；`01-process.log`证明部署成功，应用容器日志没有启动失败。当前报告未保存具体失败URL、HTTP响应体或Python调用栈，因此尚不能声称已经确认是哪一次请求触发。

已从代码确认的协议不匹配：

- `.github/scripts/deployment-smoke.py`的`request()`在核对状态码后，无条件执行`json.loads(data)`，包括只检查401的未认证请求。
- `sir-generator-spring-boot/.../internal/ApplicationRenderer.java`生成的鉴权拦截器，在身份无效时只设置`SC_UNAUTHORIZED`并返回false，没有写JSON响应体。
- 因此空体401是一条明确会触发脚本异常的路径，优先检查`request('/api/post-note', ..., expected=401)`；这是优先定位点，不冒称已有请求级日志或复现证据。

建议顺序：先补充必要的请求路径、状态、Content-Type、响应体是否为空等诊断（不得记录令牌/密码），确认触发点；把“只检查状态的空体响应”和“必须有JSON业务结果的响应”区分处理，后者仍严格解析和断言，不能用统一忽略解析异常让测试变绿。不因验收脚本问题重设计鉴权协议；若证据显示是产品问题，再按实际故障修复。

修复后提交并推送，让CI验证**修复提交的同SHA**。必须走公开package及随包部署入口，不用手工DDL绕过建库，也不删断言、清库重建或将NOT_RUN改成PASSED。

## 3. 取得证据与关键文件

```sh
# 以下只读命令不启动服务。
gh run view 39002409782 --json status,conclusion,headSha,jobs
gh run download 39002409782 -n deployment-evidence -D /tmp/kcg-deployment-93
```

同一run的artifact：

- [deployment-evidence](https://github.com/HoloNova/Software-IR/actions/runs/39002409782/artifacts/12090216090)：报告、导出摘要、构建/部署与应用/MySQL日志。
- [backend-source-delivery](https://github.com/HoloNova/Software-IR/actions/runs/39002409782/artifacts/12090102304)：实际导出的源包。
- `standalone-cli`、`surefire-reports`、`conformance-evidence`：同SHA CLI与原门证据。artifact会过期，及时从run下载；不提交运行密码或状态目录。

关键代码：

| 位置 | 用途 |
| --- | --- |
| `.github/scripts/deployment-smoke.py` | 首轮HTTP、权限、持久化、丢失卷与清理验收 |
| `.github/workflows/verify.yml`的`standalone-deployment` | 下载同SHA CLI、导出包、执行烟测与上传证据 |
| `sir-generator-spring-boot/.../internal/DeploymentTemplates.java` | 随包部署脚本、Compose、配置初始化、Flyway工具 |
| `sir-generator-spring-boot/.../internal/DeploymentEngine.java` | 物理DDL及其他交付文件渲染 |
| `sir-lowering-spring-boot/.../api/SpringBootDeploymentPlanner.java` | 物理映射、依赖/镜像选择与不支持情形拒绝 |
| `sir-toolchain-application/.../api/DeploymentPackageApplication.java` | 编译复用与完整新目录导出 |
| `sir-toolchain-application/src/test/resources/deployment/dual-token/project.sir` | 有账号长度界限的部署样例，不是测试DDL |

## 4. 关闭步骤①的条件与下一步边界

修复后的CI须完成并留存：HTTP注册/读取/登录/鉴权写入；应用账号可访问业务表但不能DDL；stop/start与同包deploy保留数据、密码和唯一Flyway V1；丢失卷后start拒绝；拥有资源全部清理。原主job也须通过。更新报告时给出受测SHA、run URL、artifact及分项结果。

负责人2026-10-17改为不等步骤①的CI结果、并行推进步骤②（合同见[方案11第12节](design/11-portable-deployment-and-http-acceptance.md)）；步骤①仍单独以同SHA CI关闭，步骤②不抵扣它。不同包升级仍属步骤③。步骤②③按方案11执行，不重开R1–R5，不给Change添加部署专用操作，不发布镜像。当前开发机仍禁止部署/业务测试；默认用CI，负责人明确切换的新机器才作部署环境，并遵守`AGENTS.md`资源约束。
