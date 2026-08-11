# 当前唯一工作单：Q1B3A Generator Service 与 Workflow 行为契约

- 状态：`READY`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 前置工作：[Q1B2 Generator Entity 与 DTO 类型/约束契约](completed/Q1B2-generator-entity-dto-contract.md) 已完成
- 本工作单性质：测试优先；只修复新增失败测试直接证明的 Service/Workflow Renderer 局部缺陷
- 下一张候选工作单：Q1B3B Controller、actor identity transport 与 response 行为契约

## 为什么把 Q1B3 拆成两张

原 Q1B3 同时包含业务工作流渲染和 HTTP transport 渲染，失败时很难判断责任在 Service/Workflow 还是 Controller/Transport。这里按生产边界拆开：

1. Q1B3A 只证明 Service 方法与 Workflow 步骤如何生成；
2. Q1B3B 再证明 Controller 如何绑定 actor/input、选择 response 外形并调用 Service；
3. 两张都完成后，路线图中的原 Q1B3 才算整体完成。

这只是验收任务拆分，不改变 SIR、Lowered IR 或生成器架构。

## 开工时怎样加载上下文

任何情况下先读取本文件最新内容并运行 `git status --short`。

- 新对话、新 Agent 或上下文不完整：先按 `AGENTS.md` 读取 `.memory/INDEX.md`、`.memory/CORE/PROJECT.md`，再读本工作单点名的文档和代码。
- 同一对话连续推进且权威文件未变化：不用机械重读已完整加载的全局材料，只补读本轮首次涉及或发生变化的文件。
- 上下文压缩后：若摘要已保留本工作单需要的事实，可以继续；拿不准就按新对话处理。

本工作单直接相关材料：

1. `docs/qualification/TEST_COVERAGE_INVENTORY.md` 的 Generator 部分；
2. `docs/superpowers/specs/2026-07-16-sir-v0.1-lowering-design.md` 中 capability、workflow、transaction 与 Find lowering；
3. `docs/architecture/ADR-002-lowered-ir-owns-generation-decisions.md`；
4. `ServiceRenderer.java`、`WorkflowRenderer.java`、`ExpressionRenderer.java`、`ResponseTypeRenderer.java`；
5. `SpringBootDeclaration.CapabilityDeclaration`、`SpringBootWorkflow`、`TransportPlan`；
6. `sir-generator-spring-boot/src/test/**` 与现有 canonical fixtures。

## 这次只做什么

通过真实 Parser → Semantic → Spring Boot Lowering → Generator 路径建立直接行为测试，至少证明：

1. Service 的 package、类名、`@Service`、事务注解和方法签名来自 Lowered IR；
2. Mapper 依赖字段、构造器参数和赋值完整、有序、无重复；
3. actor 与 input 方法参数使用 Lowered variable/binding 决定的类型、名称和顺序；
4. Create、Update、Persist、Find、Validate、Return 的关键 Java 语句按 workflow 顺序生成；
5. 复合 Find 的 AND/OR/NOT 分组不被展平或改写；
6. Unit/Optional/List/Direct 的 Service 返回类型和 Return 语句与 Lowered output 一致。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新增失败测试直接证明有缺陷的：
  - `ServiceRenderer.java`
  - `WorkflowRenderer.java`
  - `ExpressionRenderer.java`
  - `ResponseTypeRenderer.java`
- 与实际测试结果同步的：
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - 本工作单状态与交接记录

若必须修改 Parser、Semantic、Lowering、Controller、Application、CLI、POM、公共模型或架构契约，停止并报告，不能顺手扩大范围。

## 明确不做

- 不测试或修改 Controller、HTTP annotation、request binding、actor request attribute；留给 Q1B3B。
- 不做 Locale、工作目录、换行、转义、UTF-8 重复运行矩阵；留给 Q1C。
- 不写盘、不做生成工程真实离线编译；留给 Q1D。
- 不用 mock 绕过 Parser/Semantic/Lowering，不构造测试专用 Lowered model。
- 不使用整文件 golden snapshot；断言有业务含义的片段、相对顺序和不存在项。
- 不进入 Project Graph、Change、conformance、事务应用层或 CLI。
- 不修改 Grammar、ANTLR 配置，不让 Generator 读取 AST、SIR 或 SymbolTable。

## 测试优先协议

1. 先写最小行为测试并运行；失败时保存 RED 的精确原因。
2. 先排除 fixture、路径和断言错误，再认定生产缺陷。
3. 只有新增测试直接证明缺陷时才改生产 Renderer，并只修根因。
4. 若新测试首次即通过，如实记录为现有行为的直接证据，不虚构 RED。
5. 不减少、禁用或放宽现有测试，不增加 skip/exclude。
6. 若出现 bug、失败或意外行为，先按系统化调试流程定位再修改。

## 推荐测试组织

优先新增一个聚焦的 `GeneratorServiceWorkflowContractTest`，复用 `GeneratorTestSupport.generateSuccess(...)` 和现有最小 fixture。只有现有 fixture 无法表达某个必需契约时，才增加最小 SIR fixture。

断言至少分成以下组：

- Service 外形、事务与构造器注入；
- actor/input 参数与 response 类型；
- Create/Update/Persist/Validate/Return 顺序；
- Find mapper 调用、item 变量与 Optional/List 外形；
- AND/OR/NOT 的括号分组与参数绑定。

## 验证命令

定向：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test
```

完成门：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

## 完成门

- 上述 Service/Workflow 契约均有真实端到端直接测试。
- 每项生产修改都能指向先失败的测试；若无需生产修改，明确记录测试首次通过。
- 现有 Q1A、Q1B1、Q1B2 与架构回归继续通过。
- 新增 skip/exclude 为 0，现有测试数量不减少。
- 定向 reactor 与全量离线 reactor 均通过。
- 资格文档与最新 Surefire XML 一致。
- Q1B3B、Q1C、Q1D 仍明确未完成。
- 状态更新为 `AWAITING_ACCEPTANCE`，详细证据写入下方交接记录；聊天中无阻断时只请求项目负责人确认继续。

## 交接记录

2026-08-11：项目负责人确认 Q1B2 通过。为保持小任务可独立验收，原 Q1B3 按生产责任拆为 Q1B3A（Service/Workflow）与 Q1B3B（Controller/transport）；本工作单以 `READY` 建立，等待 Q1B2 本地快照完成后切换为 `IN_PROGRESS`。
