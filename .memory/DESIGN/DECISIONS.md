---
created: 2026-07-16
updated: 2026-07-17
importance: high
confidence: confirmed
source: user_decision
status: active
---

# 技术决策记录

## 决策：ADR-003 应用层拥有工程应用与文件事务

**日期：** 2026-07-17

**背景：** Generator 已能确定性产出不可变内存文件集合，但安全写盘、覆盖策略、部分失败和恢复语义不属于渲染职责。

**最终选择：** 新增 `sir-toolchain-application`。公开入口 `ToolchainApplication.execute(ToolchainRequest)` 通过现有公开 API 编排全管线；Generator 保持纯函数。应用层拥有绝对输出根、路径/符号链接/重复防护、`FAIL_IF_EXISTS`/`REPLACE_EXISTING`、同卷 staging、原子发布、备份回滚、结构化 Success/Failure 和审计清单。

**失败语义：** preflight 前失败为 `NO_CHANGES`；现有根提交失败且成功补偿为 `ROLLED_BACK`；无法完整补偿为 `RECOVERY_REQUIRED` 并保留恢复材料。提交时重新检查冲突策略与链接链，不能只信任 preflight 快照。

**测试边界：** 故障注入钩子仅为 internal 包私有测试缝，不属于公开 API；Windows 通过可注入链接探针确定性验证链接拒绝逻辑。

**权衡：** Java `Path` 检查无法彻底消除所有 TOCTOU 窗口，但通过提交前多次重检、无覆盖原子移动和保守恢复语义缩小风险；更强保证需未来平台特定目录句柄 API，不下沉到 Generator/Core。

**相关组件：** `sir-toolchain-application`、ADR-003、`FileTransaction`、`PathGuard`。

## 决策：Lowering 契约与 Target Lowered IR 分模块

**日期：** 2026-07-16

**背景：** 第一条 Spring Boot Lowering 既要绑定具体技术栈，又不能让 Spring/MyBatis 概念进入 Core 或通用 Lowering API。

**选项：** 在通用 IR 中加入 Spring 字段；使用不受约束的字符串属性 Map；通用契约与 Target 专属强类型 Lowered IR 分离。

**最终选择：** 新增 `sir-lowering-api` 与 `sir-lowering-spring-boot`。前者只定义 Target 无关结果、诊断、稳定身份、来源和验证接口；后者拥有 Profile、强类型 Spring Boot Lowered IR、输入检查和 Validator。

**原因：** Core 保持目标无关，Target 节点仍可严格验证；Generator 以后可以纯渲染，不需要从字符串属性或 Normalized Model 再次推断。

**权衡：** 不同 Target 可以拥有各自的 Lowered IR；若未来需要跨 Target Generator 复用，应通过更小的公共渲染协议演进，不能把框架字段倒灌到 Core。

**相关组件：** `sir-lowering-api`、`sir-lowering-spring-boot`、未来 Generator。

## 决策：Spring Boot v0.1 使用单一冻结 Target Profile

**日期：** 2026-07-16

**最终选择：** 只接受 Java 21、Spring Boot、MyBatis-Plus、MySQL、Maven、REST；Profile 固定 Spring Boot 3.5.3 和 MyBatis-Plus 3.5.12。Target 不匹配时返回 `SIR-LOWER-TARGET-001`，不自动降级。

**原因：** 单一 Profile 让类型、路由、事务、持久化和 Artifact ownership 映射可重复验证，并覆盖校园二手交易示例。

**权衡：** 当前不是通用 Spring 配置器；其他版本、数据库、接口或持久化框架需要新增明确 Profile 和兼容性测试。

## 决策：Lowering 契约与 Target Lowered IR 分模块

**日期：** 2026-07-16

**背景：** 第一条 Spring Boot Lowering 既要绑定具体技术栈，又不能让 Spring/MyBatis 概念进入 Core 或通用 Lowering API。

**选项：** 在通用 IR 中加入 Spring 字段；使用不受约束的字符串属性 Map；通用契约与 Target 专属强类型 Lowered IR 分离。

