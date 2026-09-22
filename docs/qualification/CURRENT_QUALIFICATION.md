# KCG-Code 当前资格报告

## 0. G0 完成门（2026-09-18 关闭，本报告的权威结论）

**G0（现有链路资格收口）的完成门由本节判定；本报告其余各节是该门的证据与历史记录。**

| # | 阶段 7 要求的记录 | 结果 | 证据 |
|---|---|---|---|
| 1 | 默认离线 Reactor | **PASS** | 2026-09-18 冻结形式 `mvn -o clean verify` 与完成形式（加 `-Dmaven.test.failure.ignore=true`）**均 BUILD SUCCESS，9 个模块全部完成**，合计 **554 run / 0 fail / 0 error / 5 skip**（两条命令各跑一次，计数一致；模块级计数取自当次 Surefire XML） |
| 2 | 平台条件 | **PARTIAL（已登记）** | Linux（Java 21.0.12 + Maven 3.6.3 + `/root/.m2/repository`，overlay 文件系统）为本门依据；**Windows 侧未在本机执行**，仅有 2026-08-11 历史运行（373 / 0 / 0 / 10，且当时软链接用例为假绿），不计入本门；5 项 junction 用例按设计只在 Windows 执行（本节第 5 行） |
| 3 | 外部 MySQL conformance | **PASS** | MySQL 8.4.11 参考环境上五场景全通过、`Terminal result: Qualified`，连续两次可复现；见 1.6 |
| 4 | CLI 冒烟与完整生命周期 | **冒烟 PASS / 完整生命周期 NOT_RUN** | 命令面、退出码、canonical JSON、四个未发布命令的不存在性与无副作用均有断言（1.8）；**四个写命令未发布**，因此完整本地生命周期仍为 `NOT_RUN` |
| 5 | 跳过、排除、BLOCKED 和 NOT_RUN | **已登记** | 见本节末尾登记表 |
| 6 | 仍未覆盖的平台或发行方式 | **已登记** | Windows junction 与 Windows 离线仓库路径、第二卷/挂载点、跨卷仅到注册阶段、thin JAR/发行包 |

**完成门判据（负责人 2026-09-18 确认）**：两条命令 BUILD SUCCESS ＋ 合计 **554 / 0 / 0 / 5** ＋ 外部 MySQL 矩阵 QUALIFIED ＋ 全部残余缺口有登记。**四项均已满足。**

**实现快照**：G0 实现已由负责人提交为 `24eec6d`，`main` 与 `origin/main` 一致。随后进行的 Q8 文档闭合只更新状态记录，不改变生产代码、测试或 POM；本次文档闭合改动仍需由负责人另行提交。

**G0 内部工作单与关闭动作全部完成并归档**：Q1（Generator 生产边界）、Q2（Project Graph 直接契约 0→72）、Q3（Change fixtures + 软链接收口，全量首次 BUILD SUCCESS）、Q4+Q5（conformance 包恢复 + 真实 MySQL 矩阵 QUALIFIED）、Q6（三路径故障矩阵，64 项，零生产改动）、Q7（CLI 只读边界，12 项，修复 1 处生产缺陷）、Q8（G0 最终资格关闭）。见 `docs/roadmap/completed/`。

**本节之后的进展**：Q9（G1 第一切片：单文件 Course 分页查询端到端）已于 2026-09-18 完成实现与验证，全量计数 **620 / 0 / 0 / 5**（两条命令均 BUILD SUCCESS），真实 MySQL + HTTP 业务场景 40 项断言全通过；详见 1.9。本节的 G0 记录不因此改写。

**登记表（NOT_RUN / 未覆盖 / 平台条件）**：

