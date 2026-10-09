# SIR批量静态校验

## 用途与资格

`kcg check`把UTF-8 JSONL从stdin逐行校验、逐行向stdout输出canonical JSON。一次启动一个JVM，串行处理，不读写工程、状态/锁/Bundle，不生成磁盘工程，不构建或启动应用。

**ok=true仅表示请求的静态阶段通过，样本只能据此进入候选池。** GENERATION不等于生成Java能编译、应用能启动或符合原始业务需求。独立真实评测见[总设计](qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)；已授权的两样本限定试跑见[实际报告](qualification/Q23_REAL_EVALUATION_PILOT.md)，不代表新SLM样本或通用运行平台已验收。

## 当前源码构建后的直接调用

在仓库根目录，已有Java21编译产物与ANTLR运行时后执行（不是新发行脚本或fat JAR）：

```bash
CP="$(printf '%s:' "$PWD"/{kcg-cli,sir-toolchain-application,sir-change,sir-project-graph,sir-generator-spring-boot,sir-lowering-spring-boot,sir-lowering-api,sir-semantic,sir-parser}/target/classes)$HOME/.m2/repository/org/antlr/antlr4-runtime/4.13.2/antlr4-runtime-4.13.2.jar"
java -XX:-UsePerfData -Xmx256m -XX:MaxMetaspaceSize=128m \
  -cp "$CP" io.kcg.cli.KcgCli check < samples.jsonl
```

该classpath/调用已本机冒烟验证。调整Maven仓库位置时改ANTLR jar路径；没有产物时需先按项目约束构建受影响模块，不能将Maven构建混入checker只读或每条样本启动。

文件读取由shell重定向负责，命令没有--input/--output/--state-root参数。调用方可自己重定向stdout保存结果；那是调用方输出文件，不是checker写工程。`-XX:-UsePerfData`避免HotSpot创建性能数据目录；如需逐目录验证，测试准备/构建/JVM宿主活动与checker测量范围分开。

## 输入

一行一个对象，例如：

```json
{"id":"A1-1","sir":"sir 0.1\nsoftware Example {}","stopAfter":"SEMANTIC"}
```

此例仅演示传输格式，不声称该软件满足语义或生成目标。id/sir必须是字符串；id允许空或重复、原值回传，不参与身份或缓存。stopAfter省略默认GENERATION；四值PARSE、SEMANTIC、LOWERING、GENERATION是真实停止点。尚不支持SIR0.2多源。

- id≤1024 UTF-8字节；sir非空白、严格Unicode、≤1,048,576 UTF-8字节；原始JSON行≤8,388,608字节，允许LF/CRLF/末行无换行。
- 空行是坏样本，空文件零结果；文件末尾一个换行不额外制造样本。JSON外壳无BOM，sir字符串BOM按现有Parser处理。
- 拒绝重复键/未知键/错类型/非法JSON或UTF-8/不合法Unicode代理字符。错shape值有界跳过以保留合法id；嵌套总深度≤32，不建通用JSON树。全部诊断/字段不截断。
- 单批前10,000物理行进入校验（坏行也计数）；超出部分仍逐行返回BATCH_LIMIT，id=null，不再解析字段/编译，以维持一一对应。调用方须切批，超限尾部不是被接受的样本。

## 输出与诊断

固定顶层字段顺序：id、ok、stage、diagnostics、fileCount、digest、sourceSha256。每行以LF结尾。失败stage是最早失败阶段，成功stage是实际最后阶段；输入错误标PARSE，采用独立KCG-CHECK-INPUT/LIMIT code与无SIR span，不混成编译器业务诊断。

每diagnostic：code、severity、stage、原message、span、原related/fixes、sourceSymbol/sourceNodeId/loweredNodeId/relativePath（原阶段无则null）。span保sourceId、1起行列、0起Unicode code-point offset，end左闭右开；不把related改写成INFO，不改文本。保原阶段/原数组顺序；后阶段内部异常仍保留先前已记录的diagnostic，再附固定内部错误，不输出异常栈/路径。

固定逻辑SourceId为sample.sir，不来自样本id或原文件绝对路径。无法取得可靠id的非法JSON/编码/预算/缺id情况回null，完整合法id遇其他字段失败仍回传；重复id键不猜其中一个值。

- sourceSha256：解码后的sir原文严格UTF-8 SHA-256，保BOM/换行/空白；合法类型/编码且在预算内时，即使编译失败仍提供。无法取sir、超限或sir键重复导致歧义则null。
- fileCount/digest：仅GENERATION成功提供，其他0/null。digest按相对路径String.compareTo排序，对`ASCII("KCG-SIR-CHECK-GENERATED-V1\0") + uint64BE文件数 + 每文件[uint64BE路径字节数+路径UTF8+uint64BE内容字节数+内容UTF8]`做SHA256。\0是一个零字节，不是反斜线和数字0；输出小写64hex。该域不等于已有Bundle或源清单摘要。

## 退出码

- 0：整批处理完成；样本ok=false是正常数据结果，不使命令失败。
- 2：CLI参数错误，输入未处理；原CLI usage协议保持。
- 3：stdin/stdout传输故障，可能不完整，不应把输出当完整批。
- 70：内部故障；可隔离的RuntimeException/StackOverflowError先保该行固定ERROR并继续其他行，整批标内部失败。OOM/进程中止等不能承诺逐行完成，不当普通SIR错误或成功。

## 证据与边界

资格以[当前工作单](roadmap/ACTIVE_WORK.md)、[资格报告](qualification/CURRENT_QUALIFICATION.md)为准。当前验证涉及现有20个合法0.1 fixture、既有非法阶段、逐文件旧生成一致、混批与预算、独立JVM/Locale确定性、目录指纹、原CLI回归及1000条规模。规模时间/RSS只反映该fixture/机器，不推导任意业务吞吐。

无新第三方依赖，无HTTP/daemon/数据库评测，无生产FixHint增强。STEP0_EXPERIMENT_HANDBOOK.md未取得，不能声称已完成手册对接；接口ok/stage/diagnostics[].code与四stage保持需求约定。
