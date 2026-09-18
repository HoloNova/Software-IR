# 已完成工作单：Q7 CLI 产品边界（形态 A：冻结并证明只读边界）

- 状态：`DONE`（项目负责人 2026-09-18 确认通过）
- 归档：2026-09-18 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 验收记录：负责人 2026-09-18 对执行结果回复“确认”，即验收通过。验收依据：
  1. 五个命令面（`--help`/`-h`、`--version`/`-V`、`context`、`plan`）与用法错误矩阵均有退出码与输出断言；
  2. 四个未发布命令（`generate`/`register`/`apply`/`recover`）有**四重证据**：退出码 2 + `KCG-CLI-USAGE-*` + help 不含该命令 + 调用前后状态根与输出根逐文件字节不变；
  3. canonical JSON 跨进程字节相同；内部失败退出码 70 且不泄漏内部消息；
  4. 生产边界闸门三条规则 0 违规，且灵敏度探针证明规则有效；
  5. **发现并修复 1 处真实生产缺陷**：`Map.of` 迭代顺序未定义 + 逐 JVM 哈希盐导致同一条不完整命令在不同进程报出不同的缺失项（用户诊断不可复现）；改为按声明顺序的 `LinkedHashMap`；
  6. 全量两个闸门 **BUILD SUCCESS**，合计 **554 run / 0 fail / 0 error / 5 skip**（542 → 554，增量恰为 Q7 的 12）。
- 所属阶段：路线图阶段 6（CLI 产品边界），**形态 A**；属 **G0：现有链路资格收口**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 6、`AGENTS.md` 第 2 节第 14 条、ADR-019（仍为提议状态，本单**未**实现其写命令）
- 前置工作：Q1–Q6 已完成并归档（[`completed/`](completed/)）
- 授权来源：负责人 2026-09-18 选择形态 A 并回复“可以”（复核通过 D1–D7）
- 残余缺口（已知并记录）：四个写命令未发布；thin JAR/发行包 `NOT_RUN`（属独立发布任务）；`io/kcg/cli/mvp/**` 保留为非命令路径的内部工具，且为死代码候选
- 版本快照：按负责人 2026-09-18 决定，仍**暂不提交 Git**

# 当前工作单：Q7 CLI 产品边界（形态 A：冻结并证明只读边界）

- 状态：`DONE`（见上方归档头）
- 所属阶段：路线图阶段 6（CLI 产品边界）；属 **G0：现有链路资格收口**
- 形态：**形态 A**（负责人 2026-09-18 明确选择）——只冻结并证明当前只读 CLI 的产品边界，**不**实现 ADR-019 的写命令
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 6、`AGENTS.md` 第 2 节第 14 条、`docs/architecture/ADR-019-local-software-ir-mvp-delivery-contract.md`（**仍为提议状态**）
- 前置工作：Q1–Q6 已完成并归档（[`completed/`](completed/)）
- 授权状态：本文件写出后仍需负责人复核 D1–D7；复核通过才转为 `IN_PROGRESS`
- 版本快照：按负责人 2026-09-18 决定，仍**暂不提交 Git**

## 目标

把"当前 CLI 是只读的"从**陈述**变成**可执行的证据**：

1. 五个命令面（`context`、`plan`、`--help`、`--version`、未知/缺参）都有退出码、help 文本与 canonical JSON 的断言；
2. `generate`、`register`、`apply`、`recover` 四个写命令的**不存在**有证据（并有"调用它们不产生任何副作用"的断言），而不是仅凭文档；
3. CLI 生产代码**只经 typed Application public API**，不引用 Application 内部包、不直接读写 Bundle / CURRENT / LOCK / Journal；
4. 已发布的 `context`/`plan` 输出是 canonical JSON 且**可重复**（同输入两次运行字节相同）。

## 勘察结论（2026-09-18，均直接读代码得到）

