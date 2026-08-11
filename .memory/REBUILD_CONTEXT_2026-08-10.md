---
created: 2026-08-10
updated: 2026-08-11
importance: critical
confidence: confirmed_and_qualified
source: codex_history_and_local_artifacts
status: active
---

# KCG-Code 灾后重建总上下文

> 这是磁盘损坏后继续工作的首要入口。先读本文，再读 `AGENTS.md`、当前代码、当前测试和与任务直接相关的 ADR。历史 `.memory` 文件可能停留在更早阶段；发生冲突时，以当前可编译代码和当前测试为准，其次是本文明确标注的较新历史证据。

## 1. 灾后事实与证据边界

- 2026-08-10 检查时，`C:\Users\zdw00\Desktop\Software IR` 完全为空，连 `.git` 都不存在；当前目录无法通过 Git、reflog 或 object database 自助恢复。
- Codex 本地任务日志保留了约 235 MB 的项目相关历史，包含源码读取、补丁、命令输出、测试结果、分支和提交元数据。
- `D:\maven-repo\io\kcg` 保留了 9 个模块的本地 POM/JAR。JAR 时间主要为 2026-08-04，`kcg-cli` JAR 为 2026-07-30。
- VS Code/Cursor 的 History 与 Backup 目录存在但均为空，不能作为恢复源。
- 历史元数据记录过远端 `https://github.com/HoloNova/Software-IR.git`，但用户确认没有把本地提交推到云端；远端地址不是已确认备份。
- 本次没有初始化 Git、提交、推送、重写历史或访问 U 盘。

### 当前恢复来源

- 从 Codex 历史按时间重放了 101 个完整补丁，随后拆分为单文件操作，又成功应用 88 个新增/更新/删除操作。
- 从本地 Maven JAR 反编译并仅补入缺失文件，共补入 227 个主源码：`kcg-cli` 10、`sir-change` 29、`sir-generator-spring-boot` 21、`sir-lowering-spring-boot` 15、`sir-project-graph` 30、`sir-semantic` 41、`sir-toolchain-application` 81。
- `sir-parser` 的 ANTLR 生成类没有写入 `src/main/java`，继续由 Maven 插件从 `Sir.g4` 生成。
- 模块 POM 与父 POM来自本地 Maven 安装缓存；父 POM恢复了全部 9 个模块。
- 日志精确恢复的源码优先保留；若本地 JAR 字节码签名证明它属于更早版本，则以同批 JAR 的较新模型补齐并用编译验证。反编译源码可用于重建行为，但不等于原始格式、注释或全部泛型写法；必须以字节码、历史补丁、编译和测试交叉校正。

## 2. 项目身份

KCG-Code 是面向 Coding Agent 的 Software IR 编译、验证、确定性生成和安全工程变更工具链。它不是普通 CRUD DSL，也不是“让模型直接写 Java”。模型或 SLM 负责把需求整理为可检查的 SIR；确定性程序负责语法、名称、类型、约束、Target Lowering、代码生成、工程写盘、变更计划、事务与恢复。

长期系统概念：

```text
需求/项目上下文
  -> SLM 或 Agent 生成/修改 Software IR
  -> Parser / Semantic / Constraint
  -> Normalized Semantic Model
  -> Project Symbol Graph
  -> Target Profile + Target Lowering
  -> 独立 Lowered IR
  -> Deterministic Generator
  -> Application-owned 工程应用、Baseline、Apply、Recovery
```

非协商原则：

- Core IR 只表达目标无关的软件语义；Spring、MyBatis-Plus、Redis、前端和部署细节不得污染 Core。
- 名称只在 Resolve 阶段解析一次；后续阶段消费稳定绑定，不再按字符串猜测。
- `SourceSpan -> AstNodeId -> SymbolId` 是源码位置、结构身份和语义身份的追踪链。
- Lowered IR 拥有生成决策权；Generator 只能纯渲染 Lowered IR，不读 AST 或重新解释业务语义。
- Generator 不写磁盘；文件系统、冲突、事务、Baseline、Journal、Recovery 归 Application 层所有。
- 失败必须结构化并 fail closed；不能用吞异常、模糊匹配或隐式 fallback 伪造成功。
- 不使用任意 Java 源码作为常规逃生口。

