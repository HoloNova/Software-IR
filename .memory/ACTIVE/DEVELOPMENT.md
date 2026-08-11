---
created: 2026-07-16
updated: 2026-07-18
importance: high
confidence: confirmed
source: document
status: active
---

# 开发进展

## 当前阶段：工具链工程应用层与 Project Symbol Graph v0.1 已实现并完成审查收尾

当前分支为 `feat/sir-semantic-v0.1`。截至 2026-07-18，PSG v0.1 与 Application GRAPH 集成已完成独立审核和完整构建验证；提交基线以本轮提交为准。`.claude/` 保持用户本地未跟踪内容，绝不读取、暂存或提交。

## 当前完整管线

```text
SIR file -> strict UTF-8 read -> Parser -> Semantic -> Spring Boot Lowering
         -> deterministic Generator -> path/conflict preflight
         -> Project Symbol Graph build/validate -> same-volume transaction
         -> immutable manifest + graph or structured failure
```

## 已实现

- 父工程新增第七个模块 `sir-toolchain-application`；Generator 继续只返回不可变内存 `GeneratedFile`，没有磁盘、进程或覆盖策略副作用。
- Parser 公共接口新增 `SirParser.create()`，调用方无需依赖 internal 实现；工厂测试归属 parser 模块。
- 应用层公开 API 包含请求、冲突策略、执行阶段、诊断、失败处置、应用文件、清单和 sealed Success/Failure；集合防御性复制且顺序确定。
- `ToolchainApplication.execute` 只经各模块公开 API 依次编排，不绕过 Semantic、Lowering 或 Lowered IR Validator。
- 路径防护覆盖绝对根、归一化包含关系、正反斜杠/点段、Windows 非法字符与保留名、Locale.ROOT 大小写重复、输出根/父链/目标链符号链接。
- `FAIL_IF_EXISTS` 默认零覆盖；`REPLACE_EXISTING` 只替换普通文件；提交时再次检查策略与链接链，降低 preflight/commit 间竞态风险。
- 新根以同卷 staging + 单次 `ATOMIC_MOVE` 发布；现有根逐文件备份和原子发布。失败按每个目标的 backup/commit 状态逆序恢复；回滚失败返回 `RECOVERY_REQUIRED` 并保留恢复材料。
- 故障注入接口位于 internal 包且包私有；生产公开 API 不暴露事务钩子。
- 失败测试覆盖 Parse、Semantic、Lowering、Generation、READ、PREFLIGHT、WRITE、ROLLBACK；Windows 符号链接测试通过可注入探针执行，不再跳过。

## 验收结果

- 精确命令 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify` 于 2026-07-18 在沙箱外 BUILD SUCCESS。
- 测试：parser 43、semantic 78、lowering-api 4、spring-lowering 32、spring-generator 47、project-graph 94、application 94，总计 392；0 failure、0 error、4 个 Windows PathGuard 条件跳过。
- `git diff --check` 通过，仅有工作区 LF/CRLF 提示。
- 生成的 campus-market 工程内容、顺序、UTF-8 字节数和 SHA-256 已验证；KCG-Code 自身八模块 Maven 编译通过。

## 尚未验证（精确环境依赖）

- 生成工程的真实 Maven 编译与 Spring Context/启动未验证：`D:\maven-repo` 缺少 Spring Boot 3.5.3、MyBatis-Plus 3.5.12、MySQL Connector/J 9.3.0，且没有显式 MySQL 环境。
- 不以 stub JavaCompiler 成功冒充真实 Spring Boot 启动成功，也不修改生成 POM 的冻结版本来规避缺失依赖。

## 工作树注意事项

- PSG v0.1 已于提交 `420cfa1 feat: add project symbol graph v0.1` 纳入版本库；当前工作区除 `.claude/` 外应保持干净。
- `.claude/` 是用户本地未跟踪内容，未读取、未修改、不得暂存或提交。

## 下一步

PSG v0.1 已冻结为只读追踪图。下一阶段先由计划 Agent 设计 typed reference-site 契约及其测试/迁移边界，再决定是否进入 Graph 跨次复用和最小 Change SIR；不同时实现 Java 反向解析或写补丁。
