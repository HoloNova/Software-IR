# ADR-021：多文件首次编译、精确源快照与 Graph 版本兼容

- Status: Accepted（负责人确认 Q18 D0–D9；D9 采用 B。Q18 已于 2026-10-05 确认验收归档；不授权 Q19 实施）
- Date: 2026-10-04
- Scope: 同 software 的多文件首次编译/生成；不含多源 Bundle/register/apply、数据库迁移或模块市场
- 工作单与运行证据：[Q18归档](../roadmap/completed/Q18-multi-source-compilation-and-source-snapshot.md) §4、§10
- 关联：ADR-001 的一次 Resolve、ADR-003 的 Application I/O 边界、ADR-020 的 CURRENT 方向均不变。

## 1. 为什么需要新入口与新格式

旧生成请求接受一个源文件，V0_1/V1 Graph 要求声明来源属于同一文件，Bundle 仅保存一份 source.sir。不能靠拼接文本、虚拟行号或把片段位置改写成根文件来实现多文件，也不能以入口文件摘要代表整个工程。

六项开工探针验证了真实课程 AST 分片可复用现有后续阶段，且按 enum/entity/input/view/error/capability 类别再按名字排列时生成字节相同。单纯按名字排序会让 view 在实体字段解析之前被检查而失败；旧单文件顺序不改。探针使用旧 document 包装只为验证阶段能力，最终产品入口使用分别解析的 0.2 根/片段。

新增 GraphVersion 后，旧 planner 的“请求与图版本相同”不再等于“版本受支持”。负责人批准 B：保留一套 Graph API，增加两处明确旧版本准入守卫；不新增第二套图模型，不改计划规则或事务。

## 2. 源与编译合同

### 2.1 输入与导入

- `SirParser.parse` 仍只接受原单文件 0.1；新 `parseProject`/`parseFragment` 接受 0.2。sources/source/imports/import 仍由 IDENT 上下文识别，不新增全局保留字。
- 根提供 software、metadata、target、sources、imports 和 declarations；片段只提供 imports/declarations。路径相对于显式 sourceRoot，不相对于各片段目录。
- `AstSourceUnit.Root/Fragment`、`AstSourcePath` 和 `AstImport` 保留真实 SourceId/SourceSpan；不拼接源码，不重解析组合文本。
- 根显式列出所有源，根自身自动纳入；无 glob、目录扫描、远程依赖或自动加载。每个使用方显式导入目标文件中的顶层声明；无别名、通配符、传递导入或独立文件重名空间。
- 全 software 的声明名仍唯一；自导入、重复/冲突导入、导入环拒绝。单文件内实体相互 Ref 不等于文件导入环。

### 2.2 一次 Resolve

`ProjectSemanticInput` 交给 `SirSemanticAnalyzer.analyzeProject`。先验证清单/导入，再以保留真实节点的工程 AST 跑一次 Resolve。引用权限直接消费 typed reference binding：字段按顶层 owner、枚举成员按枚举 owner 检查，primitive/工作流局部变量不是跨文件声明导入。检查成功后复用 Type/Validate/Normalize，不在下游按名称补绑定。

21 类可跨文件的 ReferenceRole 各有缺导入灵敏度反例。能力移动保留声明/字段/枚举成员目标的 SymbolId；AstNodeId 和依赖 AST 作用域的局部变量 ID 可以变化，不据此宣布 nodeKey/LANG-04 已完成。Lowering/Generator 业务实现不改。

### 2.3 公开首次生成

`ToolchainApplication.executeProject(ProjectToolchainRequest(sourceRoot, entry, outputRoot))` 与原 execute 明确区分。sourceRoot/outputRoot 为绝对规范路径，互不包含；输出根必须不存在。新请求没有 stateRoot、replace policy 或变更计划。

