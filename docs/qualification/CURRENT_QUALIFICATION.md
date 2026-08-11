# KCG-Code 当前资格报告

- 资格日期：2026-08-11
- 运行环境：Windows 11、Java 21、离线 Maven 仓库 `D:\maven-repo`
- 总体结论：默认离线 Reactor 通过；外部 conformance 与完整本地 MVP 未完成

## 1. 标准命令

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

最近一次实际结果：`BUILD SUCCESS`，父工程和九个子模块全部进入 Reactor 并成功结束。

## 2. 模块测试统计

统计以 Surefire XML 为准；JUnit `@Nested` 容器和类级 assumption 的展示差异不改变实际 testcase 统计。

| 模块 | run | fail | error | skip | 当前解释 |
|---|---:|---:|---:|---:|---|
| `sir-parser` | 43 | 0 | 0 | 0 | Parser 基础契约 |
| `sir-semantic` | 103 | 0 | 0 | 0 | 含 typed reference-site 契约 |
| `sir-lowering-api` | 4 | 0 | 0 | 0 | API 契约 |
| `sir-lowering-spring-boot` | 32 | 0 | 0 | 0 | 含 19 项 `@Nested` hardening 测试 |
| `sir-generator-spring-boot` | 14 | 0 | 0 | 0 | 已覆盖 canonical 输出，以及 POM、Application、Enum、Mapper、Exception 直接契约；其余 Renderer、环境确定性与离线编译仍待补齐 |
| `sir-project-graph` | 0 | 0 | 0 | 0 | 无直接模块测试 |
| `sir-change` | 22 | 0 | 0 | 0 | API/架构/fixture 支撑测试，覆盖不足 |
| `sir-toolchain-application` | 132 | 0 | 0 | 10 | 不含被 POM 排除的 conformance 包 |
| `kcg-cli` | 5 | 0 | 0 | 0 | hardening 测试；工作流类被类级 assumption 跳过 |
| **合计** | **355** | **0** | **0** | **10** | 默认构建无失败、无错误 |

## 3. 跳过与排除

### 计入 Surefire 的 10 项 skip

- `ChangePlanningApplicationTest` 6 项：缺少当前 Change base/candidate SIR fixtures。
- `PathGuardTest` 4 项：Windows 条件下不适用的非 Windows 路径语义。

### 未计入 testcase 数的类级 skip

- `KcgCliWorkflowTest` 13 个测试方法因同一批 Change fixtures 缺失而在类初始化阶段 assumption skip。

### POM 硬排除

`sir-toolchain-application` 当前同时从 testCompile 和 Surefire 排除：

```text
io/kcg/sir/application/conformance/**
```

目录中有 46 个 Java 文件，但多个 suite/fixture 类包含不完整实现或占位尾部。默认构建成功不能证明这些文件可编译或 conformance 可运行。

## 4. CLI 资格

当前 CLI 版本：`KCG-CLI-CHANGE-PLANNING-V1`。

- `--help`、`--version`：可运行。
- `context`、`plan`：当前正式命令。
- `generate`、`register`、`apply`、`recover`：当前返回 unknown command，不是已发布 CLI 能力。
- 当前 JAR 是 thin JAR；直接 `java -jar` 不作为受支持运行方式。
- 已验证入口是 Maven exec。

## 5. 外部资格

| 资格项 | 当前状态 | 原因 |
|---|---|---|
| 默认离线 Reactor | PASS | 标准命令实际运行成功 |
| Windows 路径与 junction 定向测试 | PASS/PARTIAL | 当前测试通过；第二卷挂载点未验证 |
| 外部 MySQL conformance | NOT_RUN / BLOCKED | conformance 包被 POM 排除且当前源码闭包不完整 |
| 完整 CLI 本地生命周期 | NOT_RUN | 四个写命令未发布，fixtures 和外部资格也未闭合 |
| 生产就绪 | NOT_CLAIMED | 无生产、安全、性能、HA 或全平台资格 |

历史环境中出现过的 `QUALIFIED` 或 `MVP_FEASIBLE` 只属于对应的日期化证据，不自动转移为当前版本结论。

## 6. 主要残余风险

1. conformance 包没有参加测试编译和运行。
2. Generator 已有 14 项直接测试；三组 canonical 输出和 POM/Application/Enum/Mapper/Exception 契约已冻结，但 Entity/DTO、Service/Controller/Workflow、跨环境确定性和生成工程离线编译仍未完成。
3. Project Graph 没有直接模块测试。
4. Change fixtures 缺失造成 Application 和 CLI 测试跳过。
5. UPDATE/CREATE/DELETE 的完整跨阶段故障注入矩阵尚未形成。
6. CLI 完整生命周期和发行形态尚未决定。

## 7. 结论使用规则

- 可以说：默认离线 Reactor 在上述环境和命令下通过。
- 不可以说：所有测试通过、conformance 通过、完整 MVP 通过或生产就绪。
- 新运行完成后直接更新本文件的日期、命令、模块统计、skip/exclude 和外部资格状态；不要叠加多个相互竞争的“当前报告”。
