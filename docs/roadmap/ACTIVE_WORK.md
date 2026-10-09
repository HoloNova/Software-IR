# 当前工作单：Q23 SLM数据闭环支援——SIR单文件批量校验入口

- 状态：**AWAITING_CI**（2026-10-09，限定真实评测试跑完成）。Q23静态入口本机122/0/0/0、新28；随后按负责人明确指令完成§11正/错误两样本实际构建启动与独立业务断言，正确样本BUSINESS PASSED、错误样本BUSINESS FAILED（预期拒错），新增限额测试2/IT1全部通过。仍待同SHA CI；不扩通用SLM平台/生产部署/Git/Q22恢复，原静态门不混算运行/业务结果。
- 切换：[Q22纯移动工作单](Q22-single-capability-source-move.md)按负责人指令BLOCKED（主动搁置，非技术失败），保留方案、不放completed；不得在本单恢复。G2仍未关闭，本单是已有编译链的实验入口支援，不宣称G阶段前进或进入G3。
- 执行依据：[MAIN](../../MAIN.md)§0/§1.1–1.2/§2.8/12/14、[AGENTS](../../AGENTS.md)测试资源约束、Accepted [ADR-003](../architecture/ADR-003-toolchain-application-owns-project-application.md)与CLI边界测试。
- 现有资格：[Q21归档](completed/Q21-multi-source-workflow-update-and-recovery.md)，f8a2f92同SHA run37865688743双门1049/0/0/5、五业务IT全部PASSED；**不抵扣本单**。
- 本轮：主会话完成实现/受限定向与规模实测，原规划阅读和文档证据保留；无Git/子代理/本机业务IT。用户的“G侧Agent”指当前SIR工具链，不另启动代理。

## 1. 用户流程与第一阶段交付

SLM产出大量单文件SIR → 脚本把样本逐行送入**同一个JVM** → 工具按指定深度校验 → 每行返回是否通过、最早失败阶段和完整诊断 → 调用方筛数据/回喂模型。

负责人确认的资格边界：静态ok=true仅进入候选池，不标为可运行/业务正确；独立评测总设计见[草案](../qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)。本轮明确授权其中限定课程试跑§11，其余通用平台仍需另审；不混入四阶段输出。现有五IT保护工具链已知场景，不代替每个模型样本的业务验收。

本单交付Application无状态批量校验API及只读CLI `kcg check`，支持PARSE / SEMANTIC / LOWERING / GENERATION四个stopAfter，默认GENERATION。编译和生成都在内存，不创建工程、不读写Bundle/CURRENT/锁、不构建生成工程、不连接数据库。不把静态通过等同“符合原始业务需求”或“生成工程已运行成功”。

新业务主要组合已有能力：不加SIR语法，不复制Parser/Resolve/Lowering/Generator规则；补的是一类模型样本无法高效批量调用的入口，不对课程写特判。第二阶段修复建议增强必须等STEP0失败分布，**不属于本单**。

## 2. 已直接核查的事实（代码/测试阅读，未运行）

