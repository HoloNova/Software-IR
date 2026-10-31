# 报修工单判卷落地记录

实现基线：main `9cab86894a1e55af68876af562e677a734d28393`。负责人在规格确认后另行授权 DDL、种子、业务 IT 和两个 workflow 的接入。本记录先冻结提交前的本机验证现场；下文 NOT_RUN 是该阶段状态，不是四门合格报告。负责人随后已明确授权将本轮判卷实现作为一次提交普通推送，并运行同 SHA CI；最终资格以该提交的 run URL 和原始 artifact 为准。

## 1. 交付与设计理由

- 运行参考：`sir-toolchain-application/src/test/resources/valid/maintenance-work-order.sir`，与批准的 `reference.sir` 内容一致。
- 夹具：同模块 `src/test/resources/conformance/mysql/maintenance-work-order-ddl.sql`、`maintenance-work-order-seed.sql`，原样提取 spec §2/§3 的两个 SQL 块，保留 Optional 引用的物理列 `assignee`、外键与真实凭据。
- 入口：`src/test/java/io/kcg/sir/application/conformance/MaintenanceWorkOrderBusinessConformanceIT.java`。B/W/D/P/L、R/E、X 检查分别在 `MaintenanceWorkOrderChecks`、`MaintenanceInspectionChecks`、`MaintenanceConcurrencyChecks`；逐项索引见 [traceability.md §6](traceability.md#6-判卷源码入口)。
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

## 5. 已执行的同 SHA CI：FAILED（不是 NOT_RUN，也不合格）

- 实现提交：`d31af6af8b6f140280f24ee6a2a4992c7b5da585`，已普通推送，Author 为 HoloNova，协助者署名 ChatGPT。
- [verify run 41187721552](https://github.com/HoloNova/Software-IR/actions/runs/41187721552)，受测SHA与提交一致；冻结全量门 **PASSED**，Business conformance ITs **FAILED**，后续资格摘要/独立验证/CLI/部署均跳过，不算通过。
- 原始 [conformance-evidence artifact 13037518243](https://github.com/HoloNova/Software-IR/actions/runs/41187721552/artifacts/13037518243)，ZIP SHA256=`e66f8704868d215115dc8c449468df198d9fedc456712af9f8cde85c3294fe16`。
- 原始 [surefire-reports artifact 13037457080](https://github.com/HoloNova/Software-IR/actions/runs/41187721552/artifacts/13037457080)，ZIP SHA256=`3ad3441501791bc4e437dab9bcaa90b49829d80812ee9eda2c3ae4fa3f0e109a`。
- 本题原报告：`maintenance-slice-1a1914369c1-62214/maintenance-slice-report.txt`，固定第二行 `verdict=FAILED`；**597检查通过、2失败**，JUnit本题1项失败。20项轻量测试在本次CI仍全部通过，但不抵消业务失败。

### 5.1 实际结果与根因

生成文件55个，生成工程构建退出0，就绪200，实际MySQL/HTTP、三个并发轮次和清理均已执行。X01/X02/X03分别观测到6/6/2个不同连接与事务全部到达屏障；三个并发业务结果均符合预期。应用进程停止、schema删除与advisory lock释放均通过。

仅有两个失败断言都来自同一个请求：P04使用bob身份、对版本已为6的102号工单提交旧版本2。规格要求先403 Forbidden，实际为409 StaleVersion，错误信封也相应不同。JDBC未写入检查通过；不是越权写成功，也不是并发屏障失败。

根因可从既有 `sir-generator-spring-boot/.../internal/WorkflowRenderer.java:233–268,331–370` 确认：`renderLoad` 在非空检查后立即 `renderVersionGuard`；后者根据PATCH expectedVersion抛出persist声明的冲突错误。reference.sir第152行load WorkOrder因此在第153–154行读取Facility并校验manager之前就返回冲突。这与题面S06/C04和spec §4、§6 P04所要求的顺序不一致。规格阶段的内存生成无法证明这个顺序可实现。

### 5.2 证据绑定与停点

从受测提交的Git blob逐项核对原报告：SIR SHA256=`bfe04bc9b91f970f4de151d54e686a38b68872b71a7da535dd4df362dd39be32`，DDL=`074a6d4bf175e671303d61e26e1ddc4857c26bf6944c9742dccb6d19ed688029`，seed=`f40a24b7f8caa86e1460744a43633808d893b67eafe342a925140c1ccf4d27b7`，全部一致。原ZIP、API元数据、失败步骤日志和核验摘要保留于根 `target/bounded-problems-ci/41187721552/`，未加入Git。

上传的Surefire ZIP还包含 `datasets/**/target/surefire-reports` 下三个历史失败报告，不属于本次新失败；不能不区分路径就求和当作本次冻结门计数。本次Business步骤实际为17个IT、2失败，正是报修/排班；不报告混入历史附件的全局测试总数。

本轮没有调整期望、参考、题面或生成器来掩盖失败，也未重跑同源码CI。要么由负责人确认修订规格的错误优先级，要么保持已确认题面，另行评审工具链语义；按当前约束，在候选判卷链路结束前不合入工具链修改。本题尚不能冻结任务或开始教师采样。本节记录初次归因时的停点；后续修复与提交授权见§6。

## 6. CI 根因修复与定向回归（新 CI 尚未运行）

负责人先授权“准备本地修复，暂不提交”，完成定向验证后又明确要求直接提交。本节随对应修复本地提交，未授权推送或重跑CI。本节保留已批准题面、reference/base、DDL、种子和 HTTP/JDBC oracle；不放宽 P04，不修改工作流或候选冻结输入。第5节仍是已执行 CI 的真实 FAILED 结果，本节不能覆盖它。

`WorkflowRenderer` 将版本预检查从 `renderLoad` 移至显式 `renderUpdate` 的合并之前。这样 load 后的 scope、关联存在性及权限检查按 SIR 顺序执行；获授权的旧版本仍在修改对象和候选字段校验之前返回冲突。行锁、`updateIfVersionMatches` 的 identity/version 条件、零行冲突和提交后版本递增均保留。守卫直接采用当前 update 的 ConditionalUpdate，不再跨能力扫描同实体的第一个冲突分支。另一个根因的内部属性映射见排班记录§6。

### 6.1 RED/GREEN 与保证范围

新增 `PatchAuthorizationRuntimeTest` 实际编译并调用本题参考服务，不启动 Spring 或数据库：无权限且旧版本、已软删且旧版本、关联缺失且旧版本均先报前置错误；有权限的旧版本不合并/不写；CAS 零行仍冲突；合法 PATCH 仅改到场字段并报告提交版本。

有效 RED 是两组新增测试共12项，4 assertion failures、4 OGNL errors；getter 读取方式的测试设置错误已先修正，不混算为根因证据。最终按实际报告汇总为 **59项通过、0失败/错误/跳过**：

| 测试类 | 通过项数 |
|---|---:|
| PatchAuthorizationRuntimeTest | 6 |
| OgnlPropertyRuntimeTest | 8 |
| GeneratorWriteSliceContractTest | 17 |
| GeneratorEntityArtifactInvarianceTest | 4 |
| ReservedIdentifierRuntimeTest | 3 |
| WriteSliceLoweringTest | 21 |

这不是同一轮59项全绿的宣称：广义定向轮次中其余51项已通过；最后仅改 OGNL 测试的 JSON 比较方式（将响应真实序列化，避免 LongNode/IntNode 的内存类型差异），再单独复跑8项并通过，生产逻辑未变。随后仅校正 WorkflowRenderer 的缩进/空行，未重复运行已有效的测试。

### 6.2 实际命令、资源与证据

实测 Windows 11、18逻辑处理器、可用内存4575 MiB；只用两个核的进程亲和性，单构建、单fork，无 `-T`。Maven堆384 MiB/元空间192 MiB，测试fork堆512 MiB/元空间256 MiB。当前不是 Linux，未照抄不可用的 systemd-run。实际 RED 用同一命令的前两个新增类；最后复跑将 test 选择器收窄为 OgnlPropertyRuntimeTest。

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
$env:PATH="$env:JAVA_HOME/bin;$env:PATH"
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2 -Duser.language=en -Duser.country=US'
[System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity=[IntPtr]3
& 'D:/environment/apache-maven-3.9.14/bin/mvn.cmd' -B -o '-Dmaven.repo.local=D:/maven-repo' `
  -pl sir-generator-spring-boot -am `
  '-Dtest=PatchAuthorizationRuntimeTest,OgnlPropertyRuntimeTest,GeneratorWriteSliceContractTest,GeneratorEntityArtifactInvarianceTest,ReservedIdentifierRuntimeTest,WriteSliceLoweringTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:ActiveProcessorCount=2 -Duser.language=en -Duser.country=US' `
  '-DforkCount=1' '-DreuseForks=true' test
```

本机原始JUnit副本在根 `target/bounded-problems-ci/41187721552/repro/red/` 与 `repro/green/`；最小复现和 observations.json 也在 repro 下。这些是本机诊断/回归证据，未加入Git，不是 CI artifact。AFT inspect两次桥接超时，诊断不可用；实际 javac/Maven 编译已通过。未量化覆盖率，不声称80%；未运行全量门、真实 MySQL/HTTP、并发或新 CI，无 UI 改动。

本地补丁会改变工具链身份，不能用当前工作区冒充旧候选冻结快照，亦不得重写 manifest 或继承 d31af6af 的冻结门资格。候选仍先静态、失败只修一轮、再送业务门；该链路结束前不合入工具链改动。本次按后续明确指令本地提交；未推送、触发 CI、冻结题目、采样或调用模型，未切换远端候选判卷工具链。

## 7. 修复版同 SHA CI 验收：PASSED（当前参考状态）

负责人随后明确要求推送并触发 CI。两处修复 `e47cbec8` / `5fe4c833` 已普通推送；受测完整 SHA=`5fe4c8339a927fb526c004c276beb4c1c8892996`，[verify 41194982453](https://github.com/HoloNova/Software-IR/actions/runs/41194982453) 两个 job 均 success。本题 **599/0**，生成55文件、构建退出0、就绪200，三轮真实竞争的不同连接/事务人数 **6/6/2**；清理通过。原失败CI仍留在§5，§6的未推送/NOT_RUN是修复提交前的历史现场，不代表当前状态。

原ZIP、报告/日志、JUnit及哈希绑定已整理到[参考证据与冻结记录](../../../../datasets/sir/research/2026-10-31/bounded-reference-ci41194982453/README.md)。六份SIR/DDL/seed与受测Git blob一致，Surefire历史附件不混算。未改题面或放宽判卷；base HTTP负例仍NOT_RUN。报修合同的检查顺序注记同步实际已验收的“权限→版本预检查→合并校验→条件持久化”，不把版本预检查错写在合并校验之后。

负责人现要求继续下一步，进入task定义/冻结，仍不授权模型调用、提交、推送、额外CI或训练；过程与批次清单以上述证据目录为准。

## 8. 八题及仅参考批次冻结（未采样）

按负责人继续下一步的要求，已用原prepare在LF隔离快照冻结以下八题，全部由verify 41194982453的原CLI静态生成通过、无诊断；每批只有1份existing_reference，build/start/business均NOT_RUN。原题requirement原文不动，p1/p2/p3仅作等义表述，合同、base、参考与oracle完全共用。

| task | 批次 | 静态生成文件 |
|---|---|---:|
| `maintenance-work-order-change-v1` | `maintenance-work-order-change-g13` | 55 |
| `maintenance-work-order-change-p1-v1` | `maintenance-work-order-change-p1-g13` | 55 |
| `maintenance-work-order-change-p2-v1` | `maintenance-work-order-change-p2-g13` | 55 |
| `maintenance-work-order-change-p3-v1` | `maintenance-work-order-change-p3-g13` | 55 |
| `maintenance-work-order-scratch-v1` | `maintenance-work-order-scratch-g13` | 55 |
| `maintenance-work-order-scratch-p1-v1` | `maintenance-work-order-scratch-p1-g13` | 55 |
| `maintenance-work-order-scratch-p2-v1` | `maintenance-work-order-scratch-p2-g13` | 55 |
| `maintenance-work-order-scratch-p3-v1` | `maintenance-work-order-scratch-p3-g13` | 55 |

冻结明细、输入指纹、逐句等义追溯、原始检查器输入/输出和MANIFEST在[共享证据目录](../../../../datasets/sir/research/2026-10-31/bounded-reference-ci41194982453/README.md)及各批次。工具链450份输入和oracle原字节与受测Git blob一致；scratch base为空，change base不变，两份base仅静态检查通过。未调用模型、未导出训练数据、未改学生v5；本阶段没有提交、推送或重新触发CI。负责人现已确认先提交/普通推送，再按18万tokens保守预算对四道原题各初采1次；每次最大8192输出，不含失败修复，不自动重试。
