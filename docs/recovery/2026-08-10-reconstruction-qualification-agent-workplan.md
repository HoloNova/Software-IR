# KCG-Code 灾后重建与资格恢复：分阶段 Agent 工作计划

> 创建日期：2026-08-10  
> 状态：执行入口  
> 适用仓库：`C:\Users\zdw00\Desktop\Software IR`  
> 使用方式：一次只复制并交付一张任务卡；上一张任务卡完成、复核并记录结果后，才交付下一张。

## 1. 目的

本文件把灾后重建工作拆成多个有边界、可独立验收的 Agent 任务。它不是一个要求单个 Agent 修复整个项目的巨型 Prompt，也不是开发新功能的路线图。

当前唯一目标是逐步回答三个问题：

1. 哪些文件和行为已经从历史证据中恢复；
2. 哪些恢复源码经过当前测试或端到端流程验证；
3. 哪些高风险边界仍然只具备“能够编译”的证据。

本阶段不得启动 typed reference-site、PSG `REFERENCES`、Redis、第二 Target、Constraint VM、Change SIR v0.7、GUI、daemon、多用户或增量编译等新功能。

## 2. 当前事实基线

- 项目曾因磁盘损坏完全丢失工作区和 `.git`。
- 当前源码来自 Codex 历史补丁、历史文件输出、本地 Maven POM/JAR 和有限人工校正。
- 当前有 9 个 Maven 子模块、10 个 POM、328 个主 Java 源文件、28 个测试 Java 文件。
- 已执行：

  ```powershell
  mvn "-Dmaven.repo.local=D:\maven-repo" -o clean compile -DskipTests
  ```

  父工程与 9 个模块全部 `BUILD SUCCESS`。

- 当前实际通过的恢复测试为 64 项：Parser 43、Lowering API 4、Spring Boot Lowering 13、Generator 4。
- 当前 full reactor `mvn test` 被缺失的 Application conformance harness 阻断。
- `sir-semantic`、`sir-project-graph`、`sir-change` 的历史测试大部分尚未恢复；Application 与 CLI 测试也明显不完整。
- 历史 392 项基线是灾前记录，不是灾后当前通过结论；它主要覆盖 Parser、Semantic、Lowering、Generator、PSG、Application，不能自动代表后来加入的 Change/CLI。
- 2026-08-10 已执行 `git init -b main`。这是新的空 Git 历史，目前没有提交，不是旧仓库历史的延续。

详细上下文见：

- `AGENTS.md`
- `.memory/INDEX.md`
- `.memory/REBUILD_CONTEXT_2026-08-10.md`

## 3. 总体执行策略

严格采用以下循环，不做全仓一次性改造：

```text
恢复历史证据
  -> 让测试先能编译
  -> 运行恢复测试
  -> 为反编译/人工校正边界补 characterization test
  -> 定向检查明显缺陷和安全不变量
  -> 最小修复
  -> 重新验证
  -> 最后补关键注释与文档
```

### 为什么不先批量补注释

反编译源码可能能编译但仍存在细微语义偏差。过早添加解释性注释会把未经验证的行为写成“事实”，并大量消耗上下文。注释只能在行为被测试或字节码/历史证据确认后补充。

### 为什么优先恢复旧测试

原测试代表灾前已经确认过的契约；根据当前实现重新编写测试，容易让测试迎合反编译结果。只有明确找不到原测试时，才编写 characterization test，并在报告中标明它是灾后新增测试。

## 4. 所有执行 Agent 的共同强约束

每张任务卡都自动包含以下规则：

