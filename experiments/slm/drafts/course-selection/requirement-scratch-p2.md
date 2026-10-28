- 账号注册：接收 name、password，返回 id、name、role，默认角色为 STUDENT。

- 账号登录与退出：登录返回访问令牌及刷新令牌，期限分别为 15 分钟、7 天。

- 刷新令牌仅能使用一次，退出使刷新令牌失效。

- 身份校验：需身份的路由只接受有效访问令牌，匿名请求返回 401。

- 公开课程搜索：接收 keyword、page、size，仅列 published=true 且 name 或 code 字面包含 keyword 的课程，按 code、id 升序分页。

- 课程搜索响应字段：含 teacher、capacity、enrolled、remaining、full 及周课表字段。

- remaining=capacity-enrolled，full 表示人数已达容量。

- 管理权限：创建课程、配置先修、发布、记录通过成绩仅限 ADMIN。

- 学生操作均返回 403 Forbidden，所有拒绝不得改变课程、选课、先修、成绩记录。

- 创建课程：接收 code、name、teacherId、capacity、weekday、startPeriod、endPeriod。

- 成功返回 201 及课程字段，初始 published=false、enrolled=0。

- 课程数值范围：weekday 为 1～7，startPeriod 为 1～12，endPeriod 不超过 13 且大于 startPeriod，capacity 至少为 1。

- 课程数值校验：capacity、weekday、startPeriod、endPeriod 缺失或 null 时返回 400 INVALID_REQUEST，fields 指明原字段路径及 NotNull。

- 范围错误或空/倒序时段返回 400，不得落库。

- 直接先修配置：管理员可为未发布课程增删任意多条。

- 重复关系返回 409 PrerequisiteExists，自身或尚未发布的先修课返回 400 InvalidPrerequisite。

- 课程发布：成功返回 200。

- 发布后增删先修或重复发布均返回 409 CoursePublished，不得改变既定课程要求。

- 通过成绩登记：管理员为指定 studentId、courseId 追加通过记录，成功返回 201，重复返回 409 CompletionExists。

- 通过记录生效：刚提交的记录须立即影响后续选课，学生不得自报通过。

- 本人选课：接收课程 id，成功返回 201，保存唯一的学生/课程选课记录，并占一个座位。

- 选课前提：未知课程返回 404 CourseNotFound，未发布返回 409 CourseNotOpen，重复选课返回 409 AlreadyEnrolled。

- 须通过所有直接先修，否则返回 409 MissingPrerequisites，数量相等但课程不同的成绩集合不能满足要求。

- 时间冲突：同一学生同一星期几的半开节次区间重叠时返回 409 TimeConflict。

- 首尾相接允许，不同星期几互不冲突。

- 同学生并发选课：两门重叠课只能一个成功，另一个返回 409 TimeConflict。

- 等待该学生已有事务提交后，须重新检查冲突并看到刚提交的选课。

- 座位竞争：八名学生竞争三个座位，恰好三个返回 201、五个返回 409 CourseFull。

- 退课：仅限本人，别人操作返回 403 Forbidden。

- 本人成功返回 200，删除记录并释放一个座位，重复返回 404 EnrollmentNotFound。

- 八路并发退同一条记录恰好一个 200、七个 404，退后可重选。

- 座位计数不变量：课程 enrolled 始终等于实际选课数，且处于 0～capacity。

- 晚失败与异常计数：选课晚发生唯一冲突返回 409 AlreadyEnrolled，撤销座位增加。

- 退课晚发生外键删除失败返回 500，撤销座位减少。

- 旧计数为零但仍有选课记录时返回 409 EnrollmentCounterMismatch，不得删记录。

- 失败写入限制：所有失败均不改变课程、选课、先修、成绩这四类业务记录。

- 学生级串行约束：其他事务锁住该学生行时，选课必须真实等待，不能绕过学生级串行检查。

- 本人选课列表按 createdAt、id 降序。

- 教师名单查询仅限教师自己的课程，按 createdAt、id 升序，越权返回 403 Forbidden。

- 统一分页：响应为 total、page、size、records，page/size 默认 1/20、上限 10000/100。

- 非法分页返回 400 BadPage。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
