# 当前唯一工作单：Q1 Generator 系统测试总验收

- 状态：`READY`
- 所属阶段：路线图阶段 1 / Generator 系统测试；对应主设计 **G0：现有链路资格收口**，本工作单是 G0 的最后一项闸门
- 方向来源：`docs/roadmap/README.md`、`docs/design/implementation-baseline.md`（阶段门与当次实现校准）
- 前置工作：Q1A、Q1B1、Q1B2、Q1B3A、Q1B3B、Q1C、Q1D 均已完成
- 已确认方案：编译后生产字节码边界扫描 + 公开入口反射检查
- 当前动作：规格已获项目负责人确认（2026-09-18）；在本机（Linux）执行，开工时把状态改为 `IN_PROGRESS`
- 版本规则：Q1 整体通过并再次确认后，归档、创建本地快照并普通推送 `origin`；不改写历史、不强推

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
- Generator 定向 Reactor 全绿。全量离线闸门按验证命令的①②两条执行：① 只在 `sir-toolchain-application` 因已登记的 5 项失败而中止；② 跑到 `kcg-cli`，Surefire 合计 **373 run / 5 fail / 0 error / 11 skip**，其中生成工程 32 项含真实 `--offline` 编译，`kcg-cli` 5 项已实际运行。除这 5 项外新增的任何 fail/error 都算 Q1 回归。
- 完整生成工程离线编译仍在全量 Reactor 中真实执行。
- 运行时、MySQL、HTTP、conformance、Project Graph 直接测试和后续路线不得被表述为 Q1 已完成内容。
- 状态更新为 `AWAITING_ACCEPTANCE`，等待项目负责人确认 Q1 整体通过。
- 项目负责人确认后，归档本工作单、创建 Q1 本地提交并普通推送 `main` 到 `origin`；推送成功后才建立 Q2 工作单。

## 交接记录

2026-08-11：项目负责人确认 Q1D 通过。Q1D 已归档，本地快照为 `5bba6ea`，`origin/main` 仍为 `55fc610`，没有推送远端。

2026-08-11：项目负责人从三种方案中确认采用“编译后生产字节码边界扫描 + 公开入口反射检查”。本工作单完成设计展开和自检，进入 `SPEC_REVIEW`；方案复核前不修改测试或生产代码。

2026-09-18：项目负责人确认 Q1 规格通过，并要求直接在本工作机（Linux）开新会话执行；已知 5 项软链接失败计入文档但不阻塞 Q1。本工作单由 `SPEC_REVIEW` 改为 `READY`，完成闸重新表述为上述口径。

2026-09-18：项目负责人在新工作机（Linux）要求重建本地 Maven 仓库。已完成 `/root/.m2/repository` 的在线 priming 与离线复跑，证据写入资格文档 1.2。结果：模块 1–8 通过（含完整生成工程 `--offline` 编译），`sir-toolchain-application` 5 项软链接断言失败、11 项跳过，`kcg-cli` 未运行。未修改任何生产代码或测试；本工作单仍为 `SPEC_REVIEW`。

2026-09-18：完成文档权威同步（`AGENTS.md` 把 `docs/design/` 与 `docs/roadmap/README.md` 纳入“后续功能方向主入口”并裁决 G0–G7 与 Q 系列关系；`PROJECT_STATUS`、`REMAINING_WORK`、本工作单补 G0 归属与模块计数口径；`PROJECT_OWNER_GUIDE` 增“阶段总览与派发方式”）。这些文档改动尚未提交。
