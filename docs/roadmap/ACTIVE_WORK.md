# 当前工作单：Q18 G2 第三切片——多文件编译输入与完整源快照

- 状态：**`AWAITING_CI`**（2026-10-04）。D0–D9-B 范围内实施与本机定向验证已完成：去重 346/0/0/0；双全量门与四业务 IT 均 NOT_RUN，不能引用 Q17 旧 CI 替代。主会话直接完成，未提交/推送，待负责人明确授权后由 CI 验证本批 SHA；不改计划/影响算法或执行事务。
- 所属阶段：**G2：持久身份与受控模块**；本单不关闭 G2，不进入 G3。
- 前置：Q16 持久身份与只读计划、Q17 单能力改名应用已验收；Q17 归档件 [`completed/Q17-single-declaration-rename-transaction.md`](completed/Q17-single-declaration-rename-transaction.md)。受测源码 `ab2ce09decce7b3f157ea1a52f1cc54bff5e4d7b`，CI run `36404651840` 双闸门各 893/0/0/5。
- 当前工作区：HEAD `ab2ce09`；Q17 收口的七个文档尚未提交。本轮在其中更新本文件与三份状态镜像，不覆盖 Q17 归档或验收证据。
- 方向依据：[`../design/02-sir-language-and-modules.md`](../design/02-sir-language-and-modules.md) §1–2、§8；[`../design/07-validation-and-direction-roadmap.md`](../design/07-validation-and-direction-roadmap.md) LANG-01..05 与 G2 行。
- 开工时仅有读码证据；§4.1 保留当时的先决探针/阻断记录，§10 记录当前实现与运行结果。接口、资源及编码合同冻结于 Accepted ADR-021，工作单尚未完成 CI 与最终验收。

## 1. 场景与本单结果

今天课程、学生、报名及检索写在一份 SIR 里。目标是把声明拆开：

```text
sir/
  project.sir                 # 一个 software、metadata/target、源清单和根声明
  modules/course.sir         # Course 定义
  modules/student.sir        # Student 定义
  modules/enrollment.sir     # Enrollment/状态枚举，明确引用 Course/Student
```

这些文件仍属于**一个 software、一个生成工程**，不是合并几个工程，不是模块市场。各文件分别解析；Resolve 一次检查全部声明及显式跨文件引用；后续沿用 Type/Validate/Normalize/Lowering/Generator。**不产生拼接后的大 SIR 文本，不重新解析拼接文本，也不把所有 SourceSpan 改成入口文件。**

本单成功后：给出入口 `project.sir`，可在一个新的输出目录中生成完整课程应用，结果与对应单文件业务源一致；错误指向真正出错的文件；源快照记录实际参与编译的全部原始文件字节及摘要，Graph 保留真实来源。

**本单不交付多文件基线注册/增量变更。** 现有 Bundle/ChangeBaseRevision/Journal 都绑定单文件，需要下一张单显式处理格式与兼容，不能用“入口文件摘要”假装覆盖全工程。Q18 的源快照是编译输入证据，不是已发布的 CURRENT 基线。

## 2. 核对到的事实与硬限制

| # | 读码事实 | 直接依据 |
|---|---|---|
| F1 | 公共生成请求只含一个物理 `sourceFile` 和一个逻辑 `SourceId` | `sir-toolchain-application/.../api/ToolchainRequest.java`；`ToolchainApplication.execute` |
| F2 | 两个内部编译入口均接受单个文本，Parser 返回一个 AstDocument，Semantic 分析一个 AstSoftware | `internal/SirCompilation.java`、`internal/SirCompiler.java`；`sir-semantic/.../api/SirSemanticAnalyzer.java` |
| F3 | Grammar 的 document 只接受 `sir <version> software ...`；无源清单、声明片段或 SIR import 语法 | `sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4`；`internal/DefaultSirParser.java` |
| F4 | SourceSpan 已带独立 SourceId，可表达不同文件的诊断位置；无需用虚拟行号修补 | `sir-parser/.../source/SourceSpan.java` |
| F5 | AstNodeId 以 SourceId 为根，即便能力有 @id，跨文件移动仍改变 AST 身份；SymbolIdFactory 的名称/显式 ID 均不编码文件路径 | `sir-parser/.../internal/AstIdFactory.java`；`sir-semantic/.../internal/SymbolIdFactory.java` |
| F6 | Resolve 用全 software 的 projectNames 注册声明并拒绝全局重名；只有能力和实体成员支持显式 @id，不能假设 Entity/Input/模块实例都有持久身份 | `sir-semantic/.../internal/ResolvePass.java`；当前 Grammar |
| F7 | GraphInput、GraphBuilder 给各类 provenance 写同一个 SourceId；Validator 要求所有 provenance 和大多数 span 都等于项目 SourceId | `sir-project-graph/.../api/ProjectGraphInput.java`、`ProjectGraphBuilder.java`、`ProjectGraphValidator.validateProvenanceConsistency`；`SIR-GRAPH-PROVENANCE-006/007` |
| F8 | 图模型版本只有 V0_1，规范序列化格式只有 V1；Encoder 明确拒绝其他格式 | `GraphVersion.java`、`ProjectGraphCanonicalFormatVersion.java`、`internal/SnapshotEncoder.java` |
| F9 | BaselineBundle 保存一个 sourceBytes，BundleStore 读 source.sir；描述符与 ChangeBaseRevision 各只有一个源 ID/摘要 | `application/internal/bundle/BaselineBundle.java`、`BaselineBundleStore.java`、`BaselineDescriptor.java`；`sir-change/.../api/ChangeBaseRevision.java` |
| F10 | 现有 FileTransaction 只负责生成清单，不清理旧文件；Q17 applyRename 也只面向单文件 Bundle | Q17 归档与 `ToolchainApplication.execute`、`ChangeExecutionApplication.applyRename` |
| F11 | 普通 SourceReader 严格解码 UTF-8，但直接 readAllBytes；SourceId.of 还会规范化反斜杠，不等于物理路径安全读取器 | `application/internal/SourceReader.java`、`sir-parser/.../source/SourceId.java` |

