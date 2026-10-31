在给定软件上补回 MyMonthHours、MyMonthAverage、CoachCheckIns、CoachMonthVisits，保留 ListPrograms、LogWorkout、MyMonthVisits 的现有行为。
除 ListPrograms 外，所有接口使用 Authorization: Bearer 访问令牌识别当前用户，缺少、格式错误、未知或过期的令牌均返回空体 401，ListPrograms 允许匿名访问；用户分为会员和教练，角色保存在 user 表的 role 列，值为 MEMBER 或 COACH。
MyMonthHours 和 MyMonthAverage 分别返回当前用户在 month 指定的自然月内的总时长和平均时长，month 写成 YYYY-MM，自然月按 Asia/Shanghai 时区计算，从当月 1 日 00:00 起（含）到下月 1 日 00:00 止（不含），month 缺少、格式不对或超出可表示范围均返回 400 BadPeriod，总时长和平均时长是 JSON 数字且不能有浮点误差，平均时长按两位小数四舍五入（0.5 进位），该月没有记录时都是 0。
CoachCheckIns 只允许教练使用，非教练返回 403 Forbidden，可按 member（会员 id）和 program（项目 id）筛选，两者都可省略，省略就不限制，给出不存在的 id 返回空页而不是 404，按 checkedInAt、id 都降序分页返回接口合同中的 CheckInView。
CoachMonthVisits 只允许教练使用，非教练返回 403 Forbidden，返回所有会员在 month 指定的自然月内的打卡次数，可按 program 筛选，省略就不限制，month 的规则和错误与 MyMonthVisits 相同。
分页参数 page、size 可分别省略，默认分别为 1、20，有效范围分别为 1 到 10000、1 到 100，超出范围返回 400 BadPage，响应为 total、page、size、records，total 是全部匹配记录数，越界页仍返回正确 total。
所有拒绝都不能改变已有行或新增业务记录，成功只新增 LogWorkout 的一行，声明错误返回接口合同中的 code、message、fields，所有路由、请求字段和响应字段采用接口合同，当前用户只由令牌决定，不能通过请求字段冒充其他用户。
返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
