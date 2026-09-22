# 已完成工作单：Q3 Change fixtures 与软链接失败收口

- 状态：`DONE`（项目负责人 2026-09-18 确认通过）
- 归档：2026-09-18 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 验收记录：负责人 2026-09-18 对执行结果回复“确认”，即验收通过。验收依据：冻结形式 `-o clean verify` 首次 BUILD SUCCESS；全量 **465 run / 0 fail / 0 error / 5 skip**；`sir-toolchain-application` 5 项软链接失败归零（2 处生产诊断改动均有直接证据）；`ChangePlanningApplicationTest` 6 项与 `KcgCliWorkflowTest` 13 项解除跳过并全部通过；skip 11 → 5 且无新增 skip。
- 所属阶段：路线图阶段 3（Change fixtures 与操作族测试）+ 已并入的跨阶段挂账（5 项软链接断言失败）；两者都属 **G0：现有链路资格收口**
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 3、`docs/qualification/TEST_COVERAGE_INVENTORY.md` 第 4 节与第 6 节、`docs/qualification/CURRENT_QUALIFICATION.md` 1.2
- 前置工作：Q1、Q2 已完成并归档（[`completed/`](completed/)）
- 授权来源：项目负责人 2026-09-18 指示“确认，另外的失败并入接下来的工作”——即把 `sir-toolchain-application` 的 5 项软链接失败并入本工作单，而不是各自单开
- 版本快照：按负责人 2026-09-18 决定**暂不提交 Git**，与 Q1/Q2 合并到 G1 完成后统一提交

## 目标
两件事，互相独立、各自有独立完成门：

- **Part A**：补齐阻塞 `ChangePlanningApplicationTest` 与 `KcgCliWorkflowTest` 的 base/candidate SIR fixtures，让 assumption skip 变成真实执行的断言。
- **Part B**：给 5 项软链接失败定性并收口——其中 4 项是诊断文本与测试预期不一致，1 项是**生产代码的诊断遮蔽缺陷**。

## 勘察结论（2026-09-18，Linux，均为直接读代码/读 Surefire 报告得到）

### Part A：需要的 fixture 比覆盖清单描述的更小、更明确

`ChangePlanningApplicationTest` 的 6 个 assumption 站点**只引用一个资源**：

- `valid/campus-market-candidate.sir`（路径前缀 `valid/` → `sir-toolchain-application/src/test/resources/valid/`）。文件内的 `v05-base.sir` / `v0x-c.sir` 等名称来自 `KcgCliWorkflowTest`，不属于该类。

`KcgCliWorkflowTest`（`@BeforeAll` 只检查 `CliTestFixtures.resourceExists("campus-market-candidate.sir")`）通过 `CliTestFixtures.writeSir(tempDir, "<本地名>", "<共享资源>")` 复制资源；因此 `v01-c.sir`–`v06-c.sir`、`inc-c.sir`、`sc-a.sir`、`sc-b.sir` **不是独立文件**，只是本地副本名。真正需要的共享资源（`kcg-cli/src/test/resources/io/kcg/cli/`）是：

| 资源 | 用途 | 关键要求 |
|---|---|---|
| `campus-market.sir` | 已存在 | — |
| `campus-market-candidate.sir` | v04 / stale / 类级门 | 与 `campus-market.sir` 仅差 `PublishGoodsInput.title` 的约束 |
| `campus-market-candidate-add-capability.sir` | v0.2 不兼容性与 v0.1 版本不兼容 | 新增 `SearchGoods` capability 声明 |
| `campus-market-candidate-modify-input-field-constraints.sir` | v0.4 / stale | 与 base 仅差 `title` 约束 |
| `campus-market-minimal.sir` | v0.2 base | 单实体 + 查询的最小可编译工程 |
| `campus-market-minimal-add-search-goods.sir` | v0.2 candidate | 在 minimal 上新增 `SearchGoods` |
| `campus-market-two-capabilities.sir` | v0.3 base | 两个 capability |
| `campus-market-two-capabilities-remove-publish-goods.sir` | v0.3 candidate | 移除 `PublishGoods` |
| `campus-market-actorless-readonly-base.sir` | v0.6 base | actorless + readonly capability |
| `campus-market-actorless-readonly-candidate.sir` | v0.6 candidate | 与 base 仅差 exposed 状态 |
| `v05-base.sir` | v0.5 base | `PublishGoodsInput.unusedTag` 字段存在但**未被任何 workflow 引用** |
| `v05-candidate.sir` | v0.5 candidate | 该字段类型改变 |

