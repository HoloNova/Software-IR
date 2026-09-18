# 已完成工作单：Q2 Project Graph 直接模块契约测试

- 状态：`DONE`（项目负责人 2026-09-18 确认 Q2 整体通过）
- 归档：2026-09-18 从 `docs/roadmap/ACTIVE_WORK.md` 移入 `docs/roadmap/completed/`
- 所属阶段：路线图阶段 2 / Project Graph 直接测试；对应主设计 **G0：现有链路资格收口**（G0 内部阶段 2；G0 还包含阶段 3–6，另有阶段 7 最终资格作为关闭动作）
- 方向来源：`docs/roadmap/REMAINING_WORK.md` 阶段 2、`docs/roadmap/README.md`、`docs/design/implementation-baseline.md`
- 前置工作：Q1 已完成并归档（[`completed/Q1-generator-production-boundary-and-acceptance.md`](completed/Q1-generator-production-boundary-and-acceptance.md)）
- 已确认方案：项目负责人 2026-09-18 复核通过（D1 推荐方案 1、D2 模块内独立实现不新增模块、D3 沿用 Q1 修复规则）；执行中不扩大范围。
- 当前动作：2026-09-18 在本机（Linux）执行完成；`sir-project-graph` 由 0 项变为 72 项直接测试全绿（含追加的 5 项可达性/绊线/不可编码守卫测试），全量完成形式 451/5/0/11；项目负责人同日确认通过。
- 版本快照：**未提交、未推送**。项目负责人 2026-09-18 决定 Q2 不单独提交，与 Q1 及后续工作按初定计划在 **G1 完成后统一提交**；归档时本地 HEAD 与 `origin/main` 跟踪引用仍同为 `902a7a5`。

## 目标

关闭 `sir-project-graph` **0 项直接模块测试**的资格缺口：用模块自身的测试证明四类边、稳定身份、校验规则、canonical 序列化/加载/摘要和只读边界，而不是只依赖 Application 间接经过。

`docs/qualification/CURRENT_QUALIFICATION.md` 第 2、6 节当前登记：`sir-project-graph` 为 0 项直接测试，是残余风险第 3 项。本工作单要把这一项从“无直接证据”变成“有当次可执行证据”，或如实记录未能闭合的部分。

## 必须记录、不得重复实现的既有证据

`sir-toolchain-application` 已经在全量 Reactor 中真实执行以下测试，本工作单**不复制**它们，只在资格文档中引用：

- `ToolchainProjectGraphIntegrationTest`（11 项）：真实 Parser → Semantic → Lowering → Generator → Graph 链路上验证 35 个节点（1 Project + 6 Semantic + 6 Lowered + 11 Artifact + 11 File）与 34 条边（6 `DECLARES` + 6 `LOWERS_TO` + 11 `OWNS_ARTIFACT` + 11 `GENERATES_FILE`）、Semantic→Lowered→Artifact→File 追踪、公开集合不可变、摘要跨重复运行与不同绝对输出根稳定、provenance 为 workspace 相对 SourceId、Project 角色 artifact 的 origin 原样来自 Lowering。
- `ToolchainGraphStageFailureTest`（3 项）：Graph 失败在写盘前以 `ExecutionStage.GRAPH` + `NO_CHANGES` 终止，不创建输出根、不残留 `.sir-tx-` / `.sir-bak-` 目录；Graph Success 携带的 WARNING/INFO 诊断被保留。

因此路线图阶段 2 的任务 5（集成链）与完成门第 3 条已有当次证据。本工作单补充的是 Application **无法**替代的模块内契约。

## 边界设计

### 1. 四类边与校验规则

`ProjectGraphValidator`（627 行）与 `ProjectGraphBuilder` 是唯一拥有图合法性的地方，当前没有直接测试。必须至少覆盖以下规则的**成功与违规两侧**，并断言诊断码、严重级别与失败时不构造部分图：