| # | 已知事实 | 依据与设计影响 |
| --- | --- | --- |
| F1 | CLI只发布context/plan，参数要求绝对state/output/candidate路径；暂无check | KcgCli、CliCommandLine；添加独立入口，不借旧context参数绕路 |
| F2 | SirCompiler.compile(String sourceText, SourceId sourceId, List<ExecutionDiagnostic> accumulatedDiagnostics)返回Optional<CompiledProject>；空表示失败/诊断写入调用方list；成功含model/lowered/files/graph | internal/SirCompiler；null参数抛异常，阶段无stopAfter，调用总会进Graph，不能直接套壳满足需求 |
| F3 | ToolchainApplication实际复用另一内存助手SirCompilation.compile(String, SourceId, Function<SpringBootLoweredModel,GenerationResult>, List<ExecutionDiagnostic>)，再preflight/Graph/写盘；已有generation注入合同 | internal/SirCompilation、api/ToolchainApplication；保持两个旧签名、注入点、诊断投影和调用顺序，不把写盘入口用作check |
| F4 | ExecutionDiagnostic没有related/fixes；DiagnosticMapper.fromParser只留主span，fromProjectSource把related转成额外INFO并改写文本；Lowering的sourceSymbol/sourceNodeId也被旧映射舍去 | DiagnosticMapper、ExecutionDiagnostic；新入口必须保存原始诊断并用新typed DTO，不更改旧诊断合同 |
| F5 | 原Parser/semantic Diagnostic含code/severity/message/primarySpan/related/fixes；LoweringDiagnostic还含sourceSymbol/sourceNodeId；GenerationDiagnostic含code/message/nodeId（Failure均ERROR） | 三诊断record；输出不仅保示例字段，还保存阶段实际附带的身份信息，不伪造原阶段没有的位置/建议 |
| F6 | SourcePosition行列1起、offset为Unicode code-point/0起，SourceSpan左闭右开；旧Parser仅支持0.1，strip SIR开头BOM | source/SourcePosition、SourceSpan、HeaderCompatibilityCheck、DefaultSirParser；固定逻辑SourceId，保持位置/正文契约，不手动重写语法 |
| F7 | ProjectSourceReader.MAX_FILE_BYTES=1,048,576；旧单文件SourceReader.readStrictUtf8本身是无大小上限的readAllBytes | 两reader；本单显式按SIR严格UTF-8字节计1MiB，不误称旧单源已有这个守卫，不调用文件reader读样本 |
| F8 | CLI只有JsonStringEncoder输出转义，无JSON解码器、无JSON库依赖；全仓生产FixHint仅API Diagnostic/FixHint两文件引用，未发现创建提示的生产调用 | JsonStringEncoder、两POM、全仓Java搜索；建议受限JSON输入解码、不加依赖；fixes可空但传输不能丢，合成提示只证明传输，不宣称已有生产覆盖 |
| F9 | CLI生产门禁禁止命令类引用Files/文件API、Application/Change内部与generator；非命令mvp例外不可调用 | CliProductionBoundaryTest；第一阶段文件由shell重定向stdin，不新增CLI文件打开逻辑/例外 |
| F10 | Application valid根目录20个.sir均能绑定到现有生成/变更/业务/改名测试资源用途；multi-course目录4源是0.2，不纳入 | 见§7清单；必须P1核对现有成功断言并实测，目录名本身不证明合法 |
| F11 | 两既有非法源分别被ToolchainStageFailureTest断言SEMANTIC、LOWERING；文本not valid SIR被ToolchainApplicationFailureTest断言PARSE | invalid/semantic-unresolved-name.sir、actor-non-identity.sir；后者不是semantic失败，不能凭名字猜 |
| F12 | 当前仓库及/root/workstation限定相邻检索未找到STEP0_EXPERIMENT_HANDBOOK.md | 不生成同名猜测手册，不声称已读/已同步；接口按本需求锁定，手册可得后另核第4/6/7节 |

## 3. 第一阶段协议（以下推荐值均随工作单确认冻结）

### 3.1 输入与流式处理

CLI无编译参数，默认stdin：`kcg check < samples.jsonl`。这是假定现有Java/classpath启动器已经备好的命令形式，不承诺已有kcg独立发行脚本；交付需提供可实跑的单次 `java -XX:-UsePerfData -cp <已有classpath> io.kcg.cli.KcgCli check` 调用说明，不用每样本Maven exec/JVM，不新增打包/安装工程。输出默认stdout，脚本可自行捕获；没有--state-root/--output-root/--output文件参数。

UTF-8 JSONL，LF或CRLF分行，末行无换行也算一行；空白行是一个坏样本，空文件零行。末尾一个LF不额外制造空行。每行只能是一个对象，允许JSON合法空白/转义与任意字段顺序，拒绝重复键/未知键/尾随内容/非字符串值/嵌套对象数组。字段仅id、sir、stopAfter。输入守卫的错误优先级固定：批/原行预算→UTF-8/JSON语法→键/schema→id→sir→stopAfter；同一层按预定字段顺序选一个输入ERROR，不依赖Map迭代/Locale。编译诊断则保留全部原条目。整条JSONL外壳不允许BOM；sir字符串开头BOM仍按旧Parser处理。

| 字段 | 推荐合同 |
| --- | --- |
| id | 必须是严格Unicode字符串，允许空值字符串，UTF-8≤1KiB；值原样回传，重复值允许，不用于缓存/排序/SourceId/编译身份；不承诺保留原JSON转义拼写 |
| sir | 必须是非空白字符串，严格UTF-8≤1MiB，原文保留；空/null/错类型/非法代理字符/超限走单行输入错误 |
| stopAfter | 可省，默认GENERATION；仅四个大写token，null/其他值拒绝；不是拿执行到GENERATION的结果换一个stage标签 |

推荐**每批最多10,000条进入样本校验**，按物理行计（坏行也占名额）；第10,001行起每行仍返回BATCH_LIMIT，不再解析字段/编译。为了保持一一对应，超限尾部仍流式读到EOF并逐行给错误；这里的上限是接受/执行上限，**不是超过后截断输出**，尾部id=null。若负责人要求物理总行数硬截断，则与“所有输入逐行对应”冲突，须另裁决，不能静默采用。

原始JSON行另限**8MiB**（不含LF/行尾CR），覆盖1MiB SIR在JSON中最坏约6倍转义膨胀及字段开销；有界字节framing，超限丢弃到下一LF并输出一行错误，不先readLine/readAllBytes无限分配。推荐先校验UTF-8再做受限顶层对象/字符串解码；非法UTF-8只损坏本行。合法协议只一层对象/字符串，但错类型/未知字段时仍需有界校验整行JSON语法，跳过非法shape的值而不构造通用AST，确保合法id在字段前后都可原样取得；预算外或语法未闭合则id=null。跳过值用有界迭代结构栈（建议JSON嵌套上限32，只对协议不允许的嵌套输入生效），不以递归解析承载任意深数组。解码过程递增核验字节/字符串预算，不先构造超大字符串再拒绝；处理一行、输出一行、释放该行model/files，不持有整批List或全部结果，不缓存相同SIR。每行有独立诊断collector/解析与分析上下文，不能将SirCompiler的accumulatedDiagnostics或上一行model/ERROR跨样本复用；共享的是编译步骤和JVM类加载，不是样本状态。

