# 关联字段自比较与查重后唯一写入：静态检查设计

状态：**负责人已确认并批准本批提交推送；本机实施与定向验证完成，CI 待验收**。基线 `6945f278eafee84ab368b8e3614d94a706fec82d`。对应新版交接 4.1；4.2 的重查、付费反馈、4.3 Parser 和 4.4 归档命令不混入本次实现。

## 1. 为什么需要这两条规则

博客候选 `906a864f71bf` 在业务门 #65 静态、构建、启动均通过，业务 401/25。14 条失败来自 `any(PostTag, post == item and tag == tag)` 没有按请求标签筛选；11 条来自 AttachTag 的查重后插入漏写 `persist link else TagAttached`，并发输家返回 500 而非合同要求的 409。原始报告在 `datasets/sir/pilots/blog-change-g8/runtime/content-blog-change-v1-teacher-906a864f71bf/`，分析见数据报告第二十四阶段。

运行反馈重答花费 24,257 tokens，却引入内联 `exists` 的解析错误。把这两种可机械识别的问题提前到静态门，可让所有关联查询、查重后唯一写入共同受益，不需要给博客或选课加专门语法。静态规则仍不能证明业务正确，候选仍须独立过四门。

## 2. 已核对的基线实现事实

- `ResolvePass.resolveNameExpression`：`any` 内的裸名先绑定关联实体的主键/字段，再考虑外层变量；现有优先级不改。
- `ReferenceSiteBindings` 已提供每次引用的角色和目标 `SymbolId`。`MEMBER_ACCESS` 只说明字段声明，同一实体不同对象的成员访问可以绑定到同一个字段符号；不能仅比较最终字段符号便认定是同一个值。
- `MAIN.md` 和 ADR-001 要求名称只解析一次；新检查只消费正式 binding，不按文本查名字或重新建立作用域。
- `TypePass` 负责类型和根行关联；自比较是用法限制，推荐放在 `ValidatePass`，不把它伪装成类型不兼容或缺少关联。
- 普通 find/exists/count 等的 `item.x == item.x` 已受 `validateComparisonShape` 拒绝；本次不再为它叠加第二条诊断。
- `ValidatePass.insertsIntoUniqueEntity` 已能区分新建唯一实体的行与普通更新，`uniqueEntities` 同时收集字段级、联合唯一约束，可以复用。
- 现有 versioned 写入检查主要处理最后一次 persist；新规则 B 必须检查每个符合条件的新行 persist，不能只挂到最后一次 persist 上。
- 现有 `SIR-WARN-001` 处理缺少唯一保护的先查后插；它会跳过已有唯一约束的实体，且遇到显式锁可停止告警。它与新规则 B 的“声明唯一冲突如何返回”不同，不能直接把该告警升级成错误。
- `ProjectSourceResolver` 依次运行 Type/Validate；本次不改变阶段顺序或原有诊断。Optional 等类型错误仍保留。

## 3. 规则 A：拒绝 any 内同一关联字段的自比较

### 3.1 精确判定

仅检查合法查询位置的 `any(E, condition)` 条件：按现有公开契约，为 find、exists/count、sum/avg（含分组）的谓词，不扩大 any 的可用位置。递归遍历其中的分组、逻辑组合和取反；对每个 `==` 或 `!=`：

1. 去除操作数最外层括号。
2. 两侧都必须是裸 `AstNameExpression`。
3. 两次引用的正式角色都必须为 `EXISTS_CONDITION_FIELD`。
4. 两侧目标 `SymbolId` 相同，且该字段符号的 owner 是当前 any 的实体 E。
5. 满足时在完整比较表达式上报告一条 `ERROR / SIR-VALID-001`。

实体主键也是 `FieldSymbol`，因此 `id == id` 同样适用。缺少正式绑定时不按文本猜测，保留既有 Resolve 失败。非法 any 位置和嵌套 any 继续由原有 FLOW 规则处理，不在那些位置额外重复做 A。

不是“同名就错”，也不是“所有符号自比较都错”。例如以下片段不应被 A 拒绝：

```sir
any(Loan, copy == item and reader == actor)
any(PostTag, post == item and tag == wanted)
any(User, id == item.author)
any(Node, parent == item and code == item.code)
```

最后一个例子的两次 code 可以指向同一个 `Node.code` 声明，但分别读取关联行和外层根行，不能误报。外层恰好也有变量 reader，不改变第一个例子的合法性。

拒绝示例：

```sir
any(PostTag, post == item and tag == tag)
not any(CompletedCourse, course == item and student != student)
```

