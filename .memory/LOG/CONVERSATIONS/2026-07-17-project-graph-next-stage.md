---
created: 2026-07-17
updated: 2026-07-17
importance: high
confidence: confirmed
source: document_review
status: active
---

# Project Symbol Graph v0.1 下一阶段建议

## 文档审计结论

代码、ADR-003、应用层设计和 283 项测试表明工具链工程应用层已经完成。仓库级 `AGENTS.md`、项目总纲、系统架构指南以及 CORE/RESEARCH/EXPERIMENTS 记忆仍有“应用层尚未实现、202 项测试”的陈旧描述，已同步为当前事实。

## 推荐方向

长期架构的三条主线是 Software IR、Project Symbol Graph 和 Constraint VM。当前 Software IR 到安全工程应用的生成链已经闭合，因此下一最有方向价值的独立任务是 Project Symbol Graph v0.1，而不是继续横向增加 Redis、算法或前端 Target 特性。

## 最小范围

- 只读图快照，不修改 SIR 或源码；
- 复用 SymbolId、AstNodeId、LoweredNodeId、Artifact ownership 和 manifest provenance；
- 最小节点覆盖 semantic declaration、lowered artifact、applied file；
- 最小边支持从语义声明追踪到 Artifact 和文件；
- 重复 ID、悬空边、未知端点结构化失败；
- 集合不可变，节点/边顺序和摘要在重复运行、Locale 与工作目录变化下确定。

## 非目标

Java 反向解析、图数据库、持久化服务、完整 Change SIR、AST Patch、Repair Agent、Constraint VM、Redis、算法和前端契约不与本阶段并行。
