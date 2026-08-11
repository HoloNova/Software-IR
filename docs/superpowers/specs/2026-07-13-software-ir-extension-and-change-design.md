# Software IR 扩展与增量修改规范

状态：已确认方向，细节随对应阶段冻结  
日期：2026-07-13  
适用范围：v0.1 之后的兼容边界，不扩大第一轮 Core Grammar

## 1. 目的

本规范解决四个长期问题：

1. Core Workflow 无法表达的算法、中间件和复杂逻辑如何接入；
2. 前后端如何共享可验证的接口契约；
3. 如何在已有项目上做局部修改，而不是重新生成整个项目；
4. 如何让公共黑板、静态解析和符号稳定性共同约束 Code Agent。

核心决定：

> 不把 Core SIR 扩展成通用编程语言，也不把真实项目强行完整反推成 SIR。系统使用 Base SIR、Project Symbol Graph 和 Change SIR 三者协作。

## 2. 三种项目表示

### 2.1 Base SIR

描述系统已经确认的业务语义和目标要求，是“期望的软件”。

Base SIR 负责：

- 业务类型和 Capability；
- 可验证 Workflow；
- 权限、事务和持久化意图；
- Extension 的语义声明；
- Target Profile 选择。

Base SIR 不负责保存所有 Java 实现细节。

### 2.2 Project Symbol Graph

描述项目当前真实存在的结构，是“项目实际上有什么”。

它来自 Java 静态解析、构建文件分析和生成 Manifest，不要求把所有代码恢复成业务语义。

### 2.3 Change SIR

描述一次局部修改，是“这次希望改变什么”。

它引用 Base SIR 和 Project Symbol Graph 中的稳定 Symbol ID，只表达增量操作和必须保持的约束，不复制整个项目。

```text
Base SIR ───────────────┐
                       ├→ Change Planner → 最小变更计划
Project Symbol Graph ──┤
                       │
Change SIR ────────────┘
```

## 3. Extension 模型

### 3.1 Extension 的职责

Extension 用于表达 Core 没有覆盖、但仍有稳定语义的能力，例如：

- Redis 查询缓存；
- Session 存储；
- 定时任务；
- 事件发布；
- 外部服务调用；
- 分布式锁；
- 排行和推荐能力；
- 前后端接口契约扩展。

Extension 不是随意的字符串标签。每个 Extension 必须提供：

```text
namespace
version
schema
supportedCoreVersion
validationRules
targetCapabilities
loweringProvider
migrationRules
```

缺少对应 Validator 或 Lowering Provider 时，编译必须失败，不能静默忽略。

### 3.2 Extension 的启用

未来语法方向：

```text
extensions {
  use cache.query 0.1;
}
```

Extension 的具体语法由它自己的版本化 Schema 定义。Core Parser 只识别扩展包络和命名空间，扩展内容交给已注册解析器处理。

在 Extension 框架实现前，v0.1 Core Grammar 拒绝所有扩展关键字。

### 3.3 Redis 例子

Redis 不是一种 Core 业务语义。查询缓存 Extension 可以声明：

```text
cache QueryGoods {
  ttl 5m;
  key input;
  invalidateOn PublishGoods, PlaceOrder;
  failure fallback;
}
```

语义字段必须明确：

- 缓存哪个 Capability；
- Key 来自哪些稳定输入；
- TTL；
- 哪些写能力导致失效；
- Redis 不可用时失败还是回源；
- 值的版本和序列化策略由 Target Profile 确定。

仅声明 `use redis` 不代表完成缓存能力。

## 4. 复杂算法与实现槽位

### 4.1 三层表达策略

复杂逻辑按以下优先级处理：

1. 能由 Core Workflow 明确表达时，使用 Core；
2. 有稳定、可复用语义时，设计 Extension；
3. 属于项目特有算法时，使用类型安全的 Implementation Slot。

不允许为了覆盖长尾逻辑而在 `.sir` 中嵌入任意 Java 源码。

### 4.2 Implementation Slot

未来语义方向：

