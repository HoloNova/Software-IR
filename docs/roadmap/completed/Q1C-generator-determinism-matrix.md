# 已完成工作单：Q1C Generator 环境与字节确定性矩阵

- 状态：`ACCEPTED`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 前置工作：[Q1B3B Generator Controller 与 Transport 行为契约](completed/Q1B3B-generator-controller-transport-contract.md) 已完成
- 本工作单性质：测试优先；只修复确定性矩阵直接证明的 Generator 局部缺陷
- 下一张候选工作单：Q1D 完整生成工程冻结依赖下的真实离线编译

## 这次只做什么

为纯内存 Generator 建立跨环境、跨进程的确定性证据，证明同一份 Lowered IR 不因默认 Locale、物理工作目录或平台换行设置而改变输出：

1. 同一 fixture 在至少两个不同物理工作目录的独立 JVM 中生成相同文件集合和 UTF-8 字节；
2. 至少覆盖 `Locale.US` 与 Turkish locale，防止默认 Locale 参与大小写转换；
3. 每个生成内容只使用 LF、无 CR、以单个 LF 结束，并能严格 UTF-8 round-trip；
4. 文件路径、顺序、内容、ownership metadata 和 digest 在矩阵中一致；
5. Java/XML 字符串转义 primitive 对引号、反斜杠、控制字符和 XML 保留字符拥有直接测试；
6. Generator 仍不读取磁盘、当前目录、时间、随机数、默认 Charset 或 AST/SIR。

矩阵测试可以使用 test-only 子进程 probe，但不得给生产 API 增加测试入口，也不得把 Application 写盘职责移入 Generator。

## 开工时怎样加载上下文

任何情况下先读取本文件最新内容并运行 `git status --short`。

- 新对话、新 Agent 或上下文不完整：按 `AGENTS.md` 先读 `.memory/INDEX.md`、`.memory/CORE/PROJECT.md`，再读本工作单材料。
- 同一对话连续推进且权威材料未变化：不机械重读，只补读本轮首次涉及或变化的文件。
- 上下文压缩后：摘要足够则继续；拿不准时按新对话处理。

直接相关材料：

1. `docs/qualification/TEST_COVERAGE_INVENTORY.md` 的 Generator 部分；
2. `docs/superpowers/specs/2026-07-16-sir-v0.1-generator-design.md`；
3. `SpringBootGenerator.java`、`GenerationContext.java`、`ImportSorter.java`；
4. `StringEscape.java`、XML escaping helper 与使用它们的 Renderer；
5. `GeneratedFile.java`、`GenerationResult.java`；
6. `GeneratorTestSupport.java`、`GeneratorOutputContractTest.java` 和现有 fixtures；
7. 已完成的 Q1A、Q1B1、Q1B2、Q1B3A、Q1B3B 工作单。

## 设计选择

采用“独立 JVM probe + 进程内结构断言”的组合：

- 独立 JVM probe 负责真实改变物理工作目录和默认 Locale，并输出 canonical SHA-256；
- 进程内测试负责给出可读的文件级失败信息，包括路径、顺序、内容、metadata、LF 和 UTF-8 round-trip；
- escaping helper 只做直接 primitive 测试，因为当前 SIR 并非所有 Java/XML 转义字符都有可达的生成位置。

不采用只修改 `user.dir` 系统属性的伪工作目录测试；也不通过完整 Maven 子构建来比较输出，避免把 Q1D 的编译职责混入本工作单。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新增失败测试直接证明有缺陷的 Generator 内部纯函数：
  - `GenerationContext.java`
  - `ImportSorter.java`
  - `StringEscape.java`
  - XML escaping helper
  - 直接产生非确定输出的目标 Renderer
- 与实际结果同步的：
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - 本工作单状态与交接记录

若必须修改 Parser、Semantic、Lowering、Application、CLI、POM、公共模型或文件事务层，停止并报告，不得扩大范围。

## 明确不做

- 不把 GeneratedFile 写入真实项目目录。
- 不做生成工程 Maven 编译；留给 Q1D。
- 不新增或升级依赖，不修改 Surefire fork/parallel 配置。
- 不用默认 Charset 读写 probe 协议；必须显式 UTF-8。
- 不以仅重复同一 JVM 调用代替物理 cwd/Locale 矩阵。
- 不修改业务 Renderer 外形、SIR 语义或 Lowered IR。
- 不进入 Project Graph、Change、conformance、事务应用层或 CLI。
- 不修改 Grammar 或 ANTLR 配置。

## 测试优先协议

1. 先新增最小矩阵/escaping 测试并执行，记录首次真实结果。
2. 子进程启动错误、classpath 错误、fixture 缺失或断言错误不是产品 RED，必须先纠正测试基础设施。
3. 只有测试到达 Generator 并证明输出因环境变化或转义错误时，才修改生产代码。
4. 新测试首次即通过时，如实记录为现有行为证据，不虚构 RED。
5. 不减少、禁用或放宽现有测试，不增加 skip/exclude。
6. 遇到 bug、失败或意外行为时，先按系统化调试流程定位。

## 必须建立的契约

### 1. 跨进程环境矩阵

