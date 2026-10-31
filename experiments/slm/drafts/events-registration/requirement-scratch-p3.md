这是一个活动报名系统，组织者发布活动，用户报名缴费。除了任何人都能匿名访问的 ListEvents，其他接口都用 Authorization: Bearer 访问令牌确认当前用户，令牌缺少、格式错误、未知或已过期时返回空体 401。
组织者发布活动时调用 CreateEvent，提交 title、startsAt、capacity、fee，系统创建一个以当前用户为组织者的活动，成功返回 201 和接口合同中的 EventView，初始 registered 为 0。title 必须是 1 到 100 个字符的非空白文本，capacity 必须是不小于 1 的整数，fee 必须是不小于 0、最多 8 位整数和 2 位小数的金额（0 到 99999999.99）；任何字段缺少、为 null、越界或超过两位小数，都返回 400 INVALID_REQUEST 和对应字段错误。startsAt 不要求晚于当前时刻。
用户浏览活动时，ListEvents 分页返回尚未开始（startsAt 晚于当前时刻）的活动，按 startsAt、id 都升序排列；名额已满的活动也会列出，EventView 中的 remaining 等于 capacity 减 registered。
翻页时，page 和 size 都可以单独省略，默认分别是 1 和 20；page 只能在 1 到 10000 之间，size 只能在 1 到 100 之间，超出就返回 400 BadPage。每页响应都带 total、page、size、records，其中 total 是全部匹配记录数，即使请求的页已经越界，total 也要正确。
用户报名活动时调用 Register，用路径中的 id 指明活动，系统为当前用户报名，成功返回 201 和接口合同中的 RegistrationView。系统为当前用户和该活动保存唯一一条报名记录，记录的 fee 取报名时刻该活动的 fee，registeredAt 取服务器处理请求的时刻，并把该活动的 registered 加一。活动不存在时返回 404 EventNotFound；活动已开始（startsAt 不晚于当前时刻）时返回 409 RegistrationClosed；这个用户已经报名过该活动时返回 409 AlreadyRegistered；名额已满（registered 不小于 capacity）时返回 409 EventFull；几种情况同时出现时按上述顺序返回。组织者报名自己的活动也是允许的。
热门活动开放报名时，会有很多用户同时报名同一个活动：成功的人数不能超过名额，活动的 registered 始终等于该活动的报名记录数。同一个用户同时提交多次报名同一活动时，恰好一次成功，其余返回 409 AlreadyRegistered，registered 也只增加一次。
用户查看自己的报名时，MyRegistrations 按 registeredAt、id 都降序分页返回当前用户的报名记录。
组织者查看活动收入时调用 EventRevenue，只有该活动的组织者可以用，其他人得到 403 Forbidden；活动不存在时返回 404 EventNotFound（先判断活动是否存在）。收入是该活动全部报名记录的 fee 之和，按每条报名记录上保存的 fee 计算，而不是活动当前的 fee；没有报名记录时为 0；结果是不能有浮点误差的 JSON 数字。
组织者查看报名人数时调用 EventRegistrationCount，权限和错误与 EventRevenue 相同，返回该活动的报名记录条数（JSON 整数），没有报名记录时为 0。
返回的活动 EventView 只含 id、organizer、title、startsAt、capacity、registered、remaining、fee，报名记录 RegistrationView 只含 id、event、attendee、fee、registeredAt，其中 organizer、event、attendee 都是数值主键。
无论请求因为什么被拒绝，都不能改动已有行或新增业务记录；请求成功时，也只改动这个操作应该改动的行（CreateEvent 新增一个活动，Register 新增一条报名记录并使该活动的 registered 加一）。声明的错误按接口合同返回 code、message、fields。
所有路由、请求字段和响应字段都以接口合同为准；路径中的字段值以 URL 为准，不能被请求体里的同名字段替换；请求体也不能指定组织者或 registered。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