## 3. 模块与职责

父 Reactor 的模块顺序：

1. `sir-parser`：ANTLR4 Grammar、严格 UTF-8、不可变 AST、`SourceSpan`、稳定 `AstNodeId`、解析诊断。
2. `sir-semantic`：`ResolvePass -> TypePass -> ValidatePass -> NormalizePass`，产生 `NormalizedSemanticModel` 或结构化 Failure。
3. `sir-lowering-api`：Target 无关的 Lowering 契约、诊断、`LoweredNodeId`、`LoweredOrigin`、Validator。
4. `sir-lowering-spring-boot`：固定 Java 21 + Spring Boot + MyBatis-Plus + MySQL + Maven + REST Target，产生 `SpringBootLoweredModel`。
5. `sir-generator-spring-boot`：纯函数式、确定性地生成内存 `GeneratedFile` 集合。
6. `sir-project-graph`：只读、不可变、确定性的 Project Symbol Graph，连接 semantic、lowered artifact、generated/applied file provenance。
7. `sir-change`：Change SIR 解析/分析与变更语义；历史上已演进到 v0.6。
8. `sir-toolchain-application`：统一编排编译、生成、Graph、Baseline、计划、Apply、Journal、Recovery 与文件事务。
9. `kcg-cli`：严格参数和 canonical JSON 的本地适配器；不能成为第二个状态权威。

## 4. 已实现历史与阶段演进

以下是丢失前的历史状态，不等于灾后当前代码已重新验收：

- 2026-07-14：Parser + Semantic 的历史离线基线为 119 tests。
- 2026-07-17：Parser、Semantic、Lowering、Generator、Application 的历史离线基线为 283 tests。
- 2026-07-18：加入 PSG 与 Application Graph 集成后，历史基线记录为 392 tests；PSG v0.1 为只读追踪图。
- 随后完成 Change SIR v0.1-v0.6，以及 UPDATE、CREATE、DELETE Apply 与显式 Recovery。ADR-014/015/016 定义 Apply 基线、CREATE 和 DELETE 合同。
- Stage E 曾在声明的本地 MySQL reference tuple 上完成两次 fresh qualification，历史结果为 `QUALIFIED`。该结果只适用于记录的环境，不是生产、全平台或未来环境认证。
- Stage F 的只读 `kcg context` / `kcg plan` 已存在；后来又设计了 ADR-019 的本地 MVP：`generate -> register -> context/plan -> apply -> recover`。
- 历史本地 MVP 流程曾得到 `MVP_FEASIBLE` 记录；但丢失前最后一次独立复核无法重新完成 fresh full-Reactor qualification，因为 `sir-parser` 在 `testCompile` 报 `io.kcg.sir.api` 等类型不可见。故当前只能说“历史流程跑通过，最新 fresh qualification 未完成”。

## 5. Apply、DELETE 与 Recovery 的关键不变量

