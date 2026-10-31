ListStores 允许匿名访问，其余接口用数据库中真实的 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401；用户、令牌、门店、门店 manager 和员工由夹具预置，不实现注册、登录或员工管理。
ListStores 按 name、id 都升序分页返回门店的 id、name；其他操作只能由对应门店的 manager 使用，非 manager 返回 403 Forbidden，不能靠请求体自称 manager。
GenerateWeeklyShifts 接收路径 storeId 及 firstDay、band、weeks，为该门店按周生成离散班次；firstDay 为日期，band 只能为 OPENING（开店班）或 CLOSING（闭店班），weeks 为至少 1 的整数，均必填，缺失、null 或非法值返回 400 INVALID_REQUEST；门店不存在返回 404 StoreNotFound；检查门店存在和权限后，一次最多生成 8 周，超过返回 409 TooManyWeeks 且整批不写，成功返回空体 201。
第零周的班次日期为 firstDay，之后逐周增加 7 个日历日，全部用同一 band，同一门店同一天同一 band 只能有一个班次，已有任意一周的班次返回 409 ShiftExists 且之前几周新建的也全部撤销；OPENING 与 CLOSING 是不同班次，允许同一天各一个；生成班次不指派员工，不预订资源，也不检查时间区间重叠。
StoreShifts 分页返回路径 storeId 对应门店的全部班次，按 day、id 都升序；StoreEmployees 分页返回该门店全部员工，按 name、id 都升序；两者均先检查门店存在（404 StoreNotFound），再检查 manager 权限。
AssignEmployee 接收路径 shiftId 和 employeeId，将该员工编入该班次，成功返回 201 和 AssignmentView；班次不存在返回 404 ShiftNotFound，非该班次门店 manager 返回 403 Forbidden，员工不存在返回 404 EmployeeNotFound，员工不属于该门店返回 409 WrongStore，同一员工已经在该班次返回 409 AlreadyAssigned，按这个顺序判断，成功只新建一条编排关系。
同一员工同一班次被并发编排时恰好一次成功，其余返回 409 AlreadyAssigned，最终只有一行；同一班次的不同本店员工并发编排全部成功；同一员工可以参加同一天的两个不同班次，班次不设容量、工资或预约冲突规则。
ShiftHeadcount 返回路径 shiftId 对应班次的编排关系条数，响应为不带引号的 JSON 整数，无人是 0，先检查班次存在（404 ShiftNotFound）再检查该门店 manager 权限；不能数成该员工的班次数，也不能把别店班次算进来。
EmployeeRoster 分页返回路径 employeeId 对应员工的编排关系，按编排关系 id 升序，不按班次日期排序；员工不存在返回 404 EmployeeNotFound，非该员工所属门店 manager 返回 403 Forbidden，没有编排时返回空页。
所有分页参数 page、size 可分别省略，默认 1、20，范围分别为 1 到 10000、1 到 100，非法返回 400 BadPage，响应恰好为 total、page、size、records，total 是全部匹配行数，越界页仍保留正确 total。
同一门店相同三周班次计划被并发提交时恰好一批成功，其他返回 ShiftExists，数据库只增加三张班次且不增加编排关系；任何一周的失败都不能留下半批班次。
全部接口的路由、请求字段、响应字段及其类型严格采用合同，路径字段覆盖请求体中的同名字段；所有拒绝不改变业务行，成功只产生该操作应有的行差异，声明错误返回合同的 code、message、fields，普通输入缺失或 null 的字段错误采用合同，不能返回凭据或 manager。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
