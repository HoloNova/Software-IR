# 分层架构编码计划（草案，待负责人审核）

> 历史文档（分层迁移 R1–R5 与“以 Git 取代自存版本历史”G1–G4 的计划、报告和更早的阶段控制）。其中提到的状态目录、基线 Bundle、CURRENT、日志与恢复、`context`/`plan` 命令、单文件 0.1 入口均已删除；现状见 [项目状态](../../PROJECT_STATUS.md)。

- 日期：2026-10-13。状态：**草案，未授权任何代码改动**。本文件未提交、未推送。
- 依据：设计 08、ADR-026、迁移路线（commit `3555ec5`），以及本轮对源码的实际核对（只读，未运行任何构建或测试）。
- 本文只回答一件事：**R1～R5 具体写什么代码、为什么写、怎么证明对**。方向已由 ADR-026 冻结，本文不重新讨论方向。

## 0. 先看结论

1. 建议先只授权 **R1（统一语义结构）**。它只往语义层加"结构说明"，不改任何现有行为，风险最小，却是后面所有阶段的地基。
2. R2（增删改指令 + 写回 SIR）是真正的难点，写在本文第 4 节，但**要等 R1 报告后再单独授权**。R3～R5 只给轮廓。
3. 有 5 个需要你拍板的问题，集中在第 7 节。其中 D1（结构说明怎么写）和 D3（编辑时遇到注释怎么办）会改变代码形状。

## 1. 现状：我实际看到的代码

| 事实 | 位置 | 对计划的含义 |
| --- | --- | --- |
| Change 有 6 个专用操作；`ChangeSet` 构造器强制 `operations.size()==1` | `sir-change/api/ChangeOperation.java`、`ChangeSet.java` | 旧合同不放宽，新内核另起 |
| `ChangePlan` 构造器直接拒绝 UPDATE/CREATE/DELETE 混合 | `ChangePlan.java:43-47` | 同上，新的混合计划用新类型，不改这个 record |
| 语言知识在 Change 里又写了一遍：`SemanticProjection` 888 行，`PlannerCore` 引用它 58 处 | `sir-change/internal/` | 这就是要消除的"第二份语言清单" |
| 这份清单漏过东西：Q14 的 `SemanticProjectionParityTest` 专门防"新语法崩溃/静默漏判"，注释里写明 `.present`、`any(...)`、`Page<>` 都曾漏过 | `SemanticProjectionParityTest.java` | R1 要让"漏分类"在**构建期**失败，而不是靠这种手写文本匹配 |
| 当前 `NormalizedError` 有 `httpStatus`，而旧投影的 Error 部分只含 id、name | `NormalizedError.java:15` 对比 `SemanticProjection.java` | 旧投影已有漏项；R1 盘点必须从模型字段出发，**不能抄旧投影** |
| 语义模型全是 Java `record`/`sealed`：声明 6 种，步骤 7 种，表达式 12 种，类型 6 种 | `sir-semantic/model`、`type` | 要分类的东西数量有限，可以逐个盘点 |
| 注释和空白被 ANTLR 丢到隐藏通道，AST 里没有任何注释字段 | `Sir.g4:434-447`；`sir-parser` 只在 lexer 报错处提到 comment | 写回 SIR 时，**被重写的声明里的注释会丢**，这是 R2 的核心问题 |
| 全仓没有 SIR 的 writer/printer | 全仓搜索 `SirWriter/Printer/Unparse` 无结果 | 写回是新交付，不是接线 |
| 每个 AST 声明都有精确 `SourceSpan`（行列 + code point 偏移） | `AstCapabilityDecl` 等 | 可以做到"没改的声明保留原字节"，只重写改动的声明 |
| 已有 `@id("...")` 持久身份（能力、实体字段），编码在 `SymbolId` 里 | `DeclarationIdentity.java` | 选择器可直接用，不另造 nodeKey |
| 编译链已有共享入口 `CompilationStages`、`ProjectCompilation` | `sir-toolchain-application/internal` | R3 复用，不写第三套 |
| 工程是 Java 21 + 纯 Maven，无注解处理器，无代码生成插件 | 根 `pom.xml` | D1 的选择要考虑这点 |

## 2. 总体形状