### 3.2 输出形态、失败阶段与确定性

每物理输入行对应一条canonical UTF-8 JSON + 单个LF，无日志/摘要/进度混入stdout，字段顺序固定：

```json
{"id":"A1-1","ok":false,"stage":"SEMANTIC","diagnostics":[{"code":"SIR-…","severity":"ERROR","stage":"SEMANTIC","message":"原诊断文本","span":{"sourceId":"sample.sir","startLine":12,"startColumn":9,"endLine":12,"endColumn":18,"startCodePointOffset":100,"endCodePointOffset":109},"related":[],"fixes":[],"sourceSymbol":null,"sourceNodeId":null,"loweredNodeId":null,"relativePath":null}],"fileCount":0,"digest":null,"sourceSha256":"<原文64位小写SHA-256>"}
```

示例是字段结构，不是已有诊断码/位置或哈希的运行证据。related每项含原message/span；fixes每项含原message/span（来自replacementSpan）/replacementText。阶段实际附带的identity/path字段有则原值输出、无则null。主span无位置则null、related/fixes无则[]；绝不把相关位置塞成独立INFO或改写原消息。数组保留原阶段产生的顺序，四阶段顺序固定；不截断诊断、字段或提示，不排序掩盖核心顺序不确定。

- ok：所有请求阶段无ERROR且请求阶段成功产出对应结果才true；WARNING/INFO保留，不当失败。
- stage：最早失败编译阶段；成功为实际最后阶段。始终只有PARSE/SEMANTIC/LOWERING/GENERATION，**不跑/不暴露GRAPH**。
- 输入格式/预算错误还没进入Parser，推荐stage=PARSE表示未过首关，诊断用独立`KCG-CHECK-INPUT-*`/`KCG-CHECK-LIMIT-*`code区分；不伪造SIR span，不加INPUT/READ打破实验记录四值。
- JSON完整解析且id合法时，即使sir/stopAfter失败也回传id；JSON语法/编码/原行/总行预算错、缺/非法id则id=null。不从半截JSON用正则猜id；合法id不参与其他判断。
- 以固定逻辑SourceId **sample.sir** 编译所有样本，不来自id/输入文件/工作目录。改变id只能改变输出id；不能把绝对路径、异常栈、时间戳/耗时/RSS等放进样本输出。编译原消息原样保存，Application新输入/内部消息固定且Locale无关；若核心原诊断本身不确定，报告，不改文本掩盖。
- 单行用户错误正常返回，不逃逸；阶段内RuntimeException被行边界转换为本阶段固定内部ERROR、继续下一行，不暴露getMessage/栈。P2须另测大小内深嵌套/StackOverflowError的行隔离，不笼统catch Throwable掩盖VM故障。OOM/进程终止、stdin读失败/stdout不可写不属于可完整继续的普通坏样本，明确进程级失败，不能报整批成功。

推荐退出码：完成整批传输为0（普通样本ok=false是数据结果，不是命令失败）；CLI参数错误2、流I/O不能完成3、捕获内部错误/不可恢复内部故障70。可捕获内部错误时仍先完成其他行；已有context/plan退出码/输出行为不变。进程级错误不能伪装成某个不存在的样本行；stderr仅固定不带路径的运行错误。没有内部并行度/线程池选项。

### 3.3 两种哈希，明确含义

**sourceSha256（推荐新增）**：JSON解码得到sir的严格UTF-8原字节SHA-256，含BOM/原换行/空白；不含id/stopAfter，不做Parser归一化。sir合法类型/编码且≤1MiB时即使编译失败也提供；无法取得合规sir时null。用于区分输入文本，不证明业务等价。

**digest**：仅GENERATION成功时提供，fileCount=生成文件数；所有更早stop/失败为0/null（sourceSha256独立）。生成文件不写磁盘；排序按relativePath的String.compareTo（Unicode序、Locale无关、路径保持原正斜线）。推荐冻结无歧义域：

`SHA256(ASCII("KCG-SIR-CHECK-GENERATED-V1\0") || uint64BE(fileCount) || 对每文件[uint64BE(pathUTF8长度) || pathUTF8 || uint64BE(contentUTF8长度) || contentUTF8])`

全部长度是字节数，内容不规范化、不用含歧义的裸字符串拼接、不只散列单文件hash、不含artifactId/owner/绝对输出根。输出64小写hex。独立测试用向量覆盖路径/内容边界、排序、Unicode/CRLF/空内容；此域只用于校验去重，**不能当Bundle/基线/manifest SHA**。不新增“编译缓存”功能。

