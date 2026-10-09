# 当前工作单：Q21 G2 第六切片——多源工作流更新的文件应用、基线发布与显式恢复

- 状态：**AWAITING_CI**（2026-10-08）。负责人确认D0–D8；实现/本机定向完成（去重162项，新54项，无fail/error/skip）。双门/四旧业务IT/新增多源IT均NOT_RUN，待显式提交推送后同SHA CI。Git未授权，本机不跑业务IT/全量门。
- 阶段：G2。Q16–Q20已验收归档，G2未关闭，不进入G3。
- 前置：[Q20归档](completed/Q20-multi-source-workflow-read-only-plan.md) / Accepted [ADR-023](../architecture/ADR-023-project-workflow-read-only-planning.md)。受测`e4042080e2f2f501468ede1ba5a1a3badec15582`，[CI run37764055905](https://github.com/HoloNova/Software-IR/actions/runs/37764055905)双门995/0/0/5，四IT40/61/36/63全通过，XML999含四IT；本机186、新26。Q21本机证据见§8，CI门NOT_RUN，不能沿用Q20 CI验收它。
- 开工HEAD e404208；已有Q20证据/归档及Q21设计文档差异保留。本单由主会话实施，不自动派子代理；不提交/推送。
- 依据：[MAIN.md](../../MAIN.md)、Accepted [ADR-003](../architecture/ADR-003-toolchain-application-owns-project-application.md)、[ADR-020](../architecture/ADR-020-current-baseline-transaction-direction.md)、[ADR-021](../architecture/ADR-021-multi-source-compilation-and-graph-compatibility.md)、[ADR-022](../architecture/ADR-022-multi-source-baseline-storage-and-reopen.md)、[ADR-023](../architecture/ADR-023-project-workflow-read-only-planning.md)、[设计02](../design/02-sir-language-and-modules.md)/[设计07](../design/07-validation-and-direction-roadmap.md)。

## 1. 场景与推荐交付

Q20已经从保存的四源或初始五源片段B0编译候选、选择SearchCourseEnrollments工作流，证明放宽ACTIVE→ACTIVE或CANCELLED只改变Service，并返回完整只读UPDATE计划；现在该计划还不能改变工程。

**本单推荐：Q20完整Planned及候选快照 → 锁内重新读回/编译/复核 → 备份旧受管文件、替换计划内文件 → 完整保存候选B1源/图/清单 → 文件与B1全部核验后发布CURRENT → 清理本事务材料。** 结束时工程受管树逐文件字节等于候选从零生成，新实例无需原源目录即可重开B1，再规划/应用第二次工作流修改。

中断后只接受显式恢复：CURRENT仍B0就反向补偿，已B1就验证并清理，其他值/缺失证据拒绝并保留。成功不留阻塞下一次工作的日志；失败不误删用户文件、旧基线或未知材料。

这仍是同一个软件、一个能力的工作流更新，不是扩展语言/多声明修改/数据库演进。文件更新不是数据库迁移，不声称运行中的应用无停顿切换；生成源码的可恢复事务也不是所有读者同时看见的目录级原子快照。

## 2. 已直接核查的事实（不是Q21运行证据）

| # | 当前限制/可复用能力 | 依据 |
|---|---|---|
| F1 | Q20计划只含UPDATE，Pure与Application摘要绑定完整两侧revision/context/目标/差异；Application verify锁内完整重读/重编译，Planned没有可传旧apply的类型 | ProjectChangePlanningApplication、ProjectChangePlanningResult、ProjectChangePlan |
| F2 | Q19 Bundle四成员固定、descriptor V2/ID域绑定完整源/图/受管清单；CURRENT物理锚是Bundle内baseline-id.kcg-pointer | ProjectBaselineCodec、ProjectBaselineStore、ADR-022 |
| F3 | Store.guardState只允许LOCK/baselines/CURRENT/CURRENT.new；baselines必须为空或只含当前那个ID。read/revalidate都调用此guard。因此B0+B1并存会使inspect/context/plan失败，不能只写个B1就交付 | ProjectBaselineStore.guardState、ProjectBaselineVerification.read/revalidate |
| F4 | SecureFileAccess.list逐项限8；普通读取受预算、NOFOLLOW和目录句柄锚定；发布原语只支持无CURRENT时排他硬链接，不含替换已有CURRENT | SecureFileAccess.list/read/createPendingLink/publishLink |
| F5 | 旧ChangeApplyTransaction绑定BaselineBundle/Store和单源receipt；MixedTransactionCore同样绑定旧Bundle，虽有UPDATE/硬链接备份/文件意图/发布逻辑，不能直接给它Q19 Bundle | ChangeApplyTransaction构造/prepare、MixedTransactionCore构造/preflight/prepare |
| F6 | 旧ChangeRecoveryEngine加载旧Bundle，按CURRENT决定方向；JournalGate只识别V1–V4且inspect会删空事务目录。新只读入口不能直接调用它实现无副作用门禁 | ChangeRecoveryEngine、JournalGate.inspect/parseByMagic |
| F7 | Q19register只有初次保存/同ID幂等，禁止不同ID替换CURRENT；Q20无发布权限。支持多源更新必须显式增加状态/执行合同，而非悄悄放宽初次注册 | ProjectBaselineStore.publish、ADR-022 §5、ADR-023 |
| F8 | 既有ChangeLoopBusinessConformanceIT复用课程/报名schema、种子与运行器，R1原查询三课程→四课程且ART101出现；CI当前只跑四个业务IT | conformance/ChangeLoopBusinessConformanceIT BEFORE_R1/AFTER_R1、verify.yml |

结论：本单必须把**文件更新、候选保存、当前指针、历史基线与恢复门禁**一起设计。不能把新Bundle塞旧日志、虚构source.sir、删B0绕过单基线限制、或者只为成功例把guardState放开到任意目录。

## 3. 推荐合同与边界

### 3.1 新执行入口，不放宽旧消费者

新增明确项目工作流apply/recover typed API（名称/码/版本P3冻结），输入携带Q20完整Planned、不可变候选SourceSnapshot及显式state/output绑定。只有完整UPDATE Planned可以执行；调用方hash自洽不是授权证据。

执行在同一已有StateRootLock内重读B0、核验受管盘面、从保存和候选原字节完整重编译、重建context与计划、逐字段比较、再绑定全部候选文件字节/清单及UPDATE delta。旧context/旧目标/错误输出根/候选改变/截断或伪造plan/增删文件/合同改变都在首次写入前拒绝。不能先调用公开verify释放锁，再另取锁直接执行旧结果；不复制Q20业务规则或把I/O塞sir-change。

apply执行前读取所有受管文件而非只看Service；更新阶段再次核对B0字节、物理身份、备份与staging证据。路径/根/父目录替换、链接、跨FileStore或不支持原语明确拒绝，不提供覆盖/拷贝降级。结果必须区分未开始、已回滚、已发布、需要恢复；已发布但清理未完成不能报“未修改”。具体typed状态/handle/诊断在P3冻结。

### 3.2 不改变Bundle字节格式，显式扩展状态合同

B0/B1继续使用Q19四成员Bundle、descriptor/源容器/图版本/ID域，旧V1 Bundle、旧sourceSha和V1–V4日志含义不变。历史基线不可变保留；新状态规则必须说明哪些目录是已发布历史、哪些是本事务候选，未知文件/陌生基线仍拒绝，不自动删除历史或把外部完整Bundle当已发布。

推荐在**独立项目状态/事务版本**中记录B0→B1发布关系及合法历史集合，不改变业务IR。是否需要常驻历史记录、如何在CURRENT单一线性化点下处理其部分写入、初次Q19状态如何显式接入、历史数量/读取预算如何限制，在P2实测后冻结；不得先假定旧guard支持历史或任意增加第二发布权威。若无需常驻索引也能凭不可变发布证据验证相同合同，应采用更小方案并记录依据；证据不足时先报告，不默许陌生目录。

Q19原初次注册/不同ID拒绝不变；Q19inspect与Q20context/plan/verify需要识别**受验证的已提交项目历史状态**，仍只读、只取已有锁、遇事务/残留只报告阻塞。这两处生产读编排/guard准入是本单明确范围扩展；Accepted ADR-022/023应补独立执行扩展引用，不重解释原格式或旧验收结论。

连续B0→B1→B2必须实际通过，B1重开不能因保留B0而失败；B2再次应用不能复用B0目录/key。历史/目录/日志/staging/文件累计预算固定且在写入前检查，达到上限拒绝而非隐式GC；上限P2/P3冻结，不假称无限历史或只验一次更新。

### 3.3 文件提交与CURRENT发布

只接受与完整B0/B1受管清单差异精确相等的UPDATE集合，闭包外全部保持。先持久化足以恢复的事务绑定，再完成候选全部源/图/清单及候选受管字节暂存；严格复读、核验各层摘要和原字节重编译关系，不将半写Bundle当成功。

所有被更新文件先形成经核验的同卷硬链接B0备份，再以持久化逐文件意图执行替换；已记意图未替换、已替换尚未记完成都需能区分。替换前后再次核验受管字节/身份/根，全部文件达B1且B1保存完整后才允许发布CURRENT。B1物理指针必须锚定其Bundle，已有B0到B1的替换及CURRENT.new归属由P4实测并冻结，不能挪用Q19“CURRENT不存在”的排他发布。

事务不是全目录瞬时覆盖：任何中间态都保持写门阻塞，完成/恢复才重新开放。锁是工具间串行而非任意外部修改的认证；失败在支持检测点明确拒绝，不承诺检测所有非合作竞争。

### 3.4 独立日志与显式恢复

推荐独立项目UPDATE日志族，绑定状态根/输出根、transactionId、完整B0/B1 ID及manifest/source/graph证据、Q20 context/plan摘要、目标、排序UPDATE记录与物理归属/文件状态。magic/版本/编解码/总体与逐文件状态图/尾部中断处理/预算在P3冻结，不预定为V5，也不把Project ID塞旧V1/V4模型。

解析必须拒绝非法跳跃、发布后回滚、未完成文件伪装终态、未知/重排/重复字段、日志/路径含分隔词不往返、截断/超限或伪造绑定；合法路径须自己写得出且读得回。gate无写入、不清空空目录，未知/多事务或旧族材料都阻塞；旧recover不解释新日志，新recover不解释旧族。

CURRENT=B0：只反向补偿已产生副作用的UPDATE，恢复B0工程/指针一致性；CURRENT=B1：只核验完整B1并清理本事务材料，禁止恢复B0。缺失/其他CURRENT、指针锚或Bundle/备份/输出证据不明，保留RecoveryRequired/Failure；不能凭文件像B0/B1或日志标记猜方向。

清理先逐文件/目录证明本事务归属，未知对象、外部同字节替换不能删除；不删历史B0/B1或用户文件。合法事务中断的双向恢复和清理重复执行幂等。尚无可验证日志/身份的部分准备材料可以明确拒绝保留，但不能以此豁免**已经修改工程后的恢复完成门**；缺证据矩阵与有效事务可恢复矩阵必须分列。

### 3.5 NoChanges和重试

NoChanges不发布候选源（包括注释/排版和输出等价），不建事务/新Bundle、不换CURRENT；不新增“只更基线”事务。原已执行请求若仍持B0 context，B1下应按陈旧凭据拒绝，不能声称旧请求重放自动NoChanges。以B1新context规划相同B1候选得到NoChanges，零盘面抖动；恢复重复触发才走自己的幂等合同。

## 4. 开工先决探针（历史要求；实测/冻结见§8与ADR-024）

| 门 | 先实测/冻结 | 停轮条件 |
|---|---|---|
| P1 | Q20根/片段两个真实B0，从计划到候选Bundle与完整UPDATE绑定；旧事务哪些纯/安全原语可共享，锁内重编译边界 | 必须复制工作流规则、伪造单源类型或修改业务语义时停轮 |
| P2 | 已存Q19 B0到历史状态接入、合法B0/B1/B2并存、陌生完整/半写目录拒绝、历史/枚举/字节累计预算与来源证明；冻结状态合同 | 为继续工作必须删B0、接受任意目录、无限枚举或引入第二提交权威时先报告 |
| P3 | apply/recover/结果/handle、独立日志/门禁/格式与状态图；锁内完整重新规划、计划篡改拒绝、历史/准备部分写入可恢复边界；Proposed ADR-024据实冻结 | 未有实际共享/恢复依据就直接泛化旧三族，或旧读取变成自动清理时停轮 |
| P4 | 已有CURRENT→B1指针替换、硬链接锚、更新/备份/staging物理身份与同卷原语；发布前后中断、根/父目录/指针替换；不安全fallback拒绝 | 发布产生无法按CURRENT判定方向的空窗或丢锚，或者无法保留外部对象时报告 |
| P5 | 真实成功/连续更新/重开/NoChanges、最少两文件UPDATE的合成或真实case（证据分列）、完整checkpoint故障表与到达断言；确定新CI多源业务IT接入 | 只有单文件成功/抛异常而没有新实例恢复、漏同批另一UPDATE、新基线不可重开时不开放apply |

开工时不把接口/原语推荐当已实现；P1–P5先实测再接通事务。结果见§8与[Proposed ADR-024](../architecture/ADR-024-project-workflow-update-publication-and-recovery.md)。重要范围冲突须报告，不降低完成门。

## 5. 已确认决策D0–D8（按推荐方向）

| # | 推荐 | 为什么 |
|---|---|---|
| D0 | 单能力工作流UPDATE应用＋完整B1保存/发布＋显式恢复一起交付 | 单改Java不能继续变更；单存候选不能代表工程已更新 |
| D1 | 保留Q19 Bundle字节格式；显式新项目状态/日志版本，旧消费者拒绝新族 | 状态接入范围可审，不默改单源sourceSha/旧日志 |
| D2 | Application共享锁/安全访问/来源编译/计划规则，先实测事务原语再定提取范围 | 修多源执行共性，不写课程特判、不展开重写所有旧事务 |
| D3 | 发布前重新独立规划并逐字段绑定Planned及全部生成字节 | 目录/候选/盘面过期时不能凭旧SHA覆盖 |
| D4 | 备份/意图/文件全部完成、完整B1保存后才CURRENT；恢复严格ADR-020 | 指针是唯一提交方向；发布后绝不回退已提交状态 |
| D5 | NoChanges零发布；旧请求陈旧拒绝、新context与重复recover分别幂等 | 不把注释发布/缓存请求偷偷扩成另一类事务 |
| D6 | 连续两次更新与独立重开为完成门，历史有预算、不自动GC | 避免第一次成功后guardState把工程永久堵住 |
| D7 | CI保留四旧业务IT，新增真实多源工作流闭环IT，复用既有schema/种子/运行器 | 旧单源IT全绿不能证明新发布链；核验3→4、ART101出现而表数据不变 |
| D8 | 本机仅指定类、故障用临时目录；重型门/全部业务IT由同SHA CI；Git逐次授权 | 遵守资源约束，不为取得CI自动提交/推送 |

D7场景使用已核查ChangeLoop fixture/seed的R1查询行为，拆成合法多源源集；两个真实B0的文件等价仍分别本机定向验证。CI新IT只做工作流更新/数据不变，不顺带扩R2–R4其他操作。Workflow新增IT/配置开关/证据artifact是本单明确待审范围；无环境/取锁失败记NOT_RUN，不以本机启动MySQL替代。

## 6. 完成门

1. 根能力/初始片段B0真实首次生成→Q19保存→原源目录不可用→Q20context/plan→新apply→B1；受管路径与候选从零生成逐文件字节完全等同，未触及/用户文件不变，新源全体原字节/图/清单核验，CURRENT物理锚为B1。
2. 新实例从B1重开/规划第二次工作流更新→B2；B0/B1历史保留且受限/来源可验证，读取/计划不创建清理状态，成功后无阻塞材料。新B1 context同候选NoChanges零盘面变化；旧B0计划重放明确拒绝。
3. apply锁内完整重建context/plan；错误目录/根/目标/版本/source/manifest/伪造重算hash/截断列表/新删路径/合同/非目标变化拒绝；未开始阶段所有失败零盘面变化。进入事务后的失败须真实处置，不冒称NO_CHANGES。
4. 新日志规范往返/严格状态与绑定/格式/路径/限额；新旧族互拒；active/未知/多日志均阻塞且读门无副作用。新公开apply只在内部恢复矩阵通过后接入。
5. 所有checkpoint列表及数量/到达次数钉住，至少两个UPDATE的部分提交；准备部分源/图/描述符/日志/历史材料、全部备份前后、意图未落盘/落盘未记完成、B1保存/指针发布前后、清理前中后逐点新实例恢复。有效记录CURRENT=B0恢复旧树，B1仅核验清理；无证据状态明确保留拒绝，不混报可恢复。
6. 双向恢复重复幂等；第三值/缺CURRENT、同字节外部指针/输出/根替换、未知清理目录成员、损坏/缺失B0/B1/备份、链接/跨卷/不支持原语、资源预算满均拒绝且不误删。安全检测点与模拟进程中断分列，不把抛异常测成断电持久性证明。
7. 同SHA CI双门、四旧IT及新多源业务IT的commit/run/artifact齐全。新IT真实构建/启动B0、同工程apply后重新构建启动B1，原同schema数据下total3→4、ART101出现、表行指纹不变；TEST_FIXTURE_DDL仍不抵扣产品初始化/迁移。
8. Q19原保存/15点矩阵/codec/安全读取、Q20规划/目录/篡改/golden、旧UPDATE/CREATE/DELETE/Rename/V4/恢复与CLI回归；Q20不可apply旧断言改成明确新正/旧负合同，不留包装后的假绿。计数来自实际报告，以Q20已验收995为增量基准，不预报新增、不增加skip/exclude。

## 7. 允许/禁止与交接

**已确认允许**：Application新项目执行/显式恢复/独立日志及门禁；安全访问原语、状态历史读取与受限Store/Verification扩展、锁内共享编译/规划编排；只为共享原语所必需的旧事务小范围抽取（P1/P3先冻结，旧行为保持）；指定测试、真实多源业务IT、verify.yml接入及ADR/资格文档。sir-change只读业务规则/类型含义保持，其他生产模块不改。

**禁止**：旧sourceSha/Bundle/日志含义静默重解释、删除/覆盖历史基线、自动GC、未知对象清理、读入口隐式恢复；新增/删除/字段/改名/跨文件移动/源成员变更、nodeKey/模块实例/完整身份迁移、数据库G3、CLI产品命令或部署；全部旧事务重写、不安全文件fallback；本机重型业务IT/全量门、自动Git/子代理。

本单已获实施确认，现AWAITING_CI；§8记录实际本机通过证据，CI/验收未取得，不宣称G2关闭。Q20验收/归档及本单改动未提交，Git另行授权。

## 8. 实施与本机证据（2026-10-08）

### 8.1 P1–P5收口与冻结

- P1/P4五探针通过；复用Application锁/来源编译/完整规划/安全句柄，不改sir-change/其他生产模块，不挪旧Bundle。原Q19四成员字节/ID域及旧V1–V4保持。
- P2发现回返原内容会复用B0 ID，采用独立不可变发布关系而非每ID单父节点。历史8 Bundle/32关系/32终态receipt/64 MiB累计，事务32 UPDATE/256 owned/64 MiB staging，binding/关系/日志64 KiB、日志256事件；没有第二head或自动GC。读取核对每条关系对应COMPLETED凭据，自洽关系/完整陌生候选也拒绝。
- P3冻结ProjectChangeExecutionApplication.apply/recover及typed结果/handle、binding/journal/edge V1独立magic与严格状态图。同锁原字节重编译/完整重新规划/比较，不凭调用方摘要授权。Q20只读/旧apply拒绝合同测试改为新正/旧负，没有包装假绿。
- P4发现并直接复现本机删后同字节重建复用inode；加保留硬链接pins与日志槽/绑定anchors，原地修改仍查SHA。归属/所有相关输出/备份核验先于补偿写入。终态保留恢复证明并安全搬入receipt；pins计入预算，不暗中删历史。
- P5内部矩阵通过后接通公开入口，后续pin/历史加固后重验；真实根/片段单Service与两UPDATE合成证据严格分列。详细格式/目录/预算见[Proposed ADR-024](../architecture/ADR-024-project-workflow-update-publication-and-recovery.md)，待同SHA CI与负责人验收后Accepted。

### 8.2 最终定向证据与计数

最终去重**25类162/0/0/0**，其中新10类**54**（Prerequisite5、History8、Binding5、Trail5、FileAccess5、Slots6、Transaction4、Execution8、RecoveryProtection4、FaultMatrix4）；原Q19四类33、Q20三类26、其余旧定向49。新增业务IT仅编译，不计本机执行数。没有新增skip/exclude。

最终报告索引`/tmp/q21-directed-summary.json`；原日志`/tmp/q21-final-project-regression.log`、`/tmp/q21-final-legacy-regression.log`及限额对齐复验日志。本机2 vCPU，free当次可用2271 MiB；串行systemd scope MemoryMax=2G/CPUQuota=150%，Maven 512 MiB/测试fork 384 MiB并约束Metaspace，不跑MySQL/生成工程build或应用；结束无遗留mvn/java。前者113项首轮仅一条旧合同测试反射列表漏transactionId失败，随后定点修测试、后者14/14覆盖该类；其余通过不重复，汇总按最终XML去重。新IT快照工厂引用编译笔误已改实际构造器，无业务规则改动。

- 真实根四源/初始片段五源：首次生成→Q19保存→原源目录移开→Q20计划→公开apply→B1；完整受管树与另一路候选从零实际生成逐路径/字节相等，用户文件与闭包外字节不变；候选所有源原字节、图/清单、CURRENT锚及新实例inspect验证。
- B1新context第二次UPDATE→B2与再次回返B0 ID通过；历史保留且重开/规划不写状态；32次真实发布后下一写入零修改拒绝，fresh NoChanges仍零抖动；旧B0请求在B1拒绝，注释不发布新源。
- 实际public中断结果区分NOT_PUBLISHED/PUBLISHED，明确RecoveryRequired；旧recover实调拒绝新族且不动盘面，新recover双向成功/重复ALREADY_CLEAN无修改；读入口不隐式清理。
- 伪造重算hash/截断artifact、改变候选/目录/目标、最后父目录替换未开始时零写；缺/第三CURRENT、同字节外部指针/输出/备份/槽替换、损坏/缺失Bundle/未知清理或pins成员、错误handle明确拒绝并保留；预算/链接/不安全跨目录原语反例通过。
- 旧保存/15点发布、codec/读取边界，Q20完整纯/application规划与诊断，旧Change/UPDATE/CREATE/DELETE闭环计划、改名/V4/恢复/架构定向通过；LegacySourceEvidenceCompatibilityTest的四业务fixture输出和V1图golden保持。CLI及全量回归留CI，不冒称本机已跑。

### 8.3 故障与证据缺失矩阵

两UPDATE合成Graph/Bundle使用包内受约束ReplayCompiler，仅验证文件事务分支；生产与真实纵向仍完整原字节编译。

- 应用**150检查点、64标签**：清单`project-update-checkpoints.properties`对完整标签/到达数量硬断言；64个完整binding前准备像缺证据，保留并明确拒绝，不混报恢复成功；其余56回滚/30已提交清理，新实例恢复、根/文件字节与CURRENT一致，重复恢复无抖动。
- 恢复中再次中断**85点**：回滚57、提交清理28，双向trace摘要/长度硬断言，逐点新实例继续恢复及幂等。覆盖备份/准备部分写、替换意图与实际move未记、全部文件完成、CURRENT.new/指针发布前后、日志非选槽部分写/pending selector、清理中/终态receipt搬迁前后。
- 检查点异常是模拟进程停止像，不宣称真实断电/目录级原子快照。所有未知材料/缺证据场景与合法可恢复场景分列。

### 8.4 CI接入与待取得证据

verify.yml保留四旧IT，新增ProjectWorkflowBusinessConformanceIT与`kcg.project-change-conformance.enabled`。复用课程/报名DDL/种子、同schema互斥锁/运行器/凭据redaction与artifact；真实多源B0构建启动total3→同工程apply后重建启动B1 total4/ART101出现→从保存B1第二次更新恢复total3；course/student/enrollment整表行指纹不变。原源目录不可用，TEST_FIXTURE_DDL不抵扣初始化/数据库迁移。新IT已编译，**本机运行NOT_RUN**。

本批双全量门、四旧IT、第五个多源IT均**NOT_RUN**；待显式提交/推送后核对同SHA run与conformance-evidence/surefire-reports，不使用Q20 e404208/995结果代替。文档/链接/差异检查完成后仅请求Git授权，不预先DONE/Accepted/启动下一单。