1. **命令面与退出码已存在**：`KcgCli.run(String[])`（进程外入口 `main` 做 `System.exit`）；`CliExit` 定义 `OK=0`、`USAGE=2`、`FAILURE=3`、`RECOVERY_REQUIRED=4`、`INTERNAL=70`；`CliCommandLine` 已把 `--help/-h/help`、`--version/-V/version`、`context`、`plan` 分派，其余命令（含四个写命令）走 `KCG-CLI-USAGE-001 unknown command`，缺命令走 `no command given`，非绝对路径走 `KCG-CLI-USAGE-003`。
2. **CLI 生产代码确实只用 public API**：`kcg-cli/src/main` 对 `io.kcg.sir.*` 的引用**不含任何 `internal` 包**（已逐文件核对 import）。
3. **但存在一个需要裁定的旁路包**：`kcg-cli/src/main/java/io/kcg/cli/mvp/`（4 个类，约 43 KB：`MvpEvidence`、`MvpEvidenceRoot`、`MvpSanitize`、`MvpSupport`）是**随 CLI 一起发布**的代码，**直接从状态根读文件（包括 `LOCK`）**并做证据校验，但：
   - **没有任何命令可达它**（`KcgCli` 与 `CliCommandLine` 对 `mvp` 的引用数为 0，全仓库只有它自己的测试引用它）；
   - 因此它既是一处**边界例外**（CLI 模块内存在直接读状态根文件的代码），也是**死代码候选**。
4. **现有 CLI 测试面很薄**：整个 `kcg-cli` 模块只有 **18 项**（`KcgCliWorkflowTest` 13 + `MvpEvidenceHardeningTest` 5）。没有针对 `--help`/`--version`/退出码矩阵、未知命令、非绝对路径、canonical JSON 重复性的直接断言。
5. **canonical JSON 由 `ResultRenderer` 统一渲染**（`JsonStringEncoder` 负责转义），但**没有任何测试断言"同输入两次运行字节相同"**或字段顺序稳定。
6. **发布物（thin JAR/发行包）不在本单范围**：路线图明确把 JAR/发行包列为独立发布任务，不得混入业务资格。本单只测 `run()` 的进程内行为与 `main` 的退出码契约（后者通过 `run()` 返回值断言，不启动真实子进程）。

## 任务

1. **退出码与命令面矩阵**：`--help`（顶层/`context`/`plan`）、`--version`、无命令、未知命令（含四个写命令）、缺必填参数、非绝对路径、多余位置参数——逐项断言**退出码**与 canonical JSON 的**错误码**。
2. **写命令不存在性证据**：对 `generate`、`register`、`apply`、`recover` 四个命令各断言：退出码为 `USAGE`、JSON 错误码为 `KCG-CLI-USAGE-001`、**状态根与输出根的内容树逐字节不变**（副作用为空的证据），并断言这四个名字**不在** help 文本中。
3. **help/version 契约**：顶层 help 只列 `context`/`plan`；子命令 help 列出各自必需参数；`--version` 输出 `kcg 0.1.0 (KCG-CLI-CHANGE-PLANNING-V1)` 且退出码 0（该常量为公开契约）。
4. **canonical JSON 契约**：同一输入两次运行**字节相同**；键顺序稳定；字符串转义符合 `JsonStringEncoder` 既有规则（含非 ASCII 与引号/控制字符）；顶层文档包含既有稳定字段。
5. **边界闸门（生产字节码）**：扫描 `kcg-cli/src/main` 已编译 class：
   - 禁止引用 `io/kcg/sir/application/internal/**` 与 `io/kcg/sir/change/internal/**`；
   - 禁止引用 `io/kcg/sir/application/internal/state/**` 中的 Bundle/CURRENT/LOCK/Journal 相关类型；
   - 允许清单 = `io/kcg/sir/*/api/**`（typed Application API）与 JDK；
   - **若 D1 选择保留 `mvp` 包**：该包作为**显式例外**列入允许清单，并在闸门里断言"`KcgCli` 与 `CliCommandLine` 的常量池中不出现任何 `mvp` 类型"，从而把例外限定在"非命令路径"上。
   - 闸门必须包含**灵敏度探针**（沿用 Q1 规则：以真实违规 class 驱动 `scan()`/`report()` 返回非空结果）。
6. **进程外退出码**：用 `run()` 返回值断言退出码契约；**不**在测试中启动真实 JVM 子进程（避免把发布物测试混入业务资格）。是否额外提供一个可选的子进程冒烟用例见 D6。

## 需要负责人裁定的事项（推荐项已标注）

