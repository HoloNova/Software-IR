# SLM生成工程真实评测——总设计与限定试跑

> 2026-10-09负责人要求“先进行真实工程评测”，按[ACTIVE_WORK §11](../roadmap/ACTIVE_WORK.md)仅执行已有课程题目的正/错误两样本试跑，结果见[实际报告](Q23_REAL_EVALUATION_PILOT.md)。其余通用平台/新SLM题目仍是待审设计。

## 1. 资格分开，不把静态通过当业务正确

负责人确认采用“两步筛选”：Q23 `kcg check`承担纯内存静态校验，ok=true仅表示所请求阶段通过，样本进入候选池；真实工程构建、启动及业务断言在独立评测任务中完成。本文件不是开工授权，不自动增加CLI BUILD/RUNTIME阶段。限定试跑的MySQL/应用与opt-in IT由ACTIVE_WORK §11和本次负责人指令单独授权，不推导其他样本或生产部署许可。Q22继续搁置。

| 层次 | 能证明 | 不能证明 |
| --- | --- | --- |
| 静态（Q23） | SIR解析、语义/目标支持、生成文件；必须记录实际stopAfter | PARSE通过不证明后续阶段；GENERATION通过不证明Java编译/应用启动/需求正确 |
| BUILD | 相同生成文件在冻结JDK/Maven/依赖/配置中构建成功 | 应用能连接数据库/提供接口，或返回业务正确结果 |
| START | 在参考环境启动并通过已定义readiness/最小路由检查 | 仅进程活着/端口开放不证明功能正确，更不证明原始需求正确 |
| BUSINESS | 按原始需求独立制定的HTTP+数据库断言通过 | 不自动证明未测需求、生产规模/性能、安全或任意环境 |

例：需求只返回“有有效报名的课程”，模型漏状态过滤。SIR合法、Java构建/服务启动都可能通过，但种入只有取消报名的课程后，HTTP错误地把它返回。断言必须来自原始需求/预先审定的题目测试，不得根据候选SIR自动推导“期望返回”，否则错误自证正确。

## 2. 已读取的可复用能力与硬边界

- `QuerySliceBusinessConformanceIT`/`BusinessSliceHarness`既有场景负责真实生成、离线构建、MySQL、Spring Boot、HTTP与独立JDBC断言；另外四IT覆盖写侧、关联、变更、多源更新。它们只证明既有固定fixture，不覆盖新模型候选。
- `ConformanceEnvironment`已经记录参考环境元组（合同revision、target/lowered版本、JDK/Maven/冻结仓库、MySQL实际身份/版本、schema、runtime/harness连接器等），新评测要沿用归属/证据，不另造“只要本机启动就通过”的标准。
- Query IT明确schema来自测试DDL/seed，`productInitializeImplemented=false`；当前工具链不提供数据库初始化/演进，不能直接让任意SIR自己创建匹配库。必须为每种题目准备已审定DDL/种子数据与断言。
- 环境未齐/数据库不可达/取不到互斥锁是NOT_RUN（可保留具体原因），不能把环境失败当样本业务错误。runtime权限仅限env指定schema（当前kcg_conf_run），不得随意造schema名；按现有advisory lock与五场景矩阵互斥。
- STEP0_EXPERIMENT_HANDBOOK.md当前不可读取，题目/Prompt/标准答案/案例数量/原始失败归类均未提供；不得假设已经存在通用SLM runtime runner或所有题目测试。

## 3. 输入、执行与证据的推荐形态

实验调用方准备两个可复查集合：

1. 样本：id、原始需求/题目ID、原始SIR/sourceSha256、Q23结果与GENERATION digest。
2. 题目测试包：审定需求、目标profile、DDL/seed、请求序列、期望HTTP响应/错误及数据库后置状态、测试revision。未有测试包时只能给BUILD/START结论，BUSINESS保持NOT_RUN，不能标训练答案正确。

推荐流程：静态候选（完整GENERATION）→按题目绑定测试包→从相同SIR实际生成临时工程→核对生成digest→冻结依赖构建→预备测试库→在受限临时环境启动→readiness→按测试包真实HTTP调用/JDBC独立核验→关闭应用/回收本任务材料→上传报告/日志/源码与测试绑定证据。

运行过程允许临时工程、进程、数据库测试数据，故不能放入Q23只读接口；新的评测编排仍由Application/测试框架承担，脚本不另实现编译/生成规则。构建或启动失败记录相应门，后续业务门NOT_RUN；基础设施故障分别归因。

每项报告独立记录 `static/build/start/business` 的PASSED/FAILED/NOT_RUN以及失败原因/断言、sampleId/sourceSha256/generatedDigest、工具链SHA、测试包revision、参考环境元组、run URL/产物、stdout/stderr及退出码。保持Q23四stage不变；评测字段是外层实验记录，不能冒充新增SIR诊断。

相同生成digest可以复用**同工具链/目标与构建环境**的有效构建证据；BUSINESS复用还必须测试包/需求/种子环境相同。不能只凭Java文件hash就把不同需求标成正确，不新增Q23编译缓存。原始SIR不同但生成相同，保留样本各自sourceSha，不覆盖追溯。

## 4. 按用途确定验证规模

- STEP0若只研究SIR语法/语义通过率：Q23批量统计全部样本；按题目/能力组合/新失败类别选代表候选真实跑，以测静态后的失败率与成本。抽样结论只覆盖实际样本，不扩成总体业务资格。
- 若实验指标是业务成功率：纳入该指标的每个候选都需对应BUSINESS判定；NOT_RUN不能当成功/失败，分母/缺失原因单列。
- 若训练数据标签是“业务正确”：要求对应审定测试包BUSINESS PASSED；仅静态通过的样本可留候选池或另有“仅静态合格”标签，不伪造真值。

暂不预设样本数/吞吐：先待手册/题目包可得后，选择具体可复用课程题目作为首个端到端评测，实测BUILD/START/BUSINESS时间、资源、失败分布，再由负责人决定批量规模。重活在CI/参考环境，开发机不批量启动生成工程；串行使用共享schema，资源/超时/子JVM/退出与清理遵守AGENTS，不为跑评测自动commit/push。

## 5. 下一张真实评测工作单的必要决策/完成门

通用平台或新SLM题目实施前必须：读取手册→锁定首个原始题目和测试包→核对Harness哪些能力能直接复用/哪些是固定fixture→提出独立typed评测合同/资源/归因/证据/生命周期→写入ACTIVE_WORK SPEC_REVIEW→负责人确认。此文件不替代上述流程。

推荐验收：正解样本BUSINESS PASSED；漏状态过滤的合法错误样本静态与BUILD/START通过而BUSINESS FAILED；合法但Java构建失败由受控生成故障证明BUILD门灵敏度（不声称已有生产bug）；数据库不可达/权限不符/缺测试包/超时明确归因与NOT_RUN；复测/可复用证据只覆盖同元组；单任务应用/数据库/临时材料无遗留；CI报告绑定源码SHA与原始题目，不复用旧五IT冒充样本验收。

草案起草时只有源码/合同核查、新门全部NOT_RUN。随后限定课程试跑实际完成：两者STATIC/BUILD/START通过，正解BUSINESS通过、漏状态反例BUSINESS失败；三表不变并完成临时环境清理，来源/限额/证据见实际报告。未扩通用平台，缺测试包/环境/BUILD失败归因矩阵与真实SLM题目仍NOT_RUN；等资料与另单批准，不拿这两个受控样本声称模型业务成功率。
