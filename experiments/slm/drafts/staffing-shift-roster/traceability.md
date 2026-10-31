# 门店排班题追溯（判卷源码已落地，参考业务 CI FAILED）

family `staffing.shift-roster` / train，基线 `e81200e0e039c5d178c95bbab2f12195ac13f0dd`。规格已确认；现已另获授权并落地Java/IT、DDL/seed和两个workflow；尚无task或合格CI资格，已有失败CI证据见implementation §5。实现入口见§6，验证状态见 [implementation.md](implementation.md)。reference/base最终均GENERATION、diagnostics=[]、56/50文件，真实stdout见spec §0.3；首轮引用排序失败也已登记，不隐去失败。

## 1. 参考、base与输入

- [reference.sir](reference.sir)：6实体、7能力；repeat生成空班次，再由独立能力新增员工编排关系，不能在循环里直接为员工“重复预约”。
- [base.sir](base.sir)：仅删除GenerateWeeklyShifts、AssignEmployee、ShiftHeadcount三能力，其余字节不动，少三个Controller/Service。base仍有门店/班次/员工/员工编排列表，种子使其可观察；没有运行base HTTP。
- [contract.json](contract.json)：路由、字段、类型、错误、排序、唯一键、limit及不考容量/区间排他的边界。
- 两份自然语言全文：[requirement-scratch.md](requirement-scratch.md)（S01–S13）、[requirement-change.md](requirement-change.md)（C01–C08）；编号为非空行次序，最后一行静态输出约定不当作HTTP业务项。spec §5保留同一原文。
- spec §2/§3的SQL已原样提取到test resources，未执行；保留两个真实联合唯一键和完整外键，但不声称并发去重已验收。

## 2. 逐句追溯

R=`docs/SIR_LANGUAGE_REFERENCE.md`，行号固定在上述基线。全部投影/UNCHANGED按spec §6.1展开为精确JSON/JDBC比较，不只查字段或行数。

| scratch | change承接 | 参考能力/声明 | 规格§6检查 | R依据 |
|---|---|---|---|---|
| S01 身份/匿名/预置 | C02 | AccessToken、全部能力 | B01–B02、A01 | 372–389、764–807 |
| S02 manager/门店目录 | C01保留、C02 | ListStores、所有管理能力 | B01、Q06、G06、A04–A05 | 809–854、1115–1162 |
| S03 周生成/必填/上限 | C03 | GenerateWeeklyShifts、WeeklyShifts | G01、G03、G05–G06 | 402、1935–1990、2041 |
| S04 日期/重复/晚失败/不占用 | C03 | Shift.unique、GenerateWeeklyShifts | G01–G04、G07、I03/I05 | 261、1376–1378、1990、2009、2052 |
| S05 门店班次/员工列表 | C01保留 | StoreShifts、StoreEmployees | Q01–Q02、Q04/Q06、G01 | 452、1115–1162 |
| S06 新增编排/错误顺序 | C04 | AssignEmployee、Assignment.unique | A01–A05、A07 | 261、989–1009、1376–1378、1624–1631 |
| S07 同人竞争/多员工/同日两班 | C04 | AssignEmployee | A02–A03、A06、X01–X02、I02 | 997–1009、1378、1679 |
| S08 按班人数 | C05 | ShiftHeadcount | Q05、A01–A03/A06–A08、X01–X02、I01 | 1624–1631、1679 |
| S09 按员工列表 | C01保留、C07 | EmployeeRoster | Q03/Q06、A01/A06/A08 | 1115–1162 |
| S10 分页 | C07 | 四个分页能力 | B01、Q01–Q04 | 402、1115–1162、2041 |
| S11 同计划并发/完整批次 | C06 | GenerateWeeklyShifts | G04、X03、I03/I05 | 997–1009、1378、1990 |
| S12 合同/路径/拒绝不写/信封 | C06 | 全部view/error/interface | 全部SV/EV/AV/TV/PAGE/INT/ERR/UNCHANGED和成功全库差异、G06、A05/A07、I04 | 452、595、897–918 |
| S13 输出完整文档 | C08 | 完整根文档 | 正式静态检查，不算HTTP断言 | 17–67 |

C01保留四能力也必须全部判卷，不因是改写题就忽略Q01–Q04/Q06。C02六入口×四非法身份=24请求，C07四分页路由×六坏参数=24请求；不把覆盖矩阵缩成一句“测试鉴权/分页”。

## 3. 结构覆盖与手册