没有现成多文件 SourceSnapshot/清单/模块实例实现可以直接接线；现有 `RenameSourceSnapshot` 只是一个文件的 ID、长度与摘要，不保存多文件内容。

## 3. 建议冻结的最小产品范围

### 3.1 同一个 software 内的源分片，不提前做模块市场

- 根文件独占 software 名、metadata、target；其他文件只提供声明片段，不各自重复 software 或 target。
- 根显式列出全部源；不递归扫描目录、不支持 glob、远程依赖或“找一个同名文件试试”。未列文件不能被 import 偷偷加载。
- 跨文件引用必须显式导入，文件内引用及 primitive 类型照旧；导入只指向同一源清单中的文件及其指定顶层声明，无通配符、别名、转导出或隐式传递导入。
- 当前 software 全局声明名仍必须唯一；不引入各文件独立重名空间。此处只控制跨文件可见性，**不是完整 module/export/private 字段契约**，模块实例及私有字段封装仍留后续。
- 所有类型/实体/错误等引用统一由 Resolve 绑定一次，Type/Normalize/Lowering 不再按名字补救，遵守 ADR-001。

### 3.2 已冻结的 0.2 源表面

以**新项目输入版本**开启多文件表面，不更改既有单文件 0.1 的解释。以下展示源组织；可执行完整 fixture 见 `sir-toolchain-application/src/test/resources/valid/multi-course/`：

```text
// project.sir：一个 software 的根
sir 0.2
software CourseAdmin {
  metadata { /* 沿用现有字段 */ }
  target { /* 沿用现有目标 */ }
  sources {
    source "modules/course.sir";
    source "modules/student.sir";
    source "modules/enrollment.sir";
  }
  imports {
    import Course from "modules/course.sir";
    import Student from "modules/student.sir";
    import Enrollment from "modules/enrollment.sir";
    import EnrollmentStatus from "modules/enrollment.sir";
  }
  declarations { /* 原业务的 input/view/error/capability */ }
}

// modules/enrollment.sir：声明片段
sir 0.2
imports {
  import Course from "modules/course.sir";
  import Student from "modules/student.sir";
}
declarations { /* Enrollment 与状态枚举的既有声明体 */ }
```

- 所有路径相对于显式 sourceRoot；SourceId 是该根下的规范相对路径。不是相对于每个文件的目录逐级猜测，根文件自身自动纳入清单。
- 根与片段版本必须一致；片段不允许 source 清单、software/metadata/target。不混入另一个工程根。
- **0.2 仅在 parseProject/parseFragment 新入口支持**，旧 parse/execute 仍走 0.1。source/import 等结构词由 IDENT 上下文识别，不扩大旧保留字；旧语言 84 项与新语法/快照 7 项共 91 项通过。
- 文件导入图首版拒绝环。课程检索/投影可放根文件，由根导入实体与报名定义；单文件内原有实体互相 Ref 不被误判成文件导入环。
- 不引入 modules.lock.json 的模块版本/实例绑定。本单的 SourceSnapshot 自己保存实际读到的精确字节及摘要；模块版本锁是后续完整模块协议，不能冒称被此快照替代。

### 3.3 SourceSnapshot：固定这次实际编译了什么

