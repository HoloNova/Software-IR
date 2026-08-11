---
created: 2026-07-16
updated: 2026-07-18
importance: high
confidence: confirmed
source: user_decision
status: active
---

# 待办事项

## 下一任务（推荐）

- [ ] typed reference-site 契约设计：在 `referenceBindings` 之外定义 reference-site `AstNodeId`、引用角色、生命周期与不变量；`referenceBindings` 现混合真正引用、字段/identity 声明、actor/input 声明和 workflow step 结果，`findItemBindings` 又按 Find step 保存 item 声明，二者不能直接作为 `REFERENCES` provenance。
- [ ] 该阶段同时收紧 Resolve-once 边界：将 TypePass、ValidatePass、NormalizePass 内现存的 `byName` / `lookupInScope` 与基于 SymbolId 文本恢复语义的路径迁移为消费正式绑定；不得把此项降格为顺手重构。
- [ ] 在不修改实现的前提下，先形成 ADR/设计、公共 API 边界、迁移影响评估和完整测试矩阵：全部引用角色、每个合法 site 恰一绑定、非法 site 仅由 Resolve 诊断、角色与目标 SymbolKind 相容、不可变性与确定性。通过主 Agent 审核后再交给执行 Agent。
- [ ] 契约稳定后，评估 Graph canonical form 的跨次序列化/加载校验，随后才设计最小 Change SIR 垂直切片（SymbolId/AstNodeId 定位、影响范围、受影响 Artifact、用户手写保护区冲突）。

## 待开始

- [ ] 在具备 Spring Boot 3.5.3、MyBatis-Plus 3.5.12、MySQL Connector/J 9.3.0 和显式 MySQL 环境后，补做生成工程真实 Maven 编译与 Spring Context/启动验收。
- [ ] Redis Extension：缓存资源、读写策略、TTL、一致性要求；通过 Extension + Target Profile Lowering 接入。
- [ ] 算法与复杂逻辑：扩展为有类型的表达式、操作或外部能力契约；不使用任意 Java 源码逃生口。
- [ ] 前端契约：Core/Extension 描述输入、输出、错误和权限；Target Profile 决定 REST/OpenAPI/SDK。
- [ ] Change SIR：基于 SymbolId/AstNodeId 定位，在 Project Symbol Graph 上计算影响范围，只处理受影响 Artifact。

## 已完成

- [x] 完成 Parser、Semantic、Spring Boot Lowering 和纯函数 Generator 的 v0.1 编译闭环。
- [x] 新增 `sir-toolchain-application`，通过公开 API 统一编排 READ、PARSE、SEMANTIC、LOWERING、GENERATION、PREFLIGHT、WRITE/ROLLBACK。
- [x] 实现严格 UTF-8、绝对输出根、路径归一化、Windows 保留名/非法字符、大小写重复和符号链接链防护。
- [x] 实现默认拒绝覆盖与显式 `REPLACE_EXISTING`；冲突策略在 preflight 和 commit 时均重检。
- [x] 实现同卷 staging、新根原子整树发布、现有根逐文件原子发布、备份恢复、`ROLLED_BACK`/`RECOVERY_REQUIRED` 与恢复材料语义。
- [x] 实现不可变结构化 `ToolchainResult`、诊断、生成清单、SHA-256 与 CREATED/REPLACED 审计信息。
- [x] 修复审查发现的当前文件备份丢失风险，事务按“已备份/已发布”状态逆序回滚。
- [x] 将故障注入钩子收回 internal；公开入口仅保留 `execute(ToolchainRequest)`。
- [x] Windows 下以可注入链接探针确定性验证 4 类符号链接拒绝场景，无 OS 跳过。
- [x] 2026-07-17 离线 `clean verify`：parser 43 + semantic 78 + lowering-api 4 + spring-lowering 32 + spring-generator 47 + application 79 = 283 项通过，0 failure、0 error、0 skipped。
- [x] Project Symbol Graph v0.1：新增 `sir-project-graph`，实现只读、不可变、确定性图、唯一节点/边、typed provenance、canonical digest、结构化 Failure 与查询 API。
- [x] Application GRAPH 阶段：在 PREFLIGHT 后、WRITE 前构建/验证 Graph；失败 `NO_CHANGES`，成功后与 Manifest 一同发布；Spring Boot adapter 保留真实 `LoweredOrigin`。
- [x] PSG 收尾验收：精确诊断归属、重复边去级联、三入边单诊断、ProjectRole 合成来源豁免和真实 Lowered origin 直比较均有回归测试；2026-07-18 离线 `clean verify` 为 392 项，0 failure、0 error、4 个 Windows 条件跳过。

## 明确不在下一小步同时实施

- Redis、算法、前端联调、完整 Change SIR、Java 反向解析、图数据库、Constraint VM 和任意源码逃生口不得与 typed reference-site 契约并行展开。