成功返回 `ProjectToolchainResult.Success`：不可变 SourceSnapshot、ExecutionManifest、多源 ProjectGraph 和诊断。失败返回阶段、FailureDisposition 和诊断。读取、Parse、Semantic、Lowering、Generation、Graph/preflight 都在文件落盘前完成，首次落盘复用既有 FileTransaction/FAIL_IF_EXISTS；事务失败继续沿用既有补偿/RecoveryRequired 纪律。无 Bundle/CURRENT/Journal 写入，也不提供多文件 REPLACE_EXISTING。

## 3. 源快照与读取边界

### 3.1 纯数据合同

`SourceSnapshot` 防御性保存全部原始字节，取回字节仍复制；`SourceSetManifest` 仅保存入口、条目及字节证据，Graph 不复制源正文。条目为 SourceId、非负 long byteCount、64 位小写十六进制 SHA-256。按 SourceId.value 的 Java String 字典序排序，与插入顺序、Locale、宿主目录和 mtime 无关；入口必须属于集合，重复/大小写冲突拒绝。

集合摘要是下列 canonical bytes 的 SHA-256，**不是业务身份**：

| 顺序 | 二进制字段（big-endian） |
|---|---|
| 1 | int 源清单格式版本，当前 1 |
| 2 | int UTF-8 字节长度 + entry UTF-8 字节 |
| 3 | int 条目数量 |
| 4 | 每条按路径排序：int 路径 UTF-8 长度 + 路径字节、long 源 byteCount、int 摘要文本长度 + 64 字节摘要文本 |

解码严格 UTF-8，限制长度/数量，拒绝未知版本、尾随字节、非规范路径/排序/重复项及非 canonical 重编码；每路径最多 512 UTF-8 字节，编码也拒绝畸形 Unicode，manifest 解码最多 128 KiB。源清单摘要变化不会隐式转换声明身份。

### 3.2 Application-owned 读取

最多 **128 个文件（含根）、单文件 1 MiB、合计 8 MiB**。`ProjectSourceReader` 从文件系统根逐段打开 NOFOLLOW 的 SecureDirectoryStream，片段也目录相对读取；文件系统不支持该能力时拒绝，不增加不安全 fallback。

拒绝绝对/逃逸/非规范或不可移植路径、重复/大小写路径、硬链接别名、符号链接、目录/设备占位、无可靠 fileKey、非法 UTF-8 与资源超限。读取前后核对 fileKey、size、mtime 及实际字节数；检测到变化拒绝。读取失败指向根中相应 sources 字面量，未能解析根时使用入口起点；Parse/Semantic 错误保留实际文件位置。跨文件 related location 在新 Application 入口以相邻 INFO 记录保留，带原主诊断位置关联；旧诊断格式不改。

之后只编译快照内字节，不重读磁盘。此合同证明“这批字节参与了编译”，**不承诺并发编辑下整个目录有一个原子时间点**；也不承诺检测保存 size/mtime/inode 的任意同尺寸改写。

## 4. Graph V0_2/V2 冻结合同

复用 Graph 结构和四类既有边，新增 GraphVersion.V0_2、ProjectGraphCanonicalFormatVersion.V2；只允许 V0_1↔V1、V0_2↔V2 配对。未知或交叉版本拒绝。V1 字段和字节不改，不放宽旧 PROVENANCE-006/007 规则。

| 位置 | V2 字段与规则 |
|---|---|
| HEADER/GRAPH | formatVersion=2，graphVersion=V0_2；沿用 payloadByteCount/payloadSha256Hex 与既有文本长度 framing |
| PROJECT | 原字段外增加必需的 `sources.base64`（上述 manifest canonical bytes 的标准 Base64）及 `sources.sha256Hex`；入口与 provenance.sourceId 相同 |
| semantic/Lowered | provenance.sourceId 来自真实 span；属于清单、与 span.source 相同，codePointOffset 结束值不大于该源字节数这一保守上界 |
| 有 owner 的 artifact | 使用 owner 的真实源；owner/Lowered/file 的来源关联必须一致 |
| 无 owner 的 ProjectRole artifact | provenance 为入口；保留 Lowering 既有显式 synthetic origin：nodeId=`project`、SourceId=`project`、零位置，不伪装为片段声明 |
| project file | sourceId 来自所归属 artifact，文件字节数/摘要沿用；不任意选择一个片段作为来源 |
| graph canonical digest | source-set 的 Base64 与摘要加入根 provenance canonical framing；任何源正文、路径或导入字节变化都进入图摘要 |