纯源契约层已定义不可变 SourceSnapshot 及只保存证据的 SourceSetManifest，包含：

- 明确格式版本与入口 SourceId；
- 按规范路径排序的每项 SourceId、原始字节、字节数和 SHA-256，防御性复制；
- canonical 集合摘要：版本/入口/条目数量及逐条路径、长度、摘要用明确长度 framing，不拼无边界字符串；不编码宿主绝对路径、mtime、Locale 或遍历顺序；
- 根与片段中解析出的 sources/imports 归属于其自身 AST/SourceSpan，不能只留一份无位置的文本列表。

读取后只从快照字节解析，不在后续阶段重读源文件。快照保证“编译用的是这批字节”，**不承诺用户并发编辑时整个目录存在一个原子时间点**。读取阶段发现不一致应结构化拒绝；目录级锁或外部模块版本锁不是本单承诺。

任何参与文件的注释/排版字节变动也改变 SourceSnapshot 摘要，即使生成 Java 不变。列表重排但集合与字节相同不改变快照摘要；移动文件会改变源集合摘要与来源位置，不改变其声明 SymbolId。不能把集合摘要当作业务身份。

### 3.4 编译与首次生成

- 新的类型化多文件请求/入口与 `ToolchainRequest` 明确区分；旧入口继续走原 0.1/V0_1/V1 路径。新入口仍归 ToolchainApplication 编排，不在 CLI 或 Generator 读文件。
- 各文件分别 Parser → 保留完整来源的工程 AST → Resolve 全项目注册/可见性检查 → 原有四阶段后续 → 一个 normalized/Lowered 目标模型 → 一组 GeneratedFile → 一张多源 Graph。
- 同一快照的 file/declaration 次序固定，不能由 DirectoryStream、HashMap 或请求列表偶然决定；旧单文件声明次序不改。具体次序及与既有产物逐字节等价由 P3 先验证，再冻结。
- **只允许首次生成到新的输出根**（默认 FAIL_IF_EXISTS）。不得提供多文件 REPLACE_EXISTING 来冒充工程更新；不调用单文件 Bundle 注册或 Q17 事务，不写 CURRENT/Journal。
- 复用既有生成路径预检与 FileTransaction 的首次落盘纪律；多文件读、Parse、Semantic、Graph 失败必须发生在生成文件落盘之前，无候选工程/半包。

### 3.5 Graph 必须版本化，不能放宽 V1

已新增多源 GraphVersion.V0_2 及 canonical 格式 V2，不把新字段偷偷写进 V1，也不删除 V0_1 的 PROVENANCE-006/007 规则。

多源 Graph 的项目证据应携带入口与完整源清单/源摘要；声明/Lowered origin 的真实 SourceId 必须属于清单并与其 span 一致，不能把入口 SourceId 盖在每个节点上。生成文件本身没有唯一源时按项目/owner provenance 区分，完整具体规则必须在 P4 的字段表中冻结。

旧 Graph/Bundle 保持解码和字节原样；新 Graph 不能被旧单文件注册、Change/Rename 或 CLI 入口当作 V1 继续运行。新增版本会触发已有“枚举只有一个值”的兼容绊线测试，需显式更新成真正的新/旧支持与拒绝矩阵，不能删除守位测试。字段、digest/canonical 编码及拒绝边界须先写 Proposed ADR 并随工作单审定，再实施格式。

## 4. 开工前设计/探针门（历史探针见 §4.1，当前结果见 §10）

| # | 需验证/冻结的问题 | 直接输出与停轮条件 |
|---|---|---|
| P1 | 0.1 与新项目/片段入口的 grammar/header/lexer 能否隔离 | 旧关键字作 IDENT 的真实合法源仍通过；新入口才允许 sources/imports。若必须破坏旧解析面，停轮，不靠修改旧断言蒙混 |
| P2 | Resolve 全类型/工作流引用是否都能按引用节点所属源检查导入 | 逐 ReferenceRole 列覆盖清单；真实缺导入/错目标反例在 Resolve 唯一报错，Type/Normalize 不重查名字。需要变更 role 时同步契约测试 |
| P3 | 根/片段声明的确定性归并能否保持既有业务产物字节 | 把课程 fixture 拆成真实文件，比较单/多文件生成路径与逐文件字节；若重排触发字节变化，定位原因后冻结口径，不把差异自动当可接受 |
| P4 | V0_2/V2 provenance/source-set 与旧 V0_1/V1 拒绝边界 | 出具完整字段、canonical framing、跨度所属规则与版本路由表/Proposed ADR；必须验证旧消费者不会漏验或误解读，不先预设现有编码可复用 |
| P5 | @id 声明跨文件移动后的实际身份范围 | 同能力 SymbolId/binding 目标保留，SourceSpan 指向新文件；AstNodeId/Graph digest 可随来源改变，不能用缓存旧 AST 假装 LANG-04 已完成 |
| P6 | 源集合读取的路径/资源/变化边界 | 源根和逐段 NOFOLLOW、重复路径与规范别名、strict UTF-8、数量/单文件/总量上限；反例失败前后输出与状态盘面不变。限额的具体值先登记再测 |

