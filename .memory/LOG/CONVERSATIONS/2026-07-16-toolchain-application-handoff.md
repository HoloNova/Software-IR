---
created: 2026-07-16
updated: 2026-07-16
importance: high
confidence: confirmed
source: user_decision
status: active
---

# 工具链工程应用层接力摘要

## 已完成阶段

- Git 快照：`84a726d feat: complete deterministic spring generation stage`。
- v0.1 已形成 `Parser -> Semantic -> Lowering API -> Spring Boot Lowering -> Deterministic Generator` 最小闭环。
- Generator 返回不可变内存 `GeneratedFile` 集合，不接触磁盘；这是 ADR-002 的架构边界。
- Unit/void、compound Find 分组、actor identity、TransportPlan response、一致性 import 与原始生成源码编译问题已经加固。
- 最终离线验收：parser 41 + semantic 78 + lowering-api 4 + spring-lowering 32 + generator 47 = 202 项，0 failure、0 error、0 skipped；6 个 Reactor 模块全部 SUCCESS。
- `sir-parser/pom.xml` 未修改；受限沙箱内 `target/classes`/ANTLR 异常按环境问题处理，不得据此改造 Parser POM。

## 用户确认的下一阶段

优先开始“工具链工程应用层”，目标是把内存中的生成结果接入安全、可验证的实际工程工作流。应先完成设计和边界审核，再实现最小垂直切片。

候选职责包括：统一编排 SIR 读取、Parse、Semantic、Lowering、Generate；安全输出目录；显式覆盖策略；原子写入；生成清单/摘要；结构化执行结果；真实 Maven 编译与最小启动验收。具体模块名和首批能力必须基于当前代码与文档设计，不预先硬编码结论。

## 不可破坏边界

1. `SpringBootGenerator` 继续是纯函数，只返回内存文件，不加入文件系统、进程执行或覆盖策略。
2. 应用层只能编排公开 API，不读取 Generator internal，不绕过 Parser/Semantic/Lowering Validator。
3. 不把 Spring/MyBatis、CLI 或文件系统概念倒灌到 Core Semantic IR 或 `sir-lowering-api`。
4. 输出根目录必须显式提供；所有生成路径都要在归一化后保持在根目录内，拒绝绝对路径、`..`、符号链接逃逸和重复路径。
5. 默认不得覆盖既有文件；覆盖/冲突行为必须是显式、可测试的策略。写入失败不得留下伪成功或半可信报告。
6. 本阶段不并行实现 Redis Extension、前端契约、算法能力、Change SIR、Project Symbol Graph 或 Java 反向解析。
7. 不生成默认数据库密码，不宣称 stub 编译等同真实框架运行；验收结论必须区分父工程测试、生成工程真实编译和 Spring 启动验证。
8. 继续遵守先失败测试后实现、不可变结果、稳定顺序、`Locale.ROOT`、结构化诊断和离线验收协议。

## 新会话启动顺序

1. 读取 `AGENTS.md`、`.memory/INDEX.md`、`.memory/CORE/PROJECT.md`。
2. 以 coding 模式加载 CORE、DESIGN/ARCHITECTURE、DESIGN/DECISIONS、ACTIVE/TODO；需要环境排障时再读 ACTIVE/BUGS 与 DEVELOPMENT。
3. 检查提交 `84a726d` 和当前工作树，保护 `.claude/`。
4. 审核公开 API 与模块依赖，形成工具链工程应用层设计、威胁模型、失败语义、测试矩阵和分阶段实施计划。
5. 设计获准后从最小端到端失败测试开始实现，不同时扩展其他长期方向。
