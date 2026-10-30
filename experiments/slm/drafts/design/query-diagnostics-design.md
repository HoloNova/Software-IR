# 查询作用域与类型诊断：最小文本改进设计

基线：`8bc534cc230dac7fd6229687b995211d674de877`。状态：**负责人已确认，§3 的四处文案已实施，本机定向及 `2e4fa74b` 的 verify #202 均已通过**；CI 证据见 §8。本文件保留已确认的设计，实际证据见 §7。不改语言、参考答案、冻结题目、候选或判卷规则，不调用模型。

## 1. 为什么需要改

博客改写候选的初始错误在两个 `exists` 条件中；反馈后模型正确给它们补上了 `item.`，却又把原本合法的 `any(..., post == item ...)` 改成 `any(..., item.post == item ...)`。源码差异见 [真实教师差异](../../../../datasets/sir/research/2026-10-29/blog-v7-repair/change-teacher-changes.patch)。这不是编译器允许两种解释：普通查询的 `item` 是当前查询行，`any` 中的 `item` 仍是外层根行，裸字段才属于关联实体。

现有 `ValidatePass.COMPARISON_SHAPE` 被 find、exists/count、sum/avg、分组聚合、each 共用，消息却只称为“find 谓词”。`TypePass.typeAnyExpression` 的消息只给了正向关联，遗漏实现已经接受的反向身份关联。`containsLiteral` 的类型消息没有明确 Optional 右值为何不合格；普通投影的消息虽包含实际类型，却没有区分引用与主键编号的简短解释。这些都可以先改文案，不必增加语法或自动修复。

从零候选先因缺约束逗号而停在解析阶段，删除相邻的 `notBlank` 后才暴露类型、声明和作用域错误。18 条诊断包含连带错误；不能把数量直接当作独立根因，也不能用“有一次反馈”推导完整软件应已通过所有阶段。

## 2. 已核对的实现边界

| 事实 | 生产实现 |
| --- | --- |
| 多约束以逗号连接 | `sir-parser/.../Sir.g4` 的 `constraintList : WHERE constraintCall (COMMA constraintCall)*` |
| 普通查询共用形状检查；不会把该检查递归应用到 any 的关联条件 | `ValidatePass.validateStringMatchShape`、`validateComparisonShape`；各查询步骤的调用点 |
| any 正向关联和反向身份关联都合法 | `TypePass.typeAnyExpression`、`referencesItemEntity`、`reverseConnection`；消费既有正式 binding |
| containsLiteral 允许文本列，右侧必须是非 Optional 文本 | `TypePass.typeBinaryExpression` 的 `CONTAINS_LITERAL` 分支；比较运算的 Optional 解包不适用于它 |
| 普通投影保留字段类型，关联视图另有分支 | `TypePass` 的视图字段检查：`nestedViewType` 与 `declaredType.equals(fieldSymbol.type())` |
| Parser 已有关键字名字、实体成员顺序等特定提示，但一般语法错误仍可能只有 token | `ParserDiagnosticListener.syntaxError`、`isKeywordWhereNameExpected`、`misplacedEntityMember` |
| CLI 原样传递阶段诊断 | `docs/SIR_BATCH_CHECK.md` 的输出协议；不在 CLI/Python 中重写原始诊断 |

以上缩写目录为 `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/`、`sir-parser/src/main/antlr4/io/kcg/sir/internal/` 和 `sir-parser/src/main/java/io/kcg/sir/internal/`。不按 SIR 文本重新猜实体/字段，不复制名称解析或类型推导。

## 3. 已确认方案：只澄清现有消息

保留现有触发条件、错误码、severity、所属阶段、主 SourceSpan、related/fixes、数量和顺序。只改变以下消息文本；不承诺错误软件因此变合法。

### 3.1 普通查询比较

修改 `ValidatePass.COMPARISON_SHAPE`，仍由原有所有调用点共用，建议文案：

> 普通查询条件（不含 any 内部）的比较，左侧须为 item.<直接字段>，右侧不能读取 item；例如把 0 < item.qty 写成 item.qty > 0。any 内裸字段属于关联实体，item 仍是外层根行，不要按此规则给关联字段统一添加 item.。

为什么不先增加“查询种类上下文”对象：这次问题首先是共享消息误称 find、没有声明 any 边界。中性文案加原 SourceSpan 已能纠正这两点，无需为一条文案穿透修改多层私有函数签名。若后续确有数据证明仍需逐步骤或逐实体名称，再单独评估；本方案不假装已经具备这些额外信息。

### 3.2 any 关联

