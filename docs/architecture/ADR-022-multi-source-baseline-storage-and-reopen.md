# ADR-022 多源基线保存、初次发布与独立重开

- Status: **Accepted**（2026-10-05，Q19确认验收）。D0–D7及P1–P5冻结合同、实现/本机定向199（新增33）和受测f3d9ef7同SHA [CI run37256515347](https://github.com/HoloNova/Software-IR/actions/runs/37256515347)双门969/0/0/5、四IT40/61/36/63已通过，artifact核对；验收/裁决见Q19归档§10，不授权下一单。
- Authority: [Q19归档](../roadmap/completed/Q19-multi-source-baseline-storage-and-reopen.md)。不改变 [ADR-021](ADR-021-multi-source-compilation-and-graph-compatibility.md)、旧 V1 Bundle、ChangeBaseRevision 或 V4 工程事务。

## 1. 已有证据

ProjectBaselinePrerequisiteTest 6/6（本机受限指定类，`/tmp/q19-prerequisite.log`）：四源课程原字节4657、manifest413、源容器原型4816、V2图108687、旧受管manifest块10109字节，35个生成文件。移开原源目录后从快照复用原Semantic/Lowering/Generator/Graph得相同字节，不写盘。源码成员闭包由既有ProjectSourceResolver拒绝。既有锁的tryAcquireExisting不建/改文件；硬链接排他发布完整指针、已有指针不会覆盖。

探针只是共享编排/文件系统原语的证据，不替代新格式、公开注册/重开或完整故障矩阵。首轮脚手架方法名、片段缺imports块/DiagnosticCode访问错误已订正，不修改产品规则。

## 2. 接口与复用

Application 新增 ProjectBaselineApplication：register(ProjectBaselineRegistrationRequest)、inspect(ProjectBaselineInspectionRequest)。前者输入不可变SourceSnapshot/outputRoot/stateRoot；后者显式expectedBaselineId，不读原源目录。返回新的项目基线结果/凭据，不构造单源ChangeBaseRevision、不提供ChangeTarget目录。

共享Application内ProjectCompilation：从已解析Loaded或原字节SourceSnapshot做一次Semantic，再原Lowering/Generator；新注册和重开调用同一编排。首次生成仍读物理源，新入口只消费内存/Bundle原字节。不复制业务实现，不通过executeProject重新写盘。

## 3. 目录与新版本

stateRoot保持LOCK、baselines/<64 hex ID>、CURRENT，以及发布期间CURRENT.new。新Bundle精确包含：descriptor.kcg-baseline、sources.kcg-source-set、graph.kcg-psg、baseline-id.kcg-pointer，其他对象拒绝。最后一项为descriptor推导的65字节规范ID指针，作CURRENT.new/CURRENT的物理归属锚；不只靠相同字节推断未发布指针所有权。格式由独立magic/domain区分，旧Store仍精确要求source.sir，旧枚举仍仅V1。

源容器magic `KCG-SOURCE-PAYLOAD-V1\n`；UTF-8路径用4字节大端字节长度+原字节，入口后为4字节文件数，再按SourceId.value严格递增的条目：路径、8字节原字节数、原字节。没有拼接或宿主路径，BOM/CRLF完整保存；解析字节单独严格UTF-8。解码拒绝负数/越界/重复/乱序/尾随、错误入口及非规范编码，并重编码逐字节核对。

描述符magic `KCG-PROJECT-BASELINE-V2\n`，固定字段顺序的大端framing：格式号2、boundOutputRoot、entry、源集合SHA、源容器长度/SHA、graphVersion V0_2、graphCanonicalDigest、graphSnapshotFormat V2、图字节长度/SHA、targetId、loweredIrVersion、manifestDigest、受管条目数；条目严格路径递增，字段为path、字节数、SHA、artifactId、owner存在标记及可选SymbolId。路径/版本/hex/条目都独立验证。所有字符串长度受限，严格UTF-8且无NUL；解码后完整重编码相等。

baselineId = SHA256(`KCG-PROJECT-BASELINE-ID-V2\0` + 完整规范descriptor原字节)。manifestDigest复用旧纯数据manifest framing函数；不修改旧编码器/描述符/ID含义。源集合、sourcePayload、图的长度/摘要均不互相替代。

图仍Q18 V0_2/V2；graph.sourceSet须等于重新计算的snapshot.manifest，descriptor target/IR/来源/manifest须等于原字节独立重编译结果。不能只做外层SHA或信任调用方清单。outputRoot参与基线身份，不支持异根自动重绑定。

## 4. 资源与读取

保留Q18：最多128源、单源1 MiB、总原字节8 MiB、源路径512 UTF-8字节。容器上限8 MiB+128 KiB；描述符2 MiB；图16 MiB；受管条目8192、单个生成文件8 MiB；一般字段16 KiB、输出路径4096 UTF-8字节。实际四源新descriptor约10.8 KiB（临时outputRoot不同长度会差几字节），payload4816、图108687字节；不把原型/旧manifest块冒称新descriptor。编码逐字段/条目限额，超限立即停止；非法代理字符拒绝，不替换。目录最多枚举8项，不能先收集无限脏目录再检查。

所有文件先限额再读取，目录自文件系统根逐段SecureDirectoryStream/NOFOLLOW锚定；叶文件必须可辨识普通文件，打开NOFOLLOW，读中限额，前后size/mtime/fileKey一致。没有不安全fallback。状态与输出先复用既有path guard，输出核验使用同样受限安全读取，不把旧OutputManifestVerifier.readAllBytes当新入口读取证据。

锁保证工具间串行，不承诺对任意不合作并发修改的目录级原子快照。变更/符号链接/外部占位必须按支持的检测点拒绝；写操作重验根身份及路径。不得把锁或摘要当作外部状态认证/任意并发防护。

## 5. 发布与收尾（无新的工程事务日志）

只允许无CURRENT的初次注册，或同ID的幂等复核。已有旧格式/不同ID拒绝。拒绝状态中的旧transactions与未知对象，不调用JournalGate.inspect，因其可能清理空目录。

1. 完成内存重编译、候选编码和实际受管文件验证；取得/创建明确状态锁，不修改工程。
2. 新候选目录排他创建，四文件排他写入/SYNC；已有候选只能完整安全加载、字节相同并重新编译/核验才能复用，不能覆盖。
3. 完整重新加载Bundle并再次核验输出、CURRENT条件。
4. CURRENT.new排他硬链接到Bundle中的baseline-id.kcg-pointer；残留必须是完整目标指针、候选已核验且与该锚同一物理文件才能复用。指针锚本身写入也属于部分写入/完整孤包故障矩阵。
5. 硬链接CURRENT到CURRENT.new进行排他发布，已有CURRENT绝不覆盖；同一FileStore不支持原语即拒绝，无原子move覆盖fallback。
6. 只在CURRENT、CURRENT.new和Bundle指针锚三者确为同一物理文件、字节为目标指针时删除CURRENT.new。Bundle永久保留，不删除半写候选、未知/外部文件或任何已有基线。

中断前CURRENT缺席；完整孤立Bundle可同请求复核后发布。半写Bundle/部分CURRENT.new/不同ID/未知对象缺足够证据时拒绝并保留，**不承诺自动修复全部半写数据**。指针已发布、CURRENT.new还在时，同请求重新核验全部证据、同物理身份后收尾；外部替换为相同字节但不同inode也不能删除。CURRENT已发布且无残留时，重复注册仍核验Bundle与输出再返回AlreadyRegistered。

这是初次保存的拒绝/重试合同，不是旧recover或Q17 V4恢复的扩展。部分证据需要负责人显式处置时保留在请求的stateRoot/baselines/候选ID，诊断说明缺失/损坏成员或读取失败位置，不靠“像哪一版”推断。

## 6. 重开与诊断

inspect要求state/LOCK已存在，仅tryAcquireExisting，绝不创建锁或状态目录。脏状态包括CURRENT.new、transactions、未知对象；只报告不清理。读取CURRENT→期望ID→完整Bundle→从保存原字节重编译/生成→核对图/manifest及实际工程；成功返回Verified和不可变原字节快照/图。

新基线诊断前缀 `SIR-APP-PROJECT-BASELINE-`：REQUEST-001（请求/绑定）、VERSION-001（错误/未知版本）、FORMAT-001（编码/结构/内部证据不一致）、LIMIT-001（限额）、READ-001（安全读取失败）、STATE-001（阻塞/中断/未知对象）、OUTPUT-001（盘面）、PUBLISH-001（保存发布）。复用路径/锁诊断并保留编译阶段的源位置/related INFO；失败不静默转成成功或旧凭据。拒绝过程中可留下LOCK/已写候选证据，不声称state零写；inspect与工程/已有CURRENT/Bundle禁止隐式修改。

### Q21有界历史读取增量（2026-10-08，待CI/验收）

Q19原注册/初次发布/四成员字节与ID域不变。Q21仅给inspect及Q20读入口增加可验证的有界历史：origin、不可变关系与终态receipts，CURRENT仍唯一head；每条关系需匹配COMPLETED binding及两侧实际Bundle，未知/active/残留保留拒绝，不清理。pins防同字节外部替换，保留恢复证明计预算，不自动GC。新UPDATE/指针替换/显式恢复归独立[Proposed ADR-024](ADR-024-project-workflow-update-publication-and-recovery.md)，不是旧recover/JournalGate扩展。register不因历史而放宽为通用更新；新入口本机162项/新54通过，双门/五IT仍NOT_RUN，不抵扣Q19之外完整资格。

## 7. 验收

真实四源新实例重开；容器/descriptor严格正反/边界；注释灵敏度；重算外层摘要仍拒绝语义/图/清单不一致；源/输出链接与替换、限额、版本交叉、幂等、全部发布钩子+部分文件崩溃像、不同CURRENT及未知文件保护。旧字节/注册/规划/应用/恢复定向回归，完整门由同SHA CI承担。实测四源重开、15点发布矩阵（列表长度/到达数钉住）、缺席/已发布双向重试和外部同字节指针保护均通过，见Q19归档§9；旧context/plan/apply/applyRename/recover全部实调拒绝新格式且盘面不变。本机指定类证据不替代同SHA CI；后续同SHA双门/四IT已通过，负责人已确认验收，ADR转Accepted。完整证据、资源/编码/初次发布的边界及裁决保留于Q19归档§9–10，G2未关闭。