**风险（必须在本工作单处理）**：`KcgCliWorkflowTest` 的类级 assumption 只看 `campus-market-candidate.sir`。只补这一个文件会让全部 14 个测试从 skip 变为**执行**，其中依赖 `minimal` / `two-capabilities` / `v05-base` / `actorless-readonly` 的那些会立刻失败。因此补 fixture 时**必须同时**调整门控策略（见 D1）。

目标 case 与期望结果（来自测试本身，不属于本工作单的自由设计空间）：v01 modify-capability-workflow → `PLANNED` + `fileChanges`；v02 add-capability → `fileAdditions`；v03 remove-capability → `fileDeletions`；v04 / v05 / v06 → `fileChanges`；另有 `NO_CHANGES`、`SIR-CHANGE-REQUEST-003`、`KCG-CLI-CONTEXT-002`、`SIR-APP-CHANGE-BASELINE-007`、`KCG-CLI-CONTEXT-003`、`RECOVERY_REQUIRED`（exit 4）与 context JSON 确定性。

### Part B：5 项失败的定性（逐项证据）

| 测试 | 断言 | 实际 | 定性 |
|---|---|---|---|
| `SafeTargetResolverTest.parentSymlinkOfOutputRootRejected:87` | 消息含 `symlink`（“must mention symlink”） | 拒绝已发生，消息为 `link in raw chain: …` | **诊断文本**：行为正确 |
| `SafeTargetResolverTest.linkDotDotRawChainAttackRejected:122` | 消息含 `symlink` | 同上 | **诊断文本**：行为正确 |
| `SafeTargetResolverTest.targetSymlinkRejected:151` | 消息含 `symlink` | 同上 | **诊断文本**：行为正确 |
| `SafeTargetResolverTest.parentSymlinkInsertedBeforeResolveRejected:186` | 消息含 `symlink` 或 `not a directory` 或 `escapes` | 同上 | **诊断文本**：行为正确 |
| `PathGuardTest.targetItselfIsSymlinkIsRejected:311` | 期望 `SIR-APP-CONFLICT-002` | 实得 `SIR-APP-PATH-003` | **生产缺陷：诊断遮蔽** |

已确认的事实：

1. 4 项 `SafeTargetResolverTest` 失败**全部来自同一行**：`SafeTargetResolver.java:164` 抛出 `"link in raw chain: " + current`。同一个类在 `:35` 与 `:95` 使用的措辞是 `"… must not be a symlink or reparse point"` —— 即第 164 行是与类内其他位置**不一致**的那一处，而不是测试用了过严的措辞。四项测试都**先**成功通过了 `assertThrows(UnsafePathException.class)`，所以拒绝行为本身从未失效。
2. `PathGuardTest` 那一项是真实遮蔽：`PathGuard:64` 的 `checkTargetSymlinks(outputRoot, relativePath)` 把**叶子节点自身**也算进“target chain”，于是先报 `PATH-003` 并在 `:68-70` 提前返回，使 `conflictCheck` 中**专为叶子符号链接写的分支**（`:233` → `SIR-APP-CONFLICT-002` “cannot replace symbolic link”）在当前输入下不可达。测试期望与该分支的编写意图一致。
3. 该修复的爆炸半径已核查：`checkTargetSymlinks` 只有**一个调用点**（`PathGuard:62`）；其他 PATH-003 断言（`PathGuardTest:263` 根父链、`:277` 根本身、`:295` 目标父链、`PathSecurityReviewTest:165` junction 在父链）全部是**祖先链/根**场景，排除叶子后不受影响；`FileTransaction:77` 的 PATH-003 用于 “output root is a symbolic link”，是另一条独立检查。
4. 排除叶子后仍然 fail closed：叶子为符号链接时 `Files.exists(target, NOFOLLOW_LINKS)` 为真（悬空链接也为真），`FAIL_IF_EXISTS` → `CONFLICT-001`，`REPLACE_EXISTING` → `CONFLICT-002`。不存在“漏检”路径。

## 需要项目负责人复核的决定

