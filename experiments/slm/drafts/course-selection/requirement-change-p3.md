管理员需要把课程建档、设好先修后发布，并登记学生已经通过的课程。

在给定软件上补上管理员课程建档、先修配置、发布与通过成绩登记，同时保留账号登录、课程公开查询、选退课、本人选课列表和教师名单的现有行为。

管理员只有具备 ADMIN 才能创建课程、配置先修、发布和记录通过成绩；学生尝试这些操作都得到 403 Forbidden，所有拒绝均不能改变课程、选课、先修或成绩记录。

管理员建档时提交 code、name、teacherId、capacity、weekday、startPeriod、endPeriod，创建成功得到 201 和课程字段，新课起初 published=false、enrolled=0。

管理员填写课表时，weekday 必须在 1～7，startPeriod 必须在 1～12，endPeriod 不得超过 13 且必须大于 startPeriod，capacity 不能小于 1。

这些数值若没填或填为 null，创建课程返回 400 INVALID_REQUEST，fields 必须指出原字段路径及 NotNull；若数值超范围或时段为空、倒序，则返回 400，不能保存到数据库。

在课程发布前，管理员可以增加或删除任意多条直接先修要求；关系重复时得到 409 PrerequisiteExists，先修课是自身或尚未发布的课程时得到 400 InvalidPrerequisite。

管理员成功发布课程后收到 200；此后再增加或删除先修、再次发布，都只能得到 409 CoursePublished，已经确定的课程要求不能改变。

管理员为指定的 studentId、courseId 追加通过记录时，成功得到 201，重复登记则得到 409 CompletionExists。

通过记录一经提交，就必须立即影响随后的选课判断，学生不能自行申报通过。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
