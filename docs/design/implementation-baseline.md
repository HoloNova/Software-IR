# 设计校准：当前实现边界与目标能力

核对日期：2026-09-08。源码基点：`5bba6ea6c9b00ef3daf2c705734eff5f2b053fe0`，核对时生产源码无工作区修改。本文件是本组设计的带日期校准依据，不替代仓库资格记录，也不自动接受新 ADR。

**主设计是目标设计。当前实现定位为：单文件 SIR 编译器 + Spring Boot 代码生成器 + 本地文件事务。** 最终仍要实现网页生成/修改 SIR、下载完整源码、仅准备 Docker 即可部署，以及保留数据的持续更新。

## 1. 状态词汇与证据口径

- **已实现**：所列有限范围有生产入口和实现；当次执行情况单独列出，不等于完整产品验收通过。
- **部分实现**：已有可复用基础，但尚不能满足对应课程目标的完整契约。
- **尚未实现**：本次核对的生产链中没有该目标入口和贯通实现；不因测试辅助类或设计示例同名而算作产品能力。
- **后续规划**：明确放在主设计主线之后，保留方向，不列入本版完成门。

“有测试源码”“当次测试通过”“生成工程编译通过”“真实数据库业务通过”“Docker 干净机器交付通过”分别记录。以下源码链接指向实际模块；测试执行限制见第 5 节。没有使用旧状态报告推断当前能力。

## 2. 能力对照

| 能力 | 当前状态及实现事实 | 主设计目标与验收方向 |
| --- | --- | --- |
| SIR 输入与语言 | 已实现单个 SirSource / sourceFile 的编译入口；Grammar 为 software 下 enum/entity/input/error/capability，含有限 workflow | G1 扩展课程语义，G2 增加明确源文件集合及模块绑定；不能把目标示例当作当前可解析语法。LANG-01..05 |
| 稳定身份 | 部分实现：AstNodeId 为源身份及结构路径，SymbolId 为软件名/种类/名称等编码；跨阶段传递与跨编辑保持身份不是同一保证 | G2 引入持久声明身份及兼容协议；保留 ID 改名、移动、同名重建、重复 ID 均验证 |
| 语义与编译链 | 已实现 Parse、Resolve、Type、Validate、Normalize、Spring Boot Lowering、Generator 和图构建编排 | 保持 Resolve-once；扩展类型/查询/授权须贯穿所有阶段，不在模板补语义。LANG/GEN 反例 |
| Lowered IR | 已实现独立 SpringBootLoweredModel；字段包括 Profile、名称/包名、declarations、artifacts、MavenProject、ApplicationMain | 03 的八类子模型为目标逻辑分解，不是当前八个 API；G1..G4 分别增加业务、schema、迁移和交付决策 |
| Generator | 已实现仅接收 Lowered 模型并返回内存文件集；输出 Maven、Application、Enum、Entity、Mapper、DTO、Exception、Service、Controller | 现有生成不等于完整源码交付包。GEN-01/02 验证确定性/非法输入，G4 的 GEN-03 才验证完整包构建 |
| CRUD、查询、校验、关联 | 部分实现：create/update/persist/load/find/validate 等 workflow 及实体引用映射可复用 | 完整记录删除策略、PATCH 三态、根分页/排序/投影、关联读取、版本冲突等逐项补齐；文件 DELETE 不算业务 DELETE。BIZ-01..06 |
| 身份与事务 | 部分实现：actor 绑定、authenticated/atomic/readonly 与目标映射已有基础 | actor 传输不等于登录/会话/CSRF/角色授权；事务注解不等于选课并发不超额。G5 验证 BIZ-07..13 |
| 本地工程变更 | 已实现 Application 的基线登记、计划、受支持文件 UPDATE/CREATE/DELETE、显式恢复 | 仅证明受支持 Change 操作的文件闭环，不承诺任意 SIR 修改或源码三方合并；G0 收口边界及故障矩阵 |
| CLI | 部分实现产品入口：当前公开解析 context、plan（另有 help/version） | Application 有方法不等于 CLI 已发布 generate/apply/recover；G0 后按正式契约另行开放所需入口 |
| 数据库新建/更新 | 尚未实现目标迁移闭环；测试侧 schema 工具不能算生产迁移器 | G3 实现 INITIALIZE/UPDATE、历史、漂移、数据保留和恢复，DB-01..11 |
| 完整源码包 / Docker | 部分实现源码文件生成；目标 ReleaseManifest、部署执行器、Docker/Compose 和独立部署包尚未实现 | G4 在仅有 Docker 的机器完成 DEP-01..08；公共依赖可达时不依赖平台 |
| Web 平台 | 尚未实现目标 Web API、任务队列、草稿 CAS、Release 发布及下载 | G6 实现 WEB/JOB/PUB；本地文件事务是内部可复用基础，不是 Web 平台 |
| 模块市场/搭积木/共享缓存 | 后续规划；受控多文件组合是 G2 的目标，通用拼装与跨项目缓存另计 | G7 换题观察价值后另立阶段；正确性不依赖缓存命中 |

