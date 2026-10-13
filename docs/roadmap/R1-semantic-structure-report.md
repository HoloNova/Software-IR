# R1 报告：统一语义结构（本地定向验证完成，CI 未跑）

- 日期：2026-10-13。分支：`main`（起点 `3555ec5`）。**未提交、未推送。**
- 授权：负责人 2026-10-13 批准按[编码计划草案](LAYERED_CODING_PLAN_DRAFT.md)实施 R1，并允许实施中有限优化；R2～R5 仍未授权。
- 结论：现有语义模型的每个字段都已分类，漏分类会让构建失败；已有行为零改动。

## 1. 做了什么

| 内容 | 位置 | 为什么存在 |
| --- | --- | --- |
| 5 个分类标注 + `@Keyed` | `sir-semantic/.../schema/` | 在字段上写明"这是事实/身份/引用/来源/派生"，分类和字段在同一处，不会成为第三份清单 |
| `SoftwareSchema` | 同上 | 读标注建出结构说明；漏分类、类型说不清、密封子类没登记，都抛出带完整问题列表的错误 |
| `StructureView` | 同上 | 按 schema 通用读取模型：只含事实/身份/引用（可选加来源），派生内容永不出现 |
| `ExternalSlots` | 同上 | 3 个属于解析层的记录（`AstMetadata`、`AstTarget`、`AstTargetValue`）语义层不能加标注，用一张小表登记，走同一套完整性检查 |
| 给 20 个现有源文件的 41 个 record 加标注 | `sir-semantic/.../model`、`type`、`api` | **只加标注，字段、构造器、行为一字未改** |

## 2. 证据（本地，当次输出）

Claude 会话环境（4 核 / 16 GB，无 systemd，无 Docker/MySQL；规则见 [Claude 执行规则](CLAUDE_EXECUTION_POLICY.md)）按项目冻结形式执行：先联网预热本地仓库（含两个生成工程的依赖），再 `env -u JAVA_TOOL_OPTIONS mvn -B -o -Dmaven.repo.local=$HOME/.m2/repository clean verify`；用 `nice` + `taskset -c 0,1,2` + `-Xmx1g` 限制资源，单构建、无并行。

| 模块 | run / fail / error / skip |
| --- | --- |
| parser | 91 / 0 / 0 / 0 |
| semantic | 216 / 0 / 0 / 0（其中新增 31） |
| lowering-api | 4 / 0 / 0 / 0 |
| lowering-spring-boot | 86 / 0 / 0 / 0 |
| generator | 77 / 0 / 0 / 0（含生成工程离线编译与跨 JVM 确定性） |
| project-graph | 77 / 0 / 0 / 0 |
| change | 72 / 0 / 0 / 0（其中新增 3） |
| toolchain-application | 456 / 0 / 0 / 5 |
| cli | 34 / 0 / 0 / 0 |
| **合计** | **1113 / 0 / 0 / 5**，BUILD SUCCESS |

- 5 个跳过来自 `toolchain-application` 中默认关闭的项；Claude 会话环境没有 Docker/MySQL，**5 个业务集成测试（Query/Write/Relation/ChangeLoop/ProjectWorkflow）本次 NOT_RUN**。
- 排障记录：第一次不带冻结仓库参数、且沙箱注入 `JAVA_TOOL_OPTIONS` 时，generator 4 项、cli 1 项失败；原因是测试要求 `-Dmaven.repo.local`、生成工程依赖未预热，以及子 JVM 输出被该环境变量污染。按上述冻结形式重跑后全部通过，与本次改动无关。

新增测试要点：

- `SoftwareSchemaCompletenessTest`（8）：所有字段已分类；`NormalizedDeclaration`、`NormalizedStep`、`NormalizedExpression`、`SirType` 的每个子类都登记；`model`、`type` 包里新增的 record 若不可达会失败；故意漏标、重复标、说不清的类型、`@Keyed` 缺身份都会被拒绝并点名字段。
- `StructureViewSensitivityTest`（22）：18 种事实修改（含 `httpStatus`、排序方向/键、分页来源、`.present`、`any(...)` 条件、步骤顺序、枚举成员改名/换序、约束参数、显示名、命名空间）都改变视图；注释、空行、缩进不改变事实视图但改变来源视图；两个声明换位置不算变化；输出确定；派生内容不出现。
- `StructureViewCoverageTest`（1）：schema 里的每一种节点都被至少一个有效源码产生过，没有"登记了却没测过"的条目。
- `StructureViewCoversProjectionTest`（3，放在 `sir-change`）：旧 `SemanticProjection` 能看出的修改，新视图都看得出；`httpStatus` 旧投影看不出、新视图看得出，已作为已知缺口记录。