```text
operation RankGoods {
  input RankGoodsInput;
  output List<Ref<Goods>>;
  implementation slot "rank-goods-v1";
}
```

SIR 只声明稳定契约。实现位于单独源码文件，可以由开发者或 Code Agent 完成。

每个 Slot 必须声明：

- 稳定 Slot ID；
- 输入输出类型；
- 允许的依赖和可引用符号；
- 是否允许副作用；
- 超时或资源限制；
- 文件所有权；
- 必须通过的测试。

Code Agent 只能修改 Slot 对应的 AST 节点或文件，不能借此改变公开接口、依赖和事务边界。

### 4.3 为什么不内嵌源码

源码内嵌会导致：

- SIR Parser 同时承担 Java Parser 职责；
- 源码无法跨 Target；
- 静态语义边界被逃逸代码绕过；
- Source Span、格式化和补丁冲突复杂化；
- SLM 可以利用源码块规避 SIR 约束。

因此只允许“引用外部实现”，不允许“把任意实现写进 SIR”。

## 5. 前后端契约

前后端联调首先解决契约一致性，不要求 v0.1 同时生成完整前端页面。

```text
Capability + Input + Output + Error
                ↓
REST Lowering
                ↓
OpenAPI
       ├→ Spring Controller/DTO
       └→ TypeScript 类型和 API Client
```

必须由同一个 Lowered Contract Model 同时生成后端接口和前端客户端，禁止分别读取 SIR 后各自推断。

契约至少包含：

- operation ID；
- 请求与响应类型；
- Error 映射；
- 鉴权要求；
- 路径和 HTTP 方法；
- 可空性和字段校验；
- 契约版本。

前端页面布局、交互动画和复杂状态管理不属于 Core SIR。

## 6. Project Symbol Graph 最小模型

### 6.1 节点

第一版项目图只需要 JVM 内存模型，不需要图数据库。

最小节点：

```text
Project
Module
Package
JavaType
Method
Field
Endpoint
Dependency
ConfigurationKey
GeneratedArtifact
```

### 6.2 关系

```text
CONTAINS
DECLARES
REFERENCES
CALLS
EXTENDS
IMPLEMENTS
EXPOSES
DEPENDS_ON
GENERATED_FROM
```

### 6.3 稳定标识

SIR 节点示例：

```text
sir://CampusMarket/entity/Goods/field/description
```

Java 符号示例：

```text
java://app/com.example.campusmarket.domain/Goods#description
```

生成 Manifest 保存两者的映射。显示名称可以修改，但稳定 ID 的变更必须通过显式 rename 操作，不能依赖字符串相似度猜测。

### 6.4 不做完整反向 IR

Java 静态分析可以可靠提取结构和引用，但不能保证恢复业务含义。因此：

- 生成代码保留 `GENERATED_FROM` 映射；
- 手写代码只进入 Project Symbol Graph；
- 缺失的高层语义标记为 `UNKNOWN`，不能自动编造；
- 需要业务判断时由用户或 Agent 提议 Change SIR，再由 Validator 检查。

## 7. Change SIR

### 7.1 文档身份

Change SIR 使用独立文件，例如 `.sir.change`，不与 Base SIR 顶层语法混用。

示意：

```text
sir_change 0.1

project CampusMarket {
  basedOn "snapshot-sha256";

  modify entity Goods {
    add field description: Optional<String>;
  }

  preserve publicApi;
  preserve userOwnedCode;
}
```

具体 Grammar 在增量修改阶段再冻结；本规范先固定处理模型。

### 7.2 最小操作集

未来只从以下显式操作开始：

```text
add
modify
rename
remove
```

每个操作必须引用稳定 Symbol ID，并声明前置条件。例如：

- 目标存在或不存在；
- 当前类型与预期一致；
- 当前项目快照 Hash 一致；
- 修改不破坏 preserve 约束。

如果前置条件不满足，Change Planner 必须拒绝应用，不能让 Agent自动猜测新目标。

### 7.3 最小变更流程

