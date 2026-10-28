# 非可选输入必填：实施设计

基线 `ebd82c7`。依据 `required-input-analysis.md`（现状、历史、影响清单）和本次重读的源码。状态：**设计待负责人确认，未实施**。

## 1. 要解决的问题

非 Optional 的数值输入只在参与加、减、乘、取负、内置函数或 repeat 次数时才检查必填（`ArithmeticInputs.java`）。只做比较或直接存库的字段，没传或传 null 会进入工作流，在比较时拆箱 NPE 或写库时失败，最后返回 500。要得到 400，作者得写 `input.weekday - 1 >= 0` 这种绕弯写法（`topics/course/project.sir:117–120`）。“不是 Optional 就是必填”是普通需求，现在却很绕口，教师模型和学生模型都学不会（`course-selection/failure-analysis.md` §1）。

## 2. 新规则

> 能力的输入里，每个非 Optional 字段在工作流开始前检查：没传或 null 返回 `400 INVALID_REQUEST`，`fields` 给出实际 HTTP 字段名和 `NotNull`。只有一个例外：只被本能力的分页步骤当作 page/size 使用的字段，保留默认值 1/20。

逐项说明：

| 情况 | 新行为 | 和现状比 |
| --- | --- | --- |
| 普通非 Optional 字段（数值、时间、布尔、枚举、Token、普通文本、Ref） | 缺值 → 400 NotNull，不管工作流是否用到、怎么用 | **变化**：原来只有算术依赖必填 |
| Ref 字段 | 报 HTTP 字段名，如 `categoryId` | 与现有算术检查一致 |
| Name/Code/`where notBlank` 文本 | 缺值仍由 Controller 的 `@Valid` 先拦截，报 `NotBlank` | 不变。为它改校验顺序牵动面大、收益小，文档写明即可 |
| 金额（带 precision） | 已有 DTO `@NotNull`，先于此检查 | 不变 |
| Optional 字段 | 可缺省、可 null | 不变 |
| page/size 只用于分页步骤 | 缺省取 1/20 | 不变 |
| page/size 还参与了算术、函数或 repeat | 必填 | 不变（现有规则） |
| 同一 input 被多个能力共用 | 按能力判断：A 能力用它分页就有默认，B 能力不用就必填 | **变化**：原来 B 能力不用就不查 |
| 路径参数 | 路径值写入后再查（检查在服务层，晚于 Controller 合并路径） | 不变 |
| 局部更新（patch）输入 | 本规则不适用：id、expectedVersion 已由信封 `@NotNull` 保证；changes 子字段保持“没传就保留” | 不变 |

**为什么不在 DTO 上统一加 `@NotNull`**：`PageNo`/`PageSize` 展开后就是普通 Int32，在 DTO 层分不出哪个字段是分页参数；同一个 DTO 又可能被多个能力共用，分页豁免只能按能力判断。现有“能力级必填计划”正好按能力生成、在服务层工作流之前执行，所以沿用这套机制，只扩大它覆盖的字段。这也是 2026-10-21 引入算术检查时没选 DTO 方案的原因（设计文档 `docs/design/12-feature-semantics-and-semantic-types.md:704,727–731`）。

**为什么分页豁免看用法而不看名字**：按名字猜（叫 page 的就豁免）会误伤普通业务字段，也和项目“不按名字匹配”的做法冲突（`SpringBootModelLowerer` 中 patch 字段的注释）。分页步骤（find … Page、分组汇总的 Page）在 IR 里明确记录了 page/size 绑定的是哪个输入字段，按这个判断是确定的。

## 3. 改哪里

改动集中在“必填计划”这一条链上，Parser、语义检查、DTO 渲染和数据库结构都不动。

| 模块 / 文件 | 改动 | 原因 |
| --- | --- | --- |
| `sir-lowering-spring-boot/.../internal/ArithmeticInputs.java` → 改名 `RequiredInputs.java` | 保留现有算术依赖收集；新增 `members(InputDeclaration, workflow, inputSymbol)`：取输入里全部非 Optional 字段，按声明顺序，去掉“只作分页参数”的字段（被算术收集到的分页字段仍保留） | 规则的唯一实现点。lowering 生成计划和 IR 校验复核计划都调用它，两边不会各写一套 |
| `.../model/SpringBootDeclaration.java` | `RequiredArithmeticInput` → `RequiredInput`，`arithmeticInputs` → `requiredInputs`；字段不变 | 名字要说清它是什么，不然以后读代码的人会以为只管算术 |
| `.../internal/SpringBootModelLowerer.java:704–708` | 用 `RequiredInputs.members(...)` 生成计划；patch 输入不生成 | |
| `.../internal/SpringBootIrValidation.java:727–750` | 复核改为对照 `RequiredInputs.members`；保留“计划字段必须是非 Optional”“patch changes 不能进计划”两条硬性检查 | IR 校验要独立确认计划与正式输入一致 |
| `sir-generator-spring-boot/.../internal/WorkflowRenderer.java:66–78` | 只改名（`renderRequiredInputs`、变量 `requiredInputErrors`）；生成逻辑不变 | 渲染方式已经满足需求 |
| 语言参考 `docs/SIR_LANGUAGE_REFERENCE.md` | §3.3 输入（:391–446）写明新规则和例外；§6 :1954“算术用到的输入必须有值”改写为通用规则；:255 timeZone 一句同步；§5.3/§5.11 写明分页默认只在“只作分页参数”时成立 | 文档和行为一致 |

