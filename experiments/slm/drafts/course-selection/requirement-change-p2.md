- 在给定软件上增加管理员课程建档、先修配置、发布及通过成绩登记。

- 保留账号登录、课程公开查询、选退课、本人选课列表和教师名单的现有行为。

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

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