P1–P6 属获准实施后的先决工作。若核查推翻 §3 的范围/契约，先报告，不让实现自行换方案。

### 4.1 开工时真实探针及 P4 设计阻断（2026-10-04，历史记录）

**以下保留实施前状态，P4 已由负责人批准 D9-B 解除，不作为当前状态。**

新增 `sir-toolchain-application/src/test/java/io/kcg/sir/application/MultiFilePrerequisiteProbeTest.java`，**6/0/0/0，BUILD SUCCESS**。它分别用当前合法 0.1 document 包装解析片段，再归并真实 AST；不是已实现的新片段语法或多文件产品入口。

- P1：`sources/source/imports/import` 当前是合法 0.1 IDENT；已用真实 Parser 钉住，0.2 入口策略尚未实施。
- P2：跨文件 typed binding 的源/目标位置可找到所属声明；课程场景覆盖 9 类跨文件角色，完整 role 逐项拒绝矩阵仍待实现。
- P3：只按名称归并会先解析 view 再解析实体字段，误报 Enrollment 无 Course Ref。首跑 5 项中 4 项因这个相同前提失败；已保留名字排序反例。**新工程归并按 enum/entity/input/view/error/capability 类别，再按名字**时，课程文件路径与逐文件内容和单文件相同；旧声明次序未改，不改 Lowering/Generator。
- P4：现有 GraphBuilder 对真实异源 span 报 PROVENANCE-007，必须版本化。另经读码确认 PlannerCore 只比较图/请求版本相等，没有 V0_1 支持范围检查；RenamePlannerCore 限制 V1 snapshotFormat，但 GraphVersion 同样只做相等检查。**当前只有 V0_1，不冒称 V2 已可输入或已有漏洞**；新增 V0_2 后的消费者范围必须另补边界。当前禁止 sir-change 生产改动，D9 待裁定。
- P5：@id 能力移至 modules/search.sir 后 SymbolId 不变，AstNodeId 与 SourceSpan 改变，生成文件仍逐字节一致；不抵扣 LANG-04。
- P6：多源安全读取、数量/字节上限及异常矩阵未实现/未运行，仍 NOT_RUN。

本机实测 nproc=2、free -m 可用 2094 MB；单任务命令为 `systemd-run --scope -q -p MemoryMax=1500M -p CPUQuota=125% -- env MAVEN_OPTS='-Xmx256m' mvn -B -o -Dmaven.repo.local=/root/.m2/repository -pl sir-toolchain-application -am test -Dtest=MultiFilePrerequisiteProbeTest -Dsurefire.failIfNoSpecifiedTests=false -DargLine='-Xmx384m'`，只跑此探针类（上游无测试匹配），最终约 19 秒；日志 `/tmp/q18-prerequisite.log`。未跑全量门、业务 IT 或新生成工程 Maven 构建。

新格式/旧消费者方案见 Proposed [`../architecture/ADR-021-multi-source-compilation-and-graph-compatibility.md`](../architecture/ADR-021-multi-source-compilation-and-graph-compatibility.md)。目前新增仅测试与设计文档，没有生产实现。

## 5. 决策 D0–D8（负责人已批准；实际格式/接口见 ADR-021 与 §10）