### 3.2 诊断与边界

建议消息模板：

> any(E, ...) 中比较的两侧都绑定到关联实体字段 E.f，而不是外层变量。若要与外层变量比较，请给外层变量使用不与字段同名的名称，并在这里引用它。

- 复用通用用法代码 `SIR-VALID-001`；不占用专门表示关联缺失的 `SIR-TYPE-004`，不新增代码。
- 主位置为 `f == f` 或 `f != f` 的完整 SourceSpan，保留 Unicode code-point 位置口径；不生成自动修复、不自动改变量名。
- 不做常量折叠，也不宣称所有 `x == x` 都恒真：Optional 缺值比较有既定语义。只新增“此类关联字段自比较不允许”的用法限制；判空应使用现有 `is none/is present`。
- 不检查任意工作流变量、字面量、外层成员、算术恒等式或所有比较运算符；不扩大成通用 lint 系统。
- 最小反例应只有一条新增诊断；存在其他独立错误时保留全部真实诊断，不为凑单条错误而删除旧结果。

## 4. 规则 B：先 exists 后写入唯一新行，必须声明 persist else

### 4.1 精确判定

对每个工作流按执行顺序走访；遇到 persist 时，以下条件全部成立才报错：

1. `insertsIntoUniqueEntity(capability, persist)` 为真：该目标变量由本能力 create，实体声明字段级或联合唯一约束。
2. 在该 persist 之前，存在明确的 `AstScalarQueryStep.Kind.EXISTS`，其实体 binding 与目标实体相同。
3. persist 没有 failure/else。

在完整 persist 语句上报告一条 `ERROR / SIR-VALID-004`。比较实体与目标变量都用正式 `SymbolId`，不依赖实体名、变量名或正则。

create 只是内存构造，不是数据库写入。因此允许 create 在 exists 之前，只要 exists 发生在 persist 之前，仍属于本规则；不能通过把 create 提前绕开检查。exists 出现在 persist 之后则不触发。

### 4.2 顺序、作用域与循环

- 只积累当前能力中此前的 EXISTS 实体，不跨能力，不把 COUNT 或 find 当成 EXISTS。
- 遍历 each/repeat 的循环体时继承外层此前的只读集合，按体内顺序继续检查；循环体中新发现的 EXISTS 不回流成“循环后必定执行过”的事实，因为循环可能零次。
- 不把循环的上一次迭代作为下一次迭代的已检查证明，不做跨迭代固定点分析，也不放开现有循环体限制。
- 每个 persist 节点只检查一次。已有 else 的合法性、错误是否声明于 fails，继续交给现有规则；不自动补 fails 或选择错误。
- 现有 versioned 条件更新、普通加载后更新的判定不变；新建的唯一行只是复用既有“新行唯一冲突”处理能力。

### 4.3 为什么不因 atomic 或锁而豁免

`requires atomic` 不是唯一冲突到业务错误的映射。显式锁也不在本次做完整的锁协议正确性证明。B 是一条有边界的显式错误处理要求，不声称每个命中都会实际产生竞争。

因此，像父候选 LikePost 那样“持有 Post 锁、exists Like、新建 Like、裸 persist”的路径也会命中 B，尽管 #65 的点赞竞争通过。这是应明确批准的语言限制，不得把它写成新的业务失败事实。

不扩大为“所有新建唯一实体的行都必须写 else”：没有此前 EXISTS 的注册、建分类等保持原行为。也不强制两处错误符号完全相同；提示建议沿用前面拒绝重复的错误，是否符合 HTTP 合同仍由业务门判定。

建议消息模板：

> 此前已 exists 检查实体 E，但查重不能替代唯一冲突处理；新行 persist v 必须声明 else 已声明错误。若前面用 validate ... else 拒绝重复，可沿用同一个错误。

修复片段示例：

```sir
exists PostTag where item.post == post and item.tag == wanted as attached;
validate not attached else TagAttached;
create PostTag as link { post: post; tag: wanted; }
persist link else TagAttached;
```

此处只说明已有语法，不更改生成器或数据库异常映射。

## 5. 取舍与实现范围

| 方案 | 取舍 |
| --- | --- |
| 继续加长 Prompt、重复运行期修复 | 不能机械保证遵守，且已发生高成本修复后退化为解析错误；不作为本轮方案 |
| 改 any 名称优先级 | 会改变正确程序绑定、违反本轮边界；不采用 |
| 按同名文本或同一个字段符号一律拒绝 | 会误伤合法 reader/actor 和不同对象同一字段；不采用 |
| 所有唯一新建都强制 else | 是更宽的语言政策，超出交接窄规则；不采用 |
| 绑定驱动的窄 A/B 错误检查 | 推荐；接受禁止原先合法但易误用的写法，并把兼容变化公开写进语言参考 |