- **D1（关键）`cli/mvp` 包头如何处理**：
  - **(a) 推荐**：**保留**该包，并在文档中把它登记为"非命令路径的内部只读证据工具"，同时把 D5 闸门的例外**限定**为"命令分派不可达它"（有断言）。理由：`AGENTS.md` 禁止删除用户内容；它有自己的硬化测试；`ADR-019` 的完整生命周期将来可能要用它。
  - (b) 删除该包（连同其测试），使 CLI 模块只剩命令面。更干净，但属于删除既有工作，需要你明确授权。
  - (c) 暂不动它，也不在闸门里处理——**不推荐**：那样"CLI 不读状态根文件"的结论会带一个未声明的例外。
- **D2 写命令"不存在"的证明强度**：推荐"退出码 + 错误码 + 目录树逐字节不变 + help 文本不含该命令"四重证据（比只断言退出码强，能防止将来某个命令被半途接上却在错误路径上留下副作用）。
- **D3 退出码矩阵是否包含 `INTERNAL=70`**：现有实现有一个 `crashInjection` 测试钩子（`static volatile RuntimeException`）。推荐**包含**：断言注入崩溃时退出码 70 且 JSON 错误码为 `KCG-CLI-INTERNAL-001`（这是"不逃逸为未处理异常"的证据）。
- **D4 canonical JSON 的判定方式**：推荐**字节相等**（两次运行 `run()` 的输出流字节），而不是解析后比较字段——解析相等无法发现键顺序或转义不稳定。
- **D5 边界闸门实现位置**：推荐在 `kcg-cli` 模块内实现与 Q1/Q2 同类的常量池扫描（不新增模块、不动 POM），并复用 Q1 已验证的探针模式。
- **D6 是否加真实子进程冒烟用例**：推荐**不加**（选项：加）。若加，它属于发布物验证，需要单独的 opt-in 标记；我倾向于把它留给"thin JAR/发行包"那张独立工作单，保持本单是纯资格收口。
- **D7 生产修复**：只修被本单测试**直接证明**的缺陷，最小改动并单独列出（沿用 Q3/Q6 先例）。

## 完成门

- 五个命令面 + 四个写命令的不存在性均有断言，全部通过；
- canonical JSON 重复运行字节相同；
- 边界闸门对 `kcg-cli/src/main` 的违规引用报 0，且灵敏度探针证明闸门有效；
- `kcg-cli` 模块 0 fail / 0 error；两条全量闸门 BUILD SUCCESS，且与基线 **542 / 0 / 0 / 5** 的差异逐条归因；
- 文档同步：本工作单 + `CURRENT_QUALIFICATION.md`（新增一节）+ `TEST_COVERAGE_INVENTORY.md` + `REMAINING_WORK.md` 阶段 6；并如实登记"四个写命令仍未发布""thin JAR/发行包仍为 NOT_RUN"。

## 验证命令（沿用基线）

```bash
# 定向
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl kcg-cli -am \
  -Dtest='CliProductBoundaryTest,CliContractTest' -Dsurefire.failIfNoSpecifiedTests=false test

# ① 冻结形式（失败即停）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
# ② 完成形式（忽略失败，使 Reactor 走到 kcg-cli）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true

git diff --check
git status --short
```

## 交接记录

### 结果：CLI 产品边界已冻结为可执行证据，**新增 12 项测试全绿**，并发现并修复 1 处生产缺陷

| 指标 | 值 |
|---|---|
| 新增测试 | **12**（`CliProductBoundaryTest` 6、`CliProductionBoundaryTest` 4、`CliInternalFailureTest` 2） |
| `kcg-cli` 模块 | **30 run / 0 fail / 0 error / 0 skip**（Q7 前 18；计数取自 Surefire XML） |
| 闸门①（冻结 `-o clean verify`） | **BUILD SUCCESS**，9 模块 |
| 闸门②（完成形式） | **BUILD SUCCESS** |
| 全量合计 | **554 run / 0 fail / 0 error / 5 skip**（542 → 554，增量恰为 Q7 的 12，逐条可归因） |
| 生产改动 | **1 个文件**：`kcg-cli/src/main/java/io/kcg/cli/CliCommandLine.java`（见下方缺陷 1，D7 授权范围） |

