# 交接文档（临时，任务完成后删除）

> **这是临时文件。** 下面"待办"全部完成、或负责人说不做了以后，用 `git rm -r docs/tmp` 删除整个目录，并单独提交一次（`docs: 移除交接文档`）。除本文件外没有任何文档链接到它，删除不会留下断链。
>
> 本文只写**还要做什么**和**怎么做**。已经做完的事情、每一轮的实施与 CI 经过，分别在[项目状态](../PROJECT_STATUS.md)、[选题试写报告](../qualification/TOPIC_TRIALS.md)和[验证说明](../qualification/README.md)里，这里不重复。

## 1. 先读什么

1. `AGENTS.md`（项目规范，**优先级最高**）：设计先向负责人讲清楚"为什么存在"并等到确认再写代码；不许假设；本机只跑受影响的定向测试，全量验证交给 CI；不为触发 CI 而自动推送；提交要干净（一个提交只做一件事，代码、测试、文档同一提交）。
2. `MAIN.md`（项目主体说明）、`docs/PROJECT_STATUS.md`（现状）。
3. `docs/qualification/TOPIC_TRIALS.md`：八个题目的需求逐条记录，第 4 节是缺口表，第 5 节是缺陷表。下面的待办都出自这里。
4. `docs/design/12-feature-semantics-and-semantic-types.md` 第 14 节以后：每一项语言能力的设计依据、替代方案和"没有做什么"。新设计照这个格式写。
5. `docs/SIR_LANGUAGE_REFERENCE.md`：语言参考，只讲语言本身，不写 CI 编号和验证状态。其中 `sir-declarations`／`sir-error` 代码块由 `LanguageReferenceExamplesTest` 逐个检查。

## 2. 工作方式

- 负责人用"确认"/"confirm"批准设计。流程固定：**先用普通话讲设计（为什么、替代方案、什么不做）→ 负责人确认 → 再写代码**。讲设计时按 AGENTS.md 的三问自检。
- 提交：负责人授权在 `main` 上提交并推送（会话提示里若写了别的开发分支，以负责人当次说法为准，拿不准就问）。用 `git commit --author="HoloNova <holonova25@gmail.com>"`，不改 git 配置；提交信息末尾写自己的 `Co-Authored-By`。提交前 `git diff --cached --check` 并读一遍暂存区。中文写文档和提交信息，用平实的话，少用内部代号。
- 定向测试模板（`-am` 不能省，否则会用 `~/.m2` 里过期的旧模块）：

  ```bash
  env -u JAVA_TOOL_OPTIONS MAVEN_OPTS=-Xmx1g nice -n 10 \
    mvn -B -q -o -Dmaven.repo.local=/root/.m2/repository -pl <模块> -am test \
    -Dtest='<测试类>' -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Xmx768m
  ```

  重活按 AGENTS.md 包在 `systemd-run --scope` 里；云端容器没有 systemd 时，先用 `nproc`／`free` 看资源，再用 `nice` 和 JVM 内存上限代替，一次只跑一个，不加 `-T`。
- 只改了语言参考，也要在本机单独跑一次 `LanguageReferenceExamplesTest`（纯文档推送不触发 CI）。
- 改语言的固定路径：`Sir.g4` → AST → `SirAstBuilder` → Resolve/Type/Validate/Normalize（`sir-semantic`）→ Lowering（`SpringBootModelLowerer`、`SpringBootIrValidation`）→ Generator（`WorkflowRenderer`、`ControllerRenderer`、`DeploymentEngine`）。涉及数据库结构的还有交付规划与迁移工具（`SchemaContract`、`SchemaEvolution`、`DeploymentTemplates` 里生成的 `Migrate.java`）。
- 验证一个语言改动要有四样：语义测试（反例用"替换一个片段、只应报这一条规则"的写法）、降级测试、生成代码的契约或运行时测试、**八个题目的离线编译**。需要真实数据库行为时，接到已有的业务 IT 里（优先组合已有应用，不新起一套），由 CI 的真实 MySQL 验证。

## 3. 待办（按建议顺序）

### 3.1 暂停加新语法，先做小模型评估（模型与方式需负责人决定）

最近语言新增了七种工作流步骤（`exists`、`count`、`sum`、`sum … by`、`month`、`each`、`load … for update`），每种都带不少写作规则。这个项目面向写代码的模型，规则越多越容易写错。建议继续扩语言之前，先用八个题目加双 Token 题做一次评估，看模型实际能写对多少、错在哪些规则上。评估计划草稿在 `docs/qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md`；用哪个模型、怎么判分，等负责人定。

