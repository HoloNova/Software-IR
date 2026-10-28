- 在给定软件上增加课程报名关联目录查询。

- 保留课程、学生、报名数据及原有响应字段定义。

- SearchCourseEnrollments：通过 GET /api/search-course-enrollments 查询至少有一条 ACTIVE 报名的课程。

- 每课仅出现一次，ACTIVE 报名必须属于当前这门课。

- 课程入选与排序：只有 CANCELLED 报名或没有报名的课程不入选。

- 结果按 code 升序分页。

- 课程字段：返回 id、code、name、capacity、doubledCapacity、enrollments。

- doubledCapacity 等于当前课程 capacity 的两倍。

- enrollments：包含当前课程全部报名，不限 ACTIVE。

- 按报名 id 升序且不截断，即使 30 条也全部返回。

- 报名与学生字段：报名含 id、status、active、student，active 表示该报名自身 status 是否为 ACTIVE。

- student 含 id、studentNo、name、label，label 等于该学生 name，不得串用其他课程或学生的值。

- 响应结构：total、page、size、records。

- total 统计匹配课程数，而不是报名数。

- 分页规则：page 默认 1、范围 1～10000，size 默认 20、范围 1～100。

- 超过末页返回 200、空 records 并保留真实 total。

- page=0 返回 400 InvalidPage。

- 有课程结果的读取次数：一次请求恰好 4 次业务表读取，分别为总数、当前页、按本页课程编号集合批量读取报名、按所需学生编号集合批量读取学生。

- 空页与非法分页：空页仅执行总数、当前页这前 2 次读取。

- 非法分页不读取业务表。

- 额外读取限制：不得逐门课或逐名学生额外读取。

- 其他读取仅允许连接会话变量探测。

- 数据库筛选约束：课程筛选必须体现与当前课程关联的存在性检查。

- 报名、学生的批量读取必须按已取得的编号集合筛选。

- 事务与写入限制：一次请求的全部读取须处于同一个只读事务，查询不得改变任何表。

返回完整的 sir 0.3 文档，不要 Java、SQL 或解释。
