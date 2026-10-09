# ADR-024：多源工作流 UPDATE、受限发布历史与显式恢复

- Status: **Accepted**（2026-10-09，负责人提交并要求规划下一阶段；主会话核对同SHA CI/两类artifact后按D0–D8验收归档）
- Authority: [Q21归档](../roadmap/completed/Q21-multi-source-workflow-update-and-recovery.md) D0–D8及§9–10；前置 Accepted [ADR-022](ADR-022-multi-source-baseline-storage-and-reopen.md)、[ADR-023](ADR-023-project-workflow-read-only-planning.md)。不改其Bundle字节、ID域、纯规划规则或旧V1–V4合同。

## 1. 场景与共享边界

消费Q20完整计划及候选SourceSnapshot，在原工程更新一个能力的工作流、保存全部候选源、发布CURRENT，之后可独立重开并继续修改。真实根文件四源/初始片段五源的查询修改都只产生Service UPDATE；多文件部分提交另以受约束的两UPDATE合成Graph/Bundle及包内重编译测试seam验证，不把合成产物说成真实生成器输出。

共享StateRootLock、路径守卫、SecureFileAccess、ProjectBaselineVerification和Q20锁内LockedProjectPlanning；不调用公开verify后释放锁再执行、不复制sir-change规则。旧事务仍使用旧Bundle/日志，不为本单重写；其他生产模块不改。

## 2. 公开合同与锁内重新规划

`ProjectChangeExecutionApplication`仅公开`apply(ProjectChangeApplyRequest)`与`recover(ProjectRecoveryHandle)`。请求持ProjectChangePlanningRequest、完整Planned或NoChanges以及独立64-hex transactionId；默认ID由随机UUID散列，仅定位本次材料，不是计划授权或基线版本。handle绑定状态/输出根、transactionId、B0/B1以及可选完整binding摘要。

apply复用已有锁与只读项目gate，重新读取保存源、完整编译两侧、重建context/目标/计划并逐字段比较预期；重新核验全部受管盘面及完整UPDATE delta。候选、根、目标目录、摘要或差异截断/重算hash都不能替代完整重新规划。Pure业务/SOURCE守卫仍由Q20负责：源入口/成员、声明所属文件/结构顺序、非目标/公开合同不变；不支持CREATE/DELETE/Rename/字段变更/源移动。

结果：Applied（含两侧receipt）；NoChanges（保留原因与context，零写入/零候选发布）；Failure（尚未启动事务，NO_CHANGES）；RecoveryRequired（含handle与NOT_PUBLISHED/PUBLISHED/UNKNOWN，不能冒称未修改）。应用失败不隐式恢复。recover结果区分ROLLED_BACK、COMMITTED_AND_CLEANED和ALREADY_CLEAN；拒绝保留材料。旧B0请求在B1下拒绝，B1新context同候选才NoChanges。

## 3. 状态、历史与预算

保留Q19四成员Bundle、descriptor V2/源容器/Graph V2/ID域。状态增加`project-transactions/<transactionId>`和`project-history`，后者包含65字节`origin`、不可变发布关系及`receipts/<transactionId>`。CURRENT仍是唯一提交指针；历史不是另一个head，不按目录时间/最大ID/文件相似度猜方向。

发布关系magic `KCG-PROJECT-PUBLISH-EDGE-V1\n`，长度framing的13字段：origin、B0/B1、outputRoot、两侧source/graph/manifest摘要、context摘要、完整Application计划摘要及subject SymbolId。文件名是规范关系字节SHA；与两侧实际Bundle核对。返回原内容会复用原基线ID，故记录独立关系而非“每个ID唯一父节点”，允许B0→B1→B0及再次使用已见关系。

读取同时验证有界Bundle集合、关系可达性和终态凭据；**每条关系必须有相同binding.publication的COMPLETED凭据**。只放完整候选或外层自洽关系不是已发布证明；ROLLED_BACK凭据不授予候选发布身份。原点无关系支持原Q19基线。Q19inspect与Q20context/plan/verify只读识别此状态，任何active/未知/残留均阻塞，不清空目录；Q19register初次/同ID合同不放宽。旧消费者实调仍拒绝新Bundle/族且不删新日志。

固定上限：历史8个Bundle、32条关系、32份终态receipt、累计64 MiB（保守累计读取/保存证据字节，硬链接也计入）；单事务最多32 UPDATE、256 owned记录、staging合计64 MiB；binding/关系/单日志65536字节，字段沿Q19 16 KiB、输出路径4096 UTF-8字节，日志最多256事件。Bundle/源/生成文件沿Q19原限额。执行准备前核对数量、累计字节及binding估算；目录逐项限额、逐帧编码/读取限额，不先无限收集。达到预算拒绝，不自动GC或声称无限历史；32次真实发布后下一写入拒绝，fresh NoChanges仍允许。

## 4. 物理归属与事务

先完成所有准备、B1保存、B0硬链接备份/候选staging、完整binding与锚；首次输出替换前必须已有可复核证据。更新记录绑定path、实际父目录key、B0/B1物理key与长度/字节SHA，排序集合恰为完整两侧清单差异；binding还绑定根/事务目录/两日志槽物理key、完整发布证据及精确owned域。