## 4. 放置与最小复用设计

推荐API名 **SirValidationApplication**，同模块api新增独立SirValidationRequest/ValidationStopAfter/SirValidationResult/ValidationDiagnostic与typed batch stream请求/回调合同（字段名称在P2冻结）；单样本和流式批量共用同一个校验方法。CLI只解析严格命令行、传stdin typed请求、用JsonStringEncoder/canonical renderer渲染结果；**JSONL framing/逐行解码/输入守卫及编译编排在Application**，CLI不引用内部编译器/Generator或打开文件。流由调用方拥有，不擅自关闭stdin/stdout；输出sink故障不是某样本语法错。

不能直接使用SirCompiler返回的ExecutionDiagnostic恢复丢字段。推荐在Application internal抽出**一份按阶段运行、保存原始诊断的共享内存子流程**：parse→analyze→lower→generate，支持真正stopAfter；SirCompiler和SirCompilation两个旧方法作为兼容包装继续投影到旧ExecutionDiagnostic，前者仍在生成后走Graph、后者仍复用generationStep，新入口只到请求阶段。原始diagnostic由新DTO无损保存，旧DiagnosticMapper各方法/顺序与公开合同不改变。已核调用还包括ChangePlanning/ChangeExecution的SirCompilation.compile，以及ProjectCompilation的lowerAndGenerate；因此旧多源Q18–Q21编译回归也在受影响面，不因新入口只支持0.1就省略。

不新写第三套四阶段直串，不改Parser/Semantic/Lowering/Generator的生产规则，不为校验绕过Normalize或在后阶段按名字恢复语义。共享子流程需保留旧失败/INFO/WARNING及generation注入路径；必要时阶段原语先提取再由旧包装调用，测试证明旧字节/行为相同。若必须修改旧Application公开签名、扩大语言/Target或修改既有诊断文本，停轮报告。

JSON没有现成读库：建议仅实现本协议的严格顶层对象/字符串解码（完整JSON转义、Unicode代理对、重复键/尾随/错误定位与预算），不是通用JSON AST库。先做边界向量/P3；不能偷偷用transitive依赖或复制语义。P3需覆盖合法id前/后遇错类型/未知字段的同等回传、无通用JSON树的有界跳过及嵌套预算。若最小解码器仍不能严谨满足JSON输入，明确列出依赖候选/理由/影响，请负责人另批，不能自动添加第三方包。

顶级help需列check并说明stdin/只读校验；**context --help、plan --help、VERSION及现有操作token/参数/JSON协议字节不改**。现有generate/register/apply/recover仍不发布。CliProductBoundaryTest旧正反断言保留，增加check测试与精确旧help回归；不以删除门禁/扩大mvp例外过关。MAIN当前CLI能力描述仅在交付时同步，不改架构规则。

## 5. 先决核验与实施顺序（确认后才运行）

| 阶段 | 要做什么 | 必须交付的证据/停轮条件 |
| --- | --- | --- |
| P1 | 锁定20个0.1合法fixture及两非法的现有测试依据；旧SirCompiler/SirCompilation/Toolchain输出与诊断记录，确认真实消息/顺序/位置 | 清单逐项现有成功断言+独立实测；任何名义valid实际失败先报告，不静默排除/改fixture或语言 |
| P2 | 先共享阶段子流程/旧兼容包装，再无状态typed单行入口；四stop与无损诊断/两哈希/输入guard | 计数spy证明后阶段未调用，真实/合成diagnostic原值对应；普通坏输入/内部注入/深嵌套隔离，旧公开合同/行为/旧Golden不变 |
| P3 | 有界JSONL批流、strict解码、串行处理、canonical CLI/exit/help；批预算/超限恢复与stdin/Unicode/重复键向量 | 单JVM同序/坏行隔离、总行与单行边界、文件重定向和stdin、字节确定/零写盘；不能靠readAll、并发/临时文件/隐藏Graph实现 |
| P4 | 定向回归+固定N=1,000单JVM规模测量；文档接口/调用说明/手册可得时同步；申请Git后CI | V1–V9逐项证据、本机时间/heap峰值/Metaspace/RSS与处理计数，未跑门NOT_RUN；不足则报告实测，不凭空给吞吐承诺 |

上表是先决顺序；P1–P4的最终本机结果见§10，源码阅读不单独当通过，CI/手册仍NOT_RUN；限定真实评测的后续结果见§11。第一阶段四stop一起交付，**不先交SEMANTIC后默认宣布完成**。P2/P3若暴露核心普通非法输入会抛出不可隔离故障，先报告并明确边界，不借本单改业务语义。

## 6. 推荐决策（负责人确认后执行）

