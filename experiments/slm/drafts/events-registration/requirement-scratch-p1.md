ListEvents 不用登录就能访问。其他接口都靠 Authorization: Bearer 访问令牌认出当前用户，令牌没带、格式不对、查不到或已过期，一律返回空体 401。
CreateEvent 传 title、startsAt、capacity、fee，创建一个以当前用户为组织者的活动，成功返回 201 和接口合同中的 EventView，初始 registered 是 0。title 是 1 到 100 个字符的非空白文本；capacity 是不小于 1 的整数；fee 是不小于 0 的金额，最多 8 位整数和 2 位小数（0 到 99999999.99）。没传、传 null、越界或超过两位小数，都返回 400 INVALID_REQUEST 和对应的字段错误。startsAt 不要求晚于当前时刻。
ListEvents 分页返回还没开始（startsAt 晚于当前时刻）的活动，按 startsAt、id 都升序。名额满了的活动也照样列出。EventView 的 remaining 等于 capacity 减 registered。
分页参数 page、size 都可以单独不传，默认 page 是 1、size 是 20。page 的有效范围是 1 到 10000，size 是 1 到 100，超出范围返回 400 BadPage。响应包含 total、page、size、records，total 是全部匹配的记录数，请求的页超出最后一页时也要返回正确的 total。
Register 按路径里的 id 给当前用户报名，成功返回 201 和接口合同中的 RegistrationView。它保存当前用户对这个活动的唯一一条报名记录，记录的 fee 取报名那一刻活动的 fee，registeredAt 是服务器处理请求的时刻，同时活动的 registered 加一。活动不存在返回 404 EventNotFound；活动已经开始（startsAt 不晚于当前时刻）返回 409 RegistrationClosed；同一用户已经报过这个活动返回 409 AlreadyRegistered；名额满了（registered 不小于 capacity）返回 409 EventFull。几个条件同时满足时，按这个顺序返回。组织者也可以报名自己的活动。
多个用户同时报名同一个活动时，成功的次数不能超过名额，活动的 registered 要一直等于这个活动的报名记录数。同一个用户同时重复报名同一个活动时，只能成功一次，其他请求返回 409 AlreadyRegistered，registered 也只加一次。
MyRegistrations 分页返回当前用户的报名记录，按 registeredAt、id 都降序。
EventRevenue 只有这个活动的组织者能用，不是组织者返回 403 Forbidden；活动不存在返回 404 EventNotFound（先判断活动在不在）。它返回这个活动所有报名记录的 fee 之和，按报名记录上存的 fee 算，不用活动现在的 fee。没有报名记录时是 0。结果是 JSON 数字，不能有浮点误差。
EventRegistrationCount 的权限和错误跟 EventRevenue 一样，返回这个活动的报名记录条数，是 JSON 整数，没有报名记录时是 0。
EventView 只有 id、organizer、title、startsAt、capacity、registered、remaining、fee 这几个字段，RegistrationView 只有 id、event、attendee、fee、registeredAt，其中 organizer、event、attendee 是数值主键。
被拒绝的请求不能改动已有的行，也不能新增业务记录；成功时只改动这个操作该改的行（CreateEvent 新增一个活动，Register 新增一条报名记录并让该活动的 registered 加一）。声明的错误按接口合同返回 code、message、fields。
所有路由、请求字段和响应字段都按接口合同来。路径里的字段值以 URL 为准，不能被请求体里的同名字段替换。请求体不能指定组织者或 registered。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