```text
首次：SIR 字节 ─Parse→ AST ─Resolve/Type/Validate/Normalize→ SoftwareSnapshot ─Lowering→ Generator → 文件
                                                        │
修改：                                     SoftwareSchema（R1：这个模型里"什么是事实/身份/引用/位置/派生"）
                                                        │
 SoftwareSnapshot ─(按 schema 读)→ SoftwareDraft ─(Add / Delete / Update 一批)→ 新 Draft
        │                                                             │
        └──────── SemanticDelta（R2：软件变了什么）←──────────────────┤
                                                                      ↓
                              写出新 SIR 字节（R2）→ 重新走一遍正式 Parse→…→Normalize
                                                                      ↓
                    完整重新 Lowering/Generate → FileDelta（R3，完整文件对比）→ 应用/恢复（R4）
```

一句话理解：**以前每加一种改法就在 Change 里加一个类；以后 Change 只会"加、删、改"三个动作，认识软件长什么样的责任全部交给语义层的那份结构说明。**

## 3. R1：统一语义结构（建议先批准这一阶段）

### 3.1 做什么

让语义层有**一份**机器可读的结构说明 `SoftwareSchema`，说清楚现有每一个模型字段属于哪一类，并提供一个只读的通用结构视图。

每个字段必须归入下面一类，否则构建失败：

| 类别 | 例子 | 以后怎么处理 |
| --- | --- | --- |
| 事实 | 字段类型、约束、排序键、`httpStatus`、分页、workflow 步骤 | 进入语义差异，可被指令编辑 |
| 身份与引用 | `SymbolId`、引用目标、引用角色 | 按身份比较，不按名字相似度猜 |
| 来源 | `span`、`sourceNodeId`、`directNamedTypeReferenceSiteId` | 单独比较，位置变化不算业务变化 |
| 派生 | `SymbolTable`、`referenceBindings`、`expressionTypes`、`diagnostics` | 指令不写，候选重新分析得到 |

另外每个集合要标明"有序"还是"按键无序"（声明按身份，workflow 步骤、`order by`、枚举成员按顺序），避免把所有 List 排序后比较。

### 3.2 为什么存在（对应你要求的"解释为什么存在"）

- **为什么要有这份说明**：现在"哪些字段算业务变化"只存在于 `SemanticProjection` 的手写代码里，而且已经漏过 `httpStatus`。让语义层自己声明，Change 就不再需要第二份清单。
- **为什么不用"反射比较整个 record"**：record 里混着 `span`、绑定表、缓存。整体比较会把改一行空格当成业务变化。必须有人明确说每个字段是什么。
- **为什么不用无类型 JSON 树**：现有强类型模型（`SirType`、`NormalizedStep` 等 sealed 类型）是 Lowering 和类型检查的基础，不能换掉。

### 3.3 怎么写（推荐方案，见 D1）

1. 新增一组很小的标注：`@Fact`、`@Identity`、`@Reference(role)`、`@Source`、`@Derived`，以及 `@Ordered`。标注写在现有 record 的组件上。
2. 给现有模型 record 加标注。**只加标注，不改字段、不改构造器、不改行为**。涉及 `model/` 下约 12 个声明级 record、`NormalizedStep` 的 7 个步骤、`NormalizedExpression` 的 12 个表达式、`type/` 下 6 个类型，以及 `NormalizedSemanticModel` 自己。
3. `AstMetadata`、`AstTarget` 在 `sir-parser` 里，语义层不能给别人的模块加标注，所以在 schema 里为这 2 个 record 写显式条目，并走同一个完整性检查。
4. 新增包 `io.kcg.sir.semantic.schema`：
   - `SoftwareSchema`：节点种类、字段（键、值类型、类别、有序性）、sealed 子类型清单。
   - `StructureView`：给一个 `NormalizedSemanticModel`，按 schema 产出**只读**的通用节点树（节点种类 + 按键属性 + 子节点），可去掉"来源"和"派生"后比较。
5. 完整性检查（构建期测试，不靠源码文本匹配）：
   - 每个 record 组件必须有且只有一个类别标注，缺一个就失败。
   - 每个 sealed 接口的 `permits` 必须全部在 schema 里登记。
   - 新增一个字段或子类型而没分类，测试立刻红。

### 3.4 不做什么

不改 `sir-change`、Lowering、Application、CLI；不做指令；不写 SIR；不删 `SemanticProjection`；不引入注解处理器或新依赖。

### 3.5 怎么证明（先跑定向测试）

