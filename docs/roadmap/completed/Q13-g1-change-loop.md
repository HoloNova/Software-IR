# 当前工作单：Q13 G1 变更闭环——课程切片的 SIR 变更（加能力 / 改约束 / 删能力）与真实环境行为验证

- 状态：**`DONE`**（2026-09-23 验收：GitHub CI run `35854153833` 两条全量闸门 BUILD SUCCESS、合计 775 / 0 / 0 / 5，四个业务场景 IT 全部 `PASSED`（Q9 40/0、Q10 61/0、Q11 36/0、Q13 63/0）；原状态 `AWAITING_CI`（2026-09-23：负责人确认实施内容；C5 修正 + 契约层 6/6 + 变更闭环 IT 63 项检查 0 失败 `PASSED`。门 9（Q9/Q10/Q11 同树回归）与门 10（两条全量闸门）改由 **GitHub CI** 在推送后一次跑完——负责人 2026-09-23 澄清"集中一次"指 CI，不是本机再跑；CI 结果出来后回填本单与资格报告，若为红则本单回到 `IN_PROGRESS`）
- 本单 2026-09-23 曾被阻断两次并按 D6 停轮报告；两次解除都走了"独立小单 + 负责人批准"的流程（[`completed/Q14-change-layer-projection-parity.md`](completed/Q14-change-layer-projection-parity.md)、[`completed/Q15-entity-artifact-decoupling.md`](completed/Q15-entity-artifact-decoupling.md)）。
- 所属阶段：**G1：单文件课程业务切片**（第四张工作单；G0 完成门见 `docs/qualification/CURRENT_QUALIFICATION.md` 第 0 节）
- 前置状态：Q9、Q10、Q11 已完成、验收并归档；全量 **749 / 0 / 0 / 5**（2026-09-23）
- 方向来源：`docs/design/07-validation-and-direction-roadmap.md` §8 的 **G1 行"交付与依赖"栏**——"在 G0 上先一个实体及查询，再扩展 CRUD、分页、校验、关联；**继续生成 SIR/修改 SIR**"；`docs/design/03-compiler-and-generated-backend.md`（变更计划、文件事务、基线注册）；`docs/design/02-sir-language-and-modules.md` §10（HTTP 契约）
- 编号说明：**Q12 已登记为"路由模板与资源式路由命名"**（Q10 的 D2/D16），本单不占用该编号
- 版本快照：按既有规则，执行期间不提交 Git；开工前本单从 `SPEC_REVIEW` 进入 `IN_PROGRESS`

## 为什么需要这一单

G1 的完成门是"BIZ-01..06 的基础业务与反例，GEN-01/02；真实 MySQL 中验证 HTTP 和数据结果"。BIZ-01..06 已分别由 Q9（BIZ-05）、Q10（BIZ-01..04）、Q11（BIZ-06）覆盖并验收。但 G1 那一行的交付范围还写了"**继续生成 SIR/修改 SIR**"，而今天这条只有**计划与文件事务层**的证据：

- G0 的五个 conformance 场景里，`APPLY-UPDATE`/`APPLY-CREATE`/`APPLY-DELETE` 确实跑了"注册基线 → 计划 → 应用 → 盘面校验"，但它们用的是 **campus-market fixtures**，而且校验到文件集合、摘要与清单为止——**没有一条在课程切片上、也没有一条断言变更后的业务行为**。
- Q3 的 13 个 change fixture 与 CLI 的七个变更工作流（`v01`–`v06`）证明的是"计划正确"，`ChangePlanningApplicationTest` 明确断言"零磁盘写入"（计划阶段不落盘）；Q6 的 64 项故障矩阵证明的是文件事务的失败/恢复语义。
- 三个业务 IT（Q9/Q10/Q11）都是**固定输入跑一次**：生成 → 构建 → 启动 → 断言。没有任何一条场景能回答"改了 SIR 之后，已经上线的那个工程会变成什么样"。

这一单把这条补上：**在课程切片上做三轮真实的 SIR 变更（加能力 → 改约束 → 删能力），每轮都验证"增量应用的结果与同输入从零生成的结果逐字节一致"，并在真实 MySQL + HTTP 上验证行为变化**（新能力可用、约束收紧可见、被删能力消失且支撑产物仍在）。

## 三条设计原则对照（负责人 2026-09-23 明确）

1. **新业务是否主要靠组合已有能力？** 是。本单**不新增任何 SIR 语法**，三轮变更全部由已有的六种 `ChangeOperation`（`AddCapability`、`RemoveCapability`、`ModifyCapabilityWorkflow`、`ModifyInputFieldConstraints`、`ModifyUnreferencedInputFieldType`、`ModifyActorlessReadonlyCapabilityExposure`）与已有的能力集组合而成；基线与候选都取自 Q10/Q11 已验收的能力。
2. **一个普通需求在 SIR 中是否变得绕口？** 不变。候选 SIR 就是正常书写的业务源文件，变更语义由 `ChangeSet` 声明（如"加一个能力"），不需要在 SIR 里写"这是第几个版本"。
3. **修复一种实现问题，能否让一类业务共同受益？** 本单的核心不变量（增量结果 == 从零生成结果 + 支撑产物无条件存在）对**所有**未来的变更都成立，而不是只对本场景成立；它正是 Q10 订正 C7（支撑文件必须是无条件 target 产物）在业务层的回归钉。

## 开工前必须实测的三件事（不允许假设）

| # | 待测事实 | 为什么必须测 | 影响 |
|---|---|---|---|
| P1 | `AddCapability` 是否允许候选**带来新的声明**（新的 `input`/`view`/`enum`/`entity`） | 现有 `APPLY-CREATE` 证据只加了一个复用既有 `input` 的能力（`campus-market-minimal-add-search-goods.sir`：diff 只有 capability 块）；`sir-change` 的 `ChangeSet` 只允许**恰好一个操作**，若"带新声明"不被接受，R1 必须换成复用既有声明的能力 | 决定 R1 的候选形态（见 D2 与 R1a/R1b） |
| P2 | 删除**最后一个写能力**时是否会撞 `SIR-CHANGE-IMPACT-203` | Q10 的 C7 让支撑产物（错误信封/异常基类/advice/校验原语/`application.yml`）无条件生成，但那条证据来自 `DeleteFaultMatrixTest` 的失败现场，**没有**一次真实的"删掉最后一个写能力"的 apply 成功记录 | 决定 R3 是否成立；若失败即为真实缺陷，按订正记录处理 |
| P3 | 生成工程的盘面里，哪些路径属于工具链状态、哪些属于构建输出 | "增量结果 == 从零生成结果"的比对必须显式列出排除项（预期为构建输出 `target/**` 与工具链状态文件/基线快照；必须实测确认，不能假定） | 决定 D3 的比对口径与报告中的排除清单 |