- 使用 `java.home` 下的当前 Java；
- classpath 使用 Surefire 提供的完整 test classpath；
- 至少在两个独立临时目录启动 probe；
- 至少比较 `en-US` 与 `tr-TR`；
- 子进程退出码必须为 0，stderr 为空或仅包含可解释的 JVM 环境信息；
- 比较完整 canonical digest，不只比较文件数量。

### 2. 文件集合与 metadata

- 路径、顺序、内容完全一致；
- `ownerNode`、`ownerSymbol` 和文件数量一致；
- digest 编码必须有明确长度/分隔边界，避免字符串拼接歧义。

### 3. LF 与 UTF-8

- 所有内容不包含 `\r`；
- 所有内容以 `\n` 结束且不以两个空白尾行结束；
- `content.getBytes(UTF_8)` 再解码与原字符串完全相等；
- 含非 ASCII 元数据的 fixture 必须显式 UTF-8 读取；canonical digest 只包含 Generator 实际输出及其 metadata，不把源 SIR 混入输出契约。

### 4. Escaping primitive

- Java string：引号、反斜杠、换行、回车、制表符和低位控制字符；
- XML text：`&`、`<`、`>`，以及 helper 当前承诺的引号处理；
- 输出不得依赖 Locale 或默认 Charset。

## 推荐测试组织

- `GeneratorDeterminismMatrixTest`：进程内结构、LF、UTF-8 和独立 JVM 矩阵；
- `GeneratorDeterminismProbe`：test-only main，读取 classpath fixture 并输出 canonical digest；
- 在 `internal` 测试包中扩充或新增 escaping helper test；
- 优先复用 `campus-market.sir`，因为它包含中文和主要 Renderer；必要时再增加一个只覆盖 escaping 可达面的最小 fixture。

## 验证命令

定向：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test
```

完成门：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

## 完成门

- 独立 JVM 的 cwd/Locale 矩阵真实运行并产生一致 digest。
- 全部生成文件通过路径/顺序/metadata、LF 和 UTF-8 契约。
- Java/XML escaping primitive 有直接测试。
- 每项生产修改都能指向先失败的测试；无需生产修改时如实记录。
- 现有 Q1A～Q1B3B 与架构回归继续通过。
- 新增 skip/exclude 为 0，测试数量不减少。
- 定向 reactor 与全量离线 reactor 通过。
- 资格文档与最新 Surefire XML 一致。
- Q1D 仍明确未完成。
- 状态更新为 `AWAITING_ACCEPTANCE`，详细证据留在本工作单；聊天中无阻断时只请求项目负责人确认继续。

## 交接记录

2026-08-11：项目负责人确认 Q1B3B 通过。Q1B3B 归档后，本工作单以 `READY` 建立；等待 Q1B3B 本地 Git 快照完成后切换为 `IN_PROGRESS`。

2026-08-11：Q1B3B 本地快照已创建为 `bd4c420`，`origin/main` 仍保持 `55fc610`，没有推送远端。Q1C 切换为 `IN_PROGRESS`，开始建立独立 JVM cwd/Locale、LF、UTF-8 与 escaping 契约。

2026-08-11：Q1C 已完成，等待项目负责人验收。

- 新增 `GeneratorDeterminismMatrixTest` 2 项、test-only `GeneratorDeterminismProbe` 和 `StringEscapeTest` 2 项，没有新增 fixture 或生产 API。
- 独立 probe 使用两个真实物理 cwd，并分别以 `en-US` + `ISO-8859-1`、`tr-TR` + `UTF-8` 启动当前 Java；两次输出的长度前缀 canonical SHA-256 完全一致，覆盖文件数量、路径、顺序、内容、artifactId 和可选 SymbolId。
- 所有 `campus-market` 生成文件均无 CR、以单个 LF 结束，并通过显式 UTF-8 round-trip。fixture 自身由现有支持代码显式 UTF-8 读取；源 SIR 不混入输出 digest。
- 首轮定向结果为 4 run、1 failure、0 error、0 skip。矩阵、LF/UTF-8 和 XML escaping 首次通过；Java escaping 对 U+0001 输出了原始控制字符，失败准确指向 `StringEscape.javaString` 的 default 分支。
- 单点生产修复：`StringEscape` 对未专门处理的 ISO control character 输出固定的小写四位 `\\uXXXX`，不使用默认 Locale 或格式化器。定向测试随后为 4 run、0 failure、0 error、0 skip。
- 完整 Generator reactor：31 run、0 failure、0 error、0 skip，BUILD SUCCESS；Parser 43、Semantic 103、Lowering API 4、Spring lowering 32 同时通过。
- 全量 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify`：十模块 BUILD SUCCESS；Surefire XML 为 372 run、0 failure、0 error、10 skip。
- 10 项 skip 仍是 Change fixtures 缺失的 6 项和 Windows `PathGuard` 条件限制的 4 项；没有新增 skip/exclude。
- Q1D 仍未完成；本工作单没有把 GeneratedFile 写入项目目录，也没有编译生成项目。
- `CURRENT_QUALIFICATION.md` 与 `TEST_COVERAGE_INVENTORY.md` 已同步。Q1C 尚未创建本地快照；按小版本规则，等待项目负责人确认后归档并提交，且不推送远端。

2026-08-11：项目负责人确认 Q1C 通过；工作单归档，进入本地快照流程，不推送远端。
