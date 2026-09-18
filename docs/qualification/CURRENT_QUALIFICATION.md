# KCG-Code 当前资格报告

- 资格日期：2026-08-11（Windows）；2026-09-18 在 Linux 新机器以全新本地仓库复跑，见 1.2
- 运行环境：Windows 11 + 离线仓库 `D:\maven-repo`（§2、§3、§5 的 Windows 记录）；Linux + Java 21.0.12 + Maven 3.6.3 + `/root/.m2/repository`（§1.2）
- 总体结论：Windows 离线 Reactor 于 2026-08-11 通过；Linux 新仓库下模块 1–8 通过、模块 9 有 5 项真实失败、模块 10 未运行；外部 conformance 与完整本地 MVP 未完成

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
| `kcg-cli` | 0 | 0 | 0 | 0 |
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

分类：5 项的“拒绝软链接”行为都真实发生（确实抛出 `UnsafePathException`，或在 preflight 产生 ERROR 诊断），失败点只在异常消息文本（4 项）和诊断码归属（1 项）。这 5 项此前在 Windows 上从未真正执行：`SafeTargetResolverTest.tryCreateSymlink` 在创建失败时返回 `false`，调用方直接 `return`，测试被记为**通过**而不是跳过；`PathGuardTest.targetItselfIsSymlinkIsRejected` 标注 `@EnabledOnOs({LINUX, MAC})`，在 Windows 上不运行。因此 2026-08-11 的 `0 failed` 不能证明软链接拒绝路径可用。

11 项跳过（Linux）：`ChangePlanningApplicationTest` 6 项（fixtures 缺失）、`PathSecurityReviewTest` 5 项（junction 是 Windows 专属概念）。