## 3. 源码与测试定位

| 证据 | 生产依据 | 对应测试源码与证明范围 |
| --- | --- | --- |
| E1 单文件语言 | [SirParser](../../sir-parser/src/main/java/io/kcg/sir/api/SirParser.java)、[Grammar](../../sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4)、[ToolchainRequest](../../sir-toolchain-application/src/main/java/io/kcg/sir/application/api/ToolchainRequest.java) | [GrammarBoundaryTest](../../sir-parser/src/test/java/io/kcg/sir/parser/GrammarBoundaryTest.java)、[SirParserTest](../../sir-parser/src/test/java/io/kcg/sir/parser/SirParserTest.java)：当前语法与反例 |
| E2 身份 | [AstIdFactory](../../sir-parser/src/main/java/io/kcg/sir/internal/AstIdFactory.java)、[SymbolIdFactory](../../sir-semantic/src/main/java/io/kcg/sir/semantic/internal/SymbolIdFactory.java) | [AstDeterminismTest](../../sir-parser/src/test/java/io/kcg/sir/parser/AstDeterminismTest.java)、[TypedReferenceSiteContractTest](../../sir-semantic/src/test/java/io/kcg/sir/semantic/TypedReferenceSiteContractTest.java)：确定性及引用契约，不证明目标跨编辑 ID |
| E3 编译链 | [SemanticPipeline](../../sir-semantic/src/main/java/io/kcg/sir/semantic/internal/SemanticPipeline.java)、[SirCompiler](../../sir-toolchain-application/src/main/java/io/kcg/sir/application/internal/SirCompiler.java)、[ToolchainApplication](../../sir-toolchain-application/src/main/java/io/kcg/sir/application/api/ToolchainApplication.java) | [ResolveOnceArchitectureTest](../../sir-semantic/src/test/java/io/kcg/sir/semantic/ResolveOnceArchitectureTest.java)、[ToolchainHappyPathTest](../../sir-toolchain-application/src/test/java/io/kcg/sir/application/ToolchainHappyPathTest.java) |
| E4 目标与生成 | [SpringBootLoweredModel](../../sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/model/SpringBootLoweredModel.java)、[SpringBootGenerator](../../sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/api/SpringBootGenerator.java)、[GenerationEngine](../../sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/internal/GenerationEngine.java)、[GeneratedFile](../../sir-generator-spring-boot/src/main/java/io/kcg/sir/generator/springboot/api/GeneratedFile.java) | [GeneratorOutputContractTest](../../sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratorOutputContractTest.java)、[GeneratedProjectOfflineCompilationTest](../../sir-generator-spring-boot/src/test/java/io/kcg/sir/generator/springboot/GeneratedProjectOfflineCompilationTest.java)：文件契约及指定样例离线编译，不是 Docker/数据库验收 |
| E5 文件状态 | [ChangeExecutionApplication](../../sir-toolchain-application/src/main/java/io/kcg/sir/application/api/ChangeExecutionApplication.java)、[ChangeRecoveryEngine](../../sir-toolchain-application/src/main/java/io/kcg/sir/application/internal/state/ChangeRecoveryEngine.java) | [RecoveryStateMachineTest](../../sir-toolchain-application/src/test/java/io/kcg/sir/application/internal/state/RecoveryStateMachineTest.java)：B0/B1、未知 CURRENT、幂等恢复等文件场景 |
| E6 CLI | [CliCommandLine](../../kcg-cli/src/main/java/io/kcg/cli/CliCommandLine.java) | [KcgCliWorkflowTest](../../kcg-cli/src/test/java/io/kcg/cli/KcgCliWorkflowTest.java)：当前公开命令边界 |

未实现项的判断同时依据入口、模型、生成 dispatch、模块依赖和生产源检索。检索范围为相关模块的 `src/main` 与 POM；conformance 测试辅助代码不计入生产交付物。后续复核需重新检查这些依据，不能把本表当作永久状态。

## 4. 必须保持分离的责任与状态

| 名称 | 当前/目标的准确含义 | 不可推出的结论 |
| --- | --- | --- |
| SIR Source / SourceSnapshot | 当前一次编译一个 SirSource；目标 SourceSnapshot 是固定多文件输入与模块锁 | SourceId 不是平台 projectId；源码字符串摘要不是 Release |
| SymbolId / declarationId | 当前 SymbolId 含名称；目标 declarationId 持久保存且与名称分离 | 不能把当前名称编码身份直接当作数据库改名依据 |
| Lowered / Generator | Lowering 决定目标语义及映射，Generator 校验输入并纯渲染；目标迁移规划是目标侧纯步骤 | Generator 不探测旧目录、数据库或 Docker，也不下载依赖 |
| Application 文件事务 | 当前本地工程文件与 Bundle/CURRENT；未来平台可以通过 Application 复用 | 发布 B1 不等于数据库迁移成功、应用健康或下载包已公开 |
| Migration | 目标数据库操作、schema/history 和恢复证据；执行在用户本地 | 文件 UPDATE/CREATE/DELETE 不对应 ALTER/CREATE/DROP；文件回滚不回滚 DDL |
| Release / Deployment | Release 是不可变源码交付版本；Deployment 是一个 environmentId 的实际部署尝试 | 一个 Release 可部署到多个不同状态的环境；源码发布不写用户业务库 |