本机实证：删除后立即同字节重建会复用inode，单比fileKey不足。因此不可变恢复材料（含旧/新生成文件、Bundle成员/关系及候选源副本）另留**硬链接pins**；日志A/B各留物理anchor，binding也有anchor。原inode仍由锚占用，外部重建不能复用它；原地修改则由字节SHA拒绝。pins随receipt保留并计入预算，不被清理或隐式GC，不能把“相同字节”当归属。

替换前后重新核验根/目录/旧字节/备份/staging。仅接受SecureDirectoryStream、NOFOLLOW、同FileStore硬链接及目录句柄move；叶身份/目标存在条件和已打开祖先在支持检查点重验。无拷贝/覆盖降级。所有文件达B1、Bundle与全受管输出再次核验后，排他硬链接建立CURRENT.new，确认它与B1 pointer锚同物理文件，再由安全句柄move替换已有B0 CURRENT。发布前再次复核全部材料及B0锚，无“先删CURRENT”的方向空窗。

这不是目录级所有读者同时切换，也不是数据库迁移；不合作外部任意竞态或真实断电持久性不由锁/模拟异常证明。

## 5. 日志、恢复与清理

binding magic `KCG-PROJECT-UPDATE-BINDING-V1\n`；日志magic `KCG-PROJECT-UPDATE-JOURNAL-V1\n`。严格大端长度framing、规范重编码、路径单独验证；含` kind=`/状态分隔词的合法路径往返，不按分隔词拆路径。未知/乱序/重复/截断/非法UTF-8/超限/越域owned拒绝。

总体状态图：PREPARED→UPDATING→FILES_DONE→COMMITTING→PUBLISHED→CLEANING→COMPLETED；发布前各允许转ROLLING_BACK→ROLLED_BACK。逐文件STAGED→REPLACING→UPDATED，回滚RESTORING→RESTORED。禁止跳跃、终态追加、发布后回滚、未完成文件伪装完成。每次解码重放全事件，不信终态枚举。

日志以两个预建/锚定物理槽A/B及硬链接selector保存：只写未选择槽，复读完整记录，再排他建立journal.new并move selector；下一记录写一半不毁上一完整记录。pending合法但未发布selector只能显式recover删除，读不推进状态；损坏已选记录明确拒绝，不偷换成另一旧槽。

recover重新从B0保存源与保留candidate.sources编译完整两侧、重建context/计划，核对binding/清单/字节；随后先核验所有相关输出、备份、pins和清理成员，再做任何补偿写入。CURRENT缺失/第三值/外部锚/矛盾日志拒绝。

- CURRENT=B0：反序恢复UPDATE。意图未替换与已替换未记完成均按被锚定的物理key/字节区分；恢复move已完成而状态未记也可续跑。B0全树核验后记ROLLED_BACK并清理本事务新候选/暂存/备份。
- CURRENT=B1：校验完整保存与盘面，只完成发布后清理，绝不反向补偿。
- 清理开始的合法ROLLED_BACK允许部分本事务候选/暂存已消失；未到此终态的缺失Bundle/必要备份拒绝。未知成员/同字节外部替换先拒绝，不删用户文件。

最后保留binding/anchor、候选源副本、pins及两槽/anchors/selector，终态目录安全搬到receipts；没有阻塞下一工作的active日志。清理中再次中断仍有恢复证明。重复handle经复核返回ALREADY_CLEAN，不写盘；后续CURRENT与handle方向不符时拒绝，不猜方向。

## 6. 本机证据与CI边界

新10类54项，最终定向去重162项通过（无fail/error/skip），报告索引见Q21工作单。真实两源布局逐路径/逐字节等于候选从零生成，源目录不可用仍保存/重开，连续两次更新和回返原ID、新context NoChanges/旧请求拒绝通过。旧四fixture字节及V1 golden保持，旧保存/规划/改名/V4/恢复与架构定向通过；CLI和全量留同SHA CI。

冻结两UPDATE矩阵：150应用检查点/64种标签，64缺完整绑定准备态保留拒绝、56回滚、30已提交清理；恢复中再次中断85点（回滚57、提交清理28）全部新实例恢复及幂等。列表/数量/标签命中/双向轨迹SHA均硬断言；合成Compiler仅包内测试可注入，生产默认从原源字节完整编译。真实生成链与合成矩阵分列，不冒称真实断电实验。

CI保留四旧业务IT，新增ProjectWorkflowBusinessConformanceIT与`kcg.project-change-conformance.enabled`：复用同schema/DDL/种子/互斥锁/构建启动/报告redaction，真实多源B0三课程→原工程apply/B1四课程且ART101出现，再从保存B1第二次更新恢复三课程；全部course/student/enrollment行指纹不变。原源目录不可用；明确TEST_FIXTURE_DDL不抵扣初始化/迁移。新IT已编译，**本机未运行**。本机阶段CI/验收未取得；随后受测 `f8a2f92bee936359e819d24c9768e188c9275ca5` 的[CI run37865688743](https://github.com/HoloNova/Software-IR/actions/runs/37865688743)双门各1049/0/0/5、五IT40/61/36/63/36全部PASSED，两类artifact核对；XML1054含后续五IT，995→1049增54恰为10新增类，5 skip仅Windows junction。Q21按原范围验收归档，ADR转Accepted；证据见归档§9–10。G2未关闭，跨文件移动等后续单仍须另审。
