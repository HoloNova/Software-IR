# 当前工作单：Q20 G2 第五切片——多源基线消费与单能力工作流只读变更计划

- 状态：**`IN_PROGRESS`**（2026-10-05；实现/本机定向已完成，等待提交/推送授权以取得CI）。负责人确认D0–D8；P1–P5及本机完成门通过，去重186/0/0/0，新增26。双全量门/四IT仍NOT_RUN，尚未验收；禁止自动提交/推送。
- 所属阶段：**G2：持久身份与受控模块**。Q16–Q19已验收，不关闭G2，不进入G3。
- 前置：[Q19归档](completed/Q19-multi-source-baseline-storage-and-reopen.md) / Accepted [ADR-022](../architecture/ADR-022-multi-source-baseline-storage-and-reopen.md)。受测源码`f3d9ef7cbb2c841e6068b7ca82545c05cd955772`，[CI run37256515347](https://github.com/HoloNova/Software-IR/actions/runs/37256515347)双门各969/0/0/5，四IT40/61/36/63，全通过。
- 工作区：HEAD仍f3d9ef7；差异包含Q19验收归档/证据回填、Q20源码/测试及文档，未提交。生产仅sir-change与Application；不使用Q19 CI作为Q20验收证据。
- 依据：[MAIN.md](../../MAIN.md) §1–3、Accepted [ADR-001](../architecture/ADR-001-resolve-once-and-bind-by-node-id.md)、[ADR-003](../architecture/ADR-003-toolchain-application-owns-project-application.md)、[ADR-021](../architecture/ADR-021-multi-source-compilation-and-graph-compatibility.md)、[设计02](../design/02-sir-language-and-modules.md) §1–2/[设计07](../design/07-validation-and-direction-roadmap.md) G2/LANG门。

## 1. 一个具体场景与推荐结果

Q19已经能保存课程、学生、报名四份源，并在原源目录不可用时核验35文件工程。现在用户复制那份快照作为候选，把报名查询的过滤条件从“存在ACTIVE报名”改为“存在ACTIVE或CANCELLED报名”，不改名称、ID、输入输出或其他声明。

**推荐链路：保存的B0多源基线 → 从B0原字节重编译并核验现有工程 → 编译候选快照 → 给出工作流目标目录/新项目变更凭据 → 选择一个目标 → 只读计划列出允许更新的受管文件及两侧字节证据。** 两次读取间凭据过期就拒绝；不改Java文件、Bundle或CURRENT。

本单不是“看见两份Java不一样就允许覆盖”。单个能力的公开合同不变、非目标声明语义不变、目标Service/Controller影响闭包之外的所有输出不变，才能给UPDATE计划。若输入DTO或另一能力被一起改动，应明确拒绝，而不是漏报或扩大授权。

主例来自真实`valid/multi-course/project.sir`的`SearchCourseEnrollments @id("search-course-enrollments")`，过滤表达式引用`modules/enrollment.sir`的Enrollment/EnrollmentStatus。现有fixture能力位于入口文件；验收还须准备**能力初始就位于片段**的B0，独立生成/注册后修改片段工作流，以证明不是只支持入口文件。准备B0不代表本单支持“在两个版本间移动声明”。具体合法表达式/实际文件差异先经P1实测，不能提前把“只改Service”记成事实。

## 2. 已核查的事实（仅读码，不是运行证据）

| # | 当前合同/限制 | 直接依据 |
| --- | --- | --- |
| F1 | Q19 register/inspect重新编译快照并验证图/清单/盘面；Success携带SourceSnapshot、Graph、ProjectBaselineReceipt，不携带旧Change revision/目标目录 | `application/api/ProjectBaselineApplication.java`、`ProjectBaselineReceipt.java` |
| F2 | 旧ChangeBaseRevision是单个SourceId+baseSourceSha+图版本/摘要/快照格式；ChangeSet固定这个类型且只有一操作 | `sir-change/api/ChangeBaseRevision.java`、`ChangeSet.java` |
| F3 | 旧PlannerCore显式只收V0_1/V1，并要求两图根sourceId等于单源revision；Rename plan/verify也守卫旧版本 | `sir-change/internal/PlannerCore.java:193–246`、`RenamePlannerCore.checkSupportedVersions` |
| F4 | 旧工作流规划已检查软件/metadata/target、声明集合/顺序、非目标完整语义、目标公开合同、Service/Controller闭包和全工程闭包外文件；最后输出UPDATE或两类NoChanges | `PlannerCore.planModifyCapabilityWorkflow`、`SemanticProjection`、`ClosureComputer.compute` |
| F5 | ClosureComputer按SymbolId/类型化图边追踪DECLARES→Lowered→Service/Controller→文件，不读取磁盘；现有Project Graph不含完整REFERENCES语义引用图 | `sir-change/internal/ClosureComputer.java`；ADR-021 §4 |
| F6 | 旧ChangePlan包含旧ChangeSet且禁止UPDATE/CREATE/DELETE混合，不能直接作为新项目计划的外壳 | `sir-change/api/ChangePlan.java` |
| F7 | 旧planning context/hasher绑定一个candidateSourceSha、旧receipt/revision及target目录；Application读一个candidateSirFile，目录由重编译后的typed model构造 | `application/api/ChangePlanningContext.java`、`ChangeBaselinePlanningRequest.java`、`internal/changeplanning/TargetCatalogBuilder.java`、`ChangePlanningHasher.java` |
| F8 | Q19纯inspect只取已有锁，不创建/清理；Store.guardState只接受当前候选目录，多个基线/旧transactions/残留CURRENT.new均拒绝 | `ProjectBaselineApplication.inspect`、`ProjectBaselineStore.guardState` |
| F9 | Q18多源一次Resolve，真实位置进入V2图；局部变量/AST结构路径仍可能随编辑变化，nodeKey未实现 | `ProjectCompilation`、ADR-001/021；设计02 §2 |

结论：**不能只放宽版本守卫，也不能把集合摘要伪装成旧sourceSha。** 推荐新增明确项目revision/context/request/plan类型；从既有工作流比较与闭包算法抽出共享纯决策，不复制另一套业务规则。是否能在不改变旧行为的条件下抽取，列为P3先决门，不冒称已有。

## 3. 推荐产品合同

### 3.1 新凭据与候选

Application项目入口输入显式stateRoot/outputRoot/expectedBaselineId及不可变**候选SourceSnapshot**。不读取原sourceRoot，不扫描目录，不把用户文件路径加入候选。入口/成员路径集合必须与基线相同；本单不支持加删源或跨文件移动、声明重排/改名。

新增项目revision描述**完整基线源清单及集合摘要、V0_2图canonical digest/V2格式**，Application context再绑定Q19 baselineId、输出根/受管manifest、候选源集合及图证据。具体类型名、版本/domain/framing、字段和码在P2冻结；不修改旧ChangeBaseRevision/ChangeSet/ChangeIrVersion的含义，不造一个虚拟单文件来调用旧planner。

context是该次读回/重编译结果，不是永久身份。目标目录仅开放**基线侧能力工作流**，SymbolId与声明/工作流AstNodeId来自typed model；显示名称仅供阅读。目录须带真实文件/SourceSpan，candidate编译诊断保留primary/related跨文件位置。目标key/contextId采用新域，覆盖所绑定的两侧证据及目标；旧V1目录/hash域保持不变，不复用看起来相同的key作为兼容证明。

计划请求带所用contextId、项目revision/候选集合证据和目标key（或等价类型化选择，P2定稿）。计划时重新读取并核验当前B0、输出和候选，精确比较凭据后再规划；不信任上次Success对象/调用方手工拼的摘要，也不让context释放锁后旧信息直接变成有效计划。

### 3.2 一次只改一个能力工作流

仅新增项目版ModifyCapabilityWorkflow等价操作，不新增业务SIR语法。基线与候选都复用Q18/Q19逐源Parse、一次Resolve、Type/Validate/Normalize、现有Lowering/Generator/Graph。

旧准入/旧对象包装不变；共享纯算法须在`sir-change`内部拥有业务判断，Application只负责编译、快照、状态核验和结果保护。推荐提取revision无关的“比较/闭包→UPDATE差异或NoChanges/拒绝”，旧入口仍构造旧ChangeAnalysis/ChangePlan，新入口构造独立项目结果。**禁止通过伪造V1图、虚拟SourceId、假ChangeSet或删来源后调用旧算法。** 不能保证旧字节/诊断/计划不变时，先停轮报告，不复制500行作为捷径。

纯规划必须核对新revision与两侧源清单/图版本/来源，目标与基线typed声明及workflow对应；版本匹配不是支持范围。源字节检查归Application，pure API所需源证据/模型-图关联检查在P2/P3明确；不得宣称只凭Graph能证明源重编译正确。若增加独立复核/verify入口，需相同支持矩阵与完整重算比较，不能只守plan留旁路。

### 3.3 输出与只读性

Planned携带B0与候选来源证据、目标/操作、受影响artifact及按路径排序的UPDATE文件记录（两侧长度/SHA/owner/artifact），以及可验证的确定性计划摘要。三类结果：**Planned / NoChanges / Failure**；不存在apply入口或可直接传旧apply的类型。

计划必须恰好解释**完整两侧受管文件差异**；有增删路径/闭包外变化/输入输出合同变化/其他声明变化即拒绝。不用“闭包内列几项”冒充全工程完备。计划摘要覆盖操作、revision、candidate证据、目标和所有差异；篡改、丢一文件、旧context重放必须被新入口复验拒绝。

注释/排版可能改变源集合/图摘要但不改语义和输出：返回带**新候选证据**的NoChanges，不将B0已发布源当成已更新，不改CURRENT；与精确相同字节的候选分清理由。nodeKey/LANG-04未完成，不把局部ID稳定当完成门，不通过缓存旧AST造稳定性。

读回/计划全程复用已有锁/安全受限读取，**不创建LOCK/state/output、不写Bundle/CURRENT/工程、不清空事务目录/暂存，不隐式恢复**。残留沿用Q19拒绝策略；未知/旧格式/不同CURRENT/坏输出/缺源等失败盘面不变。只证明支持检测点内一致读取，不夸称对不合作外部编辑者的任意目录原子快照。

## 4. 开工先决门（本机证据已取得，明细见§8）

| # | 先实测/冻结 | 停轮条件 |
| --- | --- | --- |
| P1 | 两个真实B0（根能力/片段能力）生成并Q19注册；无原目录重编译；改过滤条件两图/完整文件差异及跨源绑定；注释/排版/局部ID变化对比较的影响 | 合法单工作流需求必须靠修改业务规则、猜名称/映射或缓存旧AST才能通过时停轮 |
| P2 | 项目revision/context/目标/操作/结果/计划域、framing与版本矩阵、绑定/排序/诊断/限额；Proposed ADR-023据实冻结 | 字段依赖虚拟单源、旧sourceSha重解释，或没有清楚的新旧入口拒绝合同，不实施格式 |
| P3 | 找出旧工作流算法可共享边界；新纯输入的源/模型/图检查；旧合法/NoChanges/拒绝结果与新增入口对照 | 必须放宽旧准入/改旧合同、复制业务规则、把I/O移入sir-change时报告 |
| P4 | 受锁项目状态读取/重编译复用点，context到plan间CURRENT/候选/输出变化；全过程目录指纹、旧目录/残留/同字节外部指针保护 | 新只读入口会建锁/清理/发布，或必须改Q19布局/guardState才能完成时报告 |
| P5 | 完整manifest差异解释、Planned/NoChanges/Failure及计划篡改复验；目标真实片段位置/AST身份过期、旧消费者拒绝 | 计划会漏文件、范围不明、verify旁路或不得不改事务时报告 |

P1实测根/初始片段两条完整生成链通过；P2冻结Proposed ADR-023；P3共享原算法、真实新旧文件/产物决策对齐；P4已有锁/末次状态与盘面复验；P5完整差异、目标/摘要/篡改、三类结果均已验证。证据与限制见§8；双CI门和四IT未运行，不能据本机结果宣布验收完成。

## 5. 已确认决策（D0–D8）

| # | 推荐 | 为什么 |
| --- | --- | --- |
| D0 | Q20交付多源context/凭据和**一个能力工作流**只读UPDATE计划，不做落盘 | 比仅保存凭据多证明一条真实变更链，又不同时扩基线和事务 |
| D1 | 新项目类型/版本/domain，旧planner仍明确拒绝V2 | 集合是新的合同，不能改旧记录含义 |
| D2 | 提取共享纯工作流比较/闭包决策，旧、新API各自包装 | 普通过滤修改继续组合已有能力，修基础设施而非按课程写特判 |
| D3 | 候选为不可变SourceSnapshot；源成员/入口/声明所属文件与顺序不变 | 与Q19重放一致；读取物理候选目录/移动协议另单 |
| D4 | 新context与新目标目录、计划重编译/复核两侧证据 | 用户不手写AST ID，目录变旧不会误指另一个声明 |
| D5 | 全工程文件差异精确解释，UPDATE-only；输出等价返回有证据的NoChanges | 不漏闭包外文件，不把注释变化冒称基线已更新 |
| D6 | 只取已有锁、无写入/清理/恢复；保存格式/V1/V4不改 | 保持Q19Accepted和旧执行链，先验计划再审应用 |
| D7 | 普通Change其他五操作、Rename、组合改名、跨文件移动/新增源均留后续 | 不让一次版本准入扩成所有操作已经支持 |
| D8 | 本机受影响指定类；提交后同SHA CI双门/四IT，Git逐次授权 | Q19的969不证明Q20；本机仅指定类，不跑全量/业务IT |

## 6. 完成门（正反证据齐全，不靠示意图）

1. **真实纵向**：Q18生成→Q19注册B0→移开原源目录→新context取得基线/候选真实目标→新项目计划成功；根能力与片段能力两个版本均测，计划未落盘。
2. **语义及文件完备**：只改一个工作流/合同不变通过；新计划UPDATE集合等于完整候选从零生成与B0的文件差异，闭包外逐字节不变；非目标变化/输出合同/DTO变化/增删文件明确拒绝。
3. **源与版本**：新revision绑定全部源；仅片段注释变化也使候选证据变化，不能沿用旧context；成员/入口/版本/来源/内外摘要伪造、手工Graph使用不匹配证据/关联及手工计划复核旁路拒绝；纯API只证明typed模型/图来源关联，不证明原源字节的语义真实性，后者由Application完整重编译证明；源集合SHA不充当声明身份。
4. **目标与诊断**：SymbolId+基线AST ID核验，失效/错误侧/未知目标拒绝；新目录含真实文件/SourceSpan，Parse/Resolve/Type/Validate相关位置不丢失，不下游按名字修复。
5. **确定性与NoChanges**：相同字节/不同成员插入顺序/Locale产生同样context与plan域记录；确同候选、注释/排版等价、语义变但输出等价分别如实报告；源未发布不能记成CURRENT已更新。
6. **陈旧与只读**：context后候选/CURRENT/受管输出改变、Bundle损坏、旧/残留/链接/替换/超限均明确拒绝；每个成功/NoChanges/失败前后工程及state目录指纹相同，无建锁/清理/新Bundle；原目录缺失不阻止从已存字节编译。
7. **不可应用/篡改**：新计划不能进入旧apply/applyRename，不出现新apply入口；文件列表/候选哈希/revision/目标被改或截断后重新复核拒绝，plan与verify（如有）版本矩阵相同。
8. **旧回归及同SHA CI**：旧单源context/plan、Change工作流/作用域/影响相关指定类、Q19保存重开/15点矩阵、Q17改名/V4恢复及四业务/六V1图golden不变；提交后双门/四IT绑定SHA/run/artifact。全量增量以Q19的969为基线，不预报数量，无新增skip/exclude。

## 7. 允许/禁止与交接

**已确认允许**：sir-change新增项目typed API及共享纯工作流决策（旧API准入/结果不变）；Application新增项目context/plan、安全只读基线共享编排/结果保护；受影响测试、设计和资格文档。Parser/Semantic/Lowering/Generator/Graph生产格式及业务语义不改。

**禁止**：修改Q19descriptor/源容器/指针布局或Store.guardState的多基线限制；把V2变成旧计划可接受；更换CURRENT/发布候选/修改工程；新增V4/恢复协议；多源Rename/add/remove/字段操作、移动应用、nodeKey/moduleInstance/全声明ID映射、G3/数据库、CLI/远程模块、自动提交/推送。

负责人已确认D0–D8；本机实现及定向完成，IN_PROGRESS等待Git/CI授权，不提前记DONE。Q19验收仍绑定f3d9ef7及run37256515347，G2尚未关闭。下一步实际应用需另审工作单，本单不提前定其格式/恢复方案。

## 8. 实施与本机证据（2026-10-05）

### 8.1 P1–P5与实际合同

| 门 | 实际证据/结果 |
|---|---|
| P1 | ProjectChangePrerequisiteTest **2/0**：真实四源B0和初始五源片段B0均生成/注册35文件；移开原源目录后重编译，放宽ACTIVE→ACTIVE或CANCELLED仅改变SearchCourseEnrollmentsService.java。片段注释改变源集合/V2图而生成字节相同。此非跨版本移动应用 |
| P2 | Proposed [ADR-023](../architecture/ADR-023-project-workflow-read-only-planning.md)冻结项目revision 1 / V0_2 / Snapshot V2、新context/目标/纯plan与Application plan四独立域；完整成员清单、字节/图证据、绝对根/receipt/manifest/目标绑定；严格UTF-8、BE长度frame、8192项与16MiB编码限额。不改变旧sourceSha/版本/格式 |
| P3 | PlannerCore仅抽出WorkflowInput/WorkflowDecision/compareWorkflow：原比较/闭包不复制、旧准入与ChangePlan包装保留；真实旧单源与新多源过滤修改的fileChanges/artifactChanges逐记录相同，旧NoChanges/拒绝与语言投影回归通过。纯新入口验证V2规范摘要、声明ID/kind/name/node/span与metadata标题关联；图标题不是softwareName，软件前后不变由原共享SCOPE规则检查 |
| P4 | ProjectBaselineVerification共享Q19保存字节重编译/受管盘面核验；Q19register/inspect转用共享编排，Store.guardState/codec/布局/指针不改。新入口已有锁下完成重读、两侧编译及末次CURRENT/Bundle字节/指针身份/输出/root复验，无创建/清理/恢复；Q19四类33及15点矩阵通过 |
| P5 | 新Application context/plan/verify及ProjectChangePlanner plan/verify相同准入、完整重算；目标目录来自基线typed model，实际片段span可见。全工程差异精确解释，DTO/合同/非目标/闭包外更新与CREATE/DELETE拒绝；旧context/key、改摘要/文件/产物、重算伪造hash仍拒绝；计划不能进入旧apply/applyRename |

### 8.2 纵向、反例与只读

`ProjectChangePlanningApplication.context/plan/verify`是新公开边界。context请求接收不可变SourceSnapshot；计划请求持完整context和目录生成的key，复核另持完整Planned。结果为Planned / NoChanges / Failure；Application计划摘要额外绑定contextId及纯计划摘要。新失败阶段/诊断保留编译真实位置、related INFO及纯规划TARGET/SCOPE/IMPACT子阶段；Parse、重复@id、Type和Validate反例均点名真实片段。

两个真实B0的规划/复核前后完整工程/state内容指纹相同，用户未受管文件不动；重复/新实例/土耳其Locale/成员插入顺序确定。精确候选与仅注释变化均返回NoChanges，但候选证据不同，未发布源不记为CURRENT更新。**OUTPUT_EQUIVALENT用合成文件证据验证纯决策分支，不冒称真实课程的Java输出等价例；真实过滤修改确实改变Service。** 纯API不读取源字节或磁盘、不能独自证明模型正文确由该源生成；Application从保存/候选原字节每次重编译，不采用缓存AST。

拒绝矩阵包含：候选变化/旧目录/旧key、伪造context/plan/版本/revision/manifest、错误目标/模型图关联、同路径重排；已有输出修改、CURRENT变化/同字节异inode替换、缺LOCK/state、CURRENT.new/旧transactions/外部state文件、损坏源容器、descriptor/graph/payload超限、受管文件/Bundle链接。返回前注入4类外部变化（输出内容、Bundle、同字节指针、同字节输出根替换）均Failure且不修复。只声明支持检测点内一致读取，不宣称任意并发目录原子快照。

### 8.3 定向计数与命令证据

**去重186 run / 0 fail / 0 error / 0 skip（24类）**。Change **69**：SemanticProjectionParity11、Architecture3、Immutability19、Rename四类36；Application **117**：Q20新3类 **26=Prerequisite2+PlannerContract10+Application14**，Q19四类33、旧Change context/闭环/投影21、旧Rename端到端4、恢复/V4 28、多源兼容3、旧四业务/六V1 golden1、Architecture1。无新增skip/exclude。旧模块未全量运行，不用186填全量统计。

以`/tmp/q20-directed-summary.json`逐类/方法去重；主要日志：`/tmp/q20-targeted-regression.log`（首轮新 guard误用标题失败，仅保留其未受后续修改影响的旧类通过记录与Prerequisite2）、`/tmp/q20-final-contract.log`（修正后Application13全通过）、`/tmp/q20-pure-final.log`（最终Planner10全通过）、`/tmp/q20-diagnostic-contract.log`（新增且不同的Type/Validate方法1通过，13+1=14，非累加重试）、`/tmp/q20-final-compatibility.log`（Rename36+Q19Prerequisite6通过）。原source/生成4组与V1快照6组仍匹配固定Q17 CI golden，不重新生成期望值。

本机实测nproc=2、内存3875MiB、开工available1984MiB；单任务`systemd-run --scope -q -p MemoryMax=2G -p CPUQuota=150% -- env MAVEN_OPTS='-Xmx512m -XX:MaxMetaspaceSize=256m' mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am -Dtest=<指定类/方法> -Dsurefire.failIfNoSpecifiedTests=false '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m' test`。不加-T、不并发、不起MySQL或生成应用/工程构建；收尾进程检查无残留。

### 8.4 实施期订正与待CI

| 订正 | 实际处理 |
|---|---|
| 包内投影/loader接口 | 探针不扩大SemanticProjection可见性；采用公开模型与生成字节，包内共享算法验证。Graph loader实际返回ProjectGraphAnalysis，修正引用后编译通过 |
| 合同反例脚手架 | 新fails先误置input前、再误排Error顺序；按合法语法及排序构造，实际到达SCOPE-002，不放宽Parser/Validate |
| 标题/软件字段 | 新关联守卫误把Graph displayName视为softwareName，反例发现后按实际metadata.displayName检查；不改Graph格式，全部新正反门再通过 |
| 文本/资源/复核 | 不用默认UTF-8替换非法代理字符；每frame检查编码预算。contextId从不可变字段算一次并校验，目录二分核对成员；不把hash自洽当作完整计划复核 |

本批尚未提交/推送；CI两全量门、四业务IT及对应SHA/run/artifact均**NOT_RUN**。待负责人授权Git后才记AWAITING_CI并触发；969是Q19已验收基线，不能冒称Q20通过，也不预报全量数量。ADR-023暂Proposed；最终验收、归档、G2关闭及下一单均未授权。
