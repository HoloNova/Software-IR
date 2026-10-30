# Parser 高频错误提示设计

状态：**负责人已确认；本机实施、定向 TDD 与兼容核对完成，未提交，CI NOT_RUN**。

基线：`33c2f4bc09e88b91631066ae14f8edd70bb19fae`。此时已有 **49 条合格教师答案、学生 v4**，未训练。本设计对应交接 4.3，不修改已冻结题目、候选、Prompt 或判卷标准。

## 1. 为什么存在

历史错误常只得到“遇到不符合 SIR 语法的内容：as / and / max”，教师需要猜缺失结构。先对真实记录去重、定位根因，再补最常见三类的说明，比继续扩充 Prompt 或反复付费重答更直接；所有使用名字、字段约束和分页的业务都受益。

本次选择**在现有解析失败回调中改善消息**，不是语法容错、自动修复或语义规则。不能让以前拒绝的写法变成合法，也不抑制后续级联错误。统计和原 Parser 的观察记录在 [parser-diagnostics/README.md](../parser-diagnostics/README.md)。

## 2. 统计范围与前三类

只读取固定 Git 提交中的 `datasets/sir/**/*.json`、`*.jsonl` Blob，不读 `target`、Prompt 中的错误码示例，也不把研究摘要或检查器 stdout 再计为候选。

- 163 份 `checked*.jsonl`，394 行（参考 152、教师 242）。其中 95 行教师检查含 `SIR-PARSE-001`，累计 214 条该诊断。
- 去掉重复检查和跨工具链导入后，剩 **48 个教师候选响应 ID**；对应 **39 种源码字节**、53 个候选/工具链组合。此处响应身份不等于整个项目的付费调用次数；无源码的网络失败不在分母内。
- 每个响应按最早可定位的解析根因只归一类；后面的 `persist`、`entity`、`}` 等恢复错误不另计。全部 39 种源码已逐项审阅，分类输入和依据可复现。本次按交接限定，以含 `SIR-PARSE-001` 的记录建样本，不等同于所有解析失败的总体分布。
- JSON 中还有 214 条 stdout 副本、56 条研究摘要副本，均另列清单、不加到 214 条权威检查诊断上。

| 排名 | 根因 | 响应 ID | 不同源码 | 原始检查行 |
| --- | --- | ---: | ---: | ---: |
| 1 | `target` / `order` 等关键字用作字段或工作流结果名字 | 12 | 12 | 25 |
| 2 | `where` 约束之间漏逗号，用 `and` 或直接相邻 | 11 | 6 | 16 |
| 3 | `Page 页码, 大小` 后直接 `as`，漏 `else 错误名` | 8 | 4 | 12 |
| 4 | 把 `exists/count` 工作流语句或虚构调用嵌进表达式 | 5 | 5 | 7 |
| 5 | `credential` 之后又写 `field` | 4 | 4 | 25 |
| 6 | 无分页的 `find` 在 `as` 之后追加 `else` | 2 | 2 | 3 |
| 其余 | 显式 `post`、return 绑定块、匿名 input 等六类 | 各 1 | 各 1 | 合计 7 |

按响应身份排序，前三类涉及 31/48；**这不代表有 31 份都缺少专用提示**。第一类中 6 份在当前检查器的首条消息已明确指出关键字，仅其余 6 份首条仍为“多余的语法元素”。

若改按不同源码排序，前三为关键字 12、约束 6、内联查询 5，分页为 4。本设计采用“每份真实保留响应的失败”作为主排序，同时公开源码去重口径；没有为了挑分页而隐藏排名差异。同源码的不同重答仍保留响应身份，但不据此宣称统计独立或通过率显著。

## 3. 已核对的现有机制

`DefaultSirParser` 的 project、fragment、功能展开入口共用 `ParserDiagnosticListener`；当前 listener 负责中文消息，`DefaultErrorStrategy` 负责 ANTLR 恢复。`DiagnosticCollector` 排序、50 条上限及停止提示独立于消息选择。