| # | 推荐 | 理由 / 不选的方向 |
|---|---|---|
| D0 | Q18 只交付多文件首次编译/生成与完整源快照/多源 Graph；多文件 Bundle/变更基线另单 | Bundle 是源/图/清单的权威持久证据，F9 不能靠入口摘要修补；不把生成合格当变更可用 |
| D1 | 根 sources 显式清单；每个使用方显式 import；不扫描目录、不隐式跨文件全局可见 | 补文件不能静默改变名字绑定；当前单 software 的全局唯一名字仍保留，不预先铺完整模块实例体系 |
| D2 | 独立的新项目输入版本与声明片段，旧 0.1 契约保留 | 只有根提供 target；不要求每个片段伪装为另一个 software；新增词不能窃取旧 IDENT |
| D3 | 各文件分别解析，工程 AST 保留原 SourceSpan/节点，Resolve 注册与绑定一次 | 不拼接源、不生成虚拟行号，不在下游再次解释导入 |
| D4 | 多源 Graph 新模型/格式，先 Proposed ADR 与完整版本矩阵 | V1 明确拒绝异源 span，直接放宽会重解释旧快照；旧字节、身份命名空间及 consumer fail-closed 保留 |
| D5 | SourceSnapshot 保存原始字节与 canonical 集合摘要，纯数据层无 I/O；Application 读取文件 | 复用 SourceId/SourceSpan，业务身份不依赖路径，摘要仍诚实感知路径与所有文件变化 |
| D6 | 首次输出限新目录，不改旧生成/注册/apply/恢复入口的含义 | REPLACE_EXISTING 会留旧产物，Q17 不支持多源 Bundle；不绕过状态发布纪律 |
| D7 | 本单只验证跨文件能力 SymbolId 与引用目标稳定，不承诺 AST 引用点稳定 | AstIdFactory 仍以 SourceId 为根；nodeKey、Input/Entity 声明 @id 与完整旧身份映射另单，不顺手更换全语言身份 |
| D8 | 本机只跑受影响定向；双全量门与四业务 IT 留提交后 CI，旧源 V1 同源码比对 | 不启动重型参考环境；不为触发 CI 自动提交/推送；必须绑定最终 SHA/artifact |

本单的跨文件显式 import 为必要最小能力，不等于主设计的完整 moduleInstance/export/private 字段/参数化绑定。此限制必须写入最终发布支持与资格记录。

### D9：P4 兼容边界裁定（已批准 B，2026-10-04）

- **推荐 B**：同一 Graph API 增加 V0_2/V2，同时允许 sir-change 两个 planner 的请求/兼容入口增加固定 V0_1/V1 准入守卫与拒绝测试；不改 ChangeBaseRevision、计划/影响/目标算法或执行事务。必须在声明比较前结构化拒绝“请求与图都同为新版本”的匹配组合，也拒绝伪称 V1 snapshotFormat 的新图。
- **备选 A**：使用与 ProjectGraph 完全隔离的多源 Graph 类型/版本，使旧 ChangePlanningInput 在类型层不能接受，sir-change 完全不改；代价是额外图合同/codec，后续多源变更对接更重。
- 两种都不能回退成把异源 span 写成根文件、放宽 V1 Validator 或依靠版本不相等碰巧拒绝。负责人已选择 B，允许本单在 PlannerCore/RenamePlannerCore 两个准入位置加守卫与对应拒绝测试；其他禁止范围不变。

## 6. 验收场景（每项都有正反或灵敏度证据）

1. **课程真实纵向**：同一份已验收业务拆成根 + Course/Student/Enrollment 三片段；Parser→Semantic→Lowering→Generator→Graph→新工程落盘。输出路径/逐文件 SHA 与对应单文件相同，生成的是一个应用，不是多工程拼接。
2. **快照完整性**：每个参与文件原始字节与磁盘本次读值相同；移动/删文件、改其中一个注释、改 imports 会改变应改变的集合证据；打乱输入列举次序不影响 canonical 摘要/输出，未列文件新增不改变任何结果。
3. **引用可见性**：同源引用正常、正确导入正常；缺导入、指错源、源未列、声明不存在各按明确阶段/code/数量/SourceSpan 拒绝，相关位置可指向另一文件。不得按全局同名声明自动修复。
4. **重复/环**：跨文件同名和重复 @id 明确诊断两侧来源；自导入/文件导入环拒绝；单文件合法实体互相 Ref 仍保持原行为。错误发生后不生成空工程。
5. **移动身份**：仅把一个带 @id 的能力移到另一片段并更新清单/imports，能力 SymbolId 与声明/字段/枚举成员引用的绑定目标相同，诊断位置属于当前文件；Graph/source-set 摘要诚实变化。工作流局部变量 ID 仍依赖 AST 作用域，另归 nodeKey，不冒称全部引用点身份稳定。可加改名候选验证生成路径更新，但不调用多源 apply。
6. **Type/Validate 不被绕过**：跨文件 Date/Int64 不合法比较、既有关系基数错误仍由相应阶段给诊断，不让 import/归并把类型错误变成名称修复；失败无输出。
7. **版本与 provenance**：V1 旧字节原样 roundtrip；V2 完整多源 roundtrip 与 canonical 确定性；伪造清单成员/异源 span、删字段/改格式/未知版本拒绝；旧消费者遇 V2 不产生 CURRENT/Bundle/Journal 或生成变更计划。版本分支灵敏度必须驱动真实 encode/load/validate 路径，非仅源码字符串扫描。
8. **路径/读取反例**：绝对/逃逸/规范别名重复/符号链接/目录占位/非法 UTF-8/数量与字节超限，按故障位置明确拒绝；输出根与状态根快照不变；不靠隐藏文件扫描发现依赖。
9. **旧语言/工程兼容**：既有单文件源、@id/单声明改名计划及 Q17 apply/恢复定向回归；四业务 fixture 的生成组合摘要保持同源一致；两条全量门与四业务 IT 同 SHA CI 全绿，既有 skips/excludes 不增加。