已按确认范围落实：

- `sir-semantic/.../internal/ValidatePass.java`：A 接入 `validateExistenceUsage` 的合法 any 分支；B 从两参 `validateWriteWorkflow` 的能力级入口调用一次有序扫描，复用 `insertsIntoUniqueEntity`，不在循环校验的多处入口重复报告。
- 以小型 private 方法拆开比较识别、条件遍历、顺序走访，单个方法控制在 50 行以内。该类基线已有 2510 行，本次不借机全量拆分、格式化或改名；这是只在既有校验入口补规则的有限改动。
- 新增 `AnySelfComparisonSemanticsTest`、`PrecheckedUniqueInsertSemanticsTest`；调整下节已确认受影响的一处动态测试。
- `docs/SIR_LANGUAGE_REFERENCE.md` §5.3 any、§5.5 唯一冲突各补明确规则，诊断代码表同步说明，加入能被原例子检查器执行的正反例。
- 本设计随实现、测试、语言文档放在同一个提交。Parser、Resolve、Type、Normalize、Lowering、Generator、冻结 task、原候选、原 evidence 均不改。

## 6. 影响核对：不能把正则零命中当成零兼容变化

本机先原样运行附带 `rule_impact_scan.py`。它以相对路径只排除 `/target/`，没有排除顶层 `target/`，因此把 LF 快照里的历史候选也读进仓库扫描：2446 份，A=14、B=100。原始输出保留于本机 `target/static-rule-design/`。

不改原脚本，再复用同一扫描函数并把来源限定为 Git 跟踪文件，排除 2142 份顶层 target 快照，得到：

| 范围 | 扫描单元 | 正则命中 |
| --- | ---: | --- |
| Git 跟踪的参考、Java 文本块、Markdown 示例 | 304 | A=0、B=0 |
| 历史教师源码 | 171 | 28 份候选；A=15 处、B=83 处 |
| 导出索引中的合格教师 ID | 48 | 上述历史扫描中无一命中 |

完整本机核对见 `target/static-rule-design/impact-review.json`；这些是可清理的设计调查结果，不是长期四门资格证据，也不是新语义检查已经通过。

**源码审阅已发现至少一处零命中的盲区。** `CheckThenInsertWarningTest.aUniqueKeyOnTheInsertedEntityIsEnoughProtection` 在运行时对 `valid/check-then-insert.sir` 动态拼入 `unique (room, startsAt)`，移除父行锁，仍保留 `exists Booking` 和 `persist made;` 并断言成功。原正则看不到拼接后的实体，因而漏报。它符合 B，不能继续声称所有既有测试无需调整。

已把这个正例明确改为“有唯一约束且显式声明冲突错误”，在测试构造源里使用已有 `SlotTaken`，写 `persist made else SlotTaken;`，仍验证无 `SIR-WARN-001`。原 fixture 不改，非唯一实体有/无锁两条原告警测试不改；新 B 测试另锁定漏 else 的拒绝。这是在新语言政策下调整测试契约，不是放宽检查让旧断言变绿。实际兼容范围最终由定向测试和 CI 决定，不预报全绿。

## 7. 验收矩阵（本机定向完成，CI 待验）

| 类别 | 必须覆盖的边界 |
| --- | --- |
| A 拒绝 | 正常关联中相同字段 `==`/`!=`；括号、and/or、not；主键自比较；每个表达式只报一次 |
| A 放行 | 同名外层变量但比较 actor；改名 wanted；反向主键关联；同实体不同接收者的同字段；不同字段；无外层同名变量也能解释真实 binding |
| A 既有规则 | 关联缺失仍 TYPE-004；普通查询列对列仍原 VALID-001；非法位置/嵌套仍原 FLOW；不增加重复 Resolve |
| B 拒绝 | 单字段/联合 unique；先 EXISTS 后 persist；create 提前；多个 persist 中非最后一个；each/repeat 体内与外层此前 EXISTS；atomic/显式锁不豁免 |
| B 放行 | 已写合法 else；无此前 EXISTS；只有 COUNT；EXISTS 在 persist 后；查询别的实体；加载后更新；循环内 EXISTS 不污染循环后的事实；另一个能力的 EXISTS 不污染当前能力 |
| B 既有规则 | 非 unique 的旧 SIR-WARN-001 保留；versioned 条件更新仍要求 else；错误未列入 fails 仍 FLOW-003；不合法的 else 仍旧 VALID-004 |
| 诊断 | ERROR、既有代码、完整比较/persist 的精确 SourceSpan（含 Unicode）；单根因计数；固定顺序、空 fixes；不自动改源码 |
| 合法生成 | 合法输入不改变 reference role/AST/Normalized/生成文件；CI 的参考四门继续验证 |

