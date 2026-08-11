# KCG-Code 当前项目状态

> 更新日期：2026-08-11
> 状态：正常开发中的 v0.1 工程；默认离线构建可运行，完整资格尚未完成

## 1. 项目定位

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。Agent 或 SLM 负责提出可检查的 SIR；确定性程序负责解析、绑定、类型、约束、Lowering、生成、Project Graph、文件事务和变更计划。

它不是普通 CRUD DSL，也不允许模型绕过 IR 直接决定最终 Java 工程结构。

## 2. 当前实现

父 Maven Reactor 包含九个子模块：

| 模块 | 当前职责 |
|---|---|
| `sir-parser` | ANTLR4 Grammar、不可变 AST、SourceSpan、稳定 AstNodeId、解析诊断 |
| `sir-semantic` | Resolve/Type/Validate/Normalize、typed reference-site binding、Normalized model |
| `sir-lowering-api` | Target 无关 Lowering 契约、来源、版本、诊断和 Validator |
| `sir-lowering-spring-boot` | Java 21/Spring Boot/MyBatis-Plus/MySQL/Maven/REST Lowered IR |
| `sir-generator-spring-boot` | 只消费 Lowered IR 的确定性内存文件生成 |
| `sir-project-graph` | Project Graph 构建、校验、canonical serialization/load |
| `sir-change` | Change IR v0.1-v0.6 typed planning、scope、impact 和 closure |
| `sir-toolchain-application` | 编译/生成/Graph、Bundle、CURRENT、文件事务、Apply 和显式恢复 |
| `kcg-cli` | 当前公开只读 `context` / `plan` 命令和 canonical JSON |

## 3. 当前公开能力边界

### 已实现

- SIR v0.1 Parser、Semantic、Spring Boot Lowering 与确定性 Generator。
- 显式输出根上的路径/冲突预检、Graph 构建、同卷 staging、原子发布和结构化失败。
- Project Graph 的只读模型、canonical encoder/decoder 和 validator。
- Change IR v0.1-v0.6 planner。
- Application-owned Bundle、CURRENT、UPDATE/CREATE/DELETE transaction 和显式恢复 API。
- CLI 的只读 context inspection 与 plan。

### 尚未作为当前产品能力发布

- CLI `generate`、`register`、`apply`、`recover` 完整本地生命周期。
- 可重复执行的完整外部 MySQL conformance。
- fat JAR 或独立 CLI 发行包；当前验证入口是 Maven exec。
- Redis Extension、第二 Target、Constraint VM、Java 反向解析、GUI/daemon、多用户和新的增量编译系统。

## 4. 当前资格状态

最近一次标准离线 Reactor 运行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

结果为通过。精确测试数、skip、POM 排除和未运行范围只以 `docs/qualification/CURRENT_QUALIFICATION.md` 的最近一次实际记录为准。

当前结论：

- 默认离线 Reactor：通过。
- 外部 MySQL conformance：`NOT_RUN / BLOCKED`。
- 完整本地 MVP：`NOT_RUN`。
- 生产、安全认证、性能、HA 和全平台资格：未声明。

精确模块数字与阻断原因见 `docs/qualification/CURRENT_QUALIFICATION.md`。

## 5. 已冻结的高风险契约

- 名称只在 Resolve 阶段解析一次。
- Lowered IR 拥有生成决策权，Generator 只做纯渲染。
- Application 是 Bundle、CURRENT、LOCK、Journal 和文件系统写入的唯一权威。
- `CURRENT=B0` 只允许向后补偿；`CURRENT=B1` 只允许向前验证和清理。
- 方向、路径、物理身份或补偿证据不确定时 fail closed。
- DELETE backup 只使用同卷 hard link，不增加 copy/move/replace fallback。
- context/plan 只读，不构成 Apply 或写盘授权。

事务方向的正式决策见 `docs/architecture/ADR-020-current-baseline-transaction-direction.md`。

## 6. 当前优先工作

当前真正可以派发的唯一任务见 `docs/roadmap/ACTIVE_WORK.md`。下面是长期顺序，不应一次全部交给一个执行者：

1. 为 Generator 建立系统行为测试和生成工程编译验收。
2. 为 Project Graph 建立直接模块测试。
3. 补齐 Change base/candidate fixtures，消除 assumption skip。
4. 使 conformance 包重新参加 testCompile，并保留显式 opt-in 外部运行。
5. 补齐 UPDATE/CREATE/DELETE 故障注入矩阵。
6. 在上述前置完成后决定是否发布 ADR-019 的完整 CLI 生命周期。

详细任务和完成条件见 `docs/roadmap/REMAINING_WORK.md`。

## 7. 文档状态规则

- `docs/PROJECT_OWNER_GUIDE.md` 是项目负责人的操作入口。
- `docs/roadmap/ACTIVE_WORK.md` 是本轮唯一工作单。
- 本文件是当前能力入口。
- `docs/qualification/` 只记录实际运行证据和覆盖缺口。
- Accepted ADR 记录冻结契约。
- `docs/superpowers/specs/` 下的日期化文件记录当时设计背景，其中“当前”“下一阶段”和历史运行数字不自动代表今天的状态。
- `.memory/LOG` 与 Git 历史用于追溯，不参与当前状态裁决。