| 测试 | 证明什么 |
| --- | --- |
| `SoftwareSchemaCompletenessTest` | 所有 record 组件和 sealed 变体都分了类；故意构造一个未分类字段会失败 |
| `SoftwareSchemaSensitivityTest` | 对每一类事实字段，只改它一个值，结构视图就不同；只改 `span`/行号，视图相同。**必须包含 `httpStatus`、排序、分页、PATCH 的 present、`any(...)`** |
| `StructureViewDeterminismTest` | 同输入多次产出完全相同 |
| 一个只加不改的 `sir-change` 测试 | 在现有测试用的 `.sir` 样例上，旧 `SemanticProjection` 能看出的每个差异，新视图也看得出（新视图至少不比旧的少） |

规划时按 `AGENTS.md` 的小服务器假设，只跑 `sir-semantic` 的目标测试、全量交 CI。负责人 2026-10-13 已明确 Claude 直接在会话环境本地测试，见 [Claude 执行规则](../../CLAUDE_EXECUTION_POLICY.md)。

### 3.6 完成标准

- 现有字段 100% 分类，清单可在报告里逐项核对；
- 现有 `sir-semantic`、`sir-change` 的旧测试在 CI 上保持原结果（R1 对旧行为零影响）；
- 报告列出分类中遇到的**模糊字段**（例如 `directNamedTypeReferenceSiteId`、`NormalizedField.versioned`）及我的归类理由，请你复核。

## 4. R2：指令编辑与写回 SIR（轮廓较细，待 R1 后再授权）

### 4.1 先做只读探针，再写生产代码

探针要回答三个事实问题，答案会改变后面的实现：

1. 每个声明的 `SourceSpan` 是否完整覆盖该声明的全部文本，包括前导的 `@id("...")`；
2. 声明内部的注释，能否通过"扫描该 span 内的隐藏通道 token"可靠发现；
3. 把一个已正规化的能力还原成 AST（括号、优先级、字符串转义、`@id`、生成策略）后打印，再重新解析，能否得到等价模型。

### 4.2 要写的东西

| 块 | 内容 | 为什么存在 |
| --- | --- | --- |
| 指令 | `Add(容器, 槽位, 值, 位置)`、`Delete(选择器, 预期旧值?)`、`Update(选择器, 新值, 预期旧值?)`；`CommandBatch` 绑定基线、schema 版本、有序指令列表 | 固定三种动作，业务由 schema 决定而不是由指令名决定 |
| 选择器 | 第一版 = **声明身份（`SymbolId`）+ schema 槽位路径**；workflow 整体可作为一个子树被 Update | 避免要求用户给每个表达式维护 id；诚实地不承诺未实现的永久 nodeKey |
| 草稿 | `SoftwareDraft`：由 schema 视图构造的不可变结构；不带旧绑定和类型结果 | 防止拿旧分析结果当新证据 |
| 批内句柄 | 同一批里后面的指令可以引用前面刚新增的对象 | 让"先加 Input 再加引用它的能力"成为一批，而不是拆成两轮 |
| 语义差异 | `SemanticDelta`：对旧、新 `StructureView` 做通用对比，输出新增/删除/更新 | 取代 `SemanticProjection` + `PlannerCore` 里的逐类比较 |
| 写回 | 草稿 →（语义层）AST 形态 →（解析层）SIR 打印器 → 字节 | 打印器放在解析层，只认 AST，不依赖语义层，避免模块循环 |
| 保字节 | 未改动的声明、文件头、声明之间的注释，**按原 span 原样拷贝**；只重打印被改动的声明 | 不会在整份重排时动用户的注释和格式 |
| 回读核验 | 写出的新字节完整重走 Parse→Resolve→Type→Validate→Normalize，再与指令目标对照 | 名称只在这一次 Resolve 里绑定（遵守规则 3）；写出但不能重新打开的内存模型不算交付 |

### 4.3 落点

- `sir-parser`：新增 SIR 打印器（只认 AST）。
- `sir-semantic`：草稿 ↔ AST 转换。
- `sir-change`：新增包（例如 `io.kcg.sir.change.command`），放指令、批、选择器、差异；**旧的 `api`、`internal` 不动**，旧入口和旧测试继续按原合同运行。

### 4.4 验收样例（取自设计 5.4）

同一批三条指令：新增搜索 `Input`、新增引用它的查询能力、修改旧报名查询的 workflow 条件。要求：

- 整批成功，写出的新 SIR 能被独立重新打开；
- 失败（例如目标陈旧、最终引用缺失）时整批不产出任何结果；
- 再换一个商品/订单的同类组合，**不增加任何指令种类**；
- 一个"新语法探针"：模拟在 schema 里新增一个受控结构，Add/Delete/Update 和差异无需改 Change 代码。

