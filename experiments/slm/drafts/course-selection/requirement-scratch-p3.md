学生需要查课、选课和退课，教师需要看自己课程的名单，管理员负责课程与通过成绩的登记。

学生提交 name、password 注册账号后，得到 id、name、role，默认 role 为 STUDENT。

用户登录时获得有效期分别为 15 分钟、7 天的访问令牌和刷新令牌；刷新令牌使用一次后失效，用户退出时也要使刷新令牌失效。

访问需要身份的路由时，用户必须携带有效访问令牌，匿名访问得到 401。

学生用 keyword、page、size 公开搜索课程时，只能看到 published=true 且 name 或 code 字面包含 keyword 的课，结果按 code、id 升序分页。

学生看到的课程信息包含 teacher、capacity、enrolled、remaining、full 和周课表字段，其中 remaining=capacity-enrolled，full 表示人数已经达到容量。

管理员只有具备 ADMIN 才能创建课程、配置先修、发布和记录通过成绩；学生尝试这些操作都得到 403 Forbidden，所有拒绝均不能改变课程、选课、先修或成绩记录。

管理员建档时提交 code、name、teacherId、capacity、weekday、startPeriod、endPeriod，创建成功得到 201 和课程字段，新课起初 published=false、enrolled=0。

管理员填写课表时，weekday 必须在 1～7，startPeriod 必须在 1～12，endPeriod 不得超过 13 且必须大于 startPeriod，capacity 不能小于 1。

这些数值若没填或填为 null，创建课程返回 400 INVALID_REQUEST，fields 必须指出原字段路径及 NotNull；若数值超范围或时段为空、倒序，则返回 400，不能保存到数据库。

在课程发布前，管理员可以增加或删除任意多条直接先修要求；关系重复时得到 409 PrerequisiteExists，先修课是自身或尚未发布的课程时得到 400 InvalidPrerequisite。

管理员成功发布课程后收到 200；此后再增加或删除先修、再次发布，都只能得到 409 CoursePublished，已经确定的课程要求不能改变。

管理员为指定的 studentId、courseId 追加通过记录时，成功得到 201，重复登记则得到 409 CompletionExists。

通过记录一经提交，就必须立即影响随后的选课判断，学生不能自行申报通过。

学生为自己选课时提交课程 id，成功得到 201，系统保存唯一的学生/课程选课记录，同时占用一个座位。

学生选择未知课程会得到 404 CourseNotFound，选择未发布课程会得到 409 CourseNotOpen，重复选择会得到 409 AlreadyEnrolled；只有通过所有直接先修课程才符合要求，否则返回 409 MissingPrerequisites，即使通过数量相等，课程不同也不能冒充满足先修。

同一学生在同一星期几的课程若半开节次区间重叠，选课返回 409 TimeConflict；首尾相接的课程允许选择，不同星期几的课互不冲突。

一个学生并发选择两门重叠课时，只能成功一门，另一门返回 409 TimeConflict；请求等到该学生已有事务提交后，必须重新检查冲突，并看到刚提交的选课。

当八名学生竞争三个座位时，恰好三人得到 201，其余五人得到 409 CourseFull。

学生只能退自己的课，别人代退得到 403 Forbidden；本人成功退课得到 200，记录被删除且释放一个座位，重复退课得到 404 EnrollmentNotFound；八路同时退同一条记录时只能一个 200、七个 404，退课后仍可重新选课。

无论选课还是退课，课程 enrolled 都必须始终等于实际选课数，并保持在 0～capacity 内。

若选课在较晚阶段发生唯一冲突，返回 409 AlreadyEnrolled，并撤销座位增加；若退课在较晚阶段发生外键删除失败，返回 500，并撤销座位减少；若旧计数已为零却仍有选课记录，则返回 409 EnrollmentCounterMismatch，不能删除记录。

任何操作失败，都不能改变课程、选课、先修和成绩这四类业务记录。

如果其他事务已经锁住该学生行，学生的选课请求必须真实等待，不能绕开学生级串行检查。

学生看本人的选课列表时，按 createdAt、id 降序排列；教师只能查自己课程的名单，名单按 createdAt、id 升序排列，越权查询返回 403 Forbidden。

用户看到的分页响应统一包含 total、page、size、records，page/size 默认 1/20，上限 10000/100，非法分页返回 400 BadPage。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
