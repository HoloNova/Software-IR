# 当前唯一工作单：Q1B2 Generator Entity 与 DTO 类型/约束契约

- 状态：`READY`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 前置工作：[Q1B1 Generator 项目与简单 Artifact Renderer 契约](completed/Q1B1-generator-simple-artifact-contract.md) 已完成
- 本工作单性质：测试优先；只允许修复新增测试直接证明的 Generator 局部缺陷
- 下一张候选工作单：Q1B3 Service、Controller、Workflow、actor 与 response 行为契约

## 这次只做什么

通过真实的 Parser → Semantic → Spring Boot Lowering → Generator 路径，为 Entity 与请求 DTO 建立直接行为契约：

1. Entity 的 package、类名、表名、identity 映射、字段顺序、Java 类型、列名和访问器；
2. `generated auto` 与 `generated uuid` 对应的 `@TableId` 策略；
3. `Ref<Entity>` 降低后的 identity 外键字段，不允许 Generator 重新按 SIR 名称猜测；
4. DTO 的 package、类名、字段顺序、Java 类型和访问器；
5. DTO 上 `notBlank`、`email`、`length`、`min`、`max` 的 Jakarta Validation 注解、参数、import 和字段归属。

本工作单冻结的是当前 Lowered IR 已经决定好的输出外形，不扩展 SIR 语法，也不重新设计验证语义。

## 一个必须遵守的设计边界

当前 Lowering 设计把请求验证约束放在 `LoweredInput` / DTO 字段上；Entity 的职责是持久化结构和类型映射。因而本工作单：

- 验证 Entity 的 MyBatis-Plus 持久化注解、字段类型与访问器；
- 验证 DTO 的 Jakarta Validation 注解；
- **不因为源 Entity 字段带约束，就擅自在 Entity 上新增 Jakarta Validation 注解**；
- 如果现有代码或测试显示这一边界与当前 Accepted ADR、Lowered model 冲突，停止并报告，不私自选择新语义。

## 开工时怎样加载上下文

任何情况下都先重新读取本文件的最新内容，并运行 `git status --short`。

如果是新对话、新 Agent，或无法确认旧上下文是否完整，按 `AGENTS.md` 的冷启动顺序加载全局文档，然后读取本工作单点名的材料。

如果是在同一对话里从 Q1B1 连续推进，而且全局文档已经完整读取、此后未变化，则不机械重读；只重读本轮发生变化的权威文档，以及以下首次涉及或尚未加载的材料：

1. `docs/qualification/TEST_COVERAGE_INVENTORY.md` 的 Generator 部分；
2. `docs/KCG-Code_系统架构与实现指南.md` 的 4.5、6、9、10 节；
3. `docs/superpowers/specs/2026-07-16-sir-v0.1-lowering-design.md` 的 5.2、5.4、6、8 节；
4. `docs/superpowers/specs/2026-07-13-sir-v0.1-minimal-language-design.md` 的 4.2、4.3、5.2、5.3 节；
5. [Q1B1 已完成工作单](completed/Q1B1-generator-simple-artifact-contract.md)；
6. 以下生产实现和直接模型：
   - `EntityRenderer.java`
   - `DtoRenderer.java`
   - `TypeRenderer.java`
   - `SpringBootDeclaration.java`
   - `LoweredJavaType.java`
7. `sir-generator-spring-boot/src/test/**`。

如果对话发生上下文压缩，可以使用现有摘要；摘要没有保留本工作单所需事实时，再补读相应文件。拿不准时按冷启动处理。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新增失败测试直接证明有问题的以下生产文件：
  - `EntityRenderer.java`
  - `DtoRenderer.java`
  - `TypeRenderer.java`
- 与实际测试结果同步的：
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - `docs/PROJECT_STATUS.md` 中的能力描述
  - 本工作单的状态与交接记录

可以新增一个最小 Generator test fixture，用来覆盖现有 canonical fixture 没有表达的 UUID identity、复合 Java 类型或完整 DTO 验证约束。fixture 必须只包含本工作单需要的最少声明。

若需要修改 Lowering、Semantic、Parser、Application、CLI、POM 依赖、Target Profile 或公共架构契约，停止并报告，不得在本工作单里扩大范围。

## 明确不做

- 不测试或修改 Service、Controller、Workflow、Expression、actor identity 或 response transport；留给 Q1B3。
- 不做不同 Locale、工作目录、换行、转义或大规模重复运行矩阵；留给 Q1C。
- 不把生成工程写盘或做真实离线编译；留给 Q1D。
- 不新增完整文件 golden snapshot；断言只冻结有业务意义的结构、类型、注解和顺序。
- 不进入 Project Graph、Change、conformance、事务或 CLI。
- 不修改 Grammar 或 ANTLR 配置。
- 不把 AST、SIR 或 SymbolTable 暴露给 Generator。