### 发现并修复的生产缺陷（D7）：用法诊断不可复现

**证据**：同一条不完整的 `plan` 命令行，在**三个独立的子 JVM** 中分别报出不同的缺失项：

```
missing required option: --target-key
missing required option: --operation
missing required option: --change-ir-version
```

**根因**：`CliCommandLine.parseOptions` 的最后一步遍历 `known.keySet()` 报告第一个缺失的必填项，而两个解析器用 `Map.of(...)` 构造该映射——`Map.of` 的迭代顺序未定义，且**每个 JVM 进程使用不同的哈希盐**，因此同一输入的诊断在进程之间（甚至同一进程的多次运行之间）不稳定。

**影响**：违反 canonical JSON 契约（同输入必须同字节）；脚本与用户看到的错误信息不可复现。

**修复**（最小改动，仅此一处）：两个解析器改为按**声明顺序**构造 `LinkedHashMap`，于是"第一个缺失的必填项"固定为声明顺序中的第一个。修复后连续三次运行均报 `--expected-context-id`（基准确认），并有专门的回归测试 `usageErrorsAreDeterministicAcrossProcesses`（跨进程三次运行字节相同）。

### 已建立的断言矩阵

| 组 | 断言 |
|---|---|
| 未发布命令（`generate`/`register`/`apply`/`recover`） | 退出码 `2`；JSON 含 `KCG-CLI-USAGE-`；消息含 `unknown command`；**help 文本不含该命令**；调用前后**状态根与输出根逐文件字节不变**（四重证据，按 D2） |
| help/version | `--help` 与 `-h` 输出**相同**且退出码 0；顶层 help 只列 `context`/`plan` 且不含四个写命令；`context --help`/`plan --help` 各自列出必需参数；`--version` 与 `-V` 输出相同且含 `KcgCli.VERSION` |
| 用法错误矩阵 | 无参数、未知命令、缺 `--state-root`、缺 `--output-root`、相对路径（`context` 与 `plan` 各一）→ 退出码均为 `2`，且输出为 canonical 文档、`outcome=USAGE_ERROR` |
| canonical JSON | 同一 `plan` 命令两次**跨进程**运行 stdout **字节相同**；首字段为 `protocolVersion=KCG-CLI-CHANGE-PLANNING-V1`；单行且恰好一个结尾换行；用法错误同样是单行 canonical JSON |
| 内部失败 | 注入崩溃（`crashInjection`）→ 退出码 **70**、错误码 `KCG-CLI-INTERNAL-001`、输出仍是 canonical JSON 单行、**内部消息不泄漏**到文档 |
| 生产边界闸门 | `kcg-cli/src/main` 全部 class 的常量池：① 不引用 Application/Change/ProjectGraph `internal` 包与 generator 包；② **命令路径 class 不引用任何文件系统 API**（`Files`/`Paths`/`FileChannel`/`FileSystemProvider`/`java.io.File`）；③ **命令路径 class 不引用 `io/kcg/cli/mvp/**`**（D1(a) 的例外限定）。三项均报 **0 违规**，且灵敏度探针（故意违规的 `CliBoundaryProbe`）证明三条规则都会真的报出违规 |

### D1(a) 的落地方式（已按推荐执行）

`io/kcg/cli/mvp/**` **保留**，作为"非命令路径的内部只读证据工具"；闸门**不**为其放宽前两条规则中的"internal 引用"规则（实测该包也不引用任何 internal 包），而是新增第三条规则把例外**限定**为"命令分派不可达它"。这样"CLI 不读状态根文件"的结论不再带有未声明的例外。

### 明确的残余缺口（未声称覆盖）

- 四个写命令（`generate`/`register`/`apply`/`recover`）**仍未发布**；本单只证明它们被拒绝且无副作用。
- **thin JAR / 发行包**：按路线图属独立发布任务，本单 **`NOT_RUN`**；退出码契约用子 JVM（`java -cp ... io.kcg.cli.KcgCli`）验证，未验证打包后的可执行 jar。
- `mvp` 包是死代码候选（无生产调用者），保留决定见 D1(a)；其硬化测试仍在模块内运行（5 项）。