**最终选择：** 新增 `sir-lowering-api` 与 `sir-lowering-spring-boot`。前者只定义 Target 无关结果、诊断、稳定身份、来源和验证接口；后者拥有 Profile、强类型 Spring Boot Lowered IR、输入检查和 Validator。

**原因：** Core 保持目标无关，Target 节点仍可严格验证；Generator 以后可以纯渲染，不需要从字符串属性或 Normalized Model 再次推断。

**权衡：** 不同 Target 可以拥有各自的 Lowered IR；若未来需要跨 Target Generator 复用，应通过更小的公共渲染协议演进，不能把框架字段倒灌到 Core。

**相关组件：** `sir-lowering-api`、`sir-lowering-spring-boot`、未来 Generator。

## 决策：Spring Boot v0.1 使用单一冻结 Target Profile

**日期：** 2026-07-16

**最终选择：** 只接受 Java 21、Spring Boot、MyBatis-Plus、MySQL、Maven、REST；Profile 固定 Spring Boot 3.5.3 和 MyBatis-Plus 3.5.12。Target 不匹配时返回 `SIR-LOWER-TARGET-001`，不自动降级。

**原因：** 单一 Profile 让类型、路由、事务、持久化和 Artifact ownership 映射可重复验证，并覆盖校园二手交易示例。

**权衡：** 当前不是通用 Spring 配置器；其他版本、数据库、接口或持久化框架需要新增明确 Profile 和兼容性测试。

## 决策：Lowering 契约与 Target Lowered IR 分模块

**日期：** 2026-07-16

**背景：** 第一条 Spring Boot Lowering 既要绑定具体技术栈，又不能让 Spring/MyBatis 概念进入 Core 或通用 Lowering API。

**选项：** 在通用 IR 中加入 Spring 字段；使用不受约束的字符串属性 Map；通用契约与 Target 专属强类型 Lowered IR 分离。

**最终选择：** 新增 `sir-lowering-api` 与 `sir-lowering-spring-boot`。前者只定义 Target 无关结果、诊断、稳定身份、来源和验证接口；后者拥有 Profile、强类型 Spring Boot Lowered IR、输入检查和 Validator。

**原因：** Core 保持目标无关，Target 节点仍可严格验证；Generator 以后可以纯渲染，不需要从字符串属性或 Normalized Model 再次推断。

**权衡：** 不同 Target 可以拥有各自的 Lowered IR；若未来需要跨 Target Generator 复用，应通过更小的公共渲染协议演进，不能把框架字段倒灌到 Core。

**相关组件：** `sir-lowering-api`、`sir-lowering-spring-boot`、未来 Generator。

## 决策：Spring Boot v0.1 使用单一冻结 Target Profile

**日期：** 2026-07-16

**最终选择：** 只接受 Java 21、Spring Boot、MyBatis-Plus、MySQL、Maven、REST；Profile 固定 Spring Boot 3.5.3 和 MyBatis-Plus 3.5.12。Target 不匹配时返回 `SIR-LOWER-TARGET-001`，不自动降级。

**原因：** 单一 Profile 让类型、路由、事务、持久化和 Artifact ownership 映射可重复验证，并覆盖校园二手交易示例。

**权衡：** 当前不是通用 Spring 配置器；其他版本、数据库、接口或持久化框架需要新增明确 Profile 和兼容性测试。

## 决策：Core 与 Target 内部严格分层

**日期：** 2026-07-16（由此前讨论与现有文档汇总）

**背景：** 项目既希望 SIR 能确定性生成 Spring Boot/MyBatis-Plus 工程，又要长期支持不同数据库、中间件、算法和前端契约。

**问题：** 是否允许 SIR 绑定具体框架，以及如何避免 Core 被单一 Web 技术栈锁死。

**选项：**
1. Core SIR 直接包含 Spring/MyBatis/Redis 细节。
2. 完全禁止具体框架绑定。
3. Core 保持目标无关，Extension/Target Profile/Lowered IR 明确绑定具体框架。

**最终选择：** 方案 3。允许项目选择 Spring Boot + MyBatis-Plus，但绑定发生在 Target 层，不进入 Core Semantic IR。