### 4.5 暂不承诺

跨改名/跨文件移动的稳定编辑、永久 nodeKey、工程提交与数据库迁移、多源编辑落盘。

## 5. R3～R5 轮廓（不展开，进入前各自再做事实核对）

| 阶段 | 要写的 | 进入前必须先查 |
| --- | --- | --- |
| R3 统一编译与只读计划 | `CompilationService` 统一单源/多源；`FileDelta` 对完整受管文件集合做 CREATE/UPDATE/DELETE 混合对比；source-only 变化 | `CompilationStages` 与 `ProjectCompilation` 的旧诊断映射合同；共享/project 级文件如何归属 |
| R4 版本应用与恢复 | 新事务协议、一个 CURRENT 绑定"保存的源 + 完整派生清单"、连续更新、中断恢复 | 现有 UPDATE/CREATE/DELETE/Rename 事务哪些原语可复用；旧日志格式不被放宽（ADR-020 方向不变） |
| R5 构建与真实验证 | 构建/启动/业务验证端口，结果分 STATIC / BUILD / START / BUSINESS | Q23 真实评测代码的可复用面；依赖仓库、进程资源、数据库互斥 |

## 6. 对照你的三条设计自检

1. **新业务是否主要靠组合已有能力？** 是。普通新增业务只提交指令或改 SIR，不改编译器；只有新语言结构才改 Parser/Semantic/Target。
2. **普通需求在 SIR 中是否绕口？** R1 不改语言。R2 只有三个动词，混合改动就是把三条指令放进一批；不出现 `AddCapabilityWithInputAndModifyWorkflow` 这类组合名。
3. **修复一种实现问题，能否让一类业务受益？** R1 的完整性检查把"新语法漏分类"从个例修复变成构建期必然失败，影响所有当前和未来的结构。

## 7. 需要你拍板的问题

| # | 问题 | 选项与我的建议 |
| --- | --- | --- |
| D1 | 结构说明怎么写？ | **推荐 A**：在 record 组件上加标注，运行时读取标注建 schema，加完整性测试；无需新构建工具，资源小的机器也跑得动。B：编译期注解处理器生成，更接近设计 08 的偏好，但要新增构建配置，R1 就变重。C：另写一张手写字段表，会再次出现"第三份清单"，**不建议** |
| D2 | 授权范围 | **推荐**：只授权 R1，完成后汇报，再决定 R2。备选：R1 + R2 只读探针一起授权 |
| D3 | 被改动的声明里有注释怎么办 | **推荐**：默认**拒绝应用**并在预览指出具体注释，不静默丢。备选：允许丢失并明确显示（需你逐次确认）。声明之间和文件头注释都保留 |
| D4 | 新内核放哪里 | **推荐**：放 `sir-change` 的新包，旧 API 原样保留，等新路径通过再逐步退役。备选：新建 Maven 模块，会多改 POM 和 CI |
| D5 | 旧 `SemanticProjection` 何时退役 | **推荐**：R2 之前不删，R2 通过后先让新旧并行对照一段时间，再由你单独决定退役 |

## 8. 风险与我不确定的地方

- **打印器是工作量最大的一块**：表达式括号与优先级、字符串转义、`@id`、多源 `sources/imports` 都要还原。我的缓解办法是"只重打印被改的声明 + 回读核验"，但探针若发现 span 不完整，可能要小改 Parser 的 AST。
- **Normalized 模型是否包含写回所需的全部信息**尚未验证。例如括号分组（`AstGroupedExpression`）在规范化后会消失，需要按优先级重新加括号；声明的 `@id` 需要从 `SymbolId` 字符串里取出。R2 探针第 3 项专门验证。
- **范围偏大**：R1 会碰约 25 个现有源文件，但每处只加标注。我不做估时，因为设计 08 也明确"不预估未经探针证明的工期"。
- **验证资源**：规划时按 `AGENTS.md` 的小服务器假设写成"只跑定向测试、全量交 CI"；实施时已改为直接在 Claude 会话环境本地测试（见 [Claude 执行规则](../../CLAUDE_EXECUTION_POLICY.md)），没有 Docker/MySQL 导致 5 个业务集成测试仍是 `NOT_RUN`。
- 本文所有结论来自阅读，没有运行任何测试；Q23 当前仍是 AWAITING_CI，不影响 R1。