## 测试优先要求

每一类契约按 RED → GREEN → REFACTOR 执行：

1. 先写最小、可读的行为测试；
2. 确认失败来自目标 Renderer 行为，而不是 fixture、路径或断言错误；
3. 只有新增测试证明生产缺陷时才改 Renderer；
4. 新增测试首次即通过时，如实记录为对现有行为的直接证据，不虚构 RED；
5. 不削弱、删除、禁用或增加 skip/exclude 来制造绿色结果。

测试必须复用真实 Parser → Semantic → Lowering → Generator 路径，不使用 mock 替代 Lowered model 的形成过程。

## 必须建立的契约

### 1. Entity identity 与持久化外形

至少直接验证：

- package、类名和 `@TableName` 来自 Lowered entity；
- `generated auto` 生成 `@TableId(..., type = IdType.AUTO)`；
- `generated uuid` 生成 `@TableId(..., type = IdType.ASSIGN_UUID)`；
- identity 的 Java 字段名、列名和 boxed Java 类型准确；
- identity getter/setter 与字段类型一致。

### 2. Entity 字段类型、顺序和引用降低

至少直接验证：

- Lowered field 顺序保持不变；
- String、Decimal、声明类型、Optional/List 中当前 fixture 实际使用的类型得到正确 Java 外形和 import；
- `Ref<Entity>` 使用目标 identity 的存储类型，并渲染 Lowering 已决定的 Java 字段名与列名，例如 `sellerId` / `seller_id`；
- 不额外生成原始 `seller` 对象字段，也不从 SIR 文本重新猜名称；
- 每个字段的 getter/setter 名称和类型与字段一致。

### 3. DTO 字段类型与访问器

至少直接验证：

- package、类名和字段顺序来自 `LoweredInput`；
- boxed scalar、Decimal、声明类型以及 fixture 覆盖到的容器类型渲染正确；
- getter/setter 与字段名称、类型一一对应；
- import 完整、稳定且没有由测试 fixture 偶然引入的重复项。

### 4. DTO 验证约束

至少直接验证五类约束：

- `notBlank` → `@NotBlank`
- `email` → `@Email`
- `length(min,max)` → `@Size(min = ..., max = ...)`
- `min(value)` → `@DecimalMin("...")`
- `max(value)` → `@DecimalMax("...")`

还必须验证：

- 对应 Jakarta Validation import 存在；
- 参数文本没有丢失负号、小数或边界值；
- 约束顺序保持 Lowered model 的顺序；
- 注解只落到对应 DTO 字段，不串到相邻字段；
- 没有本工作单未授权的新注解或重新推导。

## 测试设计约束

- 优先复用 `GeneratorTestSupport.generateSuccess(...)` 和精确路径取文件 helper。
- 可以增加小型 test helper，但不得给生产 API 增加测试专用入口。
- 按稳定片段、字段顺序和注解归属断言；不要用整个文件字符串相等代替所有语义断言。
- 对“不存在”的断言必须具体，避免仅因无关文本碰巧缺失而通过。
- 断言消息应指出是 Entity、DTO、identity、引用降低、类型还是约束发生偏离。

## 定向验证

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test
```

## 完成门

- Entity identity、持久化字段、引用降低、类型顺序和访问器都有直接行为测试。
- DTO 类型、字段顺序、访问器以及五类验证约束都有直接行为测试。
- UUID 与 AUTO identity 至少各有一个真实 fixture 路径证据。
- 现有 Q1A、Q1B1 和架构回归继续通过。
- 没有减少、禁用或放宽原有测试；新增 skip/exclude 为 0。
- 如修改生产 Renderer，每个修改都能指向一个先失败的行为测试。
- 定向测试通过。
- 全量离线 Reactor 通过：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

- `CURRENT_QUALIFICATION.md` 与当次 Surefire XML 一致。
- Q1B3、Q1C、Q1D 仍明确报告为未完成。
- 状态更新为 `AWAITING_ACCEPTANCE`，详细证据留在本工作单和资格文档；聊天中若无阻断，只请求项目负责人确认继续。

## 交接记录

2026-08-11：项目负责人确认 Q1B1 通过，并新增版本快照与简洁汇报规则。Q1B1 已归档；本工作单作为新的唯一活动入口先以 `READY` 建立，待 Q1B1 本地 Git 快照完成后切换为 `IN_PROGRESS`。本轮为同一对话连续推进，已重新读取工作区状态和变化后的治理文档；后续只补读 Q1B2 首次涉及的材料。
