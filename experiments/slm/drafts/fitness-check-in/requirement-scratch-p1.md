ListPrograms 不用登录就能访问。其他接口都靠 Authorization: Bearer 访问令牌认出当前用户，令牌没带、格式不对、查不到或已过期，一律返回空体 401。用户分会员和教练两种，角色存在 user 表的 role 列，取值是 MEMBER 或 COACH。
ListPrograms 分页返回项目的 id 和 name，按 name、id 都升序。
分页参数 page、size 都可以单独不传，默认 page 是 1、size 是 20。page 的有效范围是 1 到 10000，size 是 1 到 100，超出范围返回 400 BadPage。响应包含 total、page、size、records，total 是全部匹配的记录数，请求的页超出最后一页时也要返回正确的 total。
LogWorkout 传 programId 和 hours，给当前用户记一次打卡，成功返回 201 和接口合同中的 CheckInView。打卡时间就是服务器处理请求的时刻。hours 是 0.25 到 12 小时之间的数，最多两位小数；没传、传 null、超出范围或超过两位小数，都返回 400 INVALID_REQUEST 和 hours 字段错误。programId 没传或传 null，返回 400 INVALID_REQUEST 和 programId 字段错误。项目不存在返回 404 ProgramNotFound。
MyMonthVisits、MyMonthHours、MyMonthAverage分别返回当前用户在 month 这个自然月里的打卡次数、总时长和平均时长。month 的格式是 YYYY-MM。自然月按 Asia/Shanghai 时区算，从当月 1 日 00:00 开始（包含），到下月 1 日 00:00 结束（不包含）。month 没传、格式不对或超出可表示范围，都返回 400 BadPeriod。次数是 JSON 整数；总时长和平均时长是 JSON 数字，不能有浮点误差；平均时长保留两位小数，四舍五入（0.5 进位）。这个月没有记录时，三个值都是 0。
CoachCheckIns 只有教练能用，不是教练就返回 403 Forbidden。可以按 member（会员 id）和 program（项目 id）筛选，两个都能不传，不传就不限制。传了不存在的 id，返回空页，不返回 404。结果按 checkedInAt、id 都降序分页，返回 CheckInView。
CoachMonthVisits 只有教练能用，不是教练就返回 403 Forbidden。它返回所有会员在 month 这个自然月里的打卡次数，可以按 program 筛选，不传就不限制。month 的规则和错误跟 MyMonthVisits 一样。
CheckInView 只有 id、member、program、hours、checkedInAt 这几个字段，其中 member、program 是数值主键。ProgramView 只有 id、name。
被拒绝的请求不能改动已有的行，也不能新增业务记录；成功时只有 LogWorkout 会新增一行。声明的错误按接口合同返回 code、message、fields。
所有路由、请求字段和响应字段都按接口合同来。当前用户只由令牌决定，不能用请求字段冒充别的用户。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