```text
Parse Change SIR
→ 解析稳定 Symbol ID
→ 校验项目快照
→ 读取影响范围子图
→ 产生 Change Plan
→ 检查所有权和 preserve 约束
→ AST Patch 或受控文件重生成
→ 更新数据库迁移
→ 编译与回归测试
→ 更新 Project Symbol Graph 和 Manifest
```

SLM 只需要看到目标符号及其邻接子图，不必接收整个项目。

## 8. 文件与符号所有权

每个生成制品和可修改符号必须具有一种所有权：

```text
GENERATED   完全由 Generator 管理，可以重新生成
USER_OWNED  用户维护，Generator 和 Agent 默认禁止修改
MIXED       只允许修改明确标记的 AST 节点
MODEL_SLOT  Agent 只能修改指定实现槽位
```

Manifest 至少记录：

```text
SIR Node ID
Java Symbol ID
文件路径
所有权
生成器版本
Target Profile 版本
内容 Hash
上次生成快照
```

禁止依赖源码中的脆弱文本注释来判断全部所有权。注释可以辅助定位，但 Manifest 和 AST Symbol 才是权威。

## 9. 最小补丁规则

Change Planner 必须优先选择影响范围最小的操作：

1. 修改单个受控 AST 节点；
2. 重生成单个 GENERATED 文件；
3. 重生成同一 SIR Node 的制品集合；
4. 只有跨边界不变量变化时才扩大到模块；
5. 默认禁止重生成整个项目。

任何删除、公开 API 变化、数据库破坏性迁移或 USER_OWNED 修改都必须产生高严重度诊断，并要求额外确认。

衡量局部修改的指标包括：

- Patch Size；
- Unrelated Change Rate；
- Existing Test Regression；
- Public API Change；
- Symbol Conflict Rate。

这些指标的具体阈值必须通过项目样本实验确定，当前不声明未经测试的数值。

## 10. 公共黑板与静态校验

公共黑板正式命名为 `Project Knowledge Context`，包含：

```text
Project Knowledge Context
├── Target、依赖及版本
├── Base SIR Node 和 Symbol ID
├── Java Project Symbol
├── 类型、字段和 Capability
├── Workflow 局部变量及作用域
├── Extension 能力
├── 文件和符号所有权
├── 生成记录与 Hash
├── 当前项目快照
└── 约束与诊断
```

### 10.1 写入权限

- Parser 写入原始声明；
- Symbol Resolver 建立引用；
- Type Checker 写入推导类型；
- Project Scanner 写入真实项目符号；
- Target Resolver 写入依赖和能力选择；
- Lowering 写入生成计划；
- Generator 只消费计划并回写 Artifact Manifest；
- Code Agent 只查询上下文并提交候选 Change SIR 或 Slot Patch。

Agent 不得直接更改可信上下文。

### 10.2 作用域

```text
Project Scope
→ Type Scope
→ Capability Scope
→ Workflow Scope
→ Step-local Scope
```

所有自定义名称在声明时进入对应作用域。引用必须绑定到唯一 Symbol ID。重名、遮蔽、越界引用和使用已删除符号均产生静态诊断。

### 10.3 稳定快照

每个编译阶段产生不可变快照：

```text
Parsed
→ Resolved
→ Typed
→ Validated
→ Lowered
→ Generated
→ Scanned
```

后续阶段不能修改上游快照。Change SIR 必须声明它基于哪个项目快照，防止在过期上下文上修改。

## 11. 版本、迁移与兼容性

为了保证长期可维护：

- Core SIR、每个 Extension、Target Profile、Manifest 和 Change SIR 分别版本化；
- 新版本通过 Migration Pass 转换旧 Canonical IR；
- Deprecated 元素必须至少保留诊断和迁移建议；
- 未识别版本必须失败，不能按最新版猜测；
- Canonical IR 必须排序稳定，并排除时间戳和随机 ID；
- 相同输入、规则和版本必须产生相同 Symbol ID 与 Lowered IR。

## 12. 未来验收场景

### 12.1 Extension

- 未安装 Extension 时拒绝对应语义；
- Extension 版本不兼容时给出明确诊断；
- Redis 查询缓存能验证命中、失效和故障策略。

