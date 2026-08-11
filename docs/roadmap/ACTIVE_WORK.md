# 当前唯一工作单：Q1B3B Generator Controller 与 Transport 行为契约

- 状态：`READY`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 前置工作：[Q1B3A Generator Service 与 Workflow 行为契约](completed/Q1B3A-generator-service-workflow-contract.md) 已完成
- 本工作单性质：测试优先；只修复新增失败测试直接证明的 Controller/transport Renderer 局部缺陷
- 下一张候选工作单：Q1C Locale、工作目录、换行、转义与 UTF-8 字节确定性矩阵

## 这次只做什么

通过真实 Parser → Semantic → Spring Boot Lowering → Generator 路径，为 Controller 和 Lowered transport decision 建立直接行为契约：

1. Controller 的 package、类名、`@RestController`、route、HTTP method 和 Service 构造器注入；
2. actor identity 只按 `ActorBinding` 生成 request attribute、identity 存储类型、名称和调用参数；
3. POST input 使用 request body，GET input 使用 model attribute；只有存在 Lowered validation constraints 时才生成 `@Valid`；
4. Unit response 只调用 Service，不生成 `return`；Value、Entity、List、Optional response 返回 Service 结果；
5. Controller 参数顺序和 Service 调用参数顺序严格保持 actor 后 input；
6. import 完整、有序，不从 AST、SIR 或名称文本重新推导 transport。

本工作单只冻结 Lowered IR 已经决定的外形，不重新设计认证、HTTP header、错误响应或 API 版本策略。

## 开工时怎样加载上下文

任何情况下先读取本文件最新内容并运行 `git status --short`。

- 新对话、新 Agent 或上下文不完整：先按 `AGENTS.md` 读取 `.memory/INDEX.md` 和 `.memory/CORE/PROJECT.md`，再读取本工作单点名材料。
- 同一对话连续推进且全局材料未变化：不用机械重读，只补读本轮首次涉及或发生变化的文件。
- 上下文压缩后：摘要足够时继续；拿不准则按新对话处理。

直接相关材料：

1. `docs/qualification/TEST_COVERAGE_INVENTORY.md` 的 Generator 部分；
2. `docs/architecture/ADR-002-lowered-ir-owns-generation-decisions.md`；
3. `docs/superpowers/specs/2026-07-16-sir-v0.1-lowering-design.md` 的 capability、actor、transport 与 response 部分；
4. `ControllerRenderer.java`、`ResponseTypeRenderer.java`、`ServiceRenderer.java`；
5. `ActorBinding.java`、`TransportPlan.java`、`SpringBootDeclaration.CapabilityDeclaration`；
6. `GeneratorTestSupport.java`、现有 Generator 契约测试和 canonical fixtures；
7. [Q1B3A 已完成工作单](completed/Q1B3A-generator-service-workflow-contract.md)。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新增失败测试直接证明有缺陷的：
  - `ControllerRenderer.java`
  - `ResponseTypeRenderer.java`
- 与实际测试结果同步的：
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - 本工作单状态与交接记录

若必须修改 Parser、Semantic、Lowering、Service、Workflow、Application、CLI、POM、公共模型或认证架构，停止并报告，不得扩大范围。

## 明确不做

- 不新增认证中间件、Security Filter、HTTP header 或 token 语义。
- 不改变 `ActorBinding.REQUEST_ATTRIBUTE`、`TransportPlan.InputBinding` 或 `ResponseRepresentation`。
- 不测试或修改 Service/Workflow 业务步骤；Q1B3A 已冻结。
- 不做 Locale、工作目录、换行、转义、UTF-8 重复运行矩阵；留给 Q1C。
- 不写盘、不做生成工程真实离线编译；留给 Q1D。
- 不使用 mock 或测试专用 Lowered model 绕过真实管线。
- 不使用整文件 golden snapshot。
- 不进入 Project Graph、Change、conformance、事务应用层或 CLI。
- 不修改 Grammar、ANTLR 配置，不让 Generator 读取 AST、SIR 或 SymbolTable。

## 测试优先协议

1. 先写最小行为测试并执行。
2. 失败时先排除 fixture、路径、Lowered 名称和断言错误。
3. 只有新增测试直接证明生产缺陷时才修改 Renderer，并只修根因。
4. 新测试首次即通过时，如实记录为现有行为证据，不虚构 RED。
5. 不减少、禁用或放宽现有测试，不增加 skip/exclude。
6. 遇到 bug、失败或意外行为时，先按系统化调试流程定位。

## 必须建立的契约

### 1. Controller 外形与 HTTP mapping

- package、类名、`@RestController`、`@RequestMapping(route)`；
- command 对应 `@PostMapping`，query 对应 `@GetMapping`；
- Service 字段、构造器参数和赋值准确且只出现一次。

### 2. Actor identity transport

- actor capability 生成 `@RequestAttribute(attributeName)`；
- Java 类型来自 identity storage type；
- 参数名来自 `ActorBinding.attributeName`；
- Controller 只把 identity 参数传给 Service，不访问 actor 非 identity 成员；
- actor 在 input 之前，Service 调用顺序一致。

### 3. Input binding 与 validation

- POST input 使用 `@RequestBody`；
- GET input 使用 `@ModelAttribute`；
- DTO 任一字段有 Lowered constraints 时才添加 `@Valid` 和对应 import；
- 无 constraints 时不得偶然生成 `@Valid`；
- 无 input 时不得生成 request body/model attribute import 或参数。

### 4. Response delegation

- Unit/VOID：Controller 方法返回 `void`，调用 `service.method(...)`，不写 `return service...`；
- VALUE、ENTITY_BODY、LIST、OPTIONAL：返回类型与 Service 一致，并 `return service.method(...)`；
- 覆盖无参数、仅 input、actor + input 三种调用外形。

## 测试组织

优先新增一个聚焦的 `GeneratorControllerTransportContractTest`，复用：

- `campus-market.sir`：POST、actor + constrained input、Entity response；
- `compound-find.sir`：GET、model attribute、List response、无 DTO constraints；
- `unit-output.sir`：无参数、Unit response；
- `service-workflow-contract.sir`：Optional 和 Value response。

现有 fixture 已能表达全部契约，不应新增 fixture，除非真实测试证明存在无法表达的最小缺口。

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

- Controller、actor identity、input binding 和五种 response representation 均有真实端到端直接测试。
- 每项生产修改都能指向先失败的测试；无需生产修改时明确记录测试首次结果。
- 现有 Q1A、Q1B1、Q1B2、Q1B3A 和架构回归继续通过。
- 新增 skip/exclude 为 0，测试数量不减少。
- 定向 reactor 与全量离线 reactor 通过。
- 资格文档与最新 Surefire XML 一致。
- Q1C、Q1D 仍明确未完成。
- 状态更新为 `AWAITING_ACCEPTANCE`，详细证据留在本工作单；聊天中无阻断时只请求项目负责人确认继续。

## 交接记录

2026-08-11：项目负责人确认 Q1B3A 通过。Q1B3A 归档后，本工作单以 `READY` 建立；等待 Q1B3A 本地 Git 快照完成后切换为 `IN_PROGRESS`。
