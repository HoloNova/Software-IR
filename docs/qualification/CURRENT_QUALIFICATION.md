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

**仓库状态**：`git diff --check` 无输出（exit 0）；78 个变更条目（34 modified / 2 deleted / 42 untracked），**其中包含项目负责人自己新增的未跟踪文档，见 G0 关闭动作的提交内容清单**；未跟踪构建产物 0 个。**未执行任何提交或推送**（负责人指示：G0 完成后由其手动提交）。

**G0 内部工作单（阶段 1–6）全部完成并归档**：Q1（Generator 生产边界）、Q2（Project Graph 直接契约 0→72）、Q3（Change fixtures + 软链接收口，全量首次 BUILD SUCCESS）、Q4+Q5（conformance 包恢复 + 真实 MySQL 矩阵 QUALIFIED）、Q6（三路径故障矩阵，64 项，零生产改动）、Q7（CLI 只读边界，12 项，修复 1 处生产缺陷）。见 `docs/roadmap/completed/`。

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

**数字口径（D1）**：本报告 1.2–1.8 记录的是**各阶段当时的计数**（374／451／465／478／542 等），它们保留原值以保全历史证据；**当前权威总数只有一个，即本节与报告开头的 554 / 0 / 0 / 5**。


- 资格日期：2026-08-11（Windows）；2026-09-18 在 Linux 新机器以全新本地仓库复跑，并完成 Q1–Q7（G0 阶段 1–6 全部工作单）Q2 Project Graph 直接模块契约，见 1.2、1.3、1.4
- 运行环境：Windows 11 + 离线仓库 `D:\maven-repo`（2026-08-11 记录）；Linux + Java 21.0.12 + Maven 3.6.3 + `/root/.m2/repository`（§1.2 新仓库复跑，§1.3 Q1 边界闸门与全量复跑；§2、§3、§5 的模块数字以 §1.3 为准）
- 总体结论：Linux 冻结与完成两种形式的离线 Reactor 均为 **BUILD SUCCESS**，合计 **554 run / 0 fail / 0 error / 5 skip**（Q1 的 Generator 生产边界闸门 6 项、Q2 的 `sir-project-graph` 72 项直接模块测试、Q3 的 Change 工作流 13 项与应用模块 6 项解除跳过）；5 项剩余 skip 全为 Windows junction。Generator 生产边界与 Project Graph 只读边界均首次执行即 0 违规；Q3 修了 2 处生产诊断缺陷（见 1.5），其余工作未修改生产代码；外部 conformance 与完整本地 MVP 未完成

## 1. 标准命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

最近一次实际结果：`BUILD SUCCESS`，父工程和九个子模块全部进入 Reactor 并成功结束。

### 1.1 待裁决的证据冲突（2026-09-18 登记）

`docs/design/implementation-baseline.md`（2026-09-08，源码基点 `5bba6ea`）记录同一标准命令失败于 `sir-parser:testCompile`，报告找不到 `io.kcg.sir.api`、`io.kcg.sir.ast` 等包，后续模块未由该次运行验证；其中另一次带 `-Dmaven.compiler.useModulePath=false` 的增量运行让 Parser 43 项通过，再接一次 `-o verify` 时 `sir-semantic` 报 `Fatal error compiling`。

本文件与 `docs/PROJECT_STATUS.md` 记录的 `BUILD SUCCESS` 来自 2026-08-11 那次运行。两次运行使用同一命令、同一源码基点，日期与环境不同：

- 在当次 `clean verify` 复跑裁决之前，本文件的 `PASS` 只代表 2026-08-11 那次运行，不自动延伸为今天的结论；
- 不得据任一方推断当前 `clean verify` 一定成功或一定失败，也不得把 `useModulePath=false` 的增量通过当作 clean 资格；
- 复跑结果按第 7 节规则直接回写本文件，不新增互相竞争的报告。

Linux 侧的复跑结果见 1.2：它没有复现这次 `sir-parser:testCompile` 失败。

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

### 1.5 2026-09-18 Linux Q3 Change fixtures 与软链接失败收口

本节关闭两件挂账：1.2 登记的 5 项软链接失败（已并入 Q3），以及覆盖清单第 4 节的 Change/base-candidate fixtures 缺口。工作单见 `docs/roadmap/ACTIVE_WORK.md`（Q3）。

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
| ① 冻结形式 `-o clean verify` | **BUILD SUCCESS（exit 0）**；十个模块全部完成；合计 **554 run / 0 fail / 0 error / 5 skip**（G0 完成门，见第 0 节；9 个模块全部完成） |
| ② 完成形式（加 `-Dmaven.test.failure.ignore=true`） | **BUILD SUCCESS**；数字与①完全相同 **465 / 0 / 0 / 5** |
| `sir-toolchain-application` | **133 run / 0 fail / 0 error / 5 skip**（原 132/5/0/11） |
| `kcg-cli` | **18 run / 0 fail / 0 error / 0 skip**（原 5 run / 13 项类级 skip） |

skip 11 → 5 的逐条解释：`PathSecurityReviewTest` 5 项 Windows junction 保留（`@EnabledOnOs(WINDOWS)`）；`ChangePlanningApplicationTest` 6 项 assumption 消失（fixture 补齐后真实执行并通过）；`KcgCliWorkflowTest` 13 项类级跳过全部消除并计入执行。**无任何新增 skip**。执行中的测试 440 → 460。