完成形式复跑（同日、同仓库）：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true`。全部 10 个 Reactor 模块完成，`kcg-cli` 5 项已实际执行，Surefire 合计 **373 run / 5 fail / 0 error / 11 skip**，5 项失败与上表完全相同。该次 `BUILD SUCCESS` 来自 `maven.test.failure.ignore`，**不代表这 5 项失败已消失**，只能在如实登记它们的前提下引用。

结论：

- 冻结命令在 Linux 全新仓库下**不能**判定为通过：模块 1–8 通过，模块 9 有 5 项真实失败，模块 10 未运行。
- 1.1 记录的 2026-09-08 `sir-parser:testCompile` 失败在本机在线与离线两次运行中都没有复现（Parser 43 项两次均通过）；那次环境未被复现，1.1 只能算部分收敛，不能宣布已裁决。

## 2. 模块测试统计

以下为 2026-08-11 Windows 运行的记录；2026-09-18 Linux 运行见 1.2。

统计以 Surefire XML 为准；JUnit `@Nested` 容器和类级 assumption 的展示差异不改变实际 testcase 统计。

| 模块 | run | fail | error | skip | 当前解释 |
|---|---:|---:|---:|---:|---|
| `sir-parser` | 43 | 0 | 0 | 0 | Parser 基础契约 |
| `sir-semantic` | 103 | 0 | 0 | 0 | 含 typed reference-site 契约 |
| `sir-lowering-api` | 4 | 0 | 0 | 0 | API 契约 |
| `sir-lowering-spring-boot` | 32 | 0 | 0 | 0 | 含 19 项 `@Nested` hardening 测试 |
| `sir-generator-spring-boot` | 32 | 0 | 0 | 0 | 已覆盖 canonical 输出、主要 Renderer、跨环境字节确定性，并由当前 Generator 输出物化完整工程后以冻结依赖真实离线编译 |
| `sir-project-graph` | 0 | 0 | 0 | 0 | 无直接模块测试 |
| `sir-change` | 22 | 0 | 0 | 0 | API/架构/fixture 支撑测试，覆盖不足 |
| `sir-toolchain-application` | 132 | 0 | 0 | 10 | 不含被 POM 排除的 conformance 包 |
| `kcg-cli` | 5 | 0 | 0 | 0 | hardening 测试；工作流类被类级 assumption 跳过 |
| **合计** | **373** | **0** | **0** | **10** | 默认构建无失败、无错误 |

## 3. 跳过与排除

### 计入 Surefire 的 10 项 skip

- `ChangePlanningApplicationTest` 6 项：缺少当前 Change base/candidate SIR fixtures。
- `PathGuardTest` 4 项：Windows 条件下不适用的非 Windows 路径语义。

### 未计入 testcase 数的类级 skip

- `KcgCliWorkflowTest` 13 个测试方法因同一批 Change fixtures 缺失而在类初始化阶段 assumption skip。

### POM 硬排除

`sir-toolchain-application` 当前同时从 testCompile 和 Surefire 排除：

```text
io/kcg/sir/application/conformance/**
```

目录中有 46 个 Java 文件，但多个 suite/fixture 类包含不完整实现或占位尾部。默认构建成功不能证明这些文件可编译或 conformance 可运行。

## 4. CLI 资格

当前 CLI 版本：`KCG-CLI-CHANGE-PLANNING-V1`。

- `--help`、`--version`：可运行。
- `context`、`plan`：当前正式命令。
- `generate`、`register`、`apply`、`recover`：当前返回 unknown command，不是已发布 CLI 能力。
- 当前 JAR 是 thin JAR；直接 `java -jar` 不作为受支持运行方式。
- 已验证入口是 Maven exec。

## 5. 外部资格

| 资格项 | 当前状态 | 原因 |
|---|---|---|
| 默认离线 Reactor | PARTIAL | 2026-08-11 Windows：PASS。2026-09-18 Linux 新仓库：模块 1–8 通过；`sir-toolchain-application` 5 项既存软链接失败；加 `-Dmaven.test.failure.ignore=true` 后 Reactor 跑完、`kcg-cli` 5 项通过，合计 373/5/0/11（见 1.2） |
| 完整生成工程离线编译 | PASS | `campus-market.sir` 的当前 Generator 输出在临时目录以 Java 21、Maven `--offline` 和 `D:\maven-repo` 完成 `compile`，代表性 class 文件存在 |
| Windows 路径与 junction 定向测试 | PASS/PARTIAL | 当前测试通过；第二卷挂载点未验证 |
| 外部 MySQL conformance | NOT_RUN / BLOCKED | conformance 包被 POM 排除且当前源码闭包不完整 |
| 完整 CLI 本地生命周期 | NOT_RUN | 四个写命令未发布，fixtures 和外部资格也未闭合 |
| 生产就绪 | NOT_CLAIMED | 无生产、安全、性能、HA 或全平台资格 |

历史环境中出现过的 `QUALIFIED` 或 `MVP_FEASIBLE` 只属于对应的日期化证据，不自动转移为当前版本结论。

## 6. 主要残余风险

1. conformance 包没有参加测试编译和运行。
2. Generator 已有 32 项直接测试并通过完整生成工程离线编译；“生产模块只能消费 Lowered IR 且不访问磁盘”的边界仍需要在 Q1 总验收中核对是否有足够的可执行约束。
3. Project Graph 没有直接模块测试。
4. Change fixtures 缺失造成 Application 和 CLI 测试跳过。
5. UPDATE/CREATE/DELETE 的完整跨阶段故障注入矩阵尚未形成。
6. CLI 完整生命周期和发行形态尚未决定。
7. 2026-09-08 的设计校准运行记录标准 `clean verify` 在 `sir-parser:testCompile` 失败，与 2026-08-11 的 `BUILD SUCCESS` 冲突；2026-09-18 的 Linux 复跑未复现该失败，冲突部分收敛但未裁决（见第 1.1、1.2 节）。
8. 软链接拒绝路径长期缺少真实执行证据：`SafeTargetResolverTest.tryCreateSymlink` 在创建失败时静默 `return`（记为通过而非跳过），`PathGuardTest.targetItselfIsSymlinkIsRejected` 仅 LINUX/MAC 启用。2026-09-18 Linux 复跑首次真实执行并暴露 5 项失败（见 1.2），需要独立工作单裁决异常消息文本与诊断码归属。

## 7. 结论使用规则

- 可以说：2026-08-11 Windows 离线 Reactor 通过；2026-09-18 Linux 离线 Reactor 模块 1–8 通过、模块 9 有 5 项失败、模块 10 未运行。
- 不可以说：当前离线 Reactor 全部通过、所有测试通过、conformance 通过、完整 MVP 通过或生产就绪。
- 新运行完成后直接更新本文件的日期、命令、模块统计、skip/exclude 和外部资格状态；不要叠加多个相互竞争的“当前报告”。
