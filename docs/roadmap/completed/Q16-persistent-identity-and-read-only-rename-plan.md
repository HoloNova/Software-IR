# 当前工作单：Q16 G2 第一切片——单文件持久身份与改名计划

- 状态：**`DONE`**（2026-09-26；负责人确认并授权全权执行后，Q16 裁决 A 的身份/拒绝/只读计划完成门据同树 CI run `36234304027` 验收：冻结与完成均 837/0/0/5，四个业务 IT 全部 PASSED；先决核查 P1–P4 已回填）
- 所属阶段：**G2：持久身份与受控模块**；本单证明单文件改名的身份与计划语义，不宣称改名已安全落盘，更不宣称 G2 或 LANG-01..05 全部门禁完成。
- 前置：G1 已验收归档；Q16 受验代码提交 `b2f5436a0c98cba69467d211b19053237ba5edf6`（`main`，已推送），CI 证据与该 SHA 绑定；此前实施阶段无自动提交/推送。

## 用一件真实事情说明问题

现有单文件 `course-admin-enrollment.sir` 中，`SearchCourseEnrollments` 同时决定声明名称、生成类名与按名称派生的 `SymbolId`。实测将源中三个出现处替换为 `SearchEnrollments`：新源能编译；变更层按旧名字找候选报 `SIR-CHANGE-TARGET-005`，按新名字找基线报 `SIR-CHANGE-TARGET-001`。另一次实测用 `ToolchainApplication` 的 `REPLACE_EXISTING` 将替换后的候选生成到同一工程根：新生成 35 个文件，但旧的 Controller/Input/Service 三个文件仍在目录；`/api/search-course-enrollments` 和 `/api/search-enrollments` 两个 Controller 同时留存。临时探针已删除，仓库无探针改动。这两项是**旧机制**的行为，不是 Q16 的安全应用能力。Q16 复审还发现该原例的全局替换会同时改名 Input 声明；一个仅针对 capability 的改名计划若漏掉 Input DTO，现改为明确拒绝（见实施证据）。

**负责人确认的总体处理方式（分 Q16 + 后续事务单交付）**：保留“复制已锁定的旧基线 → 在副本上编辑/全局替换 → 编译候选”的流程。Q16 只建立旧/新声明的非名称派生身份和经验证的改名计划，**不应用计划**；后续事务单才根据受管旧/新生成物清单，一次事务写新、更新同路径并清理失效旧文件。不能把 Q16 的计划成功说成产物已更新；不凭文本相似度猜改名，缺少同一身份的证据即拒绝。

## 已核对的实现约束（不是规划假设）

| 位置 | 今天的事实 | 对本单的约束 |
| --- | --- | --- |
| `Sir.g4`、`SymbolIdFactory` | 声明/字段无持久 `declarationId` 语法，`SymbolId` 编码软件名、种类与名称 | 改名必须让解析、绑定与规范化后的身份不随名字变化；解析不得在编译时随机分配 ID |
| `ChangeTarget`、`PlannerCore` | 一个 `declarationSymbol` 在基线与候选两边都查；改名旧/新名分别报 TARGET-005 / TARGET-001 | 先明确“同一个声明”的身份，再计划改名；不能把两个名字误认成同一个已存在的旧格式 ID |
| `ChangeOperation`、`ChangePlan` | 现有六类操作没有改名；一个 `ChangePlan` **禁止混合** UPDATE / CREATE / DELETE 文件族 | 本单建立独立类型化的**只读改名计划**，不得传给现有 apply；混合差异的一次原子应用归后续事务单，不解除既有单族不变量 |
| `ToolchainApplication` / `ConflictPolicy` | 单源读取，`REPLACE_EXISTING` 只写本次文件清单，不清理旧路径 | 直接重新生成到既有目录不能充当改名 apply；清理范围仅限受管旧清单，用户文件不可误删 |
| 设计 00/02/07 | G2 要求持久身份、显式旧格式映射、物理名称继承；G3 才负责数据库迁移 | 文件更新不等于数据库迁移；同一字段 ID 不意味着自动 `RENAME COLUMN` 或 `DROP/ADD` |

## 本单交付与边界（裁决 A）

