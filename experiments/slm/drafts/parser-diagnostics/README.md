# Parser 诊断设计的统计与只读观察

本目录是设计阶段的统计和旧 Parser 观察材料，不是产品实现或模型训练数据。负责人随后确认的产品实现与本机 TDD/兼容结果见 [parser-diagnostics-design.md §9](../design/parser-diagnostics-design.md#local-verification)；原统计与旧观察保持原值，不将它们改成新行为通过证据。

## 统计口径

固定数据提交 `33c2f4bc09e88b91631066ae14f8edd70bb19fae`，全部输入从 Git Blob 读取，不受工作区 CRLF 影响。

- 163 个 checked JSONL、394 行：参考 152，教师 242。
- 95 行教师检查含 SIR-PARSE-001，原始诊断 214 条；按候选响应 ID 去重为 48，按源码字节去重为 39，候选/core 组合为 53。
- 主排序采用不同响应身份：关键字名字 12、约束分隔 11、分页缺 else 8；内联查询 5。源码排序的前三则为 12、6、5（分页 4），两种口径同时记录。
- 相同源码的新响应不是新源码，但保留其响应身份；跨工具链重查和跨批次导入不增加响应数。不以级联错误条数排序，也不把响应数当成计费调用总数。
- 研究摘要中的 56 条和原 stdout 的 214 条是真实记录的副本，不重复加到 checked 的 214 条。完整载体清单保留在 census.json。

`classifications.json` 是对 39 种失败源码逐一人工核对后的标签和原因，不是靠正则重建的语法检查器。`census.json` 保存每份响应、原检查路径/行号/工具链、完整诊断、源码片段和全部 checked 输入 SHA256，不复制或覆盖历史源码文件。

重算（输出到新位置）：

```text
python experiments/slm/drafts/parser-diagnostics/census.py --revision 33c2f4bc --output target/parser-diagnostics-design/reproduced-census.json
```

重新生成后应与本目录 census.json 逐字节一致；分类或数据提交变化会使此比较失败，不能偷偷兼容旧报告。

## 原 Parser 的只读机制核对

使用 verify #204 的原 standalone-cli：受测 `47bf67c8f3d95fbb81d50b1b927c73bba1d0db21`，run `41019419975`，artifact `12960753406`。CLI ZIP SHA256 为 `6b1dbcfd5ba33160cd75f2f1b7b62caa5367824047f3fcfc023fe5c17f351074`，其中 JAR SHA256 为 `d23339bf47b6f4ff574e1dcd82bc33f7c7127fae5882b0a1daa89b5fea648a9a`。数据基线与该 CI 的 sir-parser 子树完全相同，原 ANTLR runtime 为 4.13.2。

`observe.py` 只编译本目录两个辅助 Java 类：

- `ParserContextProbe.java` 先调用原 DefaultSirParser，再调用同一 lexer/parser/原 listener，并额外附加一个只读观察 listener。每次都断言两边完整 Diagnostic 记录 equals。没有覆盖任何产品类、ErrorStrategy 或恢复方法。
- `ProbeJson.java` 通过同包桥接复用原 CLI 的 StrictJson 和 JsonStringEncoder，只有观察结果的 Map/List 输出拼装，没有另写 SIR 语法、名称绑定或编译语义，没有新依赖。

39 个历史源码从 Git 读取；27 项额外输入在 `probe-controls.json`，涵盖合法修饰词、普通逻辑表达式、缺尺寸、缺逗号、缺错误名、错误容器等。某些对照只为观察解析状态而构造，不声称语义检查或完整生成成功。

```text
python experiments/slm/drafts/parser-diagnostics/observe.py --jar target/ci-investigation/204/standalone-cli/kcg.jar --artifact-zip target/ci-investigation/204/standalone-cli.zip --output-dir target/parser-diagnostics-design/observation-reproduced
```

JAR/ZIP 不存在时，先取得上述原始 artifact；不能换成别的检查器。输出目录必须为空，脚本拒绝覆盖原观察结果。两次 Java 进程均显式限制 heap 256 MiB、metaspace 128 MiB、ActiveProcessorCount=2，去除额外 JVM 注入，串行运行；不执行 Maven、数据库或应用，不加载模型。

已完成的 66 项观察中，公共诊断逐项一致，39 个历史源码全部仍失败。`observations.json` 为观察输出的缩进 JSON 副本；值完全相同，但不是声称来自 CI 的原始文件。`observation-provenance.json` 同时记录原序列化与可读副本 SHA256、辅助源码/控制输入哈希及实际命令。

两个关键边界已确认：真正漏 else 时页码/尺寸子表达式完整，而当前 page 规则本身可能有本次异常；缺尺寸恢复后也会在 AS 报期待 ELSE，但尺寸子树含 ErrorNode。仅看报错 token/expected 集会误报。关键字类的 12 份中，6 份首条已有专用提示，另 6 份被 extraneous 的通用提示遮住。

首次临时观察器误用了 CLI 不含的 Jackson，编译失败、未进入 Parser；原 stderr 在本机 target/parser-diagnostics-design/probe-0.stderr.txt，未清洗、未计为成功。随后不增加依赖改为上述桥接。基线观察只证明设计条件可从原回调读取，**不证明拟议的新诊断已经实现或测试通过**。

## 文件与边界

`MANIFEST.sha256` 只覆盖本目录除清单自身外的文件；它不是 datasets 的冻结 manifest。原始 CLI ZIP 继续使用已有 CI 归档，不在此再复制。统计和观察可重复，不更新旧 checked、候选、资格或学生数据。

统计阶段模型调用 0，未实施新行为。随后实施已完成定向 RED/GREEN 与兼容核对，详见上述实施记录；目前仍为 49 条合格答案与学生 v4，不训练、不调用模型，未提交推送，新 SHA CI NOT_RUN。本目录的旧观察不能替代新功能验收。