设计和示例本身不是测试通过。计数按当次执行证据；尚未执行的门记 NOT_RUN。Graph 新格式的具体 diagnostics 和 READ/PARSE/RESOLVE 归属需先登记诊断表，不虚构现有错误码已能表达新情况。

## 7. 允许与禁止范围

**允许（批准后）**：sir-parser 的版本化项目/片段 AST 与源契约；sir-semantic 的工程输入与 Resolve 导入可见性；sir-project-graph 的多源模型/validator/codec（经 ADR 审定）；sir-toolchain-application 的读取快照/编译编排/首次生成；受影响测试与文档。

**默认不改**：Lowering/Generator 业务实现与 SQL；允许检查公共模型是否兼容，若实测必须改变其业务规则或字节口径，先停轮报告。不复制一套目标业务渲染器。

**禁止**：除 D9 明确批准的两处旧版本准入守卫外的 sir-change 生产语义、ChangeBaseRevision/Bundle/CURRENT/Journal/V4 格式或发布/恢复路径；多文件 register/apply；组合改名、数据库物理列/DDL/G3；CLI 接线、Web、模块市场/远程依赖、自动依赖扫描；moduleInstanceId/nodeKey 和全语言 @id 格式升级；重写 Lowerer 反编译主体；未经授权提交/推送或破坏性 Git 操作。

## 8. 实施次序与测试节奏（已批准，仅限本单范围）

1. 审定 D0–D8，完成 P1–P6 的 API/版本/provenance/兼容草图与限额表，写 Proposed ADR；先锁定范围，不能把未测假设写成事实。
2. 用真实反例给源快照与逐文件解析补定向测试；再实现最小根/片段输入。旧入口不变。
3. 以真实缺导入/重复声明/移动身份做 Resolve 合同；保持 ADR-001 的一次绑定，继续沿用后续语义阶段。
4. 先做 V1 兼容灵敏度与 V2 多源损坏矩阵，再实现 Graph 版本路由；未通过前不开放多文件生成入口。
5. 接通首次生成端到端，对比单/多文件输出，检查拒绝路径零写入；回填完成门，状态推 AWAITING_ACCEPTANCE 或 AWAITING_CI。
6. 负责人显式授权提交/推送后，CI 对最终 SHA 跑一次双闸门与四业务 IT，核对 run/artifact；通过后再申请验收。

本机只跑受影响单模块/指定类测试，遵循 AGENTS.md「测试资源约束」。确需本机重活先按 nproc/free 预算并包 systemd-run --scope 的 MemoryMax/CPUQuota，无 Maven -T、不并发。纯文档期仅差异/链接检查，不跑构建。

## 9. 与 G2 门及后续候选的关系

本单补多文件输入、最小显式导入和跨文件能力身份/来源证据；**不抵扣** LANG-04 nodeKey 稳定、完整旧身份映射、模块实例/物理名称继承、多源 Bundle 兼容，更不关闭 G2。

后续候选均未授权：

1. 多文件权威基线：SourceSnapshot 持久化进新版 Bundle，Change/Rename 绑定全部源，显式旧基线兼容与恢复协议；不是把原 source.sir 替换为拼接文本。
2. nodeKey、模块实例、完整引用闭包与身份兼容；每项以主设计门对照，不以 Q18 源快照摘要替代声明/引用身份。
3. 能力+Input 组合改名、字段物理列继承/G3、Q12 路由模板、BIZ-07 起报名业务；另有独立项沿用路线图，不属于本单。

## 10. 实施与本机验证记录（2026-10-04；AWAITING_CI）

### 10.1 已交付范围

- 0.2 根/片段独立 Parser 入口、带真实位置的 AST、不可变 SourceSnapshot/SourceSetManifest；旧 parse 和 0.1 IDENT 不变。
- 显式 sources/imports、类别再按名称的确定性归并、一次 Resolve 的 typed binding 可见性检查；复用 Type/Validate/Normalize/Lowering/Generator，不改 SQL 或目标业务实现。
- V0_2/V2 图与完整源集合证据、真实 owner/字段来源、codec 与版本/损坏拒绝；V0_1/V1 原协议保持。具体字段、synthetic project origin 和编码见 Accepted [ADR-021](../architecture/ADR-021-multi-source-compilation-and-graph-compatibility.md)。
- 唯一新的 `ToolchainApplication.executeProject(ProjectToolchainRequest)` 首次生成入口；成功携带源快照、manifest 和图；没有 stateRoot/replace policy，输出根已存在就拒绝，不写 Bundle/CURRENT/Journal。
- D9-B 在两个 sir-change 准入位置固定 V0_1/V1；Rename plan/verify 共用守卫，防止直接 verify 旁路。不改目标/影响/计划/事务。

