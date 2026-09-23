# 当前工作单：Q14 变更层语义投影与 Q9–Q11 语言面对齐

- 状态：**`DONE`**（2026-09-23 负责人验收通过；两条全量闸门与 Q9–Q11 场景回归按负责人指示集中到提交前那一次运行，见「验收记录」）
- 所属阶段：**G1：单文件课程业务切片**（前置修复单；解除 Q13 的阻断）
- 前置状态：G0 已闭合；G1 的 Q9、Q10、Q11 已完成、验收并归档；**Q13（G1 变更闭环）因 BLOCK-1 暂停**（见 [`Q13-g1-change-loop.md`](Q13-g1-change-loop.md)）
- 方向来源：Q13 开工前 P1–P3 实测发现的 BLOCK-1；`docs/design/03-compiler-and-generated-backend.md`（变更计划依赖语义投影判断"声明是否变化"）；`docs/design/07-validation-and-direction-roadmap.md` G1 行"继续生成 SIR/修改 SIR"
- 编号说明：Q12 仍为"路由模板与资源式路由命名"（未立项）；本单占用 Q14
- 版本快照：按既有规则执行期间不提交 Git；同一批未提交改动**只做一次提交**（负责人 2026-09-23 指示），全量与业务回归集中到提交前一次运行

## 为什么需要这一单

`sir-change` 判断"候选与基线相比哪个声明变了"靠的是 `SemanticProjection`（`sir-change/src/main/java/io/kcg/sir/change/internal/SemanticProjection.java`）：`PlannerCore` 用 `ofFullDeclaration(base) != ofFullDeclaration(candidate)`、`ofCapabilityContract(...)`、`ofWorkflowOnly(...)` 做相等判断。**这个投影停留在 Q9 之前的语言面**，因此 Q13 的实测撞上两类问题。

**第一类：直接崩溃（不是诊断）**。任何针对含 Q10 `.present` 或 Q11 `any(...)` 的源的计划调用都抛

```
java.lang.IllegalStateException: unsupported NormalizedExpression variant:
    io.kcg.sir.semantic.model.NormalizedExpression$PresentExpression
    at SemanticProjection.java:304
```

四个独立测试（R1 计划、N1 计划、整链、陈旧基线）在 0.3 s 内以同一异常中断，与操作种类无关——计划阶段遍历全部声明的投影就会撞上。同类兜底 `throw` 还有一处：`typeP` 不处理 `PageType`，即 Q9 的 `Page<view>` 输出在修好表达式变体之后会紧接着崩溃。兜底 `throw` 的语义是"新语法未接线"，但它今天在**已验收的语言面**上就是可达的。

**第二类：静默盲区**。投影记录里没有这些事实，于是"只改该事实"的候选会被判为**声明未变化**：

| 语言事实 | 来源 | 投影现状 |
|---|---|---|
| `versioned` 字段标志、`patchSourceField` | Q10（`field x: T versioned;`） | `FieldP(symbolId, name, type, constraints)` 不含 |
| 输入的 `kind`（PLAIN/PATCH）与 `patchSourceEntity` | Q10（`input X patch of Entity`） | `InputP(symbolId, name, fields)` 不含 |
| 视图字段的嵌套关联（基数 + 目标 view） | Q11（`field student: StudentSummary`） | `ViewFieldP(symbolId, name, type, sourceField)` 不含 |
| `find` 的 `order by` 与分页子句 | Q9（`order by code ascending` / `Page input.page, input.size else InvalidPage`） | `FindP(entitySymbol, resultVariable, itemVariable, predicate)` 不含 |
| 条件持久化的失败错误 | Q10（`persist course else StaleVersion;`） | `PersistP(targetVariable)` 不含 `failure` |

**影响面**：G1 完成门里"继续生成 SIR/修改 SIR"这句话，今天在 Q9/Q10/Q11 三个已验收切片上并不成立；现有全部变更证据（G0 五场景、Q3 fixture、CLI 七个工作流、计划类单测）都建立在**早于 Q9** 的 campus-market 源上，没有一条覆盖 `containsLiteral`/`.present`/`any(...)`/`Page<view>`。本单把变更层拉回当前语言面，之后 Q13 才能继续。

## 设计取向