- 项目根节点唯一性（`ProjectGraphNode.Project`）。
- 节点身份重复（`GraphNodeId` 重复、`SymbolId`/`LoweredNodeId`/相对路径重复）。
- 边端点存在性（悬挂边）与端点种类匹配（`DECLARES`/`LOWERS_TO`/`OWNS_ARTIFACT`/`GENERATES_FILE` 各自的 source/target 种类）。
- Lowered 归属、Artifact 归属与 role、File 归属一致性（`ownerSymbol`、`artifactId`、role 组合）。
- Semantic kind 与 Artifact role 的取值合法性。
- provenance 一致性（sourceId、span、origin、byteCount、sha256Hex 形态）。
- 文件路径规则（相对路径、不得绝对、不得 `..`、不得反斜杠）。
- canonical 顺序（节点按 rank：Project → Semantic → Lowered → Artifact → File，再按 `canonicalKey`；边按 edge id）。

`ProjectGraphValidator.validate(GraphVersion, List<ProjectGraphNode>, List<ProjectGraphEdge>)` 与 `validateInput(ProjectGraphInput)` 都是 public，测试可以两者都用：前者直接构造非法节点/边组合，后者验证入口层行为。

### 2. canonical 序列化、加载与摘要

`ProjectGraphSerializer` → `ProjectGraphLoader` 必须做真实往返：

- `serialize` → `load` → 同一 `canonicalDigest`、同一节点/边集合、同一 document 字节（`CanonicalProjectGraphDocument.equals`）。
- 输入顺序打乱（节点与边）后 digest 与序列化字节不变。
- `ProjectGraphLoader.load` 的“重新序列化必须与原字节相等”规则对非 canonical 输入 fail closed，返回 `SIR-GRAPH-INTEGRITY-003`。
- 畸形输入（错误 magic、错误格式版本、截断、多余空白、非最短十进制、未知 tag）返回结构化 `ProjectGraphAnalysis.Failure` 与诊断码，**不得**抛出 NPE、`IndexOutOfBoundsException` 或其它运行时异常。
- `load` 不得修改调用方传入的 `byte[]`（当前实现 clone，需直接断言）。

诊断码覆盖面按“族”取舍并如实登记：`SIR-GRAPH-{NODE,EDGE,PATH,ORDER,PROVENANCE,INTEGRITY}` 全部族要有直接断言；`SIR-GRAPH-FORMAT-*`（18 个码）与 `SIR-GRAPH-{COMPAT,VERSION}-*` 取代表性样本，未被覆盖的码写入资格文档的残余缺口，不得声称全码覆盖。

### 3. 只读边界

`docs/KCG-Code_系统架构与实现指南.md` 与 `AGENTS.md` §2.13 要求 Project Graph 不重新解析名称、不访问文件系统。必须给出可执行证据：

- `ProjectGraphLoader` 的 public 入口只接受 `byte[]` 与 `CanonicalProjectGraphDocument`，不接受 `Path`/`File`/`InputStream`（反射断言，含 array/泛型成分）。
- 生产 class 的静态引用边界：禁止 `java/nio/file/`、`java/io/`、`java/lang/System`、`java/time/`、随机源、`java/util/UUID`，并禁止重新解析入口（`io/kcg/sir/api/SirParser`、`io/kcg/sir/internal/`、`io/kcg/sir/semantic/api/`、`io/kcg/sir/semantic/internal/`、`io/kcg/sir/semantic/context/`、`io/kcg/sir/semantic/type/`）。
- 必须显式给出**允许清单**（`io/kcg/sir/ast/`、`io/kcg/sir/source/`、`io/kcg/sir/semantic/symbol/`、`io/kcg/sir/lowering/api/` 等模型类型，以及 `io/kcg/sir/projectgraph/` 自身），并断言生产 census 中出现的每一个 `io/kcg/sir/` 前缀都在允许清单内——禁止集与允许清单同时生效，避免“规则从未命中”的假绿。

## 已比较方案与决定（待复核）

### D1 测试数据来源

