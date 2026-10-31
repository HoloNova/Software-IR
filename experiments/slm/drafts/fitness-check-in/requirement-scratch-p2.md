身份认证：除 ListPrograms 外，所有接口通过 Authorization: Bearer 访问令牌识别当前用户；令牌缺少、格式错误、未知或过期时均返回空体 401；ListPrograms 允许匿名访问。
用户角色：分为会员和教练，保存在 user 表的 role 列，值为 MEMBER 或 COACH。
ListPrograms：分页返回项目的 id 和 name，按 name、id 均升序。
分页：page、size 可分别省略，默认值分别为 1、20；有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage。
分页响应：total、page、size、records；total 为全部匹配记录数，越界页仍返回正确 total。
LogWorkout：接收 programId 和 hours，为当前用户记录一次打卡，成功返回 201 和接口合同中的 CheckInView；打卡时间为服务器处理请求的时刻。
hours 规则：0.25 到 12 小时之间的数，最多两位小数；缺少、null、超出范围或超过两位小数时均返回 400 INVALID_REQUEST 和 hours 字段错误。
programId 规则：缺少或 null 时返回 400 INVALID_REQUEST 和 programId 字段错误；项目不存在时返回 404 ProgramNotFound。
MyMonthVisits、MyMonthHours、MyMonthAverage：分别返回当前用户在 month 所指自然月内的打卡次数、总时长和平均时长。
month 规则：格式为 YYYY-MM；自然月按 Asia/Shanghai 时区计算，范围从当月 1 日 00:00（含）到下月 1 日 00:00（不含）；month 缺少、格式不对或超出可表示范围时均返回 400 BadPeriod。
数值规则：次数为 JSON 整数；总时长和平均时长为 JSON 数字，不能有浮点误差；平均时长按两位小数四舍五入（0.5 进位）；该月没有记录时三个值都为 0。
CoachCheckIns：仅限教练使用，非教练返回 403 Forbidden。
筛选条件：member（会员 id）和 program（项目 id），两者均可省略，省略即不限制；给出不存在的 id 时返回空页而不是 404。
结果：按 checkedInAt、id 均降序分页，返回 CheckInView。
CoachMonthVisits：仅限教练使用，非教练返回 403 Forbidden；返回所有会员在 month 所指自然月内的打卡次数；可按 program 筛选，省略即不限制；month 的规则和错误与 MyMonthVisits 相同。
CheckInView：仅包含 id、member、program、hours、checkedInAt，其中 member、program 是数值主键。
ProgramView：仅包含 id、name。
写入约束：所有拒绝都不能改变已有行或新增业务记录；成功时只新增 LogWorkout 的一行。
错误格式：声明错误返回接口合同中的 code、message、fields。
接口约定：所有路由、请求字段和响应字段采用接口合同；当前用户只由令牌决定，不能通过请求字段冒充其他用户。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
