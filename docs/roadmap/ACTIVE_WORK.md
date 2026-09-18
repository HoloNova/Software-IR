# 当前工作单：Q8 阶段 7 最终资格关闭动作（G0 完成门）

- 状态：`AWAITING_ACCEPTANCE`（G0 完成门已闭合、数字已统一、1.1 冲突已裁决、提交内容清单已给出）
- 所属阶段：路线图阶段 7（最终资格）；**这是 G0 的关闭动作**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 7、`docs/roadmap/README.md`（G0 阶段完成门）
- 前置工作：Q1–Q7 已完成并归档，阶段 1–6 全部闭合（[`completed/`](completed/)）
- 授权状态：负责人 2026-09-18 回复“确认”（认可推荐完成门），仍需复核 D1–D5 后转为 `IN_PROGRESS`
- **版本快照（负责人 2026-09-18 明确指示）**：本单**不执行任何 Git 提交**；G0 完成后由**项目负责人手动提交**。执行者只负责让工作区处于"可提交且可复核"的状态并给出提交内容清单。

## 目标

把 G0 的完成门闭合为**一份可引用的资格报告**，并让仓库处于负责人可手动提交的干净状态：

1. 两条标准命令 + `git diff --check` + `git status --short` 的证据按当次执行记录；
2. 资格文档全部数字与事实**互相一致**（当前仍有多处阶段化的历史数字并存，需统一到本次结论并保留历史小节）；
3. `docs/qualification/CURRENT_QUALIFICATION.md` 1.1 登记的**证据冲突**给出显式裁决与指针；
4. 阶段 7 要求的六类记录（默认离线 Reactor、平台条件、外部 MySQL、CLI 冒烟与完整生命周期、跳过/排除/BLOCKED/NOT_RUN、未覆盖的平台与发行方式）逐条落到证据指针；
5. 明确列出**本单不改变任何生产行为**（预期纯文档），并把提交内容清单交给负责人。

## 勘察结论（2026-09-18，均直接读文档得到）

1. **数字仍不一致（必须在关闭前统一）**：`CURRENT_QUALIFICATION.md`
   - 第 5 行总计已是 **554 / 0 / 0 / 5**；
   - 1.6 表格仍写 **478**（该阶段的历史事实，正确但需标注为阶段值）；
   - 1.7 表格写 **542**（同上）；
   - 第 323 行"可以说"段仍写 **465 run**（**真正的陈旧值，需更新**）。
   `PROJECT_STATUS.md` 已是 554；`README.md` 与 `OWNER_GUIDE.md` 未见陈旧计数，但需逐处复核。
2. **1.1 的证据冲突尚未显式裁决**：`docs/design/implementation-baseline.md`（2026-09-08，源码基点 `5bba6ea`）第 68 行仍写着标准命令"失败于 `sir-parser:testCompile`，找不到 `io.kcg.sir.api`、`io.kcg.sir.ast`"，而 2026-09-18 的 Linux 全新仓库复跑**没有复现**该失败（见 1.2）。1.1 只说"Linux 侧复跑结果见 1.2"，未在实现基线侧标注已解决 → 两份文档仍给出互相竞争的当前印象。
3. **Windows 侧只有历史证据**：2026-08-11 Windows 运行记录为 **373 / 0 / 0 / 10**，且当时 `SafeTargetResolverTest.tryCreateSymlink` 创建失败时会静默 `return`（记为通过），因此那次"0 失败"**不构成**软链接拒绝路径的证据。5 项 junction 用例按设计只在 Windows 执行，本机（Linux）为 skip。→ 阶段 7 要求记录"平台条件"，本机无法产生 Windows 证据。
4. **已知的 NOT_RUN / 未覆盖项**（应在关闭报告里集中登记）：thin JAR/发行包、完整 CLI 本地生命周期（四个写命令未发布）、第二卷/挂载点、跨卷只在注册阶段被证明、Windows junction 与 Windows 离线仓库路径。
5. **负责人已确认的完成门判定**：两个闸门 BUILD SUCCESS + **554 / 0 / 0 / 5** + 外部 MySQL 矩阵 QUALIFIED + 全部残余缺口有登记。

## 任务