当前主要分支为 EOF → 实体成员顺序 → missing → extraneous → 关键字名字 → 通用消息。因此 `load ... as target for update ...` 的第一次错误被 ANTLR 作为 extraneous 交给 listener，先于已有关键字分支返回；后面 `for` 被当名字、`update` 再报错。应改善第一次错误的文字，而非改恢复来挽救 AST。

在已验证原哈希的 **verify #204 原 CLI**（ANTLR runtime 4.13.2）上，只增加观察 listener，不替换原 listener、ErrorStrategy 或输入流。39 份历史源码和 27 项机制对照，共 **66 次公共诊断记录逐项一致**；历史源码全部仍解析失败。设计阶段观察器只读上下文、token 和已形成的树，没有实现以下任何新消息；随后实施结果见第 9 节。

实际观察还确认两项边界：

1. 约束报错时，期待 token 集未必包含逗号，可能已退出 `constraintList` 回到字段规则，不能只匹配 `expected COMMA`。
2. 真正漏分页 `else` 时，当前 `findPageClause.exception` 本身就可能非空。不能因当前报错的 exception 而排除正确提示；应检查此前已经完成的页码、大小子表达式是否有恢复节点或异常。

## 4. 不变量与接入方式

保留现有错误码选择和位置计算；只在本方案明确命中的事件上替换 `message`。可在 listener 内增加少量 private 识别方法；确有长度需要再提取一个包内消息助手，不新建框架或公开 API。

必须保持：

- 词法 token、语法、版本要求、ANTLR 状态机和默认恢复完全不变。
- 每条错误的 code、severity、primarySpan、related、fixes、顺序、数量和截断行为不变；仅获批消息不同。
- `SIR-PARSE-001` 仍为一般解析失败，`003` 仍为多余元素；不为了让消息好看将 `003` 改成 `001`。
- `002` 的零宽插入位置、`004` 的 EOF、`999` 的数量上限，以及既有实体成员顺序提示不变。
- 未命中条件时，连旧 message 也原样保留；合法输入没有新增诊断，也不影响 AST/生成结果。

识别使用 typed parser context、token 类型/期望集合和已完成子节点，不解析英文 expected 字符串，不用源码正则猜结构。不调用 `consume`、`seek`、`recover`、`match`，不补 token、不重新解析、不自动改名或猜错误符号。

## 5. 三类提示及误报边界

### 5.1 关键字被用作名字

**建议消息：**沿用已有文字，例如：

> “target”是 SIR 的关键字，不能用作名字；换一个名字，例如 targetValue

新增覆盖限于 ANTLR 已报错，且 token 确为词法保留字、当前明确需要 IDENT 的名字槽位。首批至少覆盖 `load/create/find/exists/count` 的 `as` 结果及声明名字；具体槽位按对应规则和前置分隔 token 确认，不把“期望集合里含 IDENT”单独当作充分条件。已有 `001` 名字提示不改；重点让同一名字错误的 `003` 也有解释。

**不命中：**普通表达式缺操作数、合法 `order by`、实体成员顺序错误；`for`、`unique`、`default`、`credential` 等上下文词仍是 IDENT，不通过维护第二份关键字表将它们禁掉。功能参数明确允许的 `entity` 不受影响。恢复后第二个 `update` 错误保留旧文字，不误称它又是一个需要改名的变量。

**价值边界：**12 份中只有 6 份首条消息有新增收益；其他 6 份作为旧行为保持用例。不能声称新增检查能阻止模型选择保留字——它一直不合法，本轮只让错误原因更早可见。

### 5.2 约束漏逗号或用逻辑词连接

例：`where min(1) and max(7)`、`where min(1) max(7)`、`where length(1, 100) notBlank`。

**建议消息：**

> 若这里要追加约束，请用逗号分隔，例如 where min(1), max(7)；约束之间不用 and/or，也不能直接相邻。

使用“若要追加”是因为 Parser 不能证明未知的裸名字必为有效约束，不应暗示任何单词都能作为约束。