1. **模块内手写 `ProjectGraphInput` fixture（推荐）**：`sir-project-graph` 的 compile classpath 已有 `sir-parser`、`sir-semantic`、`sir-lowering-api` 的模型类型，可直接构造合法输入；代价是必须手工满足全部校验规则。
2. 提交一份由真实链路产生的 canonical 文档 golden fixture，模块测试只做 decode/load/re-encode：用真实数据测加载器，但多一道外部生成步骤，golden 与代码漂移时失败难归因。
3. 两者结合。

**推荐方案 1**，并把它序列化出的字节直接用于加载往返（不提交外部 golden）。fixture 的节点/边形状以 `ToolchainProjectGraphIntegrationTest` 已记录的 campus-market 结果为准（35 节点 / 34 边），测试注释写明该来源，避免与真实链路形状脱节。

### D2 只读边界的证明方式

1. **模块内独立实现 Q1 同类字节码闸门（推荐）**：`sir-project-graph/src/test` 内自带 test-only 探针与常量池扫描，规则表在本工作单第 3 节显式列出。
2. 抽取跨模块共享的 test 工具（新建 test-jar 或共享模块）。
3. 只做签名级断言。

**推荐方案 1**。理由：共享 test-jar 需要新模块或 POM 结构调整，超出本工作单范围（“不新增依赖”）。代价是规则表在两个模块各存一份，可能漂移；缓解方式是两处规则都在本工作单与资格文档逐条登记，并在资格文档说明二者关系。**该选择需要负责人确认**（若选择方案 2，本工作单范围与 POM 结构都要重写）。

### D3 发现真实缺陷时

沿用 Q1 规则：先确认不是字符串假阳性或测试自身缺陷；确认为生产缺陷后，只修被测试直接证明的 `sir-project-graph/src/main/**` 局部实现。若需要改公共 API、canonical 格式版本、模块依赖或跨模块契约，停止并请求项目负责人裁决。

## 允许修改的范围

- `sir-project-graph/src/test/**`
- 被新测试直接证明违规的 `sir-project-graph/src/main/**` 局部实现
- 与实际结果同步的：
  - `docs/qualification/CURRENT_QUALIFICATION.md`
  - `docs/qualification/TEST_COVERAGE_INVENTORY.md`
  - `docs/roadmap/REMAINING_WORK.md`
  - 本工作单状态与交接记录

不得新增依赖（含 test-scope）、skip 或 exclude；不得修改 Graph 公共 API、canonical 格式版本、`sir-toolchain-application` 的既有测试或任何其它模块；不得触碰受保护路径。

## 实现与测试顺序

1. 先建模块内 fixture 支持与最小测试：合法输入必须构建成 `ProjectGraphAnalysis.Success`。若既有行为首次即通过，如实记录“既有行为获得直接证据”，不虚构 RED、不改生产代码。
2. validator 规则矩阵：每条规则至少一个违规用例，断言诊断码、数量、被指向的节点/边和重复运行的一致性；同一用例必须同时证明失败时没有构造出 `ProjectGraph`。
3. canonical 顺序与摘要：打乱输入顺序、重复构建、跨 Locale 与工作目录（沿用 Q1C 的独立性做法，但不新建跨 JVM 探针）后 digest 与字节一致。
4. 往返与 fail-closed：序列化 → 加载 → 字节相等；对序列化字节做非 canonical 变形与截断，断言结构化 Failure 与诊断码，不抛出运行时异常。
5. 只读边界闸门：探针灵敏度 + 生产 census 扫描 + `io/kcg/sir/` 允许清单核对 + 加载器签名反射断言。
6. 回归：模块定向测试、全量离线 Reactor ①②两条（口径与 Q1 相同），同步测试计数与残余风险。

## 验证命令

离线仓库路径按机器选择，两者等价：

- Linux 工作机：`-Dmaven.repo.local=/root/.m2/repository`
- Windows 工作机：`-Dmaven.repo.local=D:\maven-repo`

定向（Linux 工作机，模块当前没有测试，本工作单新增的全部都要跑）：

```bash
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-project-graph -am test
```

完成闸（Linux 工作机，两条都跑）：

