# 可选文本用于 containsLiteral：实施设计

基线 `dccdb56`。状态：**负责人 2026-10-31 确认 §8 三项，已实施**（与本次代码、测试、文档同一提交）。

## 1. 要解决的问题

语言参考教的可选筛选写法是 `input.kind is none or item.kind == input.kind`（§5.3“可选值与可选筛选”）：可选值参与比较时，空值让比较为 false，`is none` 那一侧负责“没传就不限制”。`containsLiteral` 是唯一的例外：右侧必须是非可选文本（`TypePass.java:1045–1052`）。

“按关键字搜索、没给关键字就全部返回”是很普通的需求，作者自然会把关键字声明成 `Optional<Text>`，再照上面的写法写 `input.keyword is none or item.title containsLiteral input.keyword`，然后被拒绝（`SIR-TYPE-001`）。这正是 AGENTS.md 设计规则第 2 条说的“普通需求在 SIR 里变得绕口”。

数据上的证据：

- 历史上 96 份静态失败的教师候选里，11 份带这条错误，涉及博客两题、选课从零写 p1/p2/p3、query-slice 从零写 p3。除博客改写外，这几道题目前都没有合格答案。
- v8 手册第 95 行专门警告过这条限制，教师仍连续三轮犯同一个错；博客 g9 从零写两份候选修复后逐字节相同，仍卡在这里。
- 要绕开它，只能把关键字改成必填 `Text`，靠“空字符串包含于任何文本”实现“空关键字匹配全部”（博客参考答案的写法）。这要求作者知道一个和业务无关的技巧，而且“没传”和“传空串”被迫变成两种行为（没传返回 400）。

G7（设计文档 12 §17）把它列在“没有做”里，原因是那一阶段只做比较的范围限制（“G7 之外的缺口没有借此展开”），没有反对它的理由。本设计重新打开这一项，需要负责人确认。

## 2. 新规则

> `containsLiteral` 的两侧都可以是可选文本。任一侧为空时，结果为 false，和 `==` 等比较一样；`not (a containsLiteral b)` 在有空值时为 true。左侧仍须是 `item.<文本字段>`，右侧仍不能是实体字段。

| 情况 | 新行为 | 和现状比 |
| --- | --- | --- |
| 右侧 `Optional<Text>`，有值 | 字面包含，和现在相同 | **变化**：原来编译期拒绝 |
| 右侧 `Optional<Text>`，空 | false | **变化**：原来编译期拒绝 |
| 左侧可选字段，列为 NULL | false；外面套 `not` 时为 true | **变化**：现在生成 `col LIKE ...`，NULL 时为 UNKNOWN，套 `not` 后这一行被排除，和比较的真值表不一致 |
| 两侧都非可选 | 不变 | 生成的 SQL 逐字节不变 |
| 右侧是空字符串 `""` | 匹配所有非空列 | 不变 |
| `any(...)` 里的 `containsLiteral` | 不变：工作流里的 `any` 不支持它（`SpringBootInputValidator.validateExistsCondition` 拒绝）；视图计数字段只接受字面量（`RelatedCountRules`） | 不变 |
| 写法 `input.keyword is none or item.title containsLiteral input.keyword` | 合法，含义是“没给关键字就不限制” | **变化**：原来编译期拒绝 |

**为什么不做“判空后自动收窄类型”。** 收窄能让 `is present and ... containsLiteral input.keyword` 也通过，但它是一条通用的类型规则，会牵动算术、成员访问等所有用到可选值的地方，G7 明确不做。本设计只让 `containsLiteral` 和比较遵守同一张真值表，不引入新的类型规则。

**为什么不是“空值就自动省略这个条件”。** 和 G7 的理由一样：自动省略会让筛选，尤其是授权相关的筛选，在作者没写的情况下被放宽。作者仍要明确写 `is none or`。

## 3. 改哪里

改动沿用比较已有的“可选值计划”做法：语义层只放开类型，下降阶段把“空值时为 false”写进 IR，IR 校验独立复核，渲染器只照计划输出，不自己判断。