| # | 推荐 |
| --- | --- |
| D0 | Q22主动搁置，Q23只做第一阶段，四stop同时交付，不扩G2/G3 |
| D1 | 两旧编译助手共享原始诊断的阶段子流程，旧签名/投影/注入/Graph行为不变 |
| D2 | 串行、同一JVM、逐行输出/释放，不搞线程池或编译缓存 |
| D3 | 命令check、stdin；文件用shell重定向，CLI无文件I/O和新依赖 |
| D4 | 输出追加sourceSha256，固定sample.sir；id字符串/1KiB，不可取得时null；扩展span offset/source及各阶段附带身份字段以无损 |
| D5 | SIR1MiB/JSON行8MiB/错shape跳过嵌套32/10,000行接受上限，超批尾部逐行BATCH_LIMIT/id=null并不编译；不截断对应输出 |
| D6 | 四stage不加值，输入guard标PARSE并用独立code；处理完普通样本批exit0，运行/内部故障另码 |
| D7 | 固定长度framing的路径+原内容digest，sourceSha另算，不混用基线散列 |
| D8 | 规模首测N=1,000完整生成，报告实测不预设达标秒数；更大规模留CI/负责人决定 |
| D9 | 二阶段FixHint增强等实验失败分布；不存在的手册只标未同步，绝不造内容 |

D4–D6是需求中未完全定义的错误/预算补充合同，不作为现状或默认授权。负责人如需任意JSON类型id、物理硬截断/不同退出码，改工作单后再确认。

## 7. V1–V9验收落点与资源合同

### V1 清单：候选20个根目录单文件，不能凭目录名验收

| 来源组 | 文件（位于Application test/resources/valid） | 已阅读现有依据 |
| --- | --- | --- |
| campus八份 | campus-market.sir；campus-market-candidate.sir；campus-market-actorless-readonly-base.sir；campus-market-candidate-modify-input-field-constraints.sir；campus-market-minimal.sir；campus-market-minimal-add-search-goods.sir；campus-market-two-capabilities.sir；campus-market-two-capabilities-remove-publish-goods.sir | ToolchainHappyPathTest/ApplicationTestSupport、ChangePlanningApplicationTest/support、ScenarioMaterializer.compileBase、ChangeFaultMatrixSupport.prepare生成/应用成功路线 |
| course九份 | course-admin.sir；course-catalog.sir；course-enrollment.sir；course-admin-enrollment.sir；course-admin-enrollment-filter-any.sir；course-admin-enrollment-tighten.sir；course-admin-enrollment-remove-update.sir；course-admin-enrollment-readonly.sir；course-admin-enrollment-add-list-refs.sir | Q9/Q10/Q11业务资源、LegacySourceEvidenceCompatibilityTest、ChangeLoopPlanningContractTest/ChangeChainTestSupport真实完整链 |
| directory两份 | fault-matrix-dirs-base.sir；fault-matrix-dirs-candidate.sir | CreateFaultMatrixTest目录故障用例→ChangeFaultMatrixSupport.prepare显式pair |
| rename一份 | rename-course-search.sir | RenamePlanVerticalTest/RenamePreflightTestSupport.revision、RenameApplyEndToEndTest |

以上是代码用途清单，不是本单运行结果。P1逐项登记SIR版本、现有成功测试调用/断言及实际GENERATION结果；所有被现有测试视为合法的0.1必须ok=true，若实际冲突不能改名字/删除或算skip。multi-course四文件/片段一律不入V1；另做0.2反例确认不是暗中支持。

