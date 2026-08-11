# ADR-001：名称只解析一次，并按 AstNodeId 固化符号绑定

## 状态

Accepted，2026-07-14。

## 背景

SIR 后续要支持确定性 Lowering、局部修改、Change SIR 和 Project Symbol Graph。仅在 Normalize 或 Generator 阶段按名称重新查找符号，会产生三个问题：同名符号可能随作用域变化而改变含义；后续阶段重复实现解析规则；无法准确回答“源文件中的这一次引用指向哪个声明”。

旧的 `AstNameRef` 只有文本和 SourceSpan，没有自己的节点身份，因此 `referenceBindings` 无法覆盖 Entity、Error、Update/Persist 目标等每一次引用。NormalizePass 只能重新按字符串查找。

## 考虑过的方案

| 方案 | 优点 | 缺点 |
|------|------|------|
| 后续阶段继续按名称查找 | 改动小 | 解析规则重复，作用域漂移，不利于增量修改 |
| 用所属 Step 的 ID 代替引用 ID | 无需修改 AST | 一个 Step 可能含 Entity、Error、变量等多个引用，无法一一对应 |
| 每个名称引用拥有 AstNodeId，Resolve 一次绑定 | 精确、稳定、可追踪，后续阶段简单 | Parser AST API 需要一次兼容性修改 |

## 决定

采用第三种方案：

1. `AstNameRef` 实现 `AstNode`，字段为 `AstNodeId id`、`SourceSpan span`、`String text`。
2. `SirAstBuilder` 根据父节点结构路径和引用角色生成 ID，例如 `.../step/0001/entity`、`.../step/0001/error`、`.../expression/0002/member`。
3. `ResolvePass` 是唯一名称绑定阶段，将引用 AstNodeId 映射到 SymbolId。
4. Step 产生的结果变量以 Step AstNodeId 作为声明位置绑定。
5. TypePass 和 NormalizePass 只读取绑定及 SymbolTable；NormalizePass 不允许调用 `byName` 或 `lookupInScope` 重新解析。
6. Normalized Model 中的语义引用使用 SymbolId，例如 Capability `fails` 使用 Error SymbolId。

## 接受的取舍

- AST 公共构造器发生一次破坏性变化；当前仍处 v0.1，成本可控。
- `referenceBindings` 历史名称偏窄，因为其中也包含部分声明节点到 SymbolId 的映射。v0.1 保留名称以减少无关 API 迁移，后续若公开为通用 Project Symbol Graph，可升级为 `nodeSymbolBindings`。

## 后果

正面结果：

- 同一引用只能对应一个稳定 SymbolId；重复绑定立即失败。
- Lowering 和 Generator 不需要理解词法作用域。
- SourceSpan、AstNodeId、SymbolId 可以串成完整追踪链。
- 为 Change SIR 的精确局部修改奠定基础。

负面结果：

- 新增任何引用型 AST 节点时，都必须同步设计稳定 ID 和绑定测试。
- AST 结构路径改变可能改变 AstNodeId，因此结构迁移未来需要版本化策略。

## 重新评估触发条件

当 Project Symbol Graph 支持多文件、导入、重命名或增量编译时，重新评估 `referenceBindings` 的命名、跨文件身份以及 AstNodeId 迁移协议，但不撤销“名称只解析一次”的原则。
