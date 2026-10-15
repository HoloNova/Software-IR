# 公开CLI流程：实现与定向验证报告

- 日期：2026-10-14；状态：**AWAITING_ACCEPTANCE**。
- 授权：负责人确认[入口方案](design/09-public-cli-workflow.md)，要求进入整阶段实现；没有提交/推送授权。
- 起点：`ad64a39`（云端main）。本轮改动未提交，没有可绑定的新commit SHA或CI run URL。
- 使用方式：[CLI流程及JSON合同](CLI_WORKFLOW.md)。

## 1. 交付

公开入口现在可以串起：SIR → generate新工程 → inspect结构与基线 → edit新候选SIR → 原version-plan/apply预览/应用。

| 入口 | 实现与副作用 |
| --- | --- |
| generate | 调用原ToolchainApplication；只创建新输出根，不改SIR、不Git、不构建 |
| inspect | SourceEditingApplication读取显式源集合，复用正式语义编译；只读schema/draft、软件名及完整源摘要 |
| edit | 严格JSON映射原CommandBatch三个操作；调用原SourceEditor，成功后只发布完整候选源目录 |
| version-plan/apply | 合同未改；候选目录可以直接接入，两边完整编译、原Git快照与应用规则继续有效 |

新三命令结果协议`KCG-CLI-WORKFLOW-V1`；外部指令`KCG-CLI-COMMANDS-V1`包装核心`sir-command/1`。四种StructValue通用编解码，节点种类/槽位角色由SoftwareSchema提供，没有在CLI注册实体、查询或表达式的逐种类名单。readonly派生字段和身份不能由JSON补写。

### 代码位置

- `kcg-cli/.../CliCommandLine.java`、`KcgCli.java`：参数、调用与帮助；不做文件I/O。
- `kcg-cli/.../CommandJson.java`、`StrictJson.java`：有界UTF-8/JSON、固定三动作与通用值映射。
- `kcg-cli/.../WorkflowResultDocument.java`：新结果JSON、schema/draft模板和原拒绝报告。
- `sir-toolchain-application/.../api/SourceEditingApplication.java`及typed request/result：只读inspect与候选edit编排。
- `sir-toolchain-application/.../internal/ProjectSourceReader.java`：仅新增源根/entry重载，旧ToolchainRequest读取委托同一实现；原NOFOLLOW、显式清单和字节预算不改。
- `sir-toolchain-application/.../internal/SourceCandidatePublisher.java`：源字节暂存、原子发布、清理本次材料；不把SIR伪装成GeneratedFile。

Parser、Semantic模型、Change编辑内核、Lowering、Generator、Git版本规划/应用均未改；无新模块或运行依赖。CLI POM只更新退役context/plan描述。

## 2. 验收证据

真实CLI子进程完成多源课程组合：新增搜索输入 → 新增引用它的搜索能力 → 删除无引用错误声明 → 修改错误状态码 → 修改另一源文件里的课程字段约束。所有动作复用add/delete/update。edit时原源与工程逐字节不变，未改片段原字节保留；候选经原version-plan/apply应用后，源等于候选，生成工程等于候选从零生成。Git只在临时测试仓库里由version-apply调用，没有提交本开发仓库。

商品样例复用同一协议。拒绝路径覆盖陈旧基线、JSON未知/重复/空字段、伪造身份/源位置、未知schema节点、非法UTF-8/深度/大小、最终缺失引用、受保护注释、已有/重叠候选根、符号链接，以及发布失败或目的目录突然出现的清理。CLI边界测试验证不直接访问文件系统或内部包。

### 当次Surefire XML（各类最新结果）

| 类 | run / fail / error / skip |
| --- | --- |
| SourceEditingApplicationTest | 4 / 0 / 0 / 0 |
| SourceCandidatePublisherTest | 3 / 0 / 0 / 0 |
| CommandJsonTest | 4 / 0 / 0 / 0 |
| KcgWorkflowCommandsTest | 3 / 0 / 0 / 0 |
| CliProductBoundaryTest | 5 / 0 / 0 / 0 |
| CliProductionBoundaryTest | 3 / 0 / 0 / 0 |
| KcgVersionCommandsTest | 7 / 0 / 0 / 0 |
| **合计** | **29 / 0 / 0 / 0** |

报告来自`{sir-toolchain-application,kcg-cli}/target/surefire-reports/TEST-*.xml`中表列的7个类，不包含其他历史XML，不是全量测试，也不声称29项在一轮执行。

初轮样例用了不支持的422/410状态码，并把约束参数误写成Text；原语义/结构检查正确拒绝。仅修正测试为409与IntegerLiteral，不放宽生产规则。一次诊断轮对限定类使用`test.failure.ignore=true`收集各类结果；它的BUILD SUCCESS不算全绿证据。修正后仅复测变更/失败范围，最终SourceEditingApplicationTest、CommandJsonTest、KcgWorkflowCommandsTest的正常命令退出0；其余未受修正影响的通过结果不重复运行。

正常定向命令基准（首次运行选择表列7类；修正后只选3类）：

```bash
MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m' \
systemd-run --scope -q -p MemoryMax=2G -p CPUQuota=150% -- \
  mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl kcg-cli -am \
  -Dtest=SourceEditingApplicationTest,CommandJsonTest,KcgWorkflowCommandsTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m' test
```

机器实测2 vCPU、3875 MiB总内存、测试前2376 MiB可用；整棵进程树限制2 GiB/150% CPU，Maven及测试fork各384 MiB堆/192 MiB元空间，新流程CLI子进程各128 MiB堆/128 MiB元空间。串行、无Maven -T。依赖模块仅为编译接线而-am，不执行它们的非指定测试；不运行clean verify、MySQL业务IT或生成工程构建/启动。结束时没有遗留java/mvn/mysqld进程。

## 3. CI与未覆盖项

| 门 | 状态 |
| --- | --- |
| 本轮定向测试、真实CLI及临时Git流程 | PASSED（上表） |
| 全量冻结/完成闸门 | NOT_RUN |
| 真实MySQL业务/独立验证IT | NOT_RUN |
| 新commit SHA + CI URL/artifact | NOT_RUN：尚未授权提交/推送 |
| Windows、断电与并发竞争资格 | NOT_RUN；不声明支持扩大 |

云端verify #70绑定`4e17ca3`，只证明本轮之前的代码，不能替代新增入口验证。已有Git并发/多源中断限制不变，没有重做恢复或锁体系。

没有交付fat JAR、自然语言→指令、数据库初始化/迁移、Docker/Web、公开构建/启动命令；这些不属于本阶段。发布/验收状态和Git授权分开，等待负责人裁决，不自动进入下一阶段。
