---
created: 2026-07-16
updated: 2026-07-16
importance: medium
confidence: confirmed
source: document
status: active
---

# Bug 追踪

## 当前结论

截至第三轮最终复核，没有阻止进入 Lowering 设计的已知 Parser/Semantic 缺陷。119 项测试全部通过。

## 环境注意事项：Git 全局 ignore 读取警告

**发现日期：** 2026-07-16

**描述：** 在受限环境运行 `git status` 时出现 `unable to access C:\Users\zdw00/.config/git/ignore: Permission denied`，但命令成功返回仓库状态。

**影响范围：** 可能导致用户级全局 ignore 规则未生效；不表示仓库代码或 `.gitignore` 有缺陷。

**优先级：** low

**状态：** open（环境权限问题）

**处理方式：** 以仓库 `.gitignore` 和显式 `git status` 为准；不得借此提交 `.claude/`。只有在确需访问用户级 Git 配置时再请求权限。

## 尚未实现边界不是 Bug

Generator、可运行 Spring/MyBatis-Plus 工程、Redis、Change SIR、多文件 Symbol Graph、控制流与算术表达式均属于明确未实现范围，不应以“修 Bug”方式塞进现有 Pass。

## 环境注意事项：受限沙箱内 javac 类路径访问

**发现日期：** 2026-07-16

**描述：** 在受限沙箱内执行 `mvn clean verify` 时，javac 无法读取同一模块刚生成的 `target/classes`，导致既有 parser 测试报告自身 package 不存在；早先还出现过关闭 reactor JAR 时 `toRealPath` 的 AccessDeniedException。

**结论：** 沙箱外的 `mvn clean compile`、`mvn test` 和最终离线 `mvn "-Dmaven.repo.local=D:\maven-repo" -o clean verify` 均成功。Surefire 报告确认 135 项测试通过，0 failure、0 error、0 skipped；这不是源码或 Maven 模块依赖缺陷。

**状态：** monitored（只影响受限沙箱内构建，不阻塞工程验收）