- **D1 fixture 门控策略**（推荐 A1+）：补齐上表 11 个新文件后，把 `KcgCliWorkflowTest` 的**类级** assumption 换成**逐测试** assumption（每个测试只声明自己真正用到的资源），并同样检查 `ChangePlanningApplicationTest` 是否要从“单一资源门”改为逐测试声明。理由：类级门只看一个文件，会在只补一部分时把 skip 变成失败；逐测试门能把“还缺哪个 fixture”如实显示为 skip，而不是让读者误以为测试通过。
- **D2 Part B-1 方向**（推荐 B1+）：**保留测试意图，修生产消息**——把 `SafeTargetResolver.java:164` 改成同时包含 `symlink` 与 `reparse point` 的措辞，与同类 `:35`/`:95` 一致。备选 B1-：放宽 4 个断言去匹配现状（会让“必须提到符号链接”这一可诊断性意图消失）。**不建议**在本工作单引入结构化 reason 字段（那是公共诊断契约变更，应单独评估）。
- **D3 Part B-2 方向**（推荐 B2+）：**修生产**，让 `checkTargetSymlinks` 只覆盖祖先链（不含叶子），使策略相关的 `CONFLICT-001`/`CONFLICT-002` 分支可达；备选 B2-：把测试期望改成 `PATH-003`（等于接受“叶子专用分支永远不可达”）。无论选哪个，都必须给出“仍然拒绝”的直接证据。
- **D4 执行顺序**（推荐 B → A）：先做 Part B（两处小修、证据短、能把红色基线清零），再做 Part A（12 个 fixture，工作量大）。理由：Part B 完成后 `sir-toolchain-application` 从 5 项失败变为 0，全量闸门会第一次出现“全绿但仍有 skip”的状态，便于把 Part A 的效果与 Part B 分开归因。
- **D5 范围合并确认**：Part B 会修改 `sir-toolchain-application` 的生产代码（`SafeTargetResolver`、`PathGuard` 或至少其中之一）。这在本工作单内被显式授权；若负责人希望生产代码改动单独成单，请指示。

## 实现与测试顺序

Part B（先做）：

1. 先为 5 项失败各写/保留**能区分“拒绝”与“诊断”的最小断言**：保留 `assertThrows`；若采用 B1+，断言消息同时含 `symlink`（并保留对路径的断言）。
2. 修 `SafeTargetResolver.java:164` 的措辞（若选 B1+）。
3. 先加一个**能证明遮蔽存在**的测试：`REPLACE_EXISTING` + 叶子符号链接必须得到 `CONFLICT-002`（该测试当前正是失败项）；再加一个 `FAIL_IF_EXISTS` + 叶子符号链接必须得到 `CONFLICT-001` 的对照，防止修复时把叶子检查整体删掉。
4. 修 `checkTargetSymlinks` 的覆盖范围（若选 B2+），并确认祖先链用例（含 Windows junction 的 `PathSecurityReviewTest`）仍报 `PATH-003`。
5. 全量定向：`sir-toolchain-application` 的 5 项失败必须归零，且**不允许新增**任何 fail/error/skip。

Part A：

6. 逐个 fixture 写：每个文件必须在真实 Parser → Semantic 链路上合法（fixture 是输入，不能是非法 SIR）。
7. 每个 base/candidate 对必须写明：精确差异、对应 `ChangeOperation`、以及用 SymbolId/AstNodeId 的定位方式（不允许名称模糊匹配）。
8. 按 D1 调整门控；逐个确认目标 case 由 skip 变为**执行并通过**。
9. 负例仍须保留：`nonexistent.sir`（`protect002`）与 `protect004` 的目录输入不得被 fixture 改动破坏。

## Q3 完成闸