修改 `TypePass.typeAnyExpression` 的 `SIR-TYPE-004` 消息，建议文案：

> any 的最外层 and 中须有有效关联：关联实体的 Ref 字段 == item，或关联实体的主键 == item.引用字段，等号可交换。裸字段属于关联实体，item 始终是外层根行；不要把关联实体字段改写成 item.<字段>。

必须保留反向身份关联，不能只写正向 Ref==item；也不能为减少报错而接受没有关联、嵌套 any 或任意 JOIN。保留现有字段不存在等独立诊断，不在本次抑制或重排连带错误。

### 3.3 containsLiteral 的 Optional 右值

修改 `TypePass` 中现有 `CONTAINS_LITERAL` 类型错误文案，建议：

> containsLiteral 的左侧必须是文本字段，右侧必须是非 Optional 文本值；is none/is present 不会把 Optional 文本转换成非 Optional 文本。

不能顺手把左侧也限制成非 Optional：当前实现允许可选文本列。不能自动解包、提供空串默认值或因为前面有判空就放宽类型规则；这些会改变语言和缺值语义，不是诊断修复。

### 3.4 引用投影与主键编号

普通投影类型不一致时保留原消息；仅当来源字段为直接 `RefType` 时，追加：

> 普通引用投影须保留来源的 Ref<实体> 类型；主键编号与实体引用不是同一种 SIR 类型。

复用当前已经取得的 `fieldSymbol.type()`，不创建第二套类型格式化或名称查找。嵌套视图仍走原有合法分支。**Semantic 不解释 HTTP/JSON**；传输映射属于 Spring Boot Target 的事实，只放在通用手册的目标说明中，不能把 Target 依赖带进 TypePass。

## 4. 不采取的替代方案

- **统一重写 any 的语法/作用域**：会牵动 Parser、Resolve binding/角色、类型检查、Normalize、Lowering、生成和历史兼容。当前证据只支持先澄清既有规则，不足以授权语言重设计。
- **对所有裸字段自动补 item.**：恰好会制造本轮 any 错误；不能从单条诊断推导全局替换。
- **把 Ref 与 Id 自动互转、把 Optional 自动变成空串**：这是行为改变，会影响身份、缺值和合同，不是文案改进。
- **在 Python 或 CLI 中把诊断改成更友好的版本**：会使同一核心错误出现两套权威文本，并破坏原始反馈与证据追溯。
- **顺手改 Parser 的缺逗号/缺 else 提示**：本方案不含 Parser 实现。现有 listener 的 token、parser context 与 expected tokens 可以作为后续定向设计的输入，但不能仅凭遇到 `notBlank` 或 `as` 就断言缺逗号/else。需另行取得实际上下文和负例证据，避免误提示或改变恢复过程。
- **同时改反馈前缀或增加修复轮数**：本轮不改 `repair-general-v1.md`。先隔离 system Prompt 的变化；阶段早停和一轮预算仍保留，不承诺一轮完成所有修复。

## 5. 验收约束

下面列出方案的验收边界；本机实际执行范围与结果见 §7，未运行的同 SHA CI、全量和业务门不得据此记为通过。

1. 先增加最小定向回归，再改四处文案。可复用 `QuerySliceSemanticsTest`、`IntegerWidthSemanticsTest`、`RelationSliceSemanticsTest` 的夹具，以及本轮原 CLI 正反例。
2. 对同一个非法源码，在受测基线与新实现间比较诊断数量/顺序/code/severity/SourceSpan/related/fixes，除已批准 message 外全部保持；三种阶段的原失败仍必须失败。
3. 普通 find/exists/count/sum/avg/分组/each 的适用范围均需覆盖，不能通过跳过某种步骤来满足新文案测试。
4. any 的两种关联方向保持成功；无关联、错误字段、错误实体及现有禁用组合保持拒绝。普通查询规则不能深入 any 并把合法关联拒绝。
5. containsLiteral 的非 Optional 文本右值仍合法；Optional 右值即使判空仍拒绝；可选文本列配非 Optional 文本右值仍合法。
6. Ref 普通投影和合法嵌套视图保持通过；Ref→Id 错误继续失败；非 Ref 的类型错误不得追加误导性的引用提示。
7. 合法输入的内存生成文件逐文件保持原字节和版本化摘要，不以只比成功码代替。`kcg check` 保留原协议及 Locale 确定性，不在消费端加工消息。
8. 本机只做受影响定向检查，全量/业务门仍交 CI；不为了文案测试运行真库或应用。

