# 设施报修与巡检工单题追溯（规格已确认，判卷未实施）

family `maintenance.work-order` / train；基线 `e81200e0e039c5d178c95bbab2f12195ac13f0dd`。本目录七份文件是规格，尚无本题业务IT、DDL/seed落地文件、task或CI资格。spec §6列的是待实现检查，不能写成已有测试方法。静态实测：reference/base均GENERATION、diagnostics=[]、55/47文件，原输出和摘要在spec §0.3。

## 1. 输入、参考与base

- [reference.sir](reference.sir)：5实体、8能力；版本化人工报修与非版本化批处理巡检分开，理由及已确认的M1边界见spec §0.2/§7。
- [base.sir](base.sir)：仅删除EditWorkOrder、ListWorkOrders、GenerateInspections、CloseOverdueInspections四个完整能力块，其余字节不变；55→47生成文件，不冒称base已启动并得到HTTP失败。
- [contract.json](contract.json)：固定接口、类型、排序、错误和评分排除项；assignee的HTTP名及物理列均不加Id/_id；§2/§3的DDL/seed只存在于spec。
- 自然语言题面：[requirement-scratch.md](requirement-scratch.md)与[requirement-change.md](requirement-change.md)。S/C编号是非空行顺序；最后一行是输出约定，不算业务HTTP断言。spec §5保留同一原文。

## 2. 逐句追溯

R=`docs/SIR_LANGUAGE_REFERENCE.md`，行号固定到上述基线。所有“投影/UNCHANGED”断言展开成spec §6.1的完整JSON/JDBC比较；不能只检查字段存在或行数。

| scratch原句 | change承接 | 参考能力/声明 | 规格§6检查 | 语言依据R |
|---|---|---|---|---|
| S01 真实身份/匿名/不考登录 | C02 | AccessToken、全部能力 | B01–B02、W01、D01 | 372–389、764–807 |
| S02 设施目录 | C01保留 | ListFacilities | B01、L04 | 450–472、1115–1162 |
| S03 新建/必填/默认 | C01保留 | OpenWorkOrder、WorkOrder | W01–W03、D01、I01 | 86–113、311、402、1348–1374 |
| S04 详情权限 | C01保留 | GetWorkOrder | D01、P07、I02 | 340–370、809–854、989–995 |
| S05 局部字段/保留与清空 | C03 | WorkOrderPatch、EditWorkOrder | P01–P03、P05–P06、P08、I01 | 111、257、404–448、1372–1374 |
| S06 版本/同值/并发 | C04 | EditWorkOrder | P01/P03–P04、X01、I03 | 1372–1374、1003–1007（锁与版本路径区别） |
| S07 软删除/不可恢复 | C05 | WorkOrder.scope、Edit/Get/List | D01、P07、L01、I02 | 340–370 |
| S08 可选指派人筛选 | C05 | WorkOrderQuery、ListWorkOrders | L01–L03 | 106–113、1222–1250、2043–2050 |
| S09 周期生成/限制/可重复 | C06 | GenerateInspections | R01–R05、I04 | 1935–1990、2006、2009、2020、2052 |
| S10 批关闭/严格边界/超限 | C07 | CloseOverdueInspections | E01–E04、R05、X02、I05 | 1801–1848、1933 |
| S11 巡检列表/串行化 | C01保留列表、C07 | ListInspections、两个批入口 | L05、R01、E04、X02–X03 | 997–1009、1115–1162、1933 |
| S12 分页 | C08 | 三种分页能力 | B01、L02、L04–L05 | 402、1115–1162、2041 |
| S13 合同/写范围/信封 | C08 | 全部；视图/错误 | 全部WV/IV/FV/PAGE、ERR、UNCHANGED及成功全库差异，W03、P06/P08、I01–I05 | 452、595、897–918 |
| S14 完整文档输出 | C09 | 完整SIR根文档 | 正式检查器静态门，非HTTP项 | 17–67 |

C01的“保留”意味着W01–W03、D01、ListFacilities/ListInspections仍受判卷，不能只测四个新增入口。C02的七入口四身份矩阵为28个真实请求，不缩成一个请求的口头声明。C08的分页、信封和差异规则在上表每个请求展开，不另造空泛检查。

## 3. 结构覆盖与手册对应

| 结构 | 实际位置 | R | v9手册行 |
|---|---|---|---|
| versioned | WorkOrder.version，人工处理记录 | 257、1372–1374 | 32–33、41 |
| patch of / .present / persist else | EditWorkOrder | 404–448、1374 | 41、75 |
| Optional<Ref<User>> / is none or | WorkOrder.assignee、ListWorkOrders | 106–113、1222–1250、2043–2050 | 71、95 |
| repeat / bounded transaction | GenerateInspections，limit24 | 1935–1990 | 84 |
| each / bounded transaction | CloseOverdueInspections，limit50 | 1801–1848、1933 | 83 |
| scope | WorkOrder.removed；所有load/find共同生效 | 340–370 | 34 |
| for update | 两个巡检写入口先锁Facility | 997–1009 | 68、91 |
| Page/order | ListFacilities、ListWorkOrders、ListInspections | 1115–1162 | 40、69 |
| addDays/精确整数乘法 | 按occurrence×intervalDays推dueAt | 2006、2009、2020、2052 | 94 |
| 必填/分页例外 | 普通输入与page/size | 402、2041 | 39–40 |
| create/default/引用投影/凭据 | 新建与三个view、AccessToken | 311、372–389、452、1348–1356 | 33、35、38、42、73 |

## 4. 并发与证据职责

X01目标work_order105：六个同版本编辑，1胜5冲突，最终版本1/赢家note；X02目标facility10：六个批关闭全200，最终只关闭201/202；X03目标facility10：生成/关闭两个请求，三张新行必须全开或全闭，对应两种合法串行次序。所有请求都必须由独立HTTP客户端实际发出；数据库控制锁+真实等待图确保N个不同连接/事务全部到达屏障；超时、权限不足及失败处理按spec §6.6，不拿顺序请求代替并发。

夹具自检、external配置、readiness、进程清理/报告不是候选业务需求，但失败会阻断有效证据；构建/启动/业务/并发均NOT_RUN。不继承博客或fitness/events的资格，更不给未来教师候选继承本参考静态通过。

## 5. 负责人已确认的边界

- M1：接受两种工单拆分；不强求批关闭版本化人工报修。
- M2：指派域限已有User或null，不承诺未知User对应业务404；Optional引用存在性分支的语言限制不绕写。
- M3：removed=null沿用上游未决边界，本题暂不评分；title=null仍按目标非空白规则检查，PATCH错误路径changes.title、模板码notBlank，不与普通新建DTO的NotBlank混同。
- 接受spec §0.1的dev/test隔离结论，保留maintenance.work-order作为train；不启用质检批次备选。

以上边界已获负责人确认。本轮仅办理本目录七份规格文件的一次文档提交与推送，不触发CI；落地/四门/任务冻结仍须另行安排，不由规格确认自动启动。