1. **单文件身份**：能力声明与实体字段可携带持久、非名称派生的身份；名称仍用于阅读和 Resolve。复制源、全局替换名称与引用时，ID 留在候选；同名删除后重建不得盗用旧 ID。重复编译不重新分配 ID；重复 ID/错误引用有明确阶段、code、数量与 SourceSpan。未写 ID 的既有源应保持原有身份和图字节行为；语法及 ID 命名空间在实现前定稿。
2. **旧格式边界**：原有无 ID 源及旧 Bundle/Graph 可按旧规则继续使用，**不能将名字编码的旧身份静默当作新显式身份**。旧→新无一对一明确映射、映射冲突或引用闭包未证实则拒绝改名计划。本单要求可验证的拒绝语义；旧身份迁入新格式的显式映射应用不在本单交付范围，不就地改写旧 Graph/Bundle/ChangePlan。
3. **只读类型化改名计划**：仅在基线/候选的同一显式持久 ID 已证明、名称变更、artifact 身份/角色/归属一对一对应、受管旧/新清单经过验证的条件下，形成独立于现有六类 `ChangeOperation` / `ChangePlan` 的计划。计划明确区分幸存路径更新、旧路径撤销、新路径建立，集合互斥、排序稳定，并绑定两侧源字节与图摘要；三个集合必须恰好覆盖两次修订的**全工程受管文件差异**，否则拒绝，不与现有 `ChangePlan` 混族。源/图陈旧、基线受管路径冲突、无法验证的一对一映射直接拒绝；盘面已有未受管文件的冲突只能由后续应用单在执行前拒绝。**本单没有改名 apply 入口；已有 `REPLACE_EXISTING` 不得冒充它。**
4. **物理名契约**：测出改能力名后的 Java/API 路径变化，身份稳定不等于物理名称自动继承；实体字段只钉语义身份、列名继承的 Lowering 契约与当前做不到时的拒绝行为，不执行字段重命名落盘、DDL 或数据迁移（G3），不靠 Generator 猜旧列名。
5. **明确留到下一单**：一次原子应用混合路径变更、CURRENT/Journal 与恢复、旧文件清理和未受管文件保护的执行证据、应用盘面逐字节等于候选从零生成的盘面、旧 Controller/路由不留存；这些**不是 Q16 通过条件**，Q16 通过也不代表用户可以安全应用改名。

## 开工前先决核查（结果与决定回填本单，再改生产代码）

- 核对 `AstNodeId` 的生成路径与旧图规范序列化中身份的确切字段，锁定新格式/旧格式映射边界；`SymbolId` 改动不能仅在 Semantic 局部完成。
- 核对 `ChangeExecutionApplication` 与文件事务、CURRENT/Journal 对混合路径变更的原子性支持。若不存在可用路径，本单先设计只读类型化改名计划，故障恢复证据归后续事务单，**不得**直接解除 `ChangePlan` 的单族不变量。
- 核对 Lowered artifact ID、Java/API/SQL 物理名与声明名称的实际耦合及实体字段的物理列映射。若单文件字段改名不能安全继承旧列名，本单先证明身份与拒绝行为，把运行时字段改名作为后续独立单，不冒称完成。

## 测试顺序与完成门

1. 先钉现状反例：旧/新源仅做全局替换时当前的 TARGET-005/001、`REPLACE_EXISTING` 遗留旧文件，明确它们不是 Q16 的应用成功证据。
2. 语义身份探针：同 ID 改名后 SymbolId/AstNodeId 和引用 binding 稳定；同名删除重建使用新 ID；重复 ID、错误引用、旧/新身份不明时诊断准确；未加 ID 的旧源与旧图字节不变；固定输入字节确定性。
3. 计划探针：同一持久 ID 下识别改名而非删建；三种文件差异按受管清单确定、恰好覆盖全工程变更且计划排序/摘要稳定；源字节变化（即使图未变）、同 ID 重复/缺失、超出单声明的受管文件变化、基线受管路径冲突及计划后候选变化被拒，盘面始终不写。六类旧操作及 Q13 计划/文件集不变；**不得通过调用现有 apply 测计划。**
4. 开发机只跑受影响的定向测试；两条全量闸门及 Q9/Q10/Q11/Q13 业务场景 IT 在获准提交/推送后由 GitHub CI 对待验收 commit 做**一次**验证，记 SHA、run URL 与报告 artifact；不可用即 `NOT_RUN`。纯文档阶段仅检查差异，不跑构建。