- `sir-toolchain-application` 报告 **0 fail / 0 error**；原先 5 项失败全部有直接证据说明是“诊断修正”或“遮蔽修复”，且没有削弱任何拒绝行为。
- 叶子符号链接在 `FAIL_IF_EXISTS` 与 `REPLACE_EXISTING` 下都有精确诊断码断言；祖先链与根链路仍报 `PATH-003`。
- `ChangePlanningApplicationTest` 与 `KcgCliWorkflowTest` 的目标 case 全部从 assumption skip 变为真实执行；本工作单结束时这两个类**没有**因缺 fixture 而产生的 skip。
- 全量离线闸门按①②执行：① `-o clean verify`；② `-o clean verify -Dmaven.test.failure.ignore=true`。两条都必须记录**逐模块** run/fail/error/skip，并与 Q2 的 446/451、5 fail、11 skip 逐项对比归因。
- skip 总数变化必须逐条解释（哪些 skip 被消除、是否有新增）。
- 不新增依赖、不新增 exclude；不删除任何既有断言；不修改 `sir-parser`/`sir-semantic`/`sir-lowering-*`/`sir-generator-*`/`sir-project-graph`/`sir-change` 的公共契约。
- 不得把 `generate`/`register`/`apply`/`recover` CLI 生命周期、conformance、MySQL、HTTP 或事务故障矩阵记为本工作单内容。
- 证据写入本工作单与 `docs/qualification/`：`CURRENT_QUALIFICATION.md` 1.2 与第 6 节第 8 项当前把 5 项失败描述为“实现抛 link in raw chain / 期望 CONFLICT-002 实得 PATH-003”，必须改写成**已定性的根因 + 修复证据**。
- 完成后状态改为 `AWAITING_ACCEPTANCE`；按负责人决定归档时**不单独提交 Git**。

## 本工作单明确不做

- 不实现 ADR-019 的完整本地 CLI 生命周期（`generate`/`register`/`apply`/`recover`）；`KcgCliWorkflowTest` 用到的 `registerBase` 走的是 public Application API，不需要新 CLI 命令。
- 不改 `REFERENCES` 边、Graph service、Snapshot V2、增量索引、conformance 整包、事务故障注入矩阵、Redis 或第二 Target。
- 不为通过测试而放宽任何 fail-closed 行为，也不删除既有断言。

## 执行证据（2026-09-18，Linux，全部为当次实际运行结果）

### Part B：5 项软链接失败归零

两处生产改动（已获 D2/D3 授权）：

| 文件 | 改动 | 依据 |
|---|---|---|
| `SafeTargetResolver.java:164` | 消息 `"link in raw chain: "` → `"symlink or reparse point in raw chain: "` | 同类 `:35`、`:95` 的既有措辞就是 “symlink or reparse point”；4 项断言要求消息含子串 `symlink` |
| `PathGuard.checkTargetSymlinks` | 只检查**严格祖先链**（`findExistingAncestor(target.getParent())`），删除叶子分支；调用方消息改为 “symbolic link in target parent chain for …” | 叶子符号链接是**冲突**而非父链违规；原先叶子检查先报 `PATH-003` 并在 `PathGuard:68-70` 提前返回，使 `conflictCheck` 中专为叶子写的 `SIR-APP-CONFLICT-002` 分支不可达 |

**措辞排查的一个重要修正**：失败原因不是“某一行与类内其他行不一致”，而是**拼写**——断言找的是子串 `symlink`，旧文本是 `link in raw chain`，而我最初改成 `symbolic link or reparse point`（两个词）**仍然不含** `symlink` 子串，因此仍失败。最终取同类既有措辞 `symlink or reparse point` 才同时满足断言与一致性。这条更正说明：4 项断言要求的是“提到 symlink”这一可诊断性意图，而不是某种精确句式。

**叶子不因修复而失去保护（fail closed 证据）**：`ConflictPolicy` 只有 `FAIL_IF_EXISTS` 与 `REPLACE_EXISTING` 两个常量，`conflictCheck` 的 if/else 覆盖二者；叶子为符号链接（含悬空）时 `Files.exists(target, NOFOLLOW_LINKS)` 为真 → 分别报 `CONFLICT-001` / `CONFLICT-002`。新增测试 `PathGuardTest.targetItselfIsSymlinkIsRejectedUnderFailIfExists` 专门守住这一点（否则“不再检查叶子”可以被错误实现为“整体删掉叶子拒绝”，而单靠 REPLACE_EXISTING 那条测不出来）。

定向结果：`SafeTargetResolverTest` 10/0、`PathGuardTest` 32/0（原 31，+1 新守卫）、`PathSecurityReviewTest` 12/0/5 skip（Windows junction）。

### Part A：fixtures

新增/迁移文件：

