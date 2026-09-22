# 已完成工作单：Q1 Generator 系统测试总验收

- 状态：`DONE`（项目负责人 2026-09-18 确认 Q1 整体通过）
- 归档：2026-09-18 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 所属阶段：路线图阶段 1 / Generator 系统测试；对应主设计 **G0：现有链路资格收口**（本工作单是 G0 内部阶段 1 的总验收，G0 还有阶段 2–6）
- 方向来源：`docs/roadmap/README.md`、`docs/design/implementation-baseline.md`（阶段门与当次实现校准）
- 前置工作：Q1A、Q1B1、Q1B2、Q1B3A、Q1B3B、Q1C、Q1D 均已完成
- 已确认方案：编译后生产字节码边界扫描 + 公开入口反射检查
- 验收结论：定向 `GeneratorProductionBoundaryTest` 6 run / 0 fail / 0 error / 0 skip；全量完成形式 379 run / 5 fail / 0 error / 11 skip（5 项均为已登记的 `sir-toolchain-application` 软链接断言）；生产 census 37 个 class 禁止引用 0 违规；未修改任何生产代码。证据见资格文档 1.3。
- 版本快照：**未提交、未推送**。项目负责人 2026-09-18 决定 Q1 不单独提交，与后续工作一起按初定计划在 **G1 完成后统一提交**；归档时本地 HEAD 与 `origin/main` 跟踪引用都仍是 `902a7a5`，本工作单的未提交改动未进入任何提交。

## 目标

关闭路线图阶段 1 最后一项未被直接执行的判断：证明生产 `sir-generator-spring-boot` 只以 `SpringBootLoweredModel` 为生成输入，不读取 AST、SIR、SymbolTable、文件系统、进程环境、时间或随机源。

Q1A～Q1D 已证明输出完整性、Renderer 行为、Transport、字节确定性和完整生成工程离线编译。本工作单只增加架构边界闸门和 Q1 汇总验收，不重复已有契约。

## 已比较方案与决定

1. **编译后字节码扫描 + 反射检查（已确认）**：不依赖源码目录，检查真正进入产物的依赖；需要一个小型 test-only class-file constant-pool reader。
2. 源码/import 扫描：可读性高，但可能漏掉全限定名、方法描述符或非 import 引用，也依赖源码布局。
3. 人工审查 + POM：实现成本低，但不能阻止后续回归。

采用方案 1。不得同时加入源码扫描或第三方架构测试依赖。

## 边界设计

### 1. 公开生成入口

通过反射检查生产 `SpringBootGenerator`：

- 唯一公开生成方法为 `generate(SpringBootLoweredModel)`；
- 返回类型为 `GenerationResult`；
- 不出现 AST、SIR、Normalized Model、SymbolTable、路径或文件参数；
- 不为测试增加新的生产入口、构造器或可见性。

`GeneratedFile` 保留现有 `SymbolId` ownership metadata 是已冻结输出契约，不等于 Generator 重新消费 Semantic Model；本工作单明确允许这一项精确依赖，不允许扩大为其他 Semantic API。

### 2. 编译后生产字节码扫描

从 `SpringBootGenerator.class` 的 code source 定位当前模块生产 `target/classes`，递归读取其中全部 `.class`。test-only reader 解析 class-file constant pool 的 UTF-8 项，并检查内部类名、描述符和符号文本中是否出现禁止引用。

禁止集合：

- Parser / AST / Semantic 输入：
  - `io/kcg/sir/ast/`
  - `io/kcg/sir/api/`
  - `io/kcg/sir/internal/`
  - `io/kcg/sir/source/`
  - `io/kcg/sir/semantic/api/`
  - `io/kcg/sir/semantic/model/`
  - `io/kcg/sir/semantic/symbol/` 下除精确 `SymbolId` 之外的类型，尤其 `SymbolTable`
- 磁盘和流式 I/O：`java/nio/file/`、`java/io/`
- 环境入口：`java/lang/System`
- 时间：`java/time/`
- 随机源：`java/util/Random`、`java/util/concurrent/ThreadLocalRandom`、`java/security/SecureRandom`、`java/util/UUID`