### 12.2 Implementation Slot

- Agent 只能修改 Slot；
- 修改方法签名或依赖时被拒绝；
- Slot 实现必须通过契约测试。

### 12.3 Change SIR

- 给 Goods 增加 Optional 字段时只修改关联制品；
- 过期快照被拒绝；
- USER_OWNED 文件不被覆盖；
- preserve publicApi 能阻止公开签名变化；
- 不相关文件 Hash 保持不变。

### 12.4 前后端契约

- OpenAPI、Spring DTO 和 TypeScript 类型来自同一 Lowered Contract；
- Optional 字段在三者中的可空性一致；
- Error 和鉴权声明一致。

## 13. 与 v0.1 的边界

本规范现在只冻结接口边界，不要求第二轮立即实现：

- Project Symbol Graph；
- Change SIR Parser；
- AST Patch；
- Implementation Slot；
- Redis Extension；
- TypeScript Client Generator。

第二轮仍然只设计并实现 Base SIR 的 ANTLR Grammar、AST、SourceSpan、Parse API 和语法诊断。但这些实现不得：

- 使用随机 Node ID；
- 丢失 Source Span；
- 把 AST 与 Java/Spring 类绑定；
- 假设所有文件永远重新生成；
- 把未知扩展静默忽略；
- 让 Agent 直接修改 Symbol Table。

## 14. 第二轮准入检查

检查结论：可以进入第二轮 Parser/AST 设计。

已满足的前置条件：

- v0.1 目标栈和不支持范围已冻结；
- Base SIR 顶层结构和最小声明元素已冻结；
- 一种语义只保留一种核心写法；
- Workflow 首轮范围已限制为无循环顺序流程；
- AST、Semantic Model、Lowered IR 和真实项目图的职责已分开；
- 合法样例、负面样例和稳定诊断类别已经存在；
- Extension、Change SIR 和 Project Symbol Graph 不要求修改第二轮 Base SIR 顶层语法；
- Source Span、稳定 ID 和未知扩展失败策略已经成为强制约束；
- 不存在会迫使第二轮同时实现 Redis、SLM 或增量修改的依赖。

第二轮需要当轮冻结、但不阻止进入的问题：

1. 标识符、字符串、数字、注释和换行的词法规则；
2. Field 多约束的分隔语法；
3. 表达式优先级和括号；
4. Date/DateTime 的字面量策略；
5. ANTLR 错误恢复与 Diagnostic 映射；
6. AST Node ID 的确定性生成算法；
7. SourceSpan 的文件、行列和字符偏移定义；
8. Parser 成功、部分成功和失败的 API 结果类型。

这些问题都属于 Parser/AST 设计本身，不会改变已确认的业务语义边界。

## 14. 第二轮准入检查

检查结论：可以进入第二轮 Parser/AST 设计。

已满足的前置条件：

- v0.1 目标栈和不支持范围已冻结；
- Base SIR 顶层结构和最小声明元素已冻结；
- 一种语义只保留一种核心写法；
- Workflow 首轮范围已限制为无循环顺序流程；
- AST、Semantic Model、Lowered IR 和真实项目图的职责已分开；
- 合法样例、负面样例和稳定诊断类别已经存在；
- Extension、Change SIR 和 Project Symbol Graph 不要求修改第二轮 Base SIR 顶层语法；
- Source Span、稳定 ID 和未知扩展失败策略已经成为强制约束；
- 不存在会迫使第二轮同时实现 Redis、SLM 或增量修改的依赖。

第二轮需要当轮冻结、但不阻止进入的问题：

1. 标识符、字符串、数字、注释和换行的词法规则；
2. Field 多约束的分隔语法；
3. 表达式优先级和括号；
4. Date/DateTime 的字面量策略；
5. ANTLR 错误恢复与 Diagnostic 映射；
6. AST Node ID 的确定性生成算法；
7. SourceSpan 的文件、行列和字符偏移定义；
8. Parser 成功、部分成功和失败的 API 结果类型。

这些问题都属于 Parser/AST 设计本身，不会改变已确认的业务语义边界。
