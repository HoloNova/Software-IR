注册传 name、password，返回 id、name、role，role 默认 STUDENT。

登录给访问令牌和刷新令牌，分别 15 分钟、7 天到期。刷新令牌用一次就失效，退出也要让刷新令牌失效。

要身份的路由只认有效访问令牌。匿名返回 401。

公开搜课传 keyword、page、size。只列 published=true，且 name 或 code 字面包含 keyword 的课。按 code、id 升序分页。

搜索结果要带 teacher、capacity、enrolled、remaining、full 和周课表字段。remaining=capacity-enrolled，full 就是人数已经到容量。

建课程、配先修、发布、记通过成绩都只给 ADMIN。学生做这些事一律返回 403 Forbidden。所有拒绝都不能改课程、选课、先修和成绩记录。

建课程传 code、name、teacherId、capacity、weekday、startPeriod、endPeriod。成功返回 201 和课程字段，一开始 published=false、enrolled=0。

weekday 只能是 1～7，startPeriod 只能是 1～12。endPeriod 不能超过 13，还得大于 startPeriod。capacity 至少为 1。

这些数值漏了或传 null，返回 400 INVALID_REQUEST，fields 写出原字段路径和 NotNull。数值越界、时段为空或倒序，都返回 400，不能落库。

课没发布时，管理员能加、删任意多条直接先修。重复关系返回 409 PrerequisiteExists。把自身或尚未发布的课设成先修，返回 400 InvalidPrerequisite。

发布成功返回 200。发布后再加删先修，或者重复发布，都返回 409 CoursePublished，既定课程要求不能变。

管理员给指定 studentId、courseId 追加通过记录，成功返回 201，重复返回 409 CompletionExists。

后续选课判断要马上用上刚提交的通过记录。学生不能自己报通过。

本人选课传课程 id。成功返回 201，保存唯一的学生/课程选课记录，占一个座位。

课未知返回 404 CourseNotFound，没发布返回 409 CourseNotOpen，重复选返回 409 AlreadyEnrolled。所有直接先修都得通过，否则返回 409 MissingPrerequisites。通过课数量一样但课程不同，也不算满足。

同一学生同一星期几的课，半开节次区间重叠就返回 409 TimeConflict。首尾相接能选，不同星期几不算冲突。

同一学生同时选两门重叠课，只能一个成功，另一个返回 409 TimeConflict。等这个学生已有事务提交后，要重新查冲突，能看到刚提交的选课。

八名学生抢三个座位，必须正好三个 201、五个 409 CourseFull。

退课只准本人，别人退返回 403 Forbidden。本人退成功返回 200，删记录、释放一个座位；重复退返回 404 EnrollmentNotFound。八路同时退同一条，只能一个 200、七个 404。退完还能重选。

课程 enrolled 要一直等于实际选课数，并且在 0～capacity 内。

选课晚发生唯一冲突，返回 409 AlreadyEnrolled，把加上的座位撤销。退课晚发生外键删除失败，返回 500，把减掉的座位撤销。旧计数是零却还有选课记录，返回 409 EnrollmentCounterMismatch，别删记录。

所有失败都不能改课程、选课、先修和成绩这四类业务记录。

别的事务锁着这个学生行时，选课必须真的等，不能绕过学生级串行检查。

本人选课列表按 createdAt、id 降序。教师只能查自己课程的名单，按 createdAt、id 升序；越权返回 403 Forbidden。

分页都返回 total、page、size、records。page/size 默认 1/20，上限 10000/100。非法就返回 400 BadPage。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