允许集合：Generator 自身 API/internal、Lowering API、`io/kcg/sir/lowering/springboot/model/`、JDK 纯内存集合/字符串工具，以及精确的 `io/kcg/sir/semantic/symbol/SymbolId` 输出 metadata。

扫描必须至少发现当前生产 class 数量大于零，并明确包含 `SpringBootGenerator.class` 与一个内部 Renderer，防止空目录或错误 code source 产生假绿。

### 3. 闸门灵敏度

测试侧定义一个故意调用 `Files` 和 `System` 的最小探针 class。先用同一个 constant-pool reader 扫描该探针，并断言能够报告 `java/nio/file/` 与 `java/lang/System`；随后才扫描生产 class。

探针只存在于 `src/test` / `target/test-classes`，不得被纳入生产扫描或 JAR。它证明闸门不是“永远返回空集合”的假测试。

## 错误处理与证据

- constant-pool reader 遇到非法 magic、截断数据或未知 tag 时立即失败，报告 class 文件和偏移/标签；不得跳过。
- 生产扫描失败时列出每个 class 与对应禁止引用，使用确定顺序，便于另一个 Agent 直接定位。
- code source 不是普通目录、缺少代表性 class 或扫描数量为零时立即失败。
- 若首次生产扫描发现真实违规，先确认不是字符串假阳性；确认后按测试先行协议只修对应 Generator 局部实现。若需要改 Lowered IR、公共 API、模块依赖或 Generator 职责，停止并请求项目负责人裁决。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新架构测试直接证明违规的 `sir-generator-spring-boot/src/main/**` 局部实现
- 与实际结果同步的：
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/roadmap/REMAINING_WORK.md`
  - 本工作单状态与交接记录

不得新增依赖、修改 Generator 公共契约、修改 Parser/Semantic/Lowering/Application/Graph/Change/CLI，也不得触碰受保护路径。

## 实现与测试顺序

1. 新增 `GeneratorProductionBoundaryTest` 的 test-only constant-pool reader 和故意违规探针。
2. 先验证探针能被识别，排除空扫描和无效规则。
3. 运行生产 class 扫描和公开入口反射检查，记录首次真实结果。
4. 若生产边界首次即通过，如实记录“现有行为获得直接证据”，不虚构 RED、不修改生产代码。
5. 若首次失败，只有确认的真实生产违规才进入最小修复；修复后重跑定向测试。
6. 执行 Generator reactor 和全量离线 Reactor；同步测试计数、残余风险和 Q1 完成状态。

由于预期是给既有正确边界补直接证据，本阶段可能没有生产 RED。测试灵敏度由故意违规的 test-only 探针证明；任何生产修改仍必须先有真实失败。

## 验证命令

离线仓库路径按机器选择，两者等价：

- Linux 工作机：`-Dmaven.repo.local=/root/.m2/repository`
- Windows 工作机：`-Dmaven.repo.local=D:\maven-repo`

定向（Linux 工作机）：

```bash
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-generator-spring-boot -am \
  -Dtest=GeneratorProductionBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test
```

完成闸（Linux 工作机）：

```bash
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
git diff --check
git status --short
```

全新机器必须先在线 priming 一次本地仓库，否则完成闸无法执行：

1. 在线 `mvn -B -Dmaven.repo.local=<repo> clean verify`；
2. 用 `GeneratedProjectOfflineCompilationTest` 留下的生成工程目录在线 `mvn -B -Dmaven.repo.local=<repo> compile` 一次（生成工程依赖不在 Reactor 闭包内）；
3. 在线 `mvn -B -Dmaven.repo.local=<repo> -pl kcg-cli -am -DskipTests test`（`kcg-cli` 在默认第一轮不会执行，其依赖需要单独补齐）。

过程与证据见资格文档 1.2。

已知既存失败（本机完成闸的口径，2026-09-18 项目负责人决定）：`sir-toolchain-application` 有 5 项软链接断言失败，该模块不在本工作单允许的修改范围内。这 5 项**不阻塞 Q1**：它们按原样登记在资格文档 1.2 与§6 第 8 项，不计入 Q1 通过条件，也不得被描述为已修复或已通过。

本机全量闸门因此分两条命令，两条都要跑并如实记录：

```bash
# ① 冻结形式：预期在 sir-toolchain-application 因这 5 项而中止
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify

# ② 完成形式：不因失败中止，Reactor 跑到 kcg-cli；BUILD SUCCESS 不代表 5 项已消失
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

2026-09-18 基线：① 止于 `sir-toolchain-application`，5 项失败；② 全部 10 个 Reactor 模块完成，Surefire 合计 **373 run / 5 fail / 0 error / 11 skip**。除这 5 项以外新增的任何 fail/error 都视为 Q1 回归。Q1 在本机的完成闸要求是：定向测试全绿；全量离线 Reactor 跑到最后一模块，除这 5 项既存失败外不得新增任何 fail/error；新增失败一律视为 Q1 回归。

## Q1 完成闸

- Q1A～Q1D 的归档工作单和当前资格文档互相一致。
- 公开 `generate` 输入只为 `SpringBootLoweredModel`，输出为 `GenerationResult`。
- 故意违规的测试探针被闸门稳定识别。
- 全部生产 Generator class 通过禁止依赖扫描；扫描非空且包含入口与 Renderer。
- 不新增依赖、skip、exclude 或生产测试入口。
- Generator 定向 Reactor 全绿。全量离线闸门按验证命令的①②两条执行：① 只在 `sir-toolchain-application` 因已登记的 5 项失败而中止；② 跑到 `kcg-cli`，Surefire 合计 **379 run / 5 fail / 0 error / 11 skip**（2026-09-18 基线 373 + 本工作单新增的 6 项边界测试），其中生成工程 38 项含真实 `--offline` 编译，`kcg-cli` 5 项已实际运行。除这 5 项外新增的任何 fail/error 都算 Q1 回归。
- 完整生成工程离线编译仍在全量 Reactor 中真实执行。
- 运行时、MySQL、HTTP、conformance、Project Graph 直接测试和后续路线不得被表述为 Q1 已完成内容。
- 状态更新为 `AWAITING_ACCEPTANCE`，等待项目负责人确认 Q1 整体通过。（已完成）
- ~~项目负责人确认后，归档本工作单、创建 Q1 本地提交并普通推送 `main` 到 `origin`；推送成功后才建立 Q2 工作单。~~ 2026-09-18 项目负责人确认 Q1 通过，但决定**暂不提交**（初定 G1 完成后统一提交），因此归档时未创建本地提交、未推送 `origin`；Q2 工作单已建立。

## 交接记录

2026-08-11：项目负责人确认 Q1D 通过。Q1D 已归档，本地快照为 `5bba6ea`，`origin/main` 仍为 `55fc610`，没有推送远端。

2026-08-11：项目负责人从三种方案中确认采用“编译后生产字节码边界扫描 + 公开入口反射检查”。本工作单完成设计展开和自检，进入 `SPEC_REVIEW`；方案复核前不修改测试或生产代码。

2026-09-18：项目负责人确认 Q1 规格通过，并要求直接在本工作机（Linux）开新会话执行；已知 5 项软链接失败计入文档但不阻塞 Q1。本工作单由 `SPEC_REVIEW` 改为 `READY`，完成闸重新表述为上述口径。

2026-09-18：项目负责人在新工作机（Linux）要求重建本地 Maven 仓库。已完成 `/root/.m2/repository` 的在线 priming 与离线复跑，证据写入资格文档 1.2。结果：模块 1–8 通过（含完整生成工程 `--offline` 编译），`sir-toolchain-application` 5 项软链接断言失败、11 项跳过，`kcg-cli` 未运行。未修改任何生产代码或测试；本工作单仍为 `SPEC_REVIEW`。

2026-09-18：完成文档权威同步（`AGENTS.md` 把 `docs/design/` 与 `docs/roadmap/README.md` 纳入“后续功能方向主入口”并裁决 G0–G7 与 Q 系列关系；`PROJECT_STATUS`、`REMAINING_WORK`、本工作单补 G0 归属与模块计数口径；`PROJECT_OWNER_GUIDE` 增“阶段总览与派发方式”）。这些文档改动尚未提交。

2026-09-18：Q1 执行完成（本机 Linux、Java 21.0.12、Maven 3.6.3、离线仓库 `/root/.m2/repository`；源码基点 `902a7a5`，工作区只有本工作单新增的三个 test-only 文件与本轮文档改动）。状态改为 `AWAITING_ACCEPTANCE`。

