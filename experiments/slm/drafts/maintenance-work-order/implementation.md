# 报修工单判卷落地记录

实现基线：main `9cab86894a1e55af68876af562e677a734d28393`。负责人在规格确认后另行授权 DDL、种子、业务 IT 和两个 workflow 的接入。本记录先冻结提交前的本机验证现场；下文 NOT_RUN 是该阶段状态，不是四门合格报告。负责人随后已明确授权将本轮判卷实现作为一次提交普通推送，并运行同 SHA CI；最终资格以该提交的 run URL 和原始 artifact 为准。

## 1. 交付与设计理由

- 运行参考：`sir-toolchain-application/src/test/resources/valid/maintenance-work-order.sir`，与批准的 `reference.sir` 内容一致。
- 夹具：同模块 `src/test/resources/conformance/mysql/maintenance-work-order-ddl.sql`、`maintenance-work-order-seed.sql`，原样提取 spec §2/§3 的两个 SQL 块，保留 Optional 引用的物理列 `assignee`、外键与真实凭据。
- 入口：`src/test/java/io/kcg/sir/application/conformance/MaintenanceWorkOrderBusinessConformanceIT.java`。B/W/D/P/L、R/E、X 检查分别在 `MaintenanceWorkOrderChecks`、`MaintenanceInspectionChecks`、`MaintenanceConcurrencyChecks`；逐项索引见 [traceability.md §6](traceability.md#6-本阶段源码入口尚无业务资格)。
- 两题复用新增的 `BoundedProblemConformance/Fixture/Http/State/Race`，并继续组合已有 `BusinessSliceHarness`、`HttpAssertionClient`、`BlogLockWaits` 和候选输入/证据底座。共享的是连接、完整比较和屏障机制，不是业务判断；设施权限、版本、可选值和巡检谓词仍由本题提供。

版本化报修仅走单行 PATCH；生成/批关闭只操作非版本化巡检。每次 GET/拒绝比较独立 JDBC 连接读取的五表全部行列；成功写只允许由原始请求、夹具常量和新 ID 构造的精确差异。日期用 Instant 加完整 24 小时，不把响应和候选自己返回的计数互相比对当作正确性证明。

X01/X02/X03 分别实现同版本一胜五冲突、批关闭六请求均成功、生成/关闭两种完整串行次序。每轮所有 N 个独立客户端都须在真实 MySQL 等待图中形成不同连接/事务的锁链，才释放控制锁；finally 释放并收齐。客户端超时会终止整段执行，防止服务端仍有在途写入时重放种子。它们目前仅编译，未运行。

## 2. 工作流与证据约束

`verify.yml` 的 Business conformance ITs 增加本题及 `-Dkcg.maintenance-conformance.enabled=true`。后置检查要求唯一 `maintenance-slice-report.txt` 的固定第二行是 `verdict=PASSED`，缺失、重复、FAILED、NOT_RUN 均阻断。

`slm-business-gate.yml` 的 IT choice 和启用属性映射增加 `MaintenanceWorkOrderBusinessConformanceIT`。仍用同一 IT 判参考或候选；既有 Harness 检查候选所属题、源码、工具链/oracle 绑定并输出 `slm-runtime.json`，候选门仍强制 business=PASSED。没有另写“宽松候选判卷”。

## 3. 本机定向验证与 TDD 证据

环境实测 Windows、Java 21/Maven 3.9.14，18 逻辑核，开始时可用内存 3962 MiB。本轮 Maven 只用两个活跃处理器，堆 768 MiB/元空间 256 MiB；测试 fork 堆 256 MiB/元空间 128 MiB。没有 `-T`、全量构建、MySQL或生成应用进程。

先写合同与比较器反例：资源尚不存在时，4 项合同测试以 `missing resource` 失败；共享接口不存在时，比较器测试编译失败。落地后首轮 13/14 通过，唯一反例发现 Jackson 空输入产生 `MissingNode` 而非 null；最小 JShell 调用确认重复键/尾随文档已拒绝，只需修正新比较器的空输入检查，未改工具链。补足归属、唯一键、严格关闭谓词等反例后，同一目标最终 20/20 通过。

```powershell
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
$env:MAVEN_OPTS='-Xmx768m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2'
mvn -B -o -q -pl sir-toolchain-application -am `
  '-Dtest=BoundedProblemReferenceTest,BoundedProblemOracleTest' `
  '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=-Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2' test
```

| 范围 | 实测结果 | 证明范围 |
|---|---|---|
| `BoundedProblemReferenceTest` | 6/6，0失败/错误/跳过 | 两题批准参考/SQL一致，reference/base均内存生成，合同路由/auth/201匹配生成器，投影列映射及external/readiness配置正确 |
| `BoundedProblemOracleTest` | 14/14，0失败/错误/跳过 | null/缺键/JSON类型、顺序/total、UTC/Day、声明信封、不可变全表差异、JDBC归一化、过期响应、严格关闭谓词、跨店/重复关系及裸整数反例 |
| 两个workflow | YAML解析通过；20段shell均 `bash -n` 通过 | 新IT选择/属性/参考执行集合绑定正确；不是执行CI |
| 参考资格摘要 | 6种本机合成报告反例全部通过 | 缺两份、缺一份、真实通过、响应伪造通过、NOT_RUN、重复报告的接受/拒绝符合预期 |
| Java编译 | PASS | 上述定向 Maven 已编译全部新增 IT/辅助类；不代表生成应用构建 |
| AFT诊断 | 不可用 | 两次inspect桥接超时；不冒称LSP全绿，编译以实际Maven结果为准 |
| 差异/边界 | `git diff --check`通过；生产源码/pom/参考/base/题面无差异 | 没有引入工具链提交或改动fitness/events |

原始 JUnit 报告在模块 `target/surefire-reports/TEST-io.kcg.sir.application.conformance.BoundedProblem{Reference,Oracle}Test.xml`。初始 RED 报告副本在根 `target/bounded-problems-red/`；工作流检查记录在 `target/bounded-problems-workflow-check.json`。这些本机 target 产物未加入 Git，不冒充 CI artifact。覆盖率未测量，不能声称达到 80%；未引入覆盖率插件或为此启动重型验证。

## 4. 尚未取得的资格

生成工程构建、readiness、真实 MySQL/HTTP、三轮并发、base 的 HTTP 负例、参考/候选四门：全部 **NOT_RUN**。CI commit SHA/run URL/artifact：无；当前代码未提交，没有覆盖它的同 SHA CI 证据。未创建 task、冻结批次、采样、调用模型或学生训练。

本轮提交、推送与 CI 已另获负责人明确授权；不改写历史、不重复本机测试。参考门通过后，题目冻结和候选流程仍须另行安排；候选须先静态检查，失败只修一轮，再送业务门。在这条链路完成前不合入工具链变更；Windows 送门继续使用 LF 隔离快照核对冻结输入，不改 manifest 迁就 CI。无 UI 改动，无浏览器验收或 UI 单测。