**必要条件：**错误属于当前字段/视图字段/输入字段/语义类型声明的约束尾部；存在已完整解析、内部没有异常或 ErrorNode 的最后一个 `constraintCall`，它的 stop 正是上一个默认通道 token；当前是直接相邻的 IDENT，或 `and/or` 后跟 IDENT。对应的 WHERE 与该 constraintList 均须来自当前声明，不跨字段、分号或大括号搜索。

**排除：**合法实体修饰词 `unique/secret/default`；即使同样单词在错误的声明种类出现，也不强行提示补逗号。缺字段分号、约束参数逗号/右括号错误、重复 where、枚举中的 and、查询表达式中的 and/or、不完整约束调用均保留原提示。检查的是最后完成的调用，而不是要求正在报错的整个字段/constraintList 无 exception。

entity 字段的 `and` 可能先报 `003`，随后在 `max` 报 `001`；只改善能确定的第一次事件，不删除或合并后面的错误。未声明新约束，不把 and/or 当成合法替代语法。

### 5.3 分页大小之后缺少 `else 错误名`

例：`find ... Page input.page, input.size as rows;`。

**建议消息：**

> 分页子句须写 Page 页码, 大小 else 错误名 as 结果名；不能从大小直接接 as。

**必要条件：**当前报错规则是 `findPageClause`，当前 token 是 AS，确切期待 ELSE；页码和大小都已完整解析，各自内部没有先前恢复节点/异常，大小 stop 与上一个默认通道 token 相同。当前 page 规则因这一次缺 else 而有 exception，不作为排除条件。

共用这一语法规则的分组 sum/avg 可自然获得同一提示，不在 CLI 或题目中重复实现。不会猜应使用哪个错误名，更不自动补 fails；用户补齐后仍由原语义检查验证声明。

**排除：**缺尺寸、缺尺寸分隔逗号、else 后缺错误名、容器 `Page` 缺类型参数、非分页 validate/load 的 else、EOF。尤其 `Page p, else BadPage as rows` 在恢复后也会于 AS 期待 ELSE，但 size 子树已有 ErrorNode，必须保留旧通用错误，不能说它只是漏 else。

`Page 1, 10 BadPage as rows` 仅缺 else 单词时，观察到错误仍发生在大小表达式内部，不能可靠断言 `BadPage` 是错误声明；本轮不猜，保留通用提示。这不影响历史排名第三类的八份响应，它们均直接从大小接 AS。

## 6. 替代方案与不做什么

- **只加长 Prompt：**已有 v8 和真实反馈仍反复失败；编译器上下文更可靠，保留 Prompt 不在此轮修改。
- **把 and 当逗号、自动插 else、改 ErrorStrategy：**会改变接受语言、恢复树、错误数量与工具链行为，超出消息改进范围，拒绝。
- **全部关键字全局重命名：**会破坏上下文词及合法语法，拒绝。
- **把内联 exists 也顺手改善：**按本次主排序为第四，留后续；不能把它替换成 any 当成无条件等价建议，二者语义位置不同。
- 不修改题目、参考、冻结 manifest、历史诊断、学生数据，不采样或训练，不因新消息重新授予旧答案资格。

## 7. 验收矩阵（本机定向完成，实际结果见第 9 节）

1. 新增定向消息测试，先 RED 再改 listener；覆盖三类正例、上述反例及已有专用消息保持。具体测试数量以实际 XML 为准。
2. 一次运行受影响 Parser 测试：新类、KeywordAsNameDiagnosticTest、EntityMemberOrderDiagnosticTest、ParserDiagnosticSpanTest、GrammarBoundaryTest、LexicalDiagnosticTest、ProjectSourceGrammarTest、FeatureGrammarTest、QueryGrammarTest、AstDeterminismTest、SirParserTest；单模块定向，非全量业务 IT。
3. project/fragment/功能展开共用路径都核对；Unicode 前缀和 CRLF 保持 code-point SourceSpan，missing 分支仍是原零宽插入点，EOF/50 条上限不变。
4. 固定这 39 份历史源码及设计对照输入，新旧诊断逐条比较：仅明确命中的 message 可变，其余字段和未命中 message 都相同；不是只检查“结果仍失败”。
5. 对当前 49 份合格源码及合法参考核对原 AST/完整生成摘要不变。这只是兼容检查，不修改历史四门资格。
6. 本机只跑必要定向、串行、限制 JVM；重活和真实业务由授权提交后的同 SHA CI 负责。若变更语言参考，按项目规范另跑 LanguageReferenceExamplesTest。

