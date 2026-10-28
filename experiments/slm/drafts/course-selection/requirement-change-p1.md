在给定软件上加管理员课程建档、先修配置、发布和通过成绩登记。账号登录、课程公开查询、选退课、本人选课列表和教师名单的现有行为都保留。

建课程、配先修、发布、记通过成绩都只给 ADMIN。学生做这些事一律返回 403 Forbidden。所有拒绝都不能改课程、选课、先修和成绩记录。

建课程传 code、name、teacherId、capacity、weekday、startPeriod、endPeriod。成功返回 201 和课程字段，一开始 published=false、enrolled=0。

weekday 只能是 1～7，startPeriod 只能是 1～12。endPeriod 不能超过 13，还得大于 startPeriod。capacity 至少为 1。

这些数值漏了或传 null，返回 400 INVALID_REQUEST，fields 写出原字段路径和 NotNull。数值越界、时段为空或倒序，都返回 400，不能落库。

课没发布时，管理员能加、删任意多条直接先修。重复关系返回 409 PrerequisiteExists。把自身或尚未发布的课设成先修，返回 400 InvalidPrerequisite。

发布成功返回 200。发布后再加删先修，或者重复发布，都返回 409 CoursePublished，既定课程要求不能变。

管理员给指定 studentId、courseId 追加通过记录，成功返回 201，重复返回 409 CompletionExists。

后续选课判断要马上用上刚提交的通过记录。学生不能自己报通过。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