1. **投影必须是当前语言面的全函数**：语言里存在的事实，投影里必须有位置；漏掉一个事实就等于把"改了这个事实"判成没改。本单结束时，`expressionP`/`typeP`/`stepP` 对各自 sealed 接口的**全部**变体都有分支，`FieldP`/`InputP`/`ViewFieldP`/`FindP`/`PersistP` 覆盖上表的全部事实。
2. **兜底 `throw` 保留，但必须只对"未来新语法"可达**：不把兜底改成静默忽略（那会把新语法悄悄吞掉），而是补一个"语言面清单"单元测试：遍历每个 sealed 变体，断言投影有对应输出。新语法接入时该测试先红，接错了不会静默。
3. **只为"判断变化"服务，不改判断规则**：本单不新增变更操作、不放宽 `SIR-CHANGE-SCOPE-101` 的 scope 规则、不改 `PlannerCore` 的判定逻辑，只让投影忠实记录语义。追加字段会改变投影的 `equals` 语义，因此**必须**用 G0 五场景、Q3 的七个 CLI 工作流与 `ChangePlanningApplicationTest` 证明既有计划的种类与文件集合不变。
4. **证据用真实 SIR，而不是只测投影函数**：`expressionPForTest` 之类的钩子只作补充；主证据是"用 Q9/Q10/Q11 语法的候选能出计划，且只改一个事实时判为 UPDATE 而不是 NoChanges"。

## 修复清单（逐项说明"为什么要投影它"）

| # | 位置 | 补什么 | 为什么 |
|---|---|---|---|
| F1 | `expressionP` | `PresentExpressionP(target, field)`（Q10 `.present`） | 崩溃点；且"存在性判断的目标或字段变化"必须被判为变化 |
| F2 | `expressionP` | `ExistsExpressionP(entity, connectionField, conditions)`（Q11 `any(...)`） | 崩溃点；连接字段与条件都是语义（换连接即换查询） |
| F3 | `typeP` | `PageP(element)`（Q9 `Page<...>`） | `SirType` 六个变体里唯一没处理的；不修则修完 F1/F2 后仍崩 |
| F4 | `fieldP` | `versioned`、`patchSourceField` | Q10 的乐观锁与 PATCH 字段联动是生成代码的输入 |
| F5 | `ofFullDeclaration`（input 分支） | `kind`、`patchSourceEntity` | Q10 的 `patch of Entity` 决定载荷三态语义 |
| F6 | `viewFieldP` | `relation`（基数 + 目标 view） | Q11 的嵌套关联决定读取计划与批量预算 |
| F7 | `stepP`（Find/Persist 分支） | `FindP` 加 `orderKeys` 与 `page`；`PersistP` 加 `failure` | Q9 的排序/分页与 Q10 的条件持久化都是可观察语义；今天"只改 order by"会被判为没改 |

## 测试计划

**T1 投影单元（`sir-change` 测试树，新增 `SemanticProjectionParityTest`）**
- 每个 sealed `NormalizedExpression` 变体、每个 `SirType` 变体都有投影输出（含 F1–F3），兜底不可达。
- 每个"语言事实"的**灵敏度探针**：改该事实 → 投影不相等；不改 → 投影相等（F4–F7 各自一组，避免只测到"能跑不抛"的假绿）。
- 语言面清单：sealed 变体集合与投影分支集合一致（新语法接入时先红）。

**T2 行为（应用层，用真实 SIR）**
- **能计划**：基线/候选含 `.present`（Q10 写侧）与含 `any(...)`（Q11 关联切片）时，计划与 apply 正常返回，不再抛异常。
- **只改一个事实必须是 UPDATE 而不是 NoChanges**（每条都要能看到对应的文件集合变化）：
  1. 只改 `find` 的 `order by`；
  2. 只改分页子句（字段或错误）；
  3. 只把实体字段变成 `versioned`（或反向）；
  4. 只改 `patch of Entity` 输入规格；
  5. 只改视图字段的嵌套关联（关系方向或目标 view）；
  6. 只改 `persist ... else` 的错误。
- **既有计划不变**（回归）：G0 的 APPLY-UPDATE/CREATE/DELETE 五场景、`ChangePlanningApplicationTest`、`KcgCliWorkflowTest` 的 v01–v06 计划保持相同的种类与文件集合。

**T3 集成**：Q13 的 `ChangeLoopPlanningContractTest` 中因 BLOCK-1 中断的四项恢复执行并给出真实结论（其中 R4 的链形状问题属 Q13 自身的测试修整，不在本单）。

## 允许范围

