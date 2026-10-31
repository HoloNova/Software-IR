身份认证：除 ListEvents 外，所有接口通过 Authorization: Bearer 访问令牌识别当前用户；令牌缺少、格式错误、未知或过期时均返回空体 401；ListEvents 允许匿名访问。
CreateEvent：接收 title、startsAt、capacity、fee，创建以当前用户为组织者的活动，成功返回 201 和接口合同中的 EventView，初始 registered 为 0。
字段规则：title 为 1 到 100 个字符的非空白文本；capacity 为不小于 1 的整数；fee 为不小于 0 的金额，最多 8 位整数和 2 位小数（0 到 99999999.99）；缺少、null、越界或超过两位小数时均返回 400 INVALID_REQUEST 和对应字段错误；不要求 startsAt 晚于当前时刻。
ListEvents：分页返回尚未开始（startsAt 晚于当前时刻）的活动，按 startsAt、id 均升序；名额已满的活动仍然列出；EventView 的 remaining 等于 capacity 减 registered。
分页：page、size 可分别省略，默认值分别为 1、20；有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage。
分页响应：total、page、size、records；total 为全部匹配记录数，越界页仍返回正确 total。
Register：按路径中的 id 为当前用户报名，成功返回 201 和接口合同中的 RegistrationView。
报名记录：保存当前用户对该活动的唯一报名记录；记录的 fee 取报名时刻该活动的 fee；registeredAt 为服务器处理请求的时刻；同时该活动的 registered 加一。
报名错误（同时满足多个条件时按以下顺序返回）：活动不存在返回 404 EventNotFound；活动已开始（startsAt 不晚于当前时刻）返回 409 RegistrationClosed；同一用户已报名该活动返回 409 AlreadyRegistered；名额已满（registered 不小于 capacity）返回 409 EventFull。
组织者也可以报名自己的活动。
并发报名：多个用户并发报名同一活动时，成功次数不超过名额，活动的 registered 始终等于该活动的报名记录数。
并发重复报名：同一用户并发重复报名同一活动时恰好一次成功，其余返回 409 AlreadyRegistered，且 registered 只增加一次。
MyRegistrations：分页返回当前用户的报名记录，按 registeredAt、id 均降序。
EventRevenue：仅限该活动的组织者使用，非组织者返回 403 Forbidden；活动不存在返回 404 EventNotFound（先判断活动是否存在）。
计算规则：返回该活动全部报名记录的 fee 之和，按报名记录上保存的 fee 计算，不用活动当前的 fee；没有报名记录时为 0；结果为 JSON 数字，不能有浮点误差。
EventRegistrationCount：使用权限和错误与 EventRevenue 相同；返回该活动的报名记录条数，为 JSON 整数；没有报名记录时为 0。
EventView：仅包含 id、organizer、title、startsAt、capacity、registered、remaining、fee。
RegistrationView：仅包含 id、event、attendee、fee、registeredAt。
其中 organizer、event、attendee 是数值主键。
写入约束：所有拒绝都不能改变已有行或新增业务记录；成功只改变该操作应改变的行（CreateEvent 新增一个活动，Register 新增一条报名记录并使该活动的 registered 加一）。
错误格式：声明错误返回接口合同中的 code、message、fields。
接口约定：所有路由、请求字段和响应字段采用接口合同；路径中的字段值以 URL 为准，不能被同名请求体字段替换；请求体不能指定组织者或 registered。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