| 位置 | 文件 | 说明 |
|---|---|---|
| `sir-toolchain-application/src/test/resources/valid/` | `campus-market-candidate.sir` | base 仅第 55 行不同：`validate input.price >= 0.01` → `> 0.0` |
| `kcg-cli/src/test/resources/io/kcg/cli/` | `campus-market.sir` | **从 `kcg-cli/src/test/resources/` 根目录移入**（见下方第 5 条缺陷） |
| 同上 | `campus-market-candidate.sir` | 与应用模块同一份内容 |
| 同上 | `campus-market-candidate-modify-input-field-constraints.sir` | `PublishGoodsInput.title`：`notBlank` → `notBlank, length(1, 80)` |
| 同上 | `campus-market-candidate-add-capability.sir` | 在 `campus-market.sir` 上加 actorless readonly `SearchGoods` |
| 同上 | `campus-market-minimal.sir` | 单实体 `Goods` + actorless readonly `ListAvailableGoods`（find + return） |
| 同上 | `campus-market-minimal-add-search-goods.sir` | `minimal` + `SearchGoods` |
| 同上 | `campus-market-two-capabilities.sir` | 两个**均为 actorless** 的能力：`PublishGoods`（atomic/command）+ `SearchGoods` |
| 同上 | `campus-market-two-capabilities-remove-publish-goods.sir` | 仅删除 `PublishGoods` 能力块，保留其 input/error 声明 |
| 同上 | `campus-market-actorless-readonly-base.sir` | actorless readonly `ListAvailableGoods`，`expose query` |
| 同上 | `campus-market-actorless-readonly-candidate.sir` | 同一文件，仅 `expose command` |
| 同上 | `v05-base.sir` | `campus-market.sir` + `PublishGoodsInput.unusedTag: String`（无任何 workflow 引用） |
| 同上 | `v05-candidate.sir` | `unusedTag` 类型改为 `Int64` |

共 11 个新文件 + 1 个迁移 + 1 个应用模块 fixture。每个文件与 base 的精确差异、对应操作族与 target 定位均来自测试自身（`ftk(..., side, kind, declarationDisplayName, targetDisplayName)`）与 `TargetCatalogBuilder` 的实际语义（`CAPABILITY_DECLARATION` 的 declaration 与 target 显示名都是能力名，`CAPABILITY_WORKFLOW` 的 target 显示名为空，`INPUT_FIELD` 为 input 名 + 字段名）。

门控按 D1 改为**逐测试** `requireFixtures(...)`，删除类级 `@BeforeAll` 与 21 项 `MISSING_FIXTURES` 清单：每个测试只声明自己真正用到的资源，缺文件时如实显示为 skip 而不是把整批变成失败。

### 过程中发现并修正的 6 处缺陷（全部为 test-side 或 fixture-side，均非生产 RED）

1. `KcgCliWorkflowTest` 依赖的 base `campus-market.sir` 位于 `kcg-cli/src/test/resources/` 根目录，而 `CliTestFixtures.resource()` 只查 `/io/kcg/cli/`，9 个测试报 `missing resource: campus-market.sir`。该错误此前不可见，因为整类被 skip。修正：移入 `io/kcg/cli/`。
2. `ChangePlanningApplicationTest.baseline001RawSha256MismatchFails` 的同长替换目标是**乱码**（`鏍″洯浜屾墜浜ゆ槗绯荤粺`）而非 fixture 中真实的 displayName，`replace()` 匹配不到、文件未变，测试从 skip 变执行后失败。修正：用 `\u` 转义写正确的 displayName，并补 `assertNotEquals(original, modified, ...)` 防止将来静默失效。
3. v03 首次运行报 `SIR-CHANGE-IMPACT-202`（survivor `Application.java` 字节变化）。根因是我最初的 `campus-market-two-capabilities.sir` 让被删除的 `PublishGoods` 成为**唯一带 actor 的能力**：`ApplicationRenderer.render` 按 `actorIdentityTransportPlan()` 分叉，actor 计划消失会把 `Application.java` 从 actor 传输大版本退化为 legacy 短版本。结论：**移除唯一带 actor 的能力必然重写 `Application.java`（survivor），`IMPACT-202` 是正确行为**。修正：把两个能力都设计为 actorless。
4. v05 的 `unusedTag` 必须真正不被任何 workflow 引用，否则 `modify-unreferenced-input-field-type` 的前提不成立。
5. `KcgCliWorkflowTest` 的类级门只检查一个资源，若只补部分 fixture 会把整类从 skip 变成失败——这属于覆盖清单已经警告过的“skip 掩盖问题”模式。
6. `M1TestSupport`（`sir-toolchain-application` 测试支持类）经查**没有任何调用者**，其 `addCapabilityChangeSet`/`removeCapabilityChangeSet`/`v04ChangeSet` 仍是 v0.2/v0.3/v0.4 ChangeSet 形状的有效参考。本工作单不改动它，仅如实登记为死测试代码。