- `sir-change/src/main/java/io/kcg/sir/change/internal/SemanticProjection.java` 及其同包投影记录（`DeclarationProjection`/`ExpressionP`/`TypeP`/`StepP`/`FieldP`/`InputP`/`ViewFieldP` 等）的最小改动。
- `sir-change` 测试树新增测试类与 fixture（可复用 `sir-toolchain-application` 与 `sir-change` 既有 fixture；如需新候选源，放在 `sir-change` 或应用测试资源下并登记）。
- 文档：本单、Q13 暂存件、路线图镜像、资格报告与覆盖清单的对应小节。

## 禁止范围（明确不做）

- 不改 `PlannerCore` 的判定规则、不放宽 `SIR-CHANGE-SCOPE-101`、不新增 `ChangeOperation`、不改 `ChangePlan` 的 pure-family 约束。
- 不动 `sir-semantic`/`sir-lowering-*`/`sir-generator-*`/`sir-toolchain-application` 的**生产代码**；不改 Parser/Semantic 的语言规则。
- 不做 Q13 的链形状修整、不做路由模板（Q12）、不做权限/G2/G3 范围。
- 不新增 Maven 依赖、不加 skip/exclude、不以降 skip 的方式让闸门变绿。

## 定向验证命令

```bash
# 开发期间（按需，只跑受影响的模块）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-change -am test
mvn -B -Dmaven.repo.local=/root/.m2/repository -o -pl sir-toolchain-application -am \
  -Dtest=ChangeLoopPlanningContractTest,ChangePlanningApplicationTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test

# 提交前一次性（按 AGENTS.md“测试资源约束”的批量口径）
# ① 两条全量闸门（离线、单构建、无 -T）
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
# ② Q9/Q10/Q11 三个业务场景回归 + Q13 的变更闭环场景（参数见 Q13 工作单）
```

## 完成门

1. F1–F7 全部落地，且**每一项都有灵敏度探针**（改该事实 → 投影不相等；不改 → 相等）。
2. `expressionP`/`typeP`/`stepP` 对其 sealed 接口的全部变体都有分支；"语言面清单"测试存在并能因新语法而变红。
3. `.present`（Q10）与 `any(...)`（Q11）的源上，计划与 apply 正常返回；不再出现 `unsupported ... variant` 异常。
4. 六类"只改一个事实"的候选各自产出 UPDATE 计划（不是 NoChanges），并指出对应的文件集合变化。
5. 既有计划回归不变：G0 五场景、`ChangePlanningApplicationTest`、CLI v01–v06 的种类与文件集合一致。
6. Q13 的四项 BLOCK-1 中断项恢复执行（其真实结论记入 Q13 的实施期订正，不因本单而预先假定通过）。
7. 生产代码改动**仅限** `sir-change` 的投影；`git diff --stat` 可核对该范围。
8. 两条全量闸门 BUILD SUCCESS，与基线 **749 / 0 / 0 / 5** 的差量逐模块归因（新增测试全部来自本单）；**不得新增 skip 或 exclude**。
9. 文档同步：本单状态、Q13 暂存件的解除说明、路线图镜像、资格报告与覆盖清单；`git diff --check` 无输出。

## 结果与证据（2026-09-23）

**生产代码改动只有一处**：`sir-change/src/main/java/io/kcg/sir/change/internal/SemanticProjection.java`（F1–F7 + 相应投影记录）。其余生产改动属于 Q11，与本单无关。

| 门 | 结果 | 证据 |
|---|---|---|
| F1–F7 | 全部落地 | `expressionP` 补 `PresentExpressionP`/`ExistsExpressionP`；`typeP` 补 `PageP`；`FieldP` 补 `versioned`+`patchSourceField`；`InputP` 补 `kind`+`patchSourceEntity`；`ViewFieldP` 补 `relation`；`FindP` 补 `orderKeys`/`page`；`PersistP` 补 `failure` |
| 灵敏度探针 | 6 项通过 | `sir-change` 的 `SemanticProjectionParityTest`（11 项）：`.present`/`any(...)`/`Page<...>` 不再抛异常；改 `order by`、分页子句、`any` 条件、`.present` 目标、`versioned`、嵌套关联目标、`persist else` 各自都让投影不同；未改则相同（确定性） |
| 语言面闸门 | 通过 | 同一测试按 `getPermittedSubclasses()` 遍历 `NormalizedExpression`/`SirType`/`NormalizedStep` 的全部变体，断言投影源码（去掉 import 行）逐个命名；新语法接入时会先红 |
| 行为面（只改一个事实 = UPDATE） | 5/5 通过 | `ChangeProjectionParityApplicationTest`（6 项）：`order by`、分页子句、`any` 条件、`.present` 目标、`persist else` 各自只改一处时计划为 UPDATE 且文件确有变化；对照项（同源候选）仍为 `NoChanges` |
| 既有计划不变 | CLI 侧通过 | `KcgCliWorkflowTest` v01–v06 与 `ChangePlanningApplicationTest` 全绿（`sir-change` 33/0、`sir-toolchain-application` 225 项中仅 Q13 的 3 项链用例失败、`kcg-cli` 30/0） |
| T3（Q13 的中断项恢复执行） | 已执行，给出真实结论 | R1（改 `any(...)` 过滤条件）与 R2（收紧输入约束）现在能计划并 apply；R3a/R3b（删写能力）撞上新缺陷 **BLOCK-2**，见 [`Q13-g1-change-loop.md`](Q13-g1-change-loop.md) |

