---
created: 2026-07-16
updated: 2026-08-11
importance: high
confidence: confirmed
source: current_architecture
status: active
---

# 架构摘要

## 主编译链

```text
SIR -> Parser -> Semantic -> Normalized Model
    -> Target Lowering -> SpringBootLoweredModel
    -> deterministic Generator -> GeneratedFile set
    -> Application preflight -> Project Graph -> file transaction
```

## 变更链

```text
CURRENT Bundle + output + candidate
  -> read-only context
  -> Change planner
  -> fresh Apply validation
  -> UPDATE / CREATE / DELETE transaction
  -> next Bundle + CURRENT
  -> explicit recovery if required
```

## 核心分层

- Parser：语法、AST、SourceSpan、AstNodeId。
- Semantic：唯一 Resolve、类型、验证、Normalize、typed reference-site。
- Lowering：把目标无关语义转换为独立 Target construction model。
- Generator：只做纯渲染。
- Project Graph：只读 provenance graph，不访问文件系统。
- Change：对稳定目标做 scope/impact/closure 和 typed plan。
- Application：唯一文件系统与持久状态权威。
- CLI：typed API adapter，不拥有状态语义。

## 身份链

`SourceSpan -> AstNodeId -> SymbolId -> LoweredNodeId/ArtifactId -> relative file path`。

## 当前协议重点

- Core 与 Target 分离。
- Resolve once。
- Lowered IR owns generation decisions。
- CURRENT 是基线发布唯一线性化点。
- B0 backward compensation；B1 forward verification/cleanup。
- 不确定性 fail closed。

完整说明见 `docs/KCG-Code_系统架构与实现指南.md`。
