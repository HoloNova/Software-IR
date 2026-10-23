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

当前试写数字：84条需求，67条（80%）完整写出，5条（6%）带折扣，12条（14%）尚未写出。#154 已把 booking 重叠由△转✓；本批 library 限额只从✗转待真库△，不提前转✓。每补一个缺口，都要更新 `TOPIC_TRIALS.md` 的表和数字、`PROJECT_STATUS.md` 里那一句、语言参考、设计 12 新增一节。

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

### 4.5 G10 Money（#147 已验收，以下保留实施经过）

已按[设计 12 第 20 节](../design/12-feature-semantics-and-semantic-types.md#g10-money-proposal)实现默认 precision(19,2)、显式覆写、精确校验与 DECIMAL 物理合同；接受无损尾随零、不隐式舍入。原子金额仍基于数据库当前值加减；实际事务连接必须严格 SQL mode，最终溢出回滚。PATCH 精度来自实体，未放宽其他业务约束禁令；语义类型继续全项目可见，实体仍须 import。

15 类最新定向报告共 105 项通过，market／shop／ledger 已改 Money，八题内存生成／交付规划通过。金额 MySQL／HTTP、非严格模式与并发、八题生成工程编译和独立部署已接 CI，本机均未执行；不重定义普通非版本持久化的全行／NULL 策略。详见[验证记录](../qualification/README.md#g10-money-local)。随后 `a54c351` 的 #141 因 DecimalPrecision 辅助 record 误入模型包，以及 DTO 旧注解断言失配失败；后续真库和部署被跳过。本轮移到 feature 包并补齐完整 DTO 契约，七类 65 项定向通过，不改精度算法、不豁免架构门，随后已推送 `303a139`。#142 双全量门通过，金额 IT 已有 53 项检查通过，但在非严格模式重启因测试环境键错误而失败，后续部署被跳过。本轮修正环境绑定，5 项定向通过，完整真库与部署仍待新 CI；见[验证记录第 13 节](../qualification/README.md#ci-141-fix)和[第 14 节](../qualification/README.md#ci-142-session-fix)。随后 #143 金额真库 57 项通过，部署生命周期修复已推送；#144 在更早的合同阶段缺运行器依赖，独立修复 `b8f2cf0` 预热并离线执行真正合同，见[第 16 节](../qualification/README.md#ci-144-dependency-fix)。G10 部署仍待新 CI，G6 按负责人要求已继续实现，见 4.6；不擅自开始循环与聚合。

### 4.6 G6 乘法、G11 视图计算字段

- G6：负责人要求继续下一阶段后，已按[设计第 21 节](../design/12-feature-semantics-and-semantic-types.md#g6-multiplication-proposal)实现精确乘法、显式整数转 Decimal 和能力级算术输入检查；分页默认、Optional／PATCH 不变，Ref.id 的错误路径按真实 DTO 属性。shop 按数量计价扣库存、订单持久化后再创建明细，28 类 223 项主回归通过。真库并发、精度回滚和明细一致性已接 CI，本机未运行；library 罚款仍缺日期差，未记完成。见[验证第 17 节](../qualification/README.md#g6-multiplication-local)。#143 金额真库 57 项通过，但部署漂移未闭环；#144 在初次合同测试缺失运行器依赖，已单独修复 `b8f2cf0`，见[第 16 节](../qualification/README.md#ci-144-dependency-fix)。下一项评审 G11，不顺带循环／聚合。
- G11：已按第22节实现并由 #150 验收：`field remaining: Count = item.capacity - item.enrolled;`，计算输出独立命名、当前行正式绑定，互斥 Column／Related／Computed 分支与共用投影渲染，不新增 SQL／DDL。course 增加 remaining／full；关系与乘法真库均含计算断言。库可借数量仍需跨行聚合，不在本轮。见[验证第21节](../qualification/README.md#ci-150-accepted)。下一项回到 G2 业务组合。
- 最新 CI：`4a1d8c8c2f28fd0d5678f65bfd4494cf163d8321` 的 #150 全部门 success，G11 闭环：关系真库 40 项（含根行 doubledCapacity 60／40、嵌套 label 逐行、枚举 active 逐行）、乘法真库 56 项（含 8 并发选课仅 3 成功、满额后 remaining／full）、八题生成工程、双全量门及独立部署均通过，见[验证第21节](../qualification/README.md#ci-150-accepted)。#149 三项失败已确认是测试假设错误（借用只认夹具主表的 expectRow、视图未声明 capacity），已由 `4a1d8c8` 修正。计算仅基于本行、三个投影路径共用计划，不新增SQL／DDL，不自动舍入Money，也不补跨行可借数量。

### 4.6.1 G2 第一批（#154 已验收）

显式 `load … as row for update else Error;` 仅 atomic command／主键允许；Scope、结构和源编辑保留事实。Spring Boot 目标明确 READ_COMMITTED，工程级 MyBatis 本地缓存 STATEMENT；显式锁用既有 BaseMapper identity locking query，不因能力变化修改实体 Mapper。原版本自动锁不变。booking 改为 `[startsAt,endsAt)`，Room 锁后执行重叠 exists。15 类 147 项定向通过，见[验证第 22 节](../qualification/README.md#g2-locking-local)。真库屏障检查复用 MultiplicationBusinessConformanceIT，未在本机执行，需求账本不变。首轮 #153 双全量门通过，但新增预约比较把 Room 对象绑定到 room_id 而返回 500，未能走到锁屏障；已修复 Lowering 的 identity 取值并补真实 MyBatis 参数回归，21 类 89 项定向通过；随后 bf14d90 的 #154 全门成功，乘法／预约真库 72 项通过，含先读后等锁屏障，见[验证第 25 节](../qualification/README.md#ci-154-accepted)。

复核修正：课程冲突也需要学生锁；旧草案先修课片段的 required 并未取得，任意集合的反关联后来已用Course根的两个同级any表达，不用单门限制冒充完成。library 的限额及借／还一致性已实施待 CI（下节）；课程组合已实现见第三批，排队未完成。verify 的 push 已忽略 docs/** 和 **/*.md，纯文档更新不触发 CI，文档尽量随源码提交。

### 4.6.2 G2 第二批：library 限额（已实现，待本批 CI）

不改语言或生成器生产代码：User.activeLoans 默认 0，Borrow 先锁读者，以 <5 的条件增量占额后再改副本／建 Loan；GiveBack 锁 User → Loan → Copy，首次归还减一，重复／外人／计数异常拒写，晚失败要求整事务回滚。续借不占额。7 类 31 项定向通过；新的真库 IT 直接用实际 topics/library 源，已编译但未在本机执行。旧库需回填真实在借数，不能把 DEFAULT 0 当业务迁移；已有超限数保持真实值，可归还而不可继续借。见设计 23.6 与[验证第 26 节](../qualification/README.md#g2-library-local)。随后第三批已实现课程冲突的学生锁与任意先修课的反关联，排队另案，不重做已有读取／金额／投影原语。

随后 #156 正式全量通过，但新 IT 把 SIR 声明错误名误期待为大写下划线，借阅真库 60 项通过、22 项错误码比较失败。修复只改测试预期并补固定错误码到实际 SIR／生成异常的契约，3 类 17 项定向通过；library SIR 和编译器生产代码未改。后续 `9d31a82` 的#157现已核实success，#158借阅IT亦通过；账本另行归档，见[验证第 27 节](../qualification/README.md#ci-156-error-contract-fix)及[第29节](../qualification/README.md#ci-158-readiness-fix)。当时课程阶段仅有本地未提交测试，未混入该修复；负责人会通知 CI 结果，不等待或轮询。

CI 提速版 #155 主验证 8:18，正常路径 Completion 跳过，实际测试保留；部署被取消，不记整轮通过。完整基线仍为 #154。后续部署并行／缓存尚未实施，不必因已完成本轮提速重复修改工作流。文档随本批源码，不额外触发文档 CI。

### 4.6.3 G2 第三批：课程组合（实现，待本批CI）

实际 topics/course 已实现周课表半开区间、学生锁冲突检查、任意直接先修集合；关键是把Course作为根，组合同级 any(Prerequisite) 与 not any(CompletedCourse)，不用新语法、循环或时段快照。先修只在草稿增删、发布后冻结；成绩由管理员追加，学生不能自报通过。选课先User后Course；退课User→Enrollment→Course，座位仍按SQL守卫增减。原POST /courses/{id}/enroll、DELETE /enrollments/{id}不改路由。数值missing/null用既有G6检查，分页默认保留。

6类38项定向通过；新CourseSelectionBusinessConformanceIT直接用该题目，已编译接现有业务CI，真库/HTTP/锁等待/晚失败回滚本机未执行。源384行SIR／3379行Java／14个显式接口，完整数不升，账本67／7／10。见[设计23.7](../design/12-feature-semantics-and-semantic-types.md#g2-course-combinations)和[验证28](../qualification/README.md#g2-course-local)。旧库课表、发布、先修、成绩和座位账本须所有者维护窗口核验，不做自动迁移；不提供成绩撤销、递归闭包或多时段/多学期。文档随本批源码，CI结果仍由负责人通知，不查询/等待。

#158正式全量门及生成工程构建通过，新课程IT却用不存在的 `/api/courses/10` 判定就绪，404导致全部课程业务未运行、部署跳过。现只将探针改为已有公开搜索，并用实际 Api 端点与输入模型守契约，3类34项定向通过；未修改生产接口／业务，不接受404为READY。见[验证29](../qualification/README.md#ci-158-readiness-fix)，等新源码CI，结果仍由负责人通知。

上述CI修复已单独推送7d13a2d，排队没有混入；随后按负责人继续指示完成第四批，见下文。循环/聚合/日期差仍另案，不能借此扩大。

### 4.6.4 G2 第四批：书目FIFO与本人扫码领取（实现，待CI）

实际library增加Reservation及ReserveBook／ClaimReservation／CancelReservation／MyReservations，无新增语法／编译器生产分支。先取得User→Book等共同锁，再查同书更早预约；不能加全局owner scope，私有列表单独过滤。普通Borrow有队列即拒绝，GiveBack仅释放，AddCopy也锁Book；领取成功才扣名额、改Copy、建Loan并删预约，同事务共同提交。满额不自动跳过，所有者或馆员可明确取消；馆员锁预约所有者而不是自身，避免反向User锁。旧续借规则未改，无过期／通知／自动分配。

8类58项定向通过，实际源443行SIR／4086行Java／20个接口。原借阅8项和错误／路由／DDL合同、Mapper不变性保留。原library IT复用一个应用，新增重复入队／领取、多副本、取消／领取竞争、可观察Book锁等待和外键／触发器晚失败的四表快照检查。仅已编译、本机NOT_RUN；不查询CI，负责人通知。见[设计23.8](../design/12-feature-semantics-and-semantic-types.md#library-reservation-proposal)和[验证30](../qualification/README.md#g2-reservation-local)。账本只把预约从✗改待真库△，**67／8／9**。升级新增空队列表，维护窗口同时切换全部写入口、旧Borrow不能与新锁协议混跑，原activeLoans须真实回填。后续G5／G8及日期差先定范围，不静默扩展。

### 4.7 G5 列表循环、G8 后续聚合（新增范围先确认）

- G5：请求里带列表、工作流逐项处理；对一批行统一改或删（订单行、级联删除）。商城的"一个订单多个商品"、取消订单退库存都卡在这里。
- G8：首批条件金额SUM已确认实现，见下；平均、分组及月度预算仍另案。普通条件行计数已由 G2 提供，不重做。
- 两者都会让语言明显变复杂。上一个 Agent 的建议：等 G7、G2、G4、G9、G10 补完，看还剩多少需求写不下，再决定。

#### G8 首批已实现（待同源码CI）

#162在语言参考门发现第5.10节的声明片段误标完整sir且漏User／Entry。本批已改为包含依赖的可执行sir-declarations；新增文档到SUM计划、owner及actor正式绑定的契约，不绕开文档门、不改生产实现，23项定向通过，见[验证32](../qualification/README.md#ci-162-sum-reference-fix)。真实SUM IT和部署仍未验收，修复单独提交推送后继续分组方案，不查询或等待CI，由负责人通知。

负责人已确认并实现[设计24](../design/12-feature-semantics-and-semantic-types.md#g8-decimal-sum-proposal)的条件Decimal单列SUM：独立步骤／字段绑定／scope／源编辑与IR硬化；空／全空0，总额不继承单笔精度；复用Lambda谓词和BaseMapper。按需标记及当前Statement告警插件、STATEMENT缓存防绕过，普通查询不改；真实ParamMap缺ew与合法变量名冲突均有RED→GREEN。ledger本人范围／本人分类合计已用上，195／3949／23；不推断分类方向净额或月份预算。30类187项定向通过，见[验证31](../qualification/README.md#g8-decimal-sum-local)；旧Windows目录读取10项受阻，新的内存SUM图谱完整构建通过。Money原IT复用同一应用／构建，加Optional、scope、参数、any和固定CAST告警对照；仅已编译、本机NOT_RUN。方案／文档同源码提交，账本67／8／9不变。group-by、AVG、期间／时区／结果DTO及G5另案；CI结果仍由负责人通知，不查询。

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
