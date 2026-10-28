SearchCourseEnrollments 用 GET /api/search-course-enrollments。只找至少有一条 ACTIVE 报名的课程，每门课只出一次。那条 ACTIVE 报名必须真属于这门课。

只有 CANCELLED 报名或者没报名的课别列进来。按 code 升序分页。

每项给 id、code、name、capacity、doubledCapacity、enrollments。doubledCapacity 就是这门课 capacity 的两倍。

enrollments 要带这门课的全部报名，不只 ACTIVE。按报名 id 升序，别截断，就算有 30 条也全部返回。

每条报名给 id、status、active、student，active 看它自己的 status 是不是 ACTIVE。student 给 id、studentNo、name、label，label 等于这个学生的 name。别串用其他课或其他学生的值。

返回 total、page、size、records。total 数匹配的课程，不数报名。

page 默认 1，范围 1～10000；size 默认 20，范围 1～100。翻过末页返回 200、空 records，真实 total 要留着。page=0 返回 400 InvalidPage。

有课程结果的一次请求，业务表必须恰好读 4 次：总数、当前页、按本页课程编号集合批量读报名、按所需学生编号集合批量读学生。

空页只做前 2 次读取，也就是总数和当前页。分页非法就别读业务表。

别逐门课、逐名学生再额外读取。其他读取只允许探测连接会话变量。

数据库筛课程时，要检查当前课自己有没有符合条件的报名。批量读报名和学生，都得按已经拿到的编号集合筛。

一次请求里的全部读取要放在同一个只读事务里。任何表都不能改。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