### 10.2 P1–P6 当前结论

| 门 | 真实证据 | 边界 |
|---|---|---|
| P1 | 旧 Parser 84 项 + 新语法/快照 7 项通过，保留四个新结构词作为旧 IDENT；旧入口拒绝 0.2，新根/片段各有正反例 | 不扩旧语言保留字，不开放旧 execute 的多源含义 |
| P2 | 21 类可跨文件 ReferenceRole 的逐角色缺导入反例全部到达 VISIBILITY-001；有显式导入通过，同源引用照旧；错误源/缺声明/冲突/自导入/环拒绝 | 计数是 JUnit 方法数，不把循环中的 21 个 case 冒充 21 项；不做完整 export/private/moduleInstance |
| P3 | 真实四源课程首次生成 **35 文件**，与对应单文件逐路径/逐字节相同；清单文本重排、不同宿主根与未列文件不改变生成字节 | 清单重排会改变根原字节及快照证据；生成等价不是多源运行 IT 或多源增量更新 |
| P4 | V2 真实生成图与直接 format 测试往返；改内层摘要/字段/成员/span/版本并重算外层完整性仍拒绝；Change plan、Rename plan/verify、单源 Bundle 不偷读新图 | 旧消费者只拒绝，不实现多源 Bundle；Graph 只有源证据，不保存原始正文或完整引用边 |
| P5 | @id 能力移动后能力及声明/字段/枚举成员 binding 目标保持、SourceSpan 指向新文件；新旧生成文件相同，AstNodeId/source-set/图摘要诚实变化 | 局部变量身份仍依赖 AST；不抵扣 LANG-04/nodeKey 或“全部绑定目标均不变” |
| P6 | NOFOLLOW 实体路径反例、硬链接别名、strict UTF-8、数量/字节上限、BOM/CRLF 原字节与边界接受；读取中的 size/mtime/inode 三种变化经注入拒绝 | 仅这批原始字节的快照，不承诺目录原子时点或任意并发改写检测 |

限额：含根最多 128 文件、每文件 1 MiB、总量 8 MiB；规范路径最多 512 UTF-8 字节，manifest 解码最多 128 KiB。没有 filesystem secure-directory 支持时拒绝，不加不安全读取 fallback。READ 失败定位根的真实清单字面量；Parse/Semantic 定位实际源。新入口将 related location 保留为相邻 INFO（关联主位置），旧映射格式不改。

### 10.3 定向测试（去重口径，不是全量 Reactor）

| 模块 | 实际指定类范围 | run/fail/error/skip |
|---|---|---|
| parser | 13 类：既有全部语言契约 + ProjectSourceGrammarTest | **91/0/0/0** |
| semantic | DeclaredIdentitySemanticsTest、TypedReferenceSiteContractTest、ResolveOnceArchitectureTest | **34/0/0/0** |
| project-graph | 既有六组直接契约 + MultiSourceGraphFormatTest；边界名单补登记后仅重跑边界类 | **77/0/0/0** |
| change | RenamePlanCoverageTest、RenamePlanContractTest、RenameSourceStalenessTest、RenamePlannerTest | **36/0/0/0** |
| application | 9 个本单新类 30 项；既有 Rename 绑定/保护/纵向/应用、Mixed 日志/恢复、ChangePlanning、GraphIntegration 78 项 | **108/0/0/0** |
| **合计** | 同最终生产代码、各类只计最后有效运行，不累加重试或循环 case | **346/0/0/0** |

本单新增 JUnit 方法 **43**：parser 7、semantic 1、graph 5、application 30。此为当前差异/定向报告核对，不预报未运行 CI 的全量总数。

application 新类：MultiFilePrerequisiteProbeTest 6、MultiSourceCompatibilityTest 3、MultiSourceGenerationTest 4、MultiSourceReadBoundaryTest 7、MultiSourceTypeValidationTest 3、MultiSourceVisibilityTest 4、ProjectGenerationFailureTest 1、LegacySourceEvidenceCompatibilityTest 1、ProjectSourceReadChangeTest 1。拒绝测试钉失败阶段/错误码/位置/数量，semantic 还钉 Resolve/Type/Validate；对比输出/状态树字节无变化，没有候选工程写出。

