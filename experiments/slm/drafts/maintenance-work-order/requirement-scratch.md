ListFacilities 允许匿名访问；其余接口用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，用户和令牌由夹具预置，不实现注册或登录。
ListFacilities 按 name、id 都升序分页返回设施的 id、name；设施和各自的 manager 由夹具预置。
OpenWorkOrder 接收 facilityId 和 title，为当前用户新建一张报修单，成功返回 201 和 WorkOrderView；facility 不存在返回 404 FacilityNotFound；title 必须是 1 到 100 字符的非空白文本，缺失、null、空串、纯空白、过长均返回 400 INVALID_REQUEST；facilityId 缺失或 null 也返回 400 INVALID_REQUEST；新行 reporter 为当前用户，assignee、note 为空，removed 为 false，version 为 0。
GetWorkOrder 按 id 返回未删除报修单的 WorkOrderView，报修单不存在或已删除返回 404 WorkOrderNotFound，只有该设施的 manager 能读取，否则返回 403 Forbidden；先判断报修单存在，再判断权限。
EditWorkOrder 只允许该设施的 manager 编辑未删除的报修单，成功返回 200 和更新后的 WorkOrderView；请求的外层字段为 id、expectedVersion、changes，id 以路径为准，expectedVersion 是必填版本号，changes 只允许 title、assignee、note、removed，至少提供一项，否则返回 400 EmptyChange；没有提交的字段保持原值，assignee、note 显式提交 null 则清空，assignee 有值时为夹具中已有用户的编号，title 有值仍需满足原规则，note 最长 500 字符，removed 提交布尔值；不能修改 facility、reporter、version。
EditWorkOrder 的 id 不存在或已删除返回 404 WorkOrderNotFound，非 manager 返回 403 Forbidden，已通过权限检查但 expectedVersion 与当前版本不等返回 409 StaleVersion；成功时 version 只加一，即使提交的值与旧值相等也加一；同一版本被并发编辑恰好一次成功，其余返回 StaleVersion 且不能覆盖赢家。
EditWorkOrder 提交 removed=true 就是软删除，保留数据库中的整行；该次成功响应含 removed=true，之后详情、列表、再次编辑都看不到该行，不能通过再次编辑恢复，也不连带删除该设施或巡检单。
ListWorkOrders 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；按 id 升序分页列未删除的报修单，可用 assignee 筛选，省略则不限制（包括未指派行），提供不存在的用户编号则返回空页，不是 404。
GenerateInspections 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；接收 title、firstDueAt、intervalDays、occurrences，title 规则同报修标题，firstDueAt 是 UTC 时刻，intervalDays 是 1 到 30 的整数，occurrences 是至少 1 的整数，字段均必填，缺失、null 或违反范围返回 400 INVALID_REQUEST；最多一次生成 24 张例行巡检单，超过返回 409 TooManyOccurrences 且整批不写；第零张的 dueAt 为 firstDueAt，之后每张增加 intervalDays 个完整的 24 小时，保存同一设施和标题且 closed=false，成功返回空体 201；重复提交是新的独立巡检计划，允许生成相同日期、标题的行，不做预约占用或去重。
CloseOverdueInspections 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden；必填 cutoff 是 UTC 时刻，缺失或 null 返回 400 INVALID_REQUEST；只把该设施 closed=false 且 dueAt 严格早于 cutoff 的巡检单改为 closed=true，按 id 升序处理，一次最多 50 张，匹配 51 张或更多返回 409 TooManyInspections 且一张也不改；没有匹配行时也成功，成功返回空体 200；已关闭、恰好等于 cutoff、晚于 cutoff、其他设施的行和所有报修单都不改变。
ListInspections 只允许路径 facilityId 对应设施的 manager 使用，设施不存在返回 404 FacilityNotFound，非 manager 返回 403 Forbidden，按 dueAt、id 都升序分页返回该设施的全部巡检单（包括已关闭的）；同一设施的巡检生成与批量关闭并发时必须等价于先完整执行其中一个，再完整执行另一个，不允许半批交错。
所有分页的 page、size 可分别省略，默认 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage；返回 total、page、size、records，total 是全部匹配行数，越界空页仍有正确 total。
路由、请求名、响应字段和字段类型严格按接口合同；引用响应是数字编号，未指派的 assignee 和空 note 返回 null，不能泄露 manager 或凭据；路径字段覆盖请求体的同名字段，所有拒绝均不改变业务行，成功只产生该操作规定的行差异，声明错误为合同中的 code、message、fields，普通输入缺值的字段错误按合同给出。
只返回完整的软件描述文档，不要 Java、SQL 或解释。
