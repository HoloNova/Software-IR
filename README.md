# KCG-Code / Software IR

KCG-Code 是面向 Coding Agent 的 Software IR（SIR）编译、确定性代码生成和安全工程变更工具链。

它让 Agent 或小模型先写出可检查的 SIR，再由确定性程序完成解析、名称绑定、类型检查、约束验证、目标降级（Lowering）、代码生成和工程变更。项目不允许模型绕过 SIR 直接决定最终 Java 工程结构。

## 它怎么工作

```text
SIR 源码 ──► 解析 ──► 语义检查 ──► Spring Boot 降级 ──► 确定性生成 ──► 工程文件
                                                              │
修改 = 固定的增加／删除／更新指令 ──► 新的 SIR ──► 同一条链重新编译 ──► 计划 ──► 应用
```

- **首次生成**：读入 SIR，生成 Spring Boot（Java 21 + MyBatis-Plus + MySQL + Maven + REST）工程，写入一个尚不存在的目录。
- **后续修改**：用增加、删除、更新三种固定指令编辑 SIR，工具重新编译，给出只读计划，再应用到工程。**当前版本就是磁盘上的 SIR，历史交给 Git**；系统自己不保存版本历史。
- **独立验证**：依赖下载、构建、启动和业务验证是独立环节（静态／构建／启动／业务四级），不属于解析器或静态校验。

SIR 只有一种文档形式：`sir 0.2` 根文档（含 `metadata`、`target`、`sources { }`、`imports { }`、`declarations { }`）加可选的声明片段文件。把一个软件写在一个文件里，就是没有片段的根文档。

## 仓库结构

九个 Maven 子模块：

| 模块 | 职责 |
| --- | --- |
| `sir-parser` | 语法、不可变 AST、源位置、解析诊断 |
| `sir-semantic` | 名称解析、类型、校验、规范化；软件结构（SoftwareSchema） |
| `sir-lowering-api` / `sir-lowering-spring-boot` | 目标无关的降级契约；Spring Boot 降级模型与校验 |
| `sir-generator-spring-boot` | 只读降级模型、输出文件集合的确定性生成 |
| `sir-project-graph` | 生成物的来源图（只读、可序列化） |
| `sir-change` | 增删改指令对 SIR 的编辑与写回 |
| `sir-toolchain-application` | 编排：编译、首次生成、基于 Git 的版本变更、独立验证 |
| `kcg-cli` | 命令行：`check`、`version-plan`、`version-apply` |

## 命令行

| 命令 | 作用 |
| --- | --- |
| `kcg check < samples.jsonl` | 批量静态校验 SIR 样本（stdin 为 JSONL），不建工程、不写文件 |
| `kcg version-plan …` | 只读：把当前 SIR 与候选 SIR 各编译一遍，给出计划和 `planId` |
| `kcg version-apply …` | 按计划应用；需要 `git`，应用前后各保存一次版本；中途停下时重跑同一命令 |

用法和退出码见 `kcg <command> --help`；`check` 的协议见 [SIR 批量校验](docs/SIR_BATCH_CHECK.md)。

## 构建

需要 Java 21 和准备好的 Maven 仓库（全新环境先在线预热一遍）：

```bash
mvn -Dmaven.repo.local=<repo> -o clean verify
```

全量构建与真实 MySQL 场景较重，权威验证放在 GitHub CI（`.github/workflows/verify.yml`），本机只跑受影响的定向测试；规则和证据口径见 [验证说明](docs/qualification/README.md)。被跳过或未运行的测试一律记为 `SKIPPED`／`NOT_RUN`，不折算成通过。

## 从哪里开始读

1. [项目状态](docs/PROJECT_STATUS.md)：现在能做什么、验证到什么程度、已知限制。
2. [工程规则 AGENTS.md](AGENTS.md) 与 [项目主体说明 MAIN.md](MAIN.md)。
3. [文档导航](docs/README.md)；[系统架构与实现指南](docs/KCG-Code_%E7%B3%BB%E7%BB%9F%E6%9E%B6%E6%9E%84%E4%B8%8E%E5%AE%9E%E7%8E%B0%E6%8C%87%E5%8D%97.md)讲每一层为什么存在。
4. 已完成或退役的计划、报告都在 [docs/history](docs/history/README.md)。

本项目不宣称生产就绪：Windows 平台证据、独立发行包、数据库迁移、Docker 交付、Web 平台等仍未交付，见项目状态第 6 节。