文件 CURRENT=B0 时向后补偿，CURRENT=B1 时向前验证清理，其他值或证据不足则阻断。这是**文件状态协议**。数据库根据自己的 history、结构及操作证据恢复；应用启动失败不得借文件回滚恢复已提交的数据库结构。

目标编排进一步区分：工具链 Application 独占工程文件写入；平台构建/发布服务负责进程、依赖获取、验证、ZIP 和可见性；本地部署入口负责 Docker 动作；随包 deployment-runner 负责数据库核验、迁移和收据。平台与本地各自持有自己的锁和恢复证据。

身份演进必须先设计版本化兼容：为旧声明显式建立一次性身份映射，验证一对一及引用闭包；不静默重解释旧 Bundle/Graph/ChangePlan，不用模糊名称猜映射。缺少映射或旧格式不支持时明确拒绝该升级路径，保留旧输入；不能宣称重新生成一次便具备跨版本保留身份能力。

## 5. 当次执行证据与限制

> **2026-09-18 裁决（后来者补记，不修改下方原始记录）**：本节的失败观察属于 **2026-09-08、源码基点 `5bba6ea`** 的带日期快照。2026-09-18 在 Linux 全新本地仓库（Java 21.0.12 + Maven 3.6.3 + `/root/.m2/repository`）以同一条标准命令复跑，**未复现** `sir-parser:testCompile` 失败：冻结与完成两种形式均为 BUILD SUCCESS，九个模块全部完成，合计 **554 run / 0 fail / 0 error / 5 skip**。
>
> 裁决归属与完整证据见 [`docs/qualification/CURRENT_QUALIFICATION.md`](../qualification/CURRENT_QUALIFICATION.md) 的 1.1 与 G0 完成门一节；本节保留原样，是因为它记录的是**当时**的观察，不是当前状态。
>
> 另需注意本节最后两条的历史性：Application POM 里的两处 conformance 包排除**已于 Q4+Q5 删除**（该包现参加编译与运行），且外部 MySQL conformance 矩阵已在 2026-09-18 产出 `QUALIFIED`（见资格文档 1.6）。



本节只记录本轮设计校准命令，不替代正式资格验收。测试结果按实际执行补记；没有运行 Docker 或真实 MySQL 业务/迁移验收。

- 标准命令 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify`：失败于 `sir-parser:testCompile`，测试编译报告找不到 `io.kcg.sir.api`、`io.kcg.sir.ast` 等包；后续模块未由该次运行验证。
- 诊断命令 `mvn "-Dmaven.repo.local=D:\maven-repo" "-Dmaven.compiler.useModulePath=false" -o -pl sir-parser test`：43 项通过，0 失败、0 错误、0 跳过。它是在前次编译产物基础上的运行。
- 带同一参数的 `-o clean verify` 仍在 Parser 测试编译失败。因此不能把该参数写成已确认修复，也不能将增量通过替代 clean 资格。
- 随后执行 `mvn "-Dmaven.repo.local=D:\maven-repo" "-Dmaven.compiler.useModulePath=false" -o verify`：Parser 43 项再次通过；随后 sir-semantic 编译报 `Fatal error compiling` 并指向 Parser JAR，后续模块跳过。没有修改源码/POM修复，也没有据此确定根因。E2 的 Semantic 测试及 E3–E6 所列测试仅核对源码，本轮未取得它们的执行通过证据。
- [Application POM](../../sir-toolchain-application/pom.xml) 同时在 testCompile 和 Surefire 排除了 `io/kcg/sir/application/conformance/**`；即使默认 reactor 通过，也不证明这些真实运行相关测试被执行。

完整产品所需的数据库迁移、源码包、Docker、Web 场景仍是计划。后续工作单须补标准 clean 构建、生成工程运行、数据保留和部署故障证据，不用历史测试总数补足。

## 6. 实施起点

当前源码适合承接 **G0：现有单文件链路与文件事务资格收口**。随后进入 G1 的单文件课程业务切片，保留编译阶段和 Application 所有权。多文件与持久身份在 G2 单独验收；迁移、交付和平台按 [07 的 G0–G7](07-validation-and-direction-roadmap.md) 推进。

文档 00–07 是阅读顺序；G0–G7 是本设计的实施门；仓库 Q 系列是实际工作单编号。三者不互相替代，本轮没有切换工作单或修改源码。