### 全量离线闸门（两条都在最终代码状态执行）

| 形式 | 结果 |
|---|---|
| ① `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify` | **BUILD SUCCESS（exit 0）**，十个 Reactor 模块全部完成。合计 **465 run / 0 fail / 0 error / 5 skip** |
| ② 追加 `-Dmaven.test.failure.ignore=true` | **BUILD SUCCESS**，逐模块数字与①完全相同：合计 **465 run / 0 fail / 0 error / 5 skip** |

逐模块（两形式相同）：parser 43、semantic 103、lowering-api 4、lowering-spring-boot 32、generator 38、project-graph 72、change 22、toolchain-application **133/0/0/5**、kcg-cli **18/0/0/0**。

**这是冻结形式第一次整体通过**：此前 5 项软链接失败会让 `sir-toolchain-application` 中止而跑不到 `kcg-cli`。

### skip 变化逐条解释（11 → 5）

| 原 skip | 变化 | 原因 |
|---|---:|---|
| `PathSecurityReviewTest` 5 项 Windows junction | **保留 5** | `@EnabledOnOs(WINDOWS)`，Linux 上不适用；本工作单未修改其条件 |
| `ChangePlanningApplicationTest` 6 项 assumption | **-6** | 依赖的 `valid/campus-market-candidate.sir` 已补齐，6 项现在真实执行并通过 |
| `KcgCliWorkflowTest` 13 项类级 assumption | **-13，且由 0→13 计入 tests** | 全部 11 个共享 fixture 已补齐；此前整类以类级 assumption 跳过（未计入 gate② 的 kcg-cli “5 run”） |

净效果：skip 11 → 5；执行中的测试从 440 → 460（+20 = 应用模块 +6、CLI +13、新增守卫 +1）。**没有任何 skip 是新增的**。

### 未做 / 未覆盖（如实登记）

- `KcgCliWorkflowTest` 的 13 项现在**真实执行**，但它们只覆盖 `context` 与 `plan` 两个已发布的只读命令；`generate`/`register`/`apply`/`recover` 仍不属于 CLI 公开边界。
- 本轮没有为 Change 操作族补新的**跨版本组合**矩阵（仅按现有测试要求的最小 pair 建 fixture）。
- `SIR-CHANGE-*` 的失败矩阵、conformance 整包、MySQL/HTTP、事务故障注入仍不在范围内。
- 生产改动限定为 2 个文件；未新增依赖、未新增 POM exclude、未删除任何既有断言。

### 版本快照

**未提交、未推送**。生产与测试改动、13 个 fixture、以及文档同步全部留在工作区；本地 HEAD 与 `origin/main` 跟踪引用同为 `902a7a5`。

---

## 验收归档记录（2026-09-18）

项目负责人对执行结果回复“确认”，Q3 验收通过。归档时的状态事实：

- 两条全量离线闸门（冻结形式与 `-Dmaven.test.failure.ignore=true`）均为 **BUILD SUCCESS**，合计 **465 run / 0 fail / 0 error / 5 skip**；这是冻结形式第一次整体通过。
- 生产改动限定 2 个文件（`SafeTargetResolver.java` 诊断措辞、`PathGuard.java` 叶子链遮蔽），均在 D2/D3 授权范围内。
- 新增 fixture 13 个（应用模块 1、CLI 12 含 base 归位），`KcgCliWorkflowTest` 改为逐测试 `requireFixtures(...)`。
- 剩余 5 项 skip 全部是 `PathSecurityReviewTest` 的 `@EnabledOnOs(WINDOWS)` junction 用例，与本工作单无关。
- **未创建本地提交、未推送 `origin`**：按负责人 2026-09-18 的持续指示，Q1–Q3 的改动合并到 G1 完成后统一提交；归档时本地 HEAD 与 `origin/main` 跟踪引用同为 `902a7a5`。

G0 内部进度：阶段 1（Q1）、阶段 2（Q2）、阶段 3（Q3）已闭合；下一张候选是阶段 4（conformance 整包重入 testCompile），已写入 `docs/roadmap/ACTIVE_WORK.md` 并停在 `SPEC_REVIEW` 等待复核。