**设计阶段的 66 项只读观察不是新行为 GREEN。** 负责人确认后才实施产品代码及第 9 节的 TDD/兼容核对；新 SHA CI 仍为 NOT_RUN。设计观察辅助程序首次因误用不存在的 Jackson 依赖而未编译，未进入 Parser；随后改用 CI CLI 自带 StrictJson/JsonStringEncoder，无新依赖。首次失败 stderr 留在 target，未计入 66 项核对。

## 8. 改动范围及下一步

实际生产改动为 `sir-parser/.../ParserDiagnosticListener.java` 增加一次消息细化调用，以及包内 `ParserDiagnosticHints.java`；新增聚焦的 `TargetedSyntaxDiagnosticTest`，同步本设计/实施记录。不改 `Sir.g4`、DefaultErrorStrategy、AST builder、语义校验和生成器。

负责人已确认三类范围、响应身份排序及保留恢复行为；本机实施完成，提交/推送须另行确认。实现会改变工具链摘要，后续未判卷候选仍须按既有 import/check 流程重新绑定；本阶段没有等待中的候选业务门，不新增付费调用。交接 4.4 的 archive-gate 整理和 docs/tmp 清理均尚未开始。


<a id="local-verification"></a>

## 9. 本机实施与验证记录

### 9.1 实际实现

listener 原有的错误码分支、missingToken 判定与 SourceSpan 计算一字不改，只在计算好原文案之后调用包内 `ParserDiagnosticHints.refine`。助手只识别 `SIR-PARSE-001/003`，先验证当前 parser/context/token；不命中即返回原文案。没有可变状态字段或输入/树写入。

- 名字提示要求词法保留字、唯一期待 IDENT，以及受支持声明规则的引导词或结果规则的 AS；上下文词仍是普通 IDENT。原 `001` 关键字消息原样保留，新增收益主要是 `003` 的早期名字提示。
- 约束提示只取当前声明或其直接 constraintList，验证最后完整约束紧邻当前 token，且该调用没有 ErrorNode/异常；三个实体修饰词明确排除。
- 分页提示限定当前 findPageClause 的 AS/ELSE 边界，检查两个完成的表达式而不是当前 page 的 exception。共享的 clean 检查用本地显式栈只读遍历，避免新增递归；不会消费 token 或影响恢复。

助手 120 行左右、所有生产方法均小于 50 行。没有新公开 API、依赖或诊断码。`TargetedSyntaxDiagnosticTest` 的合法输入只断言 Parser 接受，不冒称它们均已通过语义/生成；完整生成兼容另行针对 49 条合格答案及五份参考核对。

### 9.2 TDD 证据

| 阶段 | 实际结果 | 含义 |
| --- | --- | --- |
| RED | **67 项、37 失败、0 错误、0 跳过** | 37 项全部只因旧通用消息不同；其余 30 项边界/原行为通过，无测试构造或编译失败 |
| GREEN | **11 类、135 项、0 失败/错误/跳过** | 原 67 项测试字节未变；新增消息与受影响旧 Parser 测试全部通过 |

新增 67 项覆盖五类结果名字、五类声明名字、四种声明容器中的四类约束错误、合法修饰词/上下文词、分页 find/sum/avg、缺尺寸/逗号/错误名、Unicode 与 LF/CRLF 位置、project/fragment/expansion、原 EOF 和 50 条诊断上限。持锁 load 的第二个恢复错误仍保留原消息/位置，不能把整个错误链都改成名字提示。

GREEN 的 135 项包含新增 67 项，不相加为 202。实际所选测试类：