Loader 验证外层 payload 与内层 manifest 摘要/规范编码，再执行完整图校验及 canonical 重编码。Graph 仅承载源证据，不证明外部磁盘上仍有这些字节，也不实现 REFERENCES 边或完整引用图。

新增 `SIR-GRAPH-SOURCES-001..005` 分别负责版本/入口清单、来源不在清单、span/synthetic origin、owner 来源不一致、manifest 解码/摘要错误。旧只读字节码门禁只精确登记无 I/O 的 SourceSetManifest/Entry，未放宽包级依赖规则。

## 5. 消费方支持矩阵（D9-B）

| 入口 | 0.1 / V0_1 / V1 | 0.2 / V0_2 / V2 |
|---|---|---|
| 旧 Parser/execute | 原样支持 | 拒绝，不扫描或拼接多源 |
| parseProject/parseFragment/executeProject | 不当作新项目格式 | 明确支持首次生成 |
| Graph encoder/loader/validator | 原样支持，旧字节不改 | 新字段/来源规则，必须正确版本配对 |
| 普通 Change planner | 原样支持 | 在 COMPAT 准入拒绝，含请求与两张图完全匹配的新版本组合 |
| RenamePlanner plan/verify | 原样支持 | 在 REQUEST 准入拒绝，含伪称 V1 snapshotFormat 或直接 verify 的旁路 |
| 旧 Bundle/注册/上下文/Apply/CLI | 原单文件格式与语义不改 | 不交付多源注册/应用；单源 descriptor 不能偷读 V2 |

两个 sir-change 准入位置只接受 V0_1/V1；Rename plan 和 verify 共用请求守卫。不改声明比较、影响模型、ChangeBaseRevision/ChangePlan、文件事务或恢复。

备选 A（完全隔离的新图类型）未选：会复制公共图合同与 codec。不能改为仅靠新旧版本不相等碰巧拒绝，更不能回退到伪造来源或放宽 V1。

## 6. 验证与未完成项

本机去重后的指定类测试 **346/0/0/0**，详见工作单 §10；其中 Parser 91、Semantic 34、Graph 77、Change 36、Application 108。真实课程四源生成 35 文件，与单文件逐路径/字节一致；缺导入、重复身份、环、类型/关系错误和读取边界均拒绝且不落盘。旧四业务输出摘要及 Q13 六份 V1 快照 SHA 对照 Q17 CI run 36404651840 的固定值一致，golden 未重新生成。

**Q18 同 SHA CI 已通过，2026-10-05 确认验收归档。** 已授权提交推送 `638eaa7f41ff66a1e2eaef57a8ac9679d75d70fd`，[run 37212618580](https://github.com/HoloNova/Software-IR/actions/runs/37212618580) 双全量门各 **936/0/0/5**、四业务 IT **40/61/36/63** 全 PASSED；surefire-reports/conformance-evidence 已下载核对。相对 Q17 +43，上传 XML 940 含四 IT，不冒充门计数；验收与 C1–C6 裁决见 [Q18 归档](../roadmap/completed/Q18-multi-source-compilation-and-source-snapshot.md) §11；本轮仅文档，不修改受测源码或另行提交，不关闭 G2。Q19 多源基线保存/重开仅 SPEC_REVIEW，不用此 ADR 自动授权新格式。

本单不关闭 G2；多源权威 Bundle/Change 基线、moduleInstance/nodeKey、完整旧身份映射、组合改名、数据库物理列继承和 CLI 接线另单。当前只有首次生成公开能力；不能把不可变内存源快照称为已发布 CURRENT 或模块版本锁。