**原因：** 同时保留确定性生成与可扩展性；Core 语义可复用，Target 可按技术栈形成单一确定分支。

**权衡：** 需要额外设计 Lowered IR 和 Target Lowering，不能直接从 AST 套模板。

**相关组件：** Core IR、Extension、Target Profile、Constraint Pack、Lowered IR、Generator。

## 决策：名称只解析一次并按节点固化绑定

**日期：** 2026-07-14

**背景：** 后续 Lowering、Generator、Change SIR 和 Project Symbol Graph 需要准确知道每一次源码引用指向哪个声明。

**问题：** 仅保存名称字符串会导致后续阶段重复查找、作用域漂移和同名歧义。

**选项：** 后续阶段继续按名称查找；用所属 Step ID 代替；每个 `AstNameRef` 拥有 AstNodeId 并在 Resolve 绑定 SymbolId。

**最终选择：** 每个名称引用拥有独立 AstNodeId，由 ResolvePass 唯一绑定到 SymbolId。

**原因：** 绑定精确、稳定、可追踪，并为局部修改和跨阶段确定性提供基础。

**权衡：** AST API 有一次 v0.1 破坏性调整，未来结构迁移需版本协议。

**相关组件：** sir-parser AST、ResolvePass、TypePass、NormalizePass、Project Symbol Graph。

## 决策：确定性生成采用“语义确定 + 目标 Lowering + 纯渲染”

**日期：** 2026-07-16（由此前讨论与现有文档汇总）

**背景：** 真实工程约束复杂，完全让模型补齐代码会失去可重复生成；让 SIR 描述每个 Java 细节又会导致语言过重。

**问题：** 如何在可实现范围内保留“确定性生成”。

**选项：** SIR 后由模型自由生成；SIR 逐行描述全部源码；SIR 固化语义和约束，Lowering 固化目标结构，Generator 只渲染。

**最终选择：** 第三种方案。

**原因：** 确定性边界落在程序可校验的模型转换上，模型可参与 SIR 生成和复杂实现建议，但不能绕过验证链。

**权衡：** v0.1 只能覆盖受支持的语义子集；复杂算法可能先通过受类型检查的能力契约接入，而不是任意内嵌源码。

**相关组件：** NormalizedSemanticModel、Lowering API、Lowered IR、Generator、Constraint VM。

## 决策：Redis 暂不进入 v0.1 Core

**日期：** 2026-07-16（由此前讨论与现有文档汇总）

**背景：** Redis 表面上只是增加一个组件，实际包含资源、TTL、序列化、一致性、失败策略和具体客户端等语义选择。

**问题：** 是否在 Core Workflow 中直接增加 Redis 命令或普通变量。

**选项：** 直接加入 Core；完全不支持；以后通过 Redis Extension + Capability/Resource + Target Profile Lowering 接入。

**最终选择：** 第三种方案。

**原因：** 能明确约束 Redis 语义，同时让不支持 Redis 的 Target 拒绝该 Extension，避免 Core 被实现细节污染。

**权衡：** Redis 功能推迟到 Lowering 边界稳定之后。

**相关组件：** Extension、Constraint Pack、Target Profile、Spring/Redis Lowering。

## 决策：项目修改使用稳定身份和 Artifact ownership

**日期：** 2026-07-16（由此前讨论与现有文档汇总）

**背景：** 将现有项目重新抽取成整份 SIR 再全量生成，会造成大范围改动并覆盖用户代码。

**问题：** Change SIR 如何支持局部修改。

**选项：** 按名称模糊匹配；整份重生成；以 SymbolId/AstNodeId 定位并基于 Project Symbol Graph 计算影响范围。

**最终选择：** 第三种方案，并要求未来建立 Artifact ownership 与用户代码保护区。

**原因：** 可追踪、可做冲突检测，并能只重新生成受影响 Artifact。

**权衡：** 依赖未来的多文件 Symbol Graph、增量 Pass 和反向解析能力，当前尚未实现。

**相关组件：** Change SIR、Project Symbol Graph、Lowering、Generator。
