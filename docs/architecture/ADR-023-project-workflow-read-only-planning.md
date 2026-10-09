# ADR-023：多源基线消费与单能力工作流只读规划

- Status: **Accepted**（2026-10-08，负责人提交/CI后要求继续下一阶段；Q20按D0–D8及同SHA证据验收归档）
- Date: 2026-10-05
- Scope: 项目版context与单能力工作流UPDATE只读计划；不含apply、其他五操作、Rename或源移动
- 工作单：[Q20归档](../roadmap/completed/Q20-multi-source-workflow-read-only-plan.md) §8–10；前置：Accepted [ADR-021](ADR-021-multi-source-compilation-and-graph-compatibility.md)/[ADR-022](ADR-022-multi-source-baseline-storage-and-reopen.md)

## 1. 已验证的起点

P1真实四源及初始五源片段版本从保存原字节重编译。放宽报名过滤均只有SearchCourseEnrollmentsService.java变化；片段注释改变源集合和V2图摘要而不改Java。不能把集合SHA写入旧ChangeBaseRevision，不能放宽旧V1准入或伪造V1图。

## 2. 冻结合同

- `ProjectChangeRevision`：独立formatVersion=1，完整SourceSetManifest，GraphVersion.V0_2、graphCanonicalDigest、ProjectGraphCanonicalFormatVersion.V2。sourceSetSha派生自清单，不是声明身份。
- `ProjectChangePlanningInput`：两侧revision/typed Normalized model/真实V2 graph、基线侧ChangeTarget。唯一操作为ModifyCapabilityWorkflow，重用该操作/文件证据值类型，不构造旧ChangeSet/ChangePlan。
- `ProjectChangePlan`：formatVersion=1，两侧revision、操作、排序artifact/file UPDATE集合；摘要域`KCG-PROJECT-WORKFLOW-PLAN-V1`。非空UPDATE，丢项/改证据即使重新算摘要也不代表通过复核。
- `ProjectChangePlanner.plan/verify`共用新准入及完整重算；verify必须重新plan并逐字段比较，不只查摘要/版本。Pure模型/图关联核对声明集合、ID/name/node/span及metadata.displayName（Graph标题不是softwareName，前后软件不变由共享SCOPE规则核对），Graph Validator/规范V2序列化校验来源及图摘要；不声称纯图能证明源字节重编译正确。
- 旧PlannerCore内部只提取revision无关WorkflowInput/WorkflowDecision与compareWorkflow；旧准入、诊断排序/码、ChangeAnalysis/ChangePlan包装保持不变。规则只存在一份，不复制工作流算法。
- Application `ProjectChangePlanningApplication.context/plan/verify`取已有锁，复用Q19的保存源重编译/输出核验。context request为stateRoot/outputRoot/expectedBaselineId/candidate SourceSnapshot；plan request持完整context、candidate SourceSnapshot和目标key。verify request另带计划并完整重新规划比较。没有apply入口，旧apply不可接受项目计划。
- `ProjectChangeContext`独立formatVersion=1：规范contextId、stateRoot、Q19 receipt、两侧revision及排序基线工作流目标目录（ChangeTarget、displayName、真实声明/workflow span）。context域`KCG-PROJECT-WORKFLOW-CONTEXT-V1`；目标key域`KCG-PROJECT-WORKFLOW-TARGET-V1`并绑定contextId与目标。contextId使用目标原始typed字段，不使用targetKey，避免哈希循环。
- 所有摘要用SourceSetManifest现有严格UTF-8、big-endian长度framing；整数/enum/大小字段以规范文本frame；List先计数再每项frame。计划覆盖两revision/操作/目标、每artifact全字段及其文件列表、完整UPDATE列表。context覆盖state/output根、baselineId/manifestDigest、两revision和完整目标目录。新格式版本仅1；未知版本拒绝。
- 候选入口/成员不变、声明所属源/顺序不变；Parse/Resolve诊断保留真实primary/related位置。由完整两侧文件集合比较证明UPDATE列表恰好覆盖，闭包外或新/删路径拒绝。
- Application Planned另以`KCG-PROJECT-WORKFLOW-APPLICATION-PLAN-V1`域绑定contextId和纯plan摘要；Application verify在同一锁下完整重读/重编译比较context与plan，不接受另一根的旧结果。
- NoChanges携带两侧证据及SEMANTICALLY_IDENTICAL/OUTPUT_EQUIVALENT原因，不发布候选源；精确相同源与注释/排版变化通过候选集合证据区分。

