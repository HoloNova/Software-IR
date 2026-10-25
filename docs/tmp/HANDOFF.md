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

## 3. 待办（按顺序，已核对 `0ac9739` 的 #183，验收器修复待新同源码 CI）

缺口清单（G5、优惠券、G8、G12、G13、3.4 并发约定的编译器检查）都已实现并推送，唯一剩下的是**等 CI 验收并收尾**。不要再开新功能，也**不要开始小模型评估**（评估要等负责人另行确定模型、样本和评分方式）。

### 3.1 看 CI，失败就修

已快进拉取云端 `fc60661`（业务源码 `0ac9739`）。#183 的 Frozen 全量门已通过；逐份复算281个XML／1960项，唯一失败是 BusinessSliceHarness 要求诊断完全为空，把 BookAfterRead 的合法 SIR-WARN-001 当错误。乘法／商城182项检查通过、1项诊断断言失败；Money326、library299、course151全部通过，原始资源哈希与当前源码一致。#182的优惠券错误已由858ffd7修复，#183实际执行的重复用券错误码、取消退券及八人抢三次均通过。证据见[验证49](../qualification/README.md#ci-183-compilation-warning-fix)。

本轮只修验收器的错误／警告区分，不删警告、不删先读再锁后重查的夹具、不改生产代码。新编译回归先取得同型RED，最终9类65项定向通过。独立验证／CLI／部署在#183均跳过，仍待修复源码的完整CI；不能把业务检查通过写成整轮验收通过。

- 同一次 CI 里还有第一次真库验证的内容：每周重复预约（`IntervalBookingChecks.weeklySeries`）、平均值（Money IT）、优惠券（`ShopCouponChecks`）。哪一项红，就按失败日志修；这些检查都在 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/` 下。
- 先核原始Surefire XML中的失败／错误，再看对应业务报告和应用日志；不要只从日志尾部猜失败范围。
- 当前 verify 为 `cancel-in-progress: false`；修复同批一次推送，不重复触发或轮询。结果由负责人通知。

### 3.2 CI 绿之后的文档收尾

完整CI通过后，按新同SHA报告把多商品下单、一个订单多个商品、购物车、优惠券、每周重复预约的"△／待 CI"改成"✓"，逐项复算数字（当前仍78／6／0）。记账的合并需求“图表数据、导出”即使avg验收通过，导出文件仍属不做的G14，不能因此整条变✓；保留折扣和具体原因。涉及的文件：`docs/qualification/TOPIC_TRIALS.md`、`docs/PROJECT_STATUS.md`、`docs/design/12-feature-semantics-and-semantic-types.md` 第31～37节的验收状态。纯文档提交不触发CI。

### 3.3 删除本目录

文档收尾提交之后：`git rm -r docs/tmp`，单独提交 `docs: 移除交接文档`。

### 3.4 已知没做、有意不做的

- 多分组键和补零组、导出（G14）：理由写在设计 12 第 34.3 节。
- 文件上传、发邮件短信、定时任务、调外部服务、实时推送、前端：不在范围内。

## 4. 需要留意的坑

- 一个 `update` 若带守卫或"本字段 ± 值"，这一行**只能有这一个 `update`**（整个写成一条 SQL，只写绑定的列）。
- optional 引用的列名不带 `_id`（`Optional<Ref<X>>` → 列 `author`），必填引用改可选引用在迁移里是"先改名再放宽"。
- 修改测试夹具时，`fails` 声明要按错误名排序；`order`、`item`、`from` 不能当名字；`Optional<Text where ...>` 不是合法写法；`Serial default 0` 类型不匹配，用 `Optional<Count>` 等。
- **任何新写进 SQL 文本的表名、列名都要经过 `MySqlIdentifiers.render`**（降级模块的 `profile` 包），否则遇到 `condition`、`like`、`group` 这类保留字就是语法错误。`ReservedIdentifierRuntimeTest` 是写法示例。
- 单独跑某个模块不带 `-am` 会用 `~/.m2` 里旧的模块，报出看似语法的错误，不是代码错，加 `-am`。别的机器加了新依赖时，`-o` 离线会解析失败，去掉 `-o` 联网跑一次即可。
- 加了 `Binding`、`NormalizedEntity` 这类记录的新分量时，为兼容旧调用保留旧构造器（先例：`SpringBootWorkflow.Binding`、`AstEntityDecl`）。
- 新增sealed产物种类还须补图谱工厂角色／路径和枚举消费者；增量编译可能复用旧class，不能只凭测试绿判断穷尽switch已重编。优先为新增路径补无目录导出的图谱契约，再定向强制重编受影响模块，完整门仍留CI。
- 新增语义节点种类或引用角色时，几个覆盖测试会失败并提醒你登记：`StructureViewCoverageTest`、`TypedReferenceSiteContractTest`、`SirDeclarationWriterRoundTripTest`、`ProjectVisibilityTest`，还有两个全角色夹具和测试里的 AST 遍历。
- 业务 IT 里组合了真实题目源码的，夹具文件要和组合函数的输出逐字一致（例如 `valid/g9-topic-conformance.sir` 对应 `G9TopicComposition.compose()`），改了题目要同步。
- `git diff --check` 失败（文件末尾空行）会让 `&&` 串起来的提交不发生，注意看输出。
- 部署样例每改一次，链的版本数和步骤都会变：同步改 `DeploymentPackageApplicationTest`、`.github/workflows/verify.yml` 里的 `schemaVersion` 和步骤列表、`.github/scripts/deployment-smoke.py` 里的 `applied=V7..V11` 和 `flyway_schema_history` 计数。

## 5. 完成之后

所有待办完成（或负责人明确不做）后：确认 CI 绿；`git rm -r docs/tmp`；单独提交 `docs: 移除交接文档`。