**本单达标**：单文件“复制基线 → 改名候选 → 相同持久身份与正确 binding → 类型化改名计划/精准拒绝”有直接证据；未加 ID 的旧源/图行为不变，计划不会碰盘面，现有 ChangePlan 六类不变量不变。**原子应用、旧文件清理、盘面等于从零生成及故障恢复改由后续事务单验收**；多文件移动、import/binding、moduleInstanceId、nodeKey 与剩余 LANG 门禁仍未完成，不能宣告 G2 通过。

## 实施证据（2026-09-26，本机定向 + GitHub CI）

### 改动文件

生产代码（Parser / Semantic / Change 三模块；Lowering/Generator/事务生产代码未改）：

- `sir-parser`：`Sir.g4`（`@id` 注解、`entityMemberDecl` 实体成员规则）、`AstField` / `AstCapabilityDecl`（新增 `declaredId` 组件，旧构造器保留）、`SirAstBuilder`（声明路径按显式 ID 构造，`input`/`view` 字段无此路径）。
- `sir-semantic`：`SymbolIdFactory`（`declaredDeclaration`/`declaredEntityField` 与能力作用域派生工厂，旧工厂改为委托）、`DeclarationIdentity`（新，公开命名空间判定）、`ResolvePass`（显式 ID 校验/重复检测/符号与作用域构造）、`NormalizePass`（成员身份改读 resolver binding；workflow 符号改为声明符号）、`TypePass`（能力作用域取 binding，步骤作用域按作用域符号派生）。
- `sir-change`：公开 API `RenamePlanner`、`RenamePlanningInput`、`RenameRevisionSnapshot`/`RenameSourceSnapshot`（图与 UTF-8 源字节成对）、`RenamePlanRequest`、`RenamePlan`、`RenameSubject`、`RenameSubjectKind`、`RenameFileUpdate`/`Withdrawal`/`Establishment`、`RenameAnalysis`、`RenameNoChangeReason`、`RenameDiagnostic`/`RenameDiagnosticStage`；内部 `RenamePlannerCore`（全工程受管文件差异检查）、`RenamePlanDigest` 与共用 `Sha256`。
- 未改动（边界证据）：`ChangePlan`/`ChangeOperation`/`PlannerCore`/`ChangeExecutionApplication`、生成器、事务与三套 Journal、CURRENT/Bundle 格式、生成工程支撑文件。

测试：`DeclaredIdentityGrammarTest`、`DeclaredIdentitySemanticsTest`、`RenameTestFixtures`、`RenamePlannerTest`、`RenamePlanContractTest`、`RenameSourceStalenessTest`、`RenamePlanCoverageTest`、`DeclaredIdentityPhysicalNameTest`；应用层真实管道 `RenamePlanVerticalTest` + `valid/rename-course-search.sir`，以及 `LoweringTestSupport.lowerSourceSuccess`。

### 命令与结果