1. 修改前完整阅读 `AGENTS.md`、`.memory/INDEX.md`、`.memory/REBUILD_CONTEXT_2026-08-10.md`，再读取任务指定文件。
2. 当前代码、当前测试和 `AGENTS.md` 优先于旧报告；遇到阶段描述冲突时保留当前模块，不得删除代码来迎合旧文档。
3. 不读取、修改、暂存 `.claude/`、`.trae/`、`CompleteCommand.md` 或用户交付蓝图。
4. 不执行 `git add`、`git commit`、`git switch`、`git checkout`、`git reset`、`git clean`、rebase、merge 或 push。
5. 不修改任务范围外的生产代码、测试或文档；不做全仓格式化、批量重命名或无关重构。
6. 不把反编译源码称为原始源码；必须区分：历史原文、历史补丁、JAR 反编译、人工校正、灾后新写。
7. 恢复文件时优先使用 Codex 历史中的完整文件输出或补丁；其次使用本地 JAR/POM；不得凭类名和测试名猜测原实现。
8. 普通输入失败必须保持结构化、确定性和 fail-closed；不得通过吞异常、放宽验证或删除断言让测试通过。
9. 每次只处理当前任务卡；发现跨模块问题时记录文件、症状和证据，不顺手扩展范围。
10. 最终报告必须列出：修改文件、恢复来源、运行命令、测试数量、失败/跳过项、未解决问题、是否改变生产行为。

## 5. 状态表

状态只能使用：`PENDING`、`IN_PROGRESS`、`BLOCKED`、`REVIEW_REQUIRED`、`COMPLETE`。

| ID | 任务 | 前置 | 当前状态 | 完成门槛 |
|---|---|---|---|---|
| RQ-00 | 测试资产与权威状态盘点 | 无 | DONE 2026-08-11 | 形成可核查清单，不改生产代码 |
| RQ-01 | Semantic 历史测试恢复 | RQ-00 | DONE 2026-08-11 | 103/103（历史 78 + typed-ref-site 25） |
| RQ-02 | Lowering 历史测试恢复 | RQ-01 | DONE 2026-08-11 | 36/36（4 API + 32 Spring） |
| RQ-03 | Generator 历史测试恢复 | RQ-02 | PENDING | Generator 测试与资源恢复并运行 |
| RQ-04 | PSG 历史测试恢复 | RQ-03 | PENDING | PSG 测试可编译并运行 |
| RQ-05 | Application conformance harness 恢复 | RQ-04 | PARTIAL | 约 40 文件恢复；2 缺口（ConformanceSuite 403 行 / SpringBootTargetConformanceIT 550 行截断）；POM testExcludes 绕过编译，未修复缺口 |
| RQ-06 | Change 测试恢复 | RQ-05 | DONE(部分) 2026-08-11 | 22 项全过（19+3）；12/15 类无内容证据列为未验证 |
| RQ-07 | Application 非 conformance 测试恢复 | RQ-06 | DONE 2026-08-11 | 101 过 + 10 skip（6 资源缺失 + 4 Windows），0 失败 |
| RQ-08 | CLI 测试与本地流程恢复 | RQ-07 | DONE 2026-08-11 | MvpEvidenceHardening 5/5；KcgCliWorkflowTest 13 项类级 skip（资源缺失）；7 命令 Maven exec 冒烟完成 |
| RQ-09 | 路径、链接与文件事务安全复核 | RQ-08 | DONE 2026-08-11 | 3 发现全闭合：validateRelativePath 收紧、junction/reparse-point fail-closed、manifest 重复拒绝；PathSecurityReviewTest 12/12 |
| RQ-10 | CREATE/DELETE/Recovery 安全复核 | RQ-09 | DONE 2026-08-11 | RecoveryStateMachineTest 9/9；8 条不变量全部代码级确认 + 测试固化；零 src/main 修改 |
| RQ-11 | 注释与文档收口 | RQ-10 | DONE 2026-08-11 | 8 处 [RQ-11] 注释（全部指向已验证不变量）；workplan/inventory 状态收敛；REBUILD_CONTEXT 更新待主 Agent 确认 |
| RQ-12 | 灾后全量资格验收 | RQ-11 | PENDING | clean verify 结论与缺口报告完成 |

---

# 任务卡 RQ-00：测试资产与权威状态盘点