| 项 | 状态 | 原因与影响 |
|---|---|---|
| Windows 离线 Reactor（`D:\maven-repo`） | **NOT_RUN（本机）** | 本机为 Linux；Windows 侧需在 Windows 工作机执行 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify` 后回写 |
| Windows junction 5 项 | **skip（按设计）** | `@EnabledOnOs(WINDOWS)`；Linux 上不适用 |
| 第二卷 / 挂载点 | **未覆盖** | Q6 的跨卷用例只证明到"注册阶段拒绝"，进入删除阶段后的链接失败路径不可构造 |
| thin JAR / 发行包 | **NOT_RUN** | 属独立发布任务；退出码在子 JVM（`java -cp … io.kcg.cli.KcgCli`）上验证，未验证打包产物 |
| 完整 CLI 本地生命周期 | **NOT_RUN** | `generate`/`register`/`apply`/`recover` 未发布（ADR-019 仍为提议状态） |
| POM 硬排除 | **已全部移除** | 原 `io/kcg/sir/application/conformance/**` 的两处排除已删除，该包现参加编译与运行 |
| Docker / 迁移 / 源码包 / Web 场景 | **未实现** | G1+ 范围，不构成本门缺口 |

**数字口径（D1）**：本报告 1.2–1.8 记录的是**各阶段当时的计数**（374／451／465／478／542 等），它们保留原值以保全历史证据；**G0 关闭时的权威总数为本节与报告开头的 554 / 0 / 0 / 5**。G1 开工后该总数已推进：**当前权威总数为 620 / 0 / 0 / 5**（Q9，2026-09-18，见 1.9 与第 2 节；差量 66 已逐模块归因）。


- 资格日期：2026-08-11（Windows）；2026-09-18 在 Linux 新机器以全新本地仓库复跑，并完成 Q1–Q8（G0 阶段 1–7），见 1.2–1.8 与第 0 节
- 运行环境：Windows 11 + 离线仓库 `D:\maven-repo`（2026-08-11 历史记录）；Linux + Java 21.0.12 + Maven 3.6.3 + `/root/.m2/repository`（2026-09-18 当前资格依据）
- 总体结论：Linux 冻结与完成两种形式的离线 Reactor 均为 **BUILD SUCCESS**，合计 **554 run / 0 fail / 0 error / 5 skip**；5 项剩余 skip 全为 Windows junction。Generator 生产边界、Project Graph 只读边界、Change/事务故障矩阵和 CLI 只读边界均有直接证据；外部 MySQL conformance 在登记的参考环境元组上 `QUALIFIED`；完整本地 CLI MVP、Windows 侧和发行包仍按第 0 节登记为未完成或未运行。

## 1. 标准命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

最近一次实际结果：`BUILD SUCCESS`，父工程和九个子模块全部进入 Reactor 并成功结束。

### 1.1 已裁决的证据冲突（2026-09-18）

`docs/design/implementation-baseline.md`（2026-09-08，源码基点 `5bba6ea`）记录同一标准命令失败于 `sir-parser:testCompile`，报告找不到 `io.kcg.sir.api`、`io.kcg.sir.ast` 等包，后续模块未由该次运行验证；其中另一次带 `-Dmaven.compiler.useModulePath=false` 的增量运行让 Parser 43 项通过，再接一次 `-o verify` 时 `sir-semantic` 报 `Fatal error compiling`。

本文件与 `docs/PROJECT_STATUS.md` 的旧记录来自 2026-08-11；`implementation-baseline.md` 的失败观察来自 2026-09-08。两次历史观察不能覆盖 2026-09-18 的当前证据。

**裁决**：以 2026-09-18 Linux 全新本地仓库的两次 `clean verify` 为当前结论；冻结与完成形式均 `BUILD SUCCESS`，均得到 **554 / 0 / 0 / 5**，未复现 `sir-parser:testCompile` 失败。`implementation-baseline.md` 保留原始日期快照，并已在第 5 节加入指向本裁决的补记；本节作为反向指针。`useModulePath=false` 的历史增量运行不计入当前资格。

**裁决（2026-09-18，G0 关闭动作）**：以本文件的复跑结果为准。`implementation-baseline.md` 的失败观察属于 2026-09-08、源码基点 `5bba6ea` 的带日期快照，**不是当前状态**；该文件第 5 节已加入指向本节与 G0 完成门的裁决补记（原始记录保留不改）。因此本文件与 `PROJECT_STATUS.md` 记录的 BUILD SUCCESS 不再与实现基线冲突。

### 1.2 2026-09-18 Linux 新机器复跑（全新本地仓库）

环境：Ubuntu、Java 21.0.12、Maven 3.6.3，源码基点 `b8efb83`（工作区只有未提交的文档改动）。这台机器原先没有任何 Maven 本地仓库。

仓库准备（全部为环境操作，未修改任何生产代码或测试）：

1. `mvn -B -Dmaven.repo.local=/root/.m2/repository clean verify`（在线）建立本地仓库。
2. 该次运行在 `sir-generator-spring-boot` 首次失败：`GeneratedProjectOfflineCompilationTest` 内部的生成工程编译是 `--offline`，而生成工程自身的依赖不在 Reactor 的依赖闭包里。用该测试留下的生成工程目录在线补跑一次 `mvn -B -Dmaven.repo.local=/root/.m2/repository compile`，补齐 `spring-boot-starter-parent:3.5.3`、`mybatis-plus-spring-boot3-starter:3.5.12`、`mysql-connector-j:9.3.0`、`protobuf-java:4.29.0`、`jna-platform:5.17.0` 等。
3. 以冻结形式离线复跑：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify`。
4. `kcg-cli` 在第一步到不了，需单独在线补齐依赖：`mvn -B -Dmaven.repo.local=/root/.m2/repository -pl kcg-cli -am -DskipTests test`。

结果：`BUILD FAILURE`。模块 1–8 全部通过；`sir-toolchain-application` 出现 5 项失败与 11 项跳过；`kcg-cli` 因 Reactor 在该模块停止而没有产生任何 Surefire 报告（不是通过，也不是跳过）。

| 模块 | run | fail | error | skip |
|---|---:|---:|---:|---:|
| `sir-parser` | 43 | 0 | 0 | 0 |
| `sir-semantic` | 103 | 0 | 0 | 0 |
| `sir-lowering-api` | 4 | 0 | 0 | 0 |
| `sir-lowering-spring-boot` | 32 | 0 | 0 | 0 |
| `sir-generator-spring-boot` | 32 | 0 | 0 | 0 |
| `sir-project-graph` | 0 | 0 | 0 | 0 |
| `sir-change` | 22 | 0 | 0 | 0 |
| `sir-toolchain-application` | 132 | 5 | 0 | 11 |
| `kcg-cli` | 30 | 0 | 0 | 0 |
| **合计（模块 1–9）** | **368** | **5** | **0** | **11** |

`sir-generator-spring-boot` 的 32 项包含 `GeneratedProjectOfflineCompilationTest`，即完整生成工程在本机以 `--offline` 真实编译通过。

5 项失败（真实执行后的断言失败）：

| 测试 | 期望 | 实际 |
|---|---|---|
| `PathGuardTest.targetItselfIsSymlinkIsRejected` | 诊断码 `SIR-APP-CONFLICT-002` | `SIR-APP-PATH-003`（`PathGuard.java` 第 64 行分支先命中） |
| `SafeTargetResolverTest.targetSymlinkRejected` | 异常消息含 `symlink` | `link in raw chain: <path>`（`SafeTargetResolver.java` 第 164 行） |
| `SafeTargetResolverTest.parentSymlinkOfOutputRootRejected` | 同上 | 同上 |
| `SafeTargetResolverTest.linkDotDotRawChainAttackRejected` | 同上 | 同上 |
| `SafeTargetResolverTest.parentSymlinkInsertedBeforeResolveRejected` | 同上 | 同上 |

分类（**2026-09-18 Q3 已完成根因定性，见 1.5**）：5 项的“拒绝软链接”行为都真实发生（确实抛出 `UnsafePathException`，或在 preflight 产生 ERROR 诊断）。根因分两类：4 项是**诊断消息拼写**——测试要求消息含子串 `symlink`，实现抛的是 `link in raw chain`（该类其他位置用的是 `symlink or reparse point`）；1 项是**诊断遮蔽**——`checkTargetSymlinks` 把叶子节点自身也算进 “target chain”，先报 `SIR-APP-PATH-003` 并提前返回，使 `PathGuard` 中专为叶子写的 `SIR-APP-CONFLICT-002` 分支不可达。这 5 项此前在 Windows 上从未真正执行：`SafeTargetResolverTest.tryCreateSymlink` 在创建失败时返回 `false`，调用方直接 `return`，测试被记为**通过**而不是跳过；`PathGuardTest.targetItselfIsSymlinkIsRejected` 标注 `@EnabledOnOs({LINUX, MAC})`，在 Windows 上不运行。因此 2026-08-11 的 `0 failed` 不能证明软链接拒绝路径可用。

11 项跳过（Linux）：`ChangePlanningApplicationTest` 6 项（fixtures 缺失，**Q3 已消陈，见 1.5**）、`PathSecurityReviewTest` 5 项（junction 是 Windows 专属概念，仍保留）。

完成形式复跑（同日、同仓库）：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true`。全部 10 个 Reactor 模块完成，`kcg-cli` 5 项已实际执行，Surefire 合计 **373 run / 5 fail / 0 error / 11 skip**，5 项失败与上表完全相同。该次 `BUILD SUCCESS` 来自 `maven.test.failure.ignore`，**不代表这 5 项失败已消失**，只能在如实登记它们的前提下引用。

结论：

- 冻结命令在 Linux 全新仓库下**在 1.2 当时不能**判定为通过：模块 1–8 通过，模块 9 有 5 项真实失败，模块 10 未运行。**该状态已在 Q3 闭合**（1.5）：冻结形式现在 BUILD SUCCESS，465 run / 0 fail / 0 error / 5 skip。
- 1.1 记录的 2026-09-08 `sir-parser:testCompile` 失败在本机在线与离线两次运行中都没有复现（Parser 43 项两次均通过）；那次环境未被复现，1.1 只能算部分收敛，不能宣布已裁决。

### 1.3 2026-09-18 Linux Q1 Generator 生产边界闸门与全量复跑

环境同 1.2；源码基点 `902a7a5`，工作区只有 Q1 新增的三个 test-only 文件与同批文档改动；未修改任何生产代码，未新增依赖、skip 或 exclude。

定向命令：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-generator-spring-boot -am -Dtest=GeneratorProductionBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`。

新增证据（均在 `sir-generator-spring-boot` 模块内，无生产代码改动）：

- test-only constant pool reader 解析生产 class 的 `CONSTANT_Utf8`，并把每条项分类为 `CLASS_NAME` / `STRING_LITERAL` / `OTHER`；非法 magic、截断数据和未知 tag 立即抛 `ClassFileFormatException`，报告 class 文件、偏移和 tag（四种畸形输入均有直接测试）。
- 生产 census：37 个 `.class`（22 个生产源文件），断言包含 `api/SpringBootGenerator.class`、`internal/GenerationEngine.class`、`internal/EntityRenderer.class`，全部条目位于 `io/kcg/sir/generator/springboot/` 下，且不含探针类名。
- 禁止引用扫描结果 **0 违规**；生产侧唯一 `io/kcg/sir/semantic/` 引用是精确的 `io/kcg/sir/semantic/symbol/SymbolId`（`GeneratedFile` ownership metadata），`SymbolTable`、同包其他类型和 `SymbolId$...` 仍禁止。
- 公开入口反射：`public final SpringBootGenerator`，唯一公开方法 `generate(SpringBootLoweredModel)` 返回 `GenerationResult`，只有一个无参构造器，无 AST/SIR/Semantic/文件系统类型暴露。
- 闸门灵敏度：故意调用 `Files`、`System`、`IOException` 的 test-only 探针被稳定报出 `java/nio/file/`、`java/lang/System`、`java/io/`，并确认对应 class 引用被分类为 `CLASS_NAME`；把该探针与 `GeneratorTestSupport` 的 class 文件复制成临时 census 后，生产扫描路径（`classFiles` + `scan` + `report`）稳定报出四类违规并按 class → rule → pool index 排序，排除“永远返回空集合”的假绿。
- 覆盖边界：该闸门只证明生产 class 的静态引用边界；运行时行为、反射逃逸、生成 Java 文本的正确性，以及不在批准禁止集合内的 `io/kcg/sir/semantic/{context,type,internal}` 均不在覆盖范围内。

全量离线闸门（两条都执行）：

| 运行 | 命令 | 结果 |
|---|---|---|
| ① 冻结形式 | `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify` | 模块 1–8 SUCCESS；`sir-toolchain-application` FAILURE（同样 5 项软链接断言）；`kcg-cli` SKIPPED。Reactor 内合计（模块 1–9）**374 / 5 / 0 / 11**。该次未构建 `kcg-cli`，其 `target/surefire-reports` 仍是 priming 运行（09:29:42）的旧文件，不计入本次结果 |
| ② 完成形式 | `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true` | 10 个 Reactor 模块全部完成，BUILD SUCCESS；合计 **379 run / 5 fail / 0 error / 11 skip**，除同样 5 项外无新增 fail/error/skip |

模块分布（②）：parser 43、semantic 103、lowering-api 4、lowering-spring-boot 32、generator 38、project-graph 0、change 22、application 132/5/11、cli 5。

`GeneratedProjectOfflineCompilationTest` 在②中真实执行（1 run / 0 fail，5.36 s），完整生成工程 `--offline` 编译仍在闸门内。相比 1.2 的 373/5/0/11，总数 +6，全部来自 Q1 新增的 `GeneratorProductionBoundaryTest`；`git diff --check` 无输出。

上述①②在最终状态（测试内部重构与文档更新之后）各再跑一次，结果完全一致：① **374 / 5 / 0 / 11**（kcg-cli SKIPPED）、② **379 / 5 / 0 / 11**，失败项仍是同样 5 项，`GeneratedProjectOfflineCompilationTest` 该次为 1 run / 0 fail / 4.82 s。

验收与快照：项目负责人 2026-09-18 确认 Q1 整体通过，同时决定 **Q1 暂不提交 Git**（初定 G1 完成后统一提交）。因此本小节证据对应的本地 HEAD 与 `origin/main` 跟踪引用都仍是 `902a7a5`，Q1 的未提交改动未进入任何提交；Q1 工作单已归档为 `docs/roadmap/completed/Q1-generator-production-boundary-and-acceptance.md`。

后续：Project Graph 直接模块测试（Q2）已立项待复核。在 Q2 产出当次证据之前，`sir-project-graph` 仍是 0 项直接测试，第 6 节第 3 项不变。

（2026-09-18 后续更新：Q2 已执行完成，`sir-project-graph` 由 0 项变为 72 项，证据见 1.4；第 6 节第 3 项同步改写。）

### 1.4 2026-09-18 Linux Q2 Project Graph 直接模块契约测试

环境同 1.2；源码基点 `902a7a5` 加 Q1 未提交改动；只新增 `sir-project-graph/src/test/**` 下的 5 个 test-only 文件与文档，**未修改任何生产代码，未新增依赖、skip 或 exclude**。

定向命令：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-project-graph -am test` → `sir-project-graph` **72 run / 0 fail / 0 error / 0 skip**（契约 9、规则矩阵 31、canonicalization 6、serialization 20、边界 6），上游 parser 43 / semantic 103 / lowering-api 4 全绿。

新增直接证据：

- 模块内手写 fixture 构造 campus-market 形状的 **35 节点 / 34 边**（1 Project + 6 Semantic + 6 Lowered + 11 Artifact + 11 ProjectFile；6 DECLARES + 6 LOWERS_TO + 11 OWNS_ARTIFACT + 11 GENERATES_FILE），不再依赖 Application 模块。
- 四类边的成功侧（含按 `SymbolId` 从 Semantic → Lowered → Artifact → ProjectFile 的 trace、project artifact 无 owner、每个 artifact 恰好一个文件）与违规侧（端点种类错误、自环、悬空、重复身份、arity 违规）都有直接断言。
- 规则矩阵：`NODE-001`～`NODE-004`、`EDGE-001`～`EDGE-014`、`PATH-001`、`PATH-002`、`ORDER-001`、`ORDER-002`、`VERSION-001`、`PROVENANCE-002`～`PROVENANCE-007` 均有可达输入的精确诊断码断言。
- canonical 侧：节点按种类 rank + canonical key、边按 `GraphEdgeId` 排序；digest 与 canonical bytes 对输入列表顺序无关；重复构建字节相等；digest 会因节点 provenance span 变化而变化（证明确实覆盖内容）。
- 序列化侧：序列化 → 加载 → 再序列化字节相等且节点/边完全相等；`byte[]` 与 document 都防御性拷贝；加载不修改调用方数组；13 类畸形/非规范输入（BOM、CR、非法 magic、空输入、截断 payload、尾部多余字节、非最短十进制 field count、非最短十进制 payloadByteCount、外来 formatVersion、payload 摘要不匹配、大写摘要）均以结构化 Failure 返回并断言精确诊断码，无一逃逸为运行时异常。
- 只读边界：生产 census 69 个 class（30 个生产源文件）的常量池扫描 **0 违规**；每条 `java/io/*` 与 `java/lang/System` 成员引用都必须在已复核成员清单内（清单只有 `java.io.ByteArrayOutputStream` 的构造/`write`/`toByteArray` 与 `System.arraycopy`）；每个 `io/kcg/sir/*` 类引用都必须命中显式复核清单（`AstNodeId`、`LoweredNodeId`、`LoweredOrigin`、`SymbolId`、`SymbolKind`、`SourceId`、`SourcePosition`、`SourceSpan` 或自身模块）；`ProjectGraphLoader` 只有 `load(byte[])` 与 `load(CanonicalProjectGraphDocument)` 两个公开方法，参数/返回类型不含 `java.io` 或 `java.nio.file` 类型。
- 闸门灵敏度：test-only 探针（调用 `Files`、`System.getenv`、`UUID`、`DefaultSirParser`）被同一规则表稳定报出 `java/nio/file/`、`java/util/UUID`、`io/kcg/sir/internal/` 与 `java/lang/System.getenv`；把该探针复制成临时 census 后，生产扫描路径（`classFiles` + `scan` + `report`）报出违规并按 class → rule → pool index 确定性排序，排除“永远返回空集合”的假绿；reader 对非法 magic、截断 UTF-8、未知 tag 99、常量池后缺 header 四种畸形输入立即失败并报告偏移。

全量离线闸门（两条都执行，均在最终代码状态）：

| 运行 | 结果 |
|---|---|
| ① `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify` | 模块 1–8 SUCCESS（含 `sir-project-graph` 72 项）；`sir-toolchain-application` FAILURE（同样 5 项软链接断言）；`kcg-cli` SKIPPED。Reactor 内合计 **446 / 5 / 0 / 11** |
| ② `... clean verify -Dmaven.test.failure.ignore=true` | 10 个 Reactor 模块全部完成，BUILD SUCCESS；合计 **451 run / 5 fail / 0 error / 11 skip**，除同样 5 项外无新增 fail/error/skip |

`GeneratedProjectOfflineCompilationTest` 在②中真实执行（1 run / 0 fail）。相比 1.3 的 379/5/0/11，总数 +72（Q2 主体 67 + 追加守卫/绊线/不可编码测试 5），全部来自 Q2；`git diff --check` 无输出，生产代码零改动。Q2 已获项目负责人确认并归档（暂不提交 Git）。

Q2 暴露的、必须如实登记的事实（均不影响“生产代码未被修改”的结论）：

- 边界规则相对 Q2 规格做了精化：规格要求禁止全部 `java/io/` 与 `java/lang/System`，但生产实测显示两者各有纯内存用法（`ByteArrayOutputStream`、`System.arraycopy`）是合法且必要的，因此改为成员级复核清单；其他成员仍按名禁止并由探针证明。这是“已实现规则与设计文本不一致”的可裁决点，不是静默放宽。
- `SIR-GRAPH-PROVENANCE-001` 不可通过任何公开路径触发：节点 record 的 `provenance` 组件是具体类型（编译期强制），解码器也对每种 nodeKind 逐项校验 `provenance.kind`（`SnapshotLoader` 117/164/243/313/386）。测试以反射断言该组件类型作为替代证据，该码登记为对非公开构造路径（反射、序列化框架等）的防御性复检。
- `SIR-GRAPH-PROVENANCE-001` 不可通过任何公开路径触发：节点 record 的 `provenance` 组件是具体类型（编译期强制），解码器也对每种 nodeKind 逐项校验 `provenance.kind`（`SnapshotLoader` 117/164/243/313/386）且立即 fail closed。测试以反射断言该组件类型，并用文档篡改测试直接覆盖其可达替身 `SIR-GRAPH-COMPAT-010`；该码本身登记为对非公开构造路径（反射、序列化框架等）的防御性复检。
- `SIR-GRAPH-VERSION-002` 与序列化器的 `SIR-GRAPH-COMPAT-001` 因两个枚举当前各只有一个值而不可达（前者还要加上：`ProjectGraphValidator.validate` 只能收到 loader 硬编码的 `V0_1`）。两条路的**可达部分已直接覆盖**：编码器的 `COMPAT-001`（直接调 `SnapshotEncoder.encode(graph, null, diags)`）与解码器的 `COMPAT-002`（文档声明 `graphVersion=V0_2`，且先修复 integrity 字段确保真的到达该规则）。另加一项绊线测试：一旦新增版本枚举值，测试即失败，强制把文档里的“不可达”理由换成真实测试。
- `payloadByteCount` 的 `SIR-GRAPH-FORMAT-007` 分支被 `FORMAT-012` 遮蔽；`FORMAT-007` 改由 HEADER field count 路径直接覆盖。
- 阅读代码时发现的另一个可达分支已在本轮补齐：`SIR-GRAPH-SERIALIZE-001`（`SnapshotEncoder` 192～202 行，节点字段含不可编码 Unicode 标量）——测试用带孤立代理项的 `displayName` 触发，断言恰好 `SERIALIZE-001`。至此 Q2 范围内没有已知可达而未覆盖的诊断码。
- 开发过程中修正的 4 处 test-side 缺陷（fixture 路径冲突、级联预期不全、`byte[]` 误用 `assertEquals`、`byte[].getName()` 期望值错误）已记入 Q2 工作单交接记录；既有正确行为首次真实执行仍为 0 违规。

### 1.5 2026-09-18 Linux Q3 Change fixtures 与软链接失败收口（阶段历史记录）

本节关闭两件挂账：1.2 登记的 5 项软链接失败（已并入 Q3），以及覆盖清单第 4 节的 Change/base-candidate fixtures 缺口。工作单见 `docs/roadmap/completed/Q3-change-fixtures-and-symlink-closure.md`。

生产改动（仅 2 个文件，已获工作单 D2/D3 授权）：

| 文件 | 改动 | 性质 |
|---|---|---|
| `sir-toolchain-application/.../state/SafeTargetResolver.java` | `"link in raw chain: "` → `"symlink or reparse point in raw chain: "` | 诊断文本；与同类 `:35`、`:95` 既有措辞一致 |
| `sir-toolchain-application/.../PathGuard.java` | `checkTargetSymlinks` 改为只检查**严格祖先链**，删除叶分支；调用方消息改为 “target parent chain” | 修诊断遮蔽：叶子符号链接应报 `SIR-APP-CONFLICT-001/002` |

叶子拒绝的保护未削弱（fail closed 证据）：`ConflictPolicy` 仅 `FAIL_IF_EXISTS` 与 `REPLACE_EXISTING` 两值，`conflictCheck` 覆盖二者；叶子（含悬空）为符号链接时 `Files.exists(target, NOFOLLOW_LINKS)` 为真，分别报 `CONFLICT-001` / `CONFLICT-002`。新增 `PathGuardTest.targetItselfIsSymlinkIsRejectedUnderFailIfExists` 守住 FAIL_IF_EXISTS 这一侧。

fixtures：新增 `sir-toolchain-application/src/test/resources/valid/campus-market-candidate.sir`（base 仅第 55 行 `validate` 条件不同），以及在 `kcg-cli/src/test/resources/io/kcg/cli/` 下新建/归位 12 个共享 fixture（base、5 个 candidate、minimal 及其 add 变体、two-capabilities 及其 remove 变体、actorless-readonly base/candidate、v05 base/candidate）。`KcgCliWorkflowTest` 的类级 `@BeforeAll` assumption 改为**逐测试** `requireFixtures(...)`。

结果：

| 运行 | 结果 |
|---|---|
| 定向 `sir-project-graph` 无关；见下方全量 | — |
| ① 冻结形式 `-o clean verify` | **BUILD SUCCESS（exit 0）**；十个模块全部完成；合计 **465 run / 0 fail / 0 error / 5 skip**（Q3 当时的阶段计数） |
| ② 完成形式（加 `-Dmaven.test.failure.ignore=true`） | **BUILD SUCCESS**；数字与①完全相同 **465 / 0 / 0 / 5**（Q3 当时的阶段计数） |
| `sir-toolchain-application` | **133 run / 0 fail / 0 error / 5 skip**（原 132/5/0/11） |
| `kcg-cli` | **18 run / 0 fail / 0 error / 0 skip**（原 5 run / 13 项类级 skip） |

skip 11 → 5 的逐条解释：`PathSecurityReviewTest` 5 项 Windows junction 保留（`@EnabledOnOs(WINDOWS)`）；`ChangePlanningApplicationTest` 6 项 assumption 消失（fixture 补齐后真实执行并通过）；`KcgCliWorkflowTest` 13 项类级跳过全部消除并计入执行。**无任何新增 skip**。执行中的测试 440 → 460。

过程中发现并修正的 6 处缺陷全部为 **test-side 或 fixture-side**（非生产 RED）：CLI base SIR 放错目录（`resource()` 只查 `/io/kcg/cli/`）、`baseline001RawSha256MismatchFails` 的替换目标是乱码导致替换空转、v03 fixture 让被删能力成为唯一带 actor 的能力（`ApplicationRenderer` 按 actor 传输计划分叉，`IMPACT-202` 是正确行为）、v05 字段必须真正未被引用、类级门掩盖缺件、`M1TestSupport` 为无调用者的死测试支撑类（仅登记不改动）。详见工作单“执行证据”。

**Q3 当时的阶段记录**：CLI 只有 `context`/`plan` 两个只读命令被执行；Change 操作族尚未补跨版本组合矩阵；conformance 整包、MySQL/HTTP、事务故障注入矩阵尚未运行。上述 conformance 与事务内容随后分别由 Q4+Q5、Q6 完成；当前结论见第 0 节、1.6 和 1.7。

### 1.6 2026-09-18 Linux Q4+Q5 conformance 包恢复与真实 MySQL 矩阵（QUALIFIED）

**结果：`sir-toolchain-application` conformance 包从 POM 硬排除恢复到参加编译与运行，并在真实 MySQL 8.4.11 参考环境上产出 `QUALIFIED` 终态（连续两次运行均 QUALIFIED）。**

| 项 | 证据 |
|---|---|
| 编译错误 | **56 → 0**（3 个根因：`OwnedRunDirectory` 缺 package/import 落入默认包、JNA `FILE_FLAG_*` 应在 `WinNT`、IT 缺 9 个 import） |
| 新增测试类型/方法 | 13 个缺失类型与约 21 个缺失方法按调用点契约补齐（`EvidenceWriter`、`ManifestTransactionVerifier`、`MysqlControlSession`、`RuntimeOps`/`RealRuntimeOps`、`ScenarioEndpoint` 等） |
| POM 排除 | **compiler `testExcludes` 与 surefire `excludes` 均已删除**；`*IT` 天然不在 surefire 默认包含内，故默认构建不跑外部矩阵 |
| 默认构建 | 两个闸门均 **BUILD SUCCESS**：**478 run / 0 fail / 0 error / 5 skip**（该阶段计数）（5 skip 仍为 Windows junction 用例） |
| 参考环境元组 | MySQL **8.4.11**（容器 `mysql:8.4`，宿主端口 33306）、`@@server_uuid=c8295431-b30f-11f1-85f5-82329a62fe01`、宿主文件系统 provider 由 `Files.getFileStore(workParent).type()` 记录、Maven 离线仓库 `/root/.m2/repository`、Java 21.0.12 |
| 运行命令 | `mvn -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am -Dkcg.conformance.enabled=true -Dtest=SpringBootTargetConformanceIT -Dkcg.conformance.work-parent=… -Dkcg.conformance.evidence-parent=… -Dkcg.conformance.schema-name=kcg_conf_run -Dkcg.conformance.maven-executable=… -Dkcg.conformance.maven-repo=… -Dkcg.conformance.expected-mysql-server-uuid=… -Dkcg.conformance.server-port=18080 test` |
| 终端结果 | `Terminal result: Qualified`；五个场景（IG-ACTOR、IG-READONLY、APPLY-UPDATE、APPLY-CREATE、APPLY-DELETE）**全部 `passed=true`** |
| QUALIFIED 的证明项 | 五场景按规范序全通过 + 无失败 + schema DROP 与 DROP 后缺席证明 + advisory lock 释放 + workRoot 目录本身删除 + 证据扫描干净 + 报告封存后重扫 + 证据最终发布（由 `ConformanceRun.terminalResult()` 强制） |
| 运行后状态 | workParent 为空、`kcg_conf_run` schema 不存在（证明清理真实发生） |
| 证据文件 | 每个场景的 `maven.{stdout,stderr}.log`、`spring.{stdout,stderr}.log`、`target-dependency-inspection.txt`、`ig-manifest-verified.txt` / `apply-transaction-verified.txt` / `closure-external-verified.txt` / `delete-absence-verified.txt`，加上 `schema-inventory.txt` 与 `report.txt` |

**该结果的有效边界（必须在引用时一并说明）**：
- 结论只对**上面这个参考环境元组**成立；换 MySQL 版本、宿主文件系统 provider 或端口即需重跑。
- `QUALIFIED` 由 harness 自身的证明链给出，**不代表** conformance 包中每个辅助类都被独立单元测试覆盖（见 `TEST_COVERAGE_INVENTORY.md` 的残余缺口）。
- 容器化 MySQL 的资格可用性已由项目负责人确认（2026-09-18）。

**真跑过程中修复的实现缺陷（均已记录在工作单）**：守卫调用顺序早于取锁；`Connection.setCatalog` 在 MySQL 8.4 上不可靠（ERROR 1046，改为 schema 限定名与显式 `USE`）；fixture DDL 注释内含分号导致按 `;` 切分后把注释残片当 SQL；共享 DDL 的 NOT NULL 列与 actorless 场景不兼容；work-root 清理三处缺陷（未清理子构建产物、目录身份用不稳定信号、注册目录因未注册子项删不掉）。

### 1.7 2026-09-18 Linux Q6 文件事务故障矩阵（三路径同语义）

**结果：UPDATE（apply）、CREATE、DELETE 三条事务路径具备同一套中断点故障矩阵，方向语义一致，且模糊状态 fail closed；本单未修改任何生产代码。**

| 项 | 证据 |
|---|---|
| 新增测试 | **64**（APPLY 21、CREATE 21、DELETE 18、恢复/卷 4） |
| 注入方式 | 生产既有 `ApplyHooks`（44 个 hook，注入面经包内构造器可达）；`FailingApplyHooks` 由接口源码机械生成，接口新增 hook 不更新它即编译失败 |
| 故障矩阵 | 线性化点之前**全部向后补偿到 B0 字节**；`afterBundlePublish`..`afterCurrentNewWrite` 为 **RecoveryRequired 且 CURRENT 仍=B0、树≠B0**；`afterCurrentAtomicMove` 之后为 **RecoveryRequired 且 CURRENT=B1、旧字节不回归**；清理阶段故障为 Applied + CLEANUP 诊断 + 证据保留 |
| 方向不变式 | 每例断言 V1–V5（注入点必须到达、CURRENT 不丢失、B0 时树必须=B0 除非显式 RecoveryRequired、B1 时树必须≠B0、成功即 B1 已发布） |
| 模糊态 | 真实中断后把 CURRENT 改为未知 baseline、或删除 CURRENT，**recovery 均拒绝**且事务证据逐文件不变 |
| DELETE 硬链接约束 | 在 hook 内观测：`Files.isSameFile(backup,target)=true`、`nlink>=2`、同 file store。跨卷布局在**注册阶段**即被结构化拒绝且不改动输出 |
| 全量 | 两个闸门均 **BUILD SUCCESS**，合计 **542 run / 0 fail / 0 error / 5 skip**（该阶段计数）（478 → 542，增量恰为 Q6 的 64） |
| 生产改动 | **0**（规格中的 D1(a) 经实证不需要：UPDATE 族由 apply 事务承载，其注入面已完整） |

**该结果的有效边界**：跨卷情形只证明到"注册阶段拒绝"这一层；真正进入删除阶段后遇到链接不可用的路径，在本机无法构造（前置校验会先拒绝），该分支如实记为未覆盖。三路径矩阵均在 Linux 单文件系统（overlay）上执行。

### 1.8 2026-09-18 Linux Q7 CLI 产品边界（形态 A：只读边界冻结为证据）

**结果：CLI 的只读产品边界从"文档陈述"变成 12 项可执行断言；发现并修复 1 处生产缺陷（用法诊断不可复现）。**

| 项 | 证据 |
|---|---|
| 命令面 | `--help`/`-h`（顶层 + `context`/`plan`）、`--version`/`-V`、`context`、`plan` 均退出码 0；顶层 help 只列 `context`/`plan` |
| 未发布命令 | `generate`/`register`/`apply`/`recover`：退出码 **2**、`KCG-CLI-USAGE-*`、消息含 `unknown command`、**help 不含该命令**、调用前后**状态根与输出根逐文件字节不变** |
| 用法错误矩阵 | 无参数/未知命令/缺必填项/相对路径 → 退出码 2 且 `outcome=USAGE_ERROR`，输出仍是 canonical JSON |
| canonical JSON | 同一 `plan` 命令**跨进程**两次运行 stdout **字节相同**；首字段 `protocolVersion=KCG-CLI-CHANGE-PLANNING-V1`；单行 + 单个结尾换行 |
| 内部失败 | 注入崩溃 → 退出码 **70**、`KCG-CLI-INTERNAL-001`、仍为 canonical JSON 单行、内部消息不泄漏 |
| 生产边界闸门 | `kcg-cli/src/main` 常量池扫描 0 违规：不引用 Application/Change/ProjectGraph `internal` 与 generator 包；**命令路径不做任何文件 I/O**；**命令路径不引用 `io/kcg/cli/mvp/**`**。灵敏度探针证明三条规则都会真的报违规 |
| 修复的生产缺陷 | `CliCommandLine` 用 `Map.of` 存放已知选项，`Map.of` 迭代顺序未定义且**逐 JVM 加盐**，导致同一条不完整命令在不同进程报出不同的缺失项；改为按声明顺序的 `LinkedHashMap`（最小改动），并有跨进程回归测试 |
| 模块计数 | `kcg-cli` **30 / 0 / 0 / 0**（Q7 前 18） |
| 全量 | 两个闸门均 **BUILD SUCCESS**，合计 **554 run / 0 fail / 0 error / 5 skip**（542 → 554，增量恰为 Q7 的 12） |

**该结果的有效边界**：
- 四个写命令**仍未发布**；本单只证明它们被拒绝且无副作用（不构成本地生命周期资格）。
- **thin JAR / 发行包**属独立发布任务，本单 **`NOT_RUN`**：退出码在子 JVM（`java -cp … io.kcg.cli.KcgCli`）上验证，未验证打包产物。
- `io/kcg/cli/mvp/**` 作为"非命令路径的内部只读证据工具"保留（负责人 D1 选择 (a)），其命令分派不可达性有断言；该包本身是死代码候选。

### 1.9 2026-09-18 Linux Q9 G1 第一切片：Course 单文件分页查询端到端（真实 MySQL + HTTP）

**结果：G1 的第一条业务切片真实成立——单文件 SIR 源经 Parser → Semantic → Lowering → Generator → Maven 构建 → Spring Boot 启动 → HTTP 查询 → JDBC 独立核对全链路贯通，业务场景 40 项断言全部通过（0 失败）。本单新增 G1 查询语义（投影 `view`、`Page<T>`、`order by`、`Page p,s else <Error>`、`containsLiteral`）与三个实施期订正。**

| 项 | 证据 |
|---|---|
| 新增语法 | `view <Name> from <Entity> { field ...; }`、`Page<T>` 类型引用、`find` 的 `order by <field> <ascending\|descending>[, ...]` 与 `Page <page>, <size> else <Error>` 子句、谓词算子 `containsLiteral`（字面匹配） |
| 新增诊断归属 | 各规则落在唯一阶段并逐条有反例（code + 数量 + span）：Parser（`QueryGrammarTest` 7、`GrammarBoundaryTest` +9）、Semantic Resolve/Type/Validate/Normalize（`QuerySliceSemanticsTest` 28）、Lowering + IR 校验（`QuerySliceLoweringTest` 16）、Generator 契约（`GeneratorQuerySliceContractTest` 8） |
| Lowering 决策面 | `view` 投影字段只允许读源实体已声明字段且类型逐字段相等；`order by` 只允许根实体字段且方向仅为升降；Lowering 追加 identity 升序作为最终 tiebreaker；`Page<T>` 元素只允许 `view`，只允许出现在 `expose query`；分页边界与 LIKE 转义字符/转义字面集写入 Lowered IR（`PageSpec`、`StringMatch`）并由 IR 校验器与 Profile 策略（`SpringBootQueryPolicy`）一致化 |
| Generator 产物 | 新增 `view` 响应 DTO、分页响应封装与分页配置三个产物；`find` 渲染为 `selectCount` + `selectList(... .last("LIMIT offset, size"))`；字面匹配渲染为转义 helper + `LIKE … ESCAPE`；Generator 仍只消费 Lowered IR、不读盘 |
| 真实业务场景 | `QuerySliceBusinessConformanceIT`（`sir-toolchain-application`）在 MySQL 8.4.11 参考环境上启动生成应用并对 `/api/search-courses` 做 9 组独立 HTTP 请求 + 独立 JDBC 核对：**40 项断言全部通过，0 失败** |
| 分页与排序 | 第 1/2 页返回声明顺序的前后两页且 `total` 不随翻页变化；第 3 页（越界）返回空 `records` 且 `total=4`；省略 page/size 时回落固定默认值 1/20 |
| 字面匹配灵敏度对照 | 关键字 `%` 只命中 2 条（不转义会命中全部 7 条，断言会失败）；关键字 `9_9` 只命中 2 条而不命中 `919`（`_` 未转义会命中第 3 条）；两条对照均为**当真会因未转义而失败的断言** |
| 响应投影 | 响应体只含 `code`/`name`/`capacity`（`PageResponse` 四字段：`total`/`page`/`size`/`records`），不含实体主键 `id`，也不含未被投影的 `description` |
| 非法分页 | `page=0`、`page=10001`、`size=0`、`size=101` 均返回 **400** 且响应体不含任何记录（`page=10001` 由 IR 携带的 `maxPageNumber` 守卫生效）；空关键字与缺失关键字同为 400 |
| 只读性 | 场景前后独立 JDBC 行指纹相同（7 → 7），不修改任何行 |
| 生成工程编译 | 生成工程在离线仓库下 `mvn clean verify` 成功（生成文件 10 个，combined sha256 `9a2f5c33…`）；GEN-01 字节确定性由现有跨环境矩阵覆盖；GEN-02 冲突模型在 Generator 前被拒有反例与灵敏度对照 |
| 场景证据元数据 | `schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、advisory lock 取/放各一次、schema DROP 后缺席已证明、workRoot 已删除 |
| 全量 | 冻结与完成两种形式均 **BUILD SUCCESS**，合计 **620 run / 0 fail / 0 error / 5 skip**（554 → 620，**增量恰为 66**：parser +12、semantic +28、lowering +16、generator +10；5 项 skip 仍全为 Windows junction） |
| 生产改动范围 | Parser（语法/AST/构建器）、Semantic（`SymbolKind.VIEW`、新 ReferenceRole、Resolve/Type/Validate/Normalize、`NormalizedView`）、Lowering（`ViewDeclaration`、排序/分页/投影/字面量计划、IR 与输入校验）、Generator（三个新产物 + 分页查询渲染）、Project Graph（role 追加）、Change（新种类分发）、Application（Graph 输入映射）；无 POM/依赖改动 |

**三个实施期订正（均已写入工作单 Q9）**：
1. 分页不使用 MyBatis-Plus 的 `PaginationInnerInterceptor`：自 3.5.9 起它在独立 artifact `mybatis-plus-jsqlparser`，不在冻结仓库闭包内（定向离线编译报 `cannot find symbol`）。改为 `selectCount` + `selectList(... .last("LIMIT offset, size"))`，两个 API 均在 `mybatis-plus-core` 内，`LIMIT` 只拼接已校验的 `int`。
2. 声明错误到 HTTP 的映射只有 `@ResponseStatus(BAD_REQUEST)`，代码库中不存在 `TransportPlan.ErrorMapping`；完成门只断言“400 且不返回记录”，不要求响应体携带声明错误码（字段级稳定错误码属 Q10）。
3. 业务验证必须跑在 harness 已 provision 的 schema（`KCG_CONF_SCHEMA_NAME=kcg_conf_run`）：runtime 凭据只在该 schema 上有权限，自造 schema 名会让生成应用在首次查询报 500（证据：`Access denied for user ... to database`，已修正后重跑）。IT 因此复用 harness 的 advisory lock（`AdvisoryLockKey` + `GET_LOCK/RELEASE_LOCK`）与五场景矩阵互斥，取锁失败记 `NOT_RUN` 且不触碰 schema。

7. **C7 支撑文件必须是无条件的 target 产物**：若让 `ValidationSupport` 只在"存在写入能力"时生成，删除最后一个写能力的变更计划会被 `SIR-CHANGE-IMPACT-203`（"候选中消失但不在被删闭包内的 project 级 artifact"）拒绝——真实证据来自 `DeleteFaultMatrixTest` 的 17 项失败（`result=Failure[failedStage=PLAN, disposition=NO_CHANGES, diagnostics=[SIR-CHANGE-IMPACT-203 ... validation-support/project]]`）。修复：错误契约与校验原语改为无条件产物；`ToolchainProjectGraphIntegrationTest` 的 node/edge/artifact 计数随之从 35/34/2 更新为 45/44/7（并按 lowering 实际产物集合断言角色，而非固定数字）。
8. **C8 一个既有源文件是反编译产物**：`SpringBootModelLowerer.java` 在 `24eec6d` 中即为 CFR 反编译文本（无注释、`this.` 前缀、冗余泛型）。Q9/Q10 的改动都在该文本上继续；本单一次的批量文本编辑切坏了注释块并造成文件内出现"截断副本 + 完整副本"，**修复方式是保留从第二个 `package` 行到 EOF 的完整副本**，随后以 `sir-lowering-spring-boot` 全量测试（69 项）与两个业务 IT（61/40 项）证明行为等价。该文件保留反编译形态属既有状态，本单不做重写（重写会把 Q10 的审阅边界扩大到整文件）。

**该结果的有效边界（引用时必须一并说明）**：
- 验收：负责人 2026-09-21 回复“继续推进”，即视为验收通过并授权进入 G1 下一张工作单；工作单已归档至 `docs/roadmap/completed/Q9-query-slice-course-pagination.md`。
- 结论只对上述参考环境元组成立（MySQL 8.4.11、宿主端口 33306、应用端口 18080、离线仓库 `/root/.m2/repository`、Java 21.0.12）。
- 该 IT 是 **opt-in**，默认构建（无需外部环境的）不运行它；默认全量的 620 计数**不含**该场景。
- **`NOT_RUN` 不等于通过**：环境或 schema 锁不可得时该场景会如实记 `NOT_RUN`，此时 G1 完成门不成立。
- **写侧未被业务验证**：Create/Update/Persist 的步骤与渲染仍未经真实 MySQL 业务断言（BIZ-01/02 属 Q10）；PATCH 三态（BIZ-03）、`version` 乐观锁与 409（BIZ-04）、关联读取与 `EXISTS` 过滤（BIZ-06）、业务 DELETE 均**未实现**。
- **产品 schema 生命周期仍未实现**：DDL 与 seed 都是测试侧 fixture（`schemaSource=TEST_FIXTURE_DDL`），产品没有 INITIALIZE/UPDATE（G3）。
- `Page<T>` 只允许 `view` 元素；`containsLiteral` 只支持“实体字符串字段 与 输入字段”的形态，`in`/`isNull`/`when ... present` 可选过滤仍未实现。
- 只有根实体的排序与分页，无关联排序、无多查询组合；`PageResponse` 字段名与 `records` 形状是当前冻结契约。

### 1.10 2026-09-21 Linux Q10 G1 第二切片：Course 写侧（Create + PATCH 三态 + version 冲突 + 结构化字段错误）

**结果：G1 的写侧切片真实成立——单文件 SIR 源经 Parser → Semantic → Lowering → Generator → Maven 构建 → Spring Boot 启动 → 真实 HTTP 写请求 → 独立 JDBC 核对全链路贯通，业务场景 61 项断言全部通过（0 失败），同树回归下 Q9 场景 40 项断言亦全通过。本单第一次把写路径放到真实数据库上，因此发现的不是"语义是否自洽"，而是三个只有在真实运行才暴露的生成代码缺陷（见下）；另有两处结构约束由既有测试在实现期暴露（C7、C8）。**

| 项 | 证据 |
|---|---|
| 新增语法 | `field <n>: Int64 versioned;`、`error <Name> status <400\|404\|409>;`、`persist <var> else <Error>;`、`input <Name> patch of <Entity> { ... }`、谓词成员访问 `<ref>.present` |
| 新增诊断归属 | 各规则落在唯一阶段并有反例（code + 数量 + span）：Parser（`WriteGrammarTest` 14）、Semantic Resolve/Type/Validate/Normalize（`WriteSliceSemanticsTest` 21）、Lowering + IR 校验（`WriteSliceLoweringTest` 21，含 GEN-02 三类损坏模型）、Generator 契约（`GeneratorWriteSliceContractTest` 21） |
| Lowering 决策面 | `version` 规格（每实体唯一、`Int64`、初始值 0、递增 1）写入 `EntityDeclaration.Version`；patch 计划（变更集、身份字段、`expectedVersion` 属性名）写入 `PatchSpec`；条件更新（表名、身份列、版本列、`SET` 列清单、影响行数语义）写入 `ConditionalUpdate`；错误状态码写入 `ErrorDeclaration.httpStatus`；投影返回写入 `ResponseRepresentation.PROJECTION` |
| Generator 产物 | 新增 `ApiErrorResponse`（含嵌套 `FieldError`）、`ApiException`（基类，携带码/状态/字段）、`ApiExceptionAdvice`（三类异常 → 统一信封，含解码失败的路径拼接）、`ValidationSupport`（约束原语）、`application.yml`（`fail-on-unknown-properties: true`）；patch 载荷渲染为信封 + 变更集（`has(...)` 记录请求携带的属性）；条件更新渲染为 `SELECT … FOR UPDATE` + 显式 `@Update`，写入后报告已提交版本；创建渲染 `@ResponseStatus(CREATED)` 与投影返回。Generator 仍只消费 Lowered IR、不读盘 |
| 真实业务场景 | `WriteSliceBusinessConformanceIT`（`sir-toolchain-application`）在 MySQL 8.4.11 参考环境上启动生成应用并对 `/api/create-course`、`/api/get-course`、`/api/update-course` 做 16 组独立请求 + 独立 JDBC 整行指纹核对：**61 项断言全部通过，0 失败**；生成工程 22 个文件（combined sha256 `575ad84d…`），离线 `mvn clean verify` 成功；证据 `/root/kcg-conformance/evidence/write-slice-1a0c4a9648b-38551/write-slice-report.txt` |
| 写契约 | 创建返回 **201** 与声明投影且数据库 `version=0`；读取 200、未知身份 404 `CourseNotFound`；局部变更只动被点名列并 `version+1`（整行指纹核对：`1\|CS101\|Intro to Algorithms\|Asymptotic analysis and recursion\|77\|1`）；显式 null 清空而缺席字段保持；陈旧 `expectedVersion` **409 `StaleVersion`** 且整行指纹不变 |
| 并发 | 同一 `expectedVersion` 的两次 PATCH 经 `CyclicBarrier` 同时发出：状态恰为 `[200, 409]`，版本只递增一次（`…\|21\|1`）——"恰好一成功"与"无丢失更新"各有独立断言 |
| 版本语义 | 存量版本 4 的行：`expectedVersion=0` 必须 409、`expectedVersion=4` 必须 200 且版本变 5——证明比较的是库存版本而不是"新行恒为 0" |
| 错误契约 | 空变更集 400 `EmptyChange`；未知变更属性 400 且路径 `changes.code`；缺 `expectedVersion` / 缺 `id` 各 400 且 `fields[].path` 点名；未知信封属性 400；空名（候选校验）400 且路径 `changes.name` + 码 `notBlank`；创建违反约束 400 且**未落库**（行数不变）。每次失败请求后都做整行指纹比对，因此"拒绝"与"拒绝且未写入"是两条独立断言 |
| 场景证据元数据 | `schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、advisory lock 取/放各一次、schema DROP 后缺席已证明、通过时 workRoot 已删除 |
| 同树回归 | Q9 场景 `QuerySliceBusinessConformanceIT` 在同一棵树上重跑：**PASSED，40/40**（错误契约变更后 Q9 的 400 断言仍成立；生成工程 15 个文件，combined sha256 `1b3a7d6a…`；证据 `/root/kcg-conformance/evidence/query-slice-1a0c4a9eb19-44308/query-slice-report.txt`） |
| 全量 | 冻结与完成两种形式均 **BUILD SUCCESS**，合计 **702 run / 0 fail / 0 error / 5 skip**（对比上一次记录的合计 620：+82；本次重测的各模块计数见第 2 节，其中既有模块行也按完成形式复跑重新取值）；5 项 skip 仍全为 Windows junction |
| 生产改动范围 | Parser（语法/AST/构建器）、Semantic（`SymbolKind.VIEW` 复用 + 13→18 个 ReferenceRole + `patchFieldBindings` + Resolve/Type/Validate/Normalize 新规则）、Lowering（`EntityDeclaration.Version`、`PatchSpec`、`ConditionalUpdate`、新 `ProjectArtifact` 变体、IR 校验）、Generator（五个新产物 + patch/条件更新/候选校验/投影/可空边界渲染）、Project Graph（`ArtifactRole.VIEW`）、Change（新种类分发）、Application（Graph 输入映射 + 业务 IT 与共享 harness）；无 POM/依赖改动 |

**六个实施期订正（均已写入工作单 Q10）**，其中 C1–C3 是**真实运行发现的产品缺陷**，无法由单元测试、生成工程编译或 conformance harness 自身证明：

1. **C1 实体可空字段不能用 `java.util.Optional` 承载**：冻结依赖中 MyBatis（3.5.19）与 MyBatis-Plus（3.5.12）都没有 `Optional` 参数类型处理器；真实运行证据为插入路径 `Type handler was null on parameter mapping for property 'description' (javaType java.util.Optional)` 与条件更新路径 `Cannot convert class java.util.Optional to SQL type`，两者都是 HTTP 500。修复：实体可空字段映射为普通可空属性，`Optional` 只保留在载荷与视图，边界处显式 `orElse(null)` / `Optional.ofNullable`。
2. **C2 条件更新后的响应必须报告已提交版本**：`WHERE version=? … version=version+1` 只改数据库，内存实体仍是加载时的版本（首次运行：库中 `version=1`、响应 `"version":0`）。修复：影响行数为 1 后显式设置 `expectedVersion + 1`。
3. **C3 载荷约束写在 `Optional` 字段上会让 Hibernate Validator 抛错**：`@Size` 作用于 `Optional<String>` 字段触发 `UnexpectedTypeException HV000030`（500 而非 400）。修复：可空载荷字段的约束改写为容器元素约束（`Optional<@Size(...) String>`）。
4. **C4 信封必填成员**：`id` 与 `expectedVersion` 加 `@NotNull`、控制器对 patch 载荷加 `@Valid`（SIR 无法表达"必填"，落在生成的信封 DTO 上），缺失成员由此得到 400 且路径点名；此前会先撞版本预检（409）或身份解析（404）。
5. **C5 未知变更属性的拒绝点**：若载荷用 `@JsonAnySetter` 收集未知属性，请求会先被 `validate … else EmptyChange` 拦成 `EmptyChange`，未知属性名不会出现在响应里。修复：载荷不吞未知属性，交由 `fail-on-unknown-properties` 拒绝，advice 用 Jackson 引用路径拼出 `changes.code`。
6. **C6 参考环境 control JDBC URL 不得自带 schema**：`provision-mysql.sh` 曾把 schema 写进 control URL，被 harness 断言拒绝；已改回不带 schema，且脚本不再预建运行 schema（harness 自己 CREATE/DROP 以证明所有权），fixture DDL/seed 经 `USE` 落在本次运行的 schema 内。该脚本在仓库外，未进入生产代码。

**该结果的有效边界（引用时必须一并说明）**：
- 结论只对上述参考环境元组成立（MySQL 8.4.11、宿主端口 33306、应用端口 18080、离线仓库 `/root/.m2/repository`、Java 21.0.12）。
- 该 IT 是 **opt-in**，默认构建不运行它；默认全量计数**不含**该场景。
- **`NOT_RUN` 不等于通过**：环境或 schema 锁不可得时如实记 `NOT_RUN`，且参考库在运行中不可达时同样记 `NOT_RUN`（不把基础设施故障伪装成产品失败），此时 G1 完成门不成立。
- **产品 schema 生命周期仍未实现**：DDL 与 seed 都是测试侧 fixture（`schemaSource=TEST_FIXTURE_DDL`），产品没有 INITIALIZE/UPDATE（G3）。
- **未覆盖**：DELETE、路由模板与嵌套 `/courses/{id}`（登记 Q12）、`in`/`isNull` 等过滤算子、关联读取与 `EXISTS` 过滤（Q11）、多实体/多文件与持久身份（G2）、数据库迁移生命周期（G3）、Web 平台与 docker 交付包（G4/G6）。
- 字段级错误码当前为 Bean Validation 约束码（`notBlank|email|length|min|max|NotNull`）+ 信封级 `INVALID_REQUEST`；与设计 02 的码名逐字一致性尚未裁决（见工作单"与 D 裁决的差异"）。

## 2. 模块测试统计

以下为 **2026-09-21 Linux Q10 后完成形式复跑** 的记录（裸 `mvn -o clean verify` 与 `-Dmaven.test.failure.ignore=true` 两种形式同样的每模块计数），命令与模块明细见 1.3、1.4、1.9、1.10；2026-08-11 Windows 运行见本节末尾对比。

**口径订正**：本节此前记录的各模块单元数字取自不同批次的部分运行（例如 Q9 之前 `sir-toolchain-application` 尚未完整跑完），与 Q9/Q10 的完成形式复跑不一致；本次以实测 Surefire 结果重新取值。上一次记录的合计为 620，本次为 702。

统计以 Surefire XML 为准；JUnit `@Nested` 容器和类级 assumption 的展示差异不改变实际 testcase 统计。

| 模块 | run | fail | error | skip | 当前解释 |
|---|---:|---:|---:|---:|---|
| `sir-parser` | 69 | 0 | 0 | 0 | Parser 基础契约；Q9 新增 12 项（`view`/`Page<T>`/`order by`/`Page … else`/`containsLiteral` 的正反例与边界）、Q10 新增 14 项（`WriteGrammarTest`：`versioned`、`error … status`、`persist … else`、`patch of`、`.present`） |
| `sir-semantic` | 159 | 0 | 0 | 0 | 含 typed reference-site 契约；Q9 新增 28 项（投影绑定与类型、分页/排序/字面量约束与四类新 ReferenceRole）、Q10 新增 21 项（`WriteSliceSemanticsTest`：版本字段、状态码、条件持久化、patch 载荷与存在性表达式） |
| `sir-lowering-api` | 4 | 0 | 0 | 0 | API 契约 |
| `sir-lowering-spring-boot` | 69 | 0 | 0 | 0 | 含 19 项 `@Nested` hardening 测试；Q9 新增 16 项（投影/排序/分页/字面量计划、IR 校验与策略一致性、冲突模型拒绝）、Q10 新增 21 项（`WriteSliceLoweringTest`：版本规格、patch 计划、条件更新、候选校验、错误契约 artifact 与 GEN-02 三类损坏模型） |
| `sir-generator-spring-boot` | 67 | 0 | 0 | 0 | canonical 输出、主要 Renderer、跨环境字节确定性和完整生成工程离线编译，另加 Q1 生产边界闸门（生产 census 37 个 class、禁止引用 0 违规、公开入口反射）；Q9 新增 10 项（投影 DTO/分页响应/分页配置/分页查询渲染契约）、Q10 新增 21 项（`GeneratorWriteSliceContractTest`：信封/变更集/条件更新/候选校验/投影/可空边界）并扩展确定性矩阵与离线编译覆盖写侧切片 |
| `sir-project-graph` | 72 | 0 | 0 | 0 | Q2 建立的直接模块契约：四类边、规则矩阵、canonical 序列化/加载/摘要往返、只读边界闸门（生产 census 69 个 class、0 违规）与不可信字节版本/来源类型/不可编码标量守卫 |
| `sir-change` | 22 | 0 | 0 | 0 | API/架构/fixture 支撑测试，覆盖不足 |
| `sir-toolchain-application` | 210 | 0 | 0 | 5 | conformance 包已恢复编译与运行（含 13 项此前被隐藏的模块内测试）；5 项软链接失败已在 Q3 修复（剩 5 skip 全为 Windows junction）；见 1.6 |
| `kcg-cli` | 30 | 0 | 0 | 0 | 含 Q7 新增 12 项（产品边界 6、生产边界闸门 4、内部失败 2）；另有 5 项 hardening + 13 项 Change 工作流（Q3 前整类被类级 assumption 跳过）；见 1.8 |
| **合计** | **702** | **0** | **0** | **5** | 2026-09-21 Q10 后完成形式复跑；冻结与完成两种形式均为 BUILD SUCCESS；无失败、无错误 |

对比 2026-08-11 Windows 运行：合计 373 / 0 / 0 / 10；该平台未真实执行软链接用例，因此那次 `0 failed` 不能证明软链接拒绝路径可用。当前 Linux 计数为 702（本次完成形式复跑）：Q1–Q3 阶段记录为 465，Q4+Q5 增加 13 项，Q6 增加 64 项，Q7 增加 12 项，Q9 增加 66 项（记录合计 620），Q10 增加 82 项（重测口径，见上）；5 项 skip 仍为 Windows junction。各阶段证据见 1.3–1.10。

## 3. 跳过与排除

### 计入 Surefire 的 skip

2026-09-21 Linux 当前为 5 项（Q3 后，Q9/Q10 未新增任何 skip 或排除）：

- `PathSecurityReviewTest` 5 项：junction 是 Windows 专属概念，在 Linux 下不适用。

2026-09-18 Linux Q3 前为 11 项：上项 5 项 + `ChangePlanningApplicationTest` 6 项（缺 Change base/candidate fixtures，已在 Q3 补齐并真实执行）。

2026-08-11 Windows 为 10 项：同样是 `ChangePlanningApplicationTest` 6 项，另 4 项来自 `PathGuardTest` 的非 Windows 路径语义。Q1 生产边界闸门未新增任何 skip。

### 类级 skip（现已消除）

- `KcgCliWorkflowTest` 13 个测试方法曾因同一批 Change fixtures 缺失而在类初始化阶段 assumption skip，且**不计入 Surefire 的 run/skip 计数**，导致 `kcg-cli` 看起来只有 5 项测试。Q3 补齐 fixtures 后，该类改为逐测试 `requireFixtures(...)`，13 项全部真实执行并通过。

### POM 硬排除（2026-09-18 已全部移除）

`sir-toolchain-application` 当前同时从 testCompile 和 Surefire 排除：

```text
（原 compiler `testExcludes` 与 surefire `excludes` 均指向 `io/kcg/sir/application/conformance/**`；
两处排除已在 Q4+Q5 删除，该包现参加编译与运行。）
```

（历史说明，已不再适用）目录中有 46 个 Java 文件，但多个 suite/fixture 类包含不完整实现或占位尾部。默认构建成功不能证明这些文件可编译或 conformance 可运行。

## 4. CLI 资格

当前 CLI 版本：`KCG-CLI-CHANGE-PLANNING-V1`。

- `--help`、`--version`：可运行，且有直接断言（含 `-h`/`-V` 别名等价、顶层 help 不含未发布命令）。
- `context`、`plan`：当前正式命令；退出码与 canonical JSON 有直接断言（跨进程字节相同）。
- `generate`、`register`、`apply`、`recover`：**当前返回 unknown command 且退出码 2**，不是已发布 CLI 能力；有"调用前后状态根与输出根逐文件字节不变"的无副作用证据（Q7）。
- 生产边界有静态证据：`kcg-cli/src/main` 不引用 Application/Change/ProjectGraph `internal` 包与 generator 包，命令路径不做文件 I/O，命令路径不引用 `io/kcg/cli/mvp/**`（Q7 闸门 + 灵敏度探针）。
- 当前 JAR 是 thin JAR；直接 `java -jar` 不作为受支持运行方式（**未验证，属"发行包"独立任务**）。
- 已验证入口是 Maven exec 与 `java -cp … io.kcg.cli.KcgCli`。

## 5. 外部资格

| 资格项 | 当前状态 | 原因 |
|---|---|---|
| 默认离线 Reactor | **PASS（Linux 2026-09-18）** / PARTIAL（Windows 历史） | 2026-09-18 Linux 冻结与完成两种形式均 BUILD SUCCESS，十个模块全部完成，合计 **465/0/0/5**（见 1.5）；5 项 skip 均为 Windows junction。2026-08-11 Windows 那次 `0 failed` 不能证明软链接拒绝路径（当次未真实执行），但 Linux 已提供该证据 |
| 完整生成工程离线编译 | PASS | `campus-market.sir` 的当前 Generator 输出在临时目录以 Java 21、Maven `--offline` 和 `D:\maven-repo` 完成 `compile`，代表性 class 文件存在 |
| Windows 路径与 junction 定向测试 | PASS/PARTIAL | 当前测试通过；第二卷挂载点未验证 |
| 外部 MySQL conformance | **QUALIFIED**（见 1.6） | 在 MySQL 8.4.11 参考环境上五场景全通过且全部清理证明成立；换环境需重跑 |
| 完整 CLI 本地生命周期 | NOT_RUN | 四个写命令未发布，fixtures 和外部资格也未闭合 |
| 生产就绪 | NOT_CLAIMED | 无生产、安全、性能、HA 或全平台资格 |

历史环境中出现过的 `QUALIFIED` 或 `MVP_FEASIBLE` 只属于对应的日期化证据，不自动转移为当前版本结论。

## 6. 主要残余风险

1. conformance 包现已参加编译与运行；残余缺口是该包内并非每个辅助类都有独立单元测试，且外部矩阵只在单一参考环境上验证过。
2. Generator 已有 38 项直接测试，包含 2026-09-18 建立的 Q1 生产边界闸门：生产 census 37 个 class 的常量池禁止引用扫描为 0 违规，公开入口只有 `generate(SpringBootLoweredModel)`。仍属残余缺口：禁止集合按已批准规格不含 `io/kcg/sir/semantic/{context,type,internal}`；扫描只覆盖静态引用，不覆盖运行时行为、反射逃逸或生成 Java 文本的正确性。
3. Project Graph 已有 72 项直接模块测试（Q2，2026-09-18，已验收归档，见 1.4）：四类边、规则矩阵、canonical 往返、只读边界闸门（生产 class 禁止引用 0 违规）与不可信字节守卫（`COMPAT-010` / `COMPAT-002` / 编码器 `COMPAT-001` / `SERIALIZE-001`）。仍属残余缺口：`SIR-GRAPH-PROVENANCE-001` 不能由公开路径触发（仅反射层面证据，可达替身 `COMPAT-010` 已覆盖）；`SIR-GRAPH-VERSION-002` 与序列化器 `SIR-GRAPH-COMPAT-001` 因枚举单值而不可达（绊线测试守位）；边界扫描只覆盖静态引用；`REFERENCES` 边、Graph service、Snapshot V2 和增量索引不在当前范围。
4. ~~Change fixtures 缺失造成 Application 和 CLI 测试跳过。~~ **已在 Q3 闭合**（见 1.5）：新增 13 个 fixture，应用模块 6 项与 CLI 13 项均真实执行并通过。仍未做：Change 操作族的跨版本组合矩阵、完整的 `SIR-CHANGE-*` 失败矩阵。
5. ~~UPDATE/CREATE/DELETE 的完整跨阶段故障注入矩阵尚未形成。~~ **已在 Q6 闭合**（见 1.7）：三路径同一套 18/15/16 个正向中断点 + 各自的补偿分支，方向语义一致，另有模糊态 fail-closed 与硬链接证据；残余缺口是跨卷只到注册阶段。
6. CLI 完整生命周期和发行形态**仍未决定**（见第 0 节登记表）：四个写命令未发布，thin JAR/发行包 `NOT_RUN`。
7. ~~2026-09-08 的设计校准运行记录标准 `clean verify` 在 `sir-parser:testCompile` 失败，与 2026-08-11 的 `BUILD SUCCESS` 冲突。~~ **已在 G0 关闭动作中裁决**（2026-09-18，见第 0 节与 1.1）：以 Linux 全新仓库复跑为准，未复现该失败；`implementation-baseline.md` 保留原始快照并加入指向本节与第 0 节的裁决补记。
8. 软链接拒绝路径长期缺少真实执行证据：`SafeTargetResolverTest.tryCreateSymlink` 在创建失败时静默 `return`（记为通过而非跳过），`PathGuardTest.targetItselfIsSymlinkIsRejected` 仅 LINUX/MAC 启用。2026-09-18 Linux 复跑首次真实执行并暴露 5 项失败（见 1.5）；**Q3 已定性并修复**（见 1.5）：4 项为诊断消息拼写（`link in raw chain` → `symlink or reparse point in raw chain`），1 项为 `PathGuard` 叶子链诊断遮蔽（现只检查严格祖先链，叶子符号链接恢复为 `CONFLICT-001/002`）。仍属缺口：Windows 上的 junction 用例在 Linux 不运行，Windows 平台仍未作回归。

## 7. 结论使用规则

- 可以说：2026-09-18 Linux 离线 Reactor 在**冻结与完成两种形式**下均 BUILD SUCCESS，十个模块全部完成，合计 **554 run / 0 fail / 0 error / 5 skip**（G0 完成门，见第 0 节；9 个模块全部完成）（5 项 skip 均为 Windows junction，见 1.5）；Generator 生产边界闸门（Q1）、Project Graph 直接模块契约 72 项（Q2）、Change 工作流 13 项与应用模块 6 项均真实执行；软链接拒绝路径在 Linux 上有直接证据。
- 可以说：外部 MySQL conformance 在 **第 0 节登记的参考环境元组**上 QUALIFIED（五场景全通过；换环境需重跑）。
- 不可以说：conformance 在**任意环境**都通过、完整本地 CLI 生命周期通过、完整 MVP 通过、Windows 平台已回归、生产就绪。
- 可以说：CLI 的**只读**边界（`context`/`plan`）有可执行证据；不可以说四个写命令可用（它们未发布）。
- 新运行完成后直接更新本文件的日期、命令、模块统计、skip/exclude 和外部资格状态；不要叠加多个相互竞争的“当前报告”。