即使只改 message，Java 核心源码仍会改变 `toolchain_core_sha256`。若后续实施，旧冻结批次不改 manifest、不直接继承检查结果；待验候选需原 import 在新工具链下建立新批次并重判，历史已封存资格保持原状。各冻结 task 的题面/合同/reference_evidence 也不能为了对齐新提交而追改。

## 6. 交付与边界

- 新建 `prompts/sir-0.3-general-v8.md`，逐项依据和静态例证见 [v8 改动说明](../prompt-v8/changes.md)；v7 与所有既有批次原样保留。
- v8 仅澄清现有规则，不包含本题参考实现、种子、判卷探针或并发阈值；不证明教师通过率已经改善。
- 编译器仅修改 `TypePass.java`、`ValidatePass.java`，新增一份定向回归。Parser、目标语义与协议不改；本机不启动应用/数据库，不调用模型，不做提交或推送。
- 下一步是取得本批改动的明确提交/推送授权，再由对应 SHA 的 CI 验收；新 Prompt 的实际采样仍须新批次和单独 token 预算，不用旧候选再追修来冒充新版本效果。

<a id="local-verification"></a>

## 7. 实施与本机证据（2026-10-29）

### 7.1 实际改动

生产代码只有四处消息：Validate 的共享比较消息；Type 的 any 关联、containsLiteral 类型消息；普通投影的原消息仅在来源为直接 RefType 时附加解释。原判定表达式、诊断构造位置、错误码、阶段和 SourceSpan 不改。Semantic 中没有 HTTP/JSON 解释、目标依赖或按名字恢复 binding。

新增 `sir-semantic/src/test/java/io/kcg/sir/semantic/QueryDiagnosticMessageTest.java`，22 项实例覆盖八种普通查询（find/exists/count/sum/avg/分组 sum/分组 avg/each）、关联方向与作用域、Optional 右值、可选文本列、引用/标量投影和嵌套视图。既有同类回归一并定向运行。

### 7.2 RED → GREEN

- 首次 22 项执行中有 14 项失败：13 项为预期的旧文案，1 项是新夹具错误地定位到了视图 id，而不是 owner 的声明类型。**这次不作为完整有效 RED**；原 XML 保留在 `target/query-diagnostics/red/`，只修正测试的定位起点，生产源码未动。
- 有效 RED：仍是同一批 22 项，**14 项全部因旧消息缺少已批准说明而失败，8 项边界通过，0 error/skip**。XML、测试与生产源码哈希在 `red-valid/`。
- 最小文案改动后，新增测试源码保持与有效 RED 相同。GREEN 一次运行七类，**125 项，0 failure/error/skip**：

| 类 | 当次项数 |
| --- | ---: |
| QueryDiagnosticMessageTest | 22 |
| QuerySliceSemanticsTest | 28 |
| RelationSliceSemanticsTest | 17 |
| IntegerWidthSemanticsTest | 6 |
| GroupedSumSemanticsTest | 19 |
| OptionalValueSemanticsTest | 16 |
| ComputedViewSemanticsTest | 17 |

原 XML 和计数汇总在 `target/query-diagnostics/green/`。未引入覆盖率插件，**行覆盖率未测量**，不能将这些项数冒称 80% 或全量通过；新增分支的直接 Ref/非 Ref 两侧及四类消息均有实际断言。

### 7.3 原 CLI 对照与内存产物

以 verify #200（run `40839225636`）的原 standalone JAR 为旧基线，先与已归档 ZIP 成员逐字节匹配。新侧仅将刚由本次 Maven 编译的 `sir-semantic/target/classes` 放在该 JAR 之前；其他未改模块复用同一不可变 CI 二进制，**不是全仓重编的新 CLI 包**。对照实际确认四种新消息均被加载，没有拿旧增量 class 冒称新实现。

- 13 条通用输入：顶层结果及每条诊断除四处获批 message 外全部相等，覆盖 code/severity/stage/span/related/fixes、符号/节点/路径字段、顺序/数量、源摘要、成功阶段、文件数及生成摘要。普通标量投影、Optional<Ref> 来源的投影、create 的 Ref/Id 绑定错误保留原文，不误加普通 Ref 投影说明。
- 另用既有 `RelationSliceLoweringTest.aClockExpressionInsideRelatedSqlRemainsRejectedBeforeLowering` 的同形关联时钟反例，补齐 LOWERING 失败边界：仍为 `SIR-LOWER-FEATURE-001`，旧新结果全字节一致。与前述输入合计 **14 条**，包含 PARSE、SEMANTIC、LOWERING 的真实拒绝，没有放宽规则。
- 新侧的 en_US 与 tr_TR 输出逐字节相等；Lowering 反例在旧侧/新侧/另一 Locale 三者也完全一致。
- 调用既有 `ProjectCompilation` + `SpringBootGenerator`，以相同 SourceSnapshot 在内存生成，逐个比较相对路径和完整 UTF-8 内容字节：通用正例三组各 29 文件、博客参考 55、base 47，合计 **5 组 / 189 文件完全一致**；版本化 `ValidationHashes.generated` 也分别一致，不只比较文件数或旧 combinedSha256。

