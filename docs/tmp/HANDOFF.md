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

## 4. 待办（按建议顺序）

当前试写数字：84 条需求，58 条（69%）完整写出，6 条（7%）带折扣，20 条（24%）写不出。每补一个缺口，都要更新 `TOPIC_TRIALS.md` 的表和数字、`PROJECT_STATUS.md` 里那一句、语言参考、设计 12 新增一节。

### 4.1 G7 可选值的比较与空值判断（最高优先，**必须先设计、等确认**）

现状：可选字段不能和值比较（`item.assignee == actor`、`dueAt < now()` 报 `SIR-TYPE-001`）；没有"是否为空"的判断（`.present` 只能用在局部更新的输入上）；可选的请求参数当筛选条件会被 `SIR-TYPE-001` 拒绝（D3 修复时先拒绝了，留待这里统一设计）。后果：任务看板只能把指派人、到期时间做成必填；二手市场做不了"按可选条件筛选"。挡住的需求条数最多（tasks、market）。

要负责人拍板的核心问题——**"值缺失时条件怎么算"**，上一个 Agent 倾向的方案（仅供参考，仍须确认）：
1. 加一个空值判断，比如 `item.assignee is none` / `item.assignee is present`（同时用于 `find` 谓词和 `validate`），数据库侧生成 `IS NULL` / `IS NOT NULL`；
2. 可选值与值比较：在 `find` 谓词里按 SQL 的习惯，缺失时该行不匹配（`NULL = x` 为假），语义要写明；
3. "可选的筛选条件"（请求没传就不加这个条件）是另一件事，建议单独一个写法（例如 `where item.kind == input.kind when input.kind is present`，或"请求里可选的值缺失即跳过该比较"），**不要混进第 2 点**；
4. 工作流里（非 `find`）对可选值的比较，缺失时是直接报错还是为假，也要定。

涉及：`TypePass`（`inFindPredicate` 标志与 D3 的拒绝在这里）、`WorkflowRenderer.renderPredicateNode`（谓词渲染）、题目 `tasks`、`market`。

### 4.2 G2 对多行的存在与计数

工作流里问不了"有没有这样的行""有几行"：`any(...)` 只能出现在 `find` 的谓词里，`find` 的结果没有成员可用（`rows.empty`、`rows.count` 报错）；`validate any(...)` 是 `SIR-FLOW-005`。挡住 library（每人最多借 5 本、预约）、course（时间冲突、先修课）、tasks、booking（时间段重叠）、shop 的需求。方向：给工作流一个"存在 / 计数"的条件，生成一条 `EXISTS` / `COUNT` 查询；先看 `any(...)` 现在的生成方式（`SpringExpression.ExistsPredicate`），尽量复用。要设计、等确认。

### 4.3 G4 按请求里的值穿过关联过滤

"带标签 X 的文章"需要 `any(PostTag, post == item and tag == 请求里的值)`；语义检查放过，生成时被拒绝（`SIR-LOWER-FEATURE-001`，"目标不能表达存在条件"）。和 G2 共用存在查询的机制，建议一起看。

### 4.4 G9 Crud 不够用

内置 `use Crud` 的参数只有 `entity`、`owner`、`path`：不能按角色限制、不能排除字段（卖家能把商品直接改成已售出）、路径只能一段、没有"公开读、本人写"。需要设计参数。注意 Crud 会把实体的**所有**字段放进更新输入，所以实体上凡是"只能由系统改"的字段（计数器、状态）都会被客户端改到——这也是上一个 Agent 没把计数器放到 `Note` 上、改放 `User` 的原因（见 `dual-token-v3` 样例）。

### 4.5 G10 `Money` 交付不了

十进制字段在交付规划里报"Decimal 需要明确的精度"，题目只好用整数的分。需要决定 `Money` 的默认精度或让作者声明。原子计数目前只支持整数，`Money` 的加减要等它。

### 4.6 G6 乘法、G11 视图计算字段

- G6：只有 `+`、`-`；金额 = 单价 × 数量、罚款 = 天数 × 费率写不出。放在 G10 之后更有意义。
- G11：视图字段必须是实体字段，`剩余名额 = 容量 − 已选` 写不出（`SIR-SYMBOL-002`）。

### 4.7 G5 列表循环、G8 聚合（两个大块，**先和负责人确认要不要做**）

- G5：请求里带列表、工作流逐项处理；对一批行统一改或删（订单行、级联删除）。商城的"一个订单多个商品"、取消订单退库存都卡在这里。
- G8：求和、计数、平均、分组。记账的核心，毕设里统计页很常见。
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