```bash
# ① 冻结形式：预期在 sir-toolchain-application 因已登记的 5 项软链接断言而中止
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify

# ② 完成形式：不因失败中止，Reactor 跑到 kcg-cli；BUILD SUCCESS 不代表 5 项已消失
mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify -Dmaven.test.failure.ignore=true
```

Q1 基线（2026-09-18，见资格文档 1.3）：① 模块 1–9 合计 **374 / 5 / 0 / 11**；② 十个模块合计 **379 / 5 / 0 / 11**，其中 `sir-project-graph` 为 **0** 项。本工作单完成后 `sir-project-graph` 必须为非零，且全量总数应为 `379 + 新增数`。

`sir-toolchain-application` 的 5 项软链接断言失败仍不在允许修改范围内：它们按资格文档 1.2 原样登记，不计入 Q2 通过条件，也不得被描述为已修复。

## Q2 完成闸

- `sir-project-graph` 不再有 0 项直接测试；模块定向命令全绿，测试数量来自当次 Surefire XML。
- 四类边在模块内有直接契约，成功与违规两侧都有断言。
- validator 的 NODE / EDGE / PATH / ORDER / PROVENANCE / INTEGRITY 六个诊断族都有直接断言；FORMAT / COMPAT / VERSION 的未覆盖码在资格文档如实登记为残余缺口。
- canonical 序列化 → 加载 → 再序列化字节相等；摘要对输入顺序、重复运行稳定；非 canonical 与畸形输入 fail closed 且返回结构化诊断。
- 只读边界有可执行证据：禁止集、`io/kcg/sir/` 允许清单、加载器签名三者都被断言，探针证明闸门灵敏。
- 不新增依赖、skip、exclude；未修改 Graph 公共 API 与 canonical 格式版本。
- 全量离线闸门按①②执行：① 模块 1–8 通过、`sir-toolchain-application` 因已登记的 5 项中止、`kcg-cli` 未运行，Reactor 内 **446 run / 5 fail / 0 error / 11 skip**；② 跑到 `kcg-cli`，合计 **451 run / 5 fail / 0 error / 11 skip**，失败项与 Q1 完全相同的 5 项，无新增 fail/error，skip 仍为 11。
- 既有的 Application 级集成链与 Graph 写盘前失败证据被引用，不得表述为本工作单新增。
- 运行时、MySQL、HTTP、conformance、事务故障矩阵、CLI 生命周期和后续路线不得被表述为 Q2 已完成内容。
- 状态更新为 `AWAITING_ACCEPTANCE`，等待项目负责人确认；确认后归档本工作单。按负责人 2026-09-18 决定，归档时不单独提交 Git。

## 交接记录

2026-09-18：Q1 被确认通过并归档（未提交、未推送，按负责人决定推迟到 G1 完成后统一提交）。项目负责人要求继续推进 Q2 并更新工作单。本工作单完成 Q2 设计展开与自检，状态 `SPEC_REVIEW`；方案复核通过前不修改测试或生产代码。

设计自检时确认的既有事实（供复核）：

- `sir-project-graph` 当前为 0 项直接测试；`pom.xml` 依赖为 `sir-parser`、`sir-semantic`、`sir-lowering-api` 与 test-scope JUnit，没有 Application/Generator 依赖。
- 模块无测试资源目录，无既有 fixture 可复用。
- `ProjectGraphValidator.validate`、`validateInput`、`ProjectGraphBuilder.build`、`ProjectGraphSerializer.serialize`、`ProjectGraphLoader.load`、`ProjectGraphCanonicalizer` 均为 public 入口；`ProjectGraph` 构造器为包级，测试需与 `ProjectGraphEdge`/`ProjectGraphNode` 同包或在既有 public 入口下间接构造。
- 生产诊断码共 10 个族、约 65 个码，主要分布在 validator、`SnapshotEncoder`、`SnapshotDecoder`、`SnapshotLoader`。

2026-09-18：Q2 执行完成（本机 Linux、Java 21.0.12、Maven 3.6.3、离线仓库 `/root/.m2/repository`；源码基点 `902a7a5` 加 Q1 未提交改动；本轮只新增 test-only 文件与文档，**未修改任何生产代码、未新增依赖/skip/exclude**）。状态改为 `AWAITING_ACCEPTANCE`。