辅助 Java 只把内存结果编码到 stdout，Python 比较原字节；没有另写语义、写出生成工程、构建 Java 应用或启动服务。完整脚本、请求/输出、逐文件 SHA256 与汇总在 `target/query-diagnostics/comparison/`；该目录是本机临时验证材料，不是候选的四门资格证据。

### 7.4 环境与命令

实际宿主 Windows，Java 21.0.11、Maven 3.9.14，18 逻辑处理器，总内存 24093 MiB、开始前可用 2834 MiB。单 Maven JVM 和单测试 fork 分别限制 heap 384 MiB、metaspace 192 MiB、ActiveProcessorCount=2，不使用 `-T`。静态对照 JVM/辅助 javac 限制 heap 256 MiB、metaspace 128 MiB、处理器 2，按序运行；没有并行重构建或常驻应用。

实际 GREEN 命令（PowerShell，仓库根）：

```powershell
Remove-Item Env:JAVA_TOOL_OPTIONS,Env:_JAVA_OPTIONS,Env:JDK_JAVA_OPTIONS -ErrorAction SilentlyContinue
$env:MAVEN_OPTS='-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2'
mvn -B -o '-Dmaven.repo.local=D:/maven-repo' -pl sir-semantic -am test `
  '-Dtest=QueryDiagnosticMessageTest,QuerySliceSemanticsTest,RelationSliceSemanticsTest,IntegerWidthSemanticsTest,GroupedSumSemanticsTest,OptionalValueSemanticsTest,ComputedViewSemanticsTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2' `
  '-DforkCount=1' '-DreuseForks=true'
```

RED 使用同一资源和 reactor 参数，只将 `-Dtest` 设为 `QueryDiagnosticMessageTest`。范围只到 Parser/Semantic 的依赖 reactor；不是全量构建或业务 IT。一次定向 AFT 请求仍因 bridge busy/under load 在 150 秒后超时，未计为诊断通过；实际编译与测试以当次 Maven/JUnit 及原 CLI 对照为据。

### 7.5 本机实施结束时尚未执行的验收

本机实施结束时，同 SHA Linux CI、全量门、生成工程构建/启动/真库业务均 **NOT_RUN**；后续 CI 验收见 §8。现有 verify 的冻结门运行 `clean verify`，新 `*Test` 会进入其范围，无需另改工作流或手工派发；本次没有为触发 CI 而自动提交/推送。

工具链 Java 源已变，旧 g7 的工具链冻结不再代表新实现。所有旧 task/manifest/system/候选/运行证据与学生 v3 均保持，未 import、采样、重判资格或训练。已有 48 条历史合格答案不重算，新候选也不能继承本次静态对照或旧参考四门。后续采样/导入仍按 §5 的工具链与字节规则执行。


## 8. 同 SHA CI 验收（2026-10-29）

负责人通知完成后一次性核对 [verify #202](https://github.com/HoloNova/Software-IR/actions/runs/40877385610)，head_sha 为 `2e4fa74be63e694b580d991c6a54f60d7d2ce2eb`，两个 job 均 success。三个原始 ZIP 与 GitHub artifact digest 匹配；上传 Surefire 为 **293 XML／2031 项、0 failure/error/skip**，其中 `QueryDiagnosticMessageTest` **22 项全通过**。博客参考报告正式 verdict=PASSED，生成工程构建成功、真实路由 readiness=200、业务 **425/0**；四轮各六连接／事务竞争及清理证据完整。不是只凭 workflow 绿色推导业务通过。

原始 ZIP、元数据、博客报告／日志和相关完整 XML 归档于 `datasets/sir/research/2026-10-29/blog-v8-ci202/`，不清洗原日志字节。该验收属于编译器与既有参考，不赋予教师候选资格；后续 v8 新候选初采与静态结果另见[数据报告第二十二阶段](../../../../docs/qualification/SLM_DATA_PILOT_REPORT.md#slm-blog-v8-initial)。