新增文件（全部落在 `sir-generator-spring-boot/src/test/**`，未修改任何生产代码、未新增依赖、skip 或 exclude）：

| 文件 | 作用 |
|---|---|
| `src/test/java/io/kcg/sir/generator/springboot/boundary/ClassFileConstantPoolReader.java` | test-only class-file constant pool reader：解析 `CONSTANT_Utf8` 并把每条项分类为 `CLASS_NAME` / `STRING_LITERAL` / `OTHER`；非法 magic、截断数据、未知 tag 立即抛 `ClassFileFormatException` 并报告 class 文件、偏移和 tag |
| `src/test/java/io/kcg/sir/generator/springboot/ForbiddenReferenceProbe.java` | 故意调用 `Files`、`System`、`IOException` 的 test-only 违规探针（只存在于 `target/test-classes`） |
| `src/test/java/io/kcg/sir/generator/springboot/GeneratorProductionBoundaryTest.java` | 6 项测试：探针灵敏度、reader 错误处理、生产 census 扫描、生产扫描路径灵敏度、公开入口签名、入口表面类型 |

定向命令与结果：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-generator-spring-boot -am -Dtest=GeneratorProductionBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false test` → `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS。

首次运行如实记录：第 1 次运行 5 项中 1 项失败，失败信息为 `production census is missing api/SpringBootGenerator.class`，原因是本测试自己的 census key 常量漏了 `io/kcg/sir/generator/springboot/` 前缀——这是 test-side 缺陷，不是生产 RED；修正该常量后第 2 次运行 5/5 通过，第 3 次加入扫描路径灵敏度测试后 6/6 通过；第 4 次在测试内部重构（常量池单次读取 + `SymbolId` 规则命中断言）后再跑仍为 6/6。**生产边界首次真实执行结果为零违规，没有虚构 RED，也没有修改生产代码。**

生产边界首次真实结果：

- 生产 census 为 37 个 `.class`（22 个生产源文件的编译产物）；测试断言下限 ≥30，并要求必须包含 `api/SpringBootGenerator.class`、`internal/GenerationEngine.class`、`internal/EntityRenderer.class`，全部条目必须位于 `io/kcg/sir/generator/springboot/` 下且不得包含探针类名，排除空目录、错误 code source 和测试类泄漏三种假绿。
- 禁止引用扫描：**0 违规**。生产侧出现的唯一 `io/kcg/sir/semantic/` 引用是精确的 `io/kcg/sir/semantic/symbol/SymbolId`（`GeneratedFile` 的已冻结 ownership metadata）；规则只放行这一精确类型，`SymbolTable` 和同包其他类型、以及 `SymbolId$...` 嵌套类型仍禁止。测试同时断言该 `SymbolId` class 引用确实存在于生产 census 中，证明 Semantic 规则是真的被命中后放行，而不是因为规则从未生效而显空。生产 class 中不出现 `java/nio/file/`、`java/io/`、`java/lang/System`、`java/time/`、随机源或 `java/util/UUID`。
- 公开入口反射：`SpringBootGenerator` 为 `public final`；唯一公开方法为 `generate(SpringBootLoweredModel)`，返回 `GenerationResult`；声明构造器只有一个无参构造器；所有已声明方法/构造器的参数与返回类型都不含 AST、Parser、Semantic 或文件系统类型。
- 闸门灵敏度（同一 reader 与同一规则表）：探针被稳定报出 `java/nio/file/`、`java/lang/System`、`java/io/`，且 `java/nio/file/Files`、`java/lang/System`、`java/io/IOException` 被分类为 `CLASS_NAME`；探针所在 class 文件被断言位于生产目录之外。
- 生产扫描代码路径灵敏度：把探针与既有 `GeneratorTestSupport` 的 class 文件复制成临时 census 后，`classFiles` + `scan` + `report` 报出 `java/nio/file/`、`java/lang/System`、`io/kcg/sir/api/`、`io/kcg/sir/internal/`，报告按 class → rule → pool index 确定性排序，每个违规都有对应报告行。这排除了「`scan` 永远返回空集合」的假绿。
- reader 错误处理：非法 magic、UTF-8 载荷截断、未知 tag 99、constant pool 后缺少 header 四种输入全部以 `ClassFileFormatException` 立即失败，并分别报告 `offset 0` / `offset 13` / `offset 10` / `offset 10`。