三项都在 T1 完成并写入本单的"实施期订正"节；**P1 的结果决定 R1 用 R1a 还是 R1b**，其余两轮不变。

## 场景定义

**基线（base）**：`sir-toolchain-application/src/test/resources/valid/course-admin.sir`（Q10 已验收：`Course` 含 `description`/`version versioned`，能力 `GetCourse`、`CreateCourse`、`UpdateCourse`，错误 `CourseNotFound(404)`/`EmptyChange`/`StaleVersion(409)`）。

**三轮变更（同一个落盘工程根，顺序执行）**：

- **R1 加能力（CREATE family）**
  - R1a（P1 为"允许带新声明"时）：候选 = base + 关联检索切片——`enum EnrollmentStatus`、`entity Student`、`entity Enrollment`、`view StudentSummary`、`view EnrollmentSummary`、`view CourseEnrollmentItem`（含 `List<EnrollmentSummary>`）、`input SearchCourseEnrollmentsInput`、`error InvalidPage`、`capability SearchCourseEnrollments`（Q11 已验收的能力原文）。业务叙述：给已上线的课程管理服务加一个"按报名状态查课程"的检索页。
  - R1b（P1 为"不允许带新声明"时）：候选 = base + 一个**只复用既有声明**的能力（分页查询课程，复用 `CourseDetail` 与 `Course`；新 `input` 若不被允许则复用既有 input 字段形态），并把"变更引入新声明"登记为独立项。
  - 声明的 `ChangeSet`：`AddCapability`（target = 新能力）。
- **R2 改约束（UPDATE family）**：候选 = R1 候选 + 把 `Course.name` 与 `CreateCourseInput.name` 的 `length(1, 100)` 收紧为 `length(1, 20)`。业务叙述：业务收紧课程名称长度。声明的 `ChangeSet`：`ModifyInputFieldConstraints`。
- **R3 删能力（DELETE family）**：候选 = R2 候选 + **删除 `CreateCourse` 与 `UpdateCourse`**（保留只剩 `GetCourse` 与检索能力）。业务叙述：下线写入口，服务转为只读查询。**按 Q3 fixture 已确立的口径，被删能力用到的 `input`/`error` 声明保留在源里**（`campus-market-two-capabilities-remove-publish-goods.sir` 的同款注释：保留声明才能让计划是"纯删除、不改动幸存产物"）。声明的 `ChangeSet`：`RemoveCapability`（两个能力各一轮，或按 P2 实测结果拆成两轮）。
- **R4 幂等重放**：对 R3 的候选再 apply 一次（用 R3 之后的新基线 id）→ 期望 `ChangeApplyResult.NoChanges`，盘面逐文件摘要不变。
  - **实施期订正（见 C5）**：R4 是"新增能力"，因此"再 apply 一次"在 target 阶段就会被拒（`SIR-CHANGE-TARGET-101`）。幂等的正确口径是"**未改动内容重新规划 → `NoChanges`**"加"**已落地的新增不能再被声明**"，两条都已断言。
- **N1 非法候选**：一个语义非法的候选（如关联投影缺分页、或 `any(...)` 引用非实体）→ 期望计划阶段失败、给出精确诊断码、**盘面逐字节不变**（不产生半成品）。
- **N2 陈旧基线**：用 R1 之后的基线 id 去 apply R3 的候选（基线已过期）→ 期望 apply 失败（context/stale 类），盘面逐字节不变。

## 变更闭环业务场景 IT（`ChangeLoopBusinessConformanceIT`）

与三个切片 IT 同构（opt-in、真实 MySQL + HTTP、证据写 `evidence/change-loop-*/change-loop-report.txt`、verdict `PASSED/FAILED/NOT_RUN`），但驱动方式不同：**一个已落盘工程根走完四轮变更**，每轮都用真实变更管线（plan → apply → 新基线）写入该根，然后**重新构建并重启这个根**（不是重新生成一份候选），再断言行为变化。

- 新增 fixtures：`conformance/mysql/change-loop-ddl.sql` 与 `-seed.sql`（合并 Q10 写侧列与 Q11 关系表；含只有 CANCELLED 报名的 ART101、无报名的 PHY101、版本为 4 的 ART101 等决定性行）。
- harness 增补（测试树内）：`projectRoot()`、`workPath(name)`、`rebuildAndRestart(runtimeUrl)`（停旧进程 → 重新构建**同一个**工程根 → 启动新 jar）。
- 每轮断言：**计划族纯度**（新增/更新/删除三选一，由 `ChangeAnalysis.Planned` 反读，不信任轮次标签）+ **manifest 与盘面一致**（逐条 `byteCount`/`sha256`）+ 行为断言（见下表）。
- 幂等与负例：由契约测试覆盖（`SIR-CHANGE-TARGET-101` / `NoChanges` / N1 拒绝 / N2 陈旧基线），IT 不重复。
- "增量 == 从零"由契约测试逐字节断言；IT 断言只有真实运行才能给出的东西。

| 轮次 | 变更 | 运行中的决定性断言 |
|---|---|---|
| R0（基线） | — | 检索只服务有 ACTIVE 报名的课程（`CS101/CS102/MAT101`）；写入口 201 可用 |
| R1 | 放宽过滤（`any(Enrollment, course == item)`） | **服务行为改变**：同一份数据下 ART101 出现（`ART101/CS101/CS102/MAT101`，total=4），PHY101 仍不出现；新建仍 201（改查询能力不影响写侧） |
| R2 | `CreateCourseInput.name` 收紧到 20 | 25 字符 → 400 且 `path=name`、落库行数不变；15 字符 → 201；检索结果不变 |
| R3a+R3b | 删除 `UpdateCourse` 与 `CreateCourse`（最后一个写能力） | 写路由不再被服务（实测状态码写进报告）、盘面无写入、检索仍可用、**非法分页仍得 400 `InvalidPage`（信封与校验原语在最后一个写能力消失后仍然存在 = Q10 C7 的运行证据）** |
| R4 | 新增 `ListCourseRefs`（复用既有声明） | 新路由服务有报名的四门课、不服务无报名课程；旧检索仍可用；写路由没有被"复活" |

