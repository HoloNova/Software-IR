# 交接文档（临时，任务完成后删除）

> **这是临时文件。** 下面"待办"全部完成、或负责人说不做了以后，用 `git rm -r docs/tmp` 删除整个目录，并单独提交一次（`docs: 移除交接文档`）。除本文件外没有任何文档链接到它，删除不会留下断链。
>
> 写于 2026-10-20，接手前一个 Agent（Claude）额度用尽。截至本文，`main` 在提交 `c049acd`，CI（`verify`，含真实 MySQL 部署冒烟）已由负责人确认通过。

## 1. 先读什么

1. `AGENTS.md`（项目规范，**优先级最高**）：设计先向负责人讲清楚"为什么存在"并等到确认再写代码；不许假设；本机资源很小，只跑受影响的定向测试，全量验证交给 CI；不为触发 CI 而自动推送；提交要干净（一个提交只做一件事，代码、测试、文档同一提交）。
2. `MAIN.md`（项目主体说明）、`docs/PROJECT_STATUS.md`（现状）。
3. `docs/qualification/TOPIC_TRIALS.md`（选题试写报告，**下面的待办几乎都出自它的第 4 节缺口表**）。
4. `docs/design/12-feature-semantics-and-semantic-types.md` 第 14、15、16 节（最近做的三件事：迁移放宽与删除、联合唯一与唯一冲突、原子计数）。看它们是怎么写设计、怎么写"没有做什么"的，新设计照这个格式写。
5. `docs/SIR_LANGUAGE_REFERENCE.md`：语言参考。**其中带 ```sir-declarations 的代码块由 `LanguageReferenceExamplesTest` 检查**，改语言就要同步改这里，示例必须能通过。

## 2. 工作方式（负责人的习惯，上一个 Agent 一直照此做）

- 负责人用"确认"/"confirm"批准设计。流程固定：**先用普通话讲设计（为什么、替代方案、什么不做）→ 负责人确认 → 再写代码**。不要直接动手改核心语义。
- 讲设计时按 AGENTS.md 的三问自检：新业务是否主要靠组合已有能力？一个普通需求在 SIR 里是否变得绕口？修一种实现问题，能否让一类业务共同受益？
- 提交：负责人已授权在 `main` 上提交并推送（本项目一直如此；会话提示里若写了别的开发分支，以负责人当次说法为准，拿不准就问）。用 `git commit --author="HoloNova <holonova25@gmail.com>"`，不改 git 配置；提交信息末尾写你自己的 `Co-Authored-By`。提交前 `git diff --cached --check` 并读一遍暂存区。
- 中文写文档和提交信息，风格参考 `git log` 最近几条。
- 本机 2~4 核、内存紧：只跑定向测试，模板如下（`-am` 不能省，否则会用 `~/.m2` 里过期的旧模块）：

  ```bash
  env -u JAVA_TOOL_OPTIONS MAVEN_OPTS=-Xmx1g nice -n 10 \
    mvn -B -q -o -Dmaven.repo.local=/root/.m2/repository -pl <模块> -am test \
    -Dtest='<测试类>' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx768m
  ```

  重活（多个模块整体测试）要照 AGENTS.md 用 `systemd-run --scope -p MemoryMax=2G -p CPUQuota=150%` 包起来，一次只跑一个。`sir-generator-spring-boot` 整个模块测试、`TopicSamples*`（八个题目离线 Maven 编译，约 30 秒）本机跑过没问题。
- 改语言的**固定路径**（每一处都有先例可抄，最近的例子是提交 `16c2795`、`c64f938`）：`Sir.g4` → AST → `SirAstBuilder` → Resolve/Type/Validate/Normalize（`sir-semantic`）→ Lowering（`SpringBootModelLowerer`、`SpringBootIrValidation`）→ Generator（`WorkflowRenderer`、`ControllerRenderer`、`DeploymentEngine`）。涉及数据库结构的还有交付规划与迁移工具（`SchemaContract`、`SchemaEvolution`、`DeploymentTemplates` 里生成的 `Migrate.java`）。
- 验证一个语言改动要有四样：语义测试（反例用"替换一个片段、只应报这一条规则"的写法，参考 `GuardedUpdateSemanticsTest`）、降级测试、生成代码的契约测试、**八个题目的离线编译**（语义检查通过却生成编不过的 Java，之前出过四次，D1 到 D4）。需要真实数据库行为时，加到 `.github/scripts/deployment-smoke.py`，由 CI 的真实 MySQL 验证。

## 3. 已经做完的（不要重做）

迁移放宽类型与删表删列（`--confirm-backup yes`）、`kcg scaffold` 删除、八个题目试写、D1 到 D4 缺陷、唯一冲突报错（`persist x else 错误`）、联合唯一 `unique (a, b);`、**原子计数**（`字段: 行.字段 + 值`，一条 SQL；守卫右边可以是同一行的另一列）。对应试写报告里的 G1、G3 已标"已补"。

## 3.1 历史 CI 阻塞（已由 #140 闭环）

最新核验：`484a8a792c3ec6a7b9f5a7b7171b009e981a8ce3` 的 [#140](https://github.com/HoloNova/Software-IR/actions/runs/39527408701) 两个 job 和全部实际门通过，报告产物齐备。以下文字保留失败经过，不再作为当前阻塞；G10 的后续源码需新 CI，不能沿用 #140。

负责人要求检查后已核验：#136（33f960b）全量门通过、部署 HTTP 失败，根因为 DTO Optional 被按 nullable 标量直接比较并传入 JDBC；#137（00e3ce9）应用层 5 项失败、部署跳过，根因为旧非法夹具已被 G4 合法化，以及 SCALAR_QUERY_ENTITY 缺少跨文件实例。负责人随后授权修复，本提交已增加明确的 OptionalPayloadValue 取值计划、真实生成 DTO 回归、有效非法夹具和跨文件正反例；58 项定向通过。原文件导出集成、真库、全量门与同源码 CI 尚待验证，未放宽 Windows 安全读源边界。详见验证说明第 8 节。负责人随后明确授权提交并推送本轮修复、继续工作；可继续 G9 代码核对与方案，不把本轮 CI 提前记为通过，也不把 192 项旧定向结果当成 CI 通过。G9 方案此后已获负责人确认并实现，结果见 4.4；#138 的后续失败与本轮冒烟断言修复见验证记录第 9 节。

## 4. 待办（按建议顺序）

当前试写数字（G2/G4 后）：84 条需求，64 条（76%）完整写出，5 条（6%）带折扣，15 条（18%）尚未写出。每补一个缺口，都要更新 `TOPIC_TRIALS.md` 的表和数字、`PROJECT_STATUS.md` 里那一句、语言参考、设计 12 新增一节。

### 4.1 G7 可选值比较与空值判断（已确认并实现，后续 #140 回归通过）

负责人已确认设计：`is none`／`is present` 判断空值；普通比较任一侧为空均为 false（包括 `!=`），`not (==)` 是普通取反；可选筛选以 `input.x is none or item.x == input.x` 显式表达；PATCH `.present` 保持原义。实现与限制见设计 12 第 17 节，不要重开设计或改为空值自动跳过。

tasks、market 已更新；本机语义、降级、生成真值表、源往返与题目内存生成通过。部署真库冒烟已补，八题离线构建与 MySQL／HTTP 留给 CI。Windows 部署导出测试被现有 SecureDirectoryStream 边界阻断，不放宽该安全规则。负责人随后已授权本批 G7 提交并推送、不等待 CI；当时没有核验新 CI；后续已证实 #136 部署失败，见 3.1。Git 授权仍以负责人当次明确指令为准。

### 4.2 G2 对多行的存在与计数（原语已实现，业务组合未全补）

负责人已确认并实施设计 12 第 18 节：`exists Entity where ... as flag`／`count Entity where ... as total`，结果为 Boolean／Int64，每步一条 SQL，scope 与空值规则不变。exists 只读一个主键并转 Boolean，count 用 SQL COUNT，不把读整表或 Java 集合大小当计数。任务成员授权已用上；借阅限额、排队、课程关联与任意时段并发约束不能因新增读取原语就记为完成，需另行补业务组合。本机所选 25 类 192 项通过（含 G7、源写回、结构／引用与 Mapper 不变性），真库及生成工程构建待 CI；负责人随后已明确授权本批提交并推送、不等待 CI，推送时未核验结果，后续已证实 #137 全量门失败，见 3.1；后续 Git 操作仍以当次授权为准。

### 4.3 G4 按请求里的值穿过关联过滤（已实现，后续 #140 回归通过）

已用 `ExistsArgument.Value` 把外部字段、actor 和已加载值保留为类型化表达式并参数绑定，博客样例恢复 PostTag。不会放开缺少根行关联的 any 或嵌套 any。部署 v3 冒烟已补文本注入、scope、可选参数及账号隔离断言，V1..V11 不变；Windows 没有启动业务服务。后续 G9 实施结果见 4.4；本轮不自动扩展到 G10。

### 4.4 G9 Crud（已实现，后续 #140 验收通过）

负责人确认[设计 12 第 19 节](../design/12-feature-semantics-and-semantic-types.md#g9-crud)后已在工作区贯通 text／names、字段集合选择、正式参数 binding、精确 rename／源回写及 Crud 的 writer、writeRole、roleField、writeExclude、read: public 和多段路径。旧 use 默认行为不变。library Book 只允许馆员写；market Item 从输入与赋值排除 status／createdAt，保留手写的在售浏览。此排除不是并发锁，普通非版本持久化并未重定义。

89 项定向测试通过，真实 CRLF、旧手写展开等价、跨文件正反例、角色与字段错误均有覆盖。真库 `CrudAccessBusinessConformanceIT` 已复用既有 harness 并接入 verify.yml，尚未执行；不增加八题完成数，不把本机报告当作全量 CI。#138（ee6ddeb）的全量门通过，部署因 `/favorites/raise` 更新成功被脚本误期待 201 而失败；本轮修为 200 并补控制器契约断言。详见[验证记录第 9 节](../qualification/README.md#g9-crud-local)。随后已提交推送 f82cb94；#139 双全量门通过，新增真库 IT 因错误地用夹带排除字段的请求测试正常写入／权限而失败。修复只分开测试请求与断言，13 项相关定向通过，待新 CI 复验；见[验证记录第 10 节](../qualification/README.md#ci-139-fixture-fix)。阶段完成按负责人授权直接 commit + push 触发 CI。

### 4.5 G10 Money（已确认并实现，待本批 CI）

已按[设计 12 第 20 节](../design/12-feature-semantics-and-semantic-types.md#g10-money-proposal)实现默认 precision(19,2)、显式覆写、精确校验与 DECIMAL 物理合同；接受无损尾随零、不隐式舍入。原子金额仍基于数据库当前值加减；实际事务连接必须严格 SQL mode，最终溢出回滚。PATCH 精度来自实体，未放宽其他业务约束禁令；语义类型继续全项目可见，实体仍须 import。

15 类最新定向报告共 105 项通过，market／shop／ledger 已改 Money，八题内存生成／交付规划通过。金额 MySQL／HTTP、非严格模式与并发、八题生成工程编译和独立部署已接 CI，本机均未执行；不重定义普通非版本持久化的全行／NULL 策略。详见[验证记录](../qualification/README.md#g10-money-local)。下一项先评审 G6／G11，不擅自开始循环与聚合。

### 4.6 G6 乘法、G11 视图计算字段

- G6：只有 `+`、`-`；金额 = 单价 × 数量、罚款 = 天数 × 费率写不出。放在 G10 之后更有意义。
- G11：视图字段必须是实体字段，`剩余名额 = 容量 − 已选` 写不出（`SIR-SYMBOL-002`）。

### 4.7 G5 列表循环、G8 聚合（两个大块，**先和负责人确认要不要做**）

- G5：请求里带列表、工作流逐项处理；对一批行统一改或删（订单行、级联删除）。商城的"一个订单多个商品"、取消订单退库存都卡在这里。
- G8：求和、平均、分组。记账的核心，毕设里统计页很常见；普通条件行计数已由 G2 提供，不重做。
- 两者都会让语言明显变复杂。上一个 Agent 的建议：等 G7、G2、G4、G9、G10 补完，看还剩多少需求写不下，再决定。

### 4.8 G12、G13 小项（低优先）

- G12：每个题目要重复约 35 行账号样板（`Register`、`UserView`、`use DualTokenLogin`）。
- G13：写作磕绊——`fails` 必须按错误名排序，`order`、`item`、`from` 是关键字不能当名字。可以改成更友好的报错，或放宽排序要求。

### 4.9 其它

- **SLM（小模型）评估**还没开始，用哪个模型、怎么评估没有决定，等负责人说。
- **锁顺序**：新建一行并给它引用的行计数时，必须先 `persist` 被计数的行，否则真实 MySQL 并发会死锁（外键检查的读锁）。现在只写在语言参考里，没有做成编译器规则。如果以后觉得常有人踩，可以考虑自动排序或给提示，需要设计。
- **已知的 G14**（不做）：文件上传、发邮件短信、定时任务、调外部服务、实时推送、前端。

## 5. 需要留意的坑（上一个 Agent 踩过的）

- 一个 `update` 若带守卫或"本字段 ± 值"，这一行**只能有这一个 `update`**（整个写成一条 SQL，只写绑定的列）。
- optional 引用的列名不带 `_id`（`Optional<Ref<X>>` → 列 `author`），必填引用改可选引用在迁移里是"先改名再放宽"。
- 修改测试夹具时，`fails` 声明要按错误名排序；`order`、`item`、`from` 不能当名字；`Optional<Text where ...>` 不是合法写法；`Serial default 0` 类型不匹配，用 `Optional<Count>` 等。
- 单独跑 `-pl sir-generator-spring-boot test` 不带 `-am` 会用 `~/.m2` 里旧的 parser，报 "不属于 SIR v0.1 的语法：default"——不是代码错，加 `-am`。
- 加了 `Binding`、`NormalizedEntity` 这类记录的新分量时，为兼容旧调用保留旧构造器（先例：`SpringBootWorkflow.Binding`、`AstEntityDecl`）。
- 新增语义节点种类或引用角色时，几个覆盖测试会失败并提醒你登记：`StructureViewCoverageTest`、`TypedReferenceSiteContractTest`、`SirDeclarationWriterRoundTripTest`、`ProjectVisibilityTest`。
- `git diff --check` 失败（文件末尾空行）会让 `&&` 串起来的提交不发生，注意看输出。
- 部署样例每改一次，链的版本数和步骤都会变：同步改 `DeploymentPackageApplicationTest`、`.github/workflows/verify.yml` 里的 `schemaVersion` 和步骤列表、`.github/scripts/deployment-smoke.py` 里的 `applied=V7..V11` 和 `flyway_schema_history` 计数（现在是 11）。

## 6. 完成之后

所有待办完成（或负责人明确不做）后：确认 CI 绿；`git rm -r docs/tmp`；单独提交 `docs: 移除交接文档`。