| 门 | 正向/反例与证据 |
| --- | --- |
| V1 正例 | 上述合法单文件清单逐项默认/显式GENERATION true；另测试四stop stage准确，GENERATION有count/digest、其余0/null；正文BOM/CRLF/Unicode边界依旧语义 |
| V2 反例 | not valid SIR→PARSE；semantic-unresolved-name.sir→SEMANTIC；actor-non-identity.sir→LOWERING；受控generation故障→GENERATION。与原阶段实际diagnostic code/message/severity/主span逐值比；Related真实重复声明/ID案例、FixHint无生产案例时合成无损传输注明；早stop对晚失败应成功，不能全链跑后伪装 |
| V3 同规则/产物 | 同一合法样本固定SourceId，经新入口和未改公开ToolchainApplication在测试独立临时根生成；逐路径/逐UTF-8字节相同及独立digest向量。测试准备/旧入口写盘在checker只读测量外，不能把V3写盘说成checker写盘。正常检查不做Graph/落盘/build |
| V4 行隔离 | 混批合法/parse错/semantic错/lowering错/空SIR/1MiB±1/JSON错/非法UTF-8/缺id/重复key/未知key/错stop/id/代理对/原行8MiB±1/批10,000±1；每行一结果同序，好行仍成功，处理计数无多JVM，旧id值未被改写；边界大批优先用PARSE或fake编译计数防重复重活 |
| V5 确定性 | 相同整批两独立JVM stdout逐字节同；默认/土耳其Locale、不同工作目录/id、重复id/输入顺序与转义/Unicode核验；输出无时间/path/RSS。串行无并行变量；诊断不截断/不改文本；digest/sourceSha域向量 |
| V6 只读 | 预备cwd/输入/哨兵/仿state/output树后指纹前后同（类型/路径/文件内容等沿既有做法，atime不当写盘证据）；新call graph不达FileTransaction/锁/Bundle/Graph/Filesystem写API。子JVM在已有classpath、禁UsePerfData，不夹带Maven构建；stdout由测试内存捕获，调用方重定向文件不算工具创建。拒绝/超限/内部隔离也测 |
| V7 规模 | 1,000同一合法完整SIR/不同id，从生成器式输入流到摘要/计数sink，单JVM完整GENERATION不缓存；报告输入/输出行数、ok/失败/阶段count与统计摘要、墙钟、heap/Metaspace峰值及外部进程RSS峰值，JDK/机器/限额/fixture/SHA。度量不写样本stdout，不预设速度或把1,000说成10,000实测 |
| V8 旧合同 | 原Application/CLI定向类不删改断言/skip/exclude，API签名、旧诊断映射、generation/Graph注入/失败阶段、context/plan JSON/help/exit保持；旧生成goldens。顶部help仅增加新命令，四未发布写命令仍拒绝；新旧架构门非空有灵敏度探针 |
| V9 验证口径 | 本机只指定受影响Application/CLI及必要上游类，不全量、不跑业务IT；规模测量一次有效结果不重跑。双全量门及**现有五IT（四旧+Q21）**提交授权后CI、同SHA/run/artifact；不能按旧需求少跑第五IT或沿用Q21证据。手册未可得/CI未跑如实NOT_RUN |

旧回归最低清单：ToolchainHappyPathTest/ToolchainDeterminismTest/ToolchainApplicationFailureTest/ToolchainStageFailureTest/ToolchainGraphStageFailureTest/ApplicationArchitectureTest，ChangeLoopPlanningContractTest/RenameApplyEndToEndTest，MultiSourceGenerationTest/ProjectBaselineContractTest/ProjectChangePlanningApplicationTest/ProjectChangeExecutionApplicationTest/LegacySourceEvidenceCompatibilityTest；CLI的KcgCliWorkflowTest/CliProductBoundaryTest/CliInternalFailureTest/CliProductionBoundaryTest及新check协议类。实际新增/调整后的指定类命令和去重XML索引据受影响调用补齐，不跑整个Reactor或故障矩阵集来替代定向判断。

资源：当次nproc/free计算余量，重活按AGENTS显式systemd MemoryMax/CPUQuota，串行/no Maven -T，Maven/fork/JVM/Metaspace各自限额。规模建议独立指定类/单JVM，无每行启动，处理完查遗留进程；报告实测，不在本轮规划运行。CI无环境记NOT_RUN，不为触发CI自动commit/push。

## 8. 明确不做与允许修改面

确认后只允许：Application新增typed独立校验/批流/诊断结果及共享内部阶段子流程/旧兼容包装；CLI新strict check参数/renderer与有限JSON转义补强（有反例且旧输出不变）；定向/协议/规模测试和文档。需要的架构记录按开工实际编号分配，不能把Q22未创建的ADR当Accepted。

不修改ToolchainApplication/ChangePlanningApplication/现有编译助手公开签名，不加Parser/Semantic/Lowering/Generator语义/业务/目标/语言，不接工程状态/Graph/多源0.2/CLI写生命周期，不建HTTP/常驻daemon/数据库/缓存/新第三方依赖，不增强FixHint生产覆盖、不代做SLM实验/训练/生成数据集。不能自动重启Q22或把旁路实验视为G2完成。不承诺新fat JAR/发行脚本，提供当前Java启动方式即可。

普通失败字段、raw诊断及共享旧流程是实现必须核验的点；最小JSON解码、大小内极端输入隔离、手册集成如遇事实冲突需报告，不基于假设开工。读源码无需升级为生产可用结论。

## 9. 实验手册接口与本轮记录

STEP0记录约定继续使用 `ok`、`stage`、`diagnostics[].code`，四stage大小写不改；sourceSha256等是向后兼容追加，原样例字段不改名。手册当前不在可检查仓库/限定邻近目录，**手册第4/6/7节同步NOT_RUN**。可得后先读再对齐，不创建假的手册；其缺失不阻止按已确认需求实现工具入口，但阻止声称完整实验对接已验证。第二阶段必须以负责人失败分类另起工作单。

本轮已读取MAIN/当前工作单并核对git状态（HEAD f8a2f92，原Q21归档/Q22设计文档差异保留），核查上述API/POM/源跨度/既有测试及20资源用途；Q22主动搁置后保留独立单。规划时全部P1–P4/V1–V9 NOT_RUN，未改生产/测试/CI，无构建/编译链或测试运行/安装/Git操作。随后负责人确认方案，按本单D0–D9推进实施；静态与运行/业务资格分开，真实工程评测仅规划，最终实施证据如下。