新增文件（全部在 `sir-project-graph/src/test/**`）：

| 文件 | 作用 |
|---|---|
| `boundary/ClassFileConstantPoolReader.java` | test-only class-file 常量池 reader：把 `CONSTANT_Utf8` 分类为 `CLASS_NAME` / `STRING_LITERAL` / `OTHER`，并把字段/方法引用解析为 `owner.member`；非法 magic、截断数据、未知 tag 立即抛 `ClassFileFormatException`（带文件、偏移、tag） |
| `boundary/ForbiddenReferenceProbe.java` | 故意调用 `java.nio.file.Files`、`System.getenv`、`java.util.UUID`、`io.kcg.sir.internal.DefaultSirParser` 的 test-only 违规探针 |
| `ProjectGraphFixtures.java` | 模块内手写 campus-market 形状 fixture：**35 节点 / 34 边**（1 Project + 6 Semantic + 6 Lowered + 11 Artifact + 11 ProjectFile；6 DECLARES + 6 LOWERS_TO + 11 OWNS_ARTIFACT + 11 GENERATES_FILE），并提供逐项扰动 helper |
| `ProjectGraphContractTest.java`（9 项） | 四类边、节点/边 census、按 SymbolId 的 trace、project artifact 无 owner、provenance sourceId、公开集合不可变、未知 id 返回空、validator 接受 builder 产物 |
| `ProjectGraphValidationRulesTest.java`（31 项） | 每个可触发规则一项，断言**精确**诊断码集合（意外的级联会失败而不是静默通过） |
| `ProjectGraphCanonicalizationTest.java`（6 项） | 节点/边 canonical 顺序、digest 与 canonical bytes 与输入顺序无关、重复构建字节相等、digest 依赖节点内容而非仅结构 |
| `ProjectGraphSerializationTest.java`（20 项） | 序列化→加载→再序列化字节相等、`byte[]`/document 防御性拷贝、加载不改写入参、13 类畸形/非规范输入的 fail-closed 矩阵（含行模型自检），以及项目负责人确认后追加的 5 项不可信字节守卫/绊线测试 |
| `boundary/ProjectGraphReadOnlyBoundaryTest.java`（6 项） | 生产字节码禁止引用扫描、`io/kcg/sir/` 允许清单、探针灵敏度、扫描路径灵敏度、reader 错误处理、加载器签名反射 |

定向结果：`mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean -pl sir-project-graph -am test` → `sir-project-graph` **72 run / 0 fail / 0 error / 0 skip**（31 + 20 + 9 + 6 + 6），reactor 上游 parser 43、semantic 103、lowering-api 4 也全绿。

全量离线闸门（两条都执行，均在最终代码状态）：

- ① `mvn -B -Dmaven.repo.local=/root/.m2/repository -o clean verify`：模块 1–8 SUCCESS（含 `sir-project-graph` 72 项），`sir-toolchain-application` FAILURE，`kcg-cli` SKIPPED；Reactor 内合计 **446 run / 5 fail / 0 error / 11 skip**。
- ② `... clean verify -Dmaven.test.failure.ignore=true`：10 个 Reactor 模块全部完成，合计 **451 run / 5 fail / 0 error / 11 skip**。
- 失败项与 Q1 完全相同的 5 项（`PathGuardTest.targetItselfIsSymlinkIsRejected`、`SafeTargetResolverTest.{targetSymlinkRejected,parentSymlinkOfOutputRootRejected,linkDotDotRawChainAttackRejected,parentSymlinkInsertedBeforeResolveRejected}`），**无新增 fail/error/skip**。
- 相比 Q1 的 379/5/0/11，总数 +72（其中 67 项为 Q2 主体，5 项为追加的守卫/绊线/不可编码测试），全部来自本工作单；`GeneratedProjectOfflineCompilationTest` 在②中仍真实执行；`kcg-cli` 5 项已实际运行。
- `git diff --check` 无输出；`git status --short` 只有 test-only 新增文件与本轮文档改动（生产代码零改动）。