- `context` 和 `plan` 是只读证据，不是写入授权；不能创建或修补 Bundle、`CURRENT`、Journal 或输出工程。
- `apply` 每次都必须重新读取并证明 candidate bytes、context、baseline、输出状态和物理文件身份；旧 plan 不能直接执行。
- CLI 只能把严格参数转成 typed Application request，并渲染 canonical JSON；CLI 不得直接读写 Bundle、`CURRENT`、LOCK 或 Journal。
- Registration 必须由 Application 从精确 base SIR 建立权威 B0，调用方不能自行拼 Snapshot/Bundle 内部文件。
- Candidate Apply 必须绑定 CLI 实际检查的精确字节摘要，关闭检查后替换文件的 TOCTOU 窗口。
- Recovery 始终显式触发；禁止隐式、自动或猜测方向的恢复。
- `CURRENT=B0` 只允许向前恢复；`CURRENT=B1` 只允许向后验证/清理。方向不能由“看起来最像”决定。
- DELETE 使用同卷 hard-link backup：`Files.createLink(backup, target)`；禁止 copy/move/replace fallback。
- 每次 `Files.isSameFile(target, backup)` 前，都必须用 `NOFOLLOW_LINKS` 重新读取两端属性并证明为 regular file。
- 只有直接 `NoSuchFileException` 能证明目标缺失；权限、I/O、symlink 或类型异常都必须 fail closed。
- Application 写盘使用绝对归一化根、路径包含关系、Windows 保留名/非法字符、大小写重复与符号链接链防护；提交时重新检查，不只相信 preflight。
- 默认冲突策略为 `FAIL_IF_EXISTS`；显式 `REPLACE_EXISTING` 只替换普通文件。
- 新根采用同卷 staging + 原子发布；现有根逐文件备份/发布并逆序补偿。不能完整补偿时返回 `RECOVERY_REQUIRED` 并保留权威恢复材料。

## 6. CLI 与本地运行证据

历史命令集合：`context`、`plan`、`generate`、`register`、`apply`、`recover`。