1. **执行并记录证据**：按基线跑冻结形式与完成形式两条命令，外加 `git diff --check`、`git status --short`；把模块级与合计计数（取自当次 Surefire XML/命令输出，不用历史数字）写入工作单。
2. **统一数字**：`CURRENT_QUALIFICATION.md` 内所有总数统一为本次结论；历史小节（1.2–1.8）的阶段性数字**保留但显式标注为"该阶段当时的计数"**，避免读者误读为当前值；同步核对 `PROJECT_STATUS.md`、`README.md`、`OWNER_GUIDE.md`、`REMAINING_WORK.md`。
3. **裁决 1.1 的证据冲突**：在 1.1 与 `implementation-baseline.md` 两侧各加一条**互相指向**的裁决说明（谁裁决、以什么证据、结论是什么），使读者不会把 2026-09-08 的失败基线当作当前状态。
4. **写 G0 关闭报告**：在 `CURRENT_QUALIFICATION.md` 顶部（或新增 0 节）给出 G0 完成门清单，逐项给出证据指针：两条命令、模块计数、外部矩阵 QUALIFIED、三路径故障矩阵、CLI 边界、POM 排除状态、全部 skip/排除/NOT_RUN/未覆盖项。
5. **准备可提交状态**：确认无临时文件、无探针残留、无未跟踪的构建产物；输出**提交内容清单**（按区域分组的改动文件数、生产代码改动逐文件列出、以及建议的提交信息文本供负责人使用）。**不执行提交、不执行推送。**
6. **不新增能力**：本单预期**零生产代码改动**。若在核对中发现代码级不一致（例如文档描述的契约与代码不符），**停下来向负责人报告**，由负责人决定是否另开工作单——不得在本单顺手改代码。

## 需要负责人裁定的事项（推荐项已标注）

- **D1 数字历史化的写法**：推荐"当前结论只有一个权威处（第 5 行总计 + G0 关闭报告），历史小节标注阶段值"，而不是把历史数字全部改写成本次值（那会篡改历史证据）。
- **D2 `implementation-baseline.md` 是否由本单修改**：推荐**修改**（在该文件里加一条"已由 2026-09-18 Linux 复跑裁决"的指针）。它是 `docs/design/` 下的校准文档，属于设计侧入口；不改就会继续与资格文档冲突。若你希望设计文档保持"带日期快照"不被后期修改，则改为只在资格文档侧标注，并在 1.1 里写明"实现基线保持原样，冲突以本文件裁决为准"。
- **D3 是否新建 G0 关闭报告文件**：推荐**不新建**，直接在 `CURRENT_QUALIFICATION.md` 顶部增补"G0 完成门"一节（保持"结论只有一个权威处"的既有规则）；备选是新建 `docs/qualification/G0-CLOSURE.md` 作为独立关闭件。
- **D4 Windows 证据的处理**：推荐**如实登记为"本机不可产生"**，并在报告中给出负责人可在 Windows 工作机原样执行的命令（含 `D:\maven-repo` 路径），由负责人决定是否补跑；**不**把 2026-08-11 的 Windows 数字计入本次完成门。
- **D5 提交信息草稿**：推荐在报告里给出**建议的提交信息文本**（概述 Q1–Q7 的范围与证据），供负责人手动提交时使用；执行者不提交。

## 完成门

- 两条命令 BUILD SUCCESS，合计计数取自当次执行，且与工作单记录一致；
- 资格文档内**不存在互相矛盾的当前计数**；历史值均有阶段标注；
- 1.1 的证据冲突有显式裁决，且两侧文档（按 D2 的选择）互相指向；
- 阶段 7 要求的六类记录逐条有证据指针；
- `git diff --check` 无输出；工作区无临时/探针/构建产物残留；提交内容清单已给出；
- **未执行任何 Git 提交或推送**。

## 验证命令

```bash
# ① 冻结形式（失败即停）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
# ② 完成形式（忽略失败，使 Reactor 走到 kcg-cli）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
# Windows 工作机等价命令（供负责人在 Windows 侧原样执行）
# mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify

git diff --check
git status --short
```

## 交接记录

### 当次执行证据（2026-09-18，本机 Linux）

| 检查 | 结果 |
|---|---|
| ① 冻结形式 `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify` | **BUILD SUCCESS（exit 0）**，9 个模块全部完成 |
| ② 完成形式（加 `-Dmaven.test.failure.ignore=true`） | **BUILD SUCCESS（exit 0）**，数字与①完全一致 |
| 合计 | **554 run / 0 fail / 0 error / 5 skip**（计数取自当次 Surefire XML） |
| `git diff --check` | 无输出（exit 0） |
| `git status --short` | 78 个条目：34 modified / 2 deleted / 42 untracked；**未跟踪构建产物 0 个** |

**模块级计数（当次）**：`sir-parser` 43、`sir-semantic` 103、`sir-lowering-api` 4、`sir-lowering-spring-boot` 32、`sir-generator-spring-boot` 38、`sir-project-graph` 72、`sir-change` 22、`sir-toolchain-application` **210 / 5 skip**、`kcg-cli` 30。

### 已完成的关闭动作