执行过程如实记录（均为 test-side 缺陷，不是生产 RED，也没有生产修改）：

1. 首轮 fixture 不是合法图：`SIR-GRAPH-NODE-001` × 3、`SIR-GRAPH-EDGE-007` × 3、`SIR-GRAPH-PATH-002` × 3，原因是我的 ENTITY_MODEL 与 MAPPER 复用了同一个类名/路径。改为按 role 决定子包与类名（`entity/User.java` 与 `mapper/UserMapper.java`）后 9/9 通过。
2. 规则矩阵首轮 31 项中 3 项因我的预期缺了合法级联而失败：`NODE-002` 也会产生 `EDGE-002`+`EDGE-004`；重复 Project root 若追加到末尾会额外产生 `ORDER-001`（改为插入到 index 1 以隔离该规则）；改 lowered `sourceSymbol` 也会触发 `PROVENANCE-004`。修正预期并在测试中写明原因。
3. 序列化/规范化首轮 4 项失败：3 项是我用 `assertEquals` 比较 `byte[]`（引用比较），改为 `assertArrayEquals`；1 项是 `payloadByteCount` 加前导零得到 `FORMAT-012` 而不是 `FORMAT-007`——据此新增 HEADER field count 非最短十进制用例真实覆盖 `FORMAT-007`，并把 `payloadByteCount` 的预期改为实际码。
4. 边界测试首轮 1 项失败：`byte[].getName()` 是 `[B` 而不是 `byte[]`，修正签名预期。
5. 追加 4 项守卫测试时，我写的“文档声明的 `graphVersion` 不被校验”一条**被测试直接推翻**：实际 `SnapshotDecoder` 236～255 行会对非 `V0_1` 报 `SIR-GRAPH-COMPAT-002`。原因是我只查了 `SnapshotLoader`（它硬编码 `V0_1`）而没查解码器。测试改写为“声明 V0_2 的文档被拒，恰好 `COMPAT-002`”，并把这个可达的版本偏斜守卫转成了直接证据。

需要项目负责人注意的三项事实（已写入资格文档 1.4，不影响本工作单验收口径）：

1. **边界规则相对规格做了精化（偏差）**：规格要求把 `java/io/` 与 `java/lang/System` 整体列为禁止。生产实测显示 `sir-project-graph` 合法使用 `java.io.ByteArrayOutputStream`（纯内存字节缓冲）与 `System.arraycopy`（纯内存数组复制），整体禁止会把既有正确代码判为违规。因此实现为**成员级复核清单**：这两个 owner 下只有 4 个已复核成员被放行，其他任何成员（如 `System.getenv`、`java.io.FileInputStream`）按名报违规，并由探针断言证明该规则真的会报出 `System.getenv`。未新增依赖、未修改生产代码。
2. **两个码经公开 API 不可达**：`SIR-GRAPH-VERSION-002` 与序列化器的 `SIR-GRAPH-COMPAT-001`，因为 `GraphVersion` 与 `ProjectGraphCanonicalFormatVersion` 当前都只有一个枚举值。解码器的 `SIR-GRAPH-COMPAT-001`（文档 HEADER `formatVersion != 1`）可达且已直接覆盖。
3. **`SIR-GRAPH-PROVENANCE-001` 不可通过任何公开路径触发**：节点 record 的 `provenance` 组件用的是具体记录类型，构造器参数在编译期就强制匹配（尝试传错类型会直接编译失败）；解码器也对每种 nodeKind 逐项 `requireEquals(raw, "provenance.kind", …)`（`SnapshotLoader` 117/164/243/313/386 行）。测试改为用反射断言该组件类型；该码登记为对非公开构造路径（反射、序列化框架等）的防御性复检。
4. `payloadByteCount` 的 `SIR-GRAPH-FORMAT-007` 分支被 `FORMAT-012` 遮蔽（前导零先被"非负十进制"预检拒绝）；`FORMAT-007` 在 HEADER field count 路径可达，已直接覆盖。