本机按项目限制只跑受影响的定向测试，保留 `-am`：新两类、`QueryDiagnosticMessageTest`、`CheckThenInsertWarningTest`、`WriteSliceSemanticsTest`、关系/标量查询、循环、Resolve-once 与 typed reference 的相关回归。分组或单次完成，以实际改动范围为准，不重复同源码同范围。

改语言参考后另按规范跑一次 `LanguageReferenceExamplesTest`。真实构建/启动/业务与全量门交 GitHub CI，本机不启动 MySQL 或生成应用；Windows 资源和 Maven 参数按当时实际宿主核定，不套用交接的 Linux 示例。

## 8. 后续顺序与当前状态

1. 负责人已确认本设计，包括 B 即使有锁也要求 else，以及动态旧测试的契约调整。
2. 已完成定向 RED、实施、定向 GREEN 和语言例子检查，见下节。
3. 负责人已另行确认本批六项单次提交/推送；随后以同 SHA CI 验收。旧资格与冻结输入不追改。
4. 按交接 4.2 导入博客 g9，新工具链静态重查 `906a864f71bf`、`718c0d88dafa`、`215ebacdb9b3`。保留原 Optional/fails 等诊断，不只看新代码命中；不得预先把重查结果写成通过。
5. 先把结果写进第二十五阶段，再另报静态反馈预算；费用确认前不调用教师。任何新子候选仍须自己过四门。

设计阶段只有源码/旧日志审阅、正则影响复现和本设计文档。负责人随后确认后实施了两条规则、定向测试和语言说明；实施验证阶段未修改冻结题目/历史候选/资格，也未调用模型或提交推送；负责人随后已批准本批提交推送。合格答案仍 48 条，学生 v3 不变，未训练。


<a id="local-verification"></a>

## 9. 本机实施与验证记录

**实现。**本轮只有 `ValidatePass` 一个生产文件变化：A 在现有合法 any 校验入口读取正式引用角色、目标字段及 owner；B 从能力级入口单次按序扫描，循环继承不可变的此前 EXISTS 集合，体内事实不外泄。三个新 private 方法均小于 50 行；不修改任何 AST、SymbolTable、类型或生成决策。两条规则分别使用原 `SIR-VALID-001`／`SIR-VALID-004`，主位置为完整比较／persist，错误消息不提供猜测性的自动修复。新增两类共 55 项语义用例，调整了获批的旧唯一约束正例；语言参考补了两个可执行反例，并把注册正例补为先查重后显式处理冲突。

### 9.1 TDD 与真实失败

| 阶段 | 实际结果 | 说明 |
| --- | --- | --- |
| 首次 RED | 55 项，31 失败、0 错误、0 跳过 | 30 项缺新行为；另 1 项把分页 find 放进 Unit 写能力却断言合法，属测试构造错误 |
| 有效 RED | 55 项，30 失败、0 错误、0 跳过 | 改为明确断言分页能力原有诊断后重跑；A 19、B 11 项均仅因旧检查器接受错误写法而失败，25 项边界检查通过 |
| 首次工作区 GREEN | 17 类／235 项，10 失败、0 错误、0 跳过 | 新 55 项全通过；10 个旧用例因 CRLF 破坏 LF 片段替换而失败，保留原报告，不记作全绿 |
| 独立 LF 快照 GREEN | **17 类／235 项，0 失败／错误／跳过** | 同一生产/测试内容，在精确 LF 基线中全通过 |
| 语言参考 | **LanguageReferenceExamplesTest 4 项，0 失败／错误／跳过** | 所有接受示例完整生成、所有拒绝示例含声明错误码；包含本次新增两反例及修改正例 |

测试夹具修正没有降低新规则：分页 find 原本要求 Page 视图输出，修正后的反例保留原 `SIR-VALID-001`，同时要求不得误加查重缺 else 的 `SIR-VALID-004`。首次本地汇总控制台还遇到 GBK 不能打印 emoji，发生在 XML 和结果 JSON 保存之后；仅修本机汇总输出，不因此重跑测试。所有阶段 Maven 日志、实际 XML、命令参数、摘要与退出码都保留在 `target/static-rule-design/{red,red-confirmed,green,green-lf,language-examples}/`，这些不是已提交的 CI 证据。