### 3.2 按评估结果决定剩下的缺口（每项先设计、等确认）

试写报告里还写不出或带折扣的需求，集中在这几类：

- 列表与批量（G5 剩余）：请求里带列表（一个订单多个商品、购物车）、每周重复预约、批量删除。
- 跨行数量：书目显示可借册数。
- 日期差：逾期罚款 = 天数 × 费率（乘法已有，缺"两个时刻相差几天"）。`month` 目前只服务记账一个题目，设计日期差时可以一起考虑把日期能力做得更通用。
- 统计（G8 剩余）：平均值、多个分组键、补零组、图表与导出。
- 优惠券：先和负责人确认需求本身。

### 3.3 把"靠作者自觉"的并发约定做成编译器检查或提示（需设计）

下面几条现在只写在语言参考里，写错了编译器不报，只有真实并发时才出问题，模型写代码时尤其容易漏：

- 先锁父行：所有可能产生冲突记录的写法，都必须先 `load … for update` 同一行父行（语言参考 5.2）。
- 作者维护的计数：所有改变状态的写法都要更新计数；给已上线系统加计数列，要先回填（5.2 的借阅例子）。
- `persist` 顺序：新建一行并给它引用的行计数时，先 `persist` 被计数的行，否则 MySQL 会因外键读锁死锁（5.6）。

可以考虑的方向：编译器自动排序、发出警告，或者把常见组合做成一个写法。先讲清楚每个方向能防住什么、防不住什么。

### 3.4 小项（低优先）

- G12：每个题目要重复约 35 行账号样板（`Register`、`UserView`、`use DualTokenLogin`）。
- G13：写作磕绊。`fails` 必须按错误名排序；`order`、`item`、`from` 是关键字不能当名字。可以改成更友好的报错，或放宽排序要求。
- G14（不做）：文件上传、发邮件短信、定时任务、调外部服务、实时推送、前端。

## 4. 需要留意的坑

- 一个 `update` 若带守卫或"本字段 ± 值"，这一行**只能有这一个 `update`**（整个写成一条 SQL，只写绑定的列）。
- optional 引用的列名不带 `_id`（`Optional<Ref<X>>` → 列 `author`），必填引用改可选引用在迁移里是"先改名再放宽"。
- 修改测试夹具时，`fails` 声明要按错误名排序；`order`、`item`、`from` 不能当名字；`Optional<Text where ...>` 不是合法写法；`Serial default 0` 类型不匹配，用 `Optional<Count>` 等。
- **任何新写进 SQL 文本的表名、列名都要经过 `MySqlIdentifiers.render`**（降级模块的 `profile` 包），否则遇到 `condition`、`like`、`group` 这类保留字就是语法错误。`ReservedIdentifierRuntimeTest` 是写法示例。
- 单独跑某个模块不带 `-am` 会用 `~/.m2` 里旧的模块，报出看似语法的错误，不是代码错，加 `-am`。别的机器加了新依赖时，`-o` 离线会解析失败，去掉 `-o` 联网跑一次即可。
- 加了 `Binding`、`NormalizedEntity` 这类记录的新分量时，为兼容旧调用保留旧构造器（先例：`SpringBootWorkflow.Binding`、`AstEntityDecl`）。
- 新增语义节点种类或引用角色时，几个覆盖测试会失败并提醒你登记：`StructureViewCoverageTest`、`TypedReferenceSiteContractTest`、`SirDeclarationWriterRoundTripTest`、`ProjectVisibilityTest`，还有两个全角色夹具和测试里的 AST 遍历。
- 业务 IT 里组合了真实题目源码的，夹具文件要和组合函数的输出逐字一致（例如 `valid/g9-topic-conformance.sir` 对应 `G9TopicComposition.compose()`），改了题目要同步。
- `git diff --check` 失败（文件末尾空行）会让 `&&` 串起来的提交不发生，注意看输出。
- 部署样例每改一次，链的版本数和步骤都会变：同步改 `DeploymentPackageApplicationTest`、`.github/workflows/verify.yml` 里的 `schemaVersion` 和步骤列表、`.github/scripts/deployment-smoke.py` 里的 `applied=V7..V11` 和 `flyway_schema_history` 计数。

## 5. 完成之后

所有待办完成（或负责人明确不做）后：确认 CI 绿；`git rm -r docs/tmp`；单独提交 `docs: 移除交接文档`。
