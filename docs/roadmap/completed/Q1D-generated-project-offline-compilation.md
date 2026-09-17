# 已完成工作单：Q1D 完整生成工程冻结依赖下的真实离线编译

- 状态：`ACCEPTED`
- 所属阶段：路线图阶段 1 / Generator 系统测试
- 前置工作：[Q1C Generator 环境与字节确定性矩阵](Q1C-generator-determinism-matrix.md) 已完成
- 本工作单性质：测试优先；证明 Generator 输出能够由 Java 21 与冻结 Maven 依赖真实编译
- 下一阶段：Q1 总验收与远端大版本快照；通过并确认后才进入 Q2

## 这次只做什么

把 `campus-market.sir` 经现有 Parser、Semantic、Spring Boot Lowering 和纯内存 Generator 生成的完整文件集写入 JUnit 临时目录，再启动独立 Maven 子进程做真实离线编译：

1. 写出的路径和 UTF-8 字节必须逐项来自 `GeneratedFile`，不得手写一份“看起来一样”的示例工程；
2. 使用生成工程自己的 `pom.xml`，以 Java 21 执行 `compile`；
3. Maven 必须带 `--offline`，并显式复用父构建传入的本地仓库；
4. 子进程退出码必须为 0，且目标 `.class` 文件必须真实存在；
5. 失败信息必须保留完整、可诊断的 Maven stdout/stderr，但不得把临时工程提交进仓库；
6. 不启动 Spring Boot、不连接 MySQL、不执行 HTTP 或 conformance 场景。

这张工作单证明的是“生成源码可以在冻结依赖下编译”，不是运行时、数据库或发布资格。

## 开工时怎样加载上下文

任何情况下先读取本文件最新内容并运行 `git status --short`。

- 新对话、新 Agent 或上下文不完整：按 `AGENTS.md` 先读 `.memory/INDEX.md`、`.memory/CORE/PROJECT.md`，再读本工作单材料。
- 同一对话连续推进且权威材料未变化：不机械重读，只补读本轮首次涉及或变化的文件。
- 上下文压缩后：摘要足够则继续；拿不准时按新对话处理。

直接相关材料：

1. `docs/qualification/CURRENT_QUALIFICATION.md` 与 `TEST_COVERAGE_INVENTORY.md` 的 Generator 部分；
2. 已归档的 Q1A～Q1C 工作单；
3. `SpringBootGenerator.java`、`GeneratedFile.java`、`GenerationResult.java`；
4. `GeneratorTestSupport.java` 与 `valid/campus-market.sir`；
5. `PomRenderer.java` 和各 Java Renderer；
6. 根 `pom.xml` 中 Java/Maven 插件版本，以及生成 POM 冻结的 Spring Boot 3.5.3、MyBatis-Plus 3.5.12、MySQL Connector/J 9.3.0。

## 允许修改的范围

- `sir-generator-spring-boot/src/test/**`
- 被真实编译失败直接证明有缺陷的 Generator Renderer 或转义辅助类
- 与实际结果同步的：
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - 本工作单状态与交接记录

若修复需要改变 SIR 语义、Lowered IR、Application 写盘协议、依赖版本或公共 API，停止并报告，不得扩大范围。

## 明确不做

- 不通过 `ToolchainApplication` 写盘；测试只物化纯内存 Generator 的结果，避免重复验证事务层。
- 不运行 `test`、`verify`、Spring Boot 启动或数据库连接；生成工程只执行离线 `compile`。
- 不下载依赖、不修改生成 POM 的版本、不把依赖复制进仓库。
- 不新增 Maven profile、Surefire skip/exclude 或默认关闭该测试的开关。
- 不提交 `target/`、临时工程、本地 Maven 仓库或构建日志。
- 不进入 Project Graph、Change、conformance、事务应用层或 CLI。

## 测试优先协议

1. 先新增最小的生成工程离线编译测试，并在不改生产代码时执行。
2. Maven 可执行文件定位、命令行拼接、临时目录或本地仓库发现错误属于测试基础设施错误，先修正测试，不算产品 RED。
3. 只有子进程真正进入生成工程编译，并由具体生成源码或 POM 导致失败，才允许修改生产代码。
4. 每个生产修复必须能指向首次失败日志中的具体文件和编译诊断。
5. 不减少、禁用或放宽 Q1A～Q1C 已有测试。

## 必须建立的契约

### 1. 真实物化

- 对 `GeneratorTestSupport.generateSuccess("valid/campus-market.sir")` 返回的每个 `GeneratedFile`：
  - 以 `relativePath` 解析到 JUnit 临时根目录；
  - 创建父目录；
  - 使用显式 UTF-8 写入 `content`；
  - 写入后按 UTF-8 读回并与原内容相等。