## 3. 读取与失败合同

只使用Q19既有锁/安全读和编译；不改变descriptor/payload/指针/guardState，不创建LOCK、不清理残留或隐式恢复。操作末再次核对CURRENT、Bundle字节/指针身份、受管输出及根身份；context旧候选/旧目录/旧目标不直接变成计划。限额沿用Q18（128源、1MiB/源、8MiB合计）/Q19（源容器、2MiB descriptor、16MiB graph）；新增目标目录与计划文件数量上限均8192；每字段严格UTF-8（至多16384个UTF-16单元）、逐frame限制总编码16MiB，contextId从不可变字段计算一次并验证，目标目录二分验证成员身份，不在每个key重算完整源证据。

Application新增拒绝码前缀`SIR-APP-PROJECT-CHANGE-`；ProjectChangeStage仅READ/PARSE/SEMANTIC/LOWERING/GENERATION/PREFLIGHT/GRAPH/PLAN。ProjectChangeDiagnostic逐字段保留ExecutionDiagnostic真实span/related INFO映射，并以planningStage保留纯诊断的TARGET/SCOPE/IMPACT等子阶段，不改旧诊断。纯入口新增`SIR-PROJECT-CHANGE-COMPAT-001`、`SOURCE-001`、`MODEL-001`、`VERIFY-001`，分别负责版本/清单与revision/模型图关联/重新规划不相等；共用工作流SIR-CHANGE-TARGET/SCOPE/IMPACT诊断保持原码与阶段。

### Q21共享读取与显式执行增量（2026-10-09，已验收）

本只读context/plan/verify仍无文件应用/隐式清理，算法/摘要域/旧准入不变；锁内规划编排抽出LockedProjectPlanning供新显式ProjectChangeExecutionApplication复用，不能公开verify后释放锁再写。Q19保存字节读取可验证有界历史/COMPLETED凭据，active/未知仍拒绝。旧消费者不接受项目计划，新执行合同正向实际测试与旧类型负向同步。事务/日志/预算/物理pins/终态证明另见[Accepted ADR-024](ADR-024-project-workflow-update-publication-and-recovery.md)，本机162/新54通过，f8a2f92同SHA run37865688743双门1049/0/0/5与五IT通过，Q21已验收归档；Q20历史995仍为独立证据。

## 4. 验证与剩余边界

本机完成：去重24类186/0/0/0，新三类26（Prerequisite2/PlannerContract10/Application14）；日志索引`/tmp/q20-directed-summary.json`，细证据见Q20归档§8–10/资格§1.18。两真实根/初始片段B0只改Service，新旧真实文件/产物计划直接对齐；context/plan/verify、末次四类变化、编码/源集合/合同/超限/链接/计划伪造与三结果正反通过；Type/Validate片段诊断保原码/位置。OUTPUT_EQUIVALENT合成纯分支与真实文件改变分列，纯关联不替代Application原字节重编译。Q19旧保存/15点矩阵/版本拒绝、旧Change/Rename/恢复与固定golden保持。负责人完成受测提交e404208，[run37764055905](https://github.com/HoloNova/Software-IR/actions/runs/37764055905)同SHA双门995/0/0/5、四IT40/61/36/63全通过，两类artifact核对；XML999含四IT，969→995增26。按原范围验收后转Accepted，不把历史969计为本单结果。验收不授权多源文件应用；其状态/发布/恢复合同须另单审定，不修改本只读算法/摘要域与旧准入。G2的nodeKey、完整身份兼容、多源应用/改名、数据库生命周期仍未交付。