| 结构 | 能力/位置 | R | v9行 |
|---|---|---|---|
| repeat | GenerateWeeklyShifts，limit8 | 1935–1990 | 84 |
| addDays与整数乘法 | firstDay加week×7个日历日 | 2006、2009、2020、2052 | 94 |
| unique(employee,shift) | Assignment；AssignEmployee | 261 | 30、75 |
| unique(store,day,band) | Shift；GenerateWeeklyShifts | 261 | 30、75 |
| exists后persist else | 上述两处新建；锁不豁免 | 1376–1378、1624–1631 | 75、79 |
| for update | Generate先锁Store、Assign先锁Shift | 997–1009 | 68、91 |
| count | ShiftHeadcount，Serial标量、空集0 | 1624–1631 | 79 |
| Page/order | ListStores、StoreShifts、StoreEmployees、EmployeeRoster | 1115–1162 | 40、69 |
| 普通输入必填/分页例外 | firstDay/band/weeks/employeeId vs page/size | 402、2041 | 39–40 |
| 凭据/权限/Ref投影 | AccessToken、manager比较、全部view | 372–389、809–854、452 | 35、38、42、93 |

体内仅exists/validate/create/persist，return在体外；没有find/嵌套循环/sum/avg/month，错误均列fails；R:1846–1848/1990、V:83–84。工作流count是允许的独立查询步骤，不把它误称循环禁用的sum/avg。

## 4. 并发与不变量

X01六请求编同一(103,203)，1×201/5×AlreadyAssigned，只增一关系；X02三请求把101/102/103编入203，三胜，证明班次不是独占预约；X03六请求发同一门店三周计划，一批成功五批ShiftExists，只增三班且无新增关系。

每轮先重置；目标Shift或Store由控制连接持锁；ready/start同时放行N个独立HTTP客户端；必须看到N个不同运行时连接/事务均进入该目标等待图后释放。观察5秒、请求30秒、整轮35秒；缺锁观测权限NOT_RUN，死锁/500/超时均失败，不能拿顺序执行替代。最终读取六表完整差异，并独立从JDBC重算各班人数与每个唯一键、归属不变量，不从成功请求数推导正确性。实现细则见spec §6.5。

同SHA CI构建/启动通过，业务752项通过、22失败；X01/X02业务通过，X03因band的OGNL错误失败，三轮真实锁屏障均已观测，见implementation §5；就绪/种子/环境/清理是执行器前置，不要求教师实现判卷器。任何未来候选都要独立过四门，不继承参考或其他题资格。

## 5. 发现与负责人已确认的裁决

- S1：普通find不允许按Ref<Shift>排序，首轮真实SIR-TYPE-001；当前EmployeeRoster按编排id升序，A08用反日期插入验证，不伪装成日历接口。
- S2：与booking的repeat机制重合，但“空班次→多员工编排、无起止区间/排他”的业务形状不同；负责人已确认接受train隔离结论。若要改成逐周直接为员工占用区间，则过近，应换题或归dev。
- 负责人已确认S1的关系id排序及不设容量、工资和跨店调动的边界；下一阶段不得自行补业务。保留本题，不启用spec §0.1的production.checklist备选。

规格阶段文档已单独提交；本阶段是在另行授权后落地判卷，并已获负责人一次提交、普通推送与同SHA CI的授权；判卷实现提交未改工具链；CI失败后的根因修复另获本地提交授权，未推送或运行新CI。已有失败CI证据，尚无四门资格，task冻结仍未办理。

## 6. 本阶段源码入口（尚无业务资格）

源码目录为 `sir-toolchain-application/src/test/java/io/kcg/sir/application/conformance/`。

| 检查 | 实现 |
|---|---|
| B01–B02 | `StaffingRosterChecks.identity` |
| Q01–Q06、I01–I03 | `StaffingRosterChecks.listings/recompute/validRelations/uniqueShifts` |
| G01–G07、I03/I05 | `StaffingWeeklyChecks.generation/boundsAndLateRollback/invalidPlans/leapYear/generatedState` |
| A01–A05 | `StaffingAssignmentChecks.assignmentsAndRefusals/assign` |
| A06–A08 | `StaffingAssignmentChecks.sameDayBands/pathIdentity/relationOrder` |
| X01–X03 | `StaffingConcurrencyChecks.sameEmployee/differentEmployees/sameWeeklyPlan` |
| I04–I05、所有完整投影/全库差异 | `BoundedProblemHttp` / `BoundedProblemState`，业务检查只用请求参数和夹具常量构造允许差异，新ID取响应或JDBC差集 |
| 真实N客户端/锁等待/收齐与释放 | `BoundedProblemRace`，复用既有 `BlogLockWaits` |
| 六表种子/夹具/external/执行器 | `StaffingShiftRosterBusinessConformanceIT` → `BoundedProblemConformance` / `BoundedProblemFixture` → 既有 `BusinessSliceHarness` |

运行资源为 `valid/staffing-shift-roster.sir` 和 `conformance/mysql/staffing-shift-roster-{ddl,seed}.sql`。两个workflow接入同一IT；既有Harness切换参考或候选，不改变oracle。真实业务与并发已执行且参考整体FAILED，证据与原因见 [implementation.md §5](implementation.md#5-已执行的同-sha-cifailed)。
