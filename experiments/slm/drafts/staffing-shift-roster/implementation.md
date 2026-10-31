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