### 9.2 CRLF 根因与 LF 快照

四个旧 fixture 的本机 CRLF 数分别为 cart-checkout=111、persist-order=43、weekly-booking=72、check-then-insert=35。前三类测试直接断言多行 LF 片段存在；唯一约束旧测试则因替换未命中，根本没有插入 unique，后加的 else 因此被原规则拒绝。不是 B 误伤已处理冲突的唯一写入。

从 `6945f278` 用命令级 `core.autocrlf=false`／`core.eol=lf` 导出排除 datasets 的独立源码快照，**1441 个基线文件逐项匹配 Git blob**，再覆盖本轮六个文件。四份 fixture 与工作区都只差 CRLF/LF，快照未改其语义。所有生产/测试及语言文档受测字节与当前文件按正常 LF 口径一致；设计说明后续补记不参与编译。没有批量转换工作区换行或修改 Git 配置、公共测试工具和原 fixture，核对在 `lf-snapshot-verification.json`。

### 9.3 命令、资源与范围

实际宿主为 Windows，Java 21.0.11、Maven 3.9.14；总内存 24093 MiB、运行前空闲约 3384 MiB、18 个逻辑 CPU。所有运行串行，不加 `-T`；去除 `JAVA_TOOL_OPTIONS`，Maven 限 heap 384 MiB／metaspace 192 MiB／ActiveProcessorCount=2，唯一测试 fork 限 heap 256 MiB／metaspace 128 MiB／ActiveProcessorCount=2。离线仓库为 `D:/maven-repo`，不是交接中 Linux 的路径和配额。

实际 GREEN 在 LF 快照执行，参数为：

```text
MAVEN_OPTS=-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2
mvn -B -o -Dmaven.repo.local=D:/maven-repo -pl sir-semantic -am test -Dtest=AnySelfComparisonSemanticsTest,PrecheckedUniqueInsertSemanticsTest,QueryDiagnosticMessageTest,CheckThenInsertWarningTest,WriteSliceSemanticsTest,RelationSliceSemanticsTest,ScalarQuerySemanticsTest,LoopBodySemanticsTest,BoundedEachSemanticsTest,RepeatSemanticsTest,PersistOrderSemanticsTest,UniqueKeySemanticsTest,ExplicitLockSemanticsTest,TypedReferenceSiteContractTest,ResolveOnceArchitectureTest,RelatedCountSemanticsTest,LegalSemanticTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -DreuseForks=true "-DargLine=-Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2"
mvn -B -o -Dmaven.repo.local=D:/maven-repo -pl sir-toolchain-application -am test -Dtest=LanguageReferenceExamplesTest -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -DreuseForks=true "-DargLine=-Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2"
```

这不是全量 Reactor 测试：`-am` 编译受影响依赖，`-Dtest` 只执行列出的用例。17 类的 235 项已包含新增 55 项，不能相加成 290；加文档 4 项为 239 项。没有运行 JaCoCo，不宣称已测得 80% 覆盖率。AFT 两次检查仍超时，未取得 fresh 诊断快照；编译与定向结果以 Maven 及新鲜 Surefire XML 为准，不将超时算作通过。

### 9.4 合法输入的新旧对照

正则扫描漏过动态 fixture，故在收尾追加一次有界兼容核对，而不是假设“零命中就零影响”。复用本轮 LF 编译的生产类路径，只把 `ValidatePass` 换成从基线 Git blob 原字节重新编译的旧实现作控制；两边都调用生产 `SirValidationApplication`，不另写解析/类型/生成算法，不使用本机旧增量 class。

输入为学生 v3 索引的 **48 份合格教师源码**（逐份从已提交 Git blob 读取并匹配原 source_sha256），加博客、check-then-insert、cart-checkout、persist-order、weekly-booking **5 份参考**。两边 **53/53 均完整静态生成，累计 1576 个文件的数量与生产版本化生成摘要逐项一致**。对照方法、输入路径/摘要和逐项结果在 `target/static-rule-design/legal-generation-comparison/comparison.json`，Java 编译/检查进程均限制 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2 并已退出。

这只是静态兼容性证据，不重写这 48 条的历史资格，不复用它们的成功代替新候选门，也没有更新学生格式或开始训练。4.2 的 g9 导入重查尚未执行。以上仅为本机证据；同 SHA CI、真实构建/启动/业务及全量门仍待验收。负责人已另行批准本批提交推送，后续 CI 结果由负责人通知后核对。