## 10. 实施与本机证据（2026-10-09）

### 10.1 实现及合同

独立SirValidationApplication/typed单行与批流请求、结果/summary/无损diagnostic；CompilationStages共享四阶段，旧SirCompilation.compile/lowerAndGenerate/SirCompiler签名、generation注入、旧投影与Graph行为不变。新check真stop、raw trace保related/fixes/身份/位置/原消息，后阶段内部异常仍保前诊断。JSONL framing/Unicode/byte预算/shape跳过与逐行隔离归Application；CLI新check仅传stdin/渲染，无File I/O、无依赖/Parser等核心语义修改。重复sir键不选第一值算hash，sourceSha=null；重复id键不猜id。

旧context/plan帮助和VERSION由HEAD字节hash锁定，原测试/skip/exclude不动，顶级help新增check。新Renderer只对本输出的孤立surrogate无损转义、复用旧JsonStringEncoder且不改其旧输出。[操作协议](../SIR_BATCH_CHECK.md)提供已实跑的Java21/classpath命令；[Proposed ADR-025](../architecture/ADR-025-batch-static-sir-validation.md)待CI/最终验收再Accepted。静态通过只进候选池；静态收口当时[真实评测总设计](../qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)仅草案；随后负责人授权的限定试跑见§11，其余平台未实施。

### 10.2 定向结果与各门

共24类去重**122 run / 0 fail / 0 error / 0 skip**，其中7新增类**28项**：ValidationFixtureProbeTest2、SirValidationApplicationTest8、SirValidationBatchTest8、SirValidationFaultTest3、ValidationArchitectureTest2、SirValidationScaleTest1、KcgCheckTest4。测试数取Surefire XML逐case，不把20资源/1000样本算1000测试。旧单源生成/故障Graph/工作流规划/多源生成与基线/规划与apply/改名apply/旧四业务输出golden/CLI四旧类及新旧架构回归均通过。

- P1/V1：现有20个合法0.1源全部实际GENERATION成功（文件数10–35），完整清单见§7；两非法分别SIR-FLOW-001 SEMANTIC(26:16–27)与SIR-LOWER-FEATURE-001 LOWERING(28:16–26)。源码用途不是资格依据，以上有实测。
- P2/V2/V3：20源新结果与旧Toolchain实际写出的全工程逐相对路径/逐UTF8字节一致、独立framed SHA对齐；四stop观察前缀、early SEMANTIC避开已知LOWERING失败，generation failure保原code/text/node；重复ID真实related、合成fix只证明传输不宣称生产提示；前原始诊断在后阶段异常仍保留；普通Runtime与6000层表达式StackOverflow隔离/后行正常。
- P3/V4：混批、非法JSON/UTF8/空/缺字段/未知重复key/错shape与id前后顺序/stop/代理对、SIR1MiB与行8MiB±1、CRLF/末行/空文件、批10,002逐行limit、32层迭代预算、caller stream不关闭/传输失败不当样本错均通过。
- V5/V6/V8：两独立JVM不同cwd/英语土耳其Locale stdout逐字节一致、哨兵/state/input树指纹不变，checker/transport无直接File/状态/Graph引用且有故意writer灵敏度；CLI原边界闸门无违规，旧help/version字节golden与旧工作流/故障/未发布写命令保留。Java最小classpath流式冒烟合法SEMANTIC+坏PARSE同JVM对应，stdout无附加日志。
- V7：规模实测见下。V9：仅指定类定向，无全量/业务IT/部署，全部CI新门NOT_RUN。

### 10.3 规模/资源（不是业务运行证据）

实际机器nproc=2，总内存3875MiB，开工available2275MiB。每次测试systemd MemoryMax=2G/CPUQuota=150%，Maven Xmx512m/Metaspace256m，fork Xmx384m/Metaspace192m；CLI子JVM Xmx256m/Metaspace128m/-XX:-UsePerfData；无Maven -T/重活并发。

单独SirValidationScaleTest、同一Java21.0.12.1 JVM流式生成1,000条course-admin-enrollment/不同id，不缓存/不保存结果List。**1000/1000 GENERATION通过，每条35文件；12,536,061,220ns（12.54s）**，含输入流/校验/摘要/计数断言、不含CLI渲染，更不含生成工程构建/应用/DB。digest=cd19593d66700fb92a6c274d8e60a5f5eab577c30ab229670e4bae7aee428f98。进程VmHWM=167700KiB（约164MiB），Metaspace峰值14,253,008bytes；Heap各池peak Eden31,457,280/Old6,516,944/Survivor2,097,152bytes，合计约38.22MiB是**各池峰值之和的上界，不是同一时刻总堆峰值**。不是1万实测/任意SIR性能承诺。

规模只跑一次；测量之后只加固异常诊断trace与重复sir歧义拒绝，正常样本生成/摘要未改变，未重复规模或已通过旧回归。最后只针对受影响边界补验35项全绿；以XML索引去重122，不叠加重复执行计数。

