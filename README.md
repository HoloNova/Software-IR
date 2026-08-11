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
- 只读 `context` / `plan` CLI

当前代码可以离线完成默认 Reactor 构建，但仍有明确的测试和外部资格缺口，因此不宣称生产就绪或完整 MySQL conformance。

## 从这里开始

如果你准备把项目交给一个不了解旧聊天的人，先打开[项目负责人操作与交接手册](docs/PROJECT_OWNER_GUIDE.md)。它会告诉你怎么派活、怎么验收，以及怎么进入下一张工作单。

1. [项目负责人操作与交接手册](docs/PROJECT_OWNER_GUIDE.md)
2. [当前唯一工作单](docs/roadmap/ACTIVE_WORK.md)
3. [全局工程规则](AGENTS.md)
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

## 资格边界

默认构建通过不等于外部 MySQL 资格通过。被跳过、被 POM 排除或未显式启动的测试必须报告为 `SKIPPED`、`BLOCKED` 或 `NOT_RUN`，不能折算成成功。

当前精确数字和未验证范围见 [CURRENT_QUALIFICATION.md](docs/qualification/CURRENT_QUALIFICATION.md)。