仍未覆盖、不得由本工作单记为实现的内容：`REFERENCES` 边、Graph service、Snapshot V2、增量索引、运行时/MySQL/HTTP/conformance、事务故障矩阵、CLI 生命周期和后续路线。

2026-09-18 补充（项目负责人确认后追加的 4 项可达性/绊线测试）：

| 测试 | 证明什么 | 结果 |
|---|---|---|
| 文档 `provenance.kind` 与 `nodeKind` 不匹配 | 外部文件谎报来源类型时 fail closed；这是 `PROVENANCE-001` 想守的门在**不可信字节**上的可达替身 | 恰好 `SIR-GRAPH-COMPAT-010` |
| `SnapshotEncoder.encode(graph, null, diags)` | 非 V1 格式版本编码 fail closed（公开 `ProjectGraphSerializer` 因 `Objects.requireNonNull` + 单值枚举到不了这里） | 恰好 `SIR-GRAPH-COMPAT-001` |
| 文档声明 `graphVersion=V0_2` | 文件层的版本偏斜会被拒绝（先修复 integrity 字段，确保真的走到该规则） | 恰好 `SIR-GRAPH-COMPAT-002` |
| 两个版本枚举各只有一个常量 | 绊线：一旦新增版本值，覆盖清单里“因单值枚举不可达”的理由会立即失败，强制补真实测试 | 通过 |

实现过程中修正的一处自己的错误认知（由新测试直接暴露）：我先前只查了 `SnapshotLoader`（它在 55/60/71 行硬编码 `GraphVersion.V0_1`），因而误判“文档声明的 `graphVersion` 根本不被校验”。实际上 `SnapshotDecoder` 236～255 行按字符串校验该字段并对非 `V0_1` 报 `SIR-GRAPH-COMPAT-002`。结论：**文件层的版本偏斜是有防护且有直接证据的**；真正不可达的只剩 validator 里的 `SIR-GRAPH-VERSION-002`（需要非 V0_1 的 `GraphVersion` 才能触发）与序列化器的 `COMPAT-001`。

阅读代码时发现的可达分支（本轮已补齐）：`SIR-GRAPH-SERIALIZE-001`（`SnapshotEncoder` 192～202 行，节点字段含不可编码 Unicode 标量时触发）。测试构造带孤立高位代理项（`\uD800`）的 `displayName`，断言序列化被拒且诊断码恰为 `SERIALIZE-001`。至此 Q2 范围内**没有已知可达而未覆盖的诊断码**。

2026-09-18：项目负责人确认 **Q2 整体通过**，并确认边界规则精化（成员级复核清单代替整体禁止 `java/io/` 与 `java/lang/System`）以及追加 `SERIALIZE-001` 缺口测试。据此：

- 追加第 5 项守卫测试 `rejectsNodeFieldsThatCannotBeEncodedAsUtf8`，`sir-project-graph` 由 71 → **72** 项，全绿。
- 追加后的全量离线闸门：① **446 run / 5 fail / 0 error / 11 skip**（`kcg-cli` SKIPPED）；② **451 run / 5 fail / 0 error / 11 skip**。失败项仍是与 Q1 完全相同的 5 项 `sir-toolchain-application` 软链接断言，无新增 fail/error/skip。
- 至此 Q2 范围内**没有已知可达而未覆盖的诊断码**；不可达的只剩 `SIR-GRAPH-VERSION-002` 与序列化器的 `SIR-GRAPH-COMPAT-001`（绊线测试守位），以及仅有反射层面证据的 `SIR-GRAPH-PROVENANCE-001`。
- 本轮生产代码、POM、依赖零改动（`git status --porcelain -- '*/src/main/*' 'pom.xml' '*/pom.xml'` 为空），`git diff --check` 无输出。
- 本工作单状态改为 `DONE` 并归档；**未创建本地提交、未推送 `origin`**（本地 HEAD 与 `origin/main` 仍同为 `902a7a5`）。
- 归档后 `docs/roadmap/ACTIVE_WORK.md` 重置为“无已授权工作单”占位，下一张候选（阶段 3 / Change fixtures）需要项目负责人先复核规格再派发。