## 任务目标

直接建立灾后测试恢复清单和文档冲突清单，不修改任何生产代码或测试行为。结果要让后续 Agent 不必重新扫描全部 235 MB 会话记录才能知道从哪里继续。

## 已知上下文

- 当前主源码 clean compile 成功。
- 当前只有 64 项测试实际通过。
- 历史记录存在 392 项基线，但该数字不能直接作为当前结论。
- `.memory/INDEX.md` 和部分旧文档有重复或较早阶段描述。

## 允许修改范围

- 新建或更新 `docs/recovery/TEST_RECOVERY_INVENTORY.md`。
- 不修改 `src/main`、`src/test`、POM、`AGENTS.md` 或 `.memory`。

## 执行要求

1. 枚举每个模块当前 `src/test/java`、`src/test/resources` 和 Surefire 可发现测试。
2. 从 Codex 会话记录中提取灾前测试类名、测试数量、资源路径和关键验收命令。
3. 按模块列出当前存在、历史存在但缺失、来源未知三类资产。
4. 记录 `AGENTS.md`、灾后重建文档和旧 `.memory` 之间的阶段状态冲突，但不自行决定删除模块或回退代码。
5. 标明哪些数字是历史记录，哪些是当前运行证据。

## 验收标准

- 清单覆盖全部 9 个子模块。
- 每个缺失资产至少包含一个历史会话路径、历史文件路径或“未找到证据”标记。
- 不改变任何生产或测试代码。

## 最终报告格式

- 修改文件
- 各模块当前/历史测试资产数量
- 最优先缺失文件列表
- 文档冲突列表
- 下一任务 RQ-01 的精确输入

---

# 任务卡 RQ-01：恢复 `sir-semantic` 历史测试

## 任务目标

从 Codex 历史记录优先恢复 `sir-semantic` 原测试和资源，使 Semantic 测试重新能够编译，并运行当前可恢复测试。不得通过削弱 Resolve/Type/Validate/Normalize 约束换取通过。

## 修改范围

- `sir-semantic/src/test/**`
- 只有在测试证明主源码存在明确反编译错误时，才允许最小修改 `sir-semantic/src/main/**`。
- 不修改其他模块。

## 必须覆盖的契约

- 名称只由 `ResolvePass` 解析；后续阶段消费绑定。
- 重复 SymbolId 立即失败。
- `AstNameRef`、reference binding、find item 作用域和稳定 ID。
- 类型兼容、Workflow 顺序、Return 规则、Create/Update/Persist 规则。
- Constraint 参数类型、数量和 SourceSpan/诊断归属。
- Normalize 不重新解析名称，合法模型不含 `sir://unknown`。
- 非法输入产生结构化 Failure，而不是普通运行时异常。

## 执行要求

1. 先恢复历史测试原文和 fixture；找不到原文时明确标记灾后新测试。
2. 先解决 testCompile，再运行：

   ```powershell
   mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-semantic -am test
   ```

3. 对任何生产代码修改给出历史补丁、JAR 字节码或失败测试证据。
4. 不新增语法、不修改 ANTLR、不实现 typed reference-site 新功能。

## 验收标准

- Semantic 测试源码全部可编译。
- 测试结果、数量和失败项被精确记录。
- 不以历史 78 项为必须伪造的数字；若少于历史记录，逐项列出缺失类或 fixture。

## 最终报告格式

- 恢复/新写测试文件及来源
- 生产代码修改与根因证据
- 命令和测试统计
- 与历史 78 项的差异
- 未恢复资产

---

# 任务卡 RQ-02：恢复 Lowering API 与 Spring Boot Lowering 历史测试

## 任务目标

恢复 `sir-lowering-api` 与 `sir-lowering-spring-boot` 的灾前测试，验证当前 V0_2 模型与历史测试的版本关系。不得把旧 V0_1 测试夹具直接当作当前模型规范。

## 修改范围