## 每轮必须产出的证据

1. **计划**：`ChangeBaselinePlanningResult` 成功；记录操作种类、`FileAddition`/`FileChange`/`FileDeletion` 三个集合的**数量与被影响路径清单**；断言 family 纯（创建轮只有新增、更新轮只有改动、删除轮只有删除——依据 `PlanFamily` 的 pure 语义）。
2. **应用**：`ChangeApplyResult.Applied`（携带 `newBaselineReceipt` 与 `outputManifest`）；断言 manifest 的每个条目与盘面文件的实际 `byteCount`/`sha256` 一致，且**没有未被 manifest 覆盖的多余文件**（排除项见 P3）。
3. **增量 == 从零（核心不变量）**：把同一候选 SIR 在**另一个干净目录**里从零生成，与增量应用后的工程根逐相对路径比较 `sha256`；断言 `mismatches=0`、文件集合相同（排除项同一套）。这一条同时覆盖"没有残留旧文件"与"没有缺文件"。
4. **可构建**：离线 `mvn clean verify` 成功（`--offline`、冻结仓库）。
5. **可运行 + 行为**（每轮都做，各自一次启动）：
   - R1：新检索路由可用（`total` 按根算、根不重复、嵌套投影内容正确，复用 Q11 的断言口径）；既有写路由行为不变（创建 201 + 读取 200 + PATCH 版本递增）；数据库整行指纹符合预期。
   - R2：25 字符课程名 → 400 且字段路径点名 `name`（或 `changes.name`）、数据库不落库；15 字符 → 201；读取既有行不受影响。
   - R3：`POST /api/create-course` 与 PATCH 路由不再存在（404/405，实测后钉死期望值）；检索路由仍可用；**非法分页仍得到 400 `InvalidPage` 与同一信封**——这正是"支撑产物在删掉最后一个写能力后仍然存在"的运行证据（P2 的业务层对应断言）；数据库行数不变。
6. **数据库与只读性**：schema 从 `KCG_CONF_SCHEMA_NAME` 派生、advisory lock 取/放、每次断言的独立 JDBC 整行指纹、运行前后行数一致、`schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、schema DROP 后缺席证明、workRoot 清理。

## 新增/复用的 fixture

| 文件 | 用途 |
|---|---|
| `valid/course-admin.sir`（复用，不修改） | 基线 |
| `valid/course-admin-change-r1.sir`（新增） | R1 候选（R1a 或 R1b 形态，按 P1 结果） |
| `valid/course-admin-change-r2.sir`（新增） | R2 候选（收紧名称长度） |
| `valid/course-admin-change-r3.sir`（新增） | R3 候选（删两个写能力） |
| `conformance/mysql/course-admin-enrollment-ddl.sql`、`-seed.sql`（新增） | 合并 Q10 写侧表与 Q11 关联表的 fixture（`course` 含 `description`/`version`、`student`、`enrollment`；seed 需同时支撑写侧行为与关联检索的 total/顺序断言：若干课程 + ACTIVE/CANCELLED/无报名三种形态 + 一条用于长度断言的课程） |

## 允许范围

- 新增测试与 fixture：`sir-toolchain-application` 的 `conformance` 包内新增一个业务 IT（如 `ChangeLoopBusinessConformanceIT`）与所需辅助、`valid/` 与 `conformance/mysql/` 下的新 fixture。
- 复用既有 `BusinessSliceHarness`/`ConformanceEnvironment`/`MysqlObserver`/`HttpAssertionClient`/`SpringApplicationProcess`/`MavenProjectRunner`/`AdvisoryLockKey` 等 harness，以及 `ChangeExecutionApplication`、`ChangePlanningApplication`、`ToolchainApplication` 的公开 API。
- 若 P1/P2 暴露真实缺陷：只在**最小范围**内修复生产代码，并把缺陷、证据、影响面与行为等价性证明写入"实施期订正"节（修复需负责人确认口径，不在本单自行放宽断言）。

## 禁止范围（明确不做）

- 不新增 SIR 语法、不新增 `ChangeOperation` 种类、不改 `PlanFamily` 语义。
- 不做路由模板（Q12）、不做权限/401/403（G5）、不做数据库生命周期（INITIALIZE/UPDATE，G3）、不做多文件/持久身份（G2）。
- **不做中断/恢复注入**：那已有 Q6 的三路径故障矩阵覆盖；本单的 N1/N2 只覆盖"计划失败"与"陈旧基线"，不碰 journal 恢复。
- 不做并发 apply（两个进程同时改同一个工程根）。
- 不新增 Maven 依赖、不加 skip/exclude、不在 Renderer 里补语义逻辑。

## 测试节奏（负责人 2026-09-23 指示）

- **开发期间**：只跑受影响的**定向**测试（例如改完 fixture 先跑一次 `sir-semantic` / `sir-lowering-spring-boot` 的编译与相关测试），不重复跑全量，也不为每次小改重跑业务场景。
- **提交前一次性执行**（Q11+Q13 这一批改动定稿时）：本单的变更闭环 IT、Q9/Q10/Q11 三个业务场景回归、两条全量闸门，全部集中在那一次运行；完成门按**最终树**判定，证据绑定该树（HEAD + 未提交工作区、SIR/DDL/seed 摘要、生成工程摘要）。
- 若中途出现需要立即定位的真实失败（而非预期内的小步重跑），按需要跑定向测试定位；这类运行不算“重复全量”。

## 定向验证命令

```bash
# 开发期间（按需，只跑受影响的模块）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-semantic,sir-lowering-spring-boot,sir-generator-spring-boot,sir-change -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-toolchain-application -am -DskipTests test-compile

# 提交前一次性：本单场景（opt-in；先恢复参考环境，schema 从 KCG_CONF_SCHEMA_NAME 派生）
source /root/kcg-conformance/env.sh
mvn -B -Dmaven.repo.local=/root/.m2/repository -o \
  -pl sir-toolchain-application -am \
  -Dtest=io.kcg.sir.application.conformance.ChangeLoopBusinessConformanceIT \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false \
  -Dkcg.change-loop-conformance.enabled=true \
  -Dkcg.conformance.work-parent=/root/kcg-conformance/work \
  -Dkcg.conformance.evidence-parent=/root/kcg-conformance/evidence \
  -Dkcg.conformance.maven-executable=/usr/bin/mvn \
  -Dkcg.conformance.maven-repo=/root/.m2/repository test

