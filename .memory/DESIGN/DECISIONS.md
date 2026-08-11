---
created: 2026-07-16
updated: 2026-08-11
importance: high
confidence: confirmed
source: accepted_adrs_and_current_contracts
status: active
---

# 技术决策摘要

## Resolve once

每个名称引用由 AstNodeId 标识，并在 Resolve 唯一绑定 SymbolId。后续阶段不按名称重新查找。

正式记录：ADR-001。

## Core / Target / Lowered IR 分离

Core 保持目标无关；Target Profile 与 Target Lowering 绑定 Spring Boot/MyBatis-Plus/MySQL 等选择；Generator 只消费独立 Lowered IR。

## Generator 保持纯函数

Generator 返回不可变内存 `GeneratedFile` 集合，不读取 SIR/AST/SymbolTable，不处理磁盘和事务。

## Application 拥有工程应用

Application 独占路径、冲突、Graph stage、Bundle、CURRENT、LOCK、Journal、文件事务、Apply 和显式恢复。CLI 和 Generator 不得建立第二套状态权威。

正式记录：ADR-003。

## CURRENT 决定事务方向

- `CURRENT=B0`：向后补偿到 B0。
- `CURRENT=B1`：向前验证和清理 B1。
- 不匹配或证据不充分：fail closed。

正式记录：Accepted ADR-020。

## CLI 当前保持只读

当前 CLI 只发布 `context` 和 `plan`。完整本地写生命周期仍由 Proposed ADR-019 描述，不能提前当作现有能力。

## Redis 与其他扩展

Redis、第二 Target、Constraint VM 和前端契约不进入 Core；未来通过 Extension、Constraint Pack、Target Profile 和独立 Lowering 接入。