### 10.4 原始证据与待门

日志：/tmp/q23-p1.log、q23-app-tests.log（历史测试构造失败及修正）、q23-cli-tests.log、q23-scale.log、q23-final-regression.log、q23-final-boundary.log；去重索引/tmp/q23-testcase-index.txt。旧Q21历史证据与本单分开。CI须授权提交/推送后同SHA双门+五IT并上传两类artifact，目前NOT_RUN。STEP0手册同步NOT_RUN；真实工程评测全部NOT_RUN。以上是静态入口收口时的历史状态：未提交/推送/安装/启动数据库或应用；Q22仍BLOCKED、G2未关闭。后续限定评测如下，不倒写静态资格。

## 11. 负责人追加授权：限定真实工程评测（2026-10-09）

负责人要求“先进行真实工程评测”。此授权不扩为通用运行服务：复用Q11已有课程原始需求、DDL/seed与HTTP/JDBC oracle，正样本course-enrollment.sir，反样本仅去掉报名状态过滤（仍合法），两个均必须Q23 GENERATION通过→同生成digest核验→实际Maven构建→Spring Boot启动→同一业务oracle（仅有ACTIVE报名的课程出现，cancelled-only/无报名排除，分页/投影与三表前后数据指纹）。期望正样本BUSINESS PASSED，反样本BUILD/START通过但BUSINESS FAILED，评测的拒错灵敏度才通过；不把受控反例当实际SLM错误率。

当前HEAD f8a2f92、Q23尚未提交，现有CI不可覆盖工作区；本机无MySQL镜像/容器/监听，但生成工程依赖已存在、2核/available2469MiB。允许临时MySQL8.4、仅loopback33306、独立本轮volume，不使用已有卷或改env.sh；db限512MiB/CPU50%，Java任务systemd scope1.5GiB/CPU100%且Maven/fork/生成工程/应用各自显式Xmx/Metaspace，不并发重活。现有Harness清空child env且未显式限制生成Maven/应用，需要只在test harness增加显式可选资源参数/小型opt-in试跑IT；不改任何生产编译/生成语义、CLI/校验协议或现有测试断言。

沿env.sh schema/runtime权限、既有advisory lock，Harness拒绝非本任务schema、按测试DDL初始化，结束关闭应用/删除本轮schema、释放锁、停止/回收本轮容器与卷、保留报告/脱敏日志。不运行全量/五IT，也不自动Git。证据必须注明base SHA+dirty源树manifest SHA，不冒称同commit CI资格；静态/build/start/business独立记录，环境不可得记NOT_RUN。手册/真实SLM题目样本未提供，本轮只验证已有题目评测链，不猜新题目业务真值。以下回填实际结果，不替代CI或SLM新题目资格。

### 11.1 实际结果与清理

[限定评测报告](../qualification/Q23_REAL_EVALUATION_PILOT.md)：两样本GENERATION均成功且批准digest=实际22文件逐字节核验；均离线Maven clean verify成功、Spring Boot真实HTTP就绪。正确课程total4/codes CS101/CS102/MAT101/ZOO101，BUSINESS PASSED（13个Harness检查通过）。漏状态样本total6，误带ART101/ENG101，第二页也错；BUSINESS FAILED（11通过、2预期业务失败），oracle灵敏度通过，不能标它业务正确。两个样本三表course7/enrollment37/student3原行列表前后相同。

JUnit SlmGeneratedProjectEvaluationIT1/0/0/0（涵盖2样本）；ConformanceJvmLimitsTest2/0/0/0。仅test harness可选child限额、新opt-in IT/资源，产品逻辑与原测试断言不改；不把静态122和当前3项重复累计成全量门，不新增skip/exclude、不重跑已有效范围。限额试跑IT63.48s、整命令93.49s，scope OOM0；Java单进程最大RSS约243.5MiB非合计峰值。MySQL8.4.11、Java21.0.12.1/Maven3.6.3；具体JVM/容器限额与指标口径见报告。

受测base f8a2f92 + dirty源码manifest SHA 980cfa09233901fe3ae1ca89b5642db2bc0840f1873fd2b6ac64a5dc74c1c643，756文件运行后逐SHA复核未变。证据目录/root/kcg-conformance/evidence/slm-{correct-1a11ec8b559-961,missing-status-1a11ec93939-4165}/与q23-real-evaluation-source（完整受测源树/manifest、错误工程源、XML及脱敏日志）。这是本机绑定工作区证据，不冒称同commit CI。

两应用退出、两schema不存在、advisory lock释放均报告证明；错误工程先归档后清work。本轮新容器/卷/镜像均回收，最终进程表无java/mvn/mysqld，18080/33306无监听；已有env.sh/卷未改。静态规模不重跑，五旧IT/全量/手册/真正SLM集合与通用评测故障矩阵仍NOT_RUN。无commit/push、Q22继续BLOCKED；状态回AWAITING_CI。