过程中发现并修正的 6 处缺陷全部为 **test-side 或 fixture-side**（非生产 RED）：CLI base SIR 放错目录（`resource()` 只查 `/io/kcg/cli/`）、`baseline001RawSha256MismatchFails` 的替换目标是乱码导致替换空转、v03 fixture 让被删能力成为唯一带 actor 的能力（`ApplicationRenderer` 按 actor 传输计划分叉，`IMPACT-202` 是正确行为）、v05 字段必须真正未被引用、类级门掩盖缺件、`M1TestSupport` 为无调用者的死测试支撑类（仅登记不改动）。详见工作单“执行证据”。

仍属缺口：CLI 只有 `context`/`plan` 两个只读命令被执行（`generate`/`register`/`apply`/`recover` 未发布）；Change 操作族尚未补跨版本组合矩阵；conformance 整包、MySQL/HTTP、事务故障注入矩阵仍未运行。

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

## 2. 模块测试统计

以下为 **2026-09-18 Linux 完成形式复跑（含 Q1 生产边界测试与 Q2 Project Graph 直接契约）** 的记录，命令与模块明细见 1.3、1.4；2026-08-11 Windows 运行见本节末尾对比。

统计以 Surefire XML 为准；JUnit `@Nested` 容器和类级 assumption 的展示差异不改变实际 testcase 统计。

| 模块 | run | fail | error | skip | 当前解释 |
|---|---:|---:|---:|---:|---|
| `sir-parser` | 43 | 0 | 0 | 0 | Parser 基础契约 |
| `sir-semantic` | 103 | 0 | 0 | 0 | 含 typed reference-site 契约 |
| `sir-lowering-api` | 4 | 0 | 0 | 0 | API 契约 |
| `sir-lowering-spring-boot` | 32 | 0 | 0 | 0 | 含 19 项 `@Nested` hardening 测试 |
| `sir-generator-spring-boot` | 38 | 0 | 0 | 0 | canonical 输出、主要 Renderer、跨环境字节确定性和完整生成工程离线编译，另加 Q1 生产边界闸门（生产 census 37 个 class、禁止引用 0 违规、公开入口反射） |
| `sir-project-graph` | 72 | 0 | 0 | 0 | Q2 建立的直接模块契约：四类边、规则矩阵、canonical 序列化/加载/摘要往返、只读边界闸门（生产 census 69 个 class、0 违规）与不可信字节版本/来源类型/不可编码标量守卫 |
| `sir-change` | 22 | 0 | 0 | 0 | API/架构/fixture 支撑测试，覆盖不足 |
| `sir-toolchain-application` | 210 | 0 | 0 | 5 | conformance 包已恢复编译与运行（含 13 项此前被隐藏的模块内测试）；5 项软链接失败已在 Q3 修复（剩 5 skip 全为 Windows junction）；见 1.5 |
| `kcg-cli` | 30 | 0 | 0 | 0 | 含 Q7 新增 12 项（产品边界 6、生产边界闸门 4、内部失败 2）；另有 5 项 hardening + 13 项 Change 工作流（Q3 前整类被类级 assumption 跳过）；见 1.5 |
| **合计** | **465** | **0** | **0** | **5** | 冻结与完成两种形式均为 BUILD SUCCESS；无失败、无错误 |

对比 2026-08-11 Windows 运行：合计 373 / 0 / 0 / 10；该平台未真实执行软链接用例（见第 6 节第 8 项），因此那次 `0 failed` 不能证明软链接拒绝路径可用。373 → 465 的 +92 含 Q1 新增的 `GeneratorProductionBoundaryTest` 6 项（见 1.3）、Q2 新增的 `sir-project-graph` 72 项（见 1.4）与 Q3 解除跳过的应用模块 6 项 + CLI 13 项及新增守卫 1 项（见 1.5）；skip 10 → 5 的变化见 1.5 的逐条解释。

## 3. 跳过与排除

### 计入 Surefire 的 skip

2026-09-18 Linux 当前为 5 项（Q3 后）：

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
8. 软链接拒绝路径长期缺少真实执行证据：`SafeTargetResolverTest.tryCreateSymlink` 在创建失败时静默 `return`（记为通过而非跳过），`PathGuardTest.targetItselfIsSymlinkIsRejected` 仅 LINUX/MAC 启用。2026-09-18 Linux 复跑首次真实执行并暴露 5 项失败（见 1.2）；**Q3 已定性并修复**（见 1.5）：4 项为诊断消息拼写（`link in raw chain` → `symlink or reparse point in raw chain`），1 项为 `PathGuard` 叶子链诊断遮蔽（现只检查严格祖先链，叶子符号链接恢复为 `CONFLICT-001/002`）。仍属缺口：Windows 上的 junction 用例在 Linux 不运行，Windows 平台仍未作回归。

## 7. 结论使用规则

- 可以说：2026-09-18 Linux 离线 Reactor 在**冻结与完成两种形式**下均 BUILD SUCCESS，十个模块全部完成，合计 **554 run / 0 fail / 0 error / 5 skip**（G0 完成门，见第 0 节；9 个模块全部完成）（5 项 skip 均为 Windows junction，见 1.5）；Generator 生产边界闸门（Q1）、Project Graph 直接模块契约 72 项（Q2）、Change 工作流 13 项与应用模块 6 项均真实执行；软链接拒绝路径在 Linux 上有直接证据。
- 可以说：外部 MySQL conformance 在 **第 0 节登记的参考环境元组**上 QUALIFIED（五场景全通过；换环境需重跑）。
- 不可以说：conformance 在**任意环境**都通过、完整本地 CLI 生命周期通过、完整 MVP 通过、Windows 平台已回归、生产就绪。
- 可以说：CLI 的**只读**边界（`context`/`plan`）有可执行证据；不可以说四个写命令可用（它们未发布）。
- 新运行完成后直接更新本文件的日期、命令、模块统计、skip/exclude 和外部资格状态；不要叠加多个相互竞争的“当前报告”。
