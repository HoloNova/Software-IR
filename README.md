# KCG-Code / Software IR

KCG-Code 是面向 Coding Agent 的 Software IR 编译、确定性代码生成和安全工程变更工具链。

它让 Agent 或 SLM 先产生可检查的 Software IR，再由确定性程序完成解析、名称绑定、类型检查、约束验证、Target Lowering、代码生成、Project Symbol Graph、工程应用和受保护的变更事务。项目不允许模型绕过 IR 直接决定最终 Java 工程结构。

## 当前范围

仓库包含九个 Maven 子模块：

- Parser 与不可变 AST
- Semantic pipeline 与 typed reference-site binding
- Target 无关 Lowering API
- Spring Boot Lowered IR
- 确定性 Spring Boot Generator
- Project Symbol Graph
- Change IR v0.1-v0.6 planning
- Toolchain Application、Bundle、事务与显式恢复
- 只读 `check` / `context` / `plan` CLI（check本机交付、Q23 CI待验）

已验收源码可离线完成默认 Reactor 构建（10 个 Reactor 模块），并已在**登记过的参考环境**上得到外部 MySQL conformance 的 `QUALIFIED` 结论。最新同SHA CI为Q21；本批Q23静态校验已有定向/规模证据，但新CI尚未运行，不能引用旧结果验收它。但仍有明确的未覆盖项（Windows 平台证据、thin JAR/发行包、完整 CLI 本地生命周期），因此不宣称生产就绪。精确结论与缺口登记见 [CURRENT_QUALIFICATION.md](docs/qualification/CURRENT_QUALIFICATION.md) 第 0 节。

## 从这里开始

如果你准备把项目交给一个不了解旧聊天的人，先打开[项目负责人操作与交接手册](docs/PROJECT_OWNER_GUIDE.md)。它会告诉你怎么派活、怎么验收，以及怎么进入下一张工作单。

1. [项目负责人操作与交接手册](docs/PROJECT_OWNER_GUIDE.md)
2. [当前唯一工作单](docs/roadmap/ACTIVE_WORK.md)
3. [全局工程规则](AGENTS.md) 与 [项目主体说明](MAIN.md)
4. [当前项目状态](docs/PROJECT_STATUS.md)
5. [系统架构与实现指南](docs/KCG-Code_%E7%B3%BB%E7%BB%9F%E6%9E%B6%E6%9E%84%E4%B8%8E%E5%AE%9E%E7%8E%B0%E6%8C%87%E5%8D%97.md)
6. [当前资格报告](docs/qualification/CURRENT_QUALIFICATION.md)
7. [剩余工作路线图](docs/roadmap/REMAINING_WORK.md)

## 构建

需要 Java 21 和已经准备好的离线 Maven 仓库：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
```

CLI 当前通过 Maven exec 运行：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl kcg-cli exec:java "-Dexec.mainClass=io.kcg.cli.KcgCli" "-Dexec.args=--help"
```

批量静态校验 `check < samples.jsonl` 的直接Java调用、JSONL协议和预算见[SIR批量校验](docs/SIR_BATCH_CHECK.md)。静态通过只进候选池，不保证生成Java能构建或业务正确；独立真实评测仍为[草案](docs/qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)。

## 资格边界

默认构建通过不等于外部 MySQL 资格通过。被跳过、被 POM 排除或未显式启动的测试必须报告为 `SKIPPED`、`BLOCKED` 或 `NOT_RUN`，不能折算成成功。

当前精确数字和未验证范围见 [CURRENT_QUALIFICATION.md](docs/qualification/CURRENT_QUALIFICATION.md)。
