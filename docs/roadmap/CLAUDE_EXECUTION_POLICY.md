# Claude 执行规则（草案，持续补充）

- 日期：2026-10-13。状态：**草案**，由负责人口头确定要点，后续逐条补充。
- 适用对象：**执行者是 Claude 时**（Claude Code 云端会话）。负责人在其他设备上使用其他 Agent，那些 Agent 不适用本文件，按 `AGENTS.md` 或各自的规则执行。
- 规则跟着"执行者"走，不跟着"某台机器"走：只要执行者是 Claude，就遵循本文件；换了环境，第 2 节的环境事实要重新核对，其余规则不变。
- 与 `AGENTS.md` 的关系：`AGENTS.md` 的"测试资源约束"写的是小开发服务器（2 核 3 GB），主张重活交给 CI。负责人 2026-10-13 明确：**Claude 在自己的会话环境里直接本地测试；CI 主要为体量较小的开发服务器准备。** 我没有修改 `AGENTS.md`，是否同步由负责人决定。

## 1. 为什么需要这份文档

Claude 的会话容器里，**未提交的状态会被回收**。没提交、没推送的工作，容器一回收就丢。所以"及时测试、及时提交"不是习惯问题，而是保住工作成果的必要步骤。

## 2. Claude 会话环境的实际情况（2026-10-13 实测，换环境必须重新核对）

| 项目 | 情况 |
| --- | --- |
| CPU / 内存 | 4 核 / 约 16 GB（用 `nproc`、`free` 核对） |
| systemd | 没有，`systemd-run` 不可用 |
| Docker / MySQL | 没有可用的 Docker 守护进程，也没有 MySQL |
| 网络 | 经代理访问 Maven Central；`JAVA_TOOL_OPTIONS` 里带代理设置 |
| Maven 仓库 | `~/.m2/repository`，已预热（含两个生成工程的依赖）；容器回收后需重新预热 |

## 3. 规则（当前生效）

### 3.1 及时提交

1. 每完成一个"可验证的小单元"（改动 + 对应测试通过），立即提交并推送，不攒到阶段末。
2. 提交前至少做：`git diff --check`、`git status --short`、本单元的定向测试。
3. 提交范围只含本单元已审阅内容，不用全量 `git add` 混入别的东西。
4. 分支：负责人 2026-10-13 指定直接在 `main` 上工作；推送到 `main`。不 force-push，不 rebase，不 reset。
5. 署名：提交的作者（Author）写负责人本人，Claude 作为协助者写进信息末尾的 `Co-Authored-By`。做法是每次提交用 `git commit --author="HoloNova <holonova25@gmail.com>"`，不改全局或仓库的 git 配置；信息末尾保留 `Co-Authored-By` 与 `Claude-Session` 两行。负责人 2026-10-13 起要求，此前的提交不改写。
6. 授权边界：本条只覆盖"本单元测试通过后的提交与推送"。任何新阶段（例如 R2）仍需负责人单独授权，提交授权不自动开启下一阶段。

### 3.2 及时测试

1. 改一处，测一处：先跑受影响模块的定向测试（`-pl <模块> -am -Dtest=...`）。
2. 阶段收尾或改动跨多个模块时，跑冻结形式的完整闸门（第 4 节）。
3. 不做的事：不为"顺手再确认"重复跑已有同源码、同范围的有效结果。
4. 测试数字只引用当次 Surefire 输出；跳过项、未运行项如实写明。

### 3.3 资源限制

没有 systemd，所以改用：

```bash
export MAVEN_OPTS="-Xmx1g"
nice -n 10 taskset -c 0,1,2 mvn -B ...
```

- 单构建，不加 `-T` 并行；一次只跑一个重活，跑完确认没有遗留的 `mvn`/`java` 进程。
- 取值要给环境留余量；换环境后按 `nproc`/`free` 重新取值并写进当次报告。

### 3.4 汇报方式

- 向负责人说明时用简洁易懂的语言：先说结论，再说证据，最后说需要决定的事。
- 代码改动先说明"为什么存在"，让负责人能介入设计；设计优化允许有限调整，但必须事后说明。

## 4. 常用命令（已在 Claude 会话环境验证过）

**定向测试（单模块）**

```bash
mvn -B -o -pl sir-semantic -am test -Dtest='<测试类>' -Dsurefire.failIfNoSpecifiedTests=false
```

**预热本地仓库（联网，一次即可；失败被容忍）**

```bash
mvn -B clean verify -Dmaven.test.failure.ignore=true -Dmaven.repo.local=$HOME/.m2/repository
```

生成工程自己的依赖需要另行预热：用测试支持代码生成样例工程，在其目录里联网执行 `mvn compile`，把依赖拉进同一个仓库。

**冻结形式的完整闸门（离线）**

```bash
env -u JAVA_TOOL_OPTIONS nice -n 10 taskset -c 0,1,2 \
  mvn -B -o -Dmaven.repo.local=$HOME/.m2/repository clean verify
```

注意：

- 必须显式传 `-Dmaven.repo.local`，否则生成工程离线编译测试会失败。
- 必须取消沙箱注入的 `JAVA_TOOL_OPTIONS`，否则两个会启动独立 JVM 的确定性测试会被它污染而失败。
- 取消后不能联网，所以必须先预热。

## 5. 当前不能做的验证

| 验证 | 原因 | 处理 |
| --- | --- | --- |
| 5 个业务集成测试（Query、Write、Relation、ChangeLoop、ProjectWorkflow） | 当前 Claude 环境没有 Docker / MySQL | 报告里记 `NOT_RUN`，不得用别的结果冒充 |

## 6. 待补充

负责人后续补充的条目写在这里。例如：提交信息格式、阶段收尾的固定清单、会话开始时的恢复检查（`git status`、`git log`、读取 ACTIVE_WORK）、是否同步 `AGENTS.md`。
