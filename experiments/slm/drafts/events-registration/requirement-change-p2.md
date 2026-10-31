在给定软件上补回以下接口：Register、EventRevenue、EventRegistrationCount。
保持现有行为的接口：CreateEvent、ListEvents、MyRegistrations。
身份认证：除 ListEvents 外，所有接口通过 Authorization: Bearer 访问令牌识别当前用户；令牌缺少、格式错误、未知或过期时均返回空体 401；ListEvents 允许匿名访问。
Register：按路径中的 id 为当前用户报名，成功返回 201 和接口合同中的 RegistrationView。
报名记录：保存当前用户对该活动的唯一报名记录；记录的 fee 取报名时刻该活动的 fee；registeredAt 为服务器处理请求的时刻；同时该活动的 registered 加一。
报名错误（同时满足多个条件时按以下顺序返回）：活动不存在返回 404 EventNotFound；活动已开始（startsAt 不晚于当前时刻）返回 409 RegistrationClosed；同一用户已报名该活动返回 409 AlreadyRegistered；名额已满（registered 不小于 capacity）返回 409 EventFull。
组织者也可以报名自己的活动。
并发报名：多个用户并发报名同一活动时，成功次数不超过名额，活动的 registered 始终等于该活动的报名记录数。
并发重复报名：同一用户并发重复报名同一活动时恰好一次成功，其余返回 409 AlreadyRegistered，且 registered 只增加一次。
EventRevenue：仅限该活动的组织者使用，非组织者返回 403 Forbidden；活动不存在返回 404 EventNotFound（先判断活动是否存在）。
计算规则：返回该活动全部报名记录的 fee 之和，按报名记录上保存的 fee 计算，不用活动当前的 fee；没有报名记录时为 0；结果为 JSON 数字，不能有浮点误差。
EventRegistrationCount：使用权限和错误与 EventRevenue 相同；返回该活动的报名记录条数，为 JSON 整数；没有报名记录时为 0。
写入约束：所有拒绝都不能改变已有行或新增业务记录；成功只改变该操作应改变的行。
错误格式：声明错误返回接口合同中的 code、message、fields。
接口约定：所有路由、请求字段和响应字段采用接口合同；路径中的字段值以 URL 为准，不能被同名请求体字段替换。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
