---
created: 2026-07-16
updated: 2026-07-17
importance: high
confidence: confirmed
source: document_review
status: active
---

# 研究记录

## 已冻结结论

- Lowered IR 的稳定身份、来源、独立验证与版本契约已由 `sir-lowering-api` 和 Spring Boot Lowered IR 实现。
- Artifact ownership、Target Profile、纯函数 Generator、稳定文件路径和生成失败语义已实现。
- 工程应用层已冻结显式输出根、路径/符号链接防护、默认拒绝覆盖、显式替换、原子发布、回滚和结构化执行结果。
- 精确离线 `clean verify` 已验证 7 模块、283 项测试，0 failure、0 error、0 skipped。

## 推荐研究主题：Project Symbol Graph v0.1

**目的：** 从“可安全生成新工程”进入“可精确描述生成工程结构”，为 Change SIR、影响分析和增量修改建立稳定公共黑板。

### 必须先冻结

1. 图节点身份如何复用 `SymbolId`、`AstNodeId`、`LoweredNodeId` 与文件相对路径，避免新造不稳定 ID。
2. v0.1 节点最小集合：semantic declaration、lowered artifact、generated/applied file；是否需要 software/project 根节点。
3. v0.1 边最小集合：DECLARES、LOWERS_TO、OWNS_ARTIFACT、GENERATES_FILE、REFERENCES；每种边由哪个现有阶段提供事实。
4. 图构建入口使用哪些公开快照；应用层不能读取 Generator internal，Graph 也不能让 Semantic 依赖 Target。
5. 重复节点/边、缺失端点、来源不一致如何返回结构化 Failure。
6. 节点/边排序、不可变集合、版本字段和确定性序列化规则。

### 最小垂直切片

- 新增独立 Project Graph API/实现模块；依赖方向在设计审核后确定。
- 对 campus-market 的一次成功编译结果构建只读图快照。
- 能从一个 `SymbolId` 查询其 Lowered Artifact 与最终文件路径。
- 重复稳定 ID、悬空边和未知 provenance 必须失败。
- 重复构建、不同默认 Locale 和不同工作目录得到相同节点/边顺序与摘要。

### 明确非目标

- Java 源码反向解析或从代码恢复完整 SIR；
- 图数据库、持久化服务或分布式索引；
- Change SIR 写操作、AST Patch、用户代码保护区；
- Repair Agent、Constraint VM、Redis、算法和前端契约。

## 仍待环境验证

生成工程的真实 Maven 编译与 Spring Context/启动仍依赖本地提供 Spring Boot 3.5.3、MyBatis-Plus 3.5.12、MySQL Connector/J 9.3.0 和显式 MySQL 环境。该环境缺口不阻塞只读 Project Symbol Graph 设计。