## 实施期订正（C1–C3）

- **C1（完成门 4 的口径）**：工作单要求"六类只改一个事实的候选各自产出 UPDATE 计划"，但实测发现其中三类（`versioned` 标志、`patch of` 载荷、视图嵌套关联目标）**在变更词汇里没有对应操作**（`AddCapability`/`RemoveCapability`/`ModifyCapabilityWorkflow`/`ModifyInputFieldConstraints`/`ModifyUnreferencedInputFieldType`/`ModifyActorlessReadonlyCapabilityExposure` 都不以实体字段或视图字段为目标），因此无法通过一次真实计划来表达。这三类改为在**投影层**钉灵敏度（`SemanticProjectionParityTest`），工作流类事实（`order by`、分页、`any` 条件、`.present`、`persist else`）走真实计划。语义不变：事实被改，投影必须不同。
- **C2**：`present` 的目标字段必须是非 identity 的 patch 载荷字段（`SIR-FLOW-001`/`SIR-VALID-004`），因此灵敏度用例改在 `name` 与 `capacity` 之间变化；`versioned` 事实改在**没有 patch 载荷的实体**上验证，否则会与"patch 载荷要求 version 字段"的规则耦合（`SIR-VALID-003`）。这两条都是语言规则，不是本单放宽。
- **C3**：T3 的结论是**Q14 的完成证据**，但 Q13 的链仍不能跑完——新缺陷 BLOCK-2（删除版本化写能力时幸存文件 `CourseMapper.java` 变化，`SIR-CHANGE-IMPACT-202` 拒绝）不在本单范围，按 D6 停轮报告给负责人。

## 完成门状态

1–7 与 9 已满足；**门 6 的"恢复执行"已完成**（结论记入 Q13）。**门 8 的两条全量闸门按负责人 2026-09-23 的指示集中到提交前那一次运行**（AGENTS.md「测试资源约束」：同一批未提交改动期间重活集中一次执行），因此本单在提交前的那次运行完成前不声称门 8 为绿；该运行同时覆盖 Q9/Q10/Q11 三个业务场景与 Q13 的变更闭环场景。

## 验收记录（2026-09-23）

负责人 2026-09-23 回复"确认"，本单验收通过。逐条对照：

1. **F1–F7 与灵敏度探针**：`sir-change` 的 `SemanticProjectionParityTest` 11 项通过（`.present`/`any(...)`/`Page<...>` 不再抛异常；改 `order by`、分页子句、`any` 条件、`.present` 目标、`versioned`、嵌套关联目标、`persist else` 各自都让投影不同；未改则相同）。`sir-change` 模块 33 run / 0 fail / 0 error / 0 skip。
2. **语言面闸门**：按 `getPermittedSubclasses()` 遍历 `NormalizedExpression`/`SirType`/`NormalizedStep` 的全部变体，断言投影源码（剔除 import 行）逐个命名；重复条目 `...` 不再可能。
3. **行为面**：`ChangeProjectionParityApplicationTest` 6 项通过（五类"只改一个事实"各自产出 UPDATE 计划且文件确有变化；同源候选仍为 `NoChanges`）。
4. **既有计划回归**：`ChangePlanningApplicationTest`、`KcgCliWorkflowTest`（v01–v06）全绿；`sir-toolchain-application` 225 项中仅 Q13 的 3 项链用例失败（BLOCK-2，非本单回归），`kcg-cli` 30/0。
5. **T3**：Q13 的中断项恢复执行——BLOCK-1 解除（R1/R2 两轮可计划并 apply），暴露 BLOCK-2。
6. **范围**：生产代码改动仅 `SemanticProjection.java` 一个文件（`git status` 显示的其它生产改动属 Q11）。
7. **门 8（两条全量闸门）**：按负责人指示集中到同一批改动的提交前那一次运行；本单可验收内容已由定向证据覆盖。

