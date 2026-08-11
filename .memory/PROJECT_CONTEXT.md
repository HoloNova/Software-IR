---
created: 2026-08-11
updated: 2026-08-11
importance: critical
confidence: confirmed
source: current_code_tests_and_accepted_decisions
status: active
---

# KCG-Code 项目上下文

## 项目身份

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。模型或 SLM 负责产生可检查的 SIR；确定性程序负责 Parser、Semantic、Lowering、Generator、Project Graph、工程应用和 Change planning。

项目不允许模型绕过 IR 直接决定最终 Java 工程结构，也不把任意 Java 源码作为常规逃生口。

## 权威分工

- `AGENTS.md`：工作授权和仓库级强约束。
- Accepted ADR：冻结架构决策。
- 当前生产代码与实际执行测试：现有行为证据。
- `docs/PROJECT_STATUS.md`：当前能力边界。
- `docs/qualification/CURRENT_QUALIFICATION.md`：当前运行证据。
- `.memory`：精炼上下文，不覆盖上述权威。

## 当前模块

1. `sir-parser`
2. `sir-semantic`
3. `sir-lowering-api`
4. `sir-lowering-spring-boot`
5. `sir-generator-spring-boot`
6. `sir-project-graph`
7. `sir-change`
8. `sir-toolchain-application`
9. `kcg-cli`

## 当前主闭环

```text
SIR -> Parser -> Semantic -> Normalized Model
    -> Spring Boot Lowering -> Lowered IR
    -> deterministic Generator -> GeneratedFile set
    -> Application preflight + Project Graph + file transaction
```

## 当前变更闭环

```text
CURRENT Bundle + output + candidate SIR
  -> read-only context/target catalog
  -> Change planning
  -> fresh Apply validation
  -> UPDATE / CREATE / DELETE transaction
  -> next Bundle + CURRENT publish
  -> explicit recovery when required
```

CLI 当前只发布 `context` 和 `plan`。完整 `generate/register/apply/recover` 生命周期仍是后续产品选择。

## 事务方向

- `CURRENT=B0`：B1 尚未发布，只允许向后补偿到 B0。
- `CURRENT=B1`：B1 已发布，只允许向前验证和清理。
- CURRENT、Bundle、Journal 或物理身份不能证明时 fail closed。
- 方向只由 CURRENT 与 Journal 的 B0/B1 身份决定，不观察文件猜测。

正式决策：`docs/architecture/ADR-020-current-baseline-transaction-direction.md`。

## 当前资格

标准离线 `clean verify` 最近通过；精确 run/failure/error/skip 只见 `docs/qualification/CURRENT_QUALIFICATION.md`。

这不包含被 POM 排除的 conformance 包；Change fixtures 仍导致 Application/CLI 测试跳过；Generator 和 Project Graph 直接测试不足。外部 MySQL conformance 与完整本地 MVP 均未运行成功到可声明资格的程度。

## 当前优先级

1. Generator 行为测试。
2. Project Graph 直接测试。
3. Change fixtures 与操作族测试。
4. conformance testCompile 和显式外部资格。
5. UPDATE/CREATE/DELETE 故障矩阵。
6. CLI 完整生命周期产品决策。

详细完成门见 `docs/roadmap/REMAINING_WORK.md`。

## 工作规则

- 先复现真实路径，再修改。
- 生产行为修改先写最小失败测试。
- 不把历史测试数字、skip、exclude 或 NOT_RUN 写成当前成功。
- 不读取或提交用户本地 `.claude/`、`.trae/`、`.codex/`、`.agents/` 和受保护交付文件。
- Git 写入只在用户授权范围内进行，不强推或改写历史。
