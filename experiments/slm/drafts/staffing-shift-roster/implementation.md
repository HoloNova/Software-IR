# 门店排班判卷落地记录

实现基线：main `9cab86894a1e55af68876af562e677a734d28393`。负责人在确认规格后另行授权 DDL/种子、业务 IT 及两个 workflow 接入。本记录先冻结提交前验证现场，下文 NOT_RUN 仅为本机阶段状态，不是四门资格。负责人随后已明确授权一次提交、普通推送并运行同 SHA CI；最终资格以受测 SHA、run URL 和原始 artifact 为准。

## 1. 交付与判卷边界

- 批准参考已落为 `sir-toolchain-application/src/test/resources/valid/staffing-shift-roster.sir`。
- spec §2/§3 SQL 原样落为同模块 `src/test/resources/conformance/mysql/staffing-shift-roster-ddl.sql` 和 `staffing-shift-roster-seed.sql`。保留全外键、真实合成 Bearer 凭据，以及 `UNIQUE(store_id,day,band)`、`UNIQUE(employee_id,shift_id)`；没有CHECK/触发器替候选完成业务。
- IT入口：`src/test/java/io/kcg/sir/application/conformance/StaffingShiftRosterBusinessConformanceIT.java`。
- B/Q与独立重算在 `StaffingRosterChecks`；G01–G07在 `StaffingWeeklyChecks`；A01–A08在 `StaffingAssignmentChecks`；X01–X03在 `StaffingConcurrencyChecks`。方法对应见 [traceability.md §6](traceability.md#6-本阶段源码入口尚无业务资格)。

周生成只新增空 Shift，日期由 LocalDate 按周独立重算，检查8周边界、极大次数先拒、第三周冲突全回滚、同日双band和闰年跨月。Assign只新增独立关系；同班允许多人，同员工允许同日双band。人数从JDBC全部Assignment独立计数，不从201次数推导；员工编排按关系id而非班次日期排序，A08专门反转日期顺序作为负控制。

共享 `BoundedProblemConformance/Fixture/Http/State/Race` 只处理执行器、连接、比较和屏障。六表全列快照验证每个GET/拒绝不写、每个成功写只有规定新增行；严格JSON保证没有manager/凭据等额外字段。没有通过继承maintenance、fitness/events或旧参考资格来判定本题合格。

三个并发轮次分别为6客户端同员工同班（1胜5 AlreadyAssigned）、3客户端不同员工同班（全成功）、6客户端同三周计划（1整批成功5 ShiftExists）。每轮持对应Shift/Store控制锁，全部N个不同运行时连接/事务须形成实际等待链，才释放并收齐响应；超时或未到屏障不是通过。代码已编译，真实轮次未运行。

## 2. 工作流

`verify.yml` 参考门加入本IT和 `-Dkcg.staffing-conformance.enabled=true`，后置摘要要求唯一 `staffing-slice-report.txt` 固定第二行 `verdict=PASSED`。FAILED/NOT_RUN、缺失或重复都阻断。

`slm-business-gate.yml` 的可选IT和启用属性映射加入 `StaffingShiftRosterBusinessConformanceIT` → `kcg.staffing-conformance.enabled`。参考与候选用同一套HTTP/JDBC oracle，沿用既有输入、题目与源码/工具链绑定及 `slm-runtime.json` 资格检查；未改工具链或候选采集程序。

## 3. 已运行的轻量检查

两题共享一次最终定向结果：`BoundedProblemReferenceTest` **6/6**、`BoundedProblemOracleTest` **14/14**，共20项，失败/错误/跳过均0。reference/base内存编译生成、合同/资源/投影/真实凭据配置、比较器负例、同店/联合唯一不变量均覆盖；新IT本身只编译而未执行。TDD的资源缺失RED、接口缺失编译RED、Jackson空输入修复、完整PowerShell命令及原始报告路径统一记录于 [报修题落地记录 §3](../maintenance-work-order/implementation.md#3-本机定向验证与-tdd-证据)。

两个workflow的YAML、20段shell语法和IT开关绑定通过；资格摘要的6种合成报告反例通过。无远端CI运行。AFT诊断桥接超时，未声称LSP结果；Java编译以实际Maven为准。覆盖率未测量，不宣称达到80%。只作定向测试，无全量构建、MySQL、生成应用、浏览器或遗留进程；无UI改动。

## 4. 资格与后续约束

生成工程构建、应用启动、真实HTTP/JDBC业务、三轮并发、base HTTP负例及同SHA参考/候选四门：全部 **NOT_RUN**。CI SHA/run URL/artifact尚无，不能把本机20项检查写成业务通过。未创建task、冻结批次、采样、调用模型或训练。

保持已确认的空班次/多员工/无区间排他、无容量/工资/跨店调动边界。本轮提交/推送与参考 CI 已另获授权；冻结/候选门仍由负责人另行安排；候选先静态检查、失败修一轮，再送业务门，在该链路结束前不合入改变工具链的提交。不改写历史，不自动扩大到任务冻结或模型调用。

## 5. 已执行的同 SHA CI：FAILED

提交为已推送的 `d31af6af8b6f140280f24ee6a2a4992c7b5da585`；[verify run 41187721552](https://github.com/HoloNova/Software-IR/actions/runs/41187721552) 的受测SHA一致。冻结全量门PASSED，Business conformance ITs FAILED；后续摘要、独立验证、CLI和部署跳过，不能标为通过。第3/4节的NOT_RUN是提交前本机现场，本节替代其当前资格状态。

原报告 `staffing-slice-1a19142c471-65384/staffing-slice-report.txt` 为 **752项通过、22项失败**，JUnit本题1项失败。生成文件56个，生成工程构建退出0、应用就绪200；真实MySQL/HTTP与三个并发轮次均执行，应用/schema/锁清理均通过。X01/X02/X03的实际锁等待屏障人数为6/3/6；X01同员工与X02多员工业务通过，X03仅屏障通过、业务失败，不能把它算并发通过。

### 5.1 根因不是班次冲突或 SQL 保留字

原 `logs/spring.stdout.log:31–166` 显示：

```text
Error evaluating expression 'band != null'
org.apache.ibatis.ognl.ExpressionSyntaxException: Malformed OGNL expression: band != null
The error may involve example.staffing.persistence.ShiftMapper.insert
at example.staffing.application.GenerateWeeklyShiftsService.generateWeeklyShifts(GenerateWeeklyShiftsService.java:62)
```

`band` 是OGNL按位与运算符，不是这里的属性标识符。MyBatis-Plus 3.5.12 / MyBatis 3.5.19为非版本化Shift的BaseMapper插入生成 `band != null` 动态条件，OGNL解析即失败，还没到数据库执行插入；给SQL列加反引号不能修复它。Java/SIR编译和应用启动都不会提前执行这段动态SQL，因此未暴露在本机20项检查中。

22个失败断言均落在周生成的成功/重复/晚冲突/闰年/路径场景，以及同计划X03的整批结果与回读。所有尝试写Shift的请求因此500，关联差异为0新增，不能声称晚冲突已真正插入前两行后回滚。A01–A08、既有列表/人数与其他业务检查已执行并通过，不扩大归因为所有排班功能不可用。

### 5.2 原始附件、绑定与停点

原始 [conformance-evidence 13037518243](https://github.com/HoloNova/Software-IR/actions/runs/41187721552/artifacts/13037518243) ZIP SHA256=`e66f8704868d215115dc8c449468df198d9fedc456712af9f8cde85c3294fe16`；[surefire-reports 13037457080](https://github.com/HoloNova/Software-IR/actions/runs/41187721552/artifacts/13037457080) ZIP SHA256=`3ad3441501791bc4e437dab9bcaa90b49829d80812ee9eda2c3ae4fa3f0e109a`，已按API摘要校验。原件及核验记录在根 `target/bounded-problems-ci/41187721552/`，未加入Git。

原报告与受测Git blob逐项一致：SIR SHA256=`5d1cf02d377906ab75004fab1f5ae8f92a7f027da16b5c7617c87e877974c2d0`；DDL=`5b21a1165d021269d3d197c2e6da5216e2201c0816e2712dd2d7d2159d9307af`；seed=`d9972e5984ead0e773adcd985c6f1bc916bd07cee8e453bc4836b4fbbfd6f8b8`。上传Surefire包含datasets中的历史附件，不能混算成当前冻结门数量，详情见报修记录§5.2。

没有偷偷把band改名、手改生成工程、移除断言或重复跑原提交。修复生成属性名/OGNL交互属于工具链工作，须遵守候选判卷收尾前不合入变更的约束；改题面/合同字段也须负责人裁决。本题没有参考四门资格，不冻结、不采样。本段记录初次归因时的停点；后续修复与提交授权见§6。

## 6. OGNL 根因修复（新 CI 尚未运行）

负责人先授权“准备本地修复，暂不提交”，完成验证后又明确要求直接提交；本节随对应修复本地提交，未授权推送或重跑CI。保留批准的 `band` 字段、两份 reference/base、DDL、种子和业务断言，不用业务字段改名绕开工具链问题。报修的版本检查修复、共59项定向回归的实际分轮结果、命令及本机证据路径统一见 [报修记录§6](../maintenance-work-order/implementation.md#6-ci-根因修复与定向回归新-ci-尚未运行)。

新增目标配置 `OgnlPropertyNames`：只在 lowering 为持久化属性/主键遇到 OGNL 关键字时生成内部 Java 名，例如 `band` → `kcg$band`。SIR 的 IDENT 不允许 `$`（Sir.g4:659–661），故该名不会碰撞作者字段；前缀保留字母以避开 MyBatis 对以 `$` 开头属性的反射过滤。SQL 物理列仍是 `band`，输入/视图 JSON 仍是 `band`；没有升级依赖、使用 ALWAYS 空值写策略或改默认值规则。

lowering 的实体声明、版本元数据、成员绑定、引用主键和 CAS 主键共同使用这一命名结果；普通字段、输入和视图名称不变，generator 继续渲染已有 typed IR，不在模板层做字符串替换。关键字表按当前 MyBatis 3.5.19 的 OgnlParserConstants 实际21个单词token核对，`div`/`mod`并非其中的OGNL关键字，不额外改名；SQL保留字仍交原MySqlIdentifiers处理。

`OgnlPropertyRuntimeTest` 的8项检查包含：真实 MyBatis-Plus 的 insert/updateById 与 PreparedStatement 参数绑定、多种关键字、null字段的既有省略策略、默认值和完整JSON投影、关键字主键的CAS，以及原样编译本题参考后真实 ShiftMapper.insert 的 SQL/参数。最后一项实测 SQL 仍为 `INSERT INTO shift (store_id,day,band) VALUES (?,?,?)`，绑定门店12、2026-02-02、OPENING；无数据库连接，不冒称写库或并发已通过。

首版使用前置 `$` 的内部名虽能编译，但被 MyBatis 反射拒绝；已改为字母开头的 `kcg$` 并通过上述实际Mapper测试。JSON对照最后按真实序列化后比较，避免同值 LongNode/IntNode 的内存类型差异，未放宽字段、值或额外字段检查。新增测试及原生产文件已在主会话审阅；既有大文件只作根因所需的小范围修改，没有顺便重构。

第5节原CI报告继续保留 FAILED，修复版真实 MySQL/HTTP、并发、参考四门和同SHA CI为 **NOT_RUN**。按后续明确指令本地提交，未推送或切换远端候选判卷工具链；未改候选冻结输入、未冻结/采样，未调用模型。
