在给定软件上补回 GenerateWeeklyShifts、AssignEmployee、ShiftHeadcount，保留 ListStores、StoreShifts、StoreEmployees、EmployeeRoster 的现有行为、全部声明和接口合同。
ListStores 允许匿名访问，其余接口使用数据库中的真实 Authorization: Bearer 凭据识别用户，缺少、格式错误、未知或过期返回空体 401，只允许对应门店 manager 操作，不实现注册或登录。
GenerateWeeklyShifts 接收路径 storeId 及必填 firstDay、band、weeks，firstDay 是日期，band 为 OPENING 或 CLOSING，weeks 是至少 1 的整数，缺失、null 或非法值返回 400 INVALID_REQUEST；门店不存在返回 404 StoreNotFound，非 manager 返回 403 Forbidden，之后检查最多 8 周，超过返回 409 TooManyWeeks 且整批不写；成功空体 201，第零周为 firstDay，以后每周加 7 个日历日，band 不变，同一门店、日期、band 已存在则返回 409 ShiftExists 并撤销本批先前所有新建班次，两个不同 band 可在同一天存在，生成时不编排员工，不做资源预约或区间重叠检查。
AssignEmployee 按路径 shiftId 和必填 employeeId 新增员工编排关系，成功 201 AssignmentView；按班次不存在（404 ShiftNotFound）、非门店 manager（403 Forbidden）、员工不存在（404 EmployeeNotFound）、员工不在该店（409 WrongStore）、员工已在该班次（409 AlreadyAssigned）的顺序拒绝；同一员工同一班次并发编排恰好一次成功，其余 AlreadyAssigned，最终只有一行，不同本店员工并发编入同一班次全部成功，同一员工能参加同日两个不同班次，不设容量、工资和预约冲突规则。
ShiftHeadcount 返回班次全部编排关系条数，是 JSON 整数，无人是 0，班次不存在返回 404 ShiftNotFound，非该店 manager 返回 403 Forbidden，先判断存在再判断权限；新编排提交后统计立即可见。
同一门店相同三周班次计划并发提交恰好一批成功，其余 409 ShiftExists，只增加三张班次且不增加编排关系；所有拒绝不改变业务行，成功只产生本操作规定的变化，路径优先于同名请求体字段，路由、字段名、字段类型、错误信封及必填校验均按合同。
保留所有分页约定：page、size 可各自省略，默认 1、20，范围 1 到 10000、1 到 100，非法为 400 BadPage，响应 total、page、size、records，越界空页仍有正确 total；EmployeeRoster 按编排关系 id 升序而不是按班次日期排序。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
