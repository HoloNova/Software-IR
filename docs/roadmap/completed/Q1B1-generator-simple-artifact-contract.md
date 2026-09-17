# 已完成工作单：Q1B1 Generator 项目与简单 Artifact Renderer 契约

- 状态：`DONE`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 前置工作：[Q1A Generator 输出契约基线](Q1A-generator-output-contract.md) 已完成
- 本工作单性质：测试优先；只允许修复新增测试直接证明的 Generator 局部缺陷
- 下一张候选工作单：Q1B2 Entity 与 DTO Renderer 类型/约束契约

## 这次只做什么

在 Q1A 已经冻结“会生成哪些文件”的基础上，给下面五类相对独立的生成内容建立直接行为测试：

1. `pom.xml`
2. `Application.java`
3. Enum
4. Mapper
5. Exception

测试要证明这些 Renderer 消费 Lowered IR 中已经决定好的值，并生成正确、稳定的 Java/Maven 外形。不要在本工作单进入 Entity、DTO、Service、Controller 或 Workflow 的完整语义。

## 为什么这样拆

Q1A 已证明三组 canonical fixtures 的文件集合、顺序和 ownership metadata 稳定，但“文件存在”不等于“文件内容正确”。

POM、Application、Enum、Mapper、Exception 的依赖面比较小，适合作为第一批内容契约。Entity/DTO 涉及类型与验证注解，Service/Controller/Workflow 涉及 transport、actor 和表达式，分别留到后续小工作单，避免一次任务过大。

## 开工时怎样加载上下文

任何情况下都必须先做两件事：重新读取本文件的最新内容，并运行 `git status --short`。

如果是新对话、新 Agent，或者无法确认旧上下文是否完整，按 `AGENTS.md` 的冷启动顺序读取全局文档，然后继续读取下面的本工作单材料。

如果是在同一对话中从 Q1A 连续推进，而且全局文档已经完整读取、此后未变化，则不重复读取它们；只重读期间发生变化的权威文档，并读取本轮首次涉及或尚未加载的以下材料：

1. `docs/qualification/TEST_COVERAGE_INVENTORY.md` 的 Generator 部分
2. `docs/KCG-Code_系统架构与实现指南.md` 的 4.5、6、9、10 节
3. `docs/superpowers/specs/2026-07-16-sir-v0.1-lowering-design.md` 的 4 至 8 节
4. `docs/roadmap/completed/Q1A-generator-output-contract.md`
5. 以下生产实现及其直接模型：
   - `PomRenderer.java`
   - `ApplicationRenderer.java`
   - `EnumRenderer.java`
   - `MapperRenderer.java`
   - `ExceptionRenderer.java`
   - `ProjectArtifact.java`
   - `SpringArtifact.java`
6. `sir-generator-spring-boot/src/test/**`

如果对话发生上下文压缩，可以使用现有摘要；摘要没有保留本工作单所需事实时，再补读相应文件。拿不准时一律按冷启动处理。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被新增失败测试直接证明有问题的以下生产文件：
  - `PomRenderer.java`
  - `ApplicationRenderer.java`
  - `EnumRenderer.java`
  - `MapperRenderer.java`
  - `ExceptionRenderer.java`
- 与实际测试结果同步的：
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - `docs/PROJECT_STATUS.md` 中不应复制精确测试数，只更新能力描述
  - 本工作单的状态与交接记录

如需修改 Lowering、Semantic、Parser、Application 模块、CLI、POM 依赖、Target Profile 或公共架构契约，停止并报告，不得在本工作单里扩大范围。

## 明确不做

- 不测试或修改 Entity/DTO 字段类型和验证注解；留给 Q1B2。
- 不测试或修改 Service、Controller、Workflow、Expression、actor identity 或 response transport；留给 Q1B3。
- 不做不同 Locale、工作目录或大规模重复运行矩阵；留给 Q1C。
- 不把生成工程写盘或真实离线编译；留给 Q1D。
- 不新增完整文件 golden snapshot；测试应针对稳定契约，避免把无关空格变化都冻结成产品语义。
- 不进入 Project Graph、Change、conformance、事务或 CLI。

## 测试优先要求

每一类契约都按 RED → GREEN → REFACTOR 执行：

1. 先写一个最小、可读的失败测试。
2. 确认失败原因是缺少或错误的目标行为，而不是路径、fixture 或断言写错。
3. 只在确有产品缺陷时修改生产 Renderer。
4. 每次 GREEN 后再抽取测试 helper，保持现有 9 项 Generator 测试继续通过。

如果新增测试首次就通过，说明它是在补现有行为的直接证据；可以保留，但必须记录“未触发生产修复”，不得虚构 RED。

## 必须建立的契约

### 1. Maven POM

至少直接验证：

- groupId、artifactId、version 和 Java 21 来自 Lowered project model。
- Spring Boot `3.5.3`、MyBatis-Plus `3.5.12`、MySQL Connector/J `9.3.0` 及既有 scope 被正确渲染。
- Spring Boot Maven plugin 存在。
- 依赖顺序、换行和结尾稳定。