```text
TargetedSyntaxDiagnosticTest,KeywordAsNameDiagnosticTest,EntityMemberOrderDiagnosticTest,ParserDiagnosticSpanTest,GrammarBoundaryTest,LexicalDiagnosticTest,ProjectSourceGrammarTest,FeatureGrammarTest,QueryGrammarTest,AstDeterminismTest,SirParserTest
```

未加 JaCoCo，不声称已测得覆盖率 80%；未运行未列出的 Parser 测试、全量门或业务 IT。两个 AFT 检查都超时，没有 fresh 静态诊断报告，不能算作通过；编译与测试以 Maven 日志及新鲜 Surefire XML 为准。

### 9.3 LF 与资源

实际宿主 Windows 11，Java 21.0.11、Maven 3.9.14，18 个逻辑 CPU、总内存 24093 MiB，开跑前空闲 3860 MiB。从 `33c2f4bc` 命令级禁用 autocrlf/eol 转换导出隔离快照，排除 datasets，**1444 个基线文件逐字节匹配 Git Blob**，先只覆盖新测试 RED，再覆盖两个生产文件 GREEN。没有批量转换工作区换行、修改 Git 配置或公共测试夹具。

Maven 和唯一测试 fork 串行运行，不加 `-T`，去除 JAVA_TOOL_OPTIONS/_JAVA_OPTIONS/JDK_JAVA_OPTIONS，分别限制：

```text
MAVEN_OPTS=-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2
mvn -B -o -Dmaven.repo.local=D:/maven-repo -pl sir-parser -am test -Dtest=<上述测试类> -Dsurefire.failIfNoSpecifiedTests=false -DforkCount=1 -DreuseForks=true "-DargLine=-Xmx256m -XX:MaxMetaspaceSize=128m -XX:ActiveProcessorCount=2"
```

RED 同命令只选新类。原始命令/日志/XML/退出码与摘要在 `target/parser-diagnostics-implementation/{red,green}/`，快照证明为 `snapshot-verification.json`，两个实现的受测输入为 `green-inputs.json`。这些是本机可复核记录，不是已提交 CI 证据。

### 9.4 精确兼容对照

基线为已校验原哈希的 CI #204 CLI。新侧**只覆盖本次 GREEN 直接产出的两个 class 文件**，不是本机旧增量 class：Listener 与 Hints 的源码字节匹配当前工作区。其余编译器仍为同一原 CI 产物。

Parser 对照使用两个隔离的 internal 类加载空间，同时共享公共 API/AST 类型，在同一 JVM 直接对 record 调用 equals；不是把 AST 转成字符串或猜一个摘要。输入共 **120 项**：39 份历史失败源码、27 项机制对照、49 份合格教师源码、五份合法参考。49 份源码全部从已提交 Git Blob 读取、匹配学生 v4 索引的 source_sha256。

结果：

- **120/120 接受结果、AST Optional 值、诊断数量/顺序一致**；有效 AST 含全部 54 份合法输入。每条 Diagnostic 只将新消息换回旧消息后直接 equals，确认 code/severity/primarySpan/related/fixes 完全一致。
- **24 项输入共 28 条消息变化**，变化输入名单与事先由旧观察/人工分类确定的许可名单精确相等；未许可变化为 0。其余消息也原样相同。
- 使用生产 CLI 的 check/GENERATION 再对 54 份合法输入比较完整 JSON 结果：**54/54 均完整生成且完整结果相等，累计 1633 个文件的数量与生产版本化摘要一致**，保留原告警，不误拒合法 WARNING。

原始 API 对照、输入来源、两侧 stdout/stderr、精确覆盖 class 哈希、实际命令和结果在 `target/parser-diagnostics-implementation/compatibility/`，索引为 summary.json；辅助主程序 `ParserCompatibility.java` 和 compare.py 位于本机验证目录。Java 辅助/对照/CLI 进程均 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2，串行且已结束。

这不是重新验收旧答案；历史的资格、原消息和冻结输入全部保持原样。仍为 **49 条合格、学生 v4、未训练、模型调用 0 次**。未修改语言参考，故不额外运行 LanguageReferenceExamplesTest；新 SHA CI/真实业务为 **NOT_RUN**。尚未暂存、提交或推送。