- `sir-lowering-api/src/test/**`
- `sir-lowering-spring-boot/src/test/**`
- 必要时最小修改对应模块 `src/main/**`，必须有失败测试和字节码/历史证据。

## 必须覆盖的契约

- `ProjectArtifact`、`ActorBinding`、`TransportPlan`。
- `FindStep.itemVariable`、`PersistStep.action`。
- `LoweredIrVersion.V0_2` 与 Profile 一致性。
- actor identity 只能访问 identity。
- response representation 与 output type 一致。
- Lowered ID、Origin、集合不可变性、稳定排序和重复身份拒绝。

## 执行命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-lowering-spring-boot -am test
```

## 验收标准

- 两模块测试全部 testCompile。
- 旧构造器调用根据当前字节码和当前模型正确迁移，不删除断言。
- 明确区分历史恢复测试与灾后新增 characterization test。

## 最终报告格式

- 恢复来源
- 当前测试统计
- V0_1/V0_2 差异
- 生产代码是否变化
- 未覆盖 Lowered IR 类型

---

# 任务卡 RQ-03：恢复 Spring Boot Generator 历史测试

## 任务目标

恢复 Generator 测试、fixture、快照和离线编译矩阵，验证 Generator 只消费 `SpringBootLoweredModel`，且相同输入产生字节级稳定输出。

## 修改范围

- `sir-generator-spring-boot/src/test/**`
- 仅在失败测试证明错误时最小修改 `sir-generator-spring-boot/src/main/**`。
- 不让 Generator 读取 Parser、AST、Normalized Model、SymbolTable、SIR 或文件系统。

## 必须覆盖的契约

- `pom.xml`、Application、Enum、Entity、Mapper、DTO、Exception、Service、Controller。
- Unit response、复合 Find、actor identity transport、import 排序。
- GeneratedFile 不可变、路径唯一、内容确定。
- 生成结果能以 Java 21/冻结依赖矩阵离线编译。

## 执行命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test
```

## 验收标准

- 所有恢复 Generator 测试与资源可编译、可执行。
- 找不到历史快照时不得凭当前输出直接宣称等价；新增快照必须标记为灾后基线候选。

## 最终报告格式

- 恢复的测试/资源/快照
- 测试统计
- 确定性与离线编译证据
- 与历史 47 项记录的差异
- 未验证渲染路径

---

# 任务卡 RQ-04：恢复 Project Symbol Graph 历史测试

## 任务目标

恢复 `sir-project-graph` 的历史测试，验证 PSG v0.1 只读、不可变、确定性和 provenance 边界。不得顺手实现 `REFERENCES` 或持久化。

## 修改范围

- `sir-project-graph/src/test/**`
- 必要时最小修改 `sir-project-graph/src/main/**`。
- 不修改 Semantic、Lowering、Generator 或 Application 公共接口。

## 必须覆盖的契约

- DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE。
- 稳定节点/边身份、重复拒绝、顺序确定。
- 输入集合不可变，输出图不可变。
- Graph builder 不访问文件系统、不重新按名称解析、不解析身份字符串。
- Application 集成中的 Graph Failure 保持 `NO_CHANGES`。

## 执行命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-project-graph -am test
```

## 验收标准

- PSG 测试可编译并运行。
- 不增加 `REFERENCES`、Snapshot V2 或 Graph persistence。
- 与历史 94 项记录的差异逐类说明。

## 最终报告格式

- 恢复资产和来源
- 测试统计
- PSG 不变量证据
- 生产代码修改
- 缺失测试类别

---

# 任务卡 RQ-05：恢复 Application conformance harness

## 任务目标

从 Codex 历史输出和补丁恢复 Application conformance 测试框架，使当前已存在的 conformance 测试能够 testCompile。本任务只恢复测试基础设施，不执行真实外部 MySQL qualification，也不宣称 `QUALIFIED`。

## 已知缺失入口

- `ConformanceFixtures`
- `SchemaName`
- `ConformanceResult`
- `ConformanceFailureKind`
- `SpringBootTargetConformanceIT`
- 以及它们引用的环境、证据、Maven runner、manifest verifier、数据库控制和清理辅助类。

## 修改范围

- `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/**`
- `sir-toolchain-application/src/test/resources/**`
- 必要的 test-scope POM 配置，但不得添加生产依赖。
- 不修改 `src/main/**`。

## 执行要求

1. 从历史文件输出和补丁按时间恢复，不根据编译错误逐个空实现类型。
2. 保留 credential redaction、evidence、工作区身份、清理和 fail-closed 规则。
3. 禁止连接网络、启动数据库、下载依赖或使用真实凭据。
4. 先达到：

   ```powershell
   mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-toolchain-application -am test-compile
   ```

5. 若历史框架无法完整恢复，报告最小缺失闭包，不创建返回固定成功的 stub。

## 验收标准

- conformance 测试基础设施 testCompile，或给出带历史搜索证据的精确缺失闭包。
- 没有执行外部资格测试，没有写入凭据。
- 不以跳过或删除测试作为完成方式。

## 最终报告格式

- 恢复的 harness 文件与历史来源
- testCompile 结果
- 未恢复的依赖闭包
- 是否需要外部环境才能继续
- 明确写出“未执行 qualification”

---

# 任务卡 RQ-06：恢复 `sir-change` 历史测试

## 任务目标

恢复 Change SIR parser、semantic、planning 和版本协议测试。此任务只恢复当前已有 Change v0.1-v0.6 行为，不设计 v0.7。

## 修改范围

- `sir-change/src/test/**`
- 必要时最小修改 `sir-change/src/main/**`。
- 不修改 Application 文件事务实现。

## 必须覆盖的契约

- Change 语法和版本拒绝。
- SymbolId/AstNodeId 定位，不使用名称模糊匹配。
- closure、计划顺序、重复/冲突拒绝。
- CREATE/UPDATE/DELETE 的 typed planning 结果。
- canonical 表示、不可变性、确定性和结构化诊断。

## 执行命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-change -am test
```

## 验收标准

- 当前 Change 模块测试可编译、运行。
- 不新增版本或扩展 Grammar。
- 无法恢复的历史功能必须列为未验证，不以新测试猜测补齐。

## 最终报告格式

- 测试资产来源
- 测试统计
- 各 Change 版本覆盖
- 生产代码修改
- 未验证功能

---

# 任务卡 RQ-07：恢复 Application 非 conformance 测试

## 任务目标

恢复 Toolchain Application 的常规单元/集成测试：编排、preflight、Graph、Baseline、registration、candidate、plan、apply 和文件事务。本任务不做外部 MySQL qualification。

## 修改范围

- `sir-toolchain-application/src/test/**`，排除 RQ-05 已恢复的 conformance 文件。
- 只有失败测试证明主源码错误时才修改 `src/main/**`。

## 必须覆盖的契约

- 严格 UTF-8 与阶段失败映射。
- `context` / `plan` 严格只读。
- candidate digest 与实际检查字节绑定。
- Bundle、Manifest、CURRENT、LOCK、Journal 权威边界。
- 默认 `FAIL_IF_EXISTS` 与显式 `REPLACE_EXISTING`。
- Graph Failure 在写入前返回 `NO_CHANGES`。
- 发布失败后的补偿和 `RECOVERY_REQUIRED`。

## 执行命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-toolchain-application -am test
```

如果 conformance 测试需要外部环境，使用项目已有的明确 opt-in 机制；不得删除测试或偷偷改变默认行为。

## 验收标准

- 非外部环境测试能在离线模式运行。
- 所有跳过项有原因。
- 不把 conformance 未运行写成 Application 全量通过。

## 最终报告格式

- 恢复测试分类
- 默认离线测试统计
- 跳过和环境依赖
- 生产代码修改
- 未验证事务路径

---

# 任务卡 RQ-08：恢复 CLI 测试与本地流程

## 任务目标

恢复 `kcg-cli` 的参数、canonical JSON、退出码和命令路由测试，并使用 Maven exec 执行不需要外部服务的本地冒烟流程。

## 修改范围

- `kcg-cli/src/test/**`
- 必要时最小修改 `kcg-cli/src/main/**`。
- 不设计 fat JAR，不修改 Application ownership。

## 必须覆盖的命令

- `--help`
- `generate`
- `register`
- `context`
- `plan`
- `apply`
- `recover`

## 执行要求

1. CLI 只能把严格参数转换成 typed Application request。
2. CLI 不得直接读写 Bundle、CURRENT、LOCK、Journal。
3. 使用 Maven exec；不要把 thin JAR 的 `NoClassDefFoundError` 当作业务失败。
4. 冒烟命令示例：

   ```powershell
   mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl kcg-cli exec:java "-Dexec.mainClass=io.kcg.cli.KcgCli" "-Dexec.args=--help"
   ```

5. 所有临时工作区必须位于明确临时目录，结束后安全清理；不得操作用户真实工程目录。

## 验收标准

- CLI 测试可编译、运行。
- canonical JSON 和退出码有断言。
- 明确区分命令冒烟、完整 MVP 流程和生产资格。

## 最终报告格式

- 恢复测试和来源
- 命令/退出码结果
- 临时目录清理结果
- thin JAR 现状
- 未执行的外部流程

---

# 任务卡 RQ-09：路径、链接与文件事务安全复核

## 任务目标

在测试资产基本恢复后，对 Application 的路径、链接和文件事务做定向安全复核。先写失败测试，再修改实现；不得做泛化安全重构。

## 修改范围

- `sir-toolchain-application` 中 path guard、safe target resolver、preflight、staging、publish、backup、manifest verification 相关文件及测试。
- 不修改 Parser、Semantic、Lowering、Generator、PSG 或 Change 语义。

## 必须验证的攻击/故障面

- `..`、绝对路径、盘符、UNC、Windows 保留名、尾随点/空格、大小写碰撞。
- symlink/junction/reparse point 父链和提交时重新检查。
- 普通文件与目录类型变化。
- 同卷 staging 和原子发布。
- 备份与目标物理身份。
- preflight 与 commit 之间的 TOCTOU。
- Manifest path、byte count、digest、重复项与 canonical 排序。

## 验收标准

- 每个发现先有最小失败测试。
- 权限、I/O、链接和类型不确定性全部 fail closed。
- 不通过放宽 PathGuard 或忽略异常让测试通过。
- 运行模块测试并记录 Windows 条件跳过。

## 最终报告格式

- 风险项与证据
- 新增失败测试
- 修复文件
- 测试结果
- 未覆盖平台差异

---

# 任务卡 RQ-10：CREATE、DELETE 与 Recovery 安全复核

## 任务目标

验证最危险的状态机和故障恢复边界：CREATE、UPDATE、DELETE、Journal、CURRENT 与显式 Recovery。不得根据“看起来最像”猜测恢复方向。

## 修改范围

- `sir-toolchain-application` 中 Change execution、transaction journal、baseline store、recovery engine 相关文件及测试。
- 必要时读取 `sir-change` 公共契约，但不扩展 Change 语言。

## 不可破坏的不变量

- Recovery 必须显式触发。
- `CURRENT=B0` 只允许向前恢复。
- `CURRENT=B1` 只允许向后验证/清理。
- DELETE backup 使用同卷 `Files.createLink`，无 copy/move/replace fallback。
- `Files.isSameFile` 前重新以 `NOFOLLOW_LINKS` 证明两端为 regular file。
- 只有直接 `NoSuchFileException` 证明缺失。
- 不能完整补偿时返回 `RECOVERY_REQUIRED` 并保留权威材料。
- 重复执行 Recovery 必须确定、幂等或结构化拒绝。

## 执行要求

1. 建立中断点矩阵：意图持久化前后、文件发布前后、CURRENT 切换前后、清理前后。
2. 每个矩阵单元用临时目录和故障注入测试验证。
3. 对反编译的 recovery 类对照历史补丁和本地 JAR 字节码。
4. 不执行用户真实目录上的恢复。

## 验收标准

- CREATE/DELETE/Recovery 关键路径有测试证据。
- 所有不确定状态 fail closed。
- 报告明确哪些路径仍仅有编译证据。

## 最终报告格式

- 状态机/中断点矩阵
- 新增测试和修复
- 字节码/历史证据
- 测试结果
- 残余高风险项

---

# 任务卡 RQ-11：关键注释与文档收口

## 任务目标

仅为已经被当前测试、历史原文或字节码确认的复杂不变量补注释，并收敛灾后状态文档。不得批量给所有反编译文件添加说明。

## 修改范围

- 已由 RQ-01 至 RQ-10 验证的复杂实现文件。
- `docs/recovery/**`
- 经主 Agent确认后更新 `.memory/REBUILD_CONTEXT_2026-08-10.md`；执行 Agent不得自行改写其他长期记忆。

## 应添加的注释

- 解释“为什么”的事务、安全、身份、确定性边界。
- 容易被未来重构破坏的 fail-closed 条件。
- Recovery 方向、ownership 和阶段分工。
- 与对应测试/ADR 的稳定联系。

## 不应添加的注释

- getter、record 字段、简单循环的逐句翻译。
- 未经验证的行为保证。
- “反编译代码所以不要改”一类无法维护的警告。
- 大段历史对话或临时调试过程。

## 验收标准

- 注释数量克制，均能指向已验证不变量。
- 不删除、覆盖或重排用户已有注释。
- 灾后状态文档明确区分已恢复、已验证、未验证和缺失。

## 最终报告格式

- 注释/文档修改列表
- 每项注释对应的测试或证据
- 删除的过时陈述及原因
- 仍存在的文档冲突

---

# 任务卡 RQ-12：灾后全量资格验收

## 任务目标

在前述任务完成后执行一次新的灾后资格验收，形成可重复运行的当前基线。不得把历史 `QUALIFIED` 或 `MVP_FEASIBLE` 复制为当前结论。

## 修改范围

- 默认不修改生产代码。
- 只允许修复验收脚本、测试资源或报告中的机械错误；真实功能失败必须退回对应模块任务。
- 新建 `docs/recovery/FINAL_QUALIFICATION.md`。

## 必须执行

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

另外执行不需要外部服务的 CLI 冒烟，并记录 conformance 测试是否因环境 opt-in 而跳过。

## 资格报告必须区分

- clean compile；
- 默认离线 tests；
- Windows 条件跳过；
- 外部 MySQL conformance；
- CLI 本地冒烟；
- 完整 MVP 流程；
- 生产安全、性能、HA 和跨平台结论。

## 验收标准

- 所有数字来自本次命令输出。
- 测试数减少必须逐项说明，不能只写“历史测试缺失”。
- 外部资格未运行就写 `NOT_RUN`，环境阻断写 `BLOCKED`，不得写 `QUALIFIED`。
- 报告列出所有仍为反编译且缺乏行为测试的生产文件。
- 不执行 Git 提交；由主 Agent和用户复核后决定是否建立灾后基线提交。

## 最终报告格式

- 环境与命令
- Reactor/module 测试统计
- 跳过、阻断和失败
- CLI/外部资格状态
- 残余风险
- 是否具备建立灾后 Git 基线的条件

## 6. 使用提醒

一次只交付一张任务卡。执行 Agent返回后，先检查：

1. 是否越过修改范围；
2. 是否把历史证据写成当前通过；
3. 是否删除测试或放宽断言；
4. 是否执行了未授权 Git 操作；
5. 是否留下可供下一任务直接使用的精确缺口。

只有以上检查通过，才把下一张任务卡交给 Agent。