## 4. 测试

| 测试 | 处理 |
| --- | --- |
| `sir-generator-spring-boot/.../ArithmeticInputRuntimeTest.java` → 改名 `RequiredInputRuntimeTest` | 改 :28–31 处“无关字段可缺省”的期待；新增：只做比较的字段缺值 → 400 NotNull；Ref、Flag、Moment、枚举、普通 Text 缺值 → 400；`Paged` 不传 page/size 仍成功；`PageArithmetic` 的 page 仍必填；Optional 仍可缺省；缺值时没有进入工作流 |
| `sir-lowering-spring-boot/.../MultiplicationLoweringTest.java:30–36,61–74,111` | 随改名更新；IR 硬化用例（计划缺项、多项、字段错）保留并对新计划生效 |
| 新增 lowering 单测 | 共用 input：分页能力豁免、非分页能力必填；patch 输入不生成计划 |
| 业务 IT | 不新增。`CourseSelectionChecks:96–105` 已经检查四个数值字段的 missing/null，CI 会证明参考答案在新规则下仍通过；其余 IT 的请求已核对，见 §5 |

本机只跑上面三处定向单测（lowering、generator 两个模块，`-Dtest=...`）。业务 IT 交给推送后的 CI。

## 5. 兼容性

已逐个核对 `required-input-analysis.md` §4 列出的风险点：

- **会变严、但现有测试请求已带齐**：`money.sir` 的 CreateWallet（`id`）、DebitBalance（`peer`）。Money IT 的请求本来就带这些字段（`MoneyBusinessConformanceIT.java:86–88,148`）。
- **会变严、但没有 HTTP 测试调用**：`multiplication.sir`/`shop-conformance.sir` 的 Wide（quantity/left/right）、`related-request-values.sir` 的 note、`course-admin-enrollment-add-list-refs.sir` 的 ListCourseRefs（page/size 不用于分页，变为必填）。这些只做静态编译，不受影响。
- **必须保持不变，测试会验证**：QuerySlice 不传 page/size（`QuerySliceBusinessConformanceIT:201–208`）；Calendar、GroupedSum 的 Optional 月份、区间；WriteSlice、Money、CrudAccess 的 PATCH 缺省与 null；G9 的 Optional 筛选。这些不是 Optional 就是分页或 patch，按 §2 都不变。
- **参考答案不用改**：选课参考的 `input.weekday - 1 >= 0` 在新规则下仍然成立，只是不再是必需写法。

**一个时间上的约束**：这次改动会改变工具链摘要（`toolchain_core_sha256`）。改动推送后，所有已静态检查、但还没跑完业务门的候选，都会因工具链不一致被业务门拒绝（`corpus._check_runtime_binding`）。所以**必须等当前这批业务门全部结束并归档后再推送**。已经归档的合格答案不受影响，它们绑定的是当时的工具链；以后的新采样在新工具链上重新检查。各题 `reference_evidence` 也要随新的 verify 运行更新。

## 6. 三条设计原则

1. **新业务主要靠组合已有能力？** 是。没有新语法、新约束或新关键字，复用已有的能力级计划、`ValidationSupport.notNull` 和错误信封。
2. **普通需求是否不再绕口？** 是。“这几个字段必填”不再需要写减法；作者只要不写 Optional 就行。
3. **一处修复能否让一类业务受益？** 是。所有类型的非可选字段（时间、布尔、枚举、Ref、文本）一起受益，不只修数值。

## 7. 不在本次范围

- patch 的 changes 子字段显式传 null、而目标字段非 Optional 的情况。现在金额有约束兜底，其他类型可能写库失败返回 500。这是另一条规则（“局部更新里的 null 是清空还是非法”），单独设计。
- Name/Code 缺值报 `NotBlank` 还是 `NotNull` 的统一。
- 改动生效、代码和文档都验证通过后，再出手册 v7 同步这条规则；v6 第 38 行目前描述的是旧规则。

## 8. 待负责人确认

1. 采用 §2 的规则（推荐）。
2. 等当前业务门全部归档后再推送实现（推荐，原因见 §5）。
3. 改名 `RequiredArithmeticInput` → `RequiredInput`，和行为改动放在同一个提交（推荐：名字和行为是同一件事）。
