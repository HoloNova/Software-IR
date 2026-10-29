# 通用规则手册 v8：缺口与作用域边界修订

## 1. 状态与边界

基线 `8bc534cc230dac7fd6229687b995211d674de877`。负责人确认先补通用 Prompt、审阅诊断方案，再决定下一轮采样。本轮新建 [v8](../../prompts/sir-0.3-general-v8.md)，不修改已经使用过的 v7、反馈前缀、冻结题目、g7 批次、教师答案或工具链。**模型 API 调用 0 次，未开始学生训练，未提交或推送。**

历史 Prompt 的工作区与 Git blob 对照时，确认 v7/反馈前缀仅有本机既有 CRLF/LF 差异；分别记录原始哈希、核对规范化内容，不转换或写回旧文件。这个文档工作区检查不用于放宽冻结输入、候选源码或 CI 附件的精确原始哈希要求。

本文件 §1–3 记录 v8 的纯文档修订轮；其中“不改工具链”描述当时范围。随后负责人已确认并实施编译器四处文案，本机 125 项与原 CLI/逐文件对照见[诊断实施记录](../design/query-diagnostics-design.md#local-verification)，不能据本文的历史边界误判当前源码仍未变化。

v8 仍是完整的七节通用手册，不附本题参考答案、种子、判卷清单或并发阈值。不因博客结果给语言添加特殊例外；例子继续使用 v7 已有的 Course/Enrollment/User 名称与通用字段。

按统一 LF 解码后的 Unicode 字符计数（含英文、空白和换行），v7 为 **9,104**，v8 为 **10,182**，增加 **1,078 / 11.84%**。字符数不是 token 数，也不能据此承诺 API 成本或通过率。没有扩写成完整语言参考。

## 2. 逐项差异与依据

下表位置指 v7 的原行号；生产路径缩写：Semantic 为 `sir-semantic/src/main/java/io/kcg/sir/semantic/internal/`，Lowering 为 `sir-lowering-spring-boot/src/main/java/io/kcg/sir/lowering/springboot/internal/`，Parser grammar 为 `sir-parser/src/main/antlr4/io/kcg/sir/internal/Sir.g4`。

| v7 位置 | v8 改什么 | 为什么存在 | 现有实现依据 |
| --- | --- | --- | --- |
| 20、87：声明闭合与自查 | 明确路径 `{id}` 不会自动生成 input；逐能力核对 input 声明、路径字段及视图/错误引用；自查加入 Ref 与两种谓词作用域 | 原手册已有声明闭合要求，教师仍遗漏三个 input；这里只给可执行的短自查，不把规则未遵守误报为新增语义 | Resolve 的类型引用绑定与 `SIR-SYMBOL-002`；Validate 的路径字段规则 `SIR-VALID-013` |
| 32：多约束 | 明说同一 where 后用英文逗号分隔，并给 `Text where notBlank, length(1, 64)` | 原文只能从零散例子推断分隔规则，教师把两个约束直接相邻 | Grammar 的 `constraintList`；语言参考 §3.2 的约束说明 |
| 37 后：Ref 与 HTTP 输入 | `field owner: Ref<User>` 对应 HTTP 的 `ownerId`，SIR 仍读 `input.owner`；显式 Id 输入需先取得对应实体引用再用于 Ref 绑定；主键传输类型不固定是数字 | HTTP 主键表示不等于 SIR 引用类型，不能把请求字段名直接当成 SIR 成员或用 Id 直接赋给 Ref | `SpringBootModelLowerer.targetPropertyName`；Semantic 的绑定类型检查；已有博客参考的 Ref 输入与真库验收 |
| 41：普通投影 | 保留来源的 SIR 字段类型；只返回引用主键时仍写 Ref，响应保持源字段名；展开对象才选关联视图 | 接口要求“数值主键”不意味着视图应把 Ref 改为 Id，原文没有明确这层映射 | `TypePass.typeViewFields` 的类型相等与关联视图分支；已验收博客参考的 PostView/PublicPost 区别 |
| 69、71：查询作用域 | 普通查询规则显式排除 any 内部；并排解释普通 exists 的 `item.course == selectedCourse` 与以 Course 为根的 any 的 `course == item`；禁止把两种写法互套 | 教师修对 exists 后又把原本正确的 any 改错；不是同一语法存在两个合法解释，而是上下文切换没有被正确执行 | `ValidatePass.validateStringMatchShape` 不递归进入 any；`TypePass.typeAnyExpression` 与正式 binding；保留反向身份关联 |
| 94：Optional 与 containsLiteral | 明确右侧必须是非 Optional 文本，判空不缩窄；不得为了编译通过擅改合同的可空/缺省要求 | 原文说明不缩窄，但没有明确该字符串操作；模型写了 Optional keyword 并在判空分支中继续传给它 | `TypePass.typeBinaryExpression` 的 `CONTAINS_LITERAL` 分支；语言参考 §6 的可选值说明 |

### 没有顺手改变的规则

- 正向 Ref==item 与反向身份==item.ref 两种 any 关联都保留；不增加任意 JOIN、嵌套 any 或无关联查询。
- Ref 的输入名、视图输出名与 SIR 成员名分别说明，不把 HTTP 命名移进 Semantic。
- Optional 的普通比较仍允许；没有笼统禁止 Optional 筛选。containsLiteral 的限制只强调右侧，不把当前允许的可选文本列也禁掉。
- 不修改普通输入必填、分页例外、patch 三态、错误集合、状态码、事务或并发规则。
- 不修改 `repair-general-v1.md` 的最小改动策略，不增加修复轮数。解析失败后尚未执行的语义检查仍可能在下一阶段暴露，不能承诺一轮通过。

## 3. 本机文档例证核对

不是新增教师样本，不写入数据批次，不改变任何候选资格。使用 verify #200（run `40839225636`）的原 standalone CLI，先逐字节比对已归档 ZIP 中的 `kcg.jar`，其 SHA256 为：

`d5bd0143d342d5079ac35cde4760488c085ef69d2e46961da1d3798264a07e6e`

一次 JVM 串行处理 11 份完整、通用的 SIR 例子；每行核对 id、源 SHA256、ok、真实 stage、错误码及成功时的生成文件数/摘要，不拿进程退出码当样本通过。JVM 限制 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2；没有 Maven、Java 工程构建、应用、数据库、网络采样或模型训练。

| 例证 | 实际静态结果 |
| --- | --- |
| 多约束、Ref 输入/投影、普通 exists 与正向 any 组合 | GENERATION PASSED，无诊断，29 文件 |
| 反向 any 身份关联 | GENERATION PASSED，无诊断，29 文件 |
| 判空加 Optional 普通比较 | GENERATION PASSED，无诊断，29 文件 |
| 两约束缺逗号 | PARSE FAILED，2 条 SIR-PARSE-001；保留解析恢复产生的全部诊断 |
| 把普通视图 Ref 字段写成 Id | SEMANTIC FAILED，SIR-TYPE-001 |
| 把 Id 输入直接赋给 Ref 字段 | SEMANTIC FAILED，SIR-TYPE-001 |
| 普通 exists 条件使用裸字段 | SEMANTIC FAILED，SIR-FLOW-001 / SIR-VALID-001 |
| 给 any 关联字段误加 item. | SEMANTIC FAILED，SIR-TYPE-004 / SIR-SYMBOL-002 |
| Optional 文本右值，即使有判空仍用于 containsLiteral | SEMANTIC FAILED，SIR-TYPE-001 |
| 省略 input 声明，期待路径自动声明 | SEMANTIC FAILED，SIR-SYMBOL-002 / SIR-VALID-013，含连带诊断 |
| 将 HTTP 的 ownerId 当成未声明的 SIR input.ownerId | SEMANTIC FAILED，SIR-SYMBOL-002 |

结论为 **3 个正例完整静态生成，8 个反例按预期拒绝**，不是“11 个软件四门通过”。BUILD/START/BUSINESS 均未运行。检查脚本、完整输入/输出和汇总暂存于本机 `target/prompt-v8/`，用于本次文档自查，不当作长期教师资格档案；该目录可清理。

输入 JSONL SHA256：`7bc62ba3ec2c754e6b802960c2a4eec100ee95d08fcbd88533523189cfb39e00`。

原 CLI 输出 SHA256：`ebd15ec40848eb317d1a82ac7dc5b7663a2917977cd585df4164c02c20437f75`。

静态核对说明新文字所描述的正反边界符合现有编译器，**没有证明模型能正确遵守 v8，也没有证明通过率或成本改善**。收尾的一次定向 AFT 请求因 bridge busy/under load 在 150 秒后超时，未记为诊断通过；不重复运行已经完成的原 CLI 例证检查来替代它。

## 4. 诊断设计与后续使用

诊断文本的改进单独见 [最小诊断设计与实施记录](../design/query-diagnostics-design.md)。该方案随后已获确认并本机实施：保留现有语义、阶段、code、span、数量和顺序，只澄清四处消息。实际覆盖见其 §7；同 SHA CI 仍 NOT_RUN，不能据本机记录宣称全量或业务验收通过。

若选用 v8，须用原 `prepare --system-prompt experiments/slm/prompts/sir-0.3-general-v8.md` 创建新批次。不能改旧批次 system/manifest/messages；`import` 保留原 system，也不能拿它替换 Prompt。旧失败候选的一轮预算已经用完，不追加追修来冒充新 Prompt 的结果。

本轮不创建新批次或采样计划，不请求 API、不导出答案、不合成学生 v4。后续模型验证另报 token 预算；若想研究效果归因，应区分 system Prompt 与编译器诊断的变化，不能把新旧工具链和不同提示的少量结果直接混为单一因素的提升。