1. **G0 完成门**：写入 `CURRENT_QUALIFICATION.md` **第 0 节**（权威结论），逐条对应阶段 7 要求的六类记录，并附 NOT_RUN / 未覆盖 / 平台条件登记表。
2. **数字统一（D1）**：报告开头与第 0 节给出唯一当前总数 **554 / 0 / 0 / 5**；1.2–1.8 的阶段性计数（374／451／465／478／542）**保留原值**并在第 0 节显式声明为"该阶段当时的计数"；第 7 节"可以说"里的陈旧 465 已更正 554，"不可以说"清单也改为与 1.6 的 QUALIFIED 事实一致。
3. **1.1 证据冲突裁决（D2）**：`implementation-baseline.md` 第 5 节加入**裁决补记**（不修改原始记录），指出其失败观察属 2026-09-08 基点 `5bba6ea` 的带日期快照、2026-09-18 复跑未复现，并说明该文件"conformance 包被 POM 排除"的旧描述已在 Q4+Q5 失效；`CURRENT_QUALIFICATION.md` 1.1 加入**反向指针**，两侧互相指向。
4. **残余风险对账**：第 6 节第 5 项（故障矩阵未形成）与第 7 项（构建冲突未裁决）标记为已闭合/已裁决，第 6 项（CLI 生命周期与发行形态）改写为与登记表一致的实际状态。
5. **可提交状态**：无临时探针、无未跟踪构建产物、`git diff --check` 无输出；提交内容清单与建议提交信息见下。
6. **当前状态文档一致性**：`README.md`、`PROJECT_STATUS.md`、`PROJECT_OWNER_GUIDE.md`、`CURRENT_QUALIFICATION.md` 的**当前结论**已统一为 554/0/0/5 与 QUALIFIED；所有历史阶段数字均带“该阶段计数”标注；`PROJECT_STATUS.md` 第 6 节的六条优先工作已逐条标注完成状态。

### 提交内容清单（供负责人手动提交，执行者未提交、未推送）

**生产代码（3 个文件，全部在已完成工作单的授权范围内）**：

| 文件 | 改动 | 来源 |
|---|---|---|
| `kcg-cli/src/main/java/io/kcg/cli/CliCommandLine.java` | 已知选项映射改为按声明顺序的 `LinkedHashMap`，修复"同一条不完整命令在不同进程报出不同缺失项" | Q7（D7） |
| `sir-toolchain-application/src/main/java/io/kcg/sir/application/internal/state/SafeTargetResolver.java` | 诊断文本改为含 `symlink`，与同类其余消息一致 | Q3（Part B） |
| `sir-toolchain-application/src/main/java/io/kcg/sir/application/internal/PathGuard.java` | 目标叶子符号链接不再被当作 "target chain"，使专为叶子写的 `CONFLICT-001/002` 分支可达 | Q3（Part B） |

**测试与 fixtures**：`sir-toolchain-application/src/test` 46 个条目（含 conformance 包 28 个）、`kcg-cli/src/test` 7 个、`sir-generator-spring-boot/src/test` 3 个、`sir-project-graph/src/test` 1 个、`sir-toolchain-application/pom.xml` 1 个（删除两处 conformance 包排除）。

**文档**：`docs/roadmap` 9 个（含 6 份归档工作单）、`docs/qualification` 2 个、`docs/design/implementation-baseline.md`、`docs/PROJECT_STATUS.md`、`docs/PROJECT_OWNER_GUIDE.md`。

**建议的提交信息（D5，供直接使用或修改）**：

```
G0 资格收口：Q1–Q7 六张工作单（生成器边界、Project Graph 契约、Change fixtures 与软链接收口、
conformance 恢复与真实 MySQL 矩阵、文件事务故障矩阵、CLI 只读边界）

- 全量：冻结与完成两种形式各跑一次，9 模块 BUILD SUCCESS，合计 554 run / 0 fail / 0 error / 5 skip
- 外部 MySQL 8.4.11 conformance 矩阵 QUALIFIED（五场景全通过，两次可复现）
- 生产代码仅 3 处：CLI 用法诊断确定性（Q7）、软链接诊断文本与叶子冲突分支可达性（Q3）
- 资格结论与残余缺口登记见 docs/qualification/CURRENT_QUALIFICATION.md 第 0 节
```

### 负责人自理事项（执行者未处置，避免越权）

1. **`AGENTS.md` 被删除（`D`），并出现两个未跟踪文件 `AGENT.md`、`MAIN.md`**（mtime 2026-09-18 13:43/13:47，不是本工作单产生的改动）。按"保留用户工作区原状"的规则，**我没有读取、修改或暂存它们**。若这是你有意做的文档重组，提交时请自行 `git add` 相应文件；若 `AGENT.md`/`MAIN.md` 应取代 `AGENTS.md`，也请告知是否需要我据此更新仓库内对 `AGENTS.md` 的引用（当前文档多处引用该文件名）。
2. **Windows 侧证据**：本机不可产生，请在 Windows 工作机原样执行
   `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify`
   并把结果回写第 0 节登记表；未回写前 Windows 侧保持 `NOT_RUN`。
3. **提交与推送**：按你的指示由你手动完成。