| 命令（本机，离线，未跑全量闸门/业务 IT） | 结果 |
| --- | --- |
| `mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-change,sir-lowering-spring-boot -am test` | BUILD SUCCESS：parser **84** / semantic **184** / lowering-api **4** / lowering-spring-boot **86** / project-graph **72** / change **69**，合计 **499**，失败 0 |
| `mvn -B -o … -pl sir-toolchain-application -am test -Dtest=RenamePlanVerticalTest -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS：**9** 例，真实 SIR → Parser/Semantic → Lowering/Generator → Graph → RenamePlanner；原课程三文件场景精确拒绝 |
| `mvn -B -o … -pl sir-toolchain-application -am test -Dtest=ToolchainDeterminismTest,ToolchainHappyPathTest -Dsurefire.failIfNoSpecifiedTests=false` | BUILD SUCCESS：**12** 例（确定性 7 + 主路径 5），旧源当前树重复生成一致 |
| 两条全量闸门 + Q9/Q10/Q11/Q13 业务场景 IT | **GitHub CI run [`36234304027`](https://github.com/HoloNova/Software-IR/actions/runs/36234304027)**，SHA `b2f5436a0c98cba69467d211b19053237ba5edf6`：冻结/完成双闸门均 BUILD SUCCESS、**837 / 0 / 0 / 5**；Q9 40/0、Q10 61/0、Q11 36/0、Q13 63/0 全部 `PASSED`；artifact `surefire-reports` + `conformance-evidence` 均已上传 |

### 完成门对照

1. 单文件“复制基线 → 改名候选 → 相同持久身份与正确 binding → 类型化改名计划/精准拒绝”有直接证据：`DeclaredIdentitySemanticsTest` 钉 symbol/AST/binding；`RenamePlannerTest` 钉合法计划与拒绝；`RenamePlanVerticalTest` 在真实生成图与文件上验证两文件能力改名可计划、计划前后盘面不变。**原课程场景的三处全局替换不能在本单单声明计划里完成**：真实工程里 3 个文件整体换路径、共 6 条受管路径差异，能力闭包只覆盖 Service/Controller 两文件；规划现在以 `SIR-RENAME-PATH-004` 精确点名旧/新 Input DTO 两条路径并拒绝，不能声称它已可应用。
2. 旧源旧身份仍按名称派生：`sourcesWithoutDeclaredIdsKeepTheirNameDerivedIdentities` 的字面断言及当前树内确定性测试通过。**跨提交比对已补齐**：G1 最终树 `6fad608` 的 CI run [`35855544574`](https://github.com/HoloNova/Software-IR/actions/runs/35855544574) 与本单 run `36234304027` 的四个业务场景报告中，Q9/Q10/Q11/Q13 的相同源 SHA 与生成文件组合 SHA 分别一致；Q13 artifact 内 6 份基线图的规范字节 SHA-256 集合逐份相同（目录名因运行态基线 ID 不同，不以目录名当内容身份）。这证明这些旧源/图在两棵 CI 树之间的字节不变；不声称所有可能旧源均已穷尽。
3. 计划不碰盘面且不可 apply：`RenamePlanContractTest`（公开面仅 `plan`/`verify`；改名源码不含 `java.nio.file`/`ChangePlanner`/`ConflictPolicy`/`REPLACE_EXISTING`/`ChangeExecutionApplication`；六类 `ChangeOperation` 仍为 6 且 `ChangePlan` 仍拒混合族；计划三集合排序/互斥/摘要确定性）。
4. **本单未达标、按裁决 A 移交下一单的门**：一次原子应用混合路径变更、旧文件清理、盘面逐字节等于从零生成、故障恢复；字段运行期列名继承与 DDL（G3）；多文件与 import/模块市场。本单任何成功结果都**不**意味着用户可以安全应用改名。

### 状态与交接

- 本单置 `DONE`：负责人确认并授权全权执行后，对提交 `b2f5436` 的 CI run `36234304027` 逐项核对双闸门、四个业务 IT 和两份 artifact，完成门已满足；本机没有重跑重活。
- 下一单（改名事务）起点：消费本单已验证的单声明 `RenamePlan` 三集合与 `RenamePlanner.verify`；V4 日志/恢复方案须在该单先实测并冻结。**能力名连带 Input 声明名一起改**属于另一个多声明改名需求，当前只有 `PATH-004` 拒绝证据，须另定身份/计划组合范围，不能由 Q17 的文件事务自动补足。

### 验收记录（2026-09-26）

- 负责人先确认推荐的裁决 A，后授权 Q16 代码提交与推送，并明确“由你全权执行”；代理仅依冻结完成门与 CI 原始证据作验收，不把历史旧 run 当本单运行。受测源码提交 `b2f5436a0c98cba69467d211b19053237ba5edf6`；GitHub CI [`36234304027`](https://github.com/HoloNova/Software-IR/actions/runs/36234304027) 的 `headSha` 与之一致。
- 冻结形式 `mvn -o clean verify` 与完成形式（加 `-Dmaven.test.failure.ignore=true`）均 **BUILD SUCCESS**，各 **837 run / 0 fail / 0 error / 5 skip**；逐模块 parser 84 / semantic 184 / lowering-api 4 / lowering-spring-boot 86 / generator 77 / project-graph 72 / change 69 / application 231（含 Windows junction 5 skip）/ cli 30。相对 G1 的 775 增 **62**：parser +5、semantic +8、lowering +4、change +36、application +9；skip 未增加。
- 四个真实 MySQL + HTTP 业务 IT 全部 `verdict=PASSED`：Q9 40/0、Q10 61/0、Q11 36/0、Q13 63/0，报告皆有 `schemaName=kcg_conf_run`、`advisoryLock=held`、`schemaSource=TEST_FIXTURE_DDL`、生成工程构建 `PASSED`。两份产物 `surefire-reports`、`conformance-evidence` 均存在且已下载核对；Q9 在线预热另有一份同值报告，不重复计入四场景。
- 旧源跨树证据：G1 最终提交 `6fad608` 的 CI run `35855544574` 与 Q16 CI 的四种业务输入源摘要及生成文件组合摘要一致，Q13 六份基线图规范字节摘要多重集也一致；本结论限这批 fixture，不外推到所有未枚举旧源。
- 原课程例子的“同时改能力与 Input”仍**拒绝**（`SIR-RENAME-PATH-004`），计划未提供 apply；Q17 原子事务、组合改名、实体字段物理列及 G2 多文件门仍未完成。本单仅以**单文件单声明持久身份与只读规划**判 `DONE`，不宣布 G2 关闭。

### 裁决记录

- D0：采用源内 `@id`、独立于名称的能力/实体字段身份；非法或重复 ID 精确拒绝，未写注解的旧身份格式不变。
- D1：裁决 A 保留六类 ChangePlan 的单族不变量，独立 `RenamePlan` 只读；混合文件事务和故障恢复完整移交未授权的 Q17 候选。
- D2：旧身份不静默映射；源字节/图摘要绑定并在 `plan`/`verify` 双侧检查，无法证明同一持久身份即拒绝。
- D3：仅能力单声明支持成功规划；实体字段只证明身份/列名继承拒绝，原课程例子的组合改名精确拒绝；物理落盘与数据库迁移分归后续事务/G3。

## 允许 / 禁止范围

- 允许：单文件身份语法与 AST / Semantic / Lowered 必要贯通、旧/新格式的显式区分与无映射拒绝、**只读**类型化改名计划及其契约测试、本单证据文档。计划的受管清单验证只读，不写 CURRENT/Journal/工程文件。
- 禁止：改名 apply 或混合文件事务、放宽六类 `ChangePlan` 单族不变量、无证据的旧身份自动映射、字段运行时改名或数据库 DDL/G3、多文件 `SourceSnapshot`/import/模块市场/平台、未受管文件清理、自动提交/推送。

## 已确认的设计点（D0–D3；具体语法在实现前冻结）

| # | 推荐 | 为什么必须定清 |
| --- | --- | --- |
| D0 | 源中持久声明 ID（设计 02 的 `@id` 方向；精确语法/版本经先决核查冻结），能力与实体字段先覆盖 | 用户的复制+替换操作无需额外手动比对；身份不靠名字或文本相似度 |
| D1（裁决 A） | 本单只做独立的只读类型化改名计划；原子混合文件事务与恢复另立下一张单，六类现有 `ChangePlan` 不变量不变 | P3 证实目前只有 UPDATE/CREATE/DELETE 三套单族 Journal，没有安全的混合 apply 路径 |
| D2（裁决 A） | 未加 ID 的旧源/图行为保留；无显式一对一旧→新映射时拒绝改名，映射执行后置 | 老图/Bundle 不可静默重解释；本单不构造虚假的历史编辑意图 |
| D3（裁决 A） | 先证明单文件能力的身份+计划链；字段身份/列名继承仅钉契约与拒绝，应用后的盘面一致性留下一单、数据库迁移留 G3 | 计划、文件应用与数据库数据保留是三个不同完成门 |

## 决策记录

- 2026-09-26：负责人经执行通道确认本单总体方案并授权推荐 D0–D3 进入实施（`SPEC_REVIEW` → `IN_PROGRESS`）。授权不等于放宽本单完成门：文件闭环部分若无法安全交付，按“开工前先决核查”第 3 条先回报并调整工作单，不先改生产代码。
- 2026-09-26：先决核查 P3 暴露 BLOCK-1 后，负责人明确选 **A**：Q16 只做身份、旧身份精准拒绝与只读类型化计划；原子混合事务、恢复和“盘面等于从零生成”完整留给后续工作单。未擅自把原完成门判为通过。
- 2026-09-26：实施期三处按实测修正（均在裁决 A 范围内，不放宽任何完成门）：①`input`/`view` 字段写 `@id` 在**语法层**拒绝，不静默忽略；②原冻结的 `SIR-RENAME-PATH-001` 经实测不可达（`SIR-GRAPH-PROVENANCE-004` 已强制文件→生成 artifact 的一致性），改为结构保证 + 直接断言，不保留不可触发代码；③成员身份在 `NormalizePass` 改从 resolver binding 读取，使“谁持有显式 ID”只有一处权威。
- 2026-09-26：父代理复审补齐两处证据/契约：真实 SIR → 生成文件/Graph → 只读计划的纵向测试；两侧 UTF-8 源字节摘要独立于图摘要，改注释而图不变也会报 `SIR-RENAME-STALE-004/005`。再用工作单原始 course fixture 实测出同一字面替换会连带 Input 声明改名：计划若只按能力闭包返回成功会漏 Input DTO，故增加工程级受管文件差异检查，`SIR-RENAME-PATH-004` 点名漏报路径并拒绝。**这不等于原例已支持多声明改名**；该能力单列后续范围。
- 实施阶段不自动提交或推送；两条全量闸门与业务场景 IT 仍由 GitHub CI 对待验收 commit 做一次验证。

## 实现冻结（2026-09-26 编码前定稿，均在裁决 A 范围内）

### 语法与命名空间

- 注解语法：`@id("<id>")`。能力声明写在声明名之后、`{` 之前：`capability SearchEnrollments @id("search-enrollments") { ... }`；实体字段写在字段声明末尾、`;` 之前：`field courseCode: String where notBlank, length(1, 32) @id("course-code");`。`input` / `view` 字段不接受该注解（**语法层拒绝，不静默忽略**）。
- id 取值：`@id` 参数经字符串解码后必须匹配 `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`；同一个 `software` 内所有显式 id 全局唯一（跨种类）。违反分别报 `SIR-IDENTITY-001` / `SIR-IDENTITY-002`，均带 SourceSpan。
- 旧格式（未写 `@id`，身份编码分支不变）：声明符号 `sir://<software>/<kind>/<name>`；实体字段 `sir://<software>/entity/<entity>/field/<field>`；AST 路径 `<...>/<kind>/<name>`。
- 新格式（写了 `@id`）：能力声明与其作用域符号同为 `sir://<software>/declared/capability/<id>`；实体字段 `sir://<software>/declared/entity-field/<id>`；AST 路径 `<...>/declared-capability/<id>`、`<...>/declared-entity-field/<id>`；能力作用域内的派生符号为 `<scope>/var/<name>`、`<scope>/step/<stepAstNodeId>`、`<scope>/step/<stepAstNodeId>/var/<name>`。
- 旧身份不可被重解释：`declared` 段与任何既有种类关键字（enum/entity/input/view/error/capability）都不相等，因此旧名字编码身份不会等于新显式身份；旧图/Bundle 的序列化格式无需迁移。旧树与当前树之间的**逐字节交叉比对未执行**，只证明旧身份字面仍按旧规则生成及当前树重复生成一致。

### 诊断码（冻结）

| 码 | 阶段 | 含义 |
| --- | --- | --- |
| `SIR-IDENTITY-001` | SEMANTIC | `@id` 取值超出冻结字符集/长度 |
| `SIR-IDENTITY-002` | SEMANTIC | 同一 software 内显式 id 重复（跨种类全局唯一） |
| `SIR-RENAME-REQUEST-001/002/003` | REQUEST | 请求基线与图版本/来源/规范摘要或源字节摘要不符（陈旧基线） |
| `SIR-RENAME-IDENTITY-001` | IDENTITY | 请求的 `declarationSymbol` 不属于显式持久身份命名空间 |
| `SIR-RENAME-IDENTITY-002` | IDENTITY | 候选缺少同一持久身份（疑似只改名字未加 `@id`，不给文本猜测） |
| `SIR-RENAME-TARGET-001` | TARGET | 基线无该持久身份声明 |
| `SIR-RENAME-PHYSICAL-001` | PHYSICAL | 实体字段改名的物理列无法继承（列名仍由字段名派生；列继承与 DDL 属 G3） |
| `SIR-RENAME-MAP-001/002` | MAPPING | 旧/新闭包 artifact 数量或 artifactId/role/ownerSymbol 不能一对一 |
| `SIR-RENAME-PATH-002` | PATH | 待建立路径与基线受管清单同名（路径冲突） |
| `SIR-RENAME-PATH-003` | PATH | 三个文件集合不互斥（防御性；权威在 `RenamePlan` 构造器） |
| `SIR-RENAME-PATH-004` | PATH | 计划三集合没有恰好覆盖两次修订的**全工程受管文件差异**；缺路径、类别错误、长度/摘要不符或多报未变路径，均点名路径拒绝（`plan` 与 `verify`） |
| `SIR-RENAME-STALE-001/002/003` | STALE | 验证时基线/候选图摘要或计划摘要已变 |
| `SIR-RENAME-STALE-004/005` | STALE | 验证时基线/候选源字节摘要已变，即使图未变仍拒绝 |

**冻结后的实测修正（2026-09-26，编码阶段）**：原冻结的 `SIR-RENAME-PATH-001`（“待撤销路径不由本声明的受管 artifact 独占生成”）经实测**不可达**：`ProjectGraphValidator` 已强制 `SIR-GRAPH-PROVENANCE-004`（项目文件 provenance 的 artifactId 必须等于其 GENERATES_FILE 源），因此任何合法图的文件生成者都可證。该码已删除，改为**结构保证 + 直接断言**：撤销/幸存路径只能来自基线闭包（即受管清单），新建路径只能来自基线清单不含的路径，因此未受管文件不可能进入计划；盘面上是否已被未受管文件占用是后续 apply 单的前置校验，不由图回答。

### 验收映射（本单测试顺序 1–3 → 实现与证据位置）

| 完成门条目 | 实现位置 | 直接测试 |
| --- | --- | --- |
| 持久身份语法与 AST 身份 | `Sir.g4`、`SirAstBuilder`、`AstField`、`AstCapabilityDecl` | `sir-parser`：`DeclaredIdentityGrammarTest`（5 例） |
| 语义身份/binding 不随名字变 | `SymbolIdFactory`、`ResolvePass`、`NormalizePass`、`TypePass`、`DeclarationIdentity` | `sir-semantic`：`DeclaredIdentitySemanticsTest`（8 例） |
| 旧格式不被重解释 | 同上（双命名空间） | `DeclaredIdentitySemanticsTest`：旧源符号字面断言 + `DeclarationIdentity.isDeclared` 互异断言 |
| 只读类型化改名计划与拒绝 | `sir-change`：`RenamePlanner(RenamePlannerCore)`、`RenamePlan*` | `RenamePlannerTest`（19 例）+ `RenameSourceStalenessTest` + `RenamePlanCoverageTest`（6 例） |
| 计划不可 apply、不碰盘面、六类不变量 | `RenamePlanner`（仅 plan/verify）、`RenamePlan` 构造器 | `sir-change`：`RenamePlanContractTest`（6 例） |
| 物理名契约 | `SpringBootModelLowerer`（既有事实，不改代码） | `sir-lowering-spring-boot`：`DeclaredIdentityPhysicalNameTest`（4 例） |
| 真实单文件改名计划、遗漏路径拒绝 | `ToolchainApplication` 产出真实图与文件，`RenamePlanner` 在图上规划 | `RenamePlanVerticalTest`（9 例；原 course fixture 全局替换因 Input DTO 两条遗漏路径精确拒绝） |
| 旧源当前树内重复生成一致 | 未改生成器/应用 | `ToolchainDeterminismTest`、`ToolchainHappyPathTest`（定向；非跨 commit 字节比对） |

## 先决核查结果（2026-09-26，实施期实测，非规划假设）

| # | 核查项 | 实测结论 |
| --- | --- | --- |
| P1 | 旧/新身份编码 | `SymbolIdFactory`（sir-semantic）把软件名、种类、名称编码进 `sir://...`；`AstIdFactory.named(parent, kind, name)` 把名称编码进 `AstNodeId` 路径。语义侧 4 个 Pass（Resolve/Normalize/Validate/Type）与 `TargetCatalogBuilder` 都按名称取 ID。**结论：身份不随名字变化必须同时改 AST 与 Semantic 两侧，且只在声明处生效。** |
| P2 | 图规范序列化 | `ProjectGraphCanonicalFormatVersion.V1` 与 `SnapshotFormat` 的载荷字段是字符串：`id.value`、`sourceSymbol`、`ownerSymbol.value`、`provenance.sourceNodeId`。`GraphVersion` 只有 `V0_1`。**结论：身份取值变化不改变格式；只要“未声明 `@id` 的源”编码保持原样，既有 Bundle/图逐字节不变，无需格式迁移。** |
| P3 | 混合路径变更的原子性 | 文件事务有三套**冻结**日志：`TransactionJournal`（V1，仅 UPDATE，`commitFile` 要求目标在 B0 存在）、`CreateTransactionJournal`（V2，仅 CREATE）、`DeleteTransactionJournal`（V3，仅 DELETE）；`JournalGate` 按 MAGIC 分派，三个恢复引擎各自独立。`ChangePlan` 构造器硬拒混合族；`FileTransaction`（生成期）只写不删、且不参与 CURRENT/Bundle。**结论：不存在可用于“旧路径删除 + 新路径建立 + 同路径更新”一次原子应用的既有路径；本单先设计只读类型化改名计划，故障恢复证据归后续事务单，不得解除 `ChangePlan` 单族不变量。** |
| P4 | Lowered 物理名与列映射 | `SpringBootModelLowerer` 中能力物理名来自声明名（`<Name>Service`/`<Name>Controller`/`/api/`+kebab(name)），而 artifact ID 来自 `SymbolId`（`lir://<profile>/<role>/<urlencode(symbolId)>`）。实体成员列名目前是 `snake(field.name())`（`Ref` 加 `_id`），有以 `SymbolId` 为键的 `columns` 映射表。**结论：声明身份稳定后，改名的物理影响面是文件路径与 qualifiedName，artifact ID 不变；列名继承需要显式契约，本单不执行 DDL。** |

### D1 设计要点（先决核查第 2 条要求，设计先于生产改动）

1. 改名意图由**持久身份**判定，不由文本相似度判定：同一 `declarationSymbol` 在基线与候选都存在、`name` 不同，且两侧闭包 artifact ID/role/ownerSymbol 一一对应。
2. 计划是**独立类型**（`RenamePlan`），不是 `ChangePlan` 的一个族：它同时携带①同路径幸存文件的更新集合②旧路径撤销集合③新路径建立集合，三集合互斥且都由已验证身份映射推导；`ChangePlan` 六个既有操作与其单族不变量不改。
3. 旧路径撤销只允许作用在**基线受管清单**内的路径；本单对两侧图的受管文件长度/摘要、全工程差异逐条验证。B0 盘面实际字节、符号链接、未受管文件占用及用户文件保护，须在后续 apply 单执行前校验；本单只读计划不能声称已查盘。
4. 应用需要一次原子文件事务。既有三套日志按族冻结；后续工作单需评估新日志版本（V4 为候选）、恢复引擎、`JournalGate` 分派、计划绑定校验与 `PlanProtector` 扩展，并验证 CURRENT 的故障恢复方向。**这些尚未实现，也不属于 Q16。**

**交接边界：`DONE`（BLOCK-1 已按 A 裁决；本机定向与同树 CI 全部通过）。** 本单只完成身份、拒绝与只读计划；新事务的详细设计与执行证据在下一张单冻结，不把 V4 方案当作本单已实现能力。

### BLOCK-1：文件闭环事务的范围裁决（已裁决）

- 原阻断：旧完成门要求“旧路径不留存 + 盘面逐字节等于从零生成 + 一次原子应用”；P3 实测现有三套冻结单族 Journal 均不能完成混合路径事务。
- 2026-09-26 负责人明确采用 **A**：Q16 聚焦身份、旧身份拒绝语义、只读类型化改名计划；混合路径的原子应用、故障恢复、旧路径清理与最终盘面一致性**完整迁移到后续事务单**。这不是把未做的门记通过；Q16 任何成功结果都不能声称用户可以应用改名。
- 未来事务单先实测并冻结新的日志/恢复方案（V4 仅为候选，不预设一定沿用），覆盖 `JournalGate` 分派、绑定校验、CURRENT 发布与故障注入；提交前本机不跑重活，待授权提交/推送后由 CI 验证。未获其授权前不得实现或调用混合 apply。
- 当前仓库状态在裁决时仅四份工作单/镜像文档未提交；无生产代码、测试、提交或推送。