| 模块 / 文件 | 改动 | 原因 |
| --- | --- | --- |
| `sir-semantic/.../internal/TypePass.java:1045–1052` | 右侧先去掉 `Optional` 包装再检查是否文本；报错文字删掉“右侧必须是非 Optional”那半句 | 规则本身 |
| `sir-lowering-spring-boot/.../model/SpringExpression.java` 的 `StringMatch` | 新增 `boolean absentIsFalse` | 和 `PresentComparison` 一样，把空值语义写进 IR，渲染器不从类型猜 |
| `.../internal/SpringBootModelLowerer.java:1196` `collectStringMatches` | 任一侧是 `OptionalType` 时置 `absentIsFalse = true` | |
| `.../internal/SpringBootIrValidation.java:1357` 附近 | 复核：`absentIsFalse` 必须等于“任一侧是可选值” | IR 校验要独立确认计划和操作数一致 |
| `sir-generator-spring-boot/.../internal/WorkflowRenderer.java:1330–1340` | `absentIsFalse` 时生成 `COALESCE(<col> LIKE CONCAT('%', {0}, '%') ESCAPE ..., FALSE)`，否则保持原样 | 和可选比较的 `COALESCE(比较, FALSE)` 一致；`escapeLikeLiteral` 已能接受 null（`ServiceRenderer.java:179–182`），不用改 |
| `docs/SIR_LANGUAGE_REFERENCE.md` | §5.3“可选值与可选筛选”（:1222 附近）补一个关键字筛选的例子；§6 :2012 删掉“`containsLiteral` 的右边仍要求非可选文本”，改为它和比较遵守同一张真值表 | 文档和行为一致 |
| `docs/design/12-feature-semantics-and-semantic-types.md` §17 | “没有做”一句后注明此项已由本设计补上 | 设计记录前后一致 |

手册 v9 单独出（计划第 2 步），本次不改 prompt。

## 4. 测试

| 测试 | 处理 |
| --- | --- |
| `sir-semantic/.../QueryDiagnosticMessageTest.java:116–121` | 原来断言“`is none or` / `is present and` + 可选右侧”报错，改为断言通过（含义随规则改变，不是放松检查） |
| 同上，新增 | 右侧可选文本、左侧非文本仍报错；右侧是 `item.<字段>` 仍报 `SIR-VALID-001` |
| `sir-lowering-spring-boot` 新增单测 | 右侧可选、左侧可选、都不可选三种情况的 `absentIsFalse`；手工改坏计划时 IR 校验报错 |
| `sir-generator-spring-boot/.../GeneratorOptionalValueContractTest.java` | 可选时生成的 SQL 带 `COALESCE(... LIKE ..., FALSE)`；非可选时和现在逐字节一致 |
| `LanguageReferenceExamplesTest` | 改了语言参考，单独跑一次 |

**真实数据库。** 不新增业务 IT。现有业务 IT 的参考答案都不用可选关键字，生成结果不变；G9 题目的参考答案在 test 集题目的判卷闭包里，改它会让冻结题目的哈希失配，所以不往里加。`COALESCE(NULL LIKE ..., FALSE)` 在 MySQL 上为 FALSE 是标准行为；真实库上的证据，由之后用可选关键字写法的博客从零写候选在业务门上取得（`BlogPublicationChecks` 已覆盖空关键字 `keyword=` 匹配全部、`%`/`_`/`\` 按字面处理；它不检查“不传 keyword”，所以把关键字声明成可选不会和判卷冲突：`keyword=` 无论绑定成空串还是空值，结果都是全部已发布文章）。

本机只跑上面几处定向测试（`sir-semantic`、`sir-lowering-spring-boot`、`sir-generator-spring-boot`，`-Dtest=...`），全量和业务 IT 交给推送后的 CI。

## 5. 兼容性

- **现有合法代码不受影响。** 这是放宽规则。仓库里 41 处 `containsLiteral` 用法两侧都是非可选文本，生成的 SQL 逐字节不变；新的 `COALESCE` 只出现在原来编译不过的写法里。
- **左侧可选字段的生成结果会变**（加 `COALESCE`）。仓库里现在没有这种用法，变化只影响以后的新代码，而且是把它对齐到语言参考写明的真值表。
- **工具链摘要会变。** 目前没有等待判卷的候选，可以直接推送。已归档的 49 条合格答案绑定当时的工具链，不受影响。
- **已有失败候选不自动重判。** 之后按计划第 3 步的预算，在新工具链上重新采样或修复。

## 6. 三条设计原则

1. **新业务主要靠组合已有能力？** 是。没有新语法，复用比较的可选值真值表、`COALESCE` 渲染和 IR 计划校验的做法。
2. **普通需求是否不再绕口？** 是。“可选关键字搜索”可以按文档已有的可选筛选写法直接写。
3. **一处修复能否让一类业务受益？** 是。所有按可选文本做字面搜索的查询一起受益，同时修正左侧可选字段在 `not` 下的真值不一致。

## 7. 不在本次范围

- 判空后的类型收窄。
- `any(...)` 内部支持 `containsLiteral` 或请求参数。
- `matches` 运算符（目前两侧都要求 String，不在本次）。

## 8. 待负责人确认

1. 采用 §2 的规则，重新打开 G7 列为“没有做”的这一项（推荐）。
2. 左侧可选字段一并改为 `COALESCE`，和比较的真值表对齐（推荐：同一个规则的两半，分开做会留一半不一致）。
3. 不新增业务 IT，真实库证据由后续候选的业务门取得（推荐，理由见 §4）。