不要联网查询“最新版本”，这些版本是冻结输入。

### 2. Application

至少直接验证：

- package、类名和 `@SpringBootApplication`。
- `@MapperScan` 使用 Lowered model 提供的 persistence package。
- `main` 调用 `SpringApplication.run(...)`。
- 只冻结当前 fixture 实际需要的稳定结构，不在本工作单展开 actor transport 辅助代码。

### 3. Enum

至少直接验证：

- package、类型名和成员顺序。
- 成员不被重新排序、改名或做 Locale 相关转换。
- 文件只有 Lowered enum 中已经决定的成员。

### 4. Mapper

至少直接验证：

- Mapper package 和类型名。
- 正确 import 对应 Entity。
- `extends BaseMapper<正确实体>`。
- 不生成额外方法或重新推导实体名称。

### 5. Exception

至少直接验证：

- package 和 Lowered IR 已决定的 `*Exception` 类型名。
- 当前 HTTP status 映射和异常基类。
- message 构造外形稳定。
- 不从原始 SIR 名称重新猜 Java 类型名。

## 测试设计约束

- 优先复用 `GeneratorTestSupport.generateSuccess(...)` 和 `contentEndingWith(...)`。
- 可以为“按精确路径获取文件”增加测试 helper，但不得给生产 API 加测试专用入口。
- 断言应包含有意义的失败信息，说明哪个 Renderer 契约偏离。
- 不使用 mocks 替代真实 Parser → Semantic → Lowering → Generator fixture 路径。
- 不用整文件字符串相等代替所有语义断言；只有顺序、换行等确实属于契约时才精确比较。

## 定向验证

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test
```

## 完成门

- POM、Application、Enum、Mapper、Exception 五类内容都有直接行为测试。
- 测试能指出具体缺失/错误结构，不只断言文件存在。
- 现有 Q1A 和架构回归继续通过。
- 没有减少、禁用或放宽原有测试。
- 新增 skip 和 exclude 为 0。
- 如修改生产 Renderer，每个修改都能指向一个先失败的测试。
- 定向测试通过。
- 全量离线 Reactor 通过：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

- `CURRENT_QUALIFICATION.md` 与当次 Surefire XML 一致。
- Q1B2、Q1B3、Q1C、Q1D 仍明确报告为未完成。

## 完成报告格式

执行者最后必须提供：

1. 新增或修改的测试与生产文件；
2. 五类 Renderer 分别冻结了什么契约；
3. 每次 RED 的实际失败原因；
4. 是否修改生产代码及对应测试；
5. 定向和全量 run/fail/error/skip/exclude；
6. 未运行内容与 Q1B2/Q1B3/Q1C/Q1D 边界；
7. 是否建议把状态改为 `AWAITING_ACCEPTANCE`。

## 交接记录

2026-08-11：用户确认从 Q1A 进入 Q1B1。本轮为同一对话连续推进；已重新读取本工作单并运行 `git status --short`，全局文档不机械重读。先完成冷启动/连续续作规则适配，再读取 Q1B1 新涉及的 Renderer、模型、设计材料和测试。

2026-08-11：Q1B1 已完成实现，等待项目负责人验收。

- 新增 `GeneratorSimpleArtifactContractTest` 5 项直接行为测试，并给 `GeneratorTestSupport` 增加精确相对路径取文件 helper；Generator 直接测试由 9 增至 14。
- POM 契约覆盖项目坐标、Java 21、冻结依赖版本/scope/顺序、plugin、LF 与结尾；Application 覆盖 package、类名、MapperScan 与 main；Enum 覆盖完整成员和顺序；Mapper 覆盖 Entity import、`BaseMapper<T>` 和无额外方法；Exception 覆盖 Lowered Java 名、BAD_REQUEST、基类和无参构造。
- 五个行为测试首次真正执行均通过，未触发生产 Renderer 修复；没有修改任何生产代码、POM、依赖、skip 或 exclude。
- 测试到达执行前有两类非产品失败：受控沙箱内 javac 无法读取 reactor `target/classes`，改用获准的沙箱外 Maven；新增测试 helper 的断言消息最初用 lambda 捕获可变索引，testCompile 报错后只修正测试代码。二者都不是 Renderer 行为 RED。
- 定向验证：`mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am test`，Generator 14 run、0 failure、0 error、0 skip，BUILD SUCCESS。
- 全量验证：`mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify`，355 run、0 failure、0 error、10 skip，BUILD SUCCESS；数字已从 Surefire XML 重算。
- Q1B2 Entity/DTO、Q1B3 Service/Controller/Workflow、Q1C 环境确定性和 Q1D 生成工程离线编译仍未完成。

2026-08-11：项目负责人确认 Q1B1 通过。工作单转入 completed 归档，并按小版本规则创建本地 Git 快照；不推送远端。活动入口切换到 Q1B2。