已验证的 CLI 运行方式是 Maven exec，例如：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl kcg-cli exec:java "-Dexec.mainClass=io.kcg.cli.KcgCli" "-Dexec.args=--help"
```

历史上 `generate`、`register`、`context` 曾在本地运行；`generate` 产生 11 个文件，`register` 建立 B0 Bundle/`CURRENT`/LOCK，`context` 返回 `CONTEXT_READY`、contextId 和 target catalog。

不要直接运行当前 thin JAR：

```text
java -jar kcg-cli/target/kcg-cli-0.1.0-SNAPSHOT.jar
```

历史失败为 `NoClassDefFoundError: io/kcg/sir/source/SourceId`，说明发布包缺少依赖，不是业务逻辑失败。除非另行设计并验证 fat JAR/发行包，继续使用 Maven exec。

## 7. 版本化协议与文档线索

已知关键 ADR/协议：

- ADR-001：Resolve once，按 node id 固化绑定。
- ADR-002：Lowered IR 拥有生成决策权。
- ADR-003：Application 拥有工程应用和文件事务。
- ADR-004：Project Symbol Graph v0.1。
- ADR-014：Change execution baseline contract。
- ADR-015：minimal CREATE apply contract。
- ADR-016：minimal DELETE apply contract。
- ADR-017：Stage E reference-environment qualification/evidence 边界。
- ADR-018：只读 Change Planning CLI V1（`context` / `plan`）。
- ADR-019：Local Software IR MVP Delivery Contract。

灾后当前 `docs/architecture` 并未恢复全部 ADR 文件。缺文件不表示决策不存在；应优先从 Codex 历史任务记录重建，再以当前代码和测试核对，不能凭标题补写细节。

已知持久协议边界：Parser/Semantic/Lowering/Generator/PSG V0_1、GraphVersion V0_1、Snapshot V1、Bundle V1、Journal V1/V2/V3、Change SIR v0.1-v0.6。修改这些版本、`CURRENT`、LOCK、Recovery 方向或 ownership model 必须重新做架构评审。

## 8. 灾后当前状态（2026-08-11 更新）

- 9 个 Maven 模块目录和 10 个 POM 已恢复；主源码由“日志精确恢复”和“JAR 反编译补缺”混合组成。
- 灾后测试资产恢复已推进至 RQ-11（状态明细见 `docs/recovery/TEST_RECOVERY_INVENTORY.md` §8 状态表）：
  - **已恢复且已验证**：sir-semantic 103/103、sir-lowering 36/36、sir-change 22/22（12/15 类无内容证据未验证）、sir-toolchain-application 非 conformance 101 过 + 10 skip（6 资源缺失 + 4 Windows）、PathSecurityReviewTest 12/12、RecoveryStateMachineTest 9/9、kcg-cli MvpEvidenceHardening 5/5。
  - **部分完成**：conformance harness 约 40 文件恢复，2 缺口（ConformanceSuite 403 行 / SpringBootTargetConformanceIT 550 行截断）被 POM testExcludes 绕过，未修复。
  - **明确未验证/缺失**：Change 时代 SIR 资源 20+ 个（会话仅 ~134 字符预览）；RQ-03（generator 47）、RQ-04（PSG 94）未排期；DELETE 端到端发布路径、V2/V1 RecoveryEngine、卷挂载点 isOther 仅编译证据。
- 全 reactor `mvn test` 仍不能完整通过：conformance 缺口未闭合前不能宣称 verify、QUALIFIED 或 MVP_FEASIBLE。
- 无 `.git`；不执行 Git 写操作。

### 历史 Git 元数据

历史分支：`feat/sir-semantic-v0.1`。

Codex 会话元数据记录过这些提交快照：

```text
4981ce4fb3efdca7e2ef0a6bb07cd328b6520583
a5f6b08123bb6fa2d9b96c6d5bba62d4d3aeaae3
420cfa17c7be40e39eaa5cfb0e4d1c7b2e0ba095
4bcd9a4ae034da3b66876fc89efc54cc1b14593f
7f72bca313ecd7d8add81032f18934ce4b5e390e
d1e70a6f0a409801ab5effaad9d4e126ad65a650
80e38a45f824425451a93f180ea3d491b440b527
25941b3a812492cb2de09021f475bbcdd925340d
49d63b152153c895391e1255af8906e2be624cab
4454be47fdf962b0d13b119fe37746a8d6658ee3
0127cfe211be16e8388131773d28508b3fcbdd52
```

这些 ID 只用于定位历史日志或未来可能发现的 object database；当前目录里没有对应 Git objects。

## 9. 重建与继续开发的顺序

分阶段执行入口：`docs/recovery/2026-08-10-reconstruction-qualification-agent-workplan.md`。该文档把测试资产恢复、安全复核、注释收口和最终资格验收拆成 RQ-00 至 RQ-12；一次只交付一张任务卡。

1. **已完成：** 9 模块主源码已通过完整 reactor `compile`；后续生产源码修改必须保持这一基线。
2. 用本地 JAR 的 class inventory 对比当前源码，确认每个生产类型都有来源；不要把 ANTLR 生成类重复提交到 `src/main/java`。
3. 从 Codex 日志恢复缺失测试、Application conformance harness、`src/test/resources`、SIR fixtures、README、`.gitignore` 和 ADR-002/004-018；找不到原文时明确标记重写，不伪称原件。
4. 先跑模块级 compile/test，再跑 full Reactor；灾后测试基线必须重新建立，不能沿用 392/QUALIFIED/MVP_FEASIBLE 数字。
5. 恢复功能后，优先复核 DELETE/Recovery、Bundle/Journal、candidate digest binding、read-only plan、路径/链接防护和 canonical JSON，这些是高风险边界。
6. 只有用户明确授权后再初始化 Git、提交或配置远端；首次新仓库提交前应把本文件和必要证据文档纳入版本控制，并立即建立异地备份。
7. 在灾后基线稳定前，不开始 Redis、第二 Target、Constraint VM、PSG REFERENCES、Snapshot V2、Change SIR v0.7、GUI/daemon、多用户或增量编译。

## 10. 给后续 Agent 的工作规则

- 先读本文与 `AGENTS.md`，再读当前任务相关代码/测试/ADR。
- 先复现真实路径和失败，再修改；优先最小测试和根因修复。
- 不把反编译源码视为已验证原始源码；对关键事务类必须对照字节码行为、历史补丁和测试。
- 不读取、修改、暂存 `.claude/`、`.trae/`、`CompleteCommand.md` 或用户交付蓝图，除非用户对具体文件明确授权。
- 不执行 Git 写操作，除非用户明确授权。
- 对外报告必须区分：历史记录、灾后当前验证、局部测试、完整 Reactor、环境 qualification 和生产结论。