- 不允许用测试资源中的预生成 Java/POM 替代 Generator 输出。

### 2. 冻结离线 Maven

- Maven 可执行文件优先从当前 Maven 进程的 home 定位，并提供受控的 PATH 后备查找；Windows 使用 `mvn.cmd`，其他平台使用 `mvn`。
- 本地仓库来自父构建显式传入的 `maven.repo.local`；缺失或目录不存在时明确失败，不静默改用联网仓库。
- 子进程命令至少包含：`--offline`、显式 `-Dmaven.repo.local=...`、`-DskipTests`、`compile`。
- 子进程工作目录必须是物化后的生成工程根目录。

### 3. 编译结果

- 退出码为 0。
- 至少验证生成 Application、一个 Entity、一个 DTO、一个 Mapper、一个 Service 和一个 Controller 对应的 `.class` 存在。
- 错误消息包含命令、工作目录、退出码与完整合并输出，便于新 Agent 直接定位。
- 子进程设置合理超时；超时必须强制结束并以测试失败报告。

## 推荐测试组织

- 新增 `GeneratedProjectOfflineCompilationTest`：负责物化、启动 Maven、验证退出码和代表性 class 文件。
- 测试辅助方法留在测试类或 test-only support 中，不增加生产 API。
- 复用 `campus-market.sir`，不新增第二份 canonical 工程。

## 验证命令

定向：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o -pl sir-generator-spring-boot -am -Dtest=GeneratedProjectOfflineCompilationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

完成闸：

```powershell
mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify
git diff --check
git status --short
```

## 完成闸

- 生成工程确由当前 Generator 输出物化，而非手写 fixture。
- 独立 Maven 子进程在 `--offline` 与显式冻结本地仓库下完成 Java 21 `compile`。
- 代表性 `.class` 文件真实存在。
- 若有生产修复，每项都由首次真实编译失败直接证明。
- Q1A～Q1C 与全量离线 reactor 继续通过；新增 skip/exclude 为 0。
- 资格文档与最新 Surefire XML 一致，并明确运行时/MySQL/conformance 仍未执行。
- 状态更新为 `AWAITING_ACCEPTANCE`；聊天中无阻断时只请求项目负责人确认继续。
- 项目负责人确认后，归档并创建 Q1D 本地 commit；随后进行 Q1 总验收。Q1 整体确认后才普通推送 `origin`。

## 交接记录

2026-08-11：项目负责人确认 Q1C 通过。Q1C 归档后，本工作单以 `READY` 建立；等待 Q1C 本地 Git 快照完成后切换为 `IN_PROGRESS`。

2026-08-11：Q1C 本地快照已创建为 `93cbf14`，`origin/main` 仍保持 `55fc610`，没有推送远端。Q1D 切换为 `IN_PROGRESS`，开始建立完整生成工程真实离线编译证据。

2026-08-11：Q1D 已完成，等待项目负责人验收。

- 新增 `GeneratedProjectOfflineCompilationTest` 1 项：直接物化当前 `campus-market.sir` 的 11 个 Generator 输出文件，显式 UTF-8 写入并逐项读回一致。
- 独立 Maven 子进程使用生成的 `pom.xml`，以 Java 21、`--offline`、显式 `D:\maven-repo`、`-DskipTests compile` 运行；退出码为 0。
- Application、User Entity、PublishGoodsInput DTO、UserMapper、PublishGoodsService 和 PublishGoodsController 的 `.class` 文件全部真实存在。
- 首轮沙箱内定向命令在父 Reactor testCompile 前因 javac 无法读取自身 `target/classes` 并输出 `Access is denied` 而失败，测试未启动、也未到达生成工程，不计为产品 RED。以相同源码在允许 Maven 正常读取构建目录的干净 Reactor 中重跑后，1 run、0 failure、0 error、0 skip，BUILD SUCCESS。
- 测试首次真正进入生成工程编译即通过，没有修改任何生产 Renderer、POM、Lowered IR、依赖版本或公共 API。
- 全量 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify`：十模块 BUILD SUCCESS；Generator 32 run、0 failure、0 error、0 skip；Surefire XML 合计 373 run、0 failure、0 error、10 skip。
- 10 项 skip 仍是 Change fixtures 缺失的 6 项和 Windows `PathGuard` 条件限制的 4 项；没有新增 skip/exclude。运行时、MySQL、HTTP 和 conformance 均未执行。
- `CURRENT_QUALIFICATION.md` 与 `TEST_COVERAGE_INVENTORY.md` 已同步。Q1D 尚未创建本地快照；按小版本规则，等待项目负责人确认后归档并提交，且不推送远端。

2026-08-11：项目负责人确认 Q1D 通过；工作单归档，进入本地快照流程，不推送远端。
