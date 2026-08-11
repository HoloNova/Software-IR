# KCG-Code 灾后全量资格验收（RQ-12）

- 创建日期：2026-08-11
- 证据边界：本文所有数字来自 2026-08-11 实际命令输出（本机 Windows 10，git-bash 环境）。
- 与历史 `QUALIFIED` / `MVP_FEASIBLE`（Stage E / 灾前）无关，不构成任何环境或生产资格。

## 1. 环境与命令

- 主机：Windows 10；JDK（mvn 用 classworlds 直启，见 AGENTS.md 既有环境约束）。
- Maven：`D:\environment\apache-maven-3.9.14`（本机 `mvn` 脚本损坏，一律 java 直启 classworlds）。
- 本地仓库：`D:\maven-repo`；全部命令离线 `-o`。
- 执行命令（对应任务卡）：

```powershell
java -Dmaven.multiModuleProjectDirectory="..." -Dmaven.home="D:\environment\apache-maven-3.9.14" ^
  -Dclassworlds.conf="D:\environment\apache-maven-3.9.14\bin\m2.conf" ^
  -cp "D:\environment\apache-maven-3.9.14\boot\plexus-classworlds-2.9.0.jar" ^
  org.codehaus.plexus.classworlds.launcher.Launcher "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

- `clean verify` 结果：**BUILD SUCCESS**（Reactor Summary 9 模块全部 SUCCESS；Total time 52.8 s；Finished 2026-08-11T08:29:06+08:00）。
- `git diff --check`：退出 0（仓库无任何 tracked 文件，无 diff 可检查）；`git status --short`：全部 untracked（`.git` 目录存在但**无任何提交**）。

## 2. Reactor / 模块测试统计（本次 clean verify 输出）

统计源：各模块 `target/surefire-reports/TEST-*.xml`（XML 精确聚合；TXT 报告不展开 JUnit `@Nested`，见 §3）。

| 模块 | run | fail | err | skip | 说明 |
|---|---:|---:|---:|---:|---|
| sir-parser | 43 | 0 | 0 | 0 | 与历史基线一致 |
| sir-semantic | 103 | 0 | 0 | 0 | 78 历史 + typed-ref-site 25 |
| sir-lowering-api | 4 | 0 | 0 | 0 | 一致 |
| sir-lowering-spring-boot | 32 | 0 | 0 | 0 | 含 @Nested 19 项（LoweredIrHardeningTest） |
| sir-generator-spring-boot | 4 | 0 | 0 | 0 | 历史 47，缺 43（RQ-03 未排期） |
| sir-project-graph | 0 | 0 | 0 | 0 | 历史 94，全缺（RQ-04 未排期） |
| sir-change | 22 | 0 | 0 | 0 | 历史约 306（15 类）；12/15 类无内容证据 |
| sir-toolchain-application | 132 | 0 | 0 | 10 | 非 conformance；另 46 个 conformance 文件被 POM 排除 |
| kcg-cli | 5 | 0 | 0 | 0 | MvpEvidenceHardening 5；KcgCliWorkflowTest 13 项类级跳过（见 §3） |
| **合计** | **345** | **0** | **0** | **10** | 0 失败 0 错误 |

## 3. 跳过、阻断与失败

- **跳过 10 项**（全部有原因，0 失败）：
  - `ChangePlanningApplicationTest` 6 项：assumption skip（Change 时代 SIR 资源缺失：campus-market-candidate*.sir 等——历史会话仅有 ~134 字符预览，无完整 dump）。
  - `PathGuardTest` 4 项：Windows 条件跳过（`@DisabledOnOs` 非 Windows 路径语义；历史一致）。
  - `KcgCliWorkflowTest` 13 项：类级跳过（同批 Change 资源缺失）；JUnit 容器级跳过不计入 skip 数。
- **@Nested 报告说明**：TXT 报告把 `LoweredIrHardeningTest`（纯 @Nested 容器）记为 `Tests run: 0`，@Nested 测试实际全部执行（console 与 XML 证据，19 项全过）；本报告以 XML 为准（spring-lowering 32 与 RQ-02 一致）。
- **阻断（外部资格）**：
  - conformance 包（46 个文件在磁盘）被 `sir-toolchain-application/pom.xml` 的 compiler+surefire `testExcludes`（`io/kcg/sir/application/conformance/**`）排除——**不是环境 opt-in**，是 RQ-05 恢复缺口（ConformanceSuite 403 行 / SpringBootTargetConformanceIT 550 行截断）导致的**硬排除**。因此：
    - 外部 MySQL conformance：**NOT_RUN（BLOCKED：POM 排除未解除）**。
    - conformance 历史 226 项：**NOT_RUN**。

## 4. CLI 与外部资格状态

- CLI 冒烟（Maven exec，9 个命令，本次输出）：
  - `--help` ✅ Usage 输出（Read-only Change Planning CLI V1）。
  - `--version` ✅ `kcg 0.1.0 (KCG-CLI-CHANGE-PLANNING-V1)`。
  - `context`（无参）✅ 结构化 USAGE_ERROR JSON（KCG-CLI-USAGE-002，canonical 格式）。
  - `context --help` / `plan --help` ✅ 命令用法输出。
  - `generate` / `register` / `apply` / `recover` ⚠️ `unknown command`（KCG-CLI-USAGE-001）——**当前主源码为 V1 只读 CLI（ADR-018）**，历史 M1 全命令集（ADR-019 MVP）未恢复。
- 完整 MVP 流程（generate → register → context/plan → apply → recover）：**NOT_RUN**（命令缺失 + candidate 资源缺失 + conformance 未运行三重阻断）。
- thin JAR：`kcg-cli-0.1.0-SNAPSHOT.jar` 为 thin JAR（无依赖打包）；`java -jar` 直启预期 `NoClassDefFoundError`（历史一致），不作为业务失败；CLI 运行一律 Maven exec。

## 5. 测试数减少逐项说明（vs 灾前历史 1130 + 54）

| 缺失来源 | 历史数 | 当前 | 说明 |
|---|---:|---:|---|
| conformance（MySQL） | 226 | NOT_RUN | RQ-05 缺口未闭合（2 文件截断），POM 排除；恢复上限 = 07-26 会话 40KB 输出截断 |
| sir-project-graph | 94 | 0 | RQ-04 未排期（无测试文件恢复） |
| sir-generator-spring-boot | 47 | 4 | RQ-03 未排期（43 项缺失） |
| kcg-cli | 54 | 5 + 13 跳过 | 36 项测试类无内容证据；13 项被资源缺失阻断 |
| sir-change | ~306 | 22 | 12/15 测试类仅有 surefire 运行记录（Scope=17、Impact=8、AddCapabilityTest=23 等），无源码内容 |
| toolchain 非 conformance | 历史 58 类 | 16 类 132+10 | 42 类无内容证据；6 项资源缺失 skip + 4 项 Windows 条件 skip |
| sir-semantic / parser / lowering | 78+25 / 43 / 36 | 103 / 43 / 36 | 一致（无减少） |

## 6. 仍为反编译且缺乏行为测试的生产文件

灾后记录（`.memory/REBUILD_CONTEXT_2026-08-10.md` §1）仅有模块级反编译计数（227 个），**逐文件名单无法从灾后记录重建**（该记录本身即灾后产物）。按模块列出反编译数量与当前行为测试覆盖：

| 模块 | 反编译补缺数 | 行为测试覆盖 | 结论 |
|---|---:|---|---|
| sir-project-graph | 30 | **0 测试** | 全部缺乏行为测试（RQ-04 未排期） |
| sir-generator-spring-boot | 21 | 4 项（恢复的硬化测试） | 生成器主体缺乏行为测试（RQ-03 未排期） |
| sir-change | 29 | 22 项（3 类） | 部分覆盖；12/15 类无测试内容 |
| sir-toolchain-application | 81 | 132 项（16 类，非 conformance） | 核心路径有覆盖；conformance 46 文件不编译不运行 |
| kcg-cli | 10 | 5 项（mvp 包） | 命令路由测试被资源阻断 |
| sir-semantic | 41 | 103 项 | 良好覆盖 |
| sir-lowering-spring-boot | 15 | 32 项 | 良好覆盖 |

## 7. 残余风险

1. **conformance 未运行**（最高）：历史 226 项外部 MySQL 资格证据不可复现；`QUALIFIED` 一律不得声称。
2. **Change 时代 SIR 资源缺失**（20+ 个）：阻断 kcg-cli 13 项 + Application 6 项 + DELETE/Apply 端到端场景（B1 差异构造）。
3. **未排期模块**：PSG（94）与 generator（47）零/弱测试。
4. **反编译文件行为风险**：227 个反编译补缺文件中，PSG 30 + generator 21 无行为测试；其余有测试但覆盖度未逐文件核对。
5. **平台差异**：junction 在 Windows 原生验证；卷挂载点 isOther 无第二卷未实测；POSIX 场景由既有 symlink 测试覆盖。
6. **恢复类仅编译证据**：DELETE 完整端到端发布路径、V2 CREATE / V1 UPDATE RecoveryEngine 路径仅有编译证据（RQ-10 记录）。
7. **Git 基线不存在**：无提交、无远端、全 untracked。

## 8. 是否具备建立灾后 Git 基线条件

- **条件判定：具备（有保留）**。
- 依据：`clean verify` 全 reactor 0 失败 0 错误（345+10 skip，全部有原因）；模块级基线可重复（本文命令可复跑）；已知缺口全部如实记录（§3/§5/§6）。
- 保留意见：
  1. 建立基线前应把 `.memory/REBUILD_CONTEXT_2026-08-10.md`、`docs/recovery/**` 与本报告纳入首次提交；
  2. 首次提交应排除 `target/` 与本地 Maven 仓库相关路径（`.gitignore` 已存在，需复核内容）；
  3. 用户明确授权后才可执行 `git init/add/commit`（本报告不构成授权）；
  4. conformance 缺口闭合或明确放弃前，首次基线应标记为"灾后恢复基线（资格验收前置）"，不得宣称生产资格。
