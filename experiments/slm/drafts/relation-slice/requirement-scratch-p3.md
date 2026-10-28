查课的人需要从有 ACTIVE 报名的课程出发，查看每门课的全部报名及对应学生。

用户通过 GET /api/search-course-enrollments 调用 SearchCourseEnrollments，结果只包括至少有一条 ACTIVE 报名的课程；该 ACTIVE 报名必须属于这门课本身，每门课在结果中只出现一次。

某门课如果只有 CANCELLED 报名，或根本没有报名，就不向用户列出；入选课程按 code 升序分页。

用户看到的每门课包含 id、code、name、capacity、doubledCapacity 及 enrollments，其中 doubledCapacity 是该课程 capacity 的两倍。

展开一门课的 enrollments 时，用户要看到该课全部报名，而不只是 ACTIVE；报名按 id 升序列出，不能截断，30 条也要全部返回。

每条报名展示 id、status、active、student，active 由这条报名自己的 status 是否为 ACTIVE 决定；对应的 student 展示 id、studentNo、name、label，label 等于这个学生的 name，不能混入其他课程或其他学生的值。

用户翻页时收到 total、page、size、records，total 告诉用户有多少门匹配课程，而不是有多少条报名。

用户不填 page 时用 1，不填 size 时用 20，允许的范围分别是 1～10000、1～100；翻过末页仍得到 200、空 records 和真实 total，提交 page=0 则得到 400 InvalidPage。

一次请求只要有课程结果，业务表读取就必须恰好 4 次，分别取得总数、当前页、按本页课程编号集合批量取得报名、按所需学生编号集合批量取得学生。

用户翻到空页时，只允许总数和当前页这前 2 次业务表读取；如果分页非法，则不读取业务表。

这次查询不能因逐门课或逐名学生而增加读取，除此之外的读取只能用于连接会话变量探测。

筛选课程时，数据库必须检查与当前课程关联的符合条件的报名是否存在；批量读取报名和学生时，也必须用已取得的编号集合筛选，不能串到无关数据。

用户这一次查询的所有读取必须处于同一个只读事务，整个查询不能改变任何表。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