# 提交前一次性：Q9/Q10/Q11 三个业务场景回归（同样的 -Dkcg.conformance.* 参数，换 -Dtest 与 enabled 属性）

# 提交前一次性：两条全量闸门（离线、单构建、无 -T；仓库无 CI workflow，故本机执行；若负责人先行建立 CI 则优先取 CI 证据）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

## 完成门

1. **P1–P3 已实测并写入工作单**：P1 决定 R1 形态；P2 给出"删除最后一个写能力"的 apply 结论；P3 给出比对排除清单。
2. **三轮计划的 family 纯净**：R1 只有新增、R2 只有改动、R3 只有删除；每轮记录被影响路径清单，并有配对断言（例如 R2 中被改文件数 = 预期、且无新增/删除）。
3. **核心不变量**：每轮"增量应用结果 == 同输入从零生成结果"，逐相对路径 `sha256` 比较 `mismatches=0`，文件集合相同（排除项显式列出）。
4. **清单一致**：`Applied.outputManifest` 与盘面逐条一致（`byteCount`/`sha256`），且盘面无 manifest 之外的多余文件。
5. **可构建**：三轮变更后的工程都能离线 `mvn clean verify` 成功（或按 D4 的退化口径，在成本与证据之间取负责人批准的那一档，且必须覆盖 R1 与 R3）。
6. **行为证据**：R1 新路由可用且既有写路由行为不变；R2 收紧后的约束在 HTTP 上可见（400 + 字段路径 + 不落库）；R3 被删路由消失、检索仍可用、**非法分页仍得 400 `InvalidPage`**（支撑产物仍在的运行证据）。
7. **幂等与拒绝**：R4 得 `NoChanges` 且盘面摘要不变；N1 计划失败且给出精确码、盘面不变；N2 陈旧基线失败、盘面不变。
8. **真实环境元数据**：`schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、advisory lock 取放、schema DROP 后缺席证明、workRoot 清理；证据文件与 Q9/Q10/Q11 同级（`evidence/change-loop-<sha>-<pid>/change-loop-report.txt`）。
9. **同树回归**：Q9 40/40、Q10 61/61、Q11 36/36 全部重跑通过（`WorkflowRenderer`/变更路径未被破坏）。
10. **两条全量闸门 BUILD SUCCESS**，与基线 **749 / 0 / 0 / 5** 的差量逐模块归因（新增测试必须全部来自本单）；**不得新增 skip 或 exclude**。
11. **文档同步**：资格报告新节（含有效边界）、覆盖清单新组、`PROJECT_STATUS.md`、路线图镜像；`git diff --check` 无输出。
12. **生产代码改动为零**（若因 P1/P2 暴露缺陷而必须修复，则以"实施期订正"记录并单独评审，不计入本单的默认结论）。

## 验收记录（2026-09-23）

- **验收依据**：负责人 2026-09-23 回复"确认提交"，并在同一轮澄清重活由 **GitHub CI** 承担（不是本机重跑）；实施内容此前已由负责人"确认 q13"。
- **验证位置与结果**：GitHub CI run `35854153833`（commit `1218a31`，`main`）——
  - 冻结形式 `mvn -B -o clean verify`：**BUILD SUCCESS**（十模块）
  - 完成形式 `mvn -B -o clean verify -Dmaven.test.failure.ignore=true`：**BUILD SUCCESS**，合计 **775 run / 0 fail / 0 error / 5 skip**（相对 749 的 +26 = change +11（Q14）、generator +3（Q15）、application +12（Q13））
  - 四个业务场景 IT：Q9 40/0、Q10 61/0、Q11 36/0、Q13 63/0，全部 `verdict=PASSED`；证据 artifact `conformance-evidence` 内含四份报告（`schemaName=kcg_conf_run`、advisory lock 持有、`schemaSource=TEST_FIXTURE_DDL`、`generatedProjectBuild=PASSED (exit 0)`）
- **本单范围内的证据（本机取得，均为轻量）**：契约层 6/6；变更闭环 IT 63/0（105 秒，含四次生成工程构建与四次启动）
- **G1 完成门**：至此满足（BIZ-01..06、GEN-01/02、真实环境 HTTP 与数据结果、`schemaSource=TEST_FIXTURE_DDL`、"继续生成 SIR/修改 SIR"），逐条对照见资格报告 §1.12 的 G1 小节
- **归档**：`docs/roadmap/completed/Q13-g1-change-loop.md`
- **提交形态（2026-09-23 负责人指示）**：该批工作先以三个提交推送（前两个运行因 CI 工作流/计数口径问题失败），负责人要求只保留最终形态，于是三个提交与本次文档收口被**收敛为单个提交**（正文相同、仅文档差异）。上表的 run `35854153833`（commit `1218a31`）是收敛前那棵树上的 green 运行；收敛后的单提交（`1dd0e5b`）在推送上重跑同一条流水线，结果一致——run `35854950479`：两条全量闸门 BUILD SUCCESS、合计 775 / 0 / 0 / 5、四个业务场景 IT 全部 `PASSED`（查询 40/0、写侧 61/0、关联 36/0、变更闭环 63/0）。此后仅文档追加，不影响被验证的代码内容。

## 裁决记录（2026-09-23）

- **C1–C8 全部接受**：C1（P1 边界：一次操作只能带来一个新声明）、C2（关联切片作为链基线，新增轮改为复用既有声明）、C3（重放/幂等口径改为"已落地新增不可再声明 + 未改动内容重新规划为 `NoChanges`"）、C4（首次 IT 运行的 `SIR-APP-CHANGE-BASELINE-005` 为测试侧 SourceId 不一致）、C5（重放用例链形状错误，属本单自身）、C6（契约测试脚手架抽到 `ChangeChainTestSupport`）、C7（**门 9/10 改由 GitHub CI 执行**，本机不再跑重活；本机重活必须 `systemd-run --scope`）、C8（Q11 IT 的语句预算口径：只统计触及 fixture 表的语句 + "其余计数语句必须是会话探针"守卫）
- **登记为独立项**（不阻断本单）：变更影响模型是否允许操作改动幸存文件（Q15 的 R2 方案，用于覆盖 `Application.java` 的 actor 传输片段）；其余沿用此前登记的独立项（错误信封码名对齐、`SpringBootModelLowerer.java` 重写、非分页 find 的关联读取、深度 3 层以上与预算去重口径、`in`/`isNull`/when-present/按关联字段排序）

## 验证位置改为 CI（实施期订正 C7，2026-09-23 负责人指示）

- **本机不再跑重活**。负责人 2026-09-23 澄清：此前"同一批未提交改动只做一次重活"指的是**用 CI 验证**，不是在本机再跑一遍；同时要求本机任何重活都必须 `systemd-run --scope -p MemoryMax=… -p CPUQuota=…` 包裹（本机 2 vCPU / 3 GB，权威规则见 `AGENTS.md`「测试资源约束」）。
- 我先前误按"本机提交前跑一次"启动了批量（四个 IT + 两条全量闸门），收到澄清后**已立即终止**（任务被 kill，未产出任何证据）。
- 本单门 9/门 10 改由 `.github/workflows/verify.yml` 承担：一个 job 顺序执行「在线预热 → 生成工程依赖预热（在线跑一次 Q9 IT）→ 冻结形式全量闸门 → 完成形式全量闸门 → 四个业务场景 IT」，证据（`evidence/**` 与 surefire 报告）作为 artifact 上传，与 commit SHA、run URL 绑定。
- 本单已完成的两项证据仍在本机取得，且**都属轻量**：契约层测试（约 7 秒）与变更闭环 IT（105 秒，含四次生成工程构建与四次启动）；此后不再在本机重复。

## CI 首次运行的发现（实施期订正 C8，2026-09-23）

首次 CI 运行（`verify.yml`，commit `0dc7f4d`）：**两条全量闸门都 BUILD SUCCESS**，四个业务场景 IT 中 **Q9、Q10、Q13 通过**（`verdict=PASSED`），**Q11 的关联切片 IT 失败（5 项检查）**——失败全部落在"一次请求花了几条语句"的计数上：

```
FAIL a page with roots costs the count, the page and one read per association (selectCount=6)
FAIL a page of two roots costs one read per association, not one per related row (selectCount=6)
FAIL thirty related rows cost no more statements than one (selectCount=6)
FAIL a rejected page issues no read at all (selectCount=1)
```

**根因（环境归因，不是产品缺陷）**：CI 的 MySQL 把 Connector/J 的**连接级语句**记到了 run schema 上——`SELECT @@SESSION.transaction_read_only`（驱动的只读探针）与连接初始化查询 `SELECT @@SESSION.auto_increment_increment … @@license …`。本机这些语句要么记为 `SCHEMA_NAME=NULL`（不计入 schema 增量），要么发生在测量窗口之前，所以本机 `A1 reads=4`、`A8 reads=0`，CI 是 6 与 1。业务语句本身两边一致（count、page、enrollment 批读、student 批读）。

**订正**：Q11 IT 的语句预算改为只统计**触及 fixture 表**的语句（即查询计划真正发出的业务读取），并新增守卫"其余被计数的语句必须都是会话探针（digest 含 `@@`）"，原始计数与 digest 仍全部写进报告。`A8`（被拒分页不产生读取）同口径。**语义没有放宽**：多一次业务读取（N+1、额外关联读）仍会触及 fixture 表并被计数，正是这些断言要抓的东西。

另修一处 CI 工件问题：证据目录改名为非隐藏的 `ci-evidence`（`actions/upload-artifact` 默认跳过隐藏文件，原先的 `.ci-evidence` 因此上传为空），并显式 `include-hidden-files: true` 兜底。

## 结果与证据（2026-09-23）

### 契约层（`ChangeLoopPlanningContractTest`，6/6 通过）

| 用例 | 断言 |
|---|---|
| P1 边界 | 一次操作不得带来多个新声明（`SIR-CHANGE-SCOPE-101`）；新增必须追加在声明块末尾 |
| 完整链 | R1（放宽过滤）→ R2（收紧输入约束）→ R3a/R3b（删两个写能力）→ R4（新增复用声明），每轮计划族纯（`assertPureUpdate/Creation/Deletion`）且 apply 携带 `FILES_AND_BASELINE` |
| 支撑产物 | 删除最后一个写能力那一轮的删除集合里**没有**任何信封/advice/校验原语/`application.yml`，且之后它们仍在盘上（Q10 C7 的结构钉） |
| 核心不变量 | 增量应用后的工程根与"同一候选从零生成"**逐相对路径、逐字节相同**（文件集合 + 内容双断言） |
| 幂等与拒绝 | 已落地新增再声明 → `SIR-CHANGE-TARGET-101`；未改动内容重新规划 → `NoChanges`；N1 目标拒绝的候选 → 计划失败且盘面逐字节不变；N2 陈旧基线 → apply 失败且盘面不变 |

### 真实环境（`ChangeLoopBusinessConformanceIT`，63 项检查 / 0 失败 / `PASSED`）

证据：`/root/kcg-conformance/evidence/change-loop-1a0cdeeee14-1428/change-loop-report.txt`
（`schemaName=kcg_conf_run`、`advisoryLock=held during the run`、`schemaSource=TEST_FIXTURE_DDL`、`productInitializeImplemented=false`、`productUpdateImplemented=false`、基线 SIR sha256 `e56343cf…`、fixture DDL `599b7bf5…`、seed `bfcb0c1a…`、`generatedFiles=35`、`generatedProjectBuild=PASSED (exit 0)`、schema DROP 后缺席证明与锁释放均已记录）

- **一个落盘工程根走完四轮**：每轮 plan → apply → **重新构建同一个工程根**（`mvn clean verify` exit 0，共 4 次）→ 重启（旧进程 `stopped=true`）→ HTTP 断言。计划族纯度与 manifest-vs-盘面一致性每轮都断言（如 R3b：`deletions=2`、manifest 31 条）。
- **R1 的行为变化是决定性的**：同一份数据、同一个服务，只有 SIR 变了 —— 基线 `total=3`、codes `[CS101,CS102,MAT101]`；R1 后 `total=4`、codes `[ART101,CS101,CS102,MAT101]`（ART101 只有 CANCELLED 报名；PHY101 无报名仍不出现）；新建仍 201。
- **R2**：25 字符 → `400 {"code":"INVALID_REQUEST", fields:[{path:"name", code:"Size", message:"size must be between 1 and 20"}]}`、落库行数不变；15 字符 → 201；检索结果不变。
- **R3**（删掉最后一个写能力）：`POST /api/create-course` 与 `PATCH /api/update-course` 均 **404**、无写入、检索仍 200 且结果不变；`?page=0` 仍得 `400 {"code":"InvalidPage",...}` —— **Q10 C7 的运行证据**（信封与校验原语在最后一个写能力消失后仍然存在）。
- **R4**：新路由 `/api/list-course-refs` 200 且服务有报名的四门课（不服务 PHY101），旧检索仍可用，写路由没有被"复活"。
- 收尾：行数 5 → 8（本轮创建的 3 行）、schema DROP 并证明缺席、advisory lock 释放。

### 完成门状态

1–8 与 12 已满足（**生产代码改动为零**：本单只新增测试、fixture 与文档；两处缺陷分别由 Q14、Q15 按独立工作单修复）。**门 9（Q9/Q10/Q11 同树回归）与门 10（两条全量闸门）在 CI 上执行**（`verify.yml`，与四个 IT 共用一次运行）；结果出来前不声称门 9/10 为绿。门 11 的文档同步（资格报告新节、覆盖清单新组）在 CI 结果出来后一次补齐，避免写进占位数字。

## 失败 / 跳过 / NOT_RUN 口径

- 定向或全量出现任何 fail/error（除既有 5 项 Windows junction skip 外）即本单未完成；不得以降 skip、加 exclude、改断言口径的方式"通过"。
- 参考环境不可用（容器/端口/凭据/schema 锁不可得）时，阶段 2 及其后的门禁记为 **`NOT_RUN`**，此时完成门 5–8 不成立；`NOT_RUN` 必须与通过明确区分。
- P2 若发现"删除最后一个写能力"仍被 `SIR-CHANGE-IMPACT-203` 拒绝：**不得**通过放宽规则或删断言来"通过"，按订正协议记录为真实缺陷并停止该轮，报告后再决定修复口径。
- 若某轮的计划与预期 family 不符（例如改约束导致新增文件），先按事实记录，再判断是候选设计问题还是计划器缺陷；不得为了凑断言而裁剪候选。

## 需要负责人裁决的设计选择（D0–D6）

| # | 决策 | 推荐 | 备选与代价 |
|---|---|---|---|
| D0 | 变更闭环是否单独成单（不并入 Q12 路由模板） | **单独成单**：它是 G1 门禁"修改 SIR"的业务证据，与路由模板无依赖关系 | 合并：单张更大，且路由模板不在 G1 门禁内，容易混淆验收边界 |
| D1 | 基线取哪套能力 | **Q10 的 `course-admin.sir`**：它有写侧行为（201/200/409/400）可观察，且第 3 轮删写能力正好考验 C7 | 取 Q11 的关联切片作基线：只有读路径，删能力轮无法检验"最后一个写能力"这一条 |
| D2 | R1 形态（取决于 P1 实测） | **R1a**（允许带新声明时，直接加 Q11 的关联检索能力——最接近真实业务"给已上线服务加检索页"） | R1b（不允许时退化为复用既有声明的能力），并把"变更引入新声明"登记为独立项 |
| D3 | 核心不变量的比对口径 | **逐相对路径 `sha256` + 文件集合相同**，排除项由 P3 实测确定并在报告里列出 | 只比对清单/manifest：漏掉"盘面上有清单没有的残留文件"这类问题，不采用 |
| D4 | 真实环境验证覆盖到哪几轮 | **三轮都做**（每轮各自离线构建 + 启动 + HTTP 断言）；成本约 3 次生成工程构建 | 只对 R1/R3 做完整运行、R2 只做"生成字节 + 构建"：省一次启动，但"约束收紧是否真的到达生成代码"只剩字节证据 |
| D5 | 负例范围 | **N1（非法候选）+ N2（陈旧基线）两项，均断言盘面不变**；中断/恢复交给 Q6 的既有证据 | 增加 journal 恢复场景：与 Q6 重复且需要注入中断，成本高 |
| D6 | P1/P2 暴露缺陷时的处理 | **停轮 + 记录 + 报告**，由负责人决定是否在本单内最小修复 | 自行修复并继续：会把审阅边界扩大到 `sir-change` 的规则面，不推荐 |

## 开工前实测结果（P1–P3，2026-09-23）

三项实测由 `sir-toolchain-application` 的 `ChangeLoopPlanningContractTest`（纯应用 API，不启数据库、不跑生成工程构建）承担，本轮运行：**6 项中 2 项通过、4 项因下述 BLOCK-1 中断**。

| # | 结论 | 证据 |
|---|---|---|
| P1 | **变更层拒绝"一个操作带来多个新声明"**：把整段关联切片交给 `AddCapability` 失败于 `SIR-CHANGE-SCOPE-101`，消息为 `candidate must add exactly one new declaration; got 9 (target: sir://CourseAdmin/capability/SearchCourseEnrollments)` | `PlannerCore` 的 scope 检查（`newCount != 1`）；测试 `aChangeCannotBringNewDeclarationsWithACapability` 现在把它钉为该码的回归 |
| P1b | 追加式新增可行但**有前提**：新增声明必须放在声明块末尾，否则既有声明的相对顺序变化同样触发 `SIR-CHANGE-SCOPE-101`（`existing declaration SymbolId order changed after removing target`）。`SymbolId` 是名字派生的（`sir://<software>/<kind>/<name>`），因此该检查比较的是**声明序列的相对顺序**，不是 id 值 | 首轮把新能力插在中间时的实测消息；`SymbolIdFactory` 源码 |
| P2 / P3 | **未测得**：所有针对该基线的计划路径都在 P1 之后的投影阶段崩溃（见 BLOCK-1），R3a/R3b 删除轮与"增量 == 从零"的比较都还没被执行到 |

P1 的结论已按 D2 的回退条款落进设计：**关联切片属于链的基线，而不是变更带来的东西**（新基线 fixture `valid/course-admin-enrollment.sir`），新增轮改为"只复用既有声明的单个新能力"。

## 阻断项 BLOCK-2：删除版本化写能力会改动幸存文件，计划被拒（2026-09-23 Q14 之后实测）

**现象**：R3a（删除 `UpdateCourse`）的计划失败于

```
SIR-CHANGE-IMPACT-202: survivor ProjectFile bytes changed for
  src/main/java/com/example/courseadmin/persistence/CourseMapper.java
  (base sha=17d529f3…, candidate sha=641775f0…)
```

**原因（实测，不是推断）**：把基线与候选各自从零生成后对比该文件，候选少了 12 行——正是乐观锁的两个辅助方法与其注释：

```
@Select("SELECT * FROM course WHERE id = #{id} FOR UPDATE")
Course selectByIdForUpdate(@Param("id") Long id);

@Update("UPDATE course SET …, version = version + 1 WHERE id = #{candidate.id} AND version = #{expectedVersion}")
int updateIfVersionMatches(@Param("candidate") Course candidate, @Param("expectedVersion") long expectedVersion);
```

也就是说：**生成器把"条件更新所需的方法"发射在 `CourseMapper` 里，依据是当前能力集合里有能力做条件更新**；删掉最后一个这样的能力，`Course` 实体没变、但它的 mapper 文件变了。变更层的影响模型是"删除能力只删该能力自己的产物、幸存文件不得变化"（这正是 Q3 的删除 fixture 特意回避的情形，fixture 注释里写明了这一点），于是计划被拒。检查在首个不匹配处停止，可能还有其它幸存文件同样受影响。

**影响面**：G1 的门禁"继续生成 SIR/修改 SIR"里，**删除写能力**这一整类变更今天不成立；Q10 C7 建立的那条结论（"支撑产物必须是无条件产物"）在**能力依赖片段**上还没有对应的处理。

### 待裁决（BLOCK-2 解除方式）

| # | 选项 | 代价 / 后果 |
|---|---|---|
| R1 | **把实体级片段从"能力集合决定"改为"实体声明决定"**：mapper 的乐观锁辅助方法只要实体有 `versioned` 字段就无条件发射（与 Q10 C7 的"支撑产物无条件"同一思路），删除能力就仍是纯删除 | 改动落在生成器/降层；代价是当前无写能力的工程里也保留这两个方法（与 C7 接受的是同类代价）；对**一类**此类片段（application、advice 等）也适用，但需要逐个确认 |
| R2 | **让影响模型承认"操作可以改动幸存文件"**：`RemoveCapability` 计划允许出现覆盖该幸存 artifact 的 `ArtifactChange`/`FileChange`，`SIR-CHANGE-IMPACT-202` 只对**未声明**的改动报错 | 语义更正确、覆盖面更广；但要改 planner 的 impact 模型与 scope 校验，工作量明显更大 |
| R3 | **Q13 的删除轮改为不触碰幸存文件的切片**（例如只删纯查询能力），并把该限制登记为缺口 | 成本最低；但 G1 的"删除最后一个写能力"就没有业务级证据，C7 的业务层回归钉随之取消 |

**倾向 R1**：与本项目已有的 C7 结论一致（"支撑/实体级产物不随能力集合变化"），改动面小，且能让"删除能力 = 纯删除"这个语义对**所有**未来的删除保持不变；R2 更彻底但属于影响模型重做，适合作为后续独立项。

**解除记录（2026-09-23，Q15 完成后）**：负责人确认按 **R1** 处理，由 **Q15** 落地（`VersionSpec` 携带版本增量；`MapperRenderer` 改为实体驱动，SET 列表取实体变更列；不变性探针证明同一实体在有/无条件更新能力时 mapper 逐字节相同）。重跑本单契约测试：**R1 → R2 → R3a（删 `UpdateCourse`）→ R3b（删最后一个写能力）→ R4 全部 apply 成功**，去重后的支撑产物与实体级产物都存活，且"增量应用结果 == 从零生成结果"（逐相对路径、逐字节）成立——BLOCK-2 解除。R2 保留为后续独立项（若未来出现其它"能力依赖片段"再评估）。

## 实施期订正（C1–C6）

- **C1（对应 D2 回退）**：R1 不是"加关联检索能力"，而是把关联切片作为**基线**的一部分；新增轮（R4）改为补一个只复用既有声明、且**追加在声明块末尾**的能力（`ListCourseRefs`，输出 `List<Ref<Course>>`），业务叙述为"给只读服务加一个轻量引用列表端点"。
- **C2**：`ChangeSet.basedOn` 必须是**当前基线**的修订（`baseSourceSha256Hex` + 图摘要），不是候选自己的修订；用候选修订会让每次 apply 都失败于 `SIR-APP-CHANGE-BASELINE-006`（实现期第一次运行即暴露，已修正并写入测试注释）。
- **C3**：N1 的原始形态（"非分页 find + 视图输出"）**经 SIR 不可表达**——语义层先以"返回类型不匹配"拒绝（`output List<view>` 不是合法输出形态），所以 Q11 的 C5 检查对这条路径是防御性的。N1 改用可经 SIR 到达的目标边界违规：把 create 能力的响应视图换成带嵌套关联的视图（`CourseEnrollmentItem`），声明为 `ModifyCapabilityWorkflow`。
- **C4**：`Chain` 的删除轮候选沿用 Q3 约定——被删能力的 `input`/`error` 声明保留在源里，保证计划是纯删除。
- **C5（已修，属本单自身）**：`replayingAnAppliedCandidateReportsNoChangesAndLeavesTheProjectAlone` 目前在**未做删除的基线上**重放 R4 候选，成了"加一个又删两个"的混合增量，于是以 `SIR-CHANGE-SCOPE-101`（`existing declaration SymbolId order changed after removing target`）失败——这是我写测试时的链形状错误，不是产品缺陷；恢复本单时把它改成在完整链之后再重放。
- **C6**：契约测试的公共脚手架已抽到 `ChangeChainTestSupport`（`sir-toolchain-application` 测试树），Q13 与 Q14 的行为测试共用同一套真实变更管线驱动。（`sir-toolchain-application` 测试树），Q13 与 Q14 的行为测试共用同一套真实变更管线驱动。

## 阻断项 BLOCK-1：变更层无法投影 Q10/Q11 的语法（2026-09-23 实测）

**现象**：任何针对含 `.present`（Q10）或 `any(...)`（Q11）的源的计划调用都抛出

```
java.lang.IllegalStateException: unsupported NormalizedExpression variant:
    class io.kcg.sir.semantic.model.NormalizedExpression$PresentExpression
```

抛出点：`sir-change/src/main/java/io/kcg/sir/change/internal/SemanticProjection.java:304`（`expressionP` 的兜底 `throw`）。触发者是 `UpdateCourse` 工作流里的 `validate input.name.present or ... else EmptyChange;`（Q10 的三态 PATCH 语法）；`NormalizedExpression` 一共 12 个变体，`expressionP` 只处理 10 个，缺 **`PresentExpression`（Q10）** 与 **`ExistsExpression`（Q11）**。四个独立测试（R1 计划、N1 计划、链、陈旧基线）都在 ~0.3 s 内以同一异常中断，说明它不依赖具体操作种类：计划阶段比较声明投影时（`PlannerCore` 遍历全部声明的 `ofFullDeclaration`）就会撞上。

**影响面**：
1. **Q13 自身**：P2/P3 与整条链无法执行——针对 Q9–Q11 已验收切片的任何变更计划都不可用。
2. **G1 完成门**：门禁里"继续生成 SIR/修改 SIR"这一句，今天在**我们刚验收的那三个切片上并不成立**；现有变更证据（G0 五场景、Q3 fixture、CLI 工作流）全部建立在 campus-market 那套**早于 Q9** 的源上，没有一条覆盖 `containsLiteral`/`.present`/`any(...)`。
3. **前向风险**：兜底 `throw` 意味着**每个新表达式变体都会让变更层崩溃**，而不是给出诊断；Q9 的 `containsLiteral` 之所以没暴露问题，是因为它是 `BinaryExpression` 的一个运算符。

**同一文件里的三处"静默盲区"**（结构事实，非猜测——投影记录里没有这些字段，而 `PlannerCore` 用投影相等判断"声明是否变化"）：
- `EntityP`/`FieldP` 不含 Q10 的 `versioned` 事实；
- `InputP` 不含 Q10 的 `patch of Entity` 规格；
- `ViewFieldP` 不含 Q11 的嵌套关联目标（`student: StudentSummary`）。

也就是说：只改这三类事实的候选会被判为"声明未变化"。

**按 D6 处置**：停轮、记录、报告，不自行改生产代码。需要负责人决定的口径见下。

**解除记录（2026-09-23，Q14 完成后）**：BLOCK-1 已由 **Q14** 解除——`SemanticProjection` 现覆盖 `.present`、`any(...)`、`Page<...>` 与五处盲区，并用语言面闸门防止再次漂移。重跑本单的契约测试：**R1（把检索过滤从"有 ACTIVE 报名"放宽为"有过报名"）与 R2（收紧 `CreateCourseInput.name`）现在都能计划并 apply**——这正是 Q14 的行为证据。R3a/R3b/R4 仍不能完成，原因**不再是投影**，而是下面新记录的 BLOCK-2。

### 待裁决（BLOCK-1 解除方式）

| # | 选项 | 代价 / 后果 |
|---|---|---|
| B1 | **先立一张"变更层与 Q9–Q11 语言面对齐"的小单**（`SemanticProjection` 补齐两个表达式变体 + 三处盲区，配针对性测试），完成后回到 Q13 继续 | 改动集中在 `sir-change` 一个文件与它的测试；Q13 的生产代码改动默认仍为零。这是唯一能让 G1"修改 SIR"在真实切片上成立的路径 |
| B2 | 把 Q13 的基线换成**不含 Q10/Q11 语法**的源（例如 Q9 之前的 campus-market 变体） | 能在不碰生产代码的前提下跑完一条链，但 G1 的"修改 SIR"仍然只在旧语言面上被证明；需要同时把这一限制写进 G1 完成门 |
| B3 | 接受现状、把"变更层不支持 Q10/Q11 语法"登记为已知缺口，Q13 整体记 `NOT_RUN` | G1 无法按字面关闭；缺口进入路线图 |

**倾向 B1（已采纳：Q14 已完成）**：它正是这张单存在的意义（把"改 SIR"证到真实切片上），而且改动面小、可测、对后续所有变更共同受益。

## 实施期订正协议

执行中若发现推荐方案与冻结依赖、既有契约或参考环境冲突，按 Q9/Q10/Q11 的做法处理：**保持原意图、记录证据、写入本单的"实施期订正"节，并继续保持禁止范围不变**；不得为让用例通过而放宽完成门。

## 开工前的准备

1. 恢复参考环境（MySQL 8.4.11 容器、`/root/kcg-conformance/env.sh` 凭据、advisory lock）。
2. 记录开工基线（`git status --short`、全量 749/0/0/5 的既有证据引用、当前 HEAD）。
3. 完成 P1–P3 三项实测，并把结果写入本单后再开始 R1。

## 交接记录

**状态：`IN_PROGRESS` · 已暂停（2026-09-23，等待 BLOCK-1 解除方式）。** 负责人 2026-09-23 选择方案 B（"先补一张 G1 变更闭环单，再关闭 G1"），随后回复"确认你的方案"批准 D0–D6（含 D2 由 P1 实测决定 R1 形态、D4 三轮都做真实环境验证、D6 缺陷停轮报告），并指示同一批未提交改动只做一次提交。开工顺序：P1–P3 实测 → R1–R4 与 N1/N2 的实现与断言 → 提交前一次性跑完变更闭环 IT、Q9/Q10/Q11 回归与两条全量闸门。

**收尾说明（2026-09-23）**：BLOCK-1/BLOCK-2 解除后，本单完成了 C5 修正（幂等口径改为"已落地新增不可再声明 + 未改动内容重新规划为 `NoChanges`"）、变更闭环业务场景 IT（四轮、63 项检查通过）与两张测试类的整理。剩余的门 9/10 与文档同步按批量口径在提交前一次完成。

**暂停说明**：P1 实测（`SIR-CHANGE-SCOPE-101`，一个操作只能带来一个声明）已按 D2 回退条款落进设计；紧接着的 P2/P3 被 **BLOCK-1**（`SemanticProjection` 无法投影 `.present`/`any(...)`，直接抛 `IllegalStateException`）拦住。按 D6 停轮报告，等待负责人在 B1/B2/B3 之间裁决。当前树内 `ChangeLoopPlanningContractTest` 有 4 项因 BLOCK-1 中断——这是缺陷证据，不是既有行为的回归。