## 裁决记录（2026-09-23）

1. **C1 接受**：工作单门 4 要求"六类只改一个事实"，实测其中三类（`versioned`、`patch of`、嵌套关联目标）在变更词汇里没有对应操作，改为在投影层钉灵敏度、工作流类事实走真实计划。
2. **C2 接受**：`.present` 只能用于非 identity 的 patch 载荷字段、`versioned` 事实改在无 patch 载荷的实体上验证——均为语言规则，不是放宽。
3. **C3 接受**：BLOCK-2 不在本单范围，按 D6 停轮报告。
4. **D0–D4 全部按推荐执行**（投影记录追加字段；`ExistsExpressionP` 投影连接字段；`orderKeys`/`page` 投影为列表 + `Optional`；兜底 `throw` 保留并配语言面闸门；`ofCapabilityContract`/`ofCapabilityExceptExposure` 同步）。
5. **门 8 的处理**：按负责人 2026-09-23 指示，同一批未提交改动的全量闸门集中到提交前一次运行（AGENTS.md「测试资源约束」），本单不因此扣减证据口径。

## 失败 / NOT_RUN 口径

- 任何 fail/error（除既有 5 项 Windows junction skip 外）即本单未完成；不得以降 skip、加 exclude 或改断言口径的方式"通过"。
- 若补投影导致既有计划**种类或文件集合变化**，那是行为变化而不是本单的成果：停轮、记录差异、报告，不自行放宽断言。
- 参考环境不可用时，T3 与业务回归记 `NOT_RUN`，完成门 6 与 8 的对应部分不成立；`NOT_RUN` 与通过必须区分。

## 需要负责人裁决的设计选择（D0–D4）

| # | 决策 | 推荐 | 备选与代价 |
|---|---|---|---|
| D0 | 投影记录用"追加字段"还是"新建记录类型" | **追加字段**（保持单一记录类型，`equals` 语义随之收紧） | 新建类型：两套投影并存，`PlannerCore` 侧要选一套，复杂度不划算 |
| D1 | `ExistsExpressionP` 是否投影连接字段 | **投影**（`entity` + `connectionField` + 条件） | 只投影实体与条件：换连接（子实体的另一个 `Ref`）会被判为没改 |
| D2 | `orderKeys`/`page` 的投影形态 | **`List<OrderKeyP(fieldSymbol, descending)>` + `Optional<PageP(pageField, sizeField, errorSymbol)>`** | 只投影"有没有分页"：改分页字段或错误会被判为没改 |
| D3 | 兜底 `throw` 是保留还是改成诊断 | **保留（fail-fast）**，并配"语言面清单"测试 | 改成诊断：新语法会静默按"未变化"处理，风险更大 |
| D4 | `ofCapabilityContract`/`ofCapabilityExceptExposure` 是否同步补齐 | **同步**（它们也调用 `typeP`，且契约比较要一致） | 只补 `ofFullDeclaration`：同一语言事实在两条比较路径上表现不一致 |

## 实施期订正协议

执行中若发现推荐方案与冻结依赖、既有契约或参考环境冲突，按 Q9–Q13 的做法处理：**保持原意图、记录证据、写入本单的"实施期订正"节，禁止范围不变**；不得为让用例通过而放宽完成门。

## 交接记录

**状态：`AWAITING_ACCEPTANCE`（2026-09-23）。** 负责人 2026-09-23 在 BLOCK-1 的三个选项中确认 **B1**（先立这张小单，完成后回到 Q13 继续），随后回复"按推荐"批准 D0–D4。实施顺序已完成：T1 投影单元测试（先红）→ F1–F7 → T2 行为测试 → T3（Q13 的中断项恢复执行，BLOCK-1 解除，暴露 BLOCK-2）。等负责人验收；验收后本单归档，Q13 回到 `ACTIVE_WORK.md` 继续（其删除轮需先裁决 BLOCK-2）。Q13 的实测结果、实施期订正 C1–C4 与 BLOCK-1 全文见 [`Q13-g1-change-loop.md`](Q13-g1-change-loop.md)。
