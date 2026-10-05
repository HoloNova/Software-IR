# 当前工作单：Q19 G2 第四切片——多文件基线保存与独立重开核验

- 状态：**`AWAITING_CI`**（2026-10-05）。D0–D7 已批准；P1–P5 已实测并冻结 ADR-022，主会话完成实现及定向验证。CI 双门/四 IT 均 `NOT_RUN`，等待本批源码提交/推送授权；本机不重复重活，不作最终验收。
- 所属阶段：**G2：持久身份与受控模块**。不关闭 G2，不进入 G3。
- 前置：[Q18 归档](completed/Q18-multi-source-compilation-and-source-snapshot.md)，受测 SHA `638eaa7f41ff66a1e2eaef57a8ac9679d75d70fd`，[CI run 37212618580](https://github.com/HoloNova/Software-IR/actions/runs/37212618580) 双门各 936/0/0/5、四 IT 40/61/36/63 全通过。
- 当前工作区：HEAD 为 `638eaa7`；未提交差异包含 Q18 验收归档、Q19 文档与 Application 单模块实现/测试。未提交、未推送；未改 Parser、Semantic、Lowering、Generator、Graph、sir-change 或旧 Bundle/V4 事务。
- 依据：[主设计 02](../design/02-sir-language-and-modules.md) §1–2、[ADR-021](../architecture/ADR-021-multi-source-compilation-and-graph-compatibility.md)、[MAIN.md](../../MAIN.md) §2–3；源定位、业务身份、集合摘要及 CURRENT 发布不得混为一谈。

## 1. 场景与结果

Q18 已能把 project.sir 和课程/学生/报名三片段生成一个 35 文件工程。但全部源字节只随生成结果返回；关掉进程后，现有单文件 Bundle 无法保存这套源。

**本单推荐流程：Q18 生成结果的不可变源快照 → 核验并注册多源基线 → 保存全部原字节/图/受管清单 → 新实例读回 → 不依赖原源目录重编译并核验现有工程。** 注册只写明确的 stateRoot，不重写生成工程，不把源拼成大 SIR。

例如保存后删除测试的原 sourceRoot，仍能从已发布基线取回四份原字节，分别 Parse、一次 Resolve，再生成与原工程相同的文件和 V2 Graph。任何片段被篡改（即使只改注释、Java 没变化）都不能被读成原基线。

**不交付多文件 Change/Rename 计划、增量应用或移动应用。** 本单解决“可靠保存与重新核验”，下一单才让变更消费这份基线，不一次扩展保存格式和执行事务。

## 2. 已核对事实（不是新功能已实现）

| # | 当前事实 | 源码依据 |
|---|---|---|
| F1 | ProjectToolchainResult.Success 已同时返回 SourceSnapshot、ExecutionManifest、V0_2 Graph，构造时要求图中的源清单与快照一致 | `api/ProjectToolchainResult.java` |
| F2 | SourceSnapshot 保存每源原字节，按逻辑路径排序并防御复制；SourceSetManifest 只保存路径/长度/SHA，不能从摘要恢复原字节 | `sir-parser/.../source/SourceSnapshot.java`、`SourceSetManifest.java` |
| F3 | executeProject 编排包含物理读取、Semantic、Lowering/Generator、Graph 和写新工程；不能在核验基线时调用它来再次落盘 | `api/ToolchainApplication.java` |
| F4 | 旧 BaselineBundle 只有一个 sourceBytes；Store 严格要求 descriptor.kcg-baseline、source.sir、graph.kcg-psg 三个文件 | `internal/bundle/BaselineBundle.java`、`BaselineBundleStore.loadBundle` |
| F5 | 旧描述符 magic/ID domain 固定 V1，字段顺序固定单源 ID/长度/SHA；格式枚举仅 V1 | `BaselineDescriptorCodec.java`、`api/ChangeExecutionBaselineFormatVersion.java` |
| F6 | BaselineDescriptor.toBaseRevision 直接构造单源 ChangeBaseRevision；不能把集合 SHA 填进旧 sourceSha 字段改变含义 | `BaselineDescriptor.java` |
| F7 | registerGeneratedBaseline 读取一个物理源、从源重编译、构图，再验证现有工程清单后发布；新多源请求不能直接复用该请求类型 | `api/GeneratedBaselineRegistrationRequest.java`、`ChangeExecutionApplication.doRegisterGenerated` |
| F8 | 旧初次发布检查 CURRENT、manifest，先 stageBundle 再写 CURRENT；已有不同 CURRENT 拒绝，同 ID 可返回 AlreadyRegistered；stage 中断/重复暂存须另核实，不能推定恢复已适用于新格式 | `ChangeExecutionApplication.publishInitialBaseline`、`BaselineBundleStore.stageBundle/writeCurrentAtomic` |
| F9 | 普通 plan/apply、applyRename、planning context、recover 的读取链都接单源 Store/Receipt/Revision；Q18 两个 planner 仍固定拒绝 V2 图 | `ChangeExecutionApplication.java`、sir-change 两个准入守卫 |
| F10 | StateRootLock、StateRootPathGuard、JournalGate、OutputManifestVerifier 已有；JournalGate.inspect 不是严格纯读（可能清理空事务目录），不得原样接到宣称零写入的只读入口 | `internal/state/`、`internal/OutputManifestVerifier.java` |

以上为开工前读码事实，不用来冒称新格式已实现；实施后的证据见 §9。旧三文件 Store/单源凭据未改。

## 3. 推荐产品边界

### 3.1 输入和权威数据

新增明确的项目基线注册请求，输入 Q18 生成时的 **SourceSnapshot**、outputRoot 和 stateRoot；不重新读取可变的原源目录来替代当时快照。业务声明 ID 不改。

调用方可构造普通数据对象，不能仅凭 Success/给定摘要就信任它。注册应从该快照原字节独立解析/编译、生成文件并构图；验证根 sources 与快照成员恰好一致、imports 闭包合法、图来源与源集合一致，随后核对输出受管文件内容。清单从重编译产物推导，不接受调用方随意指定要接管哪些文件。

只复用既有语义与目标实现。允许抽出 Application 内“从快照编译、不读原目录、不写工程”的共享编排，供首次生成/注册/重开核验消费；不复制 Parser、Resolve、Lowering 或 Renderer。

### 3.2 新格式与完整原字节

推荐新 Bundle 类型/descriptor codec/ID domain，与旧单源类型显式区分。具体命名与版本在 P2 及 Proposed ADR 冻结，不预设只增加枚举就足够。

建议把全部源写为一个**有路径键、长度 framing、版本及规范顺序的受限原字节容器**，而不是合并文本；保存 BOM、CRLF、注释等原字节。这样无需把不可信逻辑源路径直接用于 Bundle 内任意子目录读写。图沿用 Q18 V0_2/V2，源清单沿用 manifest v1，不再改 Graph 格式。

新描述符绑定：入口、完整源集合摘要、源容器字节长度/摘要、V2 图长度/摘要/canonical digest、target/lowered IR、outputRoot、受管文件清单及摘要。baselineId 用明确新 domain 和 canonical framing 覆盖全部证据；源集合 SHA 不是声明 ID，也不是旧单文件 sourceSha。

Bundle 仍绑定 outputRoot；**不承诺复制到另一宿主/输出根后 baselineId 不变**。源快照本身不编码宿主位置，不能据此推导 Bundle 可自动重绑定。

### 3.3 注册、CURRENT 和中断

新增入口只完成初次注册或相同基线的幂等复核，拒绝把不同 CURRENT 替换成新基线。旧 V1 状态不在本单自动升级。

复用明确 state/output 路径边界、锁和现有日志阻塞规则。完整基线在重新加载与输出验证通过后才发布 CURRENT；CURRENT 的现有 64 hex + LF 指针格式推荐保持不变，指向的 descriptor 显式区分格式。新注册中断只处理本次基线保存/发布，不新增修改工程文件的事务族，不改变 Q17 V4 或 ADR-020。

P4 必须冻结并测到：暂存写一半、完整 Bundle 尚未 CURRENT、CURRENT.new 写完尚未发布、CURRENT 发布成功尚未清理。应能对证据完整的同一注册重试或显式收尾；缺证据/不同 CURRENT/未知文件则拒绝并保留证据。不得猜“像哪一版”、扫目录删除或覆盖用户文件。若初次注册没有 B0，明确记录 CURRENT 缺席，不能伪造 B0 ID。

**注册不是纯读**：锁文件和明确有主暂存证据可能存在。失败必须不改变生成文件、不改变已有 CURRENT 或已发布 Bundle；不能笼统保证新 stateRoot 一个字节也不产生。清理白名单、物理身份和可收尾方向须先定清，不能把旧 stageBundle 的存在性视作完成证据。

### 3.4 重开与版本隔离

新增只读项目基线检查：显式 stateRoot/outputRoot/expectedBaselineId → 从 CURRENT 读取并核验新 Bundle → 从保存的原字节重编译 → 核对源清单、图、目标/清单及实际受管文件，返回新的类型化项目凭据/源摘要信息。它不是旧 ChangePlanningContext，也不提供 ChangeTarget catalog 或调用 planner。

只读核验不得修复输出、重建丢失源文件、改 CURRENT、清理暂存或自动恢复；脏状态明确报告。并发条件下如何稳定读 CURRENT/Bundle、是否取得既有锁以及锁之外的零写入契约，必须在 P5 冻结，不把 JournalGate.inspect 原样称作纯读。

旧 V1 Bundle/ID/源与图字节、旧注册/规划/应用/恢复/CLI 行为不变。旧入口遇新格式明确拒绝；新入口遇 V1/未知/交叉版本也明确拒绝，不偷偷升级/降级，不构造单源 ChangeBaseRevision。

## 4. 开工先决门（获准后才能运行；当前均 NOT_RUN）

| # | 必须核实/冻结 | 停轮条件 |
|---|---|---|
| P1 | 从 SourceSnapshot 复用 Q18 编译链的无 I/O/无落盘入口草图；根 sources 与成员闭包精确对应，重放保留全部来源 | 必须拼接文本、重读源目录、重复名称解析或改目标业务时停轮 |
| P2 | 新 Bundle/源 payload/descriptor/receipt 的字段与 canonical 编码、目录布局、版本矩阵；V1 golden 对照；Proposed ADR | 不允许只改旧 sourceSha 含义或放宽旧 codec；未写清字段/错误码不实施格式 |
| P3 | 对四源课程实测 descriptor/graph/payload 大小，再冻结逐文件/总量/计数/字符串限额及 bounded NOFOLLOW 读取；明确借用的 guard/锁/Store 哪些能安全复用 | 旧 readAllBytes 或事前查链接不能被当作新入口完整安全读取证据；未知字段/非规范内容/多余文件必须拒绝 |
| P4 | 初次发布完整故障点、重复注册与暂存物所有权/恢复方向；CURRENT 缺席/新 ID/无关 ID | 任何中断无法收尾且无法明确拒绝保留证据、未知文件可能被删、或要升级 V4/新增工程事务时停轮报告 |
| P5 | 独立重开/纯核验 API、读取一致性、旧消费者交叉调用矩阵；需要修改的旧 API 精确列表 | 新入口会隐式恢复/清理、旧 planner 准入被放宽、旧 Bundle 被重解释时停轮 |

P1–P5 在开工时均待测；已按先探针后冻结顺序完成，结果见 §9.1 与 [ADR-022](../architecture/ADR-022-multi-source-baseline-storage-and-reopen.md)。探针未要求扩大到多源 Change/Rename/工程事务。

## 5. 决策表（负责人已确认推荐 D0–D7）

| # | 推荐 | 理由 / 不选的方向 |
|---|---|---|
| D0 | Q19 仅多源初次注册、保存、重开核验及保存阶段的中断收尾；多源 Change/Rename/apply 留后续 | 输入证据与修改工程分开验收，不一次扩保存格式和事务 |
| D1 | 注册消费不可变快照、从字节独立重编译核对输出；不是再读原源目录 | 保证登记的就是该工程那批源，修复一次读取/重放能力让生成、注册、后续变更共同复用 |
| D2 | 新 Bundle 类型与独立 codec/domain；受限 framed 源容器，不拼接文本、不复用 source.sir 的旧含义 | 既有对象和 codec 明确单源；容器保留每文件定位且减少不可信子路径 I/O |
| D3 | V0_2/V2/manifest v1 不变，CURRENT 指针保持现格式；新旧入口显式拒绝交叉格式 | 不同时引入第二次 Graph 升级，不给旧 ChangeBaseRevision 填集合摘要 |
| D4 | 仅初次绑定/同基线幂等，已有不同/旧格式 CURRENT 拒绝 | 更换基线是未来变更，不把注册冒充 apply |
| D5 | 从已保存原字节重开，无原源目录依赖；只读核验不自动恢复或清理 | 重启后证据可用，诊断与修复分离；丢失原目录不能误导重读当前文件 |
| D6 | 明确保存中断的可收尾/拒绝边界，并测未知文件保护；不改旧 Journal/V4/工程文件 | 不能有写入没有中断证据，也不为了新格式顺手重写旧恢复机制 |
| D7 | 本机指定类定向；新代码提交后同 SHA CI 双门/四 IT；Git 操作逐次授权 | Q18 的全绿不证明 Q19；纯设计不跑构建、不开 MySQL |

推荐按上述范围开工，不推荐把 Change/Rename 的完整多源 revision/plan/apply 同时并入。若负责人希望一次完成多源变更，应先另审更大范围，而不是含混地让本单“兼容旧基线”。

## 6. 完成门（每门需真实正反证据）

1. **真实保存/重开纵向**：Q18 四源课程首次生成 → 从快照注册 → 关闭/新建 Application 实例 → 不再拥有测试的原源目录 → 读回四份原字节/来源 → 重编译核验 35 文件与 V2 图一致。不是只测手工 Graph/Bundle。
2. **源完整性**：源容器逐文件原字节/BOM/CRLF/注释保存；改任一片段注释即改集合证据和 Bundle ID。删/加/重复/交换/错入口/伪造长度/摘要/未知版本/非规范顺序明确拒绝；外层摘要重算不能掩盖内层源清单、语义或图不一致。
3. **输出绑定**：实际文件与独立生成清单不符、错误 outputRoot、目标/图来源/集合不符，注册或核验拒绝，不重写文件；不接管清单外用户文件，不因出现用户文件就删除它。
4. **版本与旧字节**：V1 descriptor/Bundle ID/源/图 golden 与旧注册、context、Change/Rename/恢复定向回归；新旧交叉/未知版本拒绝，不能成功返回旧计划或旧凭据。
5. **幂等和不同基线**：同快照同根重复注册需重新核验已存 Bundle 和盘面，而非仅因 CURRENT ID 相同返回成功；坏 Bundle/改盘面/不同 CURRENT 拒绝。成功不产生第二基线或指针抖动。
6. **路径与资源**：状态根/输出重叠、链接/替换/占位、额外 Bundle 文件、损坏 framing、超限正反例；读取先限额、无不安全 fallback。具体码/阶段/数量/位置在 P2–P3 登记。
7. **保存中断矩阵**：P4 全部边界注入，CURRENT 缺席/已发布方向及重复收尾；未知/外部文件零删改，已发布基线不回滚。矩阵明确点数和逐点断言，不用一个成功例代替故障门。
8. **纯核验边界**：重开不读原 sourceRoot、不写工程/CURRENT/Bundle、不自动清暂存或恢复；发现残留/证据不完整即报告，盘面与发布证据保持。锁合同单列，不把取得锁冒称完全无副作用。
9. **同 SHA 回归**：本机受影响定向后，CI 双闸门和四业务 IT 全通过，记录 commit SHA、run URL、报告产物及旧 fixture/goldens 不变；相对 Q18 的 936 计算本单新增，无新增 skip/exclude。

## 7. 允许/禁止范围与实施节奏

**已批准允许**：sir-toolchain-application 内项目基线新 API/模型/codec/Store、快照编译共享编排、受管验证与初次发布/收尾；测试和文档。复用源契约，不修改业务语言、SymbolId、Graph 编码、Lowering/Generator。

**禁止**：sir-change 准入放宽或多源 ChangeBaseRevision、旧类型字段重解释、plan/apply/applyRename 多源接线；修改已发布工程、V1/V4 事务格式与恢复方向；组合改名、nodeKey/moduleInstance、完整声明 ID 升级、数据库/G3、CLI 接线、远程模块、扫描目录寻找源、自动提交/推送。

范围确认后先做 P1–P5，冻结 Proposed ADR 及 API/格式/诊断；再依次实现保存/读取、重放核验、初次注册与故障矩阵。每段只跑受影响指定类；任何需要扩大范围的发现先报告。提交和 CI 完整验证另经授权，不在本机重复重活。

## 8. 本轮记录与交接

2026-10-05 开工前只读核查 F1–F10、起草工作单，未改代码/测试或执行构建。Q18 已验收归档；负责人随后确认推荐范围、D0–D7 并授权实施。主会话完成 §9 所记探针、实现和定向验证，等待提交/推送与同 SHA CI。G2 剩余模块实例、nodeKey、完整身份兼容、跨文件变更和组合改名均未被本单抵扣。


## 9. 实施与验证记录（2026-10-05）

### 9.1 P1–P5 实测及冻结

`ProjectBaselinePrerequisiteTest` **6/0/0/0**：四源课程从不可变快照逐文件 Parse、一次 Resolve 后生成同一套35文件和V2图；原源目录移走后仍可重放。根 sources 与成员精确闭包检查由新的 `ProjectCompilation` 共享编排复用，首次生成也调用它，不另建解析/类型/目标规则。

P3实测原字节4,657、源容器4,816、V2图108,687字节、35个受管文件。最终descriptor约10.8 KiB（绑定的临时outputRoot长度不同会差几字节，不能当作固定golden），容器/图大小不随宿主位置改变。冻结在 [ADR-022](../architecture/ADR-022-multi-source-baseline-storage-and-reopen.md)：源128份/每份1 MiB/总8 MiB、路径512字节、容器8 MiB+128 KiB、descriptor2 MiB、Graph16 MiB、受管8192条/每文件8 MiB、普通字符串16 KiB/物理或输出路径4 KiB；目录枚举最多8项，避免先收集任意大脏目录。读取先查属性/长度、受限读取、后查fileKey/size/mtime；锚定SecureDirectoryStream+NOFOLLOW，不支持时拒绝，无readAllBytes fallback。编码逐字符串/逐manifest项限额，非法UTF-16字符严格拒绝，不替换为`?`。

P2冻结独立 V2 descriptor/domain、V1源容器、V0_2/V2图以及独立Receipt。Bundle精确四成员：`descriptor.kcg-baseline`、`sources.kcg-source-set`、`graph.kcg-psg`、`baseline-id.kcg-pointer`；后者为推导ID的65字节物理指针锚。CURRENT.new/CURRENT排他硬链接此锚，以物理身份而不是“内容一样”证明可消费/清理归属。旧三文件Bundle、codec/domain、Receipt/Revision一律未改。

P4选择仅初次注册及相同候选收尾：四成员排他SYNC写入 → 读回核验 → CURRENT.new排他链接 → 再验证输出 → 排他建立CURRENT → 同物理锚且目标正确时清理CURRENT.new。不同/旧CURRENT拒绝，不更换已发布基线；半写/未知对象保留并报错，无目录/Bundle自动覆盖删除。

P5冻结 `ProjectBaselineApplication.register/inspect`，请求分别消费快照/显式expectedBaselineId；返回REGISTERED、ALREADY_REGISTERED、VERIFIED或结构化失败，不生成旧Change凭据。inspect只取得已有LOCK（无LOCK即拒绝），不创建目录/LOCK、不调用有清理副作用的JournalGate、不恢复或清理CURRENT.new。

### 9.2 真实链路与反例

- 四源真实课程首次生成 → 注册 → 移开原sourceRoot → 新Application实例读回并重编译 → 35文件、V2图、全部原字节与来源一致。输出树含用户文件不变；重开与幂等重复注册前后完整盘面指纹相同，无CURRENT.new/事务目录残留。
- 注释变化改变源集合及Bundle ID，不改Java也不能替换原CURRENT；同快照重复注册会重新验证Bundle/盘面，非仅比ID。输出损坏、错误outputRoot/expectedID、缺源/多源/旧语言快照均拒绝，不修复或接管输出。
- codec：BOM/CRLF/注释/空片段原字节往返；删截/追加/重复/逆序/错入口/伪造计数长度/未知版本/非法UTF和超限拒绝。外层SHA重算仍不能藏住内层源证据错误；完全重算descriptor/目录ID后伪造target或manifest，重开仍因独立重编译不一致拒绝。
- 旧消费者实调：新基线被旧context、plan、apply、applyRename、recover及旧Store/codec拒绝，盘面不变；新入口拒绝V1。旧注册仍正常成功/AlreadyRegistered，四业务生成摘要和六份V1图快照仍匹配Q17 CI固定golden（不是本轮重新生成golden）。
- 路径/读取：根重叠、根/祖先/叶链接、遍历、目录代文件、读取后size/mtime/inode替换、超限文件/目录枚举、额外/缺失/篡改Bundle成员明确拒绝。纯inspect遇事务、未知对象、CURRENT.new或缺LOCK均不清理、不创建，盘面保持。

### 9.3 初次发布中断矩阵

`ProjectBaselineContractTest`钉住 **15点**（列表长度和实际到达数均断言）：保存前、候选目录、四成员各首次chunk写入/完整写入、Bundle核验、pending指针、CURRENT发布、清指针前/后。CURRENT缺席的完整候选与已发布候选均可显式重试收尾，随后纯核验和再重复注册保持；缺/半写成员拒绝，完整保留故障后证据，不“补写”未知暂存。指针仅65字节，其首次chunk已完整落盘，按完整证据处理，不能冒称半指针恢复。

另钉住两方向CURRENT.new被外部相同字节文件替换（不同inode）必须拒绝且不删；发布边界出现外部CURRENT不得覆盖；输出在Bundle保存后被改必须在发布前复验拒绝，保留输出改动而不修复。

### 9.4 本机定向、源码边界与CI

本机2 vCPU、总内存约3.8 GiB、开工可用约1.9 GiB；串行 `systemd-run --scope -p MemoryMax=1500M -p CPUQuota=125%`，Maven/test fork各`-Xmx384m`，无`-T`、无MySQL/业务IT。最新已运行指定类27个去重 **199/0/0/0**，其中本单4类 **33/0/0/0**（Prerequisite6、Contract12、Codec9、ReadBoundary6）。旧注册/查询生成/变更/改名/三旧事务故障及V4恢复等受影响指定类均通过。

日志：`/tmp/q19-directed-regression.log`初轮194；随后仅因严格编码/预算及新反例补测，`/tmp/q19-final-directed.log`26、`/tmp/q19-cross-version.log`Contract12。按最新XML同一类替换去重，不把重复运行相加。报告在`sir-toolchain-application/target/surefire-reports/`。没有把这199项冒称CI全量。

生产改动仅Application：新的项目基线API/codec/Store/受限文件访问，共享快照编排及executeProject调用点；旧Bundle/ChangeExecution/V4、其他生产模块不改。CI双全量门及四业务IT **NOT_RUN**；必须待本批提交后绑定同SHA的run+artifact，不能沿用Q18的936全绿作验收。未提交/推送，G2未关闭。

### 9.5 诊断合同与未完成门

新格式错误以`SIR-APP-PROJECT-BASELINE-{REQUEST,VERSION,FORMAT,LIMIT,READ,OUTPUT,STATE,PUBLISH}-001`区分请求/版本/framing/限额/读取/盘面/残留/发布中断；共享编译错误保留原PARSE/SEMANTIC/LOWERING等code及真实每文件primary/related位置。旧根校验/锁沿用既有码，不将其伪装成新格式诊断。注册保存阶段异常为RECOVERY_REQUIRED并保留，inspect失败NO_CHANGES，不自行启动旧recover。

完成门1–8已有上述本机正反证据；门9仍NOT_RUN。状态AWAITING_CI，当前仅请求本批提交/推送授权，不归档或启动下一单。