最终日志：`/tmp/q18-parser-regression.log`（91）；`/tmp/q18-final-directed.log`（semantic 34、Graph 非边界 71 已通过；其中边界首跑失败不作通过）；`/tmp/q18-final-remaining.log`（边界 6、change 36、application 108，BUILD SUCCESS）。更早 RED/修复记录见 `/tmp/q18-verify-red.log`、`/tmp/q18-project-final.log` 与 `/tmp/q18-related-final.log`，不加到当前计数。

环境 nproc=2，末次 free -m 可用 2057 MB；单任务均包 `systemd-run --scope -q -p MemoryMax=1500M -p CPUQuota=125% -- env MAVEN_OPTS='-Xmx384m' mvn -B -o -Dmaven.repo.local=/root/.m2/repository`，测试 fork `-DargLine=-Xmx384m`，无 Maven -T、无并发。Parser 用 `-pl sir-parser -Dtest=<13 类>`；其余用 `-pl sir-toolchain-application -am -Dtest=<表内类> -Dsurefire.failIfNoSpecifiedTests=false`，完整 selector 保存在上述日志的 Running 类清单。未构建/启动生成工程，没有本机 MySQL IT 或全量门。

### 10.4 兼容字节与公开拒绝证据

`LegacySourceEvidenceCompatibilityTest` 从 Q17 `ab2ce09` / CI run `36404651840` 的 conformance-evidence 固定值建立 goldens，**没有以新实现重新生成期望值**：

- Q9/Q10/Q11/Q13 四业务 fixture 的输入 SHA、生成文件数（15/22/22/35）及组合输出 SHA 全部一致。
- Q13 基线、放宽过滤、收紧、删除 Update、只读、新增轮六份 V1 snapshot SHA 全部一致，load→serialize 字节原样。
- 多源课程 35 文件和单源逐字节比对；改任意参与文件注释会改 SourceSnapshot/Graph 证据而不改 Java；未列文件新增无影响。
- Type 的 Date/Int64 比较、关系基数/歧义和 Validate 的关联深度错误仍明确拒绝，不由 import 按名称修复。
- 新输出根占位、源/输出重叠、源错误、Parse/Semantic、注入 Generation/Graph 故障全部在生成文件落盘前拒绝。已验收单能力 apply 与双向恢复定向回归通过，未改原事务实现。

### 10.5 实施期订正（最终验收尚待 CI）

| # | 事实与处理 |
|---|---|
| C1 | 名字排序不能单独保证 Resolve 可行；已由开工反例确定类别再按名字顺序，不改旧单源顺序或生成业务规则 |
| C2 | 移动测试最初把局部变量 ID 纳入“全部 binding 目标稳定”，与 D7/nodeKey 排除范围矛盾；拆成业务声明目标稳定与 AST/局部位置诚实变化，不宣称 LANG-04 通过 |
| C3 | 重复显式 ID 原先记录诊断后仍构造 SymbolTable，抛 duplicate SymbolId；Resolve 在冲突后立即失败，新增单源/跨源反例与两侧位置，既有合法源不变 |
| C4 | Rename 的直接 verify 可绕过仅 plan 的版本检查；共用同一兼容守卫，真实 V2 + 匹配版本及伪称 V1 的请求均拒绝，不改计划算法 |
| C5 | 旧 DiagnosticMapper 丢 related location；只为新项目入口保留相邻关联 INFO，旧格式不变。重名 Input 测试一度同时改字段导致额外 unknown-field；改为同结构副本隔离重名事实，不放宽诊断断言 |
| C6 | Graph 只读门首跑未登记 SourceSetManifest/Entry；经纯数据/I/O 核对后只增两项精确白名单，保留禁止能力及灵敏度探针，6/6 重跑通过 |

### 10.6 未执行门与交接

| 必需门 | 当前结果 |
|---|---|
| Q18 源码 SHA + CI run URL + 两类 artifact | **NOT_RUN**：当前未提交/推送，没有本批 CI 证据 |
| 冻结/完成两条全量门 | **NOT_RUN**，留 GitHub CI；346 个定向结果不代替全量门 |
| Q9/Q10/Q11/Q13 真实 MySQL/HTTP 回归 | **NOT_RUN（本批）**；旧字节 goldens 与旧 CI 不冒充本批运行回归 |
| 负责人最终验收/归档 | **待 CI 后确认**，本工作单不记 DONE |

已同步资格/覆盖与状态镜像，G2 尚未关闭。当前只请求明确授权提交与推送本批（含未提交的 Q17 文档收口），以便 CI 对同 SHA 验证；无其他 Git 授权，不自动进入下一张单。