全量离线闸门（本机 Linux，两条都执行）：

- ① `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify`：模块 1–8 SUCCESS，`sir-toolchain-application` FAILURE，`kcg-cli` SKIPPED。失败 5 项与资格文档 1.2 完全相同（`PathGuardTest.targetItselfIsSymlinkIsRejected`、`SafeTargetResolverTest.targetSymlinkRejected`、`SafeTargetResolverTest.parentSymlinkOfOutputRootRejected`、`SafeTargetResolverTest.linkDotDotRawChainAttackRejected`、`SafeTargetResolverTest.parentSymlinkInsertedBeforeResolveRejected`），无新增失败。该次 Reactor 内合计（模块 1–9）**374 run / 5 fail / 0 error / 11 skip**。该次未构建 `kcg-cli`，其 `target/surefire-reports` 仍是 priming 运行（09:29:42）留下的旧文件，不计入本次结果。
- ② `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true`：10 个 Reactor 模块全部完成，BUILD SUCCESS，合计 **379 run / 5 fail / 0 error / 11 skip**，失败项与①完全相同，无新增 fail/error/skip。
- 模块分布：`sir-parser` 43、`sir-semantic` 103、`sir-lowering-api` 4、`sir-lowering-spring-boot` 32、`sir-generator-spring-boot` 38（含本工作单新增 6 项）、`sir-project-graph` 0（无直接测试）、`sir-change` 22、`sir-toolchain-application` 132/5/11、`kcg-cli` 5。
- `GeneratedProjectOfflineCompilationTest` 在全量闸门中真实执行：1 run / 0 fail / 5.36 s，完整生成工程 `--offline` 编译仍在闸门内。
- 相比 2026-09-18 基线 373 run，总数 +6，全部来自本工作单新增的边界测试；`git diff --check` 无输出，`git status --short` 只有本工作单新增的 test 文件与本轮文档改动。
- 上述①②在最终状态（测试内部重构与文档更新之后）各再跑一次，结果完全一致：① **374 / 5 / 0 / 11**、② **379 / 5 / 0 / 11**，失败项仍是同样 5 项，`GeneratedProjectOfflineCompilationTest` 该次为 1 run / 0 fail / 4.82 s。第一次①运行时观察到的 `kcg-cli` 旧报表（09:29:42 priming 遗留）只属于那一次。

仍未覆盖、不得由本工作单记为实现的内容：

- 禁止集合按已批准规格只覆盖 `io/kcg/sir/{ast,api,internal,source}`、`io/kcg/sir/semantic/{api,model,symbol}`、`java/nio/file/`、`java/io/`、`java/lang/System`、`java/time/`、`java/util/Random`、`java/util/concurrent/ThreadLocalRandom`、`java/security/SecureRandom` 和 `java/util/UUID`；`io/kcg/sir/semantic/{context,type,internal}` 不在批准集合内，是已知残余缺口。
- 扫描只证明生产 class 的静态引用边界，不证明运行时行为、反射逃逸、生成 Java 文本的正确性或 Lowered IR 自身的正确性；运行时、MySQL、HTTP、conformance、Project Graph 直接测试和后续路线都不属于 Q1 已完成内容。
- `sir-toolchain-application` 的 5 项软链接失败按原样登记，未修复、未跳过、未被本工作单触碰。

2026-09-18：项目负责人确认 Q1 整体通过，同日指示：**Q1 暂不提交 Git**（初定 G1 完成后统一提交），继续推进 Q2 并更新工作单。据此：

- 本工作单状态改为 `DONE` 并移入 `docs/roadmap/completed/`；未创建本地提交、未推送 `origin`（本地 HEAD 仍为 `902a7a5`）。
- Q2（Project Graph 直接模块契约测试）已写入 `docs/roadmap/ACTIVE_WORK.md`，状态 `SPEC_REVIEW`。
- 本工作单允许范围内未触碰的既存问题：`sir-toolchain-application` 的 5 项软链接断言失败仍未修复，按资格文档 1.2 原样登记。