## 3. 与计划相比的调整（有限优化）

1. **登记表 3 个记录而不是 2 个**：`AstTarget` 里的值包在泛型 `AstTargetValue<T>` 中，也要登记。
2. **`declarations` 标为 `@Keyed`**（按身份匹配，换顺序不算变化）；其余所有集合保持有序。`fails` 的顺序语言本身强制按名称升序，所以不需要额外处理。
3. **Claude 会话环境没有 systemd**，`AGENTS.md` 的 `systemd-run` 命令无法使用。本轮改用 `nice` + `taskset -c 0,1` + `-Xmx1g`，单次单模块、无并行。
4. **改了一个现有测试的一个词**：`SemanticProjectionParityTest.template()` 从 `private` 改为包内可见，让新对照测试复用同一份样例。

## 4. 请你复核的分类（我认为有歧义的字段）

| 字段 | 我的归类 | 理由与风险 |
| --- | --- | --- |
| 所有表达式的 `type` | 派生 | 它是类型推导结果；字段本身的类型变了，会在字段处体现 |
| 步骤的 `resultVariable`、`itemVariable` | 引用 | 它们的 `SymbolId` 里含步骤在 AST 中的序号，在前面插入一步会让后面步骤的变量 id 变化。这仍是真实结构变化，但 **R2 的选择器不能把它们当稳定键** |
| `ExistsExpression.connectionField` | 引用 | 由 Resolve 推出，保守地算作可见变化 |
| `RefType.entityName`、`DeclaredType.name` | 事实 | 与 `entityId` 重复；保守起见都参与比较 |
| `NormalizedBinding.fieldName` | 事实 | 源码里写的字段名，与 `fieldSymbol` 并存 |
| `NormalizedWorkflow.id` | 身份 | workflow 作用域的 `SymbolId` |
| `NormalizedCapability.requires` | 事实（有序） | 当前按顺序比较，调换顺序会被看成变化 |
| `directNamedTypeReferenceSiteId` | 来源 | 语法节点号 |
| `AstTargetValue.value`（泛型） | 事实 | schema 对 `Object` 型字段只在它是枚举/字符串时接受，否则运行时报错 |

## 5. 未做、留给后续

- 没有改 `sir-change` 的任何生产代码，没有删 `SemanticProjection`（D5：R2 前不删）。
- 没有指令、草稿、写回 SIR；这些属于 R2，需要你另行授权。
- `NormalizedSemanticModel` 的 6 个组件是派生分析（符号表、绑定表、表达式类型、诊断），schema 不描述它们的内部。

## 6. R2 探针后的修正

R2 要把结构写回 SIR，这时才发现 R1 有一批字段只是"按名字解析出来的结果"，源码里并不单独写。把它们当作引用，编辑时会出现"名字改了、解析结果没跟着改"的矛盾，所以改归**派生**（可由已有的名字重新算出，不会因此漏掉变化，因为名字本身仍是事实）：

| 字段 | 由什么决定 |
| --- | --- |
| 表达式 `NameExpression.resolvedSymbol`、`MemberExpression.resolvedMember` | 表达式里写的名字 |
| `PresentExpression.field` | 它的目标成员 |
| `ExistsExpression.connectionField` | `any(...)` 里的条件推断 |
| `RefType.entityId`、`DeclaredType.symbolId`、`DeclaredType.kind` | 类型里写的名字 |
| `NormalizedBinding.fieldSymbol` | 绑定里写的字段名 `fieldName` |
| `FindStep.itemVariable` | 隐式变量 `item` |
| `NormalizedViewField.sourceField`、`relation` | 视图字段的名字和类型 |
| `NormalizedEntity.versionField`、`NormalizedField.patchSourceField` | `versioned` 标记、补丁字段名 |

仍算"引用"的是源码里确实写出来的名字：能力的 `input`/`actor`/`fails`、步骤的实体、结果变量、`else` 错误、`order by` 字段、分页字段、`patch of`、`from`、`any(Entity, ...)`。
