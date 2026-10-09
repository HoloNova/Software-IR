# ADR-025：批量静态SIR校验与原始阶段诊断

- 状态：Proposed（负责人已确认Q23实施方向；最终Accepted待同SHA CI与验收）
- 日期：2026-10-09
- 工作单：[Q23](../roadmap/ACTIVE_WORK.md)

## 背景与原因

SLM数据闭环需要一次JVM内校验很多SIR0.1样本，坏行不终止整批、反馈诊断可无损回喂。SirCompiler原固定至Graph，Toolchain实际用SirCompilation固定至生成；旧ExecutionDiagnostic/Mapper已丢related/fixes及部分Lowering身份，不能从旧输出反推。旧CLI只有context/plan，文件和状态归Application，不为check放宽门禁。

## 决策

独立SirValidationApplication typed请求/结果/批流，不改旧公开合同、核心语义或产物。两旧内存助手复用CompilationStages：新入口四stop真实停止、不走Graph；旧compile/lowerAndGenerate仍完整生成、保generation注入与旧diagnostic投影，SirCompiler生成后仍建Graph。原始诊断在行级trace保持直到完成/异常，旧投影仍只给旧调用方。新ValidationDiagnostic保span/related/fixes及各阶段附带身份，不向旧ExecutionDiagnostic塞字段。

JSONL framing、strict解码、资源/输入守卫及编译编排在Application。CLI strict check只传stdin typed请求并canonical渲染；文件由shell重定向，stdout捕获也归调用方，不开文件/锁/状态。单JVM串行流式、独立行上下文、无编译缓存/整批集合；重复id不参与语义。

限额：SIR1MiB/id1KiB/JSON行8MiB/10,000行接受上限。超批尾部逐行报错/id=null并不编译，不截断输入输出对应。错shape值只用32有界迭代帧验证/跳过，不造通用JSON AST；本单不加JSON库或其他依赖。普通坏行返回PARSE输入诊断（独立code），保持实验四stage。可隔离内部故障不泄露异常消息，保前诊断/继续后行并以70标进程内部失败；传输不完整另以3报告。

固定逻辑sample.sir，不含路径/时间/随机/Locale。新Renderer复用JsonStringEncoder、仅在本输出对孤立surrogate无损JSON转义，不改旧encoder输出。两个哈希分别表达输入原字节与排序生成路径/内容；长度framing/版本域冻结在[操作协议](../SIR_BATCH_CHECK.md)，不混用基线散列。

静态ok=true只进候选池，不能声明Java构建/启动/业务正确。真实工程评测独立按原始需求断言、参考MySQL/HTTP/环境元组及证据，[总设计](../qualification/SLM_GENERATED_PROJECT_EVALUATION_PLAN.md)与后续明确授权的[限定真实试跑](../qualification/Q23_REAL_EVALUATION_PILOT.md)独立于静态入口，测试环境允许落盘/DB，check仍无写入/运行阶段；不发布生产部署/通用评测服务。FixHint生产增强等STEP0失败归类另审。

## 替代方案与代价

直接套SirCompiler会多跑Graph/不能提前停止且丢字段；单独重写第三条编译链会规则漂移；CLI自开文件或编排编译违反边界；每行JVM或默认并行增加成本/顺序/资源问题。采用共享阶段助手/独立反馈DTO，代价是共享改动必须覆盖旧单源与多源编译的定向回归，JSON解码须自己以边界向量证明正确，不能放宽旧测试。

## 验证与限制

20合法0.1与两非法阶段探针；新旧Toolchain逐文件字节/独立hash向量；四stop阶段计数、原诊断/合成fix无损、混批/大小/JSON/Unicode/内部fault；独立JVM+Locale/工作目录同字节/只读指纹；App/CLI架构灵敏度探针；旧context/plan帮助与version HEAD字节golden；旧生成/Graph/计划/多源/改名应用指定类；1,000同源完整生成的单JVM资源实测。详见Q23本机证据。

双全量门和五IT须同SHA CI，当前NOT_RUN，无自动Git操作。0.2多源校验、生成工程BUILD/START/BUSINESS资格、发行fat JAR/服务/缓存/修复提示不在本单。尚未取得实验手册，不声称集成完成。Q22仍主动搁置，G2未关闭。
